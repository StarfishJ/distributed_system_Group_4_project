package client_part2;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runnable Worker: one WebSocket to /chat/{roomId}, consumes from queue and sends messages.
 * Part 2: records per-message latency (send → echo) for P95/P99 analysis.
 *
 * Pipelining: up to PIPELINE_SIZE messages in flight per connection (send without waiting for each echo).
 * Assumes server echoes in FIFO order so we match responses to sends by order and compute per-message latency.
 *
 * - Retry on timeout: if oldest in-flight exceeds ECHO_TIMEOUT_MS, close, fail all in-flight, reconnect.
 * - Track failed messages via Metrics.recordFail().
 * - On success, latency is in CSV via recordMessageMetric; percentiles printed after run.
 * 
 * FIX: WebSocketClient instances cannot be reused after close - must create new instance for reconnection.
 */
public class Worker implements Runnable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile boolean loggedFirstError;
    private static volatile boolean loggedFirstClose;

    /** Drop duplicate broadcast lines (same messageId) to avoid double-counting lag metrics. */
    private static final java.util.Set<String> SEEN_BROADCAST_IDS = ConcurrentHashMap.newKeySet();
    private static final int BROADCAST_DEDUP_MAX = 200_000;

    private final BlockingDeque<ClientMessage> queue;
    private final String serverBaseUrl;
    private final Metrics metrics;
    private final int roomId;
    private final int maxMessages;

    /** Per {@code messageId}: count of SERVER_BUSY responses; capped to avoid starving the queue via offerFirst. */
    private final ConcurrentHashMap<String, AtomicInteger> serverBusyRetries = new ConcurrentHashMap<>();
    private volatile boolean loggedServerBusyExhausted;

    public Worker(BlockingQueue<ClientMessage> queue, String serverBaseUrl, Metrics metrics, int workerId) {
        this(queue, serverBaseUrl, metrics, workerId, 0);
    }

    public Worker(BlockingQueue<ClientMessage> queue, String serverBaseUrl, Metrics metrics, int workerId, int maxMessages) {
        this.queue = (BlockingDeque<ClientMessage>) queue;
        this.serverBaseUrl = toWsUrl(serverBaseUrl);
        this.metrics = metrics;
        this.roomId = (workerId % ClientConfig.getNumRooms()) + 1;
        this.maxMessages = maxMessages <= 0 ? 0 : maxMessages;
    }

    /** Extracts a quoted JSON string field value, e.g. {@code "messageId":"abc"} → {@code abc}. */
    private static String extractJsonStringField(String line, String field) {
        if (line == null || field == null) return null;
        String key = "\"" + field + "\":\"";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = line.indexOf('"', start);
        if (end <= start) return null;
        return line.substring(start, end);
    }

    private static String toWsUrl(String url) {
        if (url == null) return "ws://localhost:8080";
        if (url.startsWith("http://")) return "ws://" + url.substring(7);
        if (url.startsWith("https://")) return "wss://" + url.substring(8);
        if (!url.startsWith("ws")) return "ws://" + url;
        return url;
    }

    private static boolean sleepMs(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static final class SentRecord {
        final long sendTimeMs;
        final String messageType;
        final ClientMessage originalMsg;

        SentRecord(long sendTimeMs, ClientMessage msg) {
            this.sendTimeMs = sendTimeMs;
            this.originalMsg = msg;
            this.messageType = msg != null ? msg.getMessageType() : "UNKNOWN";
        }
    }

    /**
     * Creates a new WebSocketClient instance. Must create new instance for each connection
     * because Java-WebSocket clients cannot be reused after close.
     */
    private WebSocketClient createClient(URI uri, Semaphore pipelinePermits, 
            ConcurrentLinkedQueue<SentRecord> pendingSends, AtomicInteger successCount,
            AtomicLong lastResponseTimeMs) {
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                metrics.recordConnection();
            }

            @Override
            public void onMessage(String text) {
                if (text == null || text.isEmpty()) return;
                
                // Server now sends back batches separated by \n
                String[] batch = text.split("\n");
                for (String line : batch) {
                    if (line.isEmpty()) continue;
                    
                    boolean isOk = line.contains("\"status\":\"OK\"") || line.contains("\"status\":\"QUEUED\"");
                    boolean isError = line.contains("\"status\":\"ERROR\"");
                    
                    // FIX: Only poll and calculate latency if this is a status response from the server.
                    // Broadcast messages (which lack "status") should be ignored.
                    if (!isOk && !isError) {
                        String bMid = extractJsonStringField(line, "messageId");
                        if (bMid != null && !bMid.isEmpty()) {
                            if (SEEN_BROADCAST_IDS.size() >= BROADCAST_DEDUP_MAX) {
                                SEEN_BROADCAST_IDS.clear();
                            }
                            if (!SEEN_BROADCAST_IDS.add(bMid)) {
                                continue;
                            }
                        }
                        // Broadcast: extract timestamp to calculate Consumer Lag (string search, no full parse).
                        int tsIndex = line.indexOf("\"timestamp\":");
                        if (tsIndex != -1) {
                            int start = tsIndex + 12;
                            int end = line.indexOf(',', start);
                            if (end == -1) end = line.indexOf('}', start);
                            if (end != -1) {
                                try {
                                    long sentTime = Long.parseLong(line.substring(start, end).trim());
                                    metrics.recordConsumerLag(System.currentTimeMillis() - sentTime);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        continue;
                    }

                    if (isError && !loggedFirstError) {
                        loggedFirstError = true;
                        System.err.println("[Worker] First server ERROR response: " + line);
                    }

                    // Try to match each status response with an in-flight request
                    SentRecord rec = pendingSends.poll();
                    if (rec == null) {
                        continue;
                    }

                    pipelinePermits.release();
                    long now = System.currentTimeMillis();
                    lastResponseTimeMs.set(now);
                    long latencyMs = now - rec.sendTimeMs;
                    String msgType = rec.messageType;

                    String statusCode = isOk ? "OK" : (isError ? "ERROR" : "UNKNOWN");
                    
                    if (isOk) {
                        successCount.incrementAndGet();
                        metrics.recordSuccessWithDetails(roomId, msgType, latencyMs);
                        if (rec.originalMsg != null) {
                            String mid = rec.originalMsg.getMessageId();
                            if (mid != null && !mid.isEmpty()) {
                                serverBusyRetries.remove(mid);
                            }
                        }
                    } else {
                        if (line.contains("SERVER_BUSY")) {
                            // Backpressure: re-queue at front with same messageId (idempotent DB). Cap local retries for fairness.
                            if (rec.originalMsg != null) {
                                String mid = rec.originalMsg.getMessageId();
                                if (mid == null || mid.isEmpty()) {
                                    metrics.recordFail();
                                } else {
                                    int maxBusy = ClientConfig.getMaxServerBusyRetries();
                                    AtomicInteger ctr = serverBusyRetries.computeIfAbsent(mid, k -> new AtomicInteger(0));
                                    int n = ctr.incrementAndGet();
                                    if (n > maxBusy) {
                                        serverBusyRetries.remove(mid);
                                        metrics.recordFail();
                                        if (!loggedServerBusyExhausted) {
                                            loggedServerBusyExhausted = true;
                                            System.err.println("[Worker] SERVER_BUSY local retries exceeded (" + maxBusy
                                                    + ") for messageId=" + mid + " room=" + roomId);
                                        }
                                    } else {
                                        if (!queue.offerFirst(rec.originalMsg)) {
                                            ctr.decrementAndGet();
                                            metrics.recordFail();
                                        }
                                    }
                                }
                            }
                        } else {
                            metrics.recordFail();
                            if (isError) {
                                metrics.recordBusinessError();
                            }
                        }
                    }
                    
                    metrics.recordMessageMetric(now, msgType, latencyMs, statusCode, roomId);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!loggedFirstClose) {
                    loggedFirstClose = true;
                    System.err.println("[Worker] First connection close: code=" + code + " reason=" + reason + " remote=" + remote);
                }
            }

            @Override
            public void onError(Exception ex) {
                if (!loggedFirstError) {
                    // Do not set loggedFirstError to true here, as we want to log the first server ERROR response too
                    System.err.println("[Worker] Connection error: " + ex.getMessage());
                }
            }
        };
        client.setTcpNoDelay(true);
        return client;
    }

    /**
     * Attempts to connect with exponential backoff.
     * Returns connected client or null if all attempts failed.
     */
    private WebSocketClient connectWithRetry(URI uri, Semaphore pipelinePermits, 
            ConcurrentLinkedQueue<SentRecord> pendingSends, AtomicInteger successCount,
            AtomicLong lastResponseTimeMs) {
        int maxRetries = ClientConfig.getMaxConnectRetries();
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            long[] backoff = ClientConfig.getBackoffMs();
            if (attempt > 0) {
                long delay = backoff[Math.min(attempt - 1, backoff.length - 1)];
                if (sleepMs(delay)) return null;
            }
            try {
                WebSocketClient client = createClient(uri, pipelinePermits, pendingSends, successCount, lastResponseTimeMs);
                if (client.connectBlocking(ClientConfig.getConnectTimeoutSec(), TimeUnit.SECONDS)) {
                    lastResponseTimeMs.set(System.currentTimeMillis());
                    return client;
                } else {
                    metrics.recordConnectionFailure();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.recordConnectionFailure();
                return null;
            } catch (Exception e) {
                metrics.recordConnectionFailure();
            }
        }
        // All retries exhausted
        System.err.println("[Worker] Failed to connect to room " + roomId + " after " + ClientConfig.getMaxConnectRetries() + " attempts");
        return null;
    }

    @Override
    public void run() {
        Semaphore pipelinePermits = new Semaphore(ClientConfig.getPipelineSize());
        ConcurrentLinkedQueue<SentRecord> pendingSends = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong lastResponseTimeMs = new AtomicLong(System.currentTimeMillis());

        URI uri = URI.create(serverBaseUrl + "/chat/" + roomId);
        
        // Use AtomicReference to allow client replacement during reconnection
        AtomicReference<WebSocketClient> clientRef = new AtomicReference<>();
        
        // Initial connection
        WebSocketClient initialClient = connectWithRetry(uri, pipelinePermits, pendingSends, successCount, lastResponseTimeMs);
        if (initialClient == null) {
            System.err.println("[Worker] Failed initial connection to room " + roomId);
            return;
        }
        clientRef.set(initialClient);

        try {
            List<ClientMessage> batch = new ArrayList<>(ClientConfig.getBatchSize());
            while (true) {
                if (maxMessages > 0 && successCount.get() >= maxMessages) break;
                if (Thread.currentThread().isInterrupted()) break;

                batch.clear();
                ClientMessage first;
                try {
                    // Poll for the first message (aggressive polling for Super-Batching)
                    first = queue.poll(1, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (ClientMessage.isPoison(first)) break;
                batch.add(first);
                
                // Batching: try to pull up to BATCH_SIZE - 1 more messages immediately
                queue.drainTo(batch, ClientConfig.getBatchSize() - 1);

                WebSocketClient client = clientRef.get();
                StringBuilder batchBuilder = new StringBuilder();
                
                for (ClientMessage msg : batch) {
                    // For each message in the batch, acquire a pipeline permit
                    boolean acquired = false;
                    long acquireLoopStart = System.currentTimeMillis();
                    
                    while (!acquired) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (maxMessages > 0 && successCount.get() >= maxMessages) break;
                        
                        // DEADLOCK PROTECTION
                        long acquireTimeoutMs = ClientConfig.getAcquireLoopTimeoutMs();
                        if (System.currentTimeMillis() - acquireLoopStart > acquireTimeoutMs) {
                            System.err.println("[Worker] Stuck in acquire loop for " + (acquireTimeoutMs/1000) + "s, exiting worker for room " + roomId + 
                                " (queue size: " + queue.size() + ", pending sends: " + pendingSends.size() + ", pipeline permits: " + pipelinePermits.availablePermits() + ")");
                            while (pendingSends.poll() != null) {
                                pipelinePermits.release();
                                metrics.recordFail();
                            }
                            return;
                        }
                        
                        client = clientRef.get(); // Refresh in case of reconnect
                        
                        // SOFT TIMEOUT: Reconnect only if NO response has been received for 15s
                        boolean hasTimeout = !pendingSends.isEmpty() && (System.currentTimeMillis() - lastResponseTimeMs.get() > ClientConfig.getEchoTimeoutMs());
                        
                        if (!client.isOpen() || hasTimeout) {
                            if (client.isOpen()) client.close();
                            
                            List<SentRecord> toReQueue = new ArrayList<>();
                            SentRecord pending;
                            while ((pending = pendingSends.poll()) != null) {
                                pipelinePermits.release();
                                toReQueue.add(pending);
                            }
                            
                            // Re-inject into front of queue in reverse order to preserve original order
                            // Use offerFirst to avoid blocking if queue is full
                            if (!toReQueue.isEmpty()) {
                                for (int i = toReQueue.size() - 1; i >= 0; i--) {
                                    if (!queue.offerFirst(toReQueue.get(i).originalMsg)) {
                                        // Queue full, mark as failed instead of blocking
                                        metrics.recordFail();
                                    }
                                }
                            }
                            // 2. Re-queue the REMAINING messages in this local batch (including current)
                            int currentMsgIdx = batch.indexOf(msg);
                            if (currentMsgIdx != -1) {
                                for (int k = batch.size() - 1; k >= currentMsgIdx; k--) {
                                    if (!queue.offerFirst(batch.get(k))) {
                                        // Queue full, mark as failed instead of blocking
                                        metrics.recordFail();
                                    }
                                }
                            }
                            
                            metrics.recordReconnect();
                            sleepMs(500); // Slightly longer backoff before reconnecting to let server breathe
                            
                            WebSocketClient newClient = connectWithRetry(uri, pipelinePermits, pendingSends, successCount, lastResponseTimeMs);
                            if (newClient == null) {
                                // Reconnection failed: mark all pending messages as failed and exit worker
                                System.err.println("[Worker] Reconnection failed for room " + roomId + ", exiting worker (queue size: " + queue.size() + 
                                    ", re-queued: " + (toReQueue.size() + (currentMsgIdx != -1 ? batch.size() - currentMsgIdx : 0)) + " msgs)");
                                // Messages that have been re-queued will be processed by other workers, here we only need to mark the messages in the current batch that have not been sent as failed
                                for (int k = currentMsgIdx; k < batch.size(); k++) {
                                    metrics.recordFail();
                                }
                                return;
                            }
                            clientRef.set(newClient);
                            client = newClient;
                            acquireLoopStart = System.currentTimeMillis();
                            
                            // BREAK the batch loop and start over to process re-queued messages first
                            batch.clear();
                            break; 
                        }
                        
                        try {
                            acquired = pipelinePermits.tryAcquire();
                            if (!acquired) acquired = pipelinePermits.tryAcquire(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    if (!acquired) {
                        metrics.recordFail();
                        continue;
                    }
                    
                    // Add to batch string
                    try {
                        String json = msg.getSerializedJson();
                        if (json == null) json = OBJECT_MAPPER.writeValueAsString(msg);
                        if (batchBuilder.length() > 0) batchBuilder.append("\n");
                        batchBuilder.append(json);
                        pendingSends.add(new SentRecord(System.currentTimeMillis(), msg));
                    } catch (Exception e) {
                        pipelinePermits.release();
                        metrics.recordFail();
                    }
                }
                
                // Send the whole batch as one frame
                if (batchBuilder.length() > 0 && client.isOpen()) {
                    try {
                        client.send(batchBuilder.toString());
                        long throttleMs = ClientConfig.getThrottleMs();
                        if (throttleMs > 0) {
                            if (sleepMs(throttleMs)) break;
                        }
                    } catch (Exception e) {
                        // If send fails, the messages will eventually time out or connection will close
                    }
                }
            }

            // Wait for in-flight to drain (with timeout), then fail remaining
            long drainDeadline = System.currentTimeMillis() + ClientConfig.getEchoTimeoutMs();
            while (!pendingSends.isEmpty() && System.currentTimeMillis() < drainDeadline) {
                sleepMs(50);
            }
            while (pendingSends.poll() != null) {
                pipelinePermits.release();
                metrics.recordFail();
            }
        } finally {
            WebSocketClient client = clientRef.get();
            if (client != null && client.isOpen()) {
                client.close();
            }
        }
    }
}

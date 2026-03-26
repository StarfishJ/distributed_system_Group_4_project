package consumer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rabbitmq.client.Channel;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Write-Behind Message Consumer (Assignment 3):
 * - Message consumers: read from room queues, enqueue to DB + broadcast queues
 * - DB writer: separate thread, batch flush to PostgreSQL
 * - Broadcast writer: separate thread, batch flush to Fanout
 * - Error recovery: Circuit Breaker, exponential backoff retries, DLQ for exhausted retries
 *
 * Configurable: consumer.batch-size, consumer.flush-interval-ms, consumer.rooms (* or start-end for multi-instance),
 * consumer.max-db-write-queue / consumer.max-retry-batches (0 = unlimited).
 */
@Component
public class MessageConsumer {

    /** Malformed JSON or incompatible payload; delivery should be rejected (requeue=false) to DLX. */
    private static final class BadPayloadException extends RuntimeException {
        BadPayloadException(Throwable cause) {
            super(cause);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);
    private static volatile boolean loggedFirstReceive;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private record PendingBatch(List<ClientMessage> batch, int retryCount, long nextRetryAtMs) {}

    private final Cache<String, Boolean> processedIds = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final RabbitTemplate rabbitTemplate;
    private final MessagePersistenceService persistenceService;
    private final ConsumerMetrics metrics;
    private final DbCircuitBreaker circuitBreaker;
    private final DeadLetterPublisher deadLetterPublisher;

    private final Object dbQueueLock = new Object();
    private final ConcurrentLinkedQueue<ClientMessage> dbWriteQueue = new ConcurrentLinkedQueue<>();
    /** Per-room broadcast buffers to preserve ordering within each room. */
    private final Map<String, ConcurrentLinkedQueue<ClientMessage>> broadcastBuffers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingBatch> retryQueue = new ConcurrentLinkedQueue<>();
    /** Retry count per message (key=messageId or bodyHash) for at-least-once before DLQ. */
    private final Cache<String, Integer> nackRetryCount = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final int batchSize;
    private final long flushIntervalMs;
    private final int consumeNackMaxRetries;
    private final int maxRetryAttempts;
    private final long retryInitialDelayMs;
    private final double retryMultiplier;

    private final String[] subscribedRoomQueues;
    private final int maxDbWriteQueue;
    private final int maxRetryBatches;

    private final ScheduledExecutorService dbWriterExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "db-writer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService broadcastExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "broadcast-flusher");
        t.setDaemon(true);
        return t;
    });

    public MessageConsumer(
            RabbitTemplate rabbitTemplate,
            MessagePersistenceService persistenceService,
            ConsumerMetrics metrics,
            DbCircuitBreaker circuitBreaker,
            DeadLetterPublisher deadLetterPublisher,
            @Value("${consumer.batch-size:1000}") int batchSize,
            @Value("${consumer.flush-interval-ms:100}") long flushIntervalMs,
            @Value("${consumer.consume-nack-max-retries:3}") int consumeNackMaxRetries,
            @Value("${consumer.retry.max-attempts:5}") int maxRetryAttempts,
            @Value("${consumer.retry.initial-delay-ms:1000}") long retryInitialDelayMs,
            @Value("${consumer.retry.multiplier:2}") double retryMultiplier,
            @Value("#{@roomQueueNames}") String[] subscribedRoomQueues,
            @Value("${consumer.max-db-write-queue:200000}") int maxDbWriteQueue,
            @Value("${consumer.max-retry-batches:2000}") int maxRetryBatches) {
        this.rabbitTemplate = rabbitTemplate;
        this.persistenceService = persistenceService;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
        this.deadLetterPublisher = deadLetterPublisher;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.consumeNackMaxRetries = consumeNackMaxRetries;
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryMultiplier = retryMultiplier;
        this.subscribedRoomQueues = subscribedRoomQueues;
        this.maxDbWriteQueue = maxDbWriteQueue;
        this.maxRetryBatches = maxRetryBatches;
    }

    @PostConstruct
    public void init() {
        log.info("Subscribed to {} room queue(s): {} .. {}",
                subscribedRoomQueues.length,
                subscribedRoomQueues[0],
                subscribedRoomQueues[subscribedRoomQueues.length - 1]);
        log.info("Write-Behind enabled: batchSize={}, flushInterval={}ms, nackRetries={}, maxRetries={}, circuitBreaker=on, maxDbQueue={}, maxRetryBatches={}",
                batchSize, flushIntervalMs, consumeNackMaxRetries, maxRetryAttempts,
                maxDbWriteQueue <= 0 ? "unlimited" : String.valueOf(maxDbWriteQueue),
                maxRetryBatches <= 0 ? "unlimited" : String.valueOf(maxRetryBatches));
        dbWriterExecutor.scheduleAtFixedRate(this::flushToDb, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        broadcastExecutor.scheduleAtFixedRate(this::flushBroadcast, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        dbWriterExecutor.shutdown();
        broadcastExecutor.shutdown();
        try {
            dbWriterExecutor.awaitTermination(5, TimeUnit.SECONDS);
            broadcastExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushToDb();
        flushBroadcast();
    }

    @RabbitListener(queues = "#{@roomQueueNames}", ackMode = "MANUAL")
    public void consumeRoomQueue(Message amqpMessage, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        byte[] body = amqpMessage.getBody();
        if (body == null || body.length == 0) {
            ackQuietly(channel, deliveryTag);
            return;
        }
        final List<ClientMessage> messages;
        try {
            messages = parsePayload(body);
        } catch (BadPayloadException e) {
            log.warn("Poison payload rejected to DLX (requeue=false): {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            metrics.incrementPoisonPayloadReject();
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception ignored) {
            }
            return;
        }
        if (!messages.isEmpty() && !loggedFirstReceive) {
            loggedFirstReceive = true;
            log.info("First message received: count={}", messages.size());
        }
        if (messages.isEmpty()) {
            ackQuietly(channel, deliveryTag);
            clearNackRetry(body);
            return;
        }
        List<ClientMessage> candidates = new ArrayList<>(messages.size());
        for (ClientMessage msg : messages) {
            String msgId = msg.messageId();
            if (msgId != null && processedIds.getIfPresent(msgId) != null) {
                continue;
            }
            candidates.add(msg);
        }
        if (candidates.isEmpty()) {
            ackQuietly(channel, deliveryTag);
            clearNackRetry(body);
            return;
        }
        boolean backpressure;
        try {
            backpressure = !enqueueCandidates(candidates);
        } catch (Exception e) {
            log.error("Failed to process message/batch: error={}", e.getMessage());
            handleConsumeFailure(channel, deliveryTag, body);
            return;
        }
        if (backpressure) {
            metrics.incrementBackpressureNack();
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            channel.basicAck(deliveryTag, false);
            clearNackRetry(body);
        } catch (Exception e) {
            log.error("Failed to ack message/batch: error={}", e.getMessage());
            handleConsumeFailure(channel, deliveryTag, body);
        }
    }

    /**
     * @return true if all candidates were enqueued; false if caller should NACK requeue (backpressure)
     */
    private boolean enqueueCandidates(List<ClientMessage> candidates) {
        synchronized (dbQueueLock) {
            if (maxRetryBatches > 0 && retryQueue.size() >= maxRetryBatches) {
                log.debug("Retry queue cap reached ({}), applying backpressure", maxRetryBatches);
                return false;
            }
            if (maxDbWriteQueue > 0 && dbWriteQueue.size() + candidates.size() > maxDbWriteQueue) {
                log.debug("DB write queue cap ({}) would be exceeded, applying backpressure", maxDbWriteQueue);
                return false;
            }
            for (ClientMessage msg : candidates) {
                String msgId = msg.messageId();
                if (msgId != null && processedIds.asMap().putIfAbsent(msgId, Boolean.TRUE) != null) {
                    continue;
                }
                dbWriteQueue.add(msg);
                String roomId = msg.roomId() != null ? msg.roomId() : "0";
                broadcastBuffers.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>()).add(msg);
            }
        }
        return true;
    }

    private void handleConsumeFailure(Channel channel, long deliveryTag, byte[] body) {
        String key = bodyHash(body);
        int retries = nackRetryCount.asMap().getOrDefault(key, 0);
        if (retries < consumeNackMaxRetries) {
            nackRetryCount.asMap().put(key, retries + 1);
            try {
                channel.basicNack(deliveryTag, false, true);
                log.debug("Nack requeue retry {}/{}", retries + 1, consumeNackMaxRetries);
            } catch (Exception ex) { /* ignore */ }
        } else {
            nackRetryCount.asMap().remove(bodyHash(body));
            try {
                channel.basicReject(deliveryTag, false);
                log.warn("Max consume retries exceeded, rejecting to DLQ");
            } catch (Exception ex) { /* ignore */ }
        }
    }

    private static String bodyHash(byte[] body) {
        return String.valueOf(java.util.Arrays.hashCode(body));
    }

    private void clearNackRetry(byte[] body) {
        nackRetryCount.asMap().remove(bodyHash(body));
    }

    private static void ackQuietly(Channel channel, long deliveryTag) {
        try { channel.basicAck(deliveryTag, false); } catch (Exception e) { /* ignore */ }
    }

    /** JSON bytes -> List<ClientMessage>. Handles both [obj,obj] and single obj; throws on malformed JSON. */
    private List<ClientMessage> parsePayload(byte[] body) {
        String str = new String(body, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) {
            return List.of();
        }
        try {
            if (str.charAt(0) == '[') {
                List<ClientMessage> list = objectMapper.readValue(str, new TypeReference<List<ClientMessage>>() {});
                return list != null ? list : List.of();
            }
            return List.of(objectMapper.readValue(str, ClientMessage.class));
        } catch (Exception e) {
            throw new BadPayloadException(e);
        }
    }

    private void flushToDb() {
        long now = System.currentTimeMillis();

        // 1. Process due retries first
        processRetryQueue(now);

        // 2. Drain new batch from main queue
        List<ClientMessage> batch;
        synchronized (dbQueueLock) {
            batch = drain(dbWriteQueue, batchSize);
        }
        if (batch.isEmpty()) return;

        if (!circuitBreaker.allowRequest()) {
            retryQueue.add(new PendingBatch(batch, 0, now + 5000));
            if (circuitBreaker.getState() == DbCircuitBreaker.State.OPEN) {
                log.debug("Circuit OPEN, deferring batch size={}", batch.size());
            }
            return;
        }

        try {
            persistenceService.batchUpsert(batch);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            log.error("DB flush failed: size={}, error={}", batch.size(), e.getMessage());
            metrics.incrementDbWriteError();
            circuitBreaker.recordFailure();
            retryQueue.add(new PendingBatch(batch, 0, now + retryInitialDelayMs));
        }
    }

    private void processRetryQueue(long now) {
        List<PendingBatch> due = new ArrayList<>();
        List<PendingBatch> notDue = new ArrayList<>();
        PendingBatch pb;
        while ((pb = retryQueue.poll()) != null) {
            if (now >= pb.nextRetryAtMs()) due.add(pb);
            else notDue.add(pb);
        }
        notDue.forEach(retryQueue::add);
        for (PendingBatch batch : due) {
            if (!circuitBreaker.allowRequest()) {
                retryQueue.add(new PendingBatch(batch.batch(), batch.retryCount(), now + 5000));
                continue;
            }
            try {
                persistenceService.batchUpsert(batch.batch());
                circuitBreaker.recordSuccess();
                metrics.incrementDbWriteRetry();
            } catch (Exception e) {
                metrics.incrementDbWriteError();
                circuitBreaker.recordFailure();
                int nextRetry = batch.retryCount() + 1;
                long delay = (long) (retryInitialDelayMs * Math.pow(retryMultiplier, nextRetry));
                if (nextRetry >= maxRetryAttempts) {
                    log.warn("Max retries exceeded for batch size={}, sending to DLQ", batch.batch().size());
                    deadLetterPublisher.publishToDlq(batch.batch());
                } else {
                    retryQueue.add(new PendingBatch(batch.batch(), nextRetry, now + delay));
                }
            }
        }
    }

    /** Flush per-room batches to preserve ordering within each room. */
    private void flushBroadcast() {
        for (Map.Entry<String, ConcurrentLinkedQueue<ClientMessage>> entry : broadcastBuffers.entrySet()) {
            List<ClientMessage> batch = drain(entry.getValue(), batchSize);
            if (batch.isEmpty()) continue;
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.BROADCAST_EXCHANGE, "", batch, m -> {
                    m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
                    return m;
                });
                for (int i = 0; i < batch.size(); i++) {
                    metrics.incrementProcessed();
                }
            } catch (Exception e) {
                log.error("Broadcast flush failed: room={}, size={}", entry.getKey(), batch.size(), e);
                batch.forEach(entry.getValue()::add);
            }
        }
    }

    private static <T> List<T> drain(ConcurrentLinkedQueue<T> queue, int max) {
        List<T> list = new ArrayList<>();
        T item;
        while (list.size() < max && (item = queue.poll()) != null) {
            list.add(item);
        }
        return list;
    }
}

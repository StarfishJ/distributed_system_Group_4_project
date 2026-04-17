package consumer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import consumer.redis.ConsumerDedupRedisService;

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
 * - RabbitMQ: late {@code basicAck} only after {@code batchUpsert} succeeds for that delivery
 *   (one {@link DeliveryUnit} per AMQP message / {@code deliveryTag}; units are not split across flushes).
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

    /**
     * One RabbitMQ delivery (single {@code deliveryTag}) and its parsed messages. Atomic for persistence + ACK:
     * never split across two DB flushes.
     */
    private record DeliveryUnit(Channel channel, long deliveryTag, List<ClientMessage> messages, byte[] rawBody) {}

    private record PendingBatch(List<DeliveryUnit> units, int retryCount, long nextRetryAtMs) {}

    private enum EnqueueOutcome {
        /** Accepted into {@link #dbWriteQueue}; Rabbit ACK after DB flush. */
        ENQUEUED,
        /** Nothing to persist (all duplicates); caller should ACK now. */
        ALL_DUPLICATE_ACK,
        /** Caller should NACK requeue (backpressure). */
        BACKPRESSURE
    }

    private final Cache<String, Boolean> processedIds = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final RabbitTemplate rabbitTemplate;
    private final MessagePersistenceService persistenceService;
    private final ConsumerMetrics metrics;
    private final DbCircuitBreaker circuitBreaker;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ConsumerDedupRedisService dedup;
    private final boolean broadcastTargeted;
    private final ObjectProvider<StringRedisTemplate> redisForPresence;

    private final Object dbQueueLock = new Object();
    private final ConcurrentLinkedQueue<DeliveryUnit> dbWriteQueue = new ConcurrentLinkedQueue<>();
    /** Tracks total {@link ClientMessage} count sitting in {@link #dbWriteQueue} (for backpressure caps). */
    private final AtomicInteger dbWritePendingMessageCount = new AtomicInteger(0);
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
            ObjectProvider<ConsumerDedupRedisService> dedupProvider,
            ObjectProvider<StringRedisTemplate> redisForPresence,
            @Value("${consumer.broadcast.targeted:false}") boolean broadcastTargeted,
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
        this.dedup = dedupProvider.getIfAvailable();
        this.redisForPresence = redisForPresence;
        this.broadcastTargeted = broadcastTargeted;
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
        log.info("Write-Behind enabled: batchSize={}, flushInterval={}ms, nackRetries={}, maxRetries={}, circuitBreaker=on, maxDbQueue={}, maxRetryBatches={}, redisDedup={}",
                batchSize, flushIntervalMs, consumeNackMaxRetries, maxRetryAttempts,
                maxDbWriteQueue <= 0 ? "unlimited" : String.valueOf(maxDbWriteQueue),
                maxRetryBatches <= 0 ? "unlimited" : String.valueOf(maxRetryBatches),
                dedup != null);
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
        EnqueueOutcome outcome;
        try {
            outcome = enqueueDelivery(channel, deliveryTag, candidates, body);
        } catch (Exception e) {
            log.error("Failed to process message/batch: error={}", e.getMessage());
            handleConsumeFailure(channel, deliveryTag, body);
            return;
        }
        if (outcome == EnqueueOutcome.BACKPRESSURE) {
            metrics.incrementBackpressureNack();
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ignored) {
            }
            return;
        }
        if (outcome == EnqueueOutcome.ALL_DUPLICATE_ACK) {
            try {
                channel.basicAck(deliveryTag, false);
                clearNackRetry(body);
            } catch (Exception e) {
                log.error("Failed to ack duplicate-only delivery: error={}", e.getMessage());
                handleConsumeFailure(channel, deliveryTag, body);
            }
            return;
        }
        /* ENQUEUED: late ACK after batchUpsert in flushToDb / retry path. */
    }

    private EnqueueOutcome enqueueDelivery(Channel channel, long deliveryTag, List<ClientMessage> candidates, byte[] rawBody) {
        List<ClientMessage> toPersist = new ArrayList<>(candidates.size());
        synchronized (dbQueueLock) {
            if (maxRetryBatches > 0 && retryQueue.size() >= maxRetryBatches) {
                log.debug("Retry queue cap reached ({}), applying backpressure", maxRetryBatches);
                return EnqueueOutcome.BACKPRESSURE;
            }
            int addCount = 0;
            for (ClientMessage msg : candidates) {
                String msgId = msg.messageId();
                if (msgId != null && processedIds.asMap().putIfAbsent(msgId, Boolean.TRUE) != null) {
                    continue;
                }
                toPersist.add(msg);
                addCount++;
            }
            if (addCount == 0) {
                return EnqueueOutcome.ALL_DUPLICATE_ACK;
            }
            if (maxDbWriteQueue > 0 && dbWritePendingMessageCount.get() + addCount > maxDbWriteQueue) {
                for (ClientMessage msg : toPersist) {
                    String msgId = msg.messageId();
                    if (msgId != null) processedIds.invalidate(msgId);
                }
                log.debug("DB write queue cap ({}) would be exceeded, applying backpressure", maxDbWriteQueue);
                return EnqueueOutcome.BACKPRESSURE;
            }
            dbWriteQueue.add(new DeliveryUnit(channel, deliveryTag, List.copyOf(toPersist), rawBody));
            dbWritePendingMessageCount.addAndGet(addCount);
        }
        return EnqueueOutcome.ENQUEUED;
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
                Message out = MessageBuilder.withBody(body)
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                rabbitTemplate.send(RabbitMQConfig.DLX_EXCHANGE, RabbitMQConfig.DLX_ROUTING_KEY_BUSINESS, out);
                channel.basicAck(deliveryTag, false);
                log.warn("Max consume retries exceeded: forwarded to {} (rk={})", RabbitMQConfig.DLQ_NAME, RabbitMQConfig.DLX_ROUTING_KEY_BUSINESS);
            } catch (Exception ex) {
                log.error("Failed to forward to business DLQ, falling back to reject: {}", ex.getMessage());
                try {
                    channel.basicReject(deliveryTag, false);
                } catch (Exception ignored) {
                }
            }
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

        // 2. Drain whole delivery units until batchSize (message count) — never split one unit.
        List<DeliveryUnit> units;
        synchronized (dbQueueLock) {
            units = drainDbUnitsUpToMessageBudget();
        }
        if (units.isEmpty()) return;

        if (!circuitBreaker.allowRequest()) {
            retryQueue.add(new PendingBatch(units, 0, now + 5000));
            if (circuitBreaker.getState() == DbCircuitBreaker.State.OPEN) {
                log.debug("Circuit OPEN, deferring {} delivery unit(s)", units.size());
            }
            return;
        }

        try {
            List<ClientMessage> persistedBatch = flattenMessages(units);
            persistenceService.batchUpsert(persistedBatch);
            circuitBreaker.recordSuccess();
            if (dedup != null) {
                dedup.markPersisted(persistedBatch);
            }
            stageBroadcast(persistedBatch);
            acknowledgeUnits(units);
        } catch (Exception e) {
            log.error("DB flush failed: units={}, messages={}, error={}",
                    units.size(), flattenMessages(units).size(), e.getMessage());
            metrics.incrementDbWriteError();
            circuitBreaker.recordFailure();
            retryQueue.add(new PendingBatch(units, 0, now + retryInitialDelayMs));
        }
    }

    /**
     * Removes units from {@link #dbWriteQueue} up to {@link #batchSize} total messages; each {@link DeliveryUnit}
     * is kept intact (one Rabbit ACK per unit after successful persist).
     */
    private List<DeliveryUnit> drainDbUnitsUpToMessageBudget() {
        List<DeliveryUnit> units = new ArrayList<>();
        int msgCount = 0;
        while (true) {
            DeliveryUnit head = dbWriteQueue.peek();
            if (head == null) break;
            int add = head.messages().size();
            if (!units.isEmpty() && msgCount + add > batchSize) break;
            DeliveryUnit u = dbWriteQueue.poll();
            if (u == null) break;
            units.add(u);
            msgCount += add;
            dbWritePendingMessageCount.addAndGet(-add);
        }
        return units;
    }

    private static List<ClientMessage> flattenMessages(List<DeliveryUnit> units) {
        List<ClientMessage> out = new ArrayList<>();
        for (DeliveryUnit u : units) {
            out.addAll(u.messages());
        }
        return out;
    }

    /** Late ACK on the consumer {@link Channel} after DB commit (same tags as deliveries). */
    private void acknowledgeUnits(List<DeliveryUnit> units) {
        for (DeliveryUnit u : units) {
            try {
                u.channel().basicAck(u.deliveryTag(), false);
                if (u.rawBody() != null) {
                    clearNackRetry(u.rawBody());
                }
            } catch (Exception e) {
                log.error("Late basicAck failed (delivery may be redelivered): {}", e.getMessage());
            }
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
                retryQueue.add(new PendingBatch(batch.units(), batch.retryCount(), now + 5000));
                continue;
            }
            List<ClientMessage> messages = flattenMessages(batch.units());
            try {
                persistenceService.batchUpsert(messages);
                circuitBreaker.recordSuccess();
                metrics.incrementDbWriteRetry();
                if (dedup != null) {
                    dedup.markPersisted(messages);
                }
                stageBroadcast(messages);
                acknowledgeUnits(batch.units());
            } catch (Exception e) {
                metrics.incrementDbWriteError();
                circuitBreaker.recordFailure();
                int nextRetry = batch.retryCount() + 1;
                long delay = (long) (retryInitialDelayMs * Math.pow(retryMultiplier, nextRetry));
                if (nextRetry >= maxRetryAttempts) {
                    log.warn("Max retries exceeded for batch messages={}, sending to db-replay", messages.size());
                    deadLetterPublisher.publishToDlq(messages);
                    acknowledgeUnits(batch.units());
                } else {
                    retryQueue.add(new PendingBatch(batch.units(), nextRetry, now + delay));
                }
            }
        }
    }

    /** After DB commit: enqueue for the broadcast flusher (strict ordering: persist before fan-out). */
    private void stageBroadcast(List<ClientMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        synchronized (dbQueueLock) {
            for (ClientMessage msg : messages) {
                String roomId = msg.roomId() != null ? msg.roomId() : "0";
                broadcastBuffers.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>()).add(msg);
            }
        }
    }

    /** Flush per-room batches to preserve ordering within each room. */
    private void flushBroadcast() {
        StringRedisTemplate redis = redisForPresence.getIfAvailable();
        boolean useTargeted = broadcastTargeted && redis != null;

        for (Map.Entry<String, ConcurrentLinkedQueue<ClientMessage>> entry : broadcastBuffers.entrySet()) {
            List<ClientMessage> batch = drain(entry.getValue(), batchSize);
            if (batch.isEmpty()) continue;
            List<ClientMessage> toSend = new ArrayList<>(batch.size());
            List<String> broadcastClaims = new ArrayList<>();
            for (ClientMessage m : batch) {
                String mid = m.messageId();
                if (mid == null || mid.isBlank()) {
                    toSend.add(m);
                    continue;
                }
                if (dedup == null || dedup.tryClaimBroadcast(mid)) {
                    toSend.add(m);
                    if (dedup != null) {
                        broadcastClaims.add(mid);
                    }
                }
            }
            if (toSend.isEmpty()) continue;
            String roomId = entry.getKey();
            try {
                if (useTargeted) {
                    Set<String> members = redis.opsForSet().members("presence:room:" + roomId);
                    if (members != null && !members.isEmpty()) {
                        for (String token : members) {
                            if (token == null || token.isBlank()) continue;
                            String rk = BroadcastRouting.ROUTING_PREFIX + token.trim();
                            rabbitTemplate.convertAndSend(BroadcastRouting.TOPIC_EXCHANGE, rk, toSend, m -> {
                                m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
                                return m;
                            });
                        }
                    } else {
                        publishFanoutBatch(toSend);
                    }
                } else {
                    publishFanoutBatch(toSend);
                }
                for (int i = 0; i < toSend.size(); i++) {
                    metrics.incrementProcessed();
                }
            } catch (Exception e) {
                log.error("Broadcast flush failed: room={}, size={}", roomId, toSend.size(), e);
                if (dedup != null) {
                    for (String mid : broadcastClaims) {
                        dedup.releaseBroadcast(mid);
                    }
                }
                toSend.forEach(entry.getValue()::add);
            }
        }
    }

    private void publishFanoutBatch(List<ClientMessage> toSend) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.BROADCAST_EXCHANGE, "", toSend, m -> {
            m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
            return m;
        });
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

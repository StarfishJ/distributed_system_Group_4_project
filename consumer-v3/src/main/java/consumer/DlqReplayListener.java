package consumer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

/**
 * Consumes DB-failed batches from {@link RabbitMQConfig#DB_REPLAY_QUEUE} (published by {@link DeadLetterPublisher}).
 * Poison / malformed payloads from room queues still land in {@link RabbitMQConfig#DLQ_NAME} only — not replayed here.
 * <p>
 * Retries {@link MessagePersistenceService#batchUpsert} with exponential backoff in-process; after max attempts the
 * message is acked after persisting a row to {@code dlq_audit_evidence} via {@link DlqAuditService} (same as
 * {@link DeadLetterAuditListener}), so exhausted batches are not silently lost.
 */
@Component
@ConditionalOnProperty(name = "consumer.dlq-replay.enabled", havingValue = "true", matchIfMissing = true)
public class DlqReplayListener {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayListener.class);

    private final MessagePersistenceService persistenceService;
    private final DlqAuditService dlqAuditService;
    private final ConsumerMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;

    public DlqReplayListener(
            MessagePersistenceService persistenceService,
            DlqAuditService dlqAuditService,
            ConsumerMetrics metrics,
            @Value("${consumer.dlq-replay.max-attempts:5}") int maxAttempts,
            @Value("${consumer.dlq-replay.initial-delay-ms:1000}") long initialDelayMs,
            @Value("${consumer.dlq-replay.multiplier:2}") double multiplier) {
        this.persistenceService = persistenceService;
        this.dlqAuditService = dlqAuditService;
        this.metrics = metrics;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.multiplier = multiplier > 1.0 ? multiplier : 2.0;
    }

    @RabbitListener(
            queues = "${consumer.dlq-replay.queue:chat.db-replay}",
            containerFactory = "dlqReplayListenerContainerFactory",
            ackMode = "MANUAL")
    public void replay(Message amqpMessage, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        byte[] body = amqpMessage.getBody();
        if (body == null || body.length == 0) {
            ackQuietly(channel, deliveryTag);
            return;
        }

        final List<ClientMessage> batch;
        try {
            batch = parsePayload(body);
        } catch (Exception e) {
            log.error("DLQ replay: payload is not a ClientMessage batch (poison or wrong queue); discarding: {}", e.getMessage());
            metrics.incrementDlqReplayPoisonDiscard();
            ackQuietly(channel, deliveryTag);
            return;
        }
        if (batch.isEmpty()) {
            ackQuietly(channel, deliveryTag);
            return;
        }

        long delayMs = initialDelayMs;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                persistenceService.batchUpsert(batch);
                metrics.incrementDlqReplaySuccess(batch.size());
                log.info("DLQ replay persisted batch size={} on attempt {}", batch.size(), attempt);
                ackQuietly(channel, deliveryTag);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("DLQ replay attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    long sleep = Math.min(delayMs, 60_000L);
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        nackRequeueQuietly(channel, deliveryTag);
                        return;
                    }
                    delayMs = (long) (delayMs * multiplier);
                }
            }
        }
        log.error("DLQ replay exhausted after {} attempts; acking to drop (size={}). Last error: {}",
                maxAttempts, batch.size(), last != null ? last.getMessage() : "n/a");
        metrics.incrementDlqReplayExhausted(batch.size());
        persistReplayExhaustAudit(batch);
        ackQuietly(channel, deliveryTag);
    }

    private void persistReplayExhaustAudit(List<ClientMessage> batch) {
        try {
            String raw = objectMapper.writeValueAsString(batch);
            String messageId = "db-replay-exhausted";
            if (!batch.isEmpty() && batch.get(0).messageId() != null && !batch.get(0).messageId().isBlank()) {
                messageId = batch.get(0).messageId();
            }
            dlqAuditService.recordEvidence(messageId, raw);
            metrics.incrementDlqAuditEvidence();
            log.warn("DLQ replay exhausted: audit row written for messageId={}", messageId);
        } catch (Exception e) {
            log.error("DLQ replay audit insert failed: {}", e.getMessage());
        }
    }

    private List<ClientMessage> parsePayload(byte[] body) throws java.io.IOException {
        String str = new String(body, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) {
            return List.of();
        }
        if (str.charAt(0) == '[') {
            List<ClientMessage> list = objectMapper.readValue(str, new TypeReference<List<ClientMessage>>() {});
            return list != null ? list : List.of();
        }
        return List.of(objectMapper.readValue(str, ClientMessage.class));
    }

    private static void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("DLQ replay ack failed: {}", e.getMessage());
        }
    }

    private static void nackRequeueQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.warn("DLQ replay nack failed: {}", e.getMessage());
        }
    }
}

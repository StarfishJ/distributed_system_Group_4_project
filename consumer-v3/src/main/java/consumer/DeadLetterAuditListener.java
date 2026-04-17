package consumer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

/**
 * Listens on {@link RabbitMQConfig#DLQ_NAME} (routing key {@link RabbitMQConfig#DLX_ROUTING_KEY_BUSINESS}).
 * When the sum of {@code x-death} {@code count} entries exceeds a threshold, logs and appends a row to
 * {@code dlq_audit_evidence}. Otherwise acks without persisting (normal business forwards often have no x-death yet).
 */
@Component
@ConditionalOnProperty(name = "consumer.dead-letter-audit.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterAuditListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAuditListener.class);

    private final DlqAuditService dlqAuditService;
    private final ConsumerMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final long deathCountThreshold;

    public DeadLetterAuditListener(
            DlqAuditService dlqAuditService,
            ConsumerMetrics metrics,
            @Value("${consumer.dead-letter-audit.death-count-threshold:5}") long deathCountThreshold) {
        this.dlqAuditService = dlqAuditService;
        this.metrics = metrics;
        this.deathCountThreshold = Math.max(1L, deathCountThreshold);
    }

    @RabbitListener(
            queues = "${consumer.dead-letter-audit.queue:chat.dead-letter}",
            containerFactory = "deadLetterAuditListenerContainerFactory",
            ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        byte[] body = message.getBody();
        String raw = body == null ? "" : new String(body, StandardCharsets.UTF_8);

        long deathSum = sumXDeathCounts(message.getMessageProperties().getHeaders());
        if (deathSum >= deathCountThreshold) {
            String messageId = extractMessageId(raw);
            try {
                dlqAuditService.recordEvidence(messageId, raw);
                metrics.incrementDlqAuditEvidence();
                log.warn("DLQ audit: x-death sum={} >= threshold={}, messageId={}, persisted evidence row", deathSum, deathCountThreshold, messageId);
            } catch (Exception e) {
                log.error("DLQ audit insert failed: {}", e.getMessage());
            }
        }
        ackQuietly(channel, deliveryTag);
    }

    @SuppressWarnings("unchecked")
    private static long sumXDeathCounts(Map<String, Object> headers) {
        if (headers == null) {
            return 0L;
        }
        Object xd = headers.get("x-death");
        if (!(xd instanceof List)) {
            return 0L;
        }
        long sum = 0L;
        for (Object o : (List<?>) xd) {
            if (o instanceof Map) {
                Object c = ((Map<?, ?>) o).get("count");
                if (c instanceof Number) {
                    sum += ((Number) c).longValue();
                }
            }
        }
        return sum;
    }

    private String extractMessageId(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "unknown";
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isArray() && root.size() > 0) {
                JsonNode id = root.get(0).get("messageId");
                if (id != null && id.isTextual()) {
                    return id.asText();
                }
            }
            if (root.isObject()) {
                JsonNode id = root.get("messageId");
                if (id != null && id.isTextual()) {
                    return id.asText();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }

    private static void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("dead-letter audit ack failed: {}", e.getMessage());
        }
    }
}

package consumer;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes failed messages to the Dead Letter Exchange (chat.dlx).
 * Messages land in chat.dead-letter queue for inspection and replay.
 */
@Service
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ConsumerMetrics metrics;

    public DeadLetterPublisher(RabbitTemplate rabbitTemplate, ConsumerMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    public void publishToDlq(List<ClientMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.DLX_EXCHANGE, "", messages);
            log.warn("Published {} failed message(s) to DLQ", messages.size());
            metrics.incrementDlqPublished(messages.size());
        } catch (Exception e) {
            log.error("Failed to publish to DLQ: {}", e.getMessage());
        }
    }
}

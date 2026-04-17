package consumer;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes DB write failures to the dedicated replay exchange {@link RabbitMQConfig#DB_REPLAY_EXCHANGE}.
 * Poison / TTL overflow from room queues still go to the fanout {@link RabbitMQConfig#DLX_EXCHANGE}
 * and accumulate in {@link RabbitMQConfig#DLQ_NAME} — not mixed with replay batches.
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
            rabbitTemplate.convertAndSend(RabbitMQConfig.DB_REPLAY_EXCHANGE, RabbitMQConfig.DB_REPLAY_ROUTING_KEY, messages);
            log.warn("Published {} failed message(s) to db-replay queue for later persist", messages.size());
            metrics.incrementDlqPublished(messages.size());
        } catch (Exception e) {
            log.error("Failed to publish to DLQ: {}", e.getMessage());
        }
    }
}

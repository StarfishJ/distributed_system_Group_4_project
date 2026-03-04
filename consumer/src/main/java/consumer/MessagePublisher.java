package consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Message Publisher for Consumer: publishes incoming client messages to RabbitMQ.
 * Protected by a circuit breaker to fail-fast when RabbitMQ is unavailable.
 */
@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);
    private static final String CIRCUIT_BREAKER_NAME = "rabbitmq";

    private final RabbitTemplate rabbitTemplate;
    private final CircuitBreaker circuitBreaker;
    private final ConsumerMetrics metrics;

    public MessagePublisher(RabbitTemplate rabbitTemplate,
                            CircuitBreakerRegistry registry,
                            ConsumerMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.metrics = metrics;
    }

    /**
     * Publish to RabbitMQ topic exchange using routing key "room.{roomId}".
     * Uses the same exchange as server-v2 — no routing key needed to change.
     *
     * @return true if published successfully, false if circuit open or error
     */
    public boolean publishMessage(String roomId, ClientMessage message) {
        try {
            circuitBreaker.executeRunnable(() ->
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, "room." + roomId, message));
            metrics.incrementPublished();
            return true;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN - queue unavailable, message dropped: roomId={}", roomId);
            metrics.incrementPublishError();
            return false;
        } catch (Exception e) {
            log.error("Failed to publish to RabbitMQ: roomId={}, error={}", roomId, e.getMessage());
            metrics.incrementPublishError();
            return false;
        }
    }
}

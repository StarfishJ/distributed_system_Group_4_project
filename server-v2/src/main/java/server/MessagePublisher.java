package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);
    private static final String CIRCUIT_BREAKER_NAME = "rabbitmq";

    private final RabbitTemplate rabbitTemplate;
    private final CircuitBreaker circuitBreaker;

    // CircuitBreakerRegistry is a registry of circuit breakers
    public MessagePublisher(RabbitTemplate rabbitTemplate, CircuitBreakerRegistry registry) {
        this.rabbitTemplate = rabbitTemplate;
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // Publisher confirm callback: log when broker nacks a message
        this.rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Message NOT confirmed by broker (nack): cause={}, correlationData={}", cause, correlationData);
            }
        });

        // Return callback: log when a message cannot be routed to any queue
        this.rabbitTemplate.setReturnsCallback(returned -> {
            log.error("Message RETURNED (unroutable): exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });

        // Mandatory=true: trigger return callback for unroutable messages (instead of silent drop)
        this.rabbitTemplate.setMandatory(true);
    }

    /**
     * Publish message to RabbitMQ. Protected by circuit breaker - when queue is unavailable,
     * fails fast instead of overwhelming the system.
     * @return true if published, false if circuit open or publish failed
     */
    public boolean publishMessage(String roomId, ClientMessage message) {
        try {
            circuitBreaker.executeRunnable(() ->
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, "room." + roomId, message));
            return true;
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN - queue unavailable, message dropped: roomId={}", roomId);
            return false;
        } catch (Exception e) {
            log.error("Failed to publish to RabbitMQ: roomId={}, error={}", roomId, e.getMessage());
            return false;
        }
    }
}

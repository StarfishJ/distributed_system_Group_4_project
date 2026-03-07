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
    private static final int MAX_BATCH_SIZE = 200;
    private static final long FLUSH_INTERVAL_MS = 30;

    private final RabbitTemplate rabbitTemplate;
    private final CircuitBreaker circuitBreaker;
    private final ServerMetrics metrics;
    private final java.util.concurrent.ScheduledExecutorService scheduler = 
            java.util.concurrent.Executors.newScheduledThreadPool(20);
    
    private final java.util.concurrent.ConcurrentHashMap<String, RoomBuffer> roomBuffers = 
            new java.util.concurrent.ConcurrentHashMap<>();

    private static class RoomBuffer {
        final java.util.List<ClientMessage> messages = new java.util.ArrayList<>();
        boolean timerActive = false;
    }

    private static final int MAX_ROOM_BUFFER_SIZE = 2000;

    public MessagePublisher(RabbitTemplate rabbitTemplate, CircuitBreakerRegistry registry, ServerMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.metrics = metrics;
        log.info("Upstream Batching enabled: maxQueue={}, flushInterval={}ms, maxRoomBuffer={}", 
            MAX_BATCH_SIZE, FLUSH_INTERVAL_MS, MAX_ROOM_BUFFER_SIZE);

        this.rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Message NOT confirmed by broker (nack): cause={}, correlationData={}", cause, correlationData);
            }
        });

        this.rabbitTemplate.setReturnsCallback(returned -> {
            log.error("Message RETURNED (unroutable): exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });

        this.rabbitTemplate.setMandatory(true);
    }

    /**
     * @return true if accepted, false if rejected due to overflow or open circuit breaker.
     */
    public boolean publishMessage(String roomId, ClientMessage message) {
        // 1. Check Circuit Breaker upfront (Backpressure)
        if (!circuitBreaker.tryAcquirePermission()) {
            return false;
        }

        RoomBuffer buffer = roomBuffers.computeIfAbsent(roomId, k -> new RoomBuffer());
        
        synchronized (buffer) {
            // 2. Check Buffer Limit (Backpressure)
            if (buffer.messages.size() >= MAX_ROOM_BUFFER_SIZE) {
                return false;
            }

            buffer.messages.add(message);
            
            if (buffer.messages.size() >= MAX_BATCH_SIZE) {
                flush(roomId, buffer);
            } else if (!buffer.timerActive) {
                buffer.timerActive = true;
                scheduler.schedule(() -> {
                    synchronized (buffer) {
                        flush(roomId, buffer);
                    }
                }, FLUSH_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
        return true;
    }

    private void flush(String roomId, RoomBuffer buffer) {
        if (buffer.messages.isEmpty()) {
            buffer.timerActive = false;
            return;
        }

        java.util.List<ClientMessage> batch = new java.util.ArrayList<>(buffer.messages);
        buffer.messages.clear();
        buffer.timerActive = false;

        try {
            // We already checked permission in publishMessage, but we execute under CB for monitoring
            circuitBreaker.executeRunnable(() ->
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, "room." + roomId, batch));
            metrics.incrementPublished();
            if (log.isDebugEnabled()) log.debug("Flushed upstream batch: room={}, size={}", roomId, batch.size());
        } catch (Exception e) {
            // On error, we log but don't re-queue here to avoid infinite loops
            // The client will handle retries because it won't get an ACK for these messages
            log.error("Failed to publish batch to RabbitMQ: roomId={}, error={}", roomId, e.getMessage());
            metrics.incrementPublishError();
        }
    }
}

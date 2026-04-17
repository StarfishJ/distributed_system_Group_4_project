package server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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

        flushAndWaitConfirm(roomId, buffer, batch);
    }

    /**
     * Flush any non-empty per-room buffers for the given rooms and wait for broker publisher confirms
     * on each send. Call this before telling the client {@code QUEUED} so the response is not fire-and-forget.
     */
    public boolean flushAndConfirmRooms(Collection<String> roomIds) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>(roomIds);
        for (String roomId : distinct) {
            RoomBuffer buffer = roomBuffers.get(roomId);
            if (buffer == null) continue;
            synchronized (buffer) {
                if (buffer.messages.isEmpty()) continue;
                List<ClientMessage> batch = new ArrayList<>(buffer.messages);
                buffer.messages.clear();
                buffer.timerActive = false;
                if (!flushAndWaitConfirm(roomId, buffer, batch)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Publish {@code batch} and block until the broker ACKs the publisher confirm (or nack / error).
     * On failure, restores {@code batch} to the front of {@code buffer} so callers can retry.
     *
     * @return true if the broker acknowledged the publisher confirm
     */
    private boolean flushAndWaitConfirm(String roomId, RoomBuffer buffer, List<ClientMessage> batch) {
        if (batch.isEmpty()) {
            return true;
        }
        try {
            int queueIndex = Math.abs(roomId.hashCode()) % 20 + 1;
            String routingKey = "room." + queueIndex;
            circuitBreaker.executeSupplier(() -> {
                rabbitTemplate.invoke(ops -> {
                    ops.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, routingKey, batch);
                    return null;
                });
                return true;
            });
            metrics.incrementPublished();
            if (log.isDebugEnabled()) log.debug("Flushed upstream batch (confirmed): room={}, size={}", roomId, batch.size());
            return true;
        } catch (CallNotPermittedException e) {
            buffer.messages.addAll(0, batch);
            buffer.timerActive = !buffer.messages.isEmpty();
            return false;
        } catch (Exception e) {
            log.error("Failed to publish batch to RabbitMQ: roomId={}, error={}", roomId, e.getMessage());
            metrics.incrementPublishError();
            buffer.messages.addAll(0, batch);
            buffer.timerActive = !buffer.messages.isEmpty();
            return false;
        }
    }
}

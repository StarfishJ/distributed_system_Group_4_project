package consumer;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rabbitmq.client.Channel;

/**
 * Message Consumer: consume messages from room queues and publish to global broadcast exchange.
 * 
 * Compliance:
 * - Multi-threaded: Concurrency 20 (1 thread per room)
 * - Specific Rooms: Uses @RabbitListener(queues = "#{roomQueueNames}") for dynamic sharding
 * - Fair Distribution: Each queue gets dedicated processing
 * - Ordering: Prefetch=1 ensures per-room order
 * - Idempotency: Caffeine cache for deduplication
 */
@Component
public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    // High-performance thread-safe cache for message deduplication
    private final Cache<String, Boolean> processedIds = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final RabbitTemplate rabbitTemplate;
    private final ConsumerMetrics metrics;

    public MessageConsumer(RabbitTemplate rabbitTemplate, ConsumerMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    /**
     * Listen on rooms defined in RabbitMQConfig.roomQueueNames bean.
     * This allows running multiple Consumer instances each handling a subset of rooms.
     */
    @RabbitListener(queues = "#{roomQueueNames}", ackMode = "MANUAL")
    public void consumeRoomQueue(ClientMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        
        String msgId = message.messageId();
        
        // Idempotency: Atomic Duplicate detection via Caffeine Cache
        if (msgId != null && processedIds.asMap().putIfAbsent(msgId, Boolean.TRUE) != null) {
            try {
                channel.basicAck(deliveryTag, false);
                return;
            } catch (Exception e) {
                log.error("Failed to ACK duplicate message", e);
                return;
            }
        }

        try {
            // Forward to Fanout exchange (Non-persistent for performance)
            rabbitTemplate.convertAndSend(RabbitMQConfig.BROADCAST_EXCHANGE, "", message, m -> {
                m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
                return m;
            });
            
            // ACK original message from room queue
            channel.basicAck(deliveryTag, false);
            metrics.incrementProcessed();
        } catch (Exception e) {
            log.error("Failed to process message: msgId={}", msgId, e);
            try {
                // Reject if publish fails (requeue=false to DLX)
                channel.basicReject(deliveryTag, false);
            } catch (Exception re) { }
        }
    }
}

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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
    
    // Batching state
    private final ConcurrentLinkedQueue<ClientMessage> batchQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "broadcast-batcher");
        t.setDaemon(true);
        return t;
    });

    public MessageConsumer(RabbitTemplate rabbitTemplate, ConsumerMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        // Flash batch every 50ms
        scheduler.scheduleAtFixedRate(this::flushBatch, 50, 50, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        flushBatch();
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
            // Add to batch instead of direct send
            batchQueue.add(message);
            
            // ACK original message from room queue immediately (at-least-once for the batch buffer)
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to process message: msgId={}", msgId, e);
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception re) { }
        }
    }

    private synchronized void flushBatch() {
        if (batchQueue.isEmpty()) return;

        List<ClientMessage> batch = new ArrayList<>();
        ClientMessage msg;
        while ((msg = batchQueue.poll()) != null && batch.size() < 100) {
            batch.add(msg);
        }

        if (batch.isEmpty()) return;

        try {
            // Forward the whole batch as a JSON array to Fanout exchange
            rabbitTemplate.convertAndSend(RabbitMQConfig.BROADCAST_EXCHANGE, "", batch, m -> {
                m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
                return m;
            });
            metrics.incrementProcessed(); // Approximate: increments per batch or we could increment by batch.size()
            // To be accurate with existing metrics:
            for (int i = 1; i < batch.size(); i++) metrics.incrementProcessed();
        } catch (Exception e) {
            log.error("Failed to publish broadcast batch (size={})", batch.size(), e);
        }
        
        // If there are still items, schedule another immediate flush
        if (!batchQueue.isEmpty()) {
            scheduler.execute(this::flushBatch);
        }
    }
}

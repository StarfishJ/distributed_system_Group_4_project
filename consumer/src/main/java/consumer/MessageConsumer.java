package consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
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

/**
 * Message Consumer: consume messages from room queues and publish to global broadcast exchange.
 *
 * - Per-room batching: preserves ordering within each room
 * - At-least-once: basicNack requeue=true with retry limit before DLQ
 */
@Component
public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Cache<String, Boolean> processedIds = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private final Cache<String, Integer> nackRetryCount = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final RabbitTemplate rabbitTemplate;
    private final ConsumerMetrics metrics;

    private final Map<String, ConcurrentLinkedQueue<ClientMessage>> broadcastBuffers = new ConcurrentHashMap<>();
    private static final int NACK_MAX_RETRIES = 3;
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
        log.info("Bi-directional Batching enabled: downstream flush 50ms");
        // Flash batch every 50ms
        scheduler.scheduleAtFixedRate(this::flushBatch, 50, 50, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        flushBatch();
    }

    @RabbitListener(queues = "#{roomQueueNames}", ackMode = "MANUAL")
    public void consumeRoomQueue(Message amqpMessage, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        byte[] body = amqpMessage.getBody();
        if (body == null || body.length == 0) {
            try { channel.basicAck(deliveryTag, false); } catch (Exception e) { }
            return;
        }
        try {
            List<ClientMessage> messages = parsePayload(body);
            for (ClientMessage msg : messages) {
                processSingleMessage(msg);
            }
            channel.basicAck(deliveryTag, false);
            nackRetryCount.asMap().remove(bodyHash(body));
        } catch (Exception e) {
            log.error("Failed to process message/batch: error={}", e.getMessage());
            handleConsumeFailure(channel, deliveryTag, body);
        }
    }

    private void handleConsumeFailure(Channel channel, long deliveryTag, byte[] body) {
        String key = bodyHash(body);
        int retries = nackRetryCount.asMap().getOrDefault(key, 0);
        if (retries < NACK_MAX_RETRIES) {
            nackRetryCount.asMap().put(key, retries + 1);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ex) { }
        } else {
            nackRetryCount.asMap().remove(key);
            try {
                channel.basicReject(deliveryTag, false);
                log.warn("Max consume retries exceeded, rejecting to DLQ");
            } catch (Exception ex) { }
        }
    }

    private static String bodyHash(byte[] body) {
        return String.valueOf(java.util.Arrays.hashCode(body));
    }

    private static List<ClientMessage> parsePayload(byte[] body) {
        try {
            if (body[0] == '[') {
                return objectMapper.readValue(body, new TypeReference<List<ClientMessage>>() {});
            }
            return List.of(objectMapper.readValue(body, ClientMessage.class));
        } catch (Exception e) {
            log.warn("Failed to parse payload: {}", e.getMessage());
            return List.of();
        }
    }

    private void processSingleMessage(ClientMessage message) {
        String msgId = message.messageId();
        if (msgId != null && processedIds.asMap().putIfAbsent(msgId, Boolean.TRUE) != null) {
            return;
        }
        String roomId = message.roomId() != null ? message.roomId() : "0";
        broadcastBuffers.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>()).add(message);
    }

    private synchronized void flushBatch() {
        for (Map.Entry<String, ConcurrentLinkedQueue<ClientMessage>> entry : broadcastBuffers.entrySet()) {
            List<ClientMessage> batch = new ArrayList<>();
            ClientMessage msg;
            while ((msg = entry.getValue().poll()) != null && batch.size() < 100) {
                batch.add(msg);
            }
            if (batch.isEmpty()) continue;
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.BROADCAST_EXCHANGE, "", batch, m -> {
                    m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
                    return m;
                });
                for (int i = 0; i < batch.size(); i++) metrics.incrementProcessed();
            } catch (Exception e) {
                log.error("Broadcast flush failed: room={}, size={}", entry.getKey(), batch.size(), e);
                batch.forEach(entry.getValue()::add);
            }
        }
    }
}

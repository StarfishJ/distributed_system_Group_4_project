package consumer;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

/**
 * Message Consumer: consume messages from node queue and broadcast to WebSocket clients
 *
 * strategy:
 * 1. empty room (no clients online) → directly ACK discard (chat messages are real-time, no offline delivery needed)
 * 2. broadcast at least one success → ACK
 * 3. broadcast all failed → reject (requeue=false), routed to dead letter queue by DLX
 *    do not use NACK requeue, avoid infinite retries and message out of order
 * 4. delivery semantics: at-most-once (guarantee no duplicates, but may lose messages)
 */
@Component
public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    private final MessageBroadcaster broadcaster;
    private final ConsumerMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MessageConsumer(MessageBroadcaster broadcaster, ConsumerMetrics metrics) {
        this.broadcaster = broadcaster;
        this.metrics = metrics;
    }

    /**
     * Listen on all 20 room queues directly.
     * Spring dynamically creates one consumer per queue, distributing load across threads.
     */
    @RabbitListener(queues = {"room.1","room.2","room.3","room.4","room.5",
            "room.6","room.7","room.8","room.9","room.10",
            "room.11","room.12","room.13","room.14","room.15",
            "room.16","room.17","room.18","room.19","room.20"}, ackMode = "MANUAL")
    public void consumeRoomQueue(ClientMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                 Message amqpMessage) {
        String roomId = message.roomId();
        processMessage(message, channel, deliveryTag, roomId, amqpMessage);
    }

    private void processMessage(ClientMessage message, Channel channel,
                                long deliveryTag, String roomId, Message amqpMessage) {

        // get current retry count (RabbitMQ automatically tracks x-death header)
        long retryCount = getRetryCount(amqpMessage);

        String messageJson = convertToJson(message);
        broadcaster.broadcastToRoom(roomId, messageJson)
                .doOnSuccess(success -> {
                    try {
                        if (success) {
                            // at least one client successfully received → ACK
                            channel.basicAck(deliveryTag, false);
                            metrics.incrementProcessed();
                            log.debug("Broadcasted & ACKed: msgId={}, room={}", message.messageId(), roomId);
                        } else {
                            // no clients online: real-time chat, ACK and discard
                            channel.basicAck(deliveryTag, false);
                            log.debug("No online clients, ACKed & dropped: msgId={}, room={}", message.messageId(), roomId);
                        }
                    } catch (Exception e) {
                        log.error("Failed to ACK message", e);
                    }
                })
                .doOnError(error -> {
                    // broadcast error → reject, do not requeue, routed to dead letter queue by DLX
                    log.error("Broadcast error: msgId={}, retry={}", message.messageId(), retryCount, error);
                    try {
                        // requeue=false → message routed to dead letter queue by DLX, will not be retried
                        channel.basicReject(deliveryTag, false);
                    } catch (Exception e) {
                        log.error("Failed to reject message", e);
                    }
                })
                .subscribe();
    }

    /**
     * get retry count from x-death header (RabbitMQ automatically tracks)
     */
    @SuppressWarnings("unchecked")
    private long getRetryCount(Message amqpMessage) {
        Map<String, Object> headers = amqpMessage.getMessageProperties().getHeaders();
        Object xDeath = headers.get("x-death");
        if (xDeath instanceof List<?> deathList && !deathList.isEmpty()) {
            Object first = deathList.get(0);
            if (first instanceof Map<?, ?> deathInfo) {
                Object count = deathInfo.get("count");
                if (count instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return 0;
    }

    private String convertToJson(ClientMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("Failed to serialize message to JSON", e);
            return "{}";
        }
    }
}

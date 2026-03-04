package server;

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
 * Server-side message consumer: listens on the per-node private queue,
 * deserializes messages, and broadcasts to all WebSocket sessions in the room.
 *
 * This makes each Server-v2 instance a full-featured node:
 * - Receives client messages via WebSocket (ChatWebSocketHandler)
 * - Publishes to RabbitMQ (MessagePublisher)
 * - Consumes from RabbitMQ (this class)
 * - Broadcasts to connected clients (MessageBroadcaster)
 */
@Component
public class ServerMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ServerMessageConsumer.class);

    private final MessageBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ServerMessageConsumer(MessageBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @RabbitListener(queues = "#{@nodeQueue.getName()}", ackMode = "MANUAL")
    public void consumeNodeQueue(ClientMessage message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                 Message amqpMessage) {
        String roomId = message.roomId();
        String messageJson = convertToJson(message);

        broadcaster.broadcastToRoom(roomId, messageJson)
                .doOnSuccess(success -> {
                    try {
                        // ACK regardless: real-time chat, no offline delivery needed
                        channel.basicAck(deliveryTag, false);
                        if (success) {
                            log.debug("Broadcasted & ACKed: room={}, type={}", roomId, message.messageType());
                        } else {
                            log.debug("No online clients, ACKed & dropped: room={}", roomId);
                        }
                    } catch (Exception e) {
                        log.error("Failed to ACK message", e);
                    }
                })
                .doOnError(error -> {
                    log.error("Broadcast error: room={}", roomId, error);
                    try {
                        channel.basicReject(deliveryTag, false);
                    } catch (Exception e) {
                        log.error("Failed to reject message", e);
                    }
                })
                .subscribe();
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

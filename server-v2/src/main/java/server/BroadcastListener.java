package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Broadcast Listener: subscribes to {@code broadcastSubscriptionQueue} (fanout anonymous queue by default,
 * or durable topic-bound queue when {@code server.broadcast.targeted=true}).
 */
@Component
public class BroadcastListener {
    private static final Logger logger = LoggerFactory.getLogger(BroadcastListener.class);
    private final ChatWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BroadcastListener(ChatWebSocketHandler handler) {
        this.handler = handler;
    }

    /**
     * Listen on the anonymous auto-delete queue.
     * concurrency can be tuned via 'server.broadcast.concurrency' property.
     */
    /**
     * Listen on the anonymous auto-delete queue.
     * Handles both individual ClientMessage and List<ClientMessage> (batches).
     */
    @RabbitListener(queues = "#{broadcastSubscriptionQueue.name}",
                    concurrency = "${server.broadcast.concurrency:10}")
    public void onBroadcastMessage(Object rawMessage) {
        try {
            if (rawMessage instanceof java.util.List<?> list) {
                for (Object item : list) {
                    if (item instanceof ClientMessage message) {
                        processSingleMessage(message);
                    }
                }
            } else if (rawMessage instanceof ClientMessage message) {
                processSingleMessage(message);
            }
        } catch (Exception e) {
            logger.error("Failed to process broadcast message", e);
        }
    }

    private void processSingleMessage(ClientMessage message) throws Exception {
        logger.debug("Processing broadcast: msgId={}, room={}", message.messageId(), message.roomId());
        String json = objectMapper.writeValueAsString(message);
        handler.broadcastToLocalRoom(message.roomId(), json);
    }
}

package consumer;

/**
 * Message format (matches server-v2 and consumer)
 */
public record ClientMessage(
    String messageId,
    String roomId,
    String userId,
    String username,
    String message,
    String timestamp,
    String messageType,
    String serverId,
    String clientIp
) {}

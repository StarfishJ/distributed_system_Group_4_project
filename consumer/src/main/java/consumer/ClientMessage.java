package consumer;

/**
 * 消息格式（与 server-v2 中的 ClientMessage 一致）
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

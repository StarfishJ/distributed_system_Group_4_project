package server;

// record class is default immutable class in Java
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

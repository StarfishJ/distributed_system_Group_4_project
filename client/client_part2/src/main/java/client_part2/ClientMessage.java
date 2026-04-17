package client_part2;

/**
 * {@code messageId} must be set once at creation time and kept across local retries ({@code offerFirst})
 * so server/DB idempotency ({@code ON CONFLICT (message_id)}) applies.
 */
public class ClientMessage {

    /** Sentinel for queue shutdown; do not send to server. BlockingQueue does not allow null. */
    public static final ClientMessage POISON = new ClientMessage();

    public static boolean isPoison(ClientMessage msg) {
        return msg == POISON;
    }

    /** Stable id for idempotent retries; required for new messages (not POISON). */
    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String roomId;
    private String serializedJson;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getSerializedJson() { return serializedJson; }
    public void setSerializedJson(String serializedJson) { this.serializedJson = serializedJson; }

    /**
     * Fast JSON serialization without ObjectMapper overhead.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"messageId\":\"").append(messageId != null ? messageId : "")
          .append("\",\"userId\":\"").append(userId)
          .append("\",\"username\":\"").append(username)
          .append("\",\"message\":\"").append(message)
          .append("\",\"timestamp\":\"").append(timestamp)
          .append("\",\"messageType\":\"").append(messageType)
          .append("\",\"roomId\":\"").append(roomId)
          .append("\"}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ClientMessage{" +
                "messageId='" + messageId + '\'' +
                ", userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", message='" + message + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", messageType='" + messageType + '\'' +
                ", roomId='" + roomId + '\'' +
                '}';
    }

}

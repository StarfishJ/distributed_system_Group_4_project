package server;

/**
 * Routing tokens for targeted broadcast: Redis SET members = {@link #sanitizeInstanceSuffix(String)} only;
 * RabbitMQ Topic routing key = {@code "srv." + suffix}.
 */
public final class BroadcastRouting {

    public static final String TOPIC_EXCHANGE = "chat.broadcast.topic";
    public static final String ROUTING_PREFIX = "srv.";

    private BroadcastRouting() {}

    /** Safe token for Redis members and queue name suffix (alphanumeric + ._-). */
    public static String sanitizeInstanceSuffix(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "unknown";
        }
        String s = instanceId.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (s.length() > 200) {
            s = s.substring(0, 200);
        }
        return s.isEmpty() ? "unknown" : s;
    }

    public static String topicRoutingKey(String instanceId) {
        return ROUTING_PREFIX + sanitizeInstanceSuffix(instanceId);
    }
}

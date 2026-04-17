package consumer.redis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import consumer.ClientMessage;

/**
 * Cross-restart dedup: rows marked after successful {@code batchUpsert}; broadcast slots claimed before fan-out publish.
 */
public class ConsumerDedupRedisService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerDedupRedisService.class);
    private static final String PERSISTED_PREFIX = "chat:consumer:persisted:";
    private static final String BROADCAST_PREFIX = "chat:consumer:broadcast:";

    private final StringRedisTemplate redis;
    private final long persistedTtlSeconds;
    private final long broadcastTtlSeconds;

    public ConsumerDedupRedisService(StringRedisTemplate redis, long persistedTtlHours, long broadcastTtlHours) {
        this.redis = redis;
        this.persistedTtlSeconds = Math.max(3600L, persistedTtlHours * 3600L);
        this.broadcastTtlSeconds = Math.max(3600L, broadcastTtlHours * 3600L);
    }

    public boolean isPersisted(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(PERSISTED_PREFIX + messageId));
        } catch (Exception e) {
            log.debug("redis isPersisted: {}", e.getMessage());
            return false;
        }
    }

    public void markPersisted(List<ClientMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            for (ClientMessage m : messages) {
                String id = m.messageId();
                if (id == null || id.isBlank()) continue;
                redis.opsForValue().set(PERSISTED_PREFIX + id, "1", Duration.ofSeconds(persistedTtlSeconds));
            }
        } catch (Exception e) {
            log.warn("redis markPersisted failed: {}", e.getMessage());
        }
    }

    /**
     * @return true if this replica may send (first claim), false if already broadcast
     */
    public boolean tryClaimBroadcast(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(BROADCAST_PREFIX + messageId, "1", broadcastTtlSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.debug("redis tryClaimBroadcast: {}", e.getMessage());
            return true;
        }
    }

    public void releaseBroadcast(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            redis.delete(BROADCAST_PREFIX + messageId);
        } catch (Exception e) {
            log.debug("redis releaseBroadcast: {}", e.getMessage());
        }
    }
}

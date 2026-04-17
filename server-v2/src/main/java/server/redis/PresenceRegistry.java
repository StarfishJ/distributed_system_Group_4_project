package server.redis;

import java.time.Duration;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import server.BroadcastRouting;

/**
 * Registers this server's routing suffix in Redis per room ({@code SMEMBERS presence:room:{roomId}}).
 * Members are {@link BroadcastRouting#sanitizeInstanceSuffix(String)} so the Consumer can publish to
 * {@code srv.{suffix}} on {@link BroadcastRouting#TOPIC_EXCHANGE}.
 */
public class PresenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);
    private static final String KEY_PREFIX = "presence:room:";

    private final StringRedisTemplate redis;
    /** Raw identity (logging); Redis stores {@link #presenceToken} only. */
    private final String instanceIdRaw;
    private final String presenceToken;
    private final Duration keyTtl;

    public PresenceRegistry(StringRedisTemplate redis, String serverInstanceIdentity, Duration keyTtl) {
        this.redis = redis;
        this.instanceIdRaw = serverInstanceIdentity != null ? serverInstanceIdentity : "unknown";
        this.presenceToken = BroadcastRouting.sanitizeInstanceSuffix(serverInstanceIdentity);
        this.keyTtl = keyTtl;
        log.info("PresenceRegistry instanceIdRaw={} presenceToken={} keyTtl={}", instanceIdRaw, presenceToken, keyTtl);
    }

    public String getPresenceToken() {
        return presenceToken;
    }

    public void joinRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) return;
        touchRoom(roomId);
    }

    public void leaveRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) return;
        try {
            redis.opsForSet().remove(KEY_PREFIX + roomId, presenceToken);
        } catch (Exception e) {
            log.debug("presence leaveRoom failed: {}", e.getMessage());
        }
    }

    /** Heartbeat: refresh TTL and ensure this server is still listed for each occupied room. */
    public void touchRooms(Collection<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;
        for (String roomId : roomIds) {
            if (roomId != null && !roomId.isBlank()) {
                touchRoom(roomId);
            }
        }
    }

    private void touchRoom(String roomId) {
        String key = KEY_PREFIX + roomId;
        try {
            redis.opsForSet().add(key, presenceToken);
            redis.expire(key, keyTtl);
        } catch (Exception e) {
            log.debug("presence touchRoom failed: {}", e.getMessage());
        }
    }
}

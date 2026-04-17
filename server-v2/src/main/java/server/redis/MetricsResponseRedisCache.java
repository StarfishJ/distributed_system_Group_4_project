package server.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Read-through cache for {@code GET /metrics} JSON payloads. Keys under {@code metrics:v1:}.
 * {@link #invalidateAll()} removes all such keys (e.g. after materialized view refresh).
 */
public class MetricsResponseRedisCache {

    private static final Logger log = LoggerFactory.getLogger(MetricsResponseRedisCache.class);
    static final String PREFIX = "metrics:v1:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<CoarseRedisPayload> COARSE_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricsResponseRedisCache(StringRedisTemplate redis, long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public static String digestKeyPart(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    public Optional<Map<String, Object>> getFull(String logicalCacheKey) {
        try {
            String json = redis.opsForValue().get(PREFIX + "full:" + digestKeyPart(logicalCacheKey));
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            log.warn("Redis metrics full cache get failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putFull(String logicalCacheKey, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            redis.opsForValue().set(PREFIX + "full:" + digestKeyPart(logicalCacheKey), json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis metrics full cache put failed: {}", e.getMessage());
        }
    }

    public Optional<CoarseRedisPayload> getCoarse(String digest) {
        try {
            String json = redis.opsForValue().get(PREFIX + "coarse:" + digest);
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, COARSE_TYPE));
        } catch (Exception e) {
            log.warn("Redis metrics coarse cache get failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putCoarse(String digest, CoarseRedisPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(PREFIX + "coarse:" + digest, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis metrics coarse cache put failed: {}", e.getMessage());
        }
    }

    /**
     * Deletes all keys with prefix {@link #PREFIX} using SCAN (safe for large keyspaces vs KEYS *).
     */
    public void invalidateAll() {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(PREFIX + "*").count(200).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    redis.delete(cursor.next());
                }
            }
        } catch (DataAccessException e) {
            log.warn("Redis metrics cache invalidate failed: {}", e.getMessage());
        }
    }

    /** JSON-serializable coarse-cache row (wide top-N body + fingerprint). */
    public record CoarseRedisPayload(String fpBase, int topNCached, Map<String, Object> body) {}
}

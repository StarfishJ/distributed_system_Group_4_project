package server;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import server.redis.MetricsResponseRedisCache;
import server.redis.MetricsResponseRedisCache.CoarseRedisPayload;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Metrics API data provider - runs core and analytics queries against PostgreSQL.
 * Chat rows are persisted only by the Consumer ({@code INSERT INTO messages}); this class does not write to {@code messages}.
 * Analytics prefer materialized views ({@code mv_user_activity}, {@code mv_room_activity},
 * {@code mv_messages_per_minute}, {@code mv_user_rooms}) when present; refresh after bulk loads.
 * Full responses are cached briefly (Part 3 query-result caching).
 * Optional {@code REFRESH MATERIALIZED VIEW} runs only when {@code refreshMaterializedViews} is true (MV maintenance, not chat inserts).
 * <p>
 * When {@code server.metrics.cache-coarse-by-room=true}, the cache key is only {@code room:{roomId}}; entries store a wide
 * analytics top-N ({@code max(request, server.metrics.analytics-cache-wide-topn)}) and {@code topN} is applied in-memory
 * on hit — better hit rate when adding a shared Redis layer later.
 */
@Service
public class MetricsService {

    /** Primary DB: {@code REFRESH MATERIALIZED VIEW} only (replicas cannot run refresh). */
    private final JdbcTemplate jdbcWrite;
    /** Read path: replica when configured, else same as primary. */
    private final JdbcTemplate jdbcRead;
    private final Cache<String, Map<String, Object>> metricsResponseCache;
    private final Cache<String, RoomCacheEntry> roomCoarseCache;
    private final boolean cacheCoarseByRoom;
    private final int analyticsWideTopN;
    private final Optional<MetricsResponseRedisCache> redisMetrics;

    public MetricsService(
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) @Qualifier("metricsReadJdbcTemplate") JdbcTemplate metricsReadJdbcTemplate,
            ObjectProvider<MetricsResponseRedisCache> redisMetricsProvider,
            @Value("${server.metrics.cache-ttl-seconds:30}") long cacheTtlSeconds,
            @Value("${server.metrics.cache-max-keys:64}") long cacheMaxKeys,
            @Value("${server.metrics.cache-coarse-by-room:false}") boolean cacheCoarseByRoom,
            @Value("${server.metrics.analytics-cache-wide-topn:128}") int analyticsWideTopN) {
        this.jdbcWrite = jdbcTemplate;
        this.jdbcRead = metricsReadJdbcTemplate != null ? metricsReadJdbcTemplate : jdbcTemplate;
        this.cacheCoarseByRoom = cacheCoarseByRoom;
        this.analyticsWideTopN = Math.max(1, analyticsWideTopN);
        this.redisMetrics = Optional.ofNullable(redisMetricsProvider.getIfAvailable());
        this.metricsResponseCache = Caffeine.newBuilder()
                .maximumSize(Math.max(8, cacheMaxKeys))
                .expireAfterWrite(Math.max(1, cacheTtlSeconds), TimeUnit.SECONDS)
                .build();
        this.roomCoarseCache = Caffeine.newBuilder()
                .maximumSize(Math.max(8, cacheMaxKeys))
                .expireAfterWrite(Math.max(1, cacheTtlSeconds), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Run all 8 queries and return combined JSON-friendly structure.
     * Uses default params when table has data; empty results when table is empty.
     * Cached per (roomId, userId, topN, time window) unless {@code server.metrics.cache-coarse-by-room} is enabled
     * (see class javadoc).
     *
     * @param startTimeIso optional ISO-8601 instant (e.g. {@code 2025-01-01T00:00:00Z}); if set, {@code endTimeIso} must be set too
     * @param endTimeIso optional ISO-8601 instant; together they define the window for Core 1–3 and analytics time filters
     * @param refreshMaterializedViews when true, runs {@code REFRESH MATERIALIZED VIEW} on all analytics MVs, then invalidates the metrics cache
     */
    public Map<String, Object> getAllMetrics(String roomId, String userId, int topN,
            String startTimeIso, String endTimeIso, boolean refreshMaterializedViews) {
        if (refreshMaterializedViews) {
            refreshMaterializedViewsInternal();
            metricsResponseCache.invalidateAll();
            roomCoarseCache.invalidateAll();
            redisMetrics.ifPresent(MetricsResponseRedisCache::invalidateAll);
        }
        if (cacheCoarseByRoom) {
            return getAllMetricsCoarseByRoom(roomId, userId, topN, startTimeIso, endTimeIso);
        }
        String cacheKey = (roomId != null ? roomId : "") + "|" + (userId != null ? userId : "") + "|" + topN
                + "|" + (startTimeIso != null ? startTimeIso : "") + "|" + (endTimeIso != null ? endTimeIso : "");
        if (redisMetrics.isPresent()) {
            Optional<Map<String, Object>> hit = redisMetrics.get().getFull(cacheKey);
            if (hit.isPresent()) {
                return hit.get();
            }
            Map<String, Object> computed = computeAllMetrics(roomId, userId, topN, startTimeIso, endTimeIso);
            redisMetrics.get().putFull(cacheKey, computed);
            return computed;
        }
        return metricsResponseCache.get(cacheKey, k -> computeAllMetrics(roomId, userId, topN, startTimeIso, endTimeIso));
    }

    /**
     * One cache slot per room; fingerprint = user + resolved time window. Stores analytics fetched with
     * {@code max(topN, analytics-cache-wide-topn)}; trims lists in-memory for smaller requested topN.
     */
    private Map<String, Object> getAllMetricsCoarseByRoom(String roomId, String userId, int topN,
            String startTimeIso, String endTimeIso) {
        String r = (roomId != null && !roomId.isBlank()) ? roomId : "1";
        String u = (userId != null && !userId.isBlank()) ? userId : "1";
        ResolvedWindow window = resolveTimeRange(startTimeIso, endTimeIso);
        if (window.start() == null || window.end() == null) {
            return computeAllMetrics(roomId, userId, topN, startTimeIso, endTimeIso);
        }
        String fpBase = u + "|" + window.start().toInstant().toString() + "|" + window.end().toInstant().toString();
        String roomKey = "room:" + r;
        String coarseDigest = MetricsResponseRedisCache.digestKeyPart(roomKey + "|" + fpBase);
        if (redisMetrics.isPresent()) {
            Optional<CoarseRedisPayload> cr = redisMetrics.get().getCoarse(coarseDigest);
            if (cr.isPresent() && cr.get().fpBase().equals(fpBase) && topN <= cr.get().topNCached()) {
                roomCoarseCache.put(roomKey, new RoomCacheEntry(fpBase, cr.get().topNCached(), cr.get().body()));
                return sliceAnalyticsToTopN(copyMetricsForResponse(cr.get().body()), topN);
            }
        }
        RoomCacheEntry hit = roomCoarseCache.getIfPresent(roomKey);
        if (hit != null && hit.fpBase.equals(fpBase) && topN <= hit.topNCached) {
            return sliceAnalyticsToTopN(copyMetricsForResponse(hit.body), topN);
        }
        int wide = Math.max(topN, analyticsWideTopN);
        Map<String, Object> wideBody = computeAllMetrics(roomId, userId, wide, startTimeIso, endTimeIso);
        roomCoarseCache.put(roomKey, new RoomCacheEntry(fpBase, wide, wideBody));
        if (redisMetrics.isPresent()) {
            redisMetrics.get().putCoarse(coarseDigest, new CoarseRedisPayload(fpBase, wide, wideBody));
        }
        return sliceAnalyticsToTopN(copyMetricsForResponse(wideBody), topN);
    }

    private static final class RoomCacheEntry {
        final String fpBase;
        final int topNCached;
        final Map<String, Object> body;

        RoomCacheEntry(String fpBase, int topNCached, Map<String, Object> body) {
            this.fpBase = fpBase;
            this.topNCached = topNCached;
            this.body = body;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMetricsForResponse(Map<String, Object> src) {
        Map<String, Object> out = new HashMap<>(src);
        Object aq = src.get("analyticsQueries");
        if (aq instanceof Map<?, ?> amap) {
            Map<String, Object> am = new HashMap<>();
            for (Map.Entry<?, ?> e : amap.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> list) {
                    am.put(String.valueOf(e.getKey()), new ArrayList<>(list));
                } else {
                    am.put(String.valueOf(e.getKey()), v);
                }
            }
            out.put("analyticsQueries", am);
        }
        Object cq = src.get("coreQueries");
        if (cq instanceof Map<?, ?> cm) {
            out.put("coreQueries", new HashMap<>(cm));
        }
        Object params = src.get("params");
        if (params instanceof Map<?, ?> pm) {
            out.put("params", new HashMap<>(pm));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sliceAnalyticsToTopN(Map<String, Object> copy, int topN) {
        Object aq = copy.get("analyticsQueries");
        if (aq instanceof Map<?, ?>) {
            Map<String, Object> am = (Map<String, Object>) copy.get("analyticsQueries");
            trimTopList(am, "2_mostActiveUsers", topN);
            trimTopList(am, "3_mostActiveRooms", topN);
        }
        Object p = copy.get("params");
        if (p instanceof Map) {
            ((Map<String, Object>) p).put("topN", topN);
        }
        return copy;
    }

    private static void trimTopList(Map<String, Object> analytics, String key, int topN) {
        Object o = analytics.get(key);
        if (o instanceof List<?> list && !list.isEmpty()) {
            int n = Math.min(topN, list.size());
            analytics.put(key, new ArrayList<>(list.subList(0, n)));
        }
    }

    /**
     * Ops / cron: refresh analytics MVs on the primary and drop metrics caches (Caffeine + optional Redis).
     * Same effect as {@code GET /metrics?refreshMaterializedViews=true} without computing a response body.
     */
    public void refreshMaterializedViewsForScheduledJob() {
        refreshMaterializedViewsInternal();
        metricsResponseCache.invalidateAll();
        roomCoarseCache.invalidateAll();
        redisMetrics.ifPresent(MetricsResponseRedisCache::invalidateAll);
    }

    /** Refreshes all assignment MVs so analytics and Core 4 match {@code messages}. */
    private void refreshMaterializedViewsInternal() {
        try {
            jdbcWrite.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_rooms");
        } catch (Exception e) {
            jdbcWrite.execute("REFRESH MATERIALIZED VIEW mv_user_rooms");
        }
        jdbcWrite.execute("REFRESH MATERIALIZED VIEW mv_messages_per_minute");
        jdbcWrite.execute("REFRESH MATERIALIZED VIEW mv_user_activity");
        jdbcWrite.execute("REFRESH MATERIALIZED VIEW mv_room_activity");
    }

    private Map<String, Object> computeAllMetrics(String roomId, String userId, int topN,
            String startTimeIso, String endTimeIso) {
        Map<String, Object> result = new HashMap<>();

        try {
            ResolvedWindow window = resolveTimeRange(startTimeIso, endTimeIso);
            Timestamp start = window.start();
            Timestamp end = window.end();
            String rangeSource = window.source();

            if (start == null || end == null) {
                result.put("message", "No messages in database yet. Run a load test first.");
                result.put("coreQueries", Map.of());
                result.put("analyticsQueries", Map.of());
                return result;
            }

            String r = (roomId != null && !roomId.isBlank()) ? roomId : "1";
            String u = (userId != null && !userId.isBlank()) ? userId : "1";

            Map<String, Object> params = new HashMap<>();
            params.put("roomId", r);
            params.put("userId", u);
            params.put("topN", topN);
            params.put("timeRange", Map.of("start", start.toString(), "end", end.toString()));
            params.put("timeRangeSource", rangeSource);
            result.put("params", params);

            // Core queries — same [start, end] for 1–3 (spec: room window, user history optional range, active count window)
            Map<String, Object> core = new HashMap<>();
            core.put("1_roomMessages", core1RoomMessages(r, start, end));
            core.put("2_userHistory", core2UserHistory(u, start, end));
            core.put("3_activeUserCount", core3ActiveUserCount(start, end));
            core.put("3_activeUserIdsSample", core3ActiveUserIdsSample(start, end, 100));
            core.put("4_userRooms", core4UserRooms(u));
            result.put("coreQueries", core);

            // Analytics queries
            Map<String, Object> analytics = new HashMap<>();
            analytics.put("1_messagesPerMinute", analytics1MessagesPerMinute(start, end));
            analytics.put("2_mostActiveUsers", analytics2MostActiveUsers(topN));
            analytics.put("3_mostActiveRooms", analytics3MostActiveRooms(topN));
            analytics.put("4_userParticipationPatterns", analytics4ParticipationPatterns());
            result.put("analyticsQueries", analytics);

        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("message", "Database query failed. Is PostgreSQL running and schema initialized?");
        }
        return result;
    }

    /** For tests or ops: drop cached metrics (e.g. after REFRESH MATERIALIZED VIEW). */
    public void invalidateMetricsCache() {
        metricsResponseCache.invalidateAll();
        roomCoarseCache.invalidateAll();
    }

    private record ResolvedWindow(Timestamp start, Timestamp end, String source) {}

    /**
     * If both {@code startTimeIso} and {@code endTimeIso} are non-blank, use them as the window.
     * Otherwise use MIN/MAX over {@code messages}.
     */
    private ResolvedWindow resolveTimeRange(String startTimeIso, String endTimeIso) {
        boolean hasStart = startTimeIso != null && !startTimeIso.isBlank();
        boolean hasEnd = endTimeIso != null && !endTimeIso.isBlank();
        if (hasStart && hasEnd) {
            try {
                Instant s = Instant.parse(startTimeIso.trim());
                Instant e = Instant.parse(endTimeIso.trim());
                return new ResolvedWindow(Timestamp.from(s), Timestamp.from(e), "request");
            } catch (Exception ex) {
                return fromDatabaseRange("database_min_max_invalid_time_params");
            }
        }
        if (hasStart ^ hasEnd) {
            return fromDatabaseRange("database_min_max_partial_time_params_ignored");
        }
        return fromDatabaseRange("database_min_max");
    }

    private ResolvedWindow fromDatabaseRange(String source) {
        try {
            Timestamp min = jdbcRead.queryForObject(
                "SELECT MIN(timestamp_utc) FROM messages", Timestamp.class);
            Timestamp max = jdbcRead.queryForObject(
                "SELECT MAX(timestamp_utc) FROM messages", Timestamp.class);
            return new ResolvedWindow(min, max, source);
        } catch (Exception e) {
            return new ResolvedWindow(null, null, source);
        }
    }

    private List<Map<String, Object>> core1RoomMessages(String roomId, Timestamp start, Timestamp end) {
        return jdbcRead.query(
            "SELECT message_id, room_id, user_id, username, message, message_type, timestamp_utc " +
            "FROM messages WHERE room_id = ? AND timestamp_utc BETWEEN ? AND ? ORDER BY timestamp_utc ASC LIMIT 1000",
            (rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("messageId", rs.getString("message_id"));
                m.put("roomId", rs.getString("room_id"));
                m.put("userId", rs.getString("user_id"));
                m.put("username", rs.getString("username"));
                m.put("message", rs.getString("message"));
                m.put("messageType", rs.getString("message_type"));
                m.put("timestamp", rs.getTimestamp("timestamp_utc").toInstant().toString());
                return m;
            },
            roomId, start, end
        );
    }

    private List<Map<String, Object>> core2UserHistory(String userId, Timestamp start, Timestamp end) {
        if (start != null && end != null) {
            return jdbcRead.query(
                "SELECT message_id, room_id, user_id, message, timestamp_utc FROM messages " +
                "WHERE user_id = ? AND timestamp_utc BETWEEN ? AND ? ORDER BY timestamp_utc ASC LIMIT 500",
                this::rowToMap, userId, start, end);
        }
        return jdbcRead.query(
            "SELECT message_id, room_id, user_id, message, timestamp_utc FROM messages " +
            "WHERE user_id = ? ORDER BY timestamp_utc ASC LIMIT 500",
            this::rowToMap, userId);
    }

    private Long core3ActiveUserCount(Timestamp start, Timestamp end) {
        return jdbcRead.queryForObject(
            "SELECT COUNT(DISTINCT user_id) FROM messages WHERE timestamp_utc BETWEEN ? AND ?",
            Long.class, start, end);
    }

    /**
     * Lexicographically first {@code limit} distinct {@code user_id} in the window (for console/JSON preview).
     * Spec output for Core 3 remains the scalar count; full distinct set can be huge.
     */
    private List<String> core3ActiveUserIdsSample(Timestamp start, Timestamp end, int limit) {
        return jdbcRead.query(
                "SELECT DISTINCT user_id FROM messages WHERE timestamp_utc BETWEEN ? AND ? ORDER BY user_id ASC LIMIT ?",
                (rs, rowNum) -> rs.getString("user_id"),
                start, end, limit);
    }

    private List<Map<String, Object>> core4UserRooms(String userId) {
        try {
            List<Map<String, Object>> fromMv = jdbcRead.query(
                "SELECT room_id, last_activity FROM mv_user_rooms WHERE user_id = ? ORDER BY last_activity DESC",
                this::mapUserRoomRow,
                userId);
            if (!fromMv.isEmpty()) {
                return fromMv;
            }
        } catch (Exception ignored) {
            // MV missing or unreadable
        }
        return jdbcRead.query(
            "SELECT room_id, MAX(timestamp_utc) AS last_activity FROM messages WHERE user_id = ? "
                + "GROUP BY room_id ORDER BY last_activity DESC",
            this::mapUserRoomRow,
            userId);
    }

    private Map<String, Object> mapUserRoomRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("roomId", rs.getString("room_id"));
        m.put("lastActivity", rs.getTimestamp("last_activity").toInstant().toString());
        return m;
    }

    private Map<String, Object> rowToMap(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("messageId", rs.getString("message_id"));
        m.put("roomId", rs.getString("room_id"));
        m.put("userId", rs.getString("user_id"));
        m.put("message", rs.getString("message"));
        m.put("timestamp", rs.getTimestamp("timestamp_utc").toInstant().toString());
        return m;
    }

    /**
     * Prefer {@code mv_messages_per_minute} (pre-aggregated). Falls back to scanning {@code messages}.
     * After bulk load: {@code REFRESH MATERIALIZED VIEW CONCURRENTLY mv_messages_per_minute;}.
     */
    private List<Map<String, Object>> analytics1MessagesPerMinute(Timestamp start, Timestamp end) {
        try {
            List<Map<String, Object>> fromMv = jdbcRead.query(
                    "SELECT minute_bucket, message_count FROM mv_messages_per_minute "
                            + "WHERE minute_bucket >= date_trunc('minute', CAST(? AS timestamptz)) "
                            + "AND minute_bucket <= date_trunc('minute', CAST(? AS timestamptz)) "
                            + "ORDER BY minute_bucket",
                    this::mapMinuteBucketRow,
                    start, end);
            if (!fromMv.isEmpty()) {
                return fromMv;
            }
        } catch (Exception ignored) {
            // MV missing or query failed
        }
        return jdbcRead.query(
                "SELECT date_trunc('minute', timestamp_utc) AS minute_bucket, COUNT(*) AS message_count "
                        + "FROM messages WHERE timestamp_utc BETWEEN ? AND ? "
                        + "GROUP BY date_trunc('minute', timestamp_utc) ORDER BY minute_bucket",
                this::mapMinuteBucketRow,
                start, end);
    }

    private Map<String, Object> mapMinuteBucketRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("minute", rs.getTimestamp("minute_bucket").toInstant().toString());
        m.put("count", rs.getLong("message_count"));
        return m;
    }

    /** Prefer {@code mv_user_activity}: one row per user, already counted. */
    private List<Map<String, Object>> analytics2MostActiveUsers(int topN) {
        try {
            List<Map<String, Object>> fromMv = jdbcRead.query(
                    "SELECT user_id, message_count FROM mv_user_activity ORDER BY message_count DESC LIMIT ?",
                    this::mapUserActivityRow,
                    topN);
            if (!fromMv.isEmpty()) {
                return fromMv;
            }
        } catch (Exception ignored) {
            // MV missing
        }
        return jdbcRead.query(
                "SELECT user_id, COUNT(*) AS message_count FROM messages GROUP BY user_id "
                        + "ORDER BY message_count DESC LIMIT ?",
                this::mapUserActivityRow,
                topN);
    }

    private Map<String, Object> mapUserActivityRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", rs.getString("user_id"));
        m.put("messageCount", rs.getLong("message_count"));
        return m;
    }

    /** Prefer {@code mv_room_activity}: one row per room. */
    private List<Map<String, Object>> analytics3MostActiveRooms(int topN) {
        try {
            List<Map<String, Object>> fromMv = jdbcRead.query(
                    "SELECT room_id, message_count FROM mv_room_activity ORDER BY message_count DESC LIMIT ?",
                    this::mapRoomActivityRow,
                    topN);
            if (!fromMv.isEmpty()) {
                return fromMv;
            }
        } catch (Exception ignored) {
            // MV missing
        }
        return jdbcRead.query(
                "SELECT room_id, COUNT(*) AS message_count FROM messages GROUP BY room_id "
                        + "ORDER BY message_count DESC LIMIT ?",
                this::mapRoomActivityRow,
                topN);
    }

    private Map<String, Object> mapRoomActivityRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("roomId", rs.getString("room_id"));
        m.put("messageCount", rs.getLong("message_count"));
        return m;
    }

    /**
     * Prefer aggregating {@code mv_user_rooms} (already per user+room): COUNT(*) = distinct users per room,
     * SUM(message_count) = total messages in room. Much smaller than scanning {@code messages}.
     */
    private List<Map<String, Object>> analytics4ParticipationPatterns() {
        try {
            List<Map<String, Object>> fromMv = jdbcRead.query(
                    "SELECT room_id, COUNT(*) AS unique_users, SUM(message_count) AS total_messages "
                            + "FROM mv_user_rooms GROUP BY room_id ORDER BY total_messages DESC",
                    this::mapParticipationRow);
            if (!fromMv.isEmpty()) {
                return fromMv;
            }
        } catch (Exception ignored) {
            // MV missing
        }
        return jdbcRead.query(
                "SELECT room_id, COUNT(DISTINCT user_id) AS unique_users, COUNT(*) AS total_messages "
                        + "FROM messages GROUP BY room_id ORDER BY total_messages DESC",
                this::mapParticipationRow);
    }

    private Map<String, Object> mapParticipationRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("roomId", rs.getString("room_id"));
        m.put("uniqueUsers", rs.getLong("unique_users"));
        m.put("totalMessages", rs.getLong("total_messages"));
        return m;
    }
}

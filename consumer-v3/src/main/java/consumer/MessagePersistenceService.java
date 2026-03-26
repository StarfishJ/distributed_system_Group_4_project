package consumer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Batch upsert messages to PostgreSQL.
 * Uses message_id as idempotency key: ON CONFLICT DO NOTHING.
 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);
    private static final String UPSERT_SQL = """
        INSERT INTO messages (message_id, room_id, user_id, username, message, message_type, timestamp_raw, timestamp_utc, server_id, client_ip)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (message_id) DO NOTHING
        """;

    private final DataSource dataSource;
    private final ConsumerMetrics metrics;

    public MessagePersistenceService(DataSource dataSource, ConsumerMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    /**
     * Batch upsert. Idempotent via ON CONFLICT (message_id) DO NOTHING.
     */
    public void batchUpsert(List<ClientMessage> batch) {
        if (batch.isEmpty()) return;

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(UPSERT_SQL)) {

            for (ClientMessage m : batch) {
                Timestamp ts = parseTimestamp(m.timestamp());
                ps.setString(1, m.messageId());
                ps.setString(2, m.roomId());
                ps.setString(3, m.userId());
                ps.setString(4, m.username());
                ps.setString(5, m.message());
                ps.setString(6, m.messageType());
                ps.setString(7, m.timestamp());
                ps.setTimestamp(8, ts);
                ps.setString(9, m.serverId());
                ps.setString(10, m.clientIp());
                ps.addBatch();
            }
            ps.executeBatch();
            metrics.incrementPersisted(batch.size());
        } catch (Exception e) {
            log.error("Batch upsert failed: size={}, error={}", batch.size(), e.getMessage());
            metrics.incrementDbWriteError();
            throw new RuntimeException(e);
        }
    }

    private static Timestamp parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return Timestamp.from(Instant.now());
        try {
            Instant instant = Instant.parse(raw);
            return Timestamp.from(instant);
        } catch (DateTimeParseException e) {
            try {
                return Timestamp.valueOf(raw.replace("Z", "").replace("T", " "));
            } catch (Exception e2) {
                return Timestamp.from(Instant.now());
            }
        }
    }
}

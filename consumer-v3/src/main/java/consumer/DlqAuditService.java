package consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists rows to {@code dlq_audit_evidence} (no indexes — evidence only).
 */
@Service
public class DlqAuditService {

    private static final String INSERT = "INSERT INTO dlq_audit_evidence (message_id, raw_payload) VALUES (?, ?)";

    private final JdbcTemplate jdbc;

    public DlqAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordEvidence(String messageId, String rawPayload) {
        jdbc.update(INSERT, messageId, rawPayload);
    }
}

-- Lightweight evidence table for chat.dead-letter when x-death count exceeds threshold (no indexes by design).
CREATE TABLE IF NOT EXISTS dlq_audit_evidence (
    message_id  VARCHAR(128),
    raw_payload   TEXT
);

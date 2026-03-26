# Database — CS6650 Assignment 3

PostgreSQL schema and scripts for chat message persistence.

## Quick Start

### 1. Start PostgreSQL (Docker)

```bash
cd database
docker compose up -d
```

Connection: `jdbc:postgresql://localhost:5432/chatdb` (user: `chat`, password: `chat`)

### 2. Initialize Schema

**No psql installed?** Use Docker (run from `database` directory):

```powershell
.\init.ps1
```

**With psql installed**:

```bash
psql -h localhost -U chat -d chatdb -f 01_schema.sql
psql -h localhost -U chat -d chatdb -f 02_indexes.sql
psql -h localhost -U chat -d chatdb -f 03_views.sql
```

### 3. Refresh Materialized Views (after bulk load)

```sql
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_rooms;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_messages_per_minute;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_user_activity;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_room_activity;
```

## File Layout

| File | Purpose |
|------|---------|
| `01_schema.sql` | `messages` table definition |
| `02_indexes.sql` | Indexes for core queries + analytics |
| `03_views.sql` | Materialized views (user_rooms, messages_per_minute, etc.) |
| `04_init_all.sql` | Combined init (for `\i` from psql) |
| `queries.sql` | Reference queries for Metrics API |
| `docker-compose.yml` | Local PostgreSQL 16 |

## Schema Summary

**messages** (primary table):

| Column | Type | Notes |
|--------|------|-------|
| message_id | VARCHAR(64) PK | UUID, idempotency key |
| room_id | VARCHAR(64) | |
| user_id | VARCHAR(64) | |
| username | VARCHAR(128) | |
| message | TEXT | |
| message_type | VARCHAR(32) | TEXT, JOIN, LEAVE |
| timestamp_raw | VARCHAR(64) | Original ISO-8601 |
| timestamp_utc | TIMESTAMPTZ | Parsed for queries |
| server_id | VARCHAR(64) | |
| client_ip | VARCHAR(45) | |

**Upsert (idempotent)**:
```sql
INSERT INTO messages (message_id, room_id, user_id, username, message, message_type, timestamp_raw, timestamp_utc, server_id, client_ip)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
ON CONFLICT (message_id) DO NOTHING;
```

**Batch upsert** (JDBC): Use `addBatch()` + `executeBatch()` with the above, or a multi-row `INSERT ... ON CONFLICT`.

## Index Strategy

- `idx_messages_room_time`: Core query 1 (room + time range)
- `idx_messages_user_time`: Core query 2 (user history)
- `idx_messages_timestamp_user`: Core query 3 (active user count)
- `idx_messages_user_room_time`: Core query 4 (user's rooms)
- `idx_messages_room_count`: Analytics (room activity)
- Materialized views: `mv_user_rooms`, `mv_messages_per_minute`, `mv_user_activity`, `mv_room_activity`

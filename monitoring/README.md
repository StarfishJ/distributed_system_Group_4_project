# Monitoring — Assignment 3

Use these artifacts for **Metrics API & Results Log**, **system stability**, and **database performance** sections of the Performance Report.

## 1. Scripts (this folder)

| Script | Purpose |
|--------|---------|
| `collect-metrics.ps1` | Saves `/health` and `/metrics` JSON to `monitoring/results/` (timestamped). Use `-RefreshMviews` after bulk load. |
| `collect-metrics.sh` | Same on macOS/Linux; optional second arg `--refresh`. |
| `postgres-snapshot.sql` | Run in `psql` for connection counts, buffer hit ratio, `messages` table stats. |

**PowerShell example (from repo root):**

```powershell
.\monitoring\collect-metrics.ps1 -BaseUrl http://localhost:8080 -RefreshMviews
```

## 2. Runtime dashboards (manual)

- **RabbitMQ Management**: queue depth over time (screenshots for “queue depth graphs”).
- **Spring Boot**: `GET /health` on server-v2 (ALB health path in deployment notes).
- **Metrics API**: `GET /metrics` (optionally `?refreshMaterializedViews=true`) — also printed/saved by `client_part2` as `results/metrics_last.json`.
- **Database host CPU / memory**: **`htop`** (or `top`) on the PostgreSQL EC2/container host is acceptable evidence for the performance report; capture during baseline / stress if you do not use RDS Performance Insights.

## 3. Client-side exports

- Default directory: `results/` — `per_message_metrics.csv`, `throughput_over_time.csv`, `metrics_last.json`.
- **Avoid overwriting** between baseline (500k) and stress (1M): run the client with **`--results-tag baseline500k`** and **`--results-tag stress1m`** so files land under `results/baseline500k/` and `results/stress1m/` respectively (same flags work with `--results-tag=name`).
- `throughput_over_time.csv` — chart input (see `results/generate_throughput_chart.py`).
- `per_message_metrics.csv` — latency percentile analysis (P50/P95/P99).

## 4. Load tests

See `../load-tests/README.md` for batch matrix and baseline/stress commands; CSV output under `load-tests/results/` when you run `run-batch-optimization.ps1`.

**Endurance target rate:** approximate **80% of max msg/s** by scaling client total messages and wall-clock (e.g. longer run with similar worker count), or by throttling if you add sleep — the repo does not enforce a rate cap; state your method in the report.

## 5. Endurance test — minimal monitoring (assignment gap)

Full leak/pool/disk automation is not in-repo. For a ~30 min run at ~80% of peak throughput, you can still document:

| Concern | Practical check |
|---------|-----------------|
| Memory | Consumer/server **RSS** in `htop` or Docker stats at start vs end; optional JVM **GC log** (`-Xlog:gc*`) for drift. |
| Connection pool | Run `postgres-snapshot.sql` (or `pg_stat_activity`) at **start / mid / end** of the run; compare active sessions to pool max (e.g. 10). |
| Disk | **`df -h`** on DB volume before/after; watch PostgreSQL data directory growth. |
| Degradation | Save **`throughput_over_time.csv`** with a **`--results-tag endurance-30m`** run and compare early vs late buckets in a chart. |

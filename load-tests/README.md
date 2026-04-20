# Load Tests — Assignment 3

## Batch Size Optimization (Part 2)

**Test matrix:** batch-size × flush-interval

| batch_size | flush_interval_ms |
|------------|-------------------|
| 100        | 100, 500, 1000    |
| 500        | 100, 500, 1000    |
| 1000       | 100, 500, 1000    |
| 5000       | 100, 500, 1000    |

**Total:** 12 combinations × 2 runs each = 24 runs (~30-45 min)

**Goal:** Find optimal balance between throughput and latency. Assignment says "up to 5" runs per combo — 2 runs gives stability check without excessive time.

### How to Run

1. Ensure Database, RabbitMQ, Server-v2 are running.
2. Run the script from project root:
   ```powershell
   cd "D:\6650\assignment 3"
   .\load-tests\run-batch-optimization.ps1
   ```
3. For each combination, the script will:
   - Tell you to restart consumer-v3 with specific params
   - Wait for you to press Enter
   - Run the client load test (50,000 messages, no warmup)
   - Parse throughput and P95 latency from output
   - Append results to `load-tests/results/batch_optimization.csv`

### Manual Run

```powershell
# 1. Restart consumer-v3 with params (stop existing first with Ctrl+C)
mvn spring-boot:run -f consumer-v3/pom.xml -Dconsumer.batch-size=500 -Dconsumer.flush-interval-ms=100

# 2. Run load test
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 50000 --no-warmup
```

## Load Test Suite (Part 4)

The assignment requires two main JMeter scenarios plus optional endurance verification.

### 1) Baseline Performance Test

| Item | Target |
|------|--------|
| Concurrent users | 1000 |
| API calls | 100,000 |
| Duration | 5 minutes |
| Operation mix | 70% reads / 30% writes |
| Required metrics | Average response time, p95, p99, throughput, error rate |

### 2) Stress Test

| Item | Target |
|------|--------|
| Concurrent users | 500 |
| API calls | 200,000–500,000 |
| Duration | 30 minutes |
| Operation mix | Mixed read/write operations |
| Required metrics | System breaking point, CPU, memory, database connections, throughput, error rate |

### 3) Endurance Test

| Item | Target |
|------|--------|
| Duration | ~30 minutes |
| Throughput | Around 80% of maximum sustainable throughput |
| Purpose | Check degradation over time |

### Recommended commands

```powershell
# Baseline (recommended JMeter plan)
.\load-tests\jmeter\run-http-via-alb.ps1 -TestPlan "assignment-baseline-1000u-100k-5min-rw7030.jmx"

# Stress (recommended JMeter plan)
.\load-tests\jmeter\run-http-via-alb.ps1 -TestPlan "assignment-stress-500u-30min-rw-mixed.jmx"

# Optional endurance run
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 --endurance-minutes=30 --results-tag endurance-30m
```

### Metrics to collect for each run

- Average response time
- p95 and p99 response times
- Throughput (requests/second)
- Error rate percentage
- CPU utilization
- Memory utilization
- Database connections

### Notes

- The JMeter HTTP plans in `load-tests/jmeter/` approximate the assignment mix by using `/health` as the light read path and `/metrics` as the heavier read/analytics path.
- If you want literal chat write traffic, use a WebSocket-based load plan instead of HTTP-only samplers.

### Pre-run improvement checklist (recommended order)

1. **Clarify test scope**
   - State explicitly that the submitted baseline/stress runs use **HTTP JMeter**.
   - Note that `/health` is the light read path and `/metrics` is the heavier read/analytics path.
   - Add a short sentence in the report that this is an **approximation** of the assignment’s read/write mix, while the real chat write path is WebSocket-based.

2. **Freeze consumer batch settings**
   - Pick one final `consumer.batch-size` and `consumer.flush-interval-ms` combination from the optimization results.
   - Use the same values for all final baseline/stress/endurance runs so the results are comparable.

3. **Reduce avoidable metrics jitter**
   - Keep `refreshMaterializedViews=true` out of the main benchmark path.
   - Use metrics refresh only for a pre-run validation snapshot or a post-run collection step.

4. **Use targeted broadcast for multi-instance runs**
   - If you run more than one `server-v2` instance, enable the targeted broadcast path so you do not fan out to every node unnecessarily.
   - Keep fan-out as the fallback path only when Redis/presence is unavailable.

5. **Collect resource utilization evidence**
   - Record CPU, memory, and database connection counts during each scenario.
   - Also capture RabbitMQ queue depth if available.
   - Save the screenshots or logs with matching names: `baseline`, `stress`, and `endurance`.

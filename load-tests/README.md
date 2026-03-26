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

| Test        | Messages | Duration        | Purpose                    |
|-------------|----------|-----------------|----------------------------|
| Baseline    | 500,000  | To completion   | Establish baseline metrics |
| Stress       | 1,000,000| To completion   | Find bottlenecks           |
| Endurance   | Sustained| ~30 min         | 80% max throughput, check degradation |

### Commands

```powershell
# Baseline
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 500000

# Stress
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 1000000

# Endurance: run for ~30 min at target rate (adjust message count to achieve ~80% of max throughput)
```

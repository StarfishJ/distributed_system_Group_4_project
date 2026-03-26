# Client Part 2 - Performance Analysis Client

Advanced load testing client with detailed performance analysis including latency measurements (P95/P99), per-room throughput, and message type distribution.

## Features

- **All Part 1 Features:** Basic load testing with warmup and main phases
- **Latency Analysis:** Per-message latency tracking with P95/P99 percentiles
- **Per-Room Metrics:** Throughput breakdown by room (1-20)
- **Message Type Distribution:** Tracks JOIN/TEXT/LEAVE message counts
- **Throughput Chart Data:** Exports CSV data for visualization (10-second buckets)
- **Detailed Statistics:** Mean, median, min, max latencies

## Build

```bash
cd client/client_part2
mvn clean compile
```

Or package as JAR:

```bash
mvn clean package
```

## Run

### Basic Usage

```bash
mvn exec:java -Dexec.args="http://your-server:8080"
```

### With Custom Message Count

```bash
mvn exec:java -Dexec.args="http://your-server:8080 1000000"
```

### Skip Warmup

```bash
mvn exec:java -Dexec.args="http://your-server:8080 --no-warmup"
```

### Separate result folders (500k vs 1M — no overwrite)

Writes `per_message_metrics.csv`, `throughput_over_time.csv`, and `metrics_last.json` under `results/<tag>/`:

```bash
java -jar target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag baseline500k http://your-server:8080 500000
java -jar target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag stress1m http://your-server:8080 1000000
```

### Run from JAR

```bash
java -cp target/classes:target/dependency/* client_part2.ChatClientMain http://your-server:8080
```

## Configuration

Edit `src/main/resources/client.properties` to customize:

```properties
# Warmup phase
warmup.threads=64
warmup.messagesPerThread=500

# Main phase
main.threads=128
main.totalMessages=500000

# Queue and connection
queue.capacity=10000
connection.staggerEvery=4
connection.staggerMs=80

# Worker parameters (high throughput configuration)
worker.pipelineSize=2000     # In-flight messages per connection
worker.batchSize=250          # Messages per WebSocket frame
worker.echoTimeoutMs=15000
worker.acquireLoopTimeoutMs=30000
worker.connectTimeoutSec=10
worker.maxConnectRetries=10
worker.backoffMs=500,1000,2000,4000,8000
```

**Note:** Changes to `client.properties` require recompilation (`mvn clean compile`) to take effect.

## Architecture

Same as Part 1, with additional features:
- **Latency Tracking:** Records send→echo time for each message (sampled 1 in 1000 to reduce overhead)
- **Per-Room Aggregation:** Tracks success counts per room for throughput analysis
- **Message Type Tracking:** Counts JOIN/TEXT/LEAVE messages separately
- **CSV Export:** Automatically exports throughput data to `results/throughput_over_time.csv`

## Output

The client prints:
- Warmup phase metrics
- Main phase progress updates (every 10 seconds) with bucket throughput
- Final detailed summary with:
  - Successful messages, business errors, failed messages
  - Total runtime and throughput
  - **Response Time Statistics:**
    - Mean, Median latencies
    - P95, P99 percentiles
    - Min, Max latencies
  - **Per-Room Throughput:** Throughput breakdown for each room (1-20)
  - **Message Type Distribution:** JOIN/TEXT/LEAVE percentages

## Example Output

```
Part 2 Client (Performance Analysis) — server: http://3.95.208.36:8080, messages: 500000
Warmup: 64 workers × 500 msgs (32000 total)
Main:   128 workers × 500000 msgs total
---
[Warmup] Done in 6948 ms (success=32000, fail=0, 4605.6 msg/s)
---
[Main] progress: 53186 / 100000 sent (ok=53186, fail=0) — 5023.2 msg/s (bucket: 5318.6 msg/s)
[Main] Finished.

========== Part 2 Metrics (Performance Analysis) ==========
Successful messages (responses): 500000
Business errors (status=ERROR):  0
Failed messages (no response):   0
Total runtime (ms):  5983
Throughput (msg/s):  83570.12
Total connections:   64
Connection failures: 0
Reconnections:       0

--- Response Time (successful messages) ---
Mean (ms):           101.19
Median (ms):         101.00
95th percentile (ms): 121
99th percentile (ms): 127
Min (ms):            73
Max (ms):            174

--- Throughput per room (msg/s) ---
  Room  1: 251.11 msg/s (5045 msgs)
  Room  2: 246.48 msg/s (4952 msgs)
  ...
  Room 20: 254.19 msg/s (5107 msgs)

--- Message Type Distribution ---
  TEXT: 894447 (89.45%)
  JOIN: 5472 (5.47%)
  LEAVE: 5081 (5.08%)
  UNKNOWN: 0 (0.00%)
===========================================================

[Main] Throughput data exported to: results/throughput_over_time.csv (5 data points)
```

## Throughput Chart Generation

The client automatically exports throughput data to `results/throughput_over_time.csv` with 10-second bucket averages.

### Generate Chart

```bash
cd results
python generate_throughput_chart.py
```

**Requirements:**
- Python 3.x
- matplotlib: `pip install matplotlib`

The script generates `throughput_chart.png` showing throughput over time.

See `results/README.md` for detailed instructions.

## Performance Tuning

### High Throughput Configuration
- Increase `worker.pipelineSize` (default: 2000)
- Increase `worker.batchSize` (default: 250)
- Increase `main.threads` (default: 128)

### Low Latency Configuration
- Decrease `worker.pipelineSize` (e.g., 400)
- Decrease `worker.batchSize` (e.g., 50)
- Trade-off: Lower throughput but more consistent latency

## Troubleshooting

- **Connection errors:** Check server is running and accessible
- **High failure rate:** Check network latency, increase `worker.echoTimeoutMs`
- **Low throughput:** Increase `worker.pipelineSize` and `worker.batchSize`
- **CSV not generated:** Ensure `results/` directory exists or run from project root

## Requirements

- Java 17+
- Maven 3.6+
- Server running on specified URL (default: `http://localhost:8080`)
- Python 3.x + matplotlib (for chart generation)

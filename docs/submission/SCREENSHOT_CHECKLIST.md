# Assignment 3 — Where to insert screenshots

Use this when converting `PerformanceReport.md` / design doc to PDF.

| ID | File / section | What to paste |
|----|----------------|---------------|
| FIG 1 | Performance §1.1 | Chart from `results/throughput_over_time.csv` |
| FIG 2 | Performance §1.2 | Latency histogram/CDF from `results/per_message_metrics.csv` |
| FIG 3–5 | Performance §1.4 | DB CPU, consumer CPU/RAM, connections |
| **FIG 6** | Performance §2.1 | **RabbitMQ Management** queue graph (required for “queue depth over time”) |
| FIG 7–8 | Performance §2.2 | pgAdmin / SQL stats |
| FIG 9 | Performance §2.3 | JVM or Docker memory |
| FIG 10 | Performance §2.4 | `pg_stat_activity` or Hikari metrics |
| **FIG 11** | Performance §4 | **Your terminal** showing Part 2 summary + `/metrics` (e.g. `terminals/4.txt` region) |

**Quick win:** FIG 6 + FIG 11 satisfy most “show your work” expectations for stability and metrics.

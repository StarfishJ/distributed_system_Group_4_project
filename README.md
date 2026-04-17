# CS6650 Assignment 2 & 3: Distributed WebSocket Chat System

This repository contains the implementation for Assignment 2 (distributed chat) and Assignment 3 (persistence + Metrics API).

## Repository Structure

```
/
|-- server-v2/           # Server with Queue integration, Broadcast, Metrics API (A3)
|-- consumer/            # Assignment 2 Consumer (RabbitMQ -> Broadcast Fanout)
|-- consumer-v3/         # Assignment 3 Consumer with DB persistence (Write-Behind)
|-- database/            # Assignment 3: PostgreSQL schema, indexes, views, init scripts
|-- client/
|   |-- client_part2/    # Load testing client with latency/throughput analysis
|-- monitoring/          # A3: metric collection scripts + DB snapshot SQL + results/
|-- configuration/       # A3: index of all config file locations (submission)
|-- deployment/          # Terraform (AWS/EKS), systemd + ALB notes
|-- k8s/                 # K8s: in-cluster Postgres, RabbitMQ, Redis, server-v2, consumer-v3
|-- results/             # CSV exports, metrics JSON, report screenshots (e.g. 50w/, 100w/)
|-- load-tests/          # A3: batch optimization script, test configs, results CSV
|-- DatabaseDesignDocument.md   # A3 DB design (≤2 pages PDF target)
|-- PerformanceReport.md        # A3 performance report (+ embedded figures under results/)
|-- docs/submission/SCREENSHOT_CHECKLIST.md
```

## Quick Start: Common Commands

### Assignment 3 Local Run Order (full stack)

**Development / test (run from project root):**
```powershell
# 1. Database + RabbitMQ
cd database; docker compose up -d; .\init.ps1

# 2. Server
mvn spring-boot:run -f server-v2/pom.xml

# 3. Consumer-v3 (new terminal)
mvn spring-boot:run -f consumer-v3/pom.xml

# 4. Client load test (new terminal, build first)
mvn clean package -DskipTests -f client/client_part2/pom.xml
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 500000

# Optional: keep each run’s files separate (recommended for baseline vs stress)
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag baseline500k http://localhost:8080 500000
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag stress1m http://localhost:8080 1000000
```

**Production (JAR):**
1. `cd database` → `docker compose up -d` → `.\init.ps1`
2. Start RabbitMQ
3. `java -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar`
4. `java -jar consumer-v3/target/chat-consumer-v3-0.0.1-SNAPSHOT.jar`
5. Run load test, then `curl http://localhost:8080/metrics?refreshMaterializedViews=true` or `.\monitoring\collect-metrics.ps1 -RefreshMviews`  
   (On **Kubernetes**, HTTP MV refresh is **disabled** by default — use **scheduled refresh** in `server-v2` or enable the property only for trusted dev.)

### AWS EKS — pure in-cluster Postgres / RabbitMQ / Redis

1. **`deployment/terraform`**: `terraform apply` → EKS + VPC (see that **README**; do not enable external MQ/DB if you use in-cluster data plane).  
2. **`k8s/README.md`**: build/tag/push **`cs6650-server-v2`** and **`cs6650-consumer-v3`**, `kustomize edit set image …`, install **Ingress NGINX**, `kubectl apply -k k8s/`.

---

## Assignment 3 — Submission checklist (quick map)

| Requirement | Location |
|-------------|----------|
| `/database` | `database/*.sql`, `docker-compose.yml`, `init.ps1` |
| `/consumer-v3` | `consumer-v3/` |
| `/monitoring` | `monitoring/collect-metrics.ps1`, `collect-metrics.sh`, `postgres-snapshot.sql`, `README.md` |
| `/load-tests` | `load-tests/README.md`, `run-batch-optimization.ps1`, `results/*.csv` (generated) |
| Database design doc (≤2 pages PDF) | `DatabaseDesignDocument.md` — trim if needed when exporting |
| Performance report | `PerformanceReport.md` |
| All configuration referenced | `configuration/README.md` → paths into `application.properties` / Java config |

---

### 1. Build All Modules
```bash
# Build Server, Consumer, Client
mvn clean package -DskipTests -f server-v2/pom.xml
mvn clean package -DskipTests -f consumer/pom.xml
mvn clean package -DskipTests -f client/client_part2/pom.xml

# Assignment 3: Build consumer-v3
mvn clean package -DskipTests -f consumer-v3/pom.xml
```

### 2. Assignment 3: Start Database & Initialize Schema
```powershell
# Start PostgreSQL (Docker)
cd database
docker compose up -d

# Initialize schema (no psql required, uses Docker)
.\init.ps1
```
Connection: `jdbc:postgresql://localhost:5432/chatdb` (user: `chat`, password: `chat`)

### 3. Run Server-v2 (EC2/Local)
```bash
# dev
mvn spring-boot:run -f server-v2/pom.xml

# pack into jar and run
java -Dserver.id=Node-1 -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar

# EC2
java -Dserver.id=EC2-Node-A -Dspring.rabbitmq.host=<RABBITMQ_IP> -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar
```

### 4. Run Consumer

**Assignment 2 (no persistence):**
```bash
java -jar consumer/target/chat-consumer-0.0.1-SNAPSHOT.jar
```

**Assignment 3 (with DB persistence):**
```bash
# dev
mvn spring-boot:run -f consumer-v3/pom.xml

# pack into jar and run
java -jar consumer-v3/target/chat-consumer-v3-0.0.1-SNAPSHOT.jar

# run with parameters
mvn spring-boot:run -f consumer-v3/pom.xml -Dconsumer.batch-size=1000 -Dconsumer.flush-interval-ms=500
```

**EC2:** Add `-Dspring.rabbitmq.host=<RABBITMQ_IP>` and `-Dspring.datasource.url=jdbc:postgresql://<DB_IP>:5432/chatdb`

### 5. Run Client (Load Test)

Client is a plain Java app — build before running. **All commands assume project root.**

```powershell
# 1. Build
mvn clean package -DskipTests -f client/client_part2/pom.xml

# 2. Run load test
# Local (Server at localhost:8080)
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar

# Specify Server URL + message count
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 500000

# Skip warmup
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://localhost:8080 500000 --no-warmup

# ALB (WebSocket URL is still http(s)://<ALB_DNS> — same as browser)
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://<ALB_DNS> 1000000

# Separate output folders (per_message_metrics.csv, throughput_over_time.csv, metrics_last.json)
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag baseline500k http://<ALB_DNS> 500000
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar --results-tag stress1m http://<ALB_DNS> 1000000
```

**Positional args:** `[ServerURL] [MessageCount]`. Defaults: `http://localhost:8080`, **500,000** messages.  
**Flags:** `-n` / `--no-warmup` · `--results-tag <name>` or `--results-tag=<name>` (writes under `results/<name>/`) · `--metrics-json` · `--metrics-expand` · `--no-metrics-save` · `--no-refresh-mviews`

**After the run (Assignment 3):** the client calls **`GET /metrics`** (with `refreshMaterializedViews=true` unless disabled), then a **follow-up** request with a real **`userId`** (from analytics “most active user” when available) so **Core 2 / Core 4** are populated under synthetic load. It prints a human-readable summary and saves **`metrics_last.json`** (under `results/` or `results/<tag>/`).  
**Latency percentiles (P50/P95/P99)** in the **ASSIGNMENT 2 PERFORMANCE REPORT SUMMARY** block are computed from **`per_message_metrics.csv`** (OK rows) after the CSV writer shuts down.

**Endurance (~30 min wall clock):** `--endurance-minutes=30` (optional `--endurance-target-msg-per-sec=<rate>`). Example: `java -jar ... http://<ALB_DNS> --endurance-minutes=30 --results-tag endurance-30m`. After long runs, `GET /metrics?refreshMaterializedViews=true` via ALB may **504** — use `--no-refresh-mviews` or curl from inside VPC.

### 6. Metrics API (Assignment 3)
After Server-v2 and consumer-v3 are running, call:
```bash
# Default params (roomId=1, userId=1, topN=10) — Core 2/4 may be empty if user "1" has no rows
curl http://localhost:8080/metrics

# After bulk load, refresh materialized views first
curl "http://localhost:8080/metrics?refreshMaterializedViews=true"

# With params (pick a userId that exists in your data)
curl "http://localhost:8080/metrics?roomId=5&userId=100&topN=20"
```
Returns JSON with **coreQueries** (1–4) + **analyticsQueries**. The **client** automates a second call with a resolved `userId` for demos; for manual `curl`, set **`userId`** explicitly if needed.

### 7. Batch Optimization (Assignment 3)
```powershell
.\load-tests\run-batch-optimization.ps1
```
Tests batch-size (100,500,1000,5000) × flush-interval (100,500,1000ms). For each combo, restart consumer-v3 with params, then press Enter to run client. Results → `load-tests/results/batch_optimization.csv`.

### 8. System Monitoring
Please refer to [monitoring/README.md](./monitoring/README.md) for details on using the RabbitMQ Management Dashboard and Spring Actuator metrics.

## Verification & Health
- **Health Check**: `GET http://<INSTANCE_IP>:8080/health`
- **Metrics API (A3)**: `GET http://<INSTANCE_IP>:8080/metrics` — core + analytics queries
- **Actuator**: `GET http://<INSTANCE_IP>:8080/actuator/metrics`
- **RabbitMQ Dashboard**: `http://<RMQ_IP>:15672` — use a dedicated user (e.g. `chatmq`) on EC2; from laptop, **SSH port-forward** `15672` if the SG does not expose 15672 publicly

## AWS: Terraform apply → EC2 setup → load test → `terraform destroy`

Use this when you want a **repeatable** path: create AWS infra, install MQ/DB on EC2, run JARs, test from your laptop, then tear down to stop billing. More detail: [`deployment/terraform/README.md`](./deployment/terraform/README.md) and [`deployment/terraform/STEP_BY_STEP.md`](./deployment/terraform/STEP_BY_STEP.md).

### Prerequisites (your laptop)

- **Terraform** ≥ 1.5, **AWS CLI v2**, **`aws configure`** (same region as `terraform.tfvars`, e.g. `us-east-1`).
- **SSH key pair** (e.g. `ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\cs6650-aws -N '""'` on Windows).
- **This repo** pushed to GitHub (or be ready to `scp` the project). Terraform **does not** deploy your application code.

### 1) Configure Terraform (local, not committed)

```powershell
cd deployment\terraform
copy terraform.tfvars.example terraform.tfvars
```

Edit **`terraform.tfvars`** (see example for all fields):

- `aws_region` — same as `aws configure`.
- `public_key_path` — absolute path to **`.pub`** (Windows: `C:/Users/You/.ssh/cs6650-aws.pub`), **or** comment it and set `key_name` to an existing EC2 Key Pair name.
- **`allowed_ssh_cidr`** — your **current** public IPv4 + `/32` (e.g. `203.0.113.50/32`). If your home IP changes, SSH will time out until you update this and run `terraform apply` again.
- `server_count`, `enable_alb` — usually `2` and `true` for ALB + multi-server tests.

`terraform.tfvars` is **gitignored**; do not commit secrets.

### 2) Create infrastructure

```powershell
cd deployment\terraform
terraform init
terraform plan
terraform apply
```

Confirm with `yes`. Wait until EC2, ALB, VPC, etc. are created (several minutes).

### 3) Save outputs (you will need these for every deploy)

```powershell
terraform output
terraform output -raw rabbitmq_password
terraform output -raw postgres_password
```

Copy to a safe place:

| Output | Use |
|--------|-----|
| `alb_dns_name` | Client load test: `http://<alb_dns_name>` (port **80**) |
| `rabbitmq_private_ip` | `spring.rabbitmq.host` |
| `postgres_private_ip` | JDBC host: `jdbc:postgresql://<IP>:5432/chatdb` |
| `*_public_ip` | **SSH** only (use **private** IPs inside Spring `-D` for app traffic) |
| Passwords | Only if you use **RDS** / **Amazon MQ** (`use_rds_postgres` / `use_amazon_mq = true`). For **self-hosted EC2** Postgres/RabbitMQ, you set DB/MQ users yourself (see below). |

### 4) SSH into EC2 (PowerShell)

Use the **public IP** from `terraform output` and your **private key**:

```powershell
ssh -i "$env:USERPROFILE\.ssh\cs6650-aws" ec2-user@<RABBITMQ_OR_ANY_PUBLIC_IP>
```

If **Connection timed out**, fix **Security Group** / **allowed_ssh_cidr** (your IP changed) or use the correct current public IP.

### 5) Terraform does **not** install RabbitMQ / PostgreSQL on EC2 (default `use_amazon_mq = false`, `use_rds_postgres = false`)

You must install and configure them yourself (one-time per instance). Typical pattern:

**A) PostgreSQL (on the Postgres EC2)**

- Install PostgreSQL (e.g. Amazon Linux: `sudo dnf install -y postgresql15-server` and init/start, or use **Docker**).
- Create database **`chatdb`** and a user (e.g. `chat` / password you choose) matching `consumer-v3` `application.properties`.
- Apply schema from this repo: copy `database/01_schema.sql`, `02_indexes.sql`, `03_views.sql` to the instance (e.g. `scp` or `git clone` the repo), then:

```bash
psql -h 127.0.0.1 -U chat -d chatdb -f 01_schema.sql
# ... 02_indexes.sql, 03_views.sql
```

If `sudo -u postgres psql` is used, run scripts from `/tmp` with world-readable permissions (see `monitoring/postgres-snapshot.sql` notes in `PerformanceReport.md`).

**B) RabbitMQ (on the RabbitMQ EC2)**

- Example with Docker: `docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management` with `guest`/`guest` **only if** your Spring config matches; **or** create a dedicated user and set `spring.rabbitmq.username/password` in `application.properties` + JAR env.

### 6) Get code on each EC2

```bash
sudo dnf install -y git
git clone https://github.com/<YOUR_ORG>/<YOUR_REPO>.git
cd <YOUR_REPO>
```

Or build on your laptop and **`scp`** the JARs (`server-v2/target/*.jar`, `consumer-v3/target/*.jar`).

### 7) Build and run (private IPs from `terraform output`)

**Replace** `<MQ_PRIVATE>`, `<PG_PRIVATE>` with **`rabbitmq_private_ip`** and **`postgres_private_ip`**. If your JDBC user/password are not `chat`/`chat`, add `-Dspring.datasource.username` / `-Dspring.datasource.password`.

**Consumer (Assignment 3)** — run on **consumer** EC2:

```bash
mvn clean package -DskipTests -f consumer-v3/pom.xml
nohup java -Xmx2g \
  -Dspring.rabbitmq.host=<MQ_PRIVATE> \
  -Dspring.datasource.url=jdbc:postgresql://<PG_PRIVATE>:5432/chatdb \
  -jar consumer-v3/target/chat-consumer-v3-0.0.1-SNAPSHOT.jar \
  > /tmp/consumer.log 2>&1 &
```

**Server-v2** — run on **each server** EC2 (adjust `server.id` per node):

```bash
mvn clean package -DskipTests -f server-v2/pom.xml
nohup java -Xmx1g \
  -Dserver.id=Node-1 \
  -Dspring.rabbitmq.host=<MQ_PRIVATE> \
  -Dspring.datasource.url=jdbc:postgresql://<PG_PRIVATE>:5432/chatdb \
  -jar server-v2/target/chat-server-0.0.1-SNAPSHOT.jar \
  > ~/server.log 2>&1 &
```

Metrics API (`/metrics`) needs the **datasource** on the server.

### 8) Verify ALB and targets

- Wait until **Target Group** shows **Healthy** for all server instances (ALB → Target groups → **Health checks** path `/health` on port 8080).
- From your laptop:

```powershell
curl http://<alb_dns_name>/health
```

### 9) Load test from your laptop (project root)

```powershell
mvn clean package -DskipTests -f client/client_part2/pom.xml
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://<alb_dns_name> 500000
java -jar client/client_part2/target/chat-client-part2-0.0.1-SNAPSHOT.jar http://<alb_dns_name> --endurance-minutes=30 --results-tag endurance-30m
```

Use `--no-refresh-mviews` if `/metrics` returns **504** after long runs.

### 10) DB snapshot on Postgres EC2 (optional)

```bash
sudo cp /home/ec2-user/postgres-snapshot.sql /tmp/postgres-snapshot.sql
sudo chmod 644 /tmp/postgres-snapshot.sql
sudo -u postgres psql -d chatdb -f /tmp/postgres-snapshot.sql
```

Script: `monitoring/postgres-snapshot.sql` (copy to EC2 first).

### 11) Tear down (stop charges)

When reports and screenshots are saved locally:

```powershell
cd deployment\terraform
terraform destroy
```

Type `yes`. This removes EC2, ALB, VPC, etc. **EBS volumes** are typically destroyed with instances; confirm in plan. To **bring the stack back later**, repeat from **§2)** `terraform apply` and **§5–9)** (new public IPs; update `allowed_ssh_cidr` if your IP changed).

---

## Configuration
- **Server**: `server-v2/src/main/resources/application.properties` (includes datasource for Metrics API)
- **Consumer**: `consumer/src/main/resources/application.properties`
- **Consumer-v3**: `consumer-v3/src/main/resources/application.properties` (`consumer.batch-size`, `consumer.flush-interval-ms`)
- **Database**: `database/README.md` — schema, init
- **Client**: `client/client_part2/src/main/resources/client.properties`

---

## Submission Artifacts
- **Architecture**: [DesignDocument.md](./DesignDocument.md)
- **Database Design (A3)**: [DatabaseDesignDocument.md](./DatabaseDesignDocument.md) — links to load-test figures in `results/`
- **Performance Report (A3)**: [PerformanceReport.md](./PerformanceReport.md) — throughput/latency tables + embedded screenshots (`results/50w/`, `results/100w/`, …)
- **Screenshot / CSV layout**: [results/README.md](./results/README.md)
- **Deployment Guide**: [deployment/alb-setup.md](./deployment/alb-setup.md)
- **Terraform (AWS)**: [deployment/terraform/README.md](./deployment/terraform/README.md) · [一步步操作](./deployment/terraform/STEP_BY_STEP.md)
- **Monitoring**: [monitoring/README.md](./monitoring/README.md)

# EC2 部署路径（方案一：AWS Academy / 无 EKS）

适用于 **`enable_eks = false`**：Terraform 创建 VPC、RabbitMQ/Postgres **EC2**（或托管 MQ/RDS）、**可选 Amazon ElastiCache for Redis**（默认开启，替代数据面 EC2 上的 Docker Redis）、Consumer、Server×N、**ALB**。实例 **user_data** 已安装 **Java 17、git、Docker**；**RabbitMQ / PostgreSQL** 仍由 `deploy-ec2.ps1` 用 Docker 拉起（托管模式除外）；**若 `use_elasticache_redis = false`**，Redis 由 Docker 跑在 Postgres 或 Rabbit EC2 上（见下）。

## 0. 一键部署（本机 PowerShell）

在 **`terraform apply` 成功后**，于 **`deployment/terraform`** 目录执行（私钥与 `terraform.tfvars` 里 `public_key_path` 成对）：

```powershell
.\deploy-ec2.ps1 -KeyPath "C:\Users\you\.ssh\cs6650-aws"
# 已编译过 JAR 时：
.\deploy-ec2.ps1 -KeyPath "..." -SkipBuild
# 只起数据面 / 只更新应用：
.\deploy-ec2.ps1 -KeyPath "..." -Phase DataPlane
.\deploy-ec2.ps1 -KeyPath "..." -Phase Apps
```

脚本会：`terraform output -json` 读连接信息 → `scp` 仓库 **`database/`** … → **server/consumer** 走 **S3 + ASG**：本机 **`aws s3 cp`** 上传 `server-v2.jar`、`server.env`、`consumer-v3.jar`、`consumer.env` 到 **`app_artifacts_bucket`/`app_artifacts_s3_prefix`**，再 **`aws autoscaling start-instance-refresh`** 滚动替换实例（需安装 **AWS CLI** 且凭证与 Terraform 一致）。默认先对本机执行 **`mvn clean package -DskipTests -B`**，并检查 JAR 内是否含 **`BOOT-INF/`**；**`-SkipBuild`** 时若 **pom/源码比 JAR 新会直接报错**。

需要本机已安装 **OpenSSH 客户端**、**terraform**、**AWS CLI**、**mvn**（除非 `-SkipBuild` 且你确认 JAR 已是最新）。**首次 `terraform apply` 后**若 S3 尚无制品，ASG 里实例会在 user_data 中轮询 S3；**尽快**跑一次 **`deploy-ec2.ps1`**（或至少 **`-Phase Apps`**）上传制品。

Postgres 机若 **yum 无 `docker-compose-plugin`**，脚本会从 GitHub 安装 **`docker-compose` v2 二进制**；远程 shell 通过 **scp 上传 LF 脚本** 执行，避免 Windows CRLF 导致 `rm` / `set` 报错。

## 1. 清理与创建

若曾尝试 EKS 导致 state 里残留资源，先在同一目录执行：

```powershell
terraform destroy
terraform init
terraform plan
terraform apply
terraform output
```

## 2. 记下 `terraform output`

- **`alb_dns_name`**：JMeter / 客户端访问 **HTTP 80**（转到 server **8080**）。Terraform 里已配：**目标组** `8080`、**健康检查** `GET /health`（200）、**会话粘性** `lb_cookie`（多实例时 WebSocket/会话落同一台）、**`idle_timeout`** 默认 **3600s**（避免默认 60s 把长时间无流量的连接踢掉）。可在 `terraform.tfvars` 里改 `alb_idle_timeout` / `alb_health_check_path`。
- **`rabbitmq_private_ip`**、**`postgres_address`**（或 private_ip）：内网地址，给 Spring 配置用。
- **`redis_elasticache_primary_address`**：ElastiCache Redis 终端节点（`use_elasticache_redis=true` 时）；`deploy-ec2.ps1` 会写入 `SPRING_DATA_REDIS_HOST` / `PORT`。
- **可选 `jmeter_public_ip` / `jmeter_ssh_example`**：在 `terraform.tfvars` 里设 **`enable_jmeter_ec2 = true`** 时创建专用 **负载机 EC2**（与 ALB **同 region / 同 VPC**），首次启动 user-data 会安装 **`/opt/apache-jmeter`**（默认 5.6.3）与 **Java 17**。从笔记本 **`scp`** 上传 `load-tests/jmeter/*.jmx` 与 `jmeter-run.properties`，在 EC2 上对 **`alb_dns_name:80`** 跑非 GUI JMeter，可把 JTL/HTML 拉回本地写报告。默认机型 **`t3.large`**（`instance_type_jmeter` 可调）。详见仓库 **`load-tests/jmeter/README.md`**「同区域 EC2」小节。

## 3. 数据面（推荐 Docker）

在 **Postgres EC2**（SSH：`ssh -i <私钥> ec2-user@<postgres 公网 IP>`）：

```bash
sudo su - ec2-user
git clone <你的仓库> && cd assignment*/database   # 或用 scp 拷贝 database/ 目录
docker compose up -d postgres pgbouncer-write pgbouncer-read redis
# 等待 postgres 就绪后，在 database/ 目录执行（容器名与 docker-compose.yml 一致：chatdb-pg）
for f in 01_schema.sql 02_indexes.sql 03_views.sql 05_dlq_audit.sql; do
  cat "$f" | docker exec -i chatdb-pg psql -v ON_ERROR_STOP=1 -U chat -d chatdb
done
# 不要用管道执行 04_init_all.sql（内部 \\ir 在 stdin 模式下不可用）；需要时可进容器用 psql -f
```

在 **RabbitMQ EC2**：

```bash
# 与 deploy-ec2.ps1 一致：chatmq 用户；内存水位用 conf.d（3.13 起旧版 RABBITMQ_VM_MEMORY_* 环境变量会直接导致容器退出）
printf '%s\n' 'vm_memory_high_watermark.relative = 0.4' > /home/ec2-user/99-memory.conf
docker run -d --name chat-mq --hostname chat-mq -p 5672:5672 -p 15672:15672 \
  -v /home/ec2-user/99-memory.conf:/etc/rabbitmq/conf.d/99-memory.conf:ro \
  -e RABBITMQ_DEFAULT_USER=chatmq -e RABBITMQ_DEFAULT_PASS=chatmq \
  rabbitmq:3.13-management
```

（或与 `database/docker-compose.yml` 中 `rabbitmq` 服务等价配置。）

**RDS + EC2 RabbitMQ（本仓库 `deploy-ec2.ps1`）：** 在 **RabbitMQ 同一台 EC2** 上会再起 **`chat-redis`**（`redis:7-alpine`，端口 **6379**），server/consumer 的 `SPRING_DATA_REDIS_HOST` 指向 **RabbitMQ 私网 IP**。若你只用 RDS、没有 Postgres EC2，Redis **不在** Postgres 那台机上。

## 4. Server（每台 server EC2）

将 **Redis** 指到 **Postgres EC2 上 compose 起的 Redis**，或 **RDS 场景下指到 RabbitMQ EC2 上的 `chat-redis`**（安全组 `internal` 已允许实例间互通）：

```bash
export SPRING_RABBITMQ_HOST=<rabbitmq_private_ip>
export SPRING_RABBITMQ_USERNAME=chatmq
export SPRING_RABBITMQ_PASSWORD=chatmq
export SPRING_DATASOURCE_URL=jdbc:postgresql://<postgres_private_ip>:6432/chatdb
export SPRING_DATASOURCE_USERNAME=chat
export SPRING_DATASOURCE_PASSWORD=chat
# 与 k8s 单库示例一致：关读副本，/metrics 走主库（有 PgBouncer 仍可用）
export SERVER_METRICS_READ_REPLICA_ENABLED=false
export SPRING_DATA_REDIS_HOST=<postgres_private_ip>
# 然后运行 server-v2 JAR，监听 8080
```

## 5. Consumer EC2

```bash
export SPRING_RABBITMQ_HOST=<rabbitmq_private_ip>
export SPRING_RABBITMQ_USERNAME=chatmq
export SPRING_RABBITMQ_PASSWORD=chatmq
export SPRING_DATASOURCE_URL=jdbc:postgresql://<postgres_private_ip>:6432/chatdb?rewriteBatchedInserts=true
export SPRING_DATASOURCE_USERNAME=chat
export SPRING_DATASOURCE_PASSWORD=chat
export SPRING_DATA_REDIS_HOST=<postgres_private_ip>
# 运行 consumer-v3 JAR，端口 8081
```

## 6. 验证

- 浏览器或 `curl http://<alb_dns_name>/health`
- Consumer 日志无连不上 MQ/DB/Redis

作业结束请 **`terraform destroy`**。

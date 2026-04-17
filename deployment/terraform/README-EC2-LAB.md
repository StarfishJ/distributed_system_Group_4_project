# EC2 部署路径（方案一：AWS Academy / 无 EKS）

适用于 **`enable_eks = false`**：Terraform 创建 VPC、RabbitMQ/Postgres **EC2**、Consumer、Server×N、**ALB**。实例 **user_data** 已安装 **Java 17、git、Docker**；**RabbitMQ / PostgreSQL / Redis** 需你用 Docker 或包管理器自行拉起（见下）。

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

脚本会：`terraform output -json` 读 IP → `scp` 仓库 **`database/`** … → 上传 JAR。默认先对本机执行 **`mvn clean package -DskipTests -B`**（`chat-server-*.jar`、`chat-consumer-v3-*.jar`），并检查 JAR 内是否含 **`BOOT-INF/`**（Spring Boot fat jar）；**`-SkipBuild`** 时若 **pom/源码比 JAR 新会直接报错**。

需要本机已安装 **OpenSSH 客户端**、**terraform**、**mvn**（除非 `-SkipBuild` 且你确认 JAR 已是最新）。

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

- **`alb_dns_name`**：JMeter / 客户端访问 **HTTP 80**（转到 server **8080**）。
- **`rabbitmq_private_ip`**、**`postgres_address`**（或 private_ip）：内网地址，给 Spring 配置用。

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

## 4. Server（每台 server EC2）

将 **Redis** 指到 **Postgres 那台机** 上 compose 起的 Redis（安全组 `internal` 已允许实例间互通）：

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

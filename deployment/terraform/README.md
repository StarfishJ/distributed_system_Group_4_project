# Terraform: CS6650 Chat on AWS

**AWS Academy / Vocareum（`voclabs` 无 `iam:CreateRole`）无法自建 EKS，请用 EC2 路径：** **`enable_eks = false`**，步骤与 Docker 起 MQ/DB 见 **`README-EC2-LAB.md`**。

**有完整 IAM 的账户（默认）：** **`enable_eks = true`**，数据面只用 **`k8s/` 里的 Postgres + RabbitMQ + Redis**，**不要**打开 **`create_ec2_rabbitmq` / `create_ec2_postgres` / `use_amazon_mq` / `use_rds_postgres`**。Terraform 只负责 **VPC + EKS**；应用见 **`k8s/README.md`**（ECR、`kubectl apply -k`）。

---

## 空白 AWS 账户能一键建好什么？

**可以。** 本目录的 Terraform 会创建（无需你先在控制台点安全组 / VPC）：

| 资源 | 说明 |
|------|------|
| **VPC** | 10.0.0.0/16，DNS 已开 |
| **Internet Gateway + 公有路由** | 实例可出网 |
| **2 个公有子网** | 跨 2 个 AZ（ALB / EKS 可用） |
| **Amazon EKS**（默认 **`enable_eks = true`**） | 托管控制面 + **managed node group**；**server-v2 / consumer-v3** 用仓库 **`k8s/`** 部署。数据面推荐 **集群内** Postgres/Rabbit/Redis（与 `k8s/kustomization.yaml` 一致），此时 **默认不再创建** 独立的 RabbitMQ/Postgres **EC2**（省重复费用）。安全组仍允许节点访问 **5672/15672/5432**（供 Amazon MQ / RDS 或自建 EC2 数据面使用） |
| **安全组 `alb`** | 仅 **`enable_eks = false`** 时：入站 80；与 **EC2 + ALB** 路径一起用 |
| **安全组 `internal`** | 入站 22；8080 **来自 ALB**（EC2 模式）；**来自 EKS 节点** 的 5672/15672/5432（数据面在集群外时）；实例间 **互通** |
| **EC2 Key Pair** | 若设置 `public_key_path`，由 Terraform **上传你的公钥** |
| **EC2 数据面**（可选） | **`enable_eks = false`** 时：默认仍创建 RabbitMQ、Postgres **EC2**（除非 `use_amazon_mq` / `use_rds_postgres`）+ Consumer 1 + Server `server_count` + **ALB**。**`enable_eks = true`** 时：默认 **不** 创建这两台 MQ/DB EC2；需要外置 broker/DB 时设 **`create_ec2_rabbitmq = true`** / **`create_ec2_postgres = true`** 或改用托管服务 |
| **ALB + Target Group** | 仅 **EC2 应用模式**（`enable_eks = false` 且 `enable_alb = true`） |
| **User-data** | 数据面 EC2：Java 17 + git |

**托管服务（`use_amazon_mq` / `use_rds_postgres`）：**

- **Amazon MQ** / **RDS**：与 EC2 自建二选一；密码见 `terraform output`。
- **`enable_eks = true` 且未开托管、且未设 `create_ec2_*`**：`terraform output` 里 **`rabbitmq_mode` / `postgres_mode` 为 `kubernetes`**，`rabbitmq_host` / `postgres_jdbc_url` 为 **null** 属正常 — 应用连集群内 **`rabbitmq-service`** / **`postgres-service`**（见 `k8s/config.yaml`）。

**EC2 应用模式（`enable_eks = false`）** 仍需你在数据面 EC2 上装 RabbitMQ/PostgreSQL 或用托管服务；**EKS + 集群内数据面** 则 **`kubectl apply -k ../../k8s/`** 后依赖 manifest 内嵌/挂载的初始化 SQL（见 `k8s/postgres.yaml`）。

**费用：** Amazon MQ + RDS 比两台小 EC2 更贵，作业结束请 `terraform destroy`。

---

## 前置条件

1. [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5（需支持 `check` 块）  
2. AWS 凭证：`aws configure` 或 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`（账户需 EC2/VPC/ELB 等权限，**AdministratorAccess** 最省事）  
3. 本机一对 SSH 密钥（空白账户推荐让 Terraform 创建 Key Pair）：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/cs6650-aws -N ""
```

---

## 使用步骤（推荐：空白账户）

```bash
cd deployment/terraform
cp terraform.tfvars.example terraform.tfvars
```

编辑 `terraform.tfvars`：

- `public_key_path` = 你的公钥绝对路径（Windows 例：`C:/Users/你/.ssh/cs6650-aws.pub`）  
- `allowed_ssh_cidr` = 你当前公网 IP + `/32`（查 IP：搜索 “what is my ip”）  

```bash
terraform init
terraform plan
terraform apply
terraform output
```

**EKS 模式（默认）** 在 apply 后：

```bash
aws eks update-kubeconfig --region us-east-1 --name $(terraform output -raw eks_cluster_name)
kubectl apply -k ../../k8s/
```

- **镜像：** 将 `server-v2` / `consumer-v3` 推到 **ECR**，把 `k8s/server.yaml` / `consumer.yaml` 的 `image` 改为 ECR 地址，并把 `imagePullPolicy` 改为 **`Always`**（不要用 `Never`）。  
- **Ingress：** 当前 `k8s/ingress.yaml` 为 **NGINX Ingress** class —— 需在 EKS 上安装 **ingress-nginx**（Helm），或改为 **AWS Load Balancer Controller**。  
- **数据面在集群内时** 不必改 `chat-config` 里的 `MQ_HOST` / `DB_HOST`（已是 `*-service`）。
- **RabbitMQ 用 EC2 + gp3：** 设置 **`create_ec2_rabbitmq = true`**、**`use_amazon_mq = false`**。Terraform 为该实例配置 **gp3 根卷**（变量 **`rabbitmq_root_volume_*`**），减轻持久化队列下的 **gp2 IOPS credit** 瓶颈。在 EC2 上安装并启动 RabbitMQ 后，把 EKS 里 **`MQ_HOST`**（或 `SPRING_RABBITMQ_HOST`）改为 **`terraform output -raw rabbitmq_private_ip`**，并**不要**再同时使用集群内 `rabbitmq-service`（避免双 broker）。

SSH 用**对应私钥**：

```bash
ssh -i ~/.ssh/cs6650-aws ec2-user@<rabbitmq_public_ip>
```

---

## 若已在控制台创建过 Key Pair

在 `terraform.tfvars` 里注释掉 `public_key_path`，改为：

```hcl
key_name = "你在控制台里的密钥名"
```

---

## 输出说明

- **压测**：`http://<alb_dns_name>`（80）  
- **互连**：Spring / JDBC 里用 `terraform output` 中的 **private_ip**  

---

## 费用与销毁

会产生 EC2 + ALB 费用。不用时：

```bash
terraform destroy
```

---

## 变量速查

| 变量 | 说明 |
|------|------|
| `enable_eks` | 默认 `true`：创建 **EKS**，不在 EC2 上跑 server/consumer |
| `eks_*` | 集群版本、节点类型、`desired_size`（见 `variables.tf`） |
| `public_key_path` | 空白账户：填公钥路径，Terraform 创建 Key Pair |
| `key_name` | 已有 Key Pair 时用 |
| `server_count` | 仅 **`enable_eks = false`**：Server EC2 台数 |
| `enable_alb` | 仅 **EC2 模式**：`false` 可省 ALB，直连某台 server 调试 |
| `create_ec2_rabbitmq` | 默认 **null**：EKS 模式下 **不** 建 RabbitMQ EC2；`true` 可强制外置 EC2 broker |
| `create_ec2_postgres` | 默认 **null**：EKS 模式下 **不** 建 Postgres EC2；`true` 可强制外置 EC2 DB |
| `rabbitmq_root_volume_*` | 自建 RabbitMQ **EC2** 的根卷：**gp3** + 大小/IOPS/吞吐（默认 40GiB、3000 IOPS、125 MiB/s） |
| `allowed_ssh_cidr` | **必须**改为你的公网 **IP/32**；**禁止** `0.0.0.0/0` 除非同时设 `permit_unsafe_wide_ssh=true`（仅临时实验） |
| `permit_unsafe_wide_ssh` | 默认 `false`；仅当故意对全世界开放 SSH 时设 `true`（与 `0.0.0.0/0` 配合） |

---

## 与作业 A3

1. Postgres 实例：安装 PG、建库、跑 `database/*.sql`  
2. RabbitMQ 实例：安装并启动 RabbitMQ  
3. 各 Server + Consumer：部署 JAR（见仓库根 README）  
4. Client：`java -jar ... http://<alb_dns> 500000`  

可选扩展：RDS、Amazon MQ、ASG — 当前模板以 **作业可交付** 为主。

# Terraform: CS6650 Chat on AWS

## 空白 AWS 账户能一键建好什么？

**可以。** 本目录的 Terraform 会创建（无需你先在控制台点安全组 / VPC）：

| 资源 | 说明 |
|------|------|
| **VPC** | 10.0.0.0/16，DNS 已开 |
| **Internet Gateway + 公有路由** | 实例可出网 |
| **2 个公有子网** | 跨 2 个 AZ（ALB / EKS 可用） |
| **Amazon EKS**（默认 **`enable_eks = true`**） | 托管控制面 + **managed node group**（默认 `t3.medium`）；**server-v2 / consumer-v3** 用仓库 `k8s/*.yaml` 部署；安全组已放行节点访问 **RabbitMQ / PostgreSQL**（EC2 或 Amazon MQ / RDS） |
| **安全组 `alb`** | 仅 **`enable_eks = false`** 时：入站 80；与 **EC2 + ALB** 路径一起用 |
| **安全组 `internal`** | 入站 22；8080 **来自 ALB**（EC2 模式）；**来自 EKS 节点** 的 5672/15672/5432（数据面在集群外时）；实例间 **互通** |
| **EC2 Key Pair** | 若设置 `public_key_path`，由 Terraform **上传你的公钥** |
| **EC2 数据面**（可选） | **`enable_eks = false`** 时：RabbitMQ、Postgres、Consumer 各 1；Server-v2 共 `server_count` 台 + **ALB** |
| **ALB + Target Group** | 仅 **EC2 应用模式**（`enable_eks = false` 且 `enable_alb = true`） |
| **User-data** | 数据面 EC2：Java 17 + git |

**托管服务（在 `terraform.tfvars` 里设 `use_amazon_mq` / `use_rds_postgres` 为 `true`；变量默认 `false`，即默认仍为 EC2 跑 MQ/DB）：**

- **Amazon MQ**：托管 RabbitMQ（`mq.t3.micro`），用户名见变量 `amazon_mq_username`，密码见 `terraform output -raw rabbitmq_password`。
- **RDS PostgreSQL**：`db.t3.micro`，库名默认 `chatdb`，用户默认 `chat`，密码见 `terraform output -raw postgres_password`。

**仍需你手动：** 在能访问 RDS 的机器上（例如 SSH 到 **consumer** 或某台 **server**）用 `psql` 执行 `database/` 下 SQL 初始化表结构；各 EC2 上 `git pull`、打包并启动 **server-v2 / consumer-v3**（JAR 里填 `terraform output` 给出的 `rabbitmq_host`、`postgres_jdbc_url` 及账号密码）。

若 `use_amazon_mq = false` / `use_rds_postgres = false`，则仍会创建对应 **EC2**，需自己在上面安装 RabbitMQ / PostgreSQL。

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
kubectl apply -f ../../k8s/
```

（镜像、ConfigMap 里 MQ/DB 地址需按 `terraform output` 填好；入口用 **AWS Load Balancer Controller + Ingress**，与旧版「Terraform ALB → EC2」不同。）

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
| `allowed_ssh_cidr` | 尽量写你的 IP/32 |

---

## 与作业 A3

1. Postgres 实例：安装 PG、建库、跑 `database/*.sql`  
2. RabbitMQ 实例：安装并启动 RabbitMQ  
3. 各 Server + Consumer：部署 JAR（见仓库根 README）  
4. Client：`java -jar ... http://<alb_dns> 500000`  

可选扩展：RDS、Amazon MQ、ASG — 当前模板以 **作业可交付** 为主。

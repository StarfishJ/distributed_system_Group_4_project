# AWS + Terraform 一步步操作指南（从零开始）

面向：**全新 AWS 账户**、**本机 Windows**（Mac/Linux 步骤类似，路径换成 `~` 即可）。

---

## 第一步：准备 AWS 账户与权限

1. 登录 [AWS 控制台](https://console.aws.amazon.com/)。
2. 右上角选 **区域**（例如 `美国东部 us-east-1`），后面所有资源都在这个区域，**不要混区域**。
3. 创建 **IAM 用户**（不要用根账号长期访问）：
   - 服务搜索 **IAM** → **用户** → **创建用户**。
   - 勾选 **编程访问**（Access key）。
   - 权限：作业期间可勾选 **AdministratorAccess**（省事）；交作业后可删用户或撤销密钥。
4. 创建完成后 **立刻保存**：
   - `Access key ID`
   - `Secret access key`（只显示一次）

---

## 第二步：本机安装工具

### 2.1 安装 Terraform

1. 打开：<https://developer.hashicorp.com/terraform/install>
2. 下载 **Windows** 安装包并安装。
3. 新开 **PowerShell**，执行：

```powershell
terraform -version
```

应显示 `Terraform v1.5.x` 或更高（需 ≥ 1.5）。

### 2.2 安装 AWS CLI

1. 打开：<https://aws.amazon.com/cli/>
2. 安装 **AWS CLI v2**。
3. 验证：

```powershell
aws --version
```

### 2.3 配置 AWS 凭证

在 PowerShell 执行（把密钥换成你的）：

```powershell
aws configure
```

按提示输入：

| 提示 | 填什么 |
|------|--------|
| AWS Access Key ID | 第一步里的 Access key ID |
| AWS Secret Access Key | 第一步里的 Secret |
| Default region name | 与控制台一致，例如 `us-east-1` |
| Default output format | `json` |

验证：

```powershell
aws sts get-caller-identity
```

能返回 `Account`、`Arn` 即成功。

---

## 第三步：生成本机 SSH 密钥（给 EC2 登录用）

在 PowerShell 执行（路径可改，下面用 `cs6650-aws`）：

```powershell
ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\cs6650-aws -N '""'
```

- **私钥**：`C:\Users\你的用户名\.ssh\cs6650-aws`（保密，等同密码）
- **公钥**：`C:\Users\你的用户名\.ssh\cs6650-aws.pub`（会交给 Terraform 上传到 AWS）

---

## 第四步：查你的公网 IP（用于 SSH 安全组）

浏览器打开任意「查 IP」网站，记下 IPv4，例如 `203.0.113.50`。

后面在配置里写成：**`203.0.113.50/32`**（只放行你自己，比 `0.0.0.0/0` 安全）。

---

## 第五步：准备 Terraform 变量文件

1. 在资源管理器中打开项目目录：

   `你的项目\deployment\terraform\`

2. **复制** `terraform.tfvars.example`，改名为 **`terraform.tfvars`**（不要提交到 Git，已在 `.gitignore`）。

3. 用记事本 / VS Code 编辑 `terraform.tfvars`，至少改这几项：

```hcl
aws_region       = "us-east-1"                    # 与 aws configure 的区域一致
project_name     = "cs6650-chat"
allowed_ssh_cidr = "你的公网IP/32"                 # 第四步
server_count     = 2
enable_alb       = true

# 公钥路径：改成你第三步的真实路径（用正斜杠）
public_key_path = "C:/Users/你的Windows用户名/.ssh/cs6650-aws.pub"
```

- **不要**同时写 `key_name`，除非你在 AWS 控制台已经手动创建过 Key Pair。
- 若已有关键对，可注释掉 `public_key_path`，只写：`key_name = "控制台里的名字"`。

---

## 第六步：执行 Terraform

在 PowerShell 中：

```powershell
cd "D:\6650\assignment 3\deployment\terraform"
```

（若你的项目不在 D 盘，改成你的实际路径。）

```powershell
terraform init
terraform plan
```

- `plan` 无报错后：

```powershell
terraform apply
```

输入 **`yes`** 确认。

- 等待约 **3～8 分钟**（首次拉 AMI、建 ALB 会久一点）。

---

## 第七步：看输出、记下来

```powershell
terraform output
```

重点记下：

| 输出项 | 用途 |
|--------|------|
| `alb_dns_name` | 客户端压测：`http://这个地址`（端口 80） |
| `rabbitmq_private_ip` | 各 JAR 里 `spring.rabbitmq.host` |
| `postgres_private_ip` | JDBC：`jdbc:postgresql://这个IP:5432/chatdb` |
| `server_private_ips` | 排查时可 SSH 到某台 server |
| `consumer_private_ip` | Consumer 配置用 |

公网 IP 也在 output 里，**SSH 登录**用公网 IP + 第三步的**私钥**：

```powershell
ssh -i $env:USERPROFILE\.ssh\cs6650-aws ec2-user@rabbitmq的公网IP
```

（把 `rabbitmq的公网IP` 换成 `terraform output` 里 `rabbitmq_public_ip`。）

---

## 第八步：在每台机器上部署应用（Terraform 不会自动做）

先执行：

```powershell
terraform output
terraform output -raw rabbitmq_password
terraform output -raw postgres_password
```

### 若使用托管服务（在 `terraform.tfvars` 中设置 `use_amazon_mq = true`、`use_rds_postgres = true`）

1. **RDS 初始化**：RDS 已创建库 `chatdb`，但**表结构要你自己执行**。SSH 到任意一台 **server** 或 **consumer**（与 RDS 同 VPC），安装客户端后连 RDS：
   ```bash
   sudo dnf install -y postgresql15
   PGPASSWORD='<postgres_password>' psql -h <terraform output postgres_address> -U chat -d chatdb -f database/01_schema.sql
   # 再执行 02_indexes.sql、03_views.sql（或合并后的脚本）
   ```
2. **RabbitMQ**：使用 `terraform output rabbitmq_host` 作为 `spring.rabbitmq.host`，端口 **5672**，用户名 `rabbitmq_username` 输出，密码为 `rabbitmq_password`。
3. **每台 server / consumer**：`git pull`、`mvn package`，启动 JAR 时带上 MQ 与 JDBC（密码用上面 `-raw` 拿到的值）。`postgres_jdbc_url` 输出里需把密码写进 Spring 属性，例如：
   `-Dspring.datasource.url=jdbc:postgresql://<address>:5432/chatdb`
   `-Dspring.datasource.username=chat`
   `-Dspring.datasource.password=<postgres_password>`

### 若关闭托管（自建 EC2 MQ/DB）

1. **postgres** EC2：自行安装 PostgreSQL、建库与用户，执行 `database/` 下 SQL。  
2. **rabbitmq** EC2：安装 RabbitMQ。  
3. 其余同上，互连用 **private_ip**。

具体命令与参数见项目根目录 **`README.md`** 里「EC2 Deployment Workflow」一节。

---

## 第九步：验证与压测

1. 浏览器或本机：

```powershell
curl http://你的ALB_DNS/health
```

应返回含 `UP` 的 JSON。

2. 本机跑客户端（先 `mvn package` 打好 JAR）：

```powershell
java -jar client\client_part2\target\chat-client-part2-0.0.1-SNAPSHOT.jar http://你的ALB_DNS 50000
```

（大压测再把 `50000` 改成 `500000` 等。）

---

## 第十步：不用了记得删资源（避免持续扣费）

在 `deployment\terraform` 目录：

```powershell
terraform destroy
```

输入 `yes`。会删掉本模板创建的大部分资源（VPC、EC2、ALB 等）。

---

## 常见问题

| 现象 | 处理 |
|------|------|
| `Error acquiring the state lock` | 上次中断；`terraform force-unlock <ID>` 或等锁过期 |
| SSH 连不上 | 检查 `allowed_ssh_cidr` 是否仍是当前公网 IP（家用宽带会变） |
| ALB 502 | Target 不健康：Server 是否已监听 8080、`/health` 是否 200 |
| 改 `server_count` 后 | 改 `terraform.tfvars` → `terraform apply` |

---

## 和本仓库文件的对应关系

| 文件 | 作用 |
|------|------|
| `STEP_BY_STEP.md` | 本教程（操作顺序） |
| `README.md` | 资源说明、变量表、扩展思路 |
| `terraform.tfvars` | 你的真实配置（勿提交） |
| `terraform.tfvars.example` | 示例模板 |

若某一步报错，把 **完整报错贴出来**（可打码密钥），再针对性排查。

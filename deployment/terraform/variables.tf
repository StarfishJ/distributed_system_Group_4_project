variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "us-east-1"
}

variable "project_name" {
  type        = string
  description = "Prefix for resource names"
  default     = "cs6650-chat"
}

variable "public_key_path" {
  type        = string
  default     = null
  description = "Path to your SSH public key (e.g. ~/.ssh/id_ed25519.pub). If set, Terraform creates the EC2 key pair in AWS — use this on a blank account."
}

variable "key_name" {
  type        = string
  default     = null
  description = "Name of an existing EC2 Key Pair in this region (only if public_key_path is not set)."
}

variable "allowed_ssh_cidr" {
  type        = string
  description = "CIDR allowed to SSH to data-plane EC2 (use your public IP/32). Avoid 0.0.0.0/0."
  default     = "0.0.0.0/0"
}

variable "permit_unsafe_wide_ssh" {
  type        = bool
  description = "Set true only for disposable labs if you must use allowed_ssh_cidr = 0.0.0.0/0"
  default     = false
}

variable "enable_eks" {
  type        = bool
  description = "Provision Amazon EKS for server-v2 + consumer-v3 (recommended). When false, app runs on EC2 + optional ALB (legacy)."
  default     = true
}

variable "eks_cluster_version" {
  type        = string
  description = "Kubernetes version for EKS control plane"
  default     = "1.31"
}

variable "eks_node_instance_types" {
  type        = list(string)
  description = "Managed node group instance types (t3.medium matches DesignDocument server node group)"
  default     = ["t3.medium"]
}

variable "eks_node_desired_size" {
  type        = number
  description = "Desired worker nodes (adjust for consumer + server pods)"
  default     = 2
}

variable "eks_node_min_size" {
  type    = number
  default = 1
}

variable "eks_node_max_size" {
  type    = number
  default = 6
}

variable "server_count" {
  type        = number
  description = "Number of server-v2 EC2 instances behind ALB (only when enable_eks = false)"
  default     = 2
}

variable "consumer_count" {
  type        = number
  description = "Number of consumer-v3 EC2 instances (only when enable_eks = false); share same RabbitMQ/Postgres/Redis"
  default     = 2

  validation {
    condition     = var.consumer_count >= 1 && var.consumer_count <= 10
    error_message = "consumer_count must be between 1 and 10."
  }
}

variable "asg_health_check_grace_period" {
  type        = number
  description = "Seconds before ELB health checks affect ASG (S3 download + JVM start)"
  default     = 420
}

variable "enable_asg_s3_app_tier" {
  type        = bool
  description = "true = Auto Scaling + S3 bootstrap + IAM role for EC2 (requires iam:CreateRole). false = fixed EC2 + deploy-ec2.ps1 over SSH (AWS Academy / Vocareum)."
  default     = false
}

variable "instance_type_rabbitmq" {
  type    = string
  default = "t3.small"
}

# RabbitMQ EC2 broker: gp3 avoids gp2 burst-balance / IOPS credit ceilings under durable queue load.
variable "rabbitmq_root_volume_size" {
  type        = number
  description = "Root EBS size (GiB) for the RabbitMQ EC2 instance"
  default     = 40
}

variable "rabbitmq_root_volume_iops" {
  type        = number
  description = "gp3 provisioned IOPS (3000 is a common baseline; raise if broker disk is hot)"
  default     = 3000
}

variable "rabbitmq_root_volume_throughput" {
  type        = number
  description = "gp3 throughput (MiB/s); 125 is default included tier"
  default     = 125
}

variable "instance_type_db" {
  type    = string
  default = "t3.small"
}

variable "instance_type_server" {
  type    = string
  default = "t3.medium"
}

variable "instance_type_consumer" {
  type    = string
  default = "t3.medium"
}

variable "enable_alb" {
  type        = bool
  description = "Create ALB targeting EC2 server-v2 (only when enable_eks = false). With EKS, use AWS Load Balancer Controller + Ingress instead."
  default     = true
}

variable "alb_idle_timeout" {
  type        = number
  description = "ALB idle timeout in seconds (1-4000). Default 60s drops quiet TCP connections; 3600 helps WebSocket/chat survive idle periods."
  default     = 3600

  validation {
    condition     = var.alb_idle_timeout >= 1 && var.alb_idle_timeout <= 4000
    error_message = "alb_idle_timeout must be between 1 and 4000 (AWS ALB limit)."
  }
}

variable "alb_health_check_path" {
  type        = string
  description = "Target group health check path (must match server-v2, e.g. GET /health)."
  default     = "/health"
}

# --- Managed AWS services (recommended vs self-hosted EC2) ---

variable "use_amazon_mq" {
  type        = bool
  description = "Use Amazon MQ (RabbitMQ) instead of EC2 rabbitmq instance"
  default     = false
}

variable "use_rds_postgres" {
  type        = bool
  description = "Use RDS PostgreSQL instead of EC2 postgres instance"
  default     = false
}

variable "use_rds_read_replica" {
  type        = bool
  description = "Create a same-region RDS PostgreSQL read replica for read-only queries"
  default     = false
}

variable "use_elasticache_redis" {
  type        = bool
  description = "Provision Amazon ElastiCache for Redis for server/consumer (Spring Data Redis). When true, deploy-ec2.ps1 skips Docker Redis on Postgres/Rabbit EC2."
  default     = true
}

variable "elasticache_node_type" {
  type        = string
  description = "ElastiCache Redis node type (lab-friendly default)"
  default     = "cache.t3.micro"
}

variable "elasticache_redis_engine_version" {
  type        = string
  description = "Redis engine version for ElastiCache (must be available in the region)"
  default     = "7.1"
}

variable "create_ec2_rabbitmq" {
  type        = bool
  default     = null
  description = <<-EOT
    Create a dedicated RabbitMQ EC2 instance (you still install/configure RabbitMQ on it, or use user_data later).
    If null: false when enable_eks=true (use in-cluster broker from k8s/ or set use_amazon_mq=true);
    true when enable_eks=false and use_amazon_mq=false.
    Set true explicitly for a hybrid (EKS apps + external EC2 broker).
  EOT
}

variable "create_ec2_postgres" {
  type        = bool
  default     = null
  description = <<-EOT
    Create a dedicated PostgreSQL EC2 instance.
    If null: false when enable_eks=true (use in-cluster Postgres from k8s/postgres.yaml or set use_rds_postgres=true);
    true when enable_eks=false and use_rds_postgres=false.
    Set true explicitly for a hybrid (EKS apps + external EC2 Postgres).
  EOT
}

variable "amazon_mq_instance_type" {
  type        = string
  description = "Amazon MQ broker size (mq.t3.micro is cheapest)"
  default     = "mq.t3.micro"
}

variable "amazon_mq_engine_version" {
  type    = string
  default = "3.13"
}

variable "amazon_mq_username" {
  type    = string
  default = "chatmq"
}

variable "rds_instance_class" {
  type    = string
  default = "db.t3.micro"
}

variable "rds_read_replica_instance_class" {
  type        = string
  description = "Optional instance class for RDS read replica; when null, uses rds_instance_class"
  default     = null
}

variable "rds_engine_version" {
  type    = string
  default = "16"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "rds_database_name" {
  type    = string
  default = "chatdb"
}

variable "rds_master_username" {
  type    = string
  default = "chat"
}

# --- Optional JMeter load-generator EC2 (same region as ALB) ---

variable "enable_jmeter_ec2" {
  type        = bool
  description = "When true (and enable_eks=false, enable_alb=true), provision a small EC2 with Java + Apache JMeter under /opt/apache-jmeter for load tests against the ALB."
  default     = false
}

variable "instance_type_jmeter" {
  type        = string
  description = "Instance type for the JMeter host (t3.large+ recommended for stress-30min)."
  default     = "t3.large"
}

variable "jmeter_version" {
  type        = string
  description = "Apache JMeter version to download from archive.apache.org"
  default     = "5.6.3"
}

variable "jmeter_root_volume_size" {
  type        = number
  description = "Root EBS size (GiB) for JMeter EC2 — JTL/HTML reports need space."
  default     = 30
}

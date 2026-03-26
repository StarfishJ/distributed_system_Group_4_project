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
  description = "CIDR allowed to SSH (e.g. your home IP/32)"
  default     = "0.0.0.0/0"
}

variable "server_count" {
  type        = number
  description = "Number of server-v2 instances behind ALB"
  default     = 2
}

variable "instance_type_rabbitmq" {
  type    = string
  default = "t3.small"
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
  description = "Create Application Load Balancer + target group"
  default     = true
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

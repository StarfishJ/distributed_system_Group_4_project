output "aws_region" {
  description = "Region used by this stack (for AWS CLI in deploy-ec2.ps1)"
  value       = var.aws_region
}

output "alb_dns_name" {
  description = "EC2 path: point load test client here (HTTP 80). Null when using EKS (use Ingress / LB Controller instead)."
  value       = local.create_app_ec2 && var.enable_alb ? aws_lb.main[0].dns_name : null
}

# --- RabbitMQ: Amazon MQ OR self-hosted EC2 ---

output "rabbitmq_mode" {
  description = "amazon_mq | ec2 | kubernetes (in-cluster Service from k8s/, no Terraform EC2 broker)"
  value       = var.use_amazon_mq ? "amazon_mq" : (length(aws_instance.rabbitmq) > 0 ? "ec2" : "kubernetes")
}

output "rabbitmq_host" {
  description = "spring.rabbitmq.host when using Amazon MQ or EC2 broker; null when rabbitmq_mode=kubernetes (use rabbitmq-service in-cluster)"
  value       = var.use_amazon_mq ? aws_mq_broker.rabbitmq[0].instances[0].ip_address : (length(aws_instance.rabbitmq) > 0 ? aws_instance.rabbitmq[0].private_ip : null)
}

output "rabbitmq_amqp_console_url" {
  description = "Amazon MQ management UI (open from browser via SSM port-forward or bastion; not public by default)"
  value       = var.use_amazon_mq ? aws_mq_broker.rabbitmq[0].instances[0].console_url : null
}

output "rabbitmq_username" {
  value = var.use_amazon_mq ? var.amazon_mq_username : "chatmq"
}

output "rabbitmq_password" {
  description = "Amazon MQ password (sensitive). Self-hosted EC2: null (configure RabbitMQ yourself)."
  value       = length(random_password.mq) > 0 ? random_password.mq[0].result : "chatmq"
  sensitive   = true
}

output "rabbitmq_public_ip" {
  value = var.use_amazon_mq ? null : (length(aws_instance.rabbitmq) > 0 ? aws_instance.rabbitmq[0].public_ip : null)
}

output "rabbitmq_private_ip" {
  value = var.use_amazon_mq ? null : (length(aws_instance.rabbitmq) > 0 ? aws_instance.rabbitmq[0].private_ip : null)
}

# --- PostgreSQL: RDS OR self-hosted EC2 ---

output "postgres_mode" {
  description = "rds | ec2 | kubernetes (in-cluster Service from k8s/, no Terraform EC2 DB)"
  value       = var.use_rds_postgres ? "rds" : (length(aws_instance.postgres) > 0 ? "ec2" : "kubernetes")
}

output "postgres_jdbc_url" {
  description = "JDBC URL when using RDS or EC2 Postgres; null when postgres_mode=kubernetes (use postgres-service in-cluster)"
  value       = var.use_rds_postgres ? "jdbc:postgresql://${aws_db_instance.postgres[0].address}:5432/${var.rds_database_name}" : (length(aws_instance.postgres) > 0 ? "jdbc:postgresql://${aws_instance.postgres[0].private_ip}:5432/chatdb" : null)
}

output "postgres_address" {
  description = "DB host for JDBC; null when postgres runs only inside Kubernetes"
  value       = var.use_rds_postgres ? aws_db_instance.postgres[0].address : (length(aws_instance.postgres) > 0 ? aws_instance.postgres[0].private_ip : null)
}

output "postgres_read_replica_address" {
  description = "RDS read replica host for read-only queries (null when disabled or non-RDS mode)"
  value       = var.use_rds_postgres && length(aws_db_instance.postgres_read_replica) > 0 ? aws_db_instance.postgres_read_replica[0].address : null
}

output "postgres_read_replica_jdbc_url" {
  description = "JDBC URL for RDS read replica (null when disabled or non-RDS mode)"
  value       = var.use_rds_postgres && length(aws_db_instance.postgres_read_replica) > 0 ? "jdbc:postgresql://${aws_db_instance.postgres_read_replica[0].address}:5432/${var.rds_database_name}" : null
}

output "postgres_username" {
  value = var.use_rds_postgres ? var.rds_master_username : "chat"
}

output "postgres_password" {
  description = "RDS master password when use_rds_postgres=true"
  value       = length(random_password.rds) > 0 ? random_password.rds[0].result : null
  sensitive   = true
}

output "postgres_public_ip" {
  value = var.use_rds_postgres ? null : (length(aws_instance.postgres) > 0 ? aws_instance.postgres[0].public_ip : null)
}

output "postgres_private_ip" {
  value = var.use_rds_postgres ? null : (length(aws_instance.postgres) > 0 ? aws_instance.postgres[0].private_ip : null)
}

# --- Redis: ElastiCache OR Docker on EC2 (see deploy-ec2.ps1) ---

output "redis_elasticache_primary_address" {
  description = "ElastiCache primary endpoint hostname; null when use_elasticache_redis=false or enable_eks=true"
  value       = length(aws_elasticache_replication_group.app_redis) > 0 ? aws_elasticache_replication_group.app_redis[0].primary_endpoint_address : null
}

output "redis_elasticache_port" {
  description = "Redis port (6379 unless changed in elasticache.tf)"
  value       = length(aws_elasticache_replication_group.app_redis) > 0 ? aws_elasticache_replication_group.app_redis[0].port : null
}

# --- App tier: ASG + S3 (see asg_app.tf) ---

output "app_artifacts_bucket" {
  description = "S3 bucket for server-v2.jar / server.env / consumer-v3.jar / consumer.env"
  value       = length(aws_s3_bucket.app_artifacts) > 0 ? aws_s3_bucket.app_artifacts[0].id : null
}

output "app_artifacts_s3_prefix" {
  description = "Key prefix under the bucket (no leading slash)"
  value       = local.create_asg_s3_app ? local.app_artifacts_s3_prefix : null
}

output "server_autoscaling_group_name" {
  description = "Server ASG name (instance refresh after S3 upload)"
  value       = length(aws_autoscaling_group.server) > 0 ? aws_autoscaling_group.server[0].name : null
}

output "consumer_autoscaling_group_name" {
  description = "Consumer ASG name"
  value       = length(aws_autoscaling_group.consumer) > 0 ? aws_autoscaling_group.consumer[0].name : null
}

output "server_public_ips" {
  description = "Public IPs of server-v2 instances (EC2 or ASG)"
  value = !local.create_app_ec2 ? [] : (
    local.create_asg_s3_app ? sort(data.aws_instances.server_asg[0].public_ips) : sort(aws_instance.server[*].public_ip)
  )
}

output "server_private_ips" {
  description = "Private IPs of server-v2 instances"
  value = !local.create_app_ec2 ? [] : (
    local.create_asg_s3_app ? sort(data.aws_instances.server_asg[0].private_ips) : sort(aws_instance.server[*].private_ip)
  )
}

output "consumer_public_ip" {
  description = "First consumer public IP (compat)"
  value = !local.create_app_ec2 ? null : (
    local.create_asg_s3_app ? (
      length(data.aws_instances.consumer_asg[0].public_ips) > 0 ? data.aws_instances.consumer_asg[0].public_ips[0] : null
      ) : (
      length(aws_instance.consumer) > 0 ? aws_instance.consumer[0].public_ip : null
    )
  )
}

output "consumer_private_ip" {
  description = "First consumer private IP (compat)"
  value = !local.create_app_ec2 ? null : (
    local.create_asg_s3_app ? (
      length(data.aws_instances.consumer_asg[0].private_ips) > 0 ? data.aws_instances.consumer_asg[0].private_ips[0] : null
      ) : (
      length(aws_instance.consumer) > 0 ? aws_instance.consumer[0].private_ip : null
    )
  )
}

output "consumer_public_ips" {
  description = "Public IPs of consumer-v3 instances"
  value = !local.create_app_ec2 ? [] : (
    local.create_asg_s3_app ? sort(data.aws_instances.consumer_asg[0].public_ips) : sort(aws_instance.consumer[*].public_ip)
  )
}

output "consumer_private_ips" {
  description = "Private IPs of consumer-v3 instances"
  value = !local.create_app_ec2 ? [] : (
    local.create_asg_s3_app ? sort(data.aws_instances.consumer_asg[0].private_ips) : sort(aws_instance.consumer[*].private_ip)
  )
}

output "ec2_key_pair_name" {
  description = "Key pair used by all instances (created by TF or pre-existing)"
  value       = local.ec2_key_name
}

output "ssh_hint" {
  value = "ssh -i ~/.ssh/<your-private-key> ec2-user@<public_ip_from_outputs>"
}

output "jmeter_public_ip" {
  description = "Public IP of optional JMeter load-generator EC2 (null when enable_jmeter_ec2=false)"
  value       = length(aws_instance.jmeter) > 0 ? aws_instance.jmeter[0].public_ip : null
}

output "jmeter_ssh_example" {
  description = "Example SSH to JMeter host (replace key path)"
  value       = length(aws_instance.jmeter) > 0 ? "ssh -i ~/.ssh/<your-key>.pem ec2-user@${aws_instance.jmeter[0].public_ip}" : null
}

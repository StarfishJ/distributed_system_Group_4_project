output "alb_dns_name" {
  description = "EC2 path: point load test client here (HTTP 80). Null when using EKS (use Ingress / LB Controller instead)."
  value       = local.create_app_ec2 && var.enable_alb ? aws_lb.main[0].dns_name : null
}

# --- EKS ---

output "eks_cluster_name" {
  description = "EKS cluster name (kubectl config use-context)"
  value       = var.enable_eks ? module.eks[0].cluster_name : null
}

output "eks_cluster_endpoint" {
  description = "Kubernetes API endpoint"
  value       = var.enable_eks ? module.eks[0].cluster_endpoint : null
}

output "eks_cluster_certificate_authority_data" {
  description = "For kubeconfig / CI"
  value       = var.enable_eks ? module.eks[0].cluster_certificate_authority_data : null
  sensitive   = true
}

output "eks_node_security_group_id" {
  description = "Attached to worker nodes; use in SG rules for ElastiCache / extra data stores"
  value       = var.enable_eks ? module.eks[0].node_security_group_id : null
}

output "configure_kubectl" {
  description = "AWS CLI v2: update kubeconfig after apply"
  value       = var.enable_eks ? "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.eks[0].cluster_name}" : null
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
  value = var.use_amazon_mq ? var.amazon_mq_username : "guest"
}

output "rabbitmq_password" {
  description = "Amazon MQ password (sensitive). Self-hosted EC2: null (configure RabbitMQ yourself)."
  value       = length(random_password.mq) > 0 ? random_password.mq[0].result : null
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

# --- App EC2 ---

output "server_public_ips" {
  description = "EC2 server-v2 only (empty when enable_eks = true)"
  value       = aws_instance.server[*].public_ip
}

output "server_private_ips" {
  value = aws_instance.server[*].private_ip
}

output "consumer_public_ip" {
  value = length(aws_instance.consumer) > 0 ? aws_instance.consumer[0].public_ip : null
}

output "consumer_private_ip" {
  value = length(aws_instance.consumer) > 0 ? aws_instance.consumer[0].private_ip : null
}

output "ec2_key_pair_name" {
  description = "Key pair used by all instances (created by TF or pre-existing)"
  value       = local.ec2_key_name
}

output "ssh_hint" {
  value = "ssh -i ~/.ssh/<your-private-key> ec2-user@<public_ip_from_outputs>"
}

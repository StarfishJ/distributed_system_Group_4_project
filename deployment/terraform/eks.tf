# -----------------------------------------------------------------------------
# Amazon EKS: server-v2 + consumer-v3 as Kubernetes workloads (DesignDocument).
# Data plane: either in-cluster (k8s/postgres.yaml, rabbitmq.yaml, redis.yaml) or
# external EC2 / Amazon MQ / RDS — see create_ec2_* variables and locals.tf.
# -----------------------------------------------------------------------------

# Pinned to v18.x: terraform-aws-modules/eks v19+ calls aws_iam_session_context,
# which needs iam:GetRole on the lab role — AWS Academy/Vocareum often denies that.
module "eks" {
  count   = var.enable_eks ? 1 : 0
  source  = "terraform-aws-modules/eks/aws"
  version = "18.31.0"

  cluster_name    = "${var.project_name}-eks"
  cluster_version = var.eks_cluster_version

  cluster_endpoint_public_access  = true
  cluster_endpoint_private_access = true

  vpc_id     = aws_vpc.main.id
  subnet_ids = aws_subnet.public[*].id

  eks_managed_node_groups = {
    app = {
      name = "${var.project_name}-ng"

      instance_types = var.eks_node_instance_types
      capacity_type  = "ON_DEMAND"

      min_size     = var.eks_node_min_size
      max_size     = var.eks_node_max_size
      desired_size = var.eks_node_desired_size

      labels = {
        workload = "chat-app"
      }
    }
  }

  tags = {
    Project = var.project_name
  }
}

# Allow EKS worker nodes to reach RabbitMQ / PostgreSQL on instances using aws_security_group.internal
resource "aws_security_group_rule" "internal_from_eks_nodes" {
  for_each = var.enable_eks ? toset(["5672", "15672", "5432"]) : toset([])

  type      = "ingress"
  from_port = tonumber(each.value)
  to_port   = tonumber(each.value)
  protocol  = "tcp"

  security_group_id        = aws_security_group.internal.id
  source_security_group_id = module.eks[0].node_security_group_id
  description              = "From EKS nodes to RabbitMQ / Postgres (EC2 data plane)"
}

# Amazon MQ uses a dedicated SG — allow AMQP + console from EKS nodes
resource "aws_security_group_rule" "mq_amqp_from_eks" {
  count = var.enable_eks && var.use_amazon_mq ? 1 : 0

  type      = "ingress"
  from_port = 5672
  to_port   = 5672
  protocol  = "tcp"

  security_group_id        = aws_security_group.mq[0].id
  source_security_group_id = module.eks[0].node_security_group_id
  description              = "AMQP from EKS nodes"
}

resource "aws_security_group_rule" "mq_console_from_eks" {
  count = var.enable_eks && var.use_amazon_mq ? 1 : 0

  type      = "ingress"
  from_port = 15672
  to_port   = 15672
  protocol  = "tcp"

  security_group_id        = aws_security_group.mq[0].id
  source_security_group_id = module.eks[0].node_security_group_id
  description              = "RabbitMQ management UI from EKS (e.g. port-forward)"
}

resource "aws_security_group_rule" "rds_from_eks" {
  count = var.enable_eks && var.use_rds_postgres ? 1 : 0

  type      = "ingress"
  from_port = 5432
  to_port   = 5432
  protocol  = "tcp"

  security_group_id        = aws_security_group.rds[0].id
  source_security_group_id = module.eks[0].node_security_group_id
  description              = "PostgreSQL from EKS nodes"
}

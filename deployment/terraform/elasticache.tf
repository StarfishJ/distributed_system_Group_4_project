# -----------------------------------------------------------------------------
# Amazon ElastiCache for Redis (optional; replaces Docker redis on EC2 data-plane hosts)
# -----------------------------------------------------------------------------

resource "aws_elasticache_subnet_group" "app_redis" {
  count = local.create_elasticache_redis ? 1 : 0

  name       = "${replace(var.project_name, "_", "-")}-redis-subnet"
  subnet_ids = aws_subnet.public[*].id

  tags = { Name = "${var.project_name}-redis-subnet" }
}

resource "aws_security_group" "elasticache_redis" {
  count = local.create_elasticache_redis ? 1 : 0

  name        = "${var.project_name}-elasticache-redis-sg"
  description = "ElastiCache Redis from app EC2 (internal SG)"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Redis"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.internal.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-elasticache-redis-sg" }
}

resource "aws_elasticache_replication_group" "app_redis" {
  count = local.create_elasticache_redis ? 1 : 0

  replication_group_id = substr(lower(replace("${var.project_name}-redis", "_", "-")), 0, 40)
  description          = "Chat cache (metrics, presence, consumer dedup)"

  engine         = "redis"
  engine_version = var.elasticache_redis_engine_version
  node_type      = var.elasticache_node_type
  port           = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.app_redis[0].name
  security_group_ids = [aws_security_group.elasticache_redis[0].id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = false

  tags = { Name = "${var.project_name}-redis" }
}

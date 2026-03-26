# -----------------------------------------------------------------------------
# Amazon MQ for RabbitMQ + RDS PostgreSQL (optional; saves EC2 install work)
# -----------------------------------------------------------------------------

resource "random_password" "mq" {
  count   = var.use_amazon_mq ? 1 : 0
  length  = 20
  special = false
}

resource "random_password" "rds" {
  count   = var.use_rds_postgres ? 1 : 0
  length  = 24
  special = false
}

resource "aws_security_group" "mq" {
  count       = var.use_amazon_mq ? 1 : 0
  name        = "${var.project_name}-mq-sg"
  description = "Amazon MQ RabbitMQ"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "AMQP"
    from_port       = 5672
    to_port         = 5672
    protocol        = "tcp"
    security_groups = [aws_security_group.internal.id]
  }

  ingress {
    description     = "Management UI"
    from_port       = 15672
    to_port         = 15672
    protocol        = "tcp"
    security_groups = [aws_security_group.internal.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-mq-sg" }
}

resource "aws_security_group" "rds" {
  count       = var.use_rds_postgres ? 1 : 0
  name        = "${var.project_name}-rds-sg"
  description = "RDS PostgreSQL"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.internal.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-rds-sg" }
}

resource "aws_db_subnet_group" "main" {
  count      = var.use_rds_postgres ? 1 : 0
  name       = "${var.project_name}-db-subnet"
  subnet_ids = aws_subnet.public[*].id

  tags = { Name = "${var.project_name}-db-subnet" }
}

resource "aws_db_instance" "postgres" {
  count = var.use_rds_postgres ? 1 : 0

  identifier                 = "${var.project_name}-pg"
  engine                     = "postgres"
  engine_version             = var.rds_engine_version
  instance_class             = var.rds_instance_class
  allocated_storage          = var.rds_allocated_storage
  db_name                    = var.rds_database_name
  username                   = var.rds_master_username
  password                   = random_password.rds[0].result
  db_subnet_group_name       = aws_db_subnet_group.main[0].name
  vpc_security_group_ids     = [aws_security_group.rds[0].id]
  publicly_accessible        = false
  skip_final_snapshot        = true
  delete_automated_backups   = true
  backup_retention_period    = 0

  tags = { Name = "${var.project_name}-rds" }
}

resource "aws_mq_broker" "rabbitmq" {
  count = var.use_amazon_mq ? 1 : 0

  broker_name                = lower(replace("${var.project_name}-mq", "_", "-"))
  engine_type                = "RabbitMQ"
  engine_version             = var.amazon_mq_engine_version
  host_instance_type         = var.amazon_mq_instance_type
  deployment_mode            = "SINGLE_INSTANCE"
  subnet_ids                 = [aws_subnet.public[0].id]
  security_groups            = [aws_security_group.mq[0].id]
  publicly_accessible        = false
  auto_minor_version_upgrade = true

  user {
    username = var.amazon_mq_username
    password = random_password.mq[0].result
  }

  logs {
    general = true
  }

  tags = { Name = "${var.project_name}-mq" }
}

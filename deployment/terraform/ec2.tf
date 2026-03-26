resource "aws_instance" "rabbitmq" {
  count                  = var.use_amazon_mq ? 0 : 1
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type_rabbitmq
  key_name               = local.ec2_key_name
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.internal.id]

  user_data = local.user_data_base

  tags = {
    Name = "${var.project_name}-rabbitmq"
    Role = "rabbitmq"
  }
}

resource "aws_instance" "postgres" {
  count                  = var.use_rds_postgres ? 0 : 1
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type_db
  key_name               = local.ec2_key_name
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.internal.id]

  user_data = local.user_data_base

  tags = {
    Name = "${var.project_name}-postgres"
    Role = "postgres"
  }
}

resource "aws_instance" "server" {
  count                  = var.server_count
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type_server
  key_name               = local.ec2_key_name
  subnet_id              = aws_subnet.public[count.index % 2].id
  vpc_security_group_ids = [aws_security_group.internal.id]

  user_data = local.user_data_base

  tags = {
    Name = "${var.project_name}-server-${count.index + 1}"
    Role = "server-v2"
  }
}

resource "aws_instance" "consumer" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type_consumer
  key_name               = local.ec2_key_name
  subnet_id              = aws_subnet.public[1].id
  vpc_security_group_ids = [aws_security_group.internal.id]

  user_data = local.user_data_base

  tags = {
    Name = "${var.project_name}-consumer"
    Role = "consumer-v3"
  }
}

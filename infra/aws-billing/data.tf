resource "aws_kms_key" "data" {
  description             = "${var.name} database and Kafka encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  lifecycle { prevent_destroy = true }
}
resource "aws_kms_alias" "data" {
  name          = "alias/${var.name}-data"
  target_key_id = aws_kms_key.data.key_id
}
resource "aws_db_subnet_group" "billing" {
  name       = "${var.name}-database"
  subnet_ids = aws_subnet.data[*].id
}
resource "aws_db_parameter_group" "billing" {
  name_prefix = "${var.name}-pg17-"
  family      = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  lifecycle { create_before_destroy = true }
}
resource "random_id" "snapshot" { byte_length = 4 }
resource "aws_cloudwatch_log_group" "database" {
  for_each          = toset(["postgresql", "upgrade"])
  name              = "/aws/rds/instance/${var.name}/${each.key}"
  retention_in_days = 30
}
resource "aws_db_instance" "billing" {
  identifier                          = var.name
  engine                              = "postgres"
  engine_version                      = var.database_engine_version
  engine_lifecycle_support            = "open-source-rds-extended-support-disabled"
  instance_class                      = var.database_instance_class
  db_name                             = "invoice_payments"
  username                            = "billing_administrator"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = aws_kms_key.data.arn
  allocated_storage                   = 50
  max_allocated_storage               = 300
  storage_type                        = "gp3"
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.data.arn
  multi_az                            = true
  publicly_accessible                 = false
  db_subnet_group_name                = aws_db_subnet_group.billing.name
  vpc_security_group_ids              = [aws_security_group.database.id]
  parameter_group_name                = aws_db_parameter_group.billing.name
  backup_retention_period             = 14
  backup_window                       = "06:00-07:00"
  maintenance_window                  = "sun:08:00-sun:09:00"
  copy_tags_to_snapshot               = true
  delete_automated_backups            = false
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.name}-final-${random_id.snapshot.hex}"
  auto_minor_version_upgrade          = true
  allow_major_version_upgrade         = false
  apply_immediately                   = false
  enabled_cloudwatch_logs_exports     = ["postgresql", "upgrade"]
  iam_database_authentication_enabled = false
  lifecycle { prevent_destroy = true }
  depends_on = [aws_cloudwatch_log_group.database]
}
resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/${var.name}/msk"
  retention_in_days = 30
}
resource "aws_msk_configuration" "billing" {
  name              = "${var.name}-durable"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    offsets.topic.replication.factor=3
    transaction.state.log.replication.factor=3
    transaction.state.log.min.isr=2
    unclean.leader.election.enable=false
    log.retention.hours=168
    num.partitions=6
  PROPERTIES
}
resource "aws_msk_cluster" "billing" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3
  enhanced_monitoring    = "PER_BROKER"
  broker_node_group_info {
    instance_type   = var.kafka_broker_instance_type
    client_subnets  = aws_subnet.data[*].id
    security_groups = [aws_security_group.kafka.id]
    storage_info {
      ebs_storage_info { volume_size = 100 }
    }
    connectivity_info {
      public_access { type = "DISABLED" }
    }
  }
  client_authentication {
    unauthenticated = false
    sasl {
      iam   = true
      scram = false
    }
  }
  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.data.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
  configuration_info {
    arn      = aws_msk_configuration.billing.arn
    revision = aws_msk_configuration.billing.latest_revision
  }
  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }
  lifecycle { prevent_destroy = true }
}

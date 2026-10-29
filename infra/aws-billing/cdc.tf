variable "cdc_enabled" {
  description = "Create the managed Debezium relay after V23, replication login, publication, topics and verified plugin are provisioned. Defaults off for controlled handover."
  type        = bool
  default     = false
}
variable "cdc_plugin_arn" {
  description = "Registered immutable custom plugin containing the pinned PostgreSQL connector, outbox transform and AWS config providers. Build with scripts/cdc/Build-Plugin.ps1."
  type        = string
  default     = ""
  validation {
    condition     = !var.cdc_enabled || can(regex("^arn:aws:kafkaconnect:", var.cdc_plugin_arn))
    error_message = "CDC requires a verified registered custom plugin ARN."
  }
}
variable "cdc_plugin_revision" {
  type    = number
  default = 1
}
variable "cdc_database_secret_arn" {
  description = "Separate replication login secret with username/password; plaintext values never enter Terraform state."
  type        = string
  default     = ""
  validation {
    condition     = !var.cdc_enabled || can(regex("^arn:aws:secretsmanager:", var.cdc_database_secret_arn))
    error_message = "CDC requires the replication login's Secrets Manager ARN."
  }
}
variable "cdc_ca_bucket" {
  description = "Private same-account S3 bucket containing the verified RDS global CA bundle, SSE-S3 encrypted."
  type        = string
  default     = ""
  validation {
    condition     = !var.cdc_enabled || length(var.cdc_ca_bucket) > 2
    error_message = "CDC TLS verification requires the CA bundle bucket."
  }
}
variable "cdc_ca_key" {
  type    = string
  default = "rds/global-bundle.pem"
}
resource "aws_security_group" "cdc" {
  count       = var.cdc_enabled ? 1 : 0
  name_prefix = "${var.name}-cdc-"
  vpc_id      = aws_vpc.billing.id
  description = "Managed source connector with no inbound access."
}
resource "aws_vpc_security_group_egress_rule" "cdc_https" {
  count             = var.cdc_enabled ? 1 : 0
  security_group_id = aws_security_group.cdc[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "cdc_database" {
  count                        = var.cdc_enabled ? 1 : 0
  security_group_id            = aws_security_group.cdc[0].id
  referenced_security_group_id = aws_security_group.database.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_ingress_rule" "database_cdc" {
  count                        = var.cdc_enabled ? 1 : 0
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.cdc[0].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_egress_rule" "cdc_kafka" {
  count                        = var.cdc_enabled ? 1 : 0
  security_group_id            = aws_security_group.cdc[0].id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_vpc_security_group_ingress_rule" "kafka_cdc" {
  count                        = var.cdc_enabled ? 1 : 0
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.cdc[0].id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_cloudwatch_log_group" "cdc" {
  count             = var.cdc_enabled ? 1 : 0
  name              = "/${var.name}/debezium"
  retention_in_days = 30
}
resource "aws_iam_role" "cdc" {
  count = var.cdc_enabled ? 1 : 0
  name  = "${var.name}-cdc"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect    = "Allow", Action = "sts:AssumeRole", Principal = { Service = "kafkaconnect.amazonaws.com" },
    Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }, ArnLike = { "aws:SourceArn" = "arn:${data.aws_partition.current.partition}:kafkaconnect:${var.region}:${data.aws_caller_identity.current.account_id}:connector/${var.name}-outbox/*" } }
  }] })
}
resource "aws_iam_role_policy" "cdc" {
  count = var.cdc_enabled ? 1 : 0
  role  = aws_iam_role.cdc[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    { Effect = "Allow", Action = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:WriteDataIdempotently"], Resource = aws_msk_cluster.billing.arn },
    { Effect = "Allow", Action = ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"], Resource = [for topic in ["billing.events.v1", "__debezium-heartbeat.billing_cdc"] : "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":topic/")}/${topic}"] },
    { Effect = "Allow", Action = ["kafka-cluster:CreateTopic", "kafka-cluster:DescribeTopic", "kafka-cluster:WriteData", "kafka-cluster:ReadData", "kafka-cluster:AlterTopic"], Resource = "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":topic/")}/__amazon_msk_connect_*" },
    { Effect = "Allow", Action = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"], Resource = "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":group/")}/__amazon_msk_connect_*" },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.cdc_database_secret_arn },
    { Effect = "Allow", Action = ["s3:GetObject"], Resource = "arn:${data.aws_partition.current.partition}:s3:::${var.cdc_ca_bucket}/${var.cdc_ca_key}", Condition = { StringEquals = { "s3:ResourceAccount" = data.aws_caller_identity.current.account_id } } },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.cdc[0].arn}:*" }
  ], length(var.secret_kms_key_arns) > 0 ? [{ Effect = "Allow", Action = ["kms:Decrypt"], Resource = sort(tolist(var.secret_kms_key_arns)), Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.region}.amazonaws.com" } } }] : []) })
}
resource "aws_mskconnect_worker_configuration" "cdc" {
  count                   = var.cdc_enabled ? 1 : 0
  name                    = "${var.name}-cdc"
  properties_file_content = <<-PROPERTIES
    key.converter=org.apache.kafka.connect.storage.StringConverter
    value.converter=org.apache.kafka.connect.storage.StringConverter
    header.converter=org.apache.kafka.connect.storage.SimpleHeaderConverter
    config.providers=secretsmanager,s3import
    config.providers.secretsmanager.class=com.amazonaws.kafka.config.providers.SecretsManagerConfigProvider
    config.providers.secretsmanager.param.region=${var.region}
    config.providers.s3import.class=com.amazonaws.kafka.config.providers.S3ImportConfigProvider
    offset.flush.interval.ms=10000
  PROPERTIES
}
resource "aws_mskconnect_connector" "outbox" {
  count                      = var.cdc_enabled ? 1 : 0
  name                       = "${var.name}-outbox"
  kafkaconnect_version       = "3.7.x"
  service_execution_role_arn = aws_iam_role.cdc[0].arn
  capacity {
    provisioned_capacity {
      mcu_count    = 1
      worker_count = 2
    }
  }
  connector_configuration = merge(jsondecode(file("${path.module}/../cdc/connector.json")).config, {
    "database.hostname"    = aws_db_instance.billing.address,
    "database.port"        = "5432",
    "database.dbname"      = "invoice_payments",
    "database.user"        = "$${secretsmanager:${var.cdc_database_secret_arn}:username}",
    "database.password"    = "$${secretsmanager:${var.cdc_database_secret_arn}:password}",
    "database.sslmode"     = "verify-full",
    "database.sslrootcert" = "$${s3import:${var.region}:${var.cdc_ca_bucket}/${var.cdc_ca_key}}",
    "config.action.reload" = "none"
  })
  kafka_cluster {
    apache_kafka_cluster {
      bootstrap_servers = aws_msk_cluster.billing.bootstrap_brokers_sasl_iam
      vpc {
        security_groups = [aws_security_group.cdc[0].id]
        subnets         = aws_subnet.application[*].id
      }
    }
  }
  kafka_cluster_client_authentication { authentication_type = "IAM" }
  kafka_cluster_encryption_in_transit { encryption_type = "TLS" }
  plugin {
    custom_plugin {
      arn      = var.cdc_plugin_arn
      revision = var.cdc_plugin_revision
    }
  }
  worker_configuration {
    arn      = aws_mskconnect_worker_configuration.cdc[0].arn
    revision = aws_mskconnect_worker_configuration.cdc[0].latest_revision
  }
  log_delivery {
    worker_log_delivery {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.cdc[0].name
      }
    }
  }
  depends_on = [aws_iam_role_policy.cdc, aws_vpc_security_group_ingress_rule.database_cdc, aws_vpc_security_group_ingress_rule.kafka_cdc]
}


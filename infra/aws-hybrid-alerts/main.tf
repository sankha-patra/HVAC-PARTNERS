# Hybrid alerting: the Java app, PostgreSQL, Kafka and Debezium run on the laptop (Docker); this stack runs in AWS.
#
#   laptop containers --Fluent Bit--> CloudWatch Logs /<name>/{billing,postgres,docker-events,shipper}
#   recovery Lambda   -------------> /aws/lambda/<recovery function>
#
# Three paths reach the one encrypted SNS topic, which emails var.alert_email_addresses:
#   1. One-off failures (an ALERT event line, a blocked delete, a PostgreSQL failure, a container that died) go through
#      a subscription filter to the forwarder Lambda, which emails the exact line: what broke, why, and the ids.
#   2. Ongoing conditions (state ALERT codes, heartbeats, Lambda/Step Functions metrics) are CloudWatch alarms, which
#      email once when the condition starts and once when it has been clear for five minutes.
#   3. A failed, timed-out or aborted recovery execution is an EventBridge event with Step Functions' own error/cause.

terraform {
  required_version = ">= 1.13.5, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.27.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "2.7.1"
    }
  }
  # Lab stack: local state. It holds no secrets (access keys are created outside Terraform).
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Application = var.name
      ManagedBy   = "Terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

variable "name" {
  type    = string
  default = "payments-hybrid"
}
variable "region" {
  type    = string
  default = "ap-southeast-2"
}
variable "alert_email_addresses" {
  description = "Mailboxes subscribed to the alerts topic. Each must confirm the subscription email from AWS."
  type        = list(string)
  validation {
    condition     = length(var.alert_email_addresses) > 0 && alltrue([for address in var.alert_email_addresses : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", address))])
    error_message = "Give at least one valid email address."
  }
}
variable "recovery_function_name" {
  description = "Recovery Lambda deployed by the recovery stack; its log group is watched for ALERT lines."
  type        = string
  default     = "payments-rzp-recovery-recovery"
}
variable "recovery_state_machine_name" {
  description = "Recovery state machine; its failed, timed-out and aborted executions are emailed."
  type        = string
  default     = "payments-rzp-recovery-recovery"
}
variable "watched_containers" {
  description = "Laptop containers whose unexpected stop is emailed, by role."
  type        = map(string)
  default = {
    billing  = "payments-rzp-failures-billing-1"
    postgres = "payments-rzp-failures-postgres-1"
    kafka    = "payments-rzp-failures-kafka-1"
    connect  = "payments-rzp-failures-connect-1"
  }
}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  namespace = "InvoicePayments/Hybrid"
  log_groups = {
    billing       = "/${var.name}/billing"
    postgres      = "/${var.name}/postgres"
    docker_events = "/${var.name}/docker-events"
    shipper       = "/${var.name}/shipper"
  }
  recovery_log_group = "/aws/lambda/${var.recovery_function_name}"
  logs_hint          = "The exact line, with ids, is in CloudWatch Logs Insights on ${local.log_groups.billing} and ${local.recovery_log_group}: filter @message like 'ALERT code="
}

output "alerts_topic_arn" { value = aws_sns_topic.alerts.arn }
output "log_groups" { value = local.log_groups }
output "telemetry_user" { value = aws_iam_user.telemetry.name }
output "dispatcher_user" { value = aws_iam_user.dispatcher.name }

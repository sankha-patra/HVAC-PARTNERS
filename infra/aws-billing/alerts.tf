# Alert delivery: every CloudWatch alarm and EventBridge failure event below publishes to one encrypted SNS topic,
# which emails the addresses in var.alert_email_addresses (use team/group mailboxes, not personal ones).
#
# Three sources feed it:
#   1. Application ALERT log lines. The billing service writes `ALERT code=<CODE> severity=... reason="..."`
#      (OperationalAlert.java) after the related database commit. A metric filter per code counts them and an alarm
#      per code emails the team. The alarm says what broke and why; the log line carries the payment/refund id.
#   2. AWS service events with their own reason text: an ECS task that died, an RDS failover or outage,
#      a Step Functions recovery execution that failed (EventBridge -> SNS, reason included in the email).
#   3. The infrastructure alarms in observability.tf and cdc-monitoring.tf (load balancer, RDS, MSK, MSK Connect).

variable "alert_email_addresses" {
  description = "Mailboxes subscribed to the alerts topic. Each address must confirm the subscription email from AWS before it receives alerts."
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for address in var.alert_email_addresses : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", address))])
    error_message = "Every alert_email_addresses entry must be an email address."
  }
}
variable "recovery_state_machine_arns" {
  description = "Step Functions state machines that run payment/refund recovery (deployed separately); their failed executions are emailed."
  type        = list(string)
  default     = []
}
variable "recovery_function_names" {
  description = "Recovery Lambda functions (deployed separately); any invocation error is emailed."
  type        = list(string)
  default     = []
}


locals {
  alarm_actions   = concat([aws_sns_topic.alerts.arn], var.alarm_action_arns)
  alert_namespace = "InvoicePayments/Alerts"
  # Recovery runs in the Lambda too (it opens finance cases and sees database outages), so its log groups are watched.
  alert_log_groups = merge({ billing = aws_cloudwatch_log_group.application["billing"].name, worker = aws_cloudwatch_log_group.worker.name },
  { for name in var.recovery_function_names : "lambda-${name}" => "/aws/lambda/${name}" })
  logs_hint = "Find the exact line, with the payment/refund id: CloudWatch Logs Insights on ${aws_cloudwatch_log_group.application["billing"].name} and ${aws_cloudwatch_log_group.worker.name}, filter @message like 'ALERT code="
  # One entry per ALERT code the application writes. `state` codes describe an ongoing condition and also send OK when it clears.
  app_alerts = {
    PAYMENT_OUTCOME_UNKNOWN = {
      state       = false
      description = "A payment request got no answer from the payment provider (timeout or outage). The payment is kept as UNKNOWN with its saved request and stock reserved; the buyer is told not to pay again. Recovery (Step Functions + Lambda) will ask the provider. Act only if it later reaches finance review."
    }
    REFUND_OUTCOME_UNKNOWN = {
      state       = false
      description = "A refund request got no answer from the payment provider. The refund is kept as UNKNOWN with its saved request; recovery will ask the provider. Act only if it later reaches finance review."
    }
    DATABASE_UNAVAILABLE = {
      state       = false
      description = "The billing service could not reach RDS PostgreSQL (source says http, worker, scheduler, recovery or watch). Requests fail safely; a payment already sent stays saved and is recovered once the database is back (a PAYMENT_OUTCOME_UNKNOWN alert names it). Check the RDS events email (failover/outage) and the RDS console. Raised at most once a minute per source."
    }
    FINANCE_CASE_OPENED = {
      state       = false
      description = "A payment or refund needs a human decision (for example PAYMENT_UNRESOLVED, REFUND_UNRESOLVED, RECOVERY_EVIDENCE_MISMATCH, ORDER_MONEY_RETURNED_REVIEW). The case reason is in the log line. Carrier finance decides in the Finance screen; engineering checks for a defect."
    }
    RECOVERY_NEEDS_REVIEW = {
      state       = false
      description = "A Step Functions recovery workflow ended without a money result after its last attempt (cause carries the execution's error), or the campaign limit or deadline was reached. The payment/refund stays reserved and owned by finance review. Check the execution in Step Functions and the matching finance case."
    }
    OUTBOX_NOT_DRAINING = {
      state       = true
      description = "Committed payment events have waited over 60 s to reach Kafka (MSK or the Debezium connector is down or stalled). Money records are safe; order updates and buyer emails are delayed and will be delivered once when Kafka returns. Check MSK and MSK Connect."
    }
    RECOVERY_WORK_NOT_CLAIMED = {
      state       = true
      description = "An uncertain payment or refund has been due for recovery for over 3 minutes and no workflow, claim or finance case owns it (dispatcher stopped, campaign limit or deadline, or AWS permissions). Check the RECOVERY_DISPATCH_FAILING / RECOVERY_CAMPAIGN_EXPIRING alerts and the worker logs."
    }
    CDC_SLOT_INACTIVE = {
      state       = true
      description = "Debezium is not reading the outbox replication slot. Events and WAL are accumulating in RDS. Restart or fix the MSK Connect connector before WAL storage grows."
    }
    MONEY_OPERATION_STUCK = {
      state       = true
      description = "A payment or refund has been uncertain for over 15 minutes and no finance case owns it yet. Recovery may still be trying; if it continues, check the recovery executions and the worker log. The count is in the log line."
    }
    RECOVERY_DISPATCH_FAILING = {
      state       = false
      description = "The recovery dispatcher cannot call Step Functions (errorCode in the line: AccessDenied, ExpiredToken, network). Uncertain payments wait unrecovered until it works again. Check the worker task role."
    }
    PROVIDER_PAUSED = {
      state       = false
      description = "New payments are refused before anything is saved or sent: the provider was paused after repeated failures, or every call slot is busy. Nothing was charged; it resumes by itself. Check the provider status page."
    }
    DEAD_LETTER_OPEN = {
      state       = true
      description = "A Kafka event could not be applied and was parked as a dead letter (for example the order update or buyer email of a captured payment). Money is recorded; that order or email waits. Fix the cause and replay it from the Operations screen."
    }
    NOTIFICATION_DELIVERY_FAILING = {
      state       = true
      description = "Buyer or finance emails are refused by the mail server or have no configured recipient (counts in the line). Emails are kept and retried; check SMTP settings and billing.notifications.finance-recipient."
    }
    DEBEZIUM_STALLED = {
      state       = true
      description = "Debezium has not updated its heartbeat row for over 60 s, so outbox events are not being read even without payment traffic. Check the MSK Connect connector."
    }
    KAFKA_UNREACHABLE = {
      state       = true
      description = "The billing service cannot reach the MSK brokers. Committed payments stay in the outbox; order updates and emails resume when Kafka is back."
    }
    CDC_WAL_RETAINED = {
      state       = true
      description = "The outbox replication slot holds over 512 MB of WAL in RDS. Debezium is behind or stopped; fix it before storage fills."
    }
    PROVIDER_CREDENTIALS_REJECTED = {
      state       = true
      description = "The payment provider rejected the app's API credentials; new payments cannot start. Check the provider secret in Secrets Manager."
    }
    WEBHOOK_REJECTED = {
      state       = false
      description = "The billing service refused a payment provider webhook (stage in the line: SIGNATURE means the webhook secret differs from the provider's; ACCOUNT means another merchant account). Payments are still confirmed by recovery, more slowly. Fix the webhook secret in Secrets Manager or the provider dashboard."
    }
    RECOVERY_CAMPAIGN_EXPIRING = {
      state       = true
      description = "The live recovery campaign ends within a day or has used 80% of its starts. After that uncertain payments are not recovered automatically; roll a new campaign."
    }
  }
  app_alert_filters = { for pair in setproduct(keys(local.app_alerts), keys(local.alert_log_groups)) : "${pair[0]}-${pair[1]}" => { code = pair[0], group = local.alert_log_groups[pair[1]] } }
}

resource "aws_kms_key" "alerts" {
  description             = "${var.name} alert topic encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.alerts_key.json
}
data "aws_iam_policy_document" "alerts_key" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
  statement {
    sid       = "AlarmAndEventPublishers"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "events.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}
resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-alerts"
  kms_master_key_id = aws_kms_key.alerts.arn
}
resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts_topic.json
}
data "aws_iam_policy_document" "alerts_topic" {
  statement {
    sid       = "AlarmAndEventPublishers"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "events.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}
resource "aws_sns_topic_subscription" "alert_email" {
  for_each  = toset(var.alert_email_addresses)
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

# 1. Application ALERT lines -> metric per code -> alarm per code -> email.
resource "aws_cloudwatch_log_metric_filter" "app_alert" {
  for_each       = local.app_alert_filters
  name           = "${var.name}-alert-${lower(replace(each.key, "_", "-"))}"
  log_group_name = each.value.group
  pattern        = "\"operational-alerts\" \"ALERT code=${each.value.code} \""
  metric_transformation {
    name          = each.value.code
    namespace     = local.alert_namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}
resource "aws_cloudwatch_metric_alarm" "app_alert" {
  for_each          = local.app_alerts
  alarm_name        = "${var.name}-${lower(replace(each.key, "_", "-"))}"
  alarm_description = substr("${each.value.description} ${local.logs_hint}${each.key}'.", 0, 1024)
  namespace         = local.alert_namespace
  metric_name       = each.key
  statistic         = "Sum"
  # Condition codes are written every minute while they hold: ALARM on the first line, OK after five clear minutes.
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = each.value.state ? local.alarm_actions : []
  depends_on          = [aws_cloudwatch_log_metric_filter.app_alert]
}

# Someone tried to delete or rewrite a protected money/order row directly in the database.
resource "aws_cloudwatch_log_metric_filter" "protected_row_change" {
  name           = "${var.name}-protected-row-change-blocked"
  log_group_name = aws_cloudwatch_log_group.database["postgresql"].name
  pattern        = "?\"cannot be deleted\" ?\"cannot be truncated\" ?\"is immutable\" ?\"update or delete on table\""
  metric_transformation {
    name          = "PROTECTED_ROW_CHANGE_BLOCKED"
    namespace     = local.alert_namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}
resource "aws_cloudwatch_metric_alarm" "protected_row_change" {
  alarm_name          = "${var.name}-protected-row-change-blocked"
  alarm_description   = "RDS refused a delete, truncate or rewrite of a protected payment/order row (saved payment request, money journal, refund request, or a row other records depend on). The data is intact. Someone ran a manual or faulty statement: find who in the RDS PostgreSQL log ${aws_cloudwatch_log_group.database["postgresql"].name} and stop it."
  namespace           = local.alert_namespace
  metric_name         = "PROTECTED_ROW_CHANGE_BLOCKED"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  depends_on          = [aws_cloudwatch_log_metric_filter.protected_row_change]
}

# 2. AWS service events, with AWS's own reason text in the email.
resource "aws_cloudwatch_event_rule" "ecs_task_crashed" {
  name        = "${var.name}-ecs-task-crashed"
  description = "An application or worker task stopped because its container exited or failed to start"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.billing.arn]
      lastStatus = ["STOPPED"]
      stopCode   = ["EssentialContainerExited", "TaskFailedToStart"]
    }
  })
}
resource "aws_cloudwatch_event_target" "ecs_task_crashed" {
  rule = aws_cloudwatch_event_rule.ecs_task_crashed.name
  arn  = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths    = { group = "$.detail.group", reason = "$.detail.stoppedReason", task = "$.detail.taskArn", time = "$.time" }
    input_template = "\"[${var.name}] ECS task in <group> stopped unexpectedly at <time>. Reason from ECS: <reason>. ECS starts a replacement task; recovery resumes any payment that was in flight. Task: <task>\""
  }
}
resource "aws_cloudwatch_event_rule" "rds_events" {
  name        = "${var.name}-rds-availability"
  description = "RDS PostgreSQL outage, failover, failure, recovery or low storage"
  event_pattern = jsonencode({
    source        = ["aws.rds"]
    "detail-type" = ["RDS DB Instance Event"]
    detail = {
      SourceIdentifier = [aws_db_instance.billing.identifier]
      EventCategories  = ["availability", "failover", "failure", "recovery", "low storage"]
    }
  })
}
resource "aws_cloudwatch_event_target" "rds_events" {
  rule = aws_cloudwatch_event_rule.rds_events.name
  arn  = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths    = { db = "$.detail.SourceIdentifier", message = "$.detail.Message", time = "$.time" }
    input_template = "\"[${var.name}] RDS <db> at <time>: <message>. Requests fail safely while the database is unavailable; Step Functions recovery records payments taken meanwhile once it is back.\""
  }
}
resource "aws_cloudwatch_event_rule" "recovery_failed" {
  count       = length(var.recovery_state_machine_arns) > 0 ? 1 : 0
  name        = "${var.name}-recovery-execution-failed"
  description = "A payment/refund recovery workflow execution failed, timed out or was aborted"
  event_pattern = jsonencode({
    source        = ["aws.states"]
    "detail-type" = ["Step Functions Execution Status Change"]
    detail = {
      status          = ["FAILED", "TIMED_OUT", "ABORTED"]
      stateMachineArn = [for arn in var.recovery_state_machine_arns : { prefix = arn }]
    }
  })
}
resource "aws_cloudwatch_event_target" "recovery_failed" {
  count = length(var.recovery_state_machine_arns) > 0 ? 1 : 0
  rule  = aws_cloudwatch_event_rule.recovery_failed[0].name
  arn   = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths    = { execution = "$.detail.name", status = "$.detail.status", error = "$.detail.error", cause = "$.detail.cause", time = "$.time" }
    input_template = "\"[${var.name}] Recovery workflow <execution> ended <status> at <time>. Error: <error>. Cause: <cause>. The payment/refund stays reserved and goes to finance review.\""
  }
}
resource "aws_cloudwatch_metric_alarm" "recovery_lambda_errors" {
  for_each            = toset(var.recovery_function_names)
  alarm_name          = "${var.name}-recovery-lambda-errors-${each.value}"
  alarm_description   = "The recovery Lambda ${each.value} returned errors. Step Functions retries connection errors; repeated errors end in finance review. Check /aws/lambda/${each.value} for the exception."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = each.value }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

output "alerts_topic_arn" { value = aws_sns_topic.alerts.arn }

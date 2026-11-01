locals {
  # Ongoing conditions. OperationalWatch writes the line on every check (every 60 s) while the condition holds, so the
  # alarm emails once when it starts and once when it has been clear for 5 minutes. Event codes go to forwarder.tf.
  state_alerts = {
    DATABASE_UNAVAILABLE          = "The billing app cannot reach the laptop PostgreSQL (the watch checks every minute, with or without traffic; source and error in the line). Payments fail safely; one already sent stays saved and is recovered after it returns (a PAYMENT_OUTCOME_UNKNOWN email names it). Check `docker ps` for the postgres container."
    OUTBOX_NOT_DRAINING           = "Committed payment events have waited over 60 s to reach Kafka: the laptop Kafka broker or the Debezium connector is down or stalled. Money records are safe; order updates and buyer emails wait and are delivered once when it returns. Check `docker ps` for the kafka and connect containers and `curl 127.0.0.1:28083/connectors/customer-email-outbox/status`."
    RECOVERY_WORK_NOT_CLAIMED     = "An uncertain payment or refund has been due for recovery for over 3 minutes and no workflow, claim or finance case owns it (oldestSeconds in the line). Likely causes: the dispatcher cannot reach AWS (RECOVERY_DISPATCH_FAILING email), the campaign hit its limit or deadline (RECOVERY_CAMPAIGN_EXPIRING), or the app is not running. Check the billing log."
    CDC_SLOT_INACTIVE             = "Debezium is not reading the outbox replication slot, so events and WAL pile up in the laptop PostgreSQL. Restart the connector task: `curl -X POST -H 'Content-Type: application/json' '127.0.0.1:28083/connectors/customer-email-outbox/restart?includeTasks=true'`."
    MONEY_OPERATION_STUCK         = "A payment or refund has been in an uncertain state for over 15 minutes and is not owned by a finance case yet. Recovery may still be trying; if it continues, check the recovery execution and the billing log for that id."
    DEAD_LETTER_OPEN              = "A Kafka event could not be applied and was parked as a dead letter (for example the order update or buyer email for a captured payment). Money is recorded, but that order or email is waiting. Fix the cause and replay it from the Operations screen."
    NOTIFICATION_DELIVERY_FAILING = "Buyer or finance emails are not being delivered (mail server refused or unreachable). The line names the failure class. Emails are kept and retried; check SMTP settings and the notification table."
    DEBEZIUM_STALLED              = "Debezium has not updated its heartbeat row for over 60 s, so outbox events are not being read even if no payment is waiting. Check the connect container and the connector status."
    DEBEZIUM_TASK_NOT_RUNNING     = "Kafka Connect reports the outbox connector or its task is not RUNNING (state in the line). It does not restart itself after a Kafka outage; restart the task."
    KAFKA_UNREACHABLE             = "The billing service cannot reach the Kafka broker. Committed payments stay in the outbox; order updates and emails resume when Kafka is back. Check the kafka container."
    CDC_WAL_RETAINED              = "The outbox replication slot is holding a lot of WAL in the laptop PostgreSQL (bytes in the line). Debezium is behind or stopped; fix it before the disk fills."
    PROVIDER_CREDENTIALS_REJECTED = "The payment provider rejected the app's API credentials. New payments cannot start. Check the provider key in the app settings."
    RECOVERY_CAMPAIGN_EXPIRING    = "The live recovery campaign reaches its deadline or start limit soon. After that no new uncertain payment is recovered automatically. Roll a new campaign before it expires."
  }
}

resource "aws_cloudwatch_log_metric_filter" "state" {
  for_each       = local.state_alerts
  name           = "${var.name}-${lower(replace(each.key, "_", "-"))}"
  log_group_name = aws_cloudwatch_log_group.laptop["billing"].name
  pattern        = "\"operational-alerts\" \"ALERT code=${each.key} \""
  metric_transformation {
    name          = each.key
    namespace     = local.namespace
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}
resource "aws_cloudwatch_metric_alarm" "state" {
  for_each            = local.state_alerts
  alarm_name          = "${var.name}-${lower(replace(each.key, "_", "-"))}"
  alarm_description   = substr("${each.value} ${local.logs_hint}${each.key}'.", 0, 1024)
  namespace           = local.namespace
  metric_name         = each.key
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  depends_on          = [aws_cloudwatch_log_metric_filter.state]
}

# Heartbeats. The app writes `HEARTBEAT service=billing` every check; Fluent Bit writes `alive` every 60 s.
# A crash or hang stops the first; a sleeping laptop, stopped Docker or revoked key stops both.
resource "aws_cloudwatch_log_metric_filter" "heartbeat" {
  for_each       = { billing = { group = "billing", pattern = "\"operational-alerts\" \"HEARTBEAT service=billing\"" }, shipper = { group = "shipper", pattern = "\"alive\"" } }
  name           = "${var.name}-heartbeat-${each.key}"
  log_group_name = aws_cloudwatch_log_group.laptop[each.value.group].name
  pattern        = each.value.pattern
  metric_transformation {
    name      = "HEARTBEAT_${upper(each.key)}"
    namespace = local.namespace
    value     = "1"
    unit      = "Count"
  }
}
resource "aws_cloudwatch_metric_alarm" "heartbeat" {
  for_each            = aws_cloudwatch_log_metric_filter.heartbeat
  alarm_name          = "${var.name}-heartbeat-${each.key}-missing"
  alarm_description   = "No ${each.key} heartbeat for 3 minutes. Child of a composite alarm; it sends nothing itself."
  namespace           = local.namespace
  metric_name         = "HEARTBEAT_${upper(each.key)}"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
}
resource "aws_cloudwatch_composite_alarm" "billing_down" {
  alarm_name        = "${var.name}-billing-app-down"
  alarm_description = "The Spring Boot billing app stopped sending its heartbeat for 3 minutes while the laptop log shipper is still alive: the app crashed, hung or was stopped. Payments in flight stay saved (SUBMITTING/UNKNOWN) and are recovered after restart. The container-stopped email, if any, has the exit code. Check `docker ps -a` and `docker logs payments-rzp-failures-billing-1`."
  alarm_rule        = "ALARM(\"${aws_cloudwatch_metric_alarm.heartbeat["billing"].alarm_name}\") AND NOT ALARM(\"${aws_cloudwatch_metric_alarm.heartbeat["shipper"].alarm_name}\")"
  alarm_actions     = [aws_sns_topic.alerts.arn]
  ok_actions        = [aws_sns_topic.alerts.arn]
  # When the laptop sleeps both heartbeats stop, but the app's may trip a minute first; wait for the shipper's verdict.
  actions_suppressor {
    alarm            = aws_cloudwatch_metric_alarm.heartbeat["shipper"].alarm_name
    wait_period      = 180
    extension_period = 180
  }
}
resource "aws_cloudwatch_composite_alarm" "telemetry_lost" {
  alarm_name        = "${var.name}-laptop-telemetry-lost"
  alarm_description = "Nothing has arrived from the laptop for 3 minutes: the laptop is asleep or offline, Docker or the Fluent Bit container stopped, or its access key was revoked. While this lasts no laptop alert can reach you, and Lambda recovery cannot reach the laptop database (workflows retry, then go to review)."
  alarm_rule        = "ALARM(\"${aws_cloudwatch_metric_alarm.heartbeat["shipper"].alarm_name}\")"
  alarm_actions     = [aws_sns_topic.alerts.arn]
  ok_actions        = [aws_sns_topic.alerts.arn]
}

# AWS-side recovery health.
resource "aws_cloudwatch_metric_alarm" "lambda" {
  for_each = {
    errors    = { metric = "Errors", text = "The recovery Lambda returned errors (database or tunnel unreachable, missing grant, or a bug). Step Functions retries; the failed-execution email has the cause. Check ${local.recovery_log_group}." }
    throttles = { metric = "Throttles", text = "The recovery Lambda was throttled: the account's 10 concurrent executions are in use. Recovery executions retry and may fail into review. Check for other Lambdas using the account limit." }
  }
  alarm_name          = "${var.name}-recovery-lambda-${each.key}"
  alarm_description   = each.value.text
  namespace           = "AWS/Lambda"
  metric_name         = each.value.metric
  dimensions          = { FunctionName = var.recovery_function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_event_rule" "recovery_failed" {
  name = "${var.name}-recovery-execution-failed"
  # Failed and timed-out executions are routine (a later attempt follows; the last one raises RECOVERY_NEEDS_REVIEW with
  # the error), so only a human abort is emailed here.
  description = "A payment/refund recovery workflow execution was aborted by a person"
  event_pattern = jsonencode({
    source        = ["aws.states"]
    "detail-type" = ["Step Functions Execution Status Change"]
    detail = {
      status          = ["ABORTED"]
      stateMachineArn = [{ prefix = "arn:${local.partition}:states:${var.region}:${local.account}:stateMachine:${var.recovery_state_machine_name}" }]
    }
  })
}
resource "aws_cloudwatch_event_target" "recovery_failed" {
  rule = aws_cloudwatch_event_rule.recovery_failed.name
  arn  = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths    = { execution = "$.detail.name", time = "$.time" }
    input_template = "\"[${var.name}] Recovery workflow <execution> was aborted at <time>. The payment/refund it was checking goes to finance review (separate email).\""
  }
}

# A rule whose delivery to SNS fails is otherwise silent.
resource "aws_cloudwatch_metric_alarm" "recovery_rule_failed_delivery" {
  alarm_name          = "${var.name}-recovery-event-delivery-failed"
  alarm_description   = "EventBridge could not deliver a recovery execution event to the alerts topic. Check the rule's target and the topic policy."
  namespace           = "AWS/Events"
  metric_name         = "FailedInvocations"
  dimensions          = { RuleName = aws_cloudwatch_event_rule.recovery_failed.name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

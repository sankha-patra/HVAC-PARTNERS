mock_provider "aws" {
  override_during = plan
  mock_resource "aws_sns_topic" { defaults = { arn = "arn:aws:sns:ap-southeast-2:123456789012:payments-hybrid-alerts" } }
  mock_resource "aws_kms_key" { defaults = { arn = "arn:aws:kms:ap-southeast-2:123456789012:key/00000000-0000-0000-0000-000000000000", key_id = "00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:ap-southeast-2:123456789012:log-group:/test" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/mock" } }
  mock_resource "aws_lambda_function" { defaults = { arn = "arn:aws:lambda:ap-southeast-2:123456789012:function:payments-hybrid-alert-forwarder" } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012", arn = "arn:aws:iam::123456789012:root", user_id = "mock" } }
  mock_data "aws_partition" { defaults = { partition = "aws", dns_suffix = "amazonaws.com" } }
}
variables {
  alert_email_addresses = ["payments-oncall@example.test"]
}

run "every_path_reaches_the_encrypted_topic" {
  command = plan
  assert {
    condition     = aws_sns_topic.alerts.kms_master_key_id != null && length(aws_sns_topic_subscription.email) == 1
    error_message = "The topic must be encrypted and email the configured mailbox."
  }
  assert {
    condition = alltrue(concat([for a in aws_cloudwatch_metric_alarm.state : contains(a.alarm_actions, aws_sns_topic.alerts.arn) && contains(a.ok_actions, aws_sns_topic.alerts.arn)],
      [contains(aws_cloudwatch_composite_alarm.billing_down.alarm_actions, aws_sns_topic.alerts.arn), contains(aws_cloudwatch_composite_alarm.telemetry_lost.alarm_actions, aws_sns_topic.alerts.arn)],
    [aws_cloudwatch_event_target.recovery_failed.arn == aws_sns_topic.alerts.arn]))
    error_message = "Condition alarms, heartbeat composites and failed recovery executions must all email the topic."
  }
}

run "conditions_do_not_flap_and_heartbeats_alarm_on_silence" {
  command = plan
  assert {
    condition     = alltrue([for a in aws_cloudwatch_metric_alarm.state : a.evaluation_periods == 5 && a.datapoints_to_alarm == 1 && a.treat_missing_data == "notBreaching"])
    error_message = "A condition alarm must stay in ALARM while its line repeats every minute and clear after five quiet minutes."
  }
  assert {
    condition     = alltrue([for a in aws_cloudwatch_metric_alarm.heartbeat : a.treat_missing_data == "breaching" && a.comparison_operator == "LessThanThreshold" && length(coalesce(a.alarm_actions, [])) == 0])
    error_message = "Heartbeat alarms must fire on silence and notify only through their composites."
  }
  assert {
    condition     = try(strcontains(aws_cloudwatch_composite_alarm.billing_down.alarm_rule, "AND NOT ALARM"), true) && length(aws_cloudwatch_composite_alarm.billing_down.ok_actions) == 1
    error_message = "An app crash is reported only while the laptop shipper is still alive."
  }
}

run "one_off_failures_reach_the_forwarder_from_every_source" {
  command = plan
  assert {
    condition     = toset(keys(aws_cloudwatch_log_subscription_filter.forwarder)) == toset(["billing", "recovery", "postgres", "docker_events"])
    error_message = "Billing, recovery Lambda, PostgreSQL and container-stop logs must all be forwarded."
  }
  assert {
    condition     = strcontains(aws_cloudwatch_log_subscription_filter.forwarder["billing"].filter_pattern, "ALERT code=PAYMENT_OUTCOME_UNKNOWN ") && !strcontains(aws_cloudwatch_log_subscription_filter.forwarder["billing"].filter_pattern, "OUTBOX_NOT_DRAINING") && !strcontains(aws_cloudwatch_log_subscription_filter.forwarder["billing"].filter_pattern, "DATABASE_UNAVAILABLE")
    error_message = "Event codes are forwarded one by one; condition codes are left to their alarms so nothing is emailed twice."
  }
  assert {
    condition     = strcontains(aws_cloudwatch_log_subscription_filter.forwarder["postgres"].filter_pattern, "cannot be truncated")
    error_message = "Blocked truncates must be emailed like blocked deletes."
  }
  assert {
    condition     = strcontains(aws_cloudwatch_log_subscription_filter.forwarder["docker_events"].filter_pattern, "$.Action")
    error_message = "Docker 25+ events carry Action, not the legacy status field."
  }
}

run "laptop_credentials_can_only_do_their_one_job" {
  command = plan
  assert {
    condition     = jsondecode(aws_iam_user_policy.telemetry.policy).Statement[0].Action == ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    error_message = "The log shipper may only write log events."
  }
  assert {
    condition     = toset(flatten([for st in jsondecode(aws_iam_user_policy.dispatcher.policy).Statement : st.Action])) == toset(["states:StartExecution", "states:DescribeExecution"])
    error_message = "The dispatcher may only start and read recovery executions."
  }
}

run "alert_mailboxes_are_required" {
  command = plan
  variables {
    alert_email_addresses = []
  }
  expect_failures = [var.alert_email_addresses]
}

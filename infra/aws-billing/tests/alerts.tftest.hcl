mock_provider "aws" {
  override_during = plan
  mock_resource "aws_sns_topic" { defaults = { arn = "arn:aws:sns:us-east-1:123456789012:invoice-payments-alerts" } }
  mock_resource "aws_kms_key" { defaults = { arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_msk_configuration" { defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:configuration/test/00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_lb" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/test/0000000000000000" } }
  mock_resource "aws_lb_listener" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/test/0000000000000000/0000000000000000" } }
  mock_resource "aws_lb_target_group" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/test/0000000000000000" } }
  mock_resource "aws_cloudfront_function" { defaults = { arn = "arn:aws:cloudfront::123456789012:function/test" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:/test" } }
  mock_resource "aws_ecs_cluster" { defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:cluster/test" } }
  mock_resource "aws_ecs_task_definition" { defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/test:1" } }
  mock_data "aws_availability_zones" {
    defaults = { names = ["us-east-1a", "us-east-1b", "us-east-1c"] }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012", arn = "arn:aws:iam::123456789012:root", user_id = "mock" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws", dns_suffix = "amazonaws.com" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_acm_certificate" {
    defaults = { arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000001", domain_validation_options = [{ domain_name = "example.test", resource_record_name = "_mock.example.test", resource_record_type = "CNAME", resource_record_value = "_mock.validation.test" }] }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000001" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { repository_url = "123456789012.dkr.ecr.us-east-1.amazonaws.com/mock", arn = "arn:aws:ecr:us-east-1:123456789012:repository/mock" }
  }
  mock_resource "aws_msk_cluster" {
    defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:cluster/mock/00000000-0000-0000-0000-000000000001-1", bootstrap_brokers_sasl_iam = "mock-broker.example.test:9098" }
  }
  mock_resource "aws_mskconnect_worker_configuration" {
    defaults = { arn = "arn:aws:kafkaconnect:us-east-1:123456789012:worker-configuration/mock/00000000-0000-0000-0000-000000000001-1", latest_revision = 1 }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      address            = "mock.database.internal"
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }]
    }
  }
}
mock_provider "random" {}
override_resource {
  target          = random_password.origin
  override_during = plan
  values          = { result = "test-only-origin-header-not-a-real-secret" }
}
override_resource {
  target          = aws_db_instance.billing
  override_during = plan
  values          = { address = "mock.database.internal", master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }] }
}
override_resource {
  target          = aws_msk_cluster.billing
  override_during = plan
  values          = { arn = "arn:aws:kafka:us-east-1:123456789012:cluster/mock/00000000-0000-0000-0000-000000000001-1", bootstrap_brokers_sasl_iam = "mock-broker.example.test:9098" }
}
variables {
  route53_zone_id            = "Z123456789"
  api_origin_hostname        = "origin.example.test"
  frontend_hostname          = "shop.example.test"
  image_digests              = { identity = "sha256:0000000000000000000000000000000000000000000000000000000000000000", billing = "sha256:0000000000000000000000000000000000000000000000000000000000000000" }
  database_secret_arns       = { identity = "arn:aws:secretsmanager:us-east-1:123456789012:secret:identity-db", billing = "arn:aws:secretsmanager:us-east-1:123456789012:secret:billing-db" }
  worker_database_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:worker-db"
  jwt_secret_arn             = "arn:aws:secretsmanager:us-east-1:123456789012:secret:jwt"
  stripe_secret_arn          = "arn:aws:secretsmanager:us-east-1:123456789012:secret:stripe"
  provider_merchant_id       = "10000000-0000-0000-0000-000000000001"
}
run "every_alarm_and_failure_event_emails_the_alert_topic" {
  command = plan
  variables {
    alert_email_addresses = ["payments-oncall@example.test", "carrier-finance@example.test"]
  }
  assert {
    condition     = aws_sns_topic.alerts.kms_master_key_id != null && length(aws_sns_topic_subscription.alert_email) == 2 && alltrue([for s in aws_sns_topic_subscription.alert_email : s.protocol == "email"])
    error_message = "The alert topic must be encrypted and email every configured mailbox."
  }
  assert {
    condition = alltrue(concat(
      [for a in aws_cloudwatch_metric_alarm.app_alert : contains(a.alarm_actions, aws_sns_topic.alerts.arn)],
      [contains(aws_cloudwatch_metric_alarm.protected_row_change.alarm_actions, aws_sns_topic.alerts.arn)],
      [for a in aws_cloudwatch_metric_alarm.kafka_replicas : contains(a.alarm_actions, aws_sns_topic.alerts.arn)],
      [contains(aws_cloudwatch_metric_alarm.database_storage.alarm_actions, aws_sns_topic.alerts.arn)]
    ))
    error_message = "Application, database and infrastructure alarms must all notify the alert topic."
  }
  assert {
    condition     = aws_cloudwatch_event_target.ecs_task_crashed.arn == aws_sns_topic.alerts.arn && aws_cloudwatch_event_target.rds_events.arn == aws_sns_topic.alerts.arn
    error_message = "ECS crashes and RDS outages must reach the alert topic with their AWS reason."
  }
}

run "each_application_alert_code_has_a_filter_per_log_group_and_one_alarm" {
  command = plan
  assert {
    condition = alltrue([for code in ["PAYMENT_OUTCOME_UNKNOWN", "REFUND_OUTCOME_UNKNOWN", "DATABASE_UNAVAILABLE", "FINANCE_CASE_OPENED", "RECOVERY_NEEDS_REVIEW", "OUTBOX_NOT_DRAINING", "RECOVERY_WORK_NOT_CLAIMED", "CDC_SLOT_INACTIVE", "MONEY_OPERATION_STUCK",
      "RECOVERY_DISPATCH_FAILING", "PROVIDER_PAUSED", "DEAD_LETTER_OPEN", "NOTIFICATION_DELIVERY_FAILING", "DEBEZIUM_STALLED", "KAFKA_UNREACHABLE", "CDC_WAL_RETAINED", "PROVIDER_CREDENTIALS_REJECTED", "RECOVERY_CAMPAIGN_EXPIRING"] :
    contains(keys(aws_cloudwatch_metric_alarm.app_alert), code) && length([for f in aws_cloudwatch_log_metric_filter.app_alert : f if f.metric_transformation[0].name == code]) == 2])
    error_message = "Every ALERT code written by OperationalAlert must have a metric filter on the billing and worker log groups and one alarm."
  }
  assert {
    condition     = alltrue([for f in aws_cloudwatch_log_metric_filter.app_alert : f.pattern == "\"operational-alerts\" \"ALERT code=${f.metric_transformation[0].name} \""])
    error_message = "Filters must match the alert logger and the exact ALERT line prefix so one code cannot match another."
  }
  assert {
    condition     = alltrue([for a in aws_cloudwatch_metric_alarm.app_alert : a.evaluation_periods == 5 && a.datapoints_to_alarm == 1])
    error_message = "Alarms must not flap to OK between repeated condition lines."
  }
  assert {
    condition     = length(aws_cloudwatch_event_rule.recovery_failed) == 0 && length(aws_cloudwatch_metric_alarm.recovery_lambda_errors) == 0
    error_message = "Recovery alerts stay off until the separately deployed recovery stack is named."
  }
}

run "named_recovery_stack_gets_failure_and_error_alerts" {
  command = plan
  variables {
    recovery_state_machine_arns = ["arn:aws:states:us-east-1:123456789012:stateMachine:payments-recovery"]
    recovery_function_names     = ["payments-recovery"]
  }
  assert {
    condition     = length(aws_cloudwatch_event_rule.recovery_failed) == 1 && aws_cloudwatch_event_target.recovery_failed[0].arn == aws_sns_topic.alerts.arn && length(aws_cloudwatch_metric_alarm.recovery_lambda_errors) == 1
    error_message = "A named recovery stack must email failed executions and Lambda errors."
  }
  assert {
    condition     = length([for f in aws_cloudwatch_log_metric_filter.app_alert : f if f.log_group_name == "/aws/lambda/payments-recovery"]) == length(aws_cloudwatch_metric_alarm.app_alert)
    error_message = "ALERT lines written by the recovery Lambda must be filtered too."
  }
}

run "alert_mailboxes_must_be_email_addresses" {
  command = plan
  variables {
    alert_email_addresses = ["not-an-email"]
  }
  expect_failures = [var.alert_email_addresses]
}

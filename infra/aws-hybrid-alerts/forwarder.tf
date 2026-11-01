locals {
  # DATABASE_UNAVAILABLE is an alarm (alarms.tf): one outage is one email and one OK, not one per line.
  event_codes   = ["PAYMENT_OUTCOME_UNKNOWN", "REFUND_OUTCOME_UNKNOWN", "FINANCE_CASE_OPENED", "RECOVERY_NEEDS_REVIEW", "RECOVERY_DISPATCH_FAILING", "PROVIDER_PAUSED", "WEBHOOK_REJECTED"]
  event_pattern = join(" ", [for code in local.event_codes : "?\"ALERT code=${code} \""])
  forwarded = {
    billing       = { group = aws_cloudwatch_log_group.laptop["billing"].name, pattern = local.event_pattern }
    recovery      = { group = local.recovery_log_group, pattern = local.event_pattern }
    postgres      = { group = aws_cloudwatch_log_group.laptop["postgres"].name, pattern = "?\"cannot be deleted\" ?\"cannot be truncated\" ?\"is immutable\" ?\"update or delete on table\" ?PANIC ?\"terminated by signal\" ?\"No space left on device\" ?\"database system is shut down\"" }
    docker_events = { group = aws_cloudwatch_log_group.laptop["docker_events"].name, pattern = "{ ($.Action = \"die\") || ($.Action = \"oom\") }" }
  }
}

data "archive_file" "forwarder" {
  type        = "zip"
  source_file = "${path.module}/forwarder/forwarder.py"
  output_path = "${path.module}/.build/forwarder.zip"
}
resource "aws_iam_role" "forwarder" {
  name = "${var.name}-alert-forwarder"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole", Condition = { StringEquals = { "aws:SourceAccount" = local.account } } }]
  })
}
resource "aws_iam_role_policy" "forwarder" {
  name = "publish-alerts"
  role = aws_iam_role.forwarder.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "sns:Publish", Resource = aws_sns_topic.alerts.arn },
      { Effect = "Allow", Action = ["kms:GenerateDataKey*", "kms:Decrypt"], Resource = aws_kms_key.alerts.arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.forwarder.arn}:*" }
    ]
  })
}
resource "aws_cloudwatch_log_group" "forwarder" {
  name              = "/aws/lambda/${var.name}-alert-forwarder"
  retention_in_days = 30
}
resource "aws_lambda_function" "forwarder" {
  function_name    = "${var.name}-alert-forwarder"
  role             = aws_iam_role.forwarder.arn
  runtime          = "python3.13"
  architectures    = ["arm64"]
  handler          = "forwarder.handler"
  filename         = data.archive_file.forwarder.output_path
  source_code_hash = data.archive_file.forwarder.output_base64sha256
  memory_size      = 128
  timeout          = 10
  environment {
    variables = {
      TOPIC_ARN          = aws_sns_topic.alerts.arn
      STACK_NAME         = var.name
      WATCHED_CONTAINERS = jsonencode(var.watched_containers)
    }
  }
  depends_on = [aws_iam_role_policy.forwarder, aws_cloudwatch_log_group.forwarder]
}
resource "aws_lambda_permission" "forwarder" {
  for_each       = local.forwarded
  statement_id   = "logs-${each.key}"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.forwarder.function_name
  principal      = "logs.amazonaws.com"
  source_arn     = "arn:${local.partition}:logs:${var.region}:${local.account}:log-group:${each.value.group}:*"
  source_account = local.account
}
resource "aws_cloudwatch_log_subscription_filter" "forwarder" {
  for_each        = local.forwarded
  name            = "${var.name}-forward-${each.key}"
  log_group_name  = each.value.group
  filter_pattern  = each.value.pattern
  destination_arn = aws_lambda_function.forwarder.arn
  depends_on      = [aws_lambda_permission.forwarder]
}

# The forwarder itself failing would silence one-off alerts; its errors are an alarm.
resource "aws_cloudwatch_metric_alarm" "forwarder_errors" {
  alarm_name          = "${var.name}-alert-forwarder-errors"
  alarm_description   = "The alert forwarder Lambda failed, so one-off failure emails may be missing. Check /aws/lambda/${var.name}-alert-forwarder."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.forwarder.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

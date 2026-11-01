resource "aws_kms_key" "alerts" {
  description             = "${var.name} alert topic encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "AccountAdministration", Effect = "Allow", Principal = { AWS = "arn:${local.partition}:iam::${local.account}:root" }, Action = "kms:*", Resource = "*" },
      {
        Sid       = "AlarmAndEventPublishers", Effect = "Allow",
        Principal = { Service = ["cloudwatch.amazonaws.com", "events.amazonaws.com"] },
        Action    = ["kms:Decrypt", "kms:GenerateDataKey*"], Resource = "*",
        Condition = { StringEquals = { "aws:SourceAccount" = local.account } }
      }
    ]
  })
}
resource "aws_kms_alias" "alerts" {
  name          = "alias/${var.name}-alerts"
  target_key_id = aws_kms_key.alerts.key_id
}
resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-alerts"
  kms_master_key_id = aws_kms_key.alerts.arn
}
resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AlarmAndEventPublishers", Effect = "Allow",
      Principal = { Service = ["cloudwatch.amazonaws.com", "events.amazonaws.com"] },
      Action    = "sns:Publish", Resource = aws_sns_topic.alerts.arn,
      Condition = { StringEquals = { "aws:SourceAccount" = local.account } }
    }]
  })
}
resource "aws_sns_topic_subscription" "email" {
  for_each  = toset(var.alert_email_addresses)
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_cloudwatch_log_group" "laptop" {
  for_each          = local.log_groups
  name              = each.value
  retention_in_days = 30
}

# Fluent Bit on the laptop writes with this user's access key (created outside Terraform, kept in the lab's private/
# folder). It can only write log events into this stack's log groups.
resource "aws_iam_user" "telemetry" {
  name = "${var.name}-telemetry"
}
resource "aws_iam_user_policy" "telemetry" {
  name = "write-laptop-logs"
  user = aws_iam_user.telemetry.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = [for group in values(local.log_groups) : "arn:${local.partition}:logs:${var.region}:${local.account}:log-group:${group}:*"]
    }]
  })
}

# The app's recovery dispatcher starts and reads recovery executions with this user's key instead of the developer's
# own AWS sign-in, so it keeps working overnight and can do nothing else.
resource "aws_iam_user" "dispatcher" {
  name = "${var.name}-dispatcher"
}
resource "aws_iam_user_policy" "dispatcher" {
  name = "start-and-read-recovery"
  user = aws_iam_user.dispatcher.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "states:StartExecution", Resource = ["arn:${local.partition}:states:${var.region}:${local.account}:stateMachine:${var.recovery_state_machine_name}", "arn:${local.partition}:states:${var.region}:${local.account}:stateMachine:${var.recovery_state_machine_name}:*"] },
      { Effect = "Allow", Action = "states:DescribeExecution", Resource = "arn:${local.partition}:states:${var.region}:${local.account}:execution:${var.recovery_state_machine_name}:*" }
    ]
  })
}

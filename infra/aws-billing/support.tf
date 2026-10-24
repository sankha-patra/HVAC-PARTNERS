# Read-only monitoring for the application support team: the service provider that builds and runs the app.
# This file only defines what that access allows. Carrier's identity team attaches it, together with the AWS
# managed ViewOnlyAccess job-function policy, to its own single sign-on permission set, and decides who gets it.
# Nothing here assigns access to anyone. Anything beyond reading is temporary access that Carrier approves.
resource "aws_iam_policy" "application_support_read_only" {
  name        = "${var.name}-application-support-read-only"
  description = "Application support monitoring: metrics, alarms and the application's own logs. Never data, secrets, sessions or changes."
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadTheApplicationsOwnLogs"
        Effect   = "Allow"
        Action   = ["logs:DescribeLogStreams", "logs:GetLogEvents", "logs:FilterLogEvents", "logs:StartQuery"]
        Resource = flatten([for group in aws_cloudwatch_log_group.application : [group.arn, "${group.arn}:*"]])
      },
      {
        # These read actions do not support resource-level restriction.
        Sid    = "ReadMetricsAlarmsAndDashboards"
        Effect = "Allow"
        Action = [
          "cloudwatch:DescribeAlarms", "cloudwatch:DescribeAlarmHistory", "cloudwatch:GetMetricData",
          "cloudwatch:GetMetricStatistics", "cloudwatch:ListMetrics", "cloudwatch:GetDashboard", "cloudwatch:ListDashboards",
          "logs:DescribeLogGroups", "logs:DescribeQueries", "logs:GetQueryResults", "logs:StopQuery"
        ]
        Resource = "*"
      },
      {
        # Explicit, so attaching a broader read policy by mistake still cannot expose data or allow changes.
        Sid    = "NeverDataSecretsSessionsOrChanges"
        Effect = "Deny"
        Action = [
          "secretsmanager:GetSecretValue", "ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath", "kms:Decrypt",
          "s3:GetObject", "s3:GetObjectVersion", "rds-db:connect", "rds-data:*", "ecs:ExecuteCommand", "ssm:StartSession",
          "kafka-cluster:Connect", "kafka-cluster:ReadData", "iam:*", "sso:*",
          "cloudtrail:StopLogging", "cloudtrail:DeleteTrail", "cloudtrail:UpdateTrail"
        ]
        Resource = "*"
      }
    ]
  })
}

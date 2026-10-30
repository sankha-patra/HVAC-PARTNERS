# Thresholds are starting operational signals, not measured production SLOs.
resource "aws_cloudwatch_metric_alarm" "no_healthy_targets" {
  for_each            = local.enabled_services
  alarm_name          = "${var.name}-${each.key}-no-healthy-target"
  alarm_description   = "No healthy target or no health telemetry for an enabled service; inspect desired/running tasks and ALB target registration."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = 3
  period              = 60
  treat_missing_data  = "breaching"
  dimensions = {
    LoadBalancer = aws_lb.billing.arn_suffix
    TargetGroup  = aws_lb_target_group.application[each.key].arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  for_each            = local.enabled_services
  alarm_name          = "${var.name}-${each.key}-unhealthy-target"
  alarm_description   = "A registered application target failed readiness. Inspect ECS events and private health before restarting anything."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 3
  period              = 60
  treat_missing_data  = "notBreaching"
  dimensions = {
    LoadBalancer = aws_lb.billing.arn_suffix
    TargetGroup  = aws_lb_target_group.application[each.key].arn_suffix
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "api_errors" {
  count               = var.application_enabled ? 1 : 0
  alarm_name          = "${var.name}-api-5xx"
  alarm_description   = "More than 5% target responses are 5xx with at least 100 requests in each five-minute interval."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 2
  treat_missing_data  = "notBreaching"
  metric_query {
    id          = "error_rate"
    expression  = "IF(requests >= 100, 100 * FILL(errors, 0) / requests, 0)"
    label       = "HTTP 5xx percent with traffic floor"
    return_data = true
  }
  metric_query {
    id = "errors"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.billing.arn_suffix }
    }
  }
  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.billing.arn_suffix }
    }
  }
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${var.name}-database-storage"
  alarm_description   = "Database free storage below 10 GiB; inspect growth before reaching the configured autoscaling cap."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  comparison_operator = "LessThanThreshold"
  threshold           = 10737418240
  evaluation_periods  = 3
  period              = 60
  treat_missing_data  = "missing"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.billing.identifier }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "kafka_replicas" {
  for_each            = toset(["1", "2", "3"])
  alarm_name          = "${var.name}-kafka-${each.key}-under-replicated"
  alarm_description   = "Kafka partitions are under-replicated. Preserve acks=all/minISR2; do not weaken durability to clear the alarm."
  namespace           = "AWS/Kafka"
  metric_name         = "UnderReplicatedPartitions"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 3
  period              = 60
  treat_missing_data  = "missing"
  dimensions          = { "Cluster Name" = aws_msk_cluster.billing.cluster_name, "Broker ID" = each.key }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cdc_task_errors" {
  count               = var.cdc_enabled ? 1 : 0
  alarm_name          = "${var.name}-cdc-task-errors"
  namespace           = "AWS/KafkaConnect"
  metric_name         = "ErroredTaskCount"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 60
  evaluation_periods  = 2
  treat_missing_data  = "breaching"
  dimensions          = { ConnectorName = aws_mskconnect_connector.outbox[0].name }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "cdc_slot_storage" {
  count               = var.cdc_enabled ? 1 : 0
  alarm_name          = "${var.name}-cdc-slot-storage"
  namespace           = "AWS/RDS"
  metric_name         = "ReplicationSlotDiskUsage"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 536870912
  period              = 60
  evaluation_periods  = 3
  treat_missing_data  = "breaching"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.billing.identifier }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
resource "aws_cloudwatch_metric_alarm" "worker_missing" {
  count               = var.application_enabled ? 1 : 0
  alarm_name          = "${var.name}-worker-missing"
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Minimum"
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  period              = 60
  evaluation_periods  = 3
  treat_missing_data  = "breaching"
  dimensions          = { ClusterName = aws_ecs_cluster.billing.name, ServiceName = aws_ecs_service.worker[0].name }
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

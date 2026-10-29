resource "aws_ecr_repository" "application" {
  for_each             = local.services
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
}
resource "aws_cloudwatch_log_group" "application" {
  for_each          = local.services
  name              = "/${var.name}/${each.key}"
  retention_in_days = 30
}
resource "aws_ecs_cluster" "billing" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:*"]
    }
  }
}
locals {
  secret_arns = {
    for service in keys(local.enabled_services) : service => compact(concat(
      [lookup(var.database_secret_arns, service, ""), var.jwt_secret_arn],
      service == "billing" ? [var.stripe_secret_arn] : []
    ))
  }
}
resource "aws_iam_role" "execution" {
  for_each           = local.enabled_services
  name               = "${var.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
resource "aws_iam_role_policy" "execution" {
  for_each = local.enabled_services
  role     = aws_iam_role.execution[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.application[each.key].arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.application[each.key].arn}:*" },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = local.secret_arns[each.key] }
      ], length(var.secret_kms_key_arns) > 0 ? [{
        Effect    = "Allow", Action = ["kms:Decrypt"], Resource = sort(tolist(var.secret_kms_key_arns))
        Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.region}.amazonaws.com" } }
    }] : [])
  })
}
resource "aws_iam_role" "task" {
  for_each           = local.enabled_services
  name               = "${var.name}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
resource "aws_iam_role_policy" "payment_kafka" {
  count = var.application_enabled ? 1 : 0
  role  = aws_iam_role.worker[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["kafka-cluster:Connect", "kafka-cluster:WriteDataIdempotently"], Resource = aws_msk_cluster.billing.arn },
      {
        Effect   = "Allow", Action = ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"]
        Resource = [for topic in ["billing.events.v1", "billing.events.v1.DLT"] : "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":topic/")}/${topic}"]
      },
      {
        Effect   = "Allow", Action = ["kafka-cluster:ReadData"]
        Resource = [for topic in ["billing.events.v1", "billing.events.v1.DLT"] : "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":topic/")}/${topic}"]
      },
      {
        Effect   = "Allow", Action = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
        Resource = [for group in ["billing-dlt-audit-v1", "billing-cdc-receipts-v1"] : "${replace(aws_msk_cluster.billing.arn, ":cluster/", ":group/")}/${group}"]
      }
    ]
  })
}
locals {
  common_environment = {
    SPRING_PROFILES_ACTIVE                             = "prod,billing"
    SPRING_DATASOURCE_URL                              = "jdbc:postgresql://${aws_db_instance.billing.address}:5432/invoice_payments?sslmode=verify-full&sslrootcert=/app/certs/global-bundle.pem"
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE         = "10"
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE              = "2"
    SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT        = "3000"
    SPRING_JPA_OPEN_IN_VIEW                            = "false"
    APP_DEMO_USERS_ENABLED                             = "false"
    APP_JWT_COOKIE_SECURE                              = "true"
    BILLING_PUBLIC_URL                                 = "https://${var.frontend_hostname}"
    SERVER_FORWARD_HEADERS_STRATEGY                    = "none"
    SERVER_SHUTDOWN                                    = "graceful"
    SPRING_TASK_SCHEDULING_SHUTDOWN_AWAIT_TERMINATION = "true"
    SPRING_TASK_SCHEDULING_SHUTDOWN_AWAIT_TERMINATION_PERIOD = "30s"
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE        = "30s"
    MANAGEMENT_SERVER_PORT                             = "9090"
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE          = "health,info"
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS            = "never"
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED          = "true"
    MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE = "readinessState,db"
    MANAGEMENT_TRACING_ENABLED                         = "false"
    JAVA_TOOL_OPTIONS                                  = "-XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp"
  }
  service_environment = {
    identity = {
      SERVER_PORT = "8081"
    }
    billing = {
      SERVER_PORT                                                            = "8084"
      BILLING_PROVIDER                                                       = "STRIPE"
      BILLING_PROVIDER_MERCHANT_ID                                           = var.provider_merchant_id
      BILLING_SIMULATOR_ENABLED                                              = "false"
      BILLING_DEMO_ENABLED                                                   = "false"
      BILLING_ERP_SIMULATOR_ENABLED                                          = "false"
      BILLING_WORKERS_ENABLED                                                = "false"
      BILLING_ROLE                                                           = "api"
      BILLING_REQUIRE_SEPARATED_ROLE                                         = "true"
      BILLING_REQUIRED_RELAY_MODE                                            = var.cdc_enabled ? "CDC" : "POLL"
      BILLING_STRIPE_MAX_CONCURRENT                                          = tostring(local.stripe_slots_per_task)
      BILLING_STRIPE_FLEET_CONCURRENCY_BUDGET                                = tostring(var.stripe_fleet_concurrency_budget)
      BILLING_STRIPE_MAXIMUM_FLEET_TASKS                                     = tostring(local.maximum_payment_fleet_tasks)
      SPRING_KAFKA_ADMIN_AUTO_CREATE                                         = "false"
      KAFKA_BOOTSTRAP_SERVERS                                                = aws_msk_cluster.billing.bootstrap_brokers_sasl_iam
      SPRING_KAFKA_BOOTSTRAP_SERVERS                                         = aws_msk_cluster.billing.bootstrap_brokers_sasl_iam
      SPRING_KAFKA_PRODUCER_ACKS                                             = "all"
      SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL                              = "SASL_SSL"
      SPRING_KAFKA_PROPERTIES_SASL_MECHANISM                                 = "AWS_MSK_IAM"
      SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG                               = "software.amazon.msk.auth.iam.IAMLoginModule required;"
      SPRING_KAFKA_PROPERTIES_SASL_CLIENT_CALLBACK_HANDLER_CLASS             = "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
      SPRING_KAFKA_PRODUCER_PROPERTIES_ENABLE_IDEMPOTENCE                    = "true"
      SPRING_KAFKA_PRODUCER_PROPERTIES_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION = "5"
      SPRING_KAFKA_PRODUCER_PROPERTIES_DELIVERY_TIMEOUT_MS                   = "120000"
      SPRING_KAFKA_PRODUCER_PROPERTIES_REQUEST_TIMEOUT_MS                    = "30000"
      SPRING_KAFKA_PRODUCER_PROPERTIES_MAX_BLOCK_MS                          = "10000"
      SPRING_KAFKA_PRODUCER_PROPERTIES_COMPRESSION_TYPE                      = "lz4"
      SPRING_KAFKA_PRODUCER_PROPERTIES_LINGER_MS                             = "5"
    }
  }
}
resource "aws_ecs_task_definition" "application" {
  for_each                 = local.enabled_services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume { name = "temporary" }
  container_definitions = jsonencode([{
    name                   = each.key
    image                  = "${aws_ecr_repository.application[each.key].repository_url}@${var.image_digests[each.key]}"
    essential              = true
    user                   = "10001:10001"
    readonlyRootFilesystem = true
    stopTimeout            = 60
    linuxParameters        = { initProcessEnabled = true, capabilities = { drop = ["ALL"] } }
    mountPoints            = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }]
    portMappings           = [{ containerPort = each.value.port, protocol = "tcp" }, { containerPort = 9090, protocol = "tcp" }]
    environment            = [for name, value in merge(local.common_environment, local.service_environment[each.key]) : { name = name, value = value }]
    secrets = concat([
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${var.database_secret_arns[each.key]}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${var.database_secret_arns[each.key]}:password::" },
      { name = "APP_JWT_SECRET", valueFrom = var.jwt_secret_arn }
      ], each.key == "billing" ? [
      { name = "BILLING_STRIPE_SECRET_KEY", valueFrom = "${var.stripe_secret_arn}:secret_key::" },
      { name = "BILLING_STRIPE_WEBHOOK_SECRET", valueFrom = "${var.stripe_secret_arn}:webhook_secret::" }
    ] : [])
    healthCheck = {
      command     = ["CMD-SHELL", "curl --fail --silent http://localhost:9090/actuator/health/liveness >/dev/null || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 120
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.application[each.key].name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "ecs"
        "mode"                  = "non-blocking"
        "max-buffer-size"       = "4m"
      }
    }
  }])
  depends_on = [aws_iam_role_policy.execution]
}
resource "aws_ecs_service" "application" {
  for_each                           = local.enabled_services
  name                               = each.key
  cluster                            = aws_ecs_cluster.billing.id
  task_definition                    = aws_ecs_task_definition.application[each.key].arn
  desired_count                      = 2
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  enable_execute_command             = false
  availability_zone_rebalancing      = "ENABLED"
  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.application[*].id
    security_groups  = [aws_security_group.application[each.key].id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.application[each.key].arn
    container_name   = each.key
    container_port   = each.value.port
  }
  lifecycle { ignore_changes = [desired_count] }
  depends_on = [aws_lb_listener_rule.application, aws_route_table_association.application, aws_iam_role_policy.payment_kafka]
}
resource "aws_appautoscaling_target" "application" {
  for_each           = local.enabled_services
  max_capacity       = var.maximum_tasks_per_service
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.billing.name}/${aws_ecs_service.application[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}
resource "aws_appautoscaling_policy" "cpu" {
  for_each           = local.enabled_services
  name               = "${var.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.application[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.application[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.application[each.key].service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}



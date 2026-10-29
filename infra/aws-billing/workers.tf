variable "maximum_worker_tasks" {
  type    = number
  default = 2
  validation {
    condition     = var.maximum_worker_tasks >= 2 && var.maximum_worker_tasks <= 6 && floor(var.maximum_worker_tasks) == var.maximum_worker_tasks
    error_message = "Choose 2-6 worker tasks and budget every rollout copy."
  }
}
variable "stripe_fleet_concurrency_budget" {
  description = "Engineering cap across API + worker tasks including 200% rollout surge; validate against the actual Stripe account and load tests."
  type        = number
  default     = 32
  validation {
    condition     = var.stripe_fleet_concurrency_budget >= 2 * (var.maximum_tasks_per_service + var.maximum_worker_tasks) && floor(var.stripe_fleet_concurrency_budget) == var.stripe_fleet_concurrency_budget
    error_message = "The fleet budget must permit at least one call per possible API/worker rollout task."
  }
}
variable "database_application_connection_budget" {
  description = "Connections allocated to this app after DBA/admin/other workload reserves, checked against maximum replicas and rollout surge. Must fit the provisioned RDS max_connections."
  type        = number
  default     = 300
}
variable "worker_database_secret_arn" {
  description = "Separate Secrets Manager username/password for the worker, with billing DML/sequence grants and no identity-schema grants."
  type        = string
  default     = ""
  validation {
    condition     = !var.application_enabled || can(regex("^arn:aws:secretsmanager:", var.worker_database_secret_arn))
    error_message = "Running applications requires an independently provisioned worker database login secret."
  }
}
locals {
  maximum_payment_fleet_tasks = 2 * (var.maximum_tasks_per_service + var.maximum_worker_tasks)
  stripe_slots_per_task       = floor(var.stripe_fleet_concurrency_budget / local.maximum_payment_fleet_tasks)
  maximum_app_connections     = 2 * (20 * var.maximum_tasks_per_service + 12 * var.maximum_worker_tasks) + (var.cdc_enabled ? 4 : 0)
}
check "database_connection_budget" {
  assert {
    condition     = local.maximum_app_connections <= var.database_application_connection_budget
    error_message = "Identity/API/worker pools at 200% rollout plus connector connections exceed the allocated DB budget."
  }
}
resource "aws_cloudwatch_log_group" "worker" {
  name              = "/${var.name}/payment-worker"
  retention_in_days = 30
}
resource "aws_security_group" "worker" {
  name_prefix = "${var.name}-worker-"
  description = "Private payment worker; no inbound business or management routes."
  vpc_id      = aws_vpc.billing.id
}
resource "aws_vpc_security_group_egress_rule" "worker_https" {
  security_group_id = aws_security_group.worker.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "worker_database" {
  security_group_id            = aws_security_group.worker.id
  referenced_security_group_id = aws_security_group.database.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_ingress_rule" "database_worker" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.worker.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_egress_rule" "worker_kafka" {
  security_group_id            = aws_security_group.worker.id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_vpc_security_group_ingress_rule" "kafka_worker" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.worker.id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_iam_role" "worker_execution" {
  count              = var.application_enabled ? 1 : 0
  name               = "${var.name}-worker-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
resource "aws_iam_role_policy" "worker_execution" {
  count = var.application_enabled ? 1 : 0
  role  = aws_iam_role.worker_execution[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.application["billing"].arn },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.worker.arn}:*" },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.worker_database_secret_arn, var.stripe_secret_arn, var.jwt_secret_arn] }
  ], length(var.secret_kms_key_arns) > 0 ? [{ Effect = "Allow", Action = ["kms:Decrypt"], Resource = sort(tolist(var.secret_kms_key_arns)), Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.region}.amazonaws.com" } } }] : []) })
}
resource "aws_iam_role" "worker" {
  count              = var.application_enabled ? 1 : 0
  name               = "${var.name}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}
resource "aws_ecs_task_definition" "worker" {
  lifecycle {
    precondition {
      condition = local.maximum_app_connections <= var.database_application_connection_budget
      error_message = "Maximum rollout database pools exceed the allocated database connection budget."
    }
  }
  count                    = var.application_enabled ? 1 : 0
  family                   = "${var.name}-payment-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.worker_execution[0].arn
  task_role_arn            = aws_iam_role.worker[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume { name = "temporary" }
  container_definitions = jsonencode([{
    name            = "payment-worker", image = "${aws_ecr_repository.application["billing"].repository_url}@${var.image_digests["billing"]}", essential = true,
    user            = "10001:10001", readonlyRootFilesystem = true, stopTimeout = 120,
    linuxParameters = { initProcessEnabled = true, capabilities = { drop = ["ALL"] } },
    mountPoints     = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }],
    environment = [for name, value in merge(local.common_environment, local.service_environment.billing, {
      BILLING_ROLE                                = "worker", BILLING_WORKERS_ENABLED = "true", BILLING_OUTBOX_PUBLISHER_ENABLED = var.cdc_enabled ? "false" : "true",
      SPRING_FLYWAY_ENABLED                       = "false", SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "12", BILLING_WORKER_SHUTDOWN_SECONDS = "60",
      SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE = "110s"
    }) : { name = name, value = value }],
    secrets = [
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${var.worker_database_secret_arn}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${var.worker_database_secret_arn}:password::" },
      { name = "APP_JWT_SECRET", valueFrom = var.jwt_secret_arn },
      { name = "BILLING_STRIPE_SECRET_KEY", valueFrom = "${var.stripe_secret_arn}:secret_key::" },
      { name = "BILLING_STRIPE_WEBHOOK_SECRET", valueFrom = "${var.stripe_secret_arn}:webhook_secret::" }
    ],
    healthCheck      = { command = ["CMD-SHELL", "curl --fail --silent http://localhost:9090/actuator/health/liveness >/dev/null || exit 1"], interval = 30, timeout = 5, retries = 3, startPeriod = 120 },
    logConfiguration = { logDriver = "awslogs", options = { "awslogs-group" = aws_cloudwatch_log_group.worker.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "ecs", "mode" = "non-blocking", "max-buffer-size" = "4m" } }
  }])
  depends_on = [aws_iam_role_policy.worker_execution]
}
resource "aws_ecs_service" "worker" {
  count                              = var.application_enabled ? 1 : 0
  name                               = "payment-worker"
  cluster                            = aws_ecs_cluster.billing.id
  task_definition                    = aws_ecs_task_definition.worker[0].arn
  desired_count                      = 2
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  availability_zone_rebalancing      = "ENABLED"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.application[*].id
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = false
  }
  # No ALB target group or published business port for the worker.
  lifecycle { ignore_changes = [desired_count] }
  depends_on = [aws_iam_role_policy.payment_kafka, aws_route_table_association.application]
}
resource "aws_appautoscaling_target" "worker" {
  count              = var.application_enabled ? 1 : 0
  max_capacity       = var.maximum_worker_tasks
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.billing.name}/${aws_ecs_service.worker[0].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}
resource "aws_appautoscaling_policy" "worker_cpu" {
  count              = var.application_enabled ? 1 : 0
  name               = "${var.name}-worker-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.worker[0].resource_id
  scalable_dimension = aws_appautoscaling_target.worker[0].scalable_dimension
  service_namespace  = "ecs"
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}

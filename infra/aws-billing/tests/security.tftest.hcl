mock_provider "aws" {
  override_during = plan
  mock_data "aws_availability_zones" { defaults = { names = ["us-east-1a", "us-east-1b", "us-east-1c"] } }
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_data "aws_partition" { defaults = { partition = "aws" } }
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_msk_cluster" { defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:cluster/invoice-payments/uuid" } }
  mock_resource "aws_cloudfront_distribution" { defaults = { domain_name = "example.cloudfront.net" } }
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn                       = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
      domain_validation_options = [{ domain_name = "billing-origin.example.com", resource_record_name = "_validation.example.com", resource_record_type = "CNAME", resource_record_value = "_validation.acm-validations.aws" }]
    }
  }
  mock_resource "aws_acm_certificate_validation" { defaults = { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/test-role" } }
  mock_resource "aws_kms_key" { defaults = { arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_msk_configuration" { defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:configuration/test/00000000-0000-0000-0000-000000000000" } }
  mock_resource "aws_lb" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/test/0000000000000000" } }
  mock_resource "aws_lb_listener" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/test/0000000000000000/0000000000000000" } }
  mock_resource "aws_lb_target_group" { defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/test/0000000000000000" } }
  mock_resource "aws_cloudfront_function" { defaults = { arn = "arn:aws:cloudfront::123456789012:function/test" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:/test" } }
  mock_resource "aws_ecs_cluster" { defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:cluster/test" } }
  mock_resource "aws_ecs_task_definition" { defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/test:1" } }
  mock_resource "aws_db_instance" {
    defaults = { master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }] }
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
  values = {
    address            = "test.database.internal"
    master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }]
  }
}
override_resource {
  target          = aws_msk_cluster.billing
  override_during = plan
  values = {
    arn                        = "arn:aws:kafka:us-east-1:123456789012:cluster/invoice-payments/uuid"
    bootstrap_brokers_sasl_iam = "test.kafka.internal:9098"
  }
}
override_resource {
  target          = aws_cloudfront_distribution.billing
  override_during = plan
  values          = { domain_name = "example.cloudfront.net" }
}
override_resource {
  target          = aws_ecr_repository.application["identity"]
  override_during = plan
  values          = { repository_url = "123456789012.dkr.ecr.us-east-1.amazonaws.com/invoice-payments/identity" }
}
override_resource {
  target          = aws_ecr_repository.application["billing"]
  override_during = plan
  values          = { repository_url = "123456789012.dkr.ecr.us-east-1.amazonaws.com/invoice-payments/billing" }
}

variables {
  worker_database_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-worker-abcdef"
  route53_zone_id     = "ZTESTONLY"
  api_origin_hostname = "billing-origin.example.com"
  frontend_hostname   = "billing.example.com"
}

run "infrastructure_is_private_and_durable" {
  command = plan
  assert {
    condition     = !aws_db_instance.billing.publicly_accessible && aws_db_instance.billing.multi_az && aws_db_instance.billing.storage_encrypted
    error_message = "Database must be private, encrypted and MultiAZ."
  }
  assert {
    condition     = aws_db_instance.billing.backup_retention_period >= 14 && aws_db_instance.billing.deletion_protection && !aws_db_instance.billing.skip_final_snapshot
    error_message = "Database deletion and recovery protections must remain enabled."
  }
  assert {
    condition     = aws_db_instance.billing.manage_master_user_password && aws_db_instance.billing.password == null
    error_message = "Terraform must not carry a literal database master password."
  }
  assert {
    condition     = aws_msk_cluster.billing.number_of_broker_nodes == 3 && length(aws_subnet.data) == 3 && strcontains(aws_msk_configuration.billing.server_properties, "min.insync.replicas=2") && strcontains(aws_msk_configuration.billing.server_properties, "default.replication.factor=3")
    error_message = "Kafka must retain three-zone RF3/minISR2 durability defaults."
  }
  assert {
    condition     = aws_msk_cluster.billing.client_authentication[0].sasl[0].iam && !aws_msk_cluster.billing.client_authentication[0].unauthenticated && aws_msk_cluster.billing.encryption_info[0].encryption_in_transit[0].client_broker == "TLS"
    error_message = "Kafka must require IAM and TLS."
  }
  assert {
    condition     = length(aws_ecs_service.application) == 0
    error_message = "Bootstrap must not launch uninitialized applications."
  }
  assert {
    condition     = alltrue([for settings in aws_s3_bucket_public_access_block.private : settings.block_public_acls && settings.block_public_policy && settings.ignore_public_acls && settings.restrict_public_buckets])
    error_message = "S3 must deny public access."
  }
  assert {
    condition     = aws_lb_listener.https.port == 443 && aws_lb_listener.https.default_action[0].type == "fixed-response" && length(aws_lb_listener_rule.application["billing"].condition) == 2
    error_message = "ALB must use HTTPS with both path and origin-header checks."
  }
  assert {
    condition     = length(aws_route_table.data.route) == 0 && length(aws_nat_gateway.billing) == 2
    error_message = "Data subnets must be isolated, app egress must not rely on a single AZ."
  }
  assert {
    condition     = aws_cloudfront_distribution.billing.viewer_certificate[0].minimum_protocol_version == "TLSv1.2_2021" && aws_cloudfront_distribution.billing.viewer_certificate[0].ssl_support_method == "sni-only"
    error_message = "Viewer TLS must not fall back to the default certificate's TLS1.0 policy."
  }
}

run "application_has_bounded_identity_and_access" {
  command = plan
  variables {
    application_enabled = true
    image_digests = {
      identity = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      billing  = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
    database_secret_arns = {
      identity = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-identity-abcdef"
      billing  = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-billing-abcdef"
    }
    jwt_secret_arn       = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-jwt-abcdef"
    stripe_secret_arn    = "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-stripe-abcdef"
    provider_merchant_id = "b94adf59-8dba-4aa9-b3c3-6d8fc438d1fe"
  }
  assert {
    condition     = alltrue([for service in aws_ecs_service.application : !service.network_configuration[0].assign_public_ip && service.desired_count >= 2 && service.availability_zone_rebalancing == "ENABLED" && service.deployment_circuit_breaker[0].rollback])
    error_message = "Applications must remain private, replicated and rollback-capable."
  }
  assert {
    condition     = alltrue([for definition in aws_ecs_task_definition.application : jsondecode(definition.container_definitions)[0].user == "10001:10001" && jsondecode(definition.container_definitions)[0].readonlyRootFilesystem])
    error_message = "Containers must run without root and without writable root filesystems."
  }
  assert {
    condition     = length(aws_iam_role_policy.payment_kafka) == 1 && !strcontains(aws_iam_role_policy.payment_kafka[0].policy, "kafka-cluster:*") && !strcontains(aws_iam_role_policy.payment_kafka[0].policy, "CreateTopic")
    error_message = "Only billing receives scoped Kafka permissions; applications must not administer Kafka."
  }
  assert {
    condition     = alltrue([for env in jsondecode(aws_ecs_task_definition.application["billing"].container_definitions)[0].environment : !contains(["BILLING_SIMULATOR_ENABLED", "BILLING_DEMO_ENABLED", "BILLING_ERP_SIMULATOR_ENABLED"], env.name) || env.value == "false"])
    error_message = "Production must veto simulator and demo financial writers."
  }
  assert {
    condition     = alltrue([for definition in aws_ecs_task_definition.application : length(jsondecode(definition.container_definitions)[0].secrets) >= 3]) && !strcontains(aws_iam_role_policy.execution["identity"].policy, "test-stripe")
    error_message = "Secrets must be referenced, and auth must not receive provider credentials."
  }
  assert {
    condition     = alltrue(flatten([for statement in jsondecode(aws_iam_role_policy.payment_kafka[0].policy).Statement : [for resource in flatten([statement.Resource]) : endswith(resource, "/billing.events.v1.DLT") || endswith(resource, "/billing.events.v1")] if contains(statement.Action, "kafka-cluster:ReadData")]))
    error_message = "Runtime consumers may read only billing events and their DLT, never arbitrary topics."
  }
  assert {
    condition     = alltrue(flatten([for statement in jsondecode(aws_iam_role_policy.payment_kafka[0].policy).Statement : [for resource in flatten([statement.Resource]) : endswith(resource, "/billing-dlt-audit-v1") || endswith(resource, "/billing-cdc-receipts-v1")] if contains(statement.Action, "kafka-cluster:AlterGroup")]))
    error_message = "Runtime consumers must remain restricted to the exact audit and CDC receipt groups."
  }
  assert {
    condition     = anytrue([for env in jsondecode(aws_ecs_task_definition.application["billing"].container_definitions)[0].environment : env.name == "BILLING_PROVIDER_MERCHANT_ID" && env.value == var.provider_merchant_id]) && alltrue([for definition in aws_ecs_task_definition.application : anytrue([for env in jsondecode(definition.container_definitions)[0].environment : env.name == "APP_JWT_COOKIE_SECURE" && env.value == "true"]) && anytrue([for env in jsondecode(definition.container_definitions)[0].environment : env.name == "BILLING_PUBLIC_URL" && env.value == "https://${var.frontend_hostname}"])])
    error_message = "Provider ownership must be explicitly pinned and both identity/billing cookies must be secure."
  }
}

run "application_support_can_monitor_but_never_read_data_or_change_anything" {
  command = plan
  assert {
    condition = alltrue([for statement in jsondecode(aws_iam_policy.application_support_read_only.policy).Statement :
    statement.Effect == "Deny" || alltrue([for action in statement.Action : can(regex("^(logs|cloudwatch):(Describe|Get|List|Filter|StartQuery|StopQuery)", action))])])
    error_message = "Application support may only read logs, metrics and alarms."
  }
  assert {
    condition = alltrue([for blocked in ["secretsmanager:GetSecretValue", "ecs:ExecuteCommand", "rds-db:connect", "s3:GetObject", "ssm:StartSession", "iam:*"] :
    anytrue([for statement in jsondecode(aws_iam_policy.application_support_read_only.policy).Statement : statement.Effect == "Deny" && contains(statement.Action, blocked)])])
    error_message = "Secrets, database logins, container shells, stored files and access control must be explicitly denied."
  }
  assert {
    condition     = output.application_support_policies.view_only == "arn:aws:iam::aws:policy/job-function/ViewOnlyAccess"
    error_message = "Use the view-only job function, never ReadOnlyAccess, which can read stored data."
  }
}

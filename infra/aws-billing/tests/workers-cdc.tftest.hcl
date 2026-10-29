mock_provider "aws" {
  override_during = plan
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
      address = "mock.database.internal"
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }]
    }
  }
}
mock_provider "random" {}
override_resource {
  target = random_password.origin
  override_during = plan
  values = { result = "test-only-origin-header-not-a-real-secret" }
}
override_resource {
  target = aws_db_instance.billing
  override_during = plan
  values = { address = "mock.database.internal", master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:master-test", kms_key_id = "test-key", secret_status = "active" }] }
}
override_resource {
  target = aws_msk_cluster.billing
  override_during = plan
  values = { arn = "arn:aws:kafka:us-east-1:123456789012:cluster/mock/00000000-0000-0000-0000-000000000001-1", bootstrap_brokers_sasl_iam = "mock-broker.example.test:9098" }
}
variables {
  route53_zone_id = "Z123456789"
  api_origin_hostname = "origin.example.test"
  frontend_hostname = "shop.example.test"
  image_digests = { identity = "sha256:0000000000000000000000000000000000000000000000000000000000000000", billing = "sha256:0000000000000000000000000000000000000000000000000000000000000000" }
  database_secret_arns = { identity = "arn:aws:secretsmanager:us-east-1:123456789012:secret:identity-db", billing = "arn:aws:secretsmanager:us-east-1:123456789012:secret:billing-db" }
  worker_database_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:worker-db"
  jwt_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:jwt"
  stripe_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:stripe"
  provider_merchant_id = "10000000-0000-0000-0000-000000000001"
}
run "bootstrap_starts_no_app_or_connector" {
  command = plan
  assert {
    condition = length(aws_ecs_service.worker) == 0 && length(aws_mskconnect_connector.outbox) == 0
    error_message = "Bootstrap must not activate the worker or connector without explicit enablement."
  }
}
run "dedicated_worker_is_private_and_uses_the_verified_billing_image" {
  command = plan
  variables { application_enabled = true }
  assert {
    condition = length(aws_ecs_service.worker[0].load_balancer) == 0 && !aws_ecs_service.worker[0].network_configuration[0].assign_public_ip
    error_message = "The worker must have neither a load balancer nor a public address."
  }
  assert {
    condition = jsondecode(aws_ecs_task_definition.worker[0].container_definitions)[0].image == jsondecode(aws_ecs_task_definition.application["billing"].container_definitions)[0].image
    error_message = "API and worker must use the same billing image digest."
  }
  assert {
    condition = one([for e in jsondecode(aws_ecs_task_definition.application["billing"].container_definitions)[0].environment : e.value if e.name == "BILLING_ROLE"]) == "api"
    error_message = "The customer API must select its explicit API role."
  }
  assert {
    condition = one([for e in jsondecode(aws_ecs_task_definition.worker[0].container_definitions)[0].environment : e.value if e.name == "BILLING_ROLE"]) == "worker" && local.stripe_slots_per_task * local.maximum_payment_fleet_tasks <= var.stripe_fleet_concurrency_budget
    error_message = "Worker ownership and total rollout provider budget must be explicit."
  }
}
run "managed_cdc_uses_secret_references_and_disables_the_polling_relay" {
  command = plan
  variables {
    application_enabled = true
    cdc_enabled = true
    cdc_plugin_arn = "arn:aws:kafkaconnect:us-east-1:123456789012:custom-plugin/mock/00000000-0000-0000-0000-000000000001"
    cdc_database_secret_arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:replication-db"
    cdc_ca_bucket = "private-test-ca-bucket"
  }
  assert {
    condition = startswith(aws_mskconnect_connector.outbox[0].connector_configuration["database.password"], "$${secretsmanager:") && !strcontains(jsonencode(aws_mskconnect_connector.outbox[0].connector_configuration), "disposable-test-only")
    error_message = "A managed connector must use a secret reference and never inherit disposable test credentials."
  }
  assert {
    condition = aws_mskconnect_connector.outbox[0].connector_configuration["publication.autocreate.mode"] == "disabled" && aws_mskconnect_connector.outbox[0].connector_configuration["database.sslmode"] == "verify-full"
    error_message = "Publication ownership and verified PostgreSQL TLS are mandatory."
  }
  assert {
    condition = one([for e in jsondecode(aws_ecs_task_definition.worker[0].container_definitions)[0].environment : e.value if e.name == "BILLING_OUTBOX_PUBLISHER_ENABLED"]) == "false"
    error_message = "The polling relay must not run in a CDC worker deployment."
  }
}

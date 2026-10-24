output "frontend_url" {
  value       = "https://${var.frontend_hostname}"
  description = "Same-origin Angular and /api entry point. Empty asset bucket until a frontend build is uploaded."
}
output "frontend_bucket" { value = aws_s3_bucket.assets.id }
output "cloudfront_distribution_id" { value = aws_cloudfront_distribution.billing.id }
output "ecr_repositories" { value = { for service, repository in aws_ecr_repository.application : service => repository.repository_url } }
output "ecs_cluster_name" { value = aws_ecs_cluster.billing.name }
output "application_subnet_ids" { value = aws_subnet.application[*].id }
output "application_security_group_ids" { value = { for service, group in aws_security_group.application : service => group.id } }
output "database_identifier" { value = aws_db_instance.billing.identifier }
output "database_address" {
  value       = aws_db_instance.billing.address
  description = "Private DNS endpoint; no public database route."
}
output "database_master_secret_arn" {
  value       = aws_db_instance.billing.master_user_secret[0].secret_arn
  description = "ARN only, for authorized bootstrap/restore operators. Never inject this secret into an application."
}
output "kafka_cluster_arn" { value = aws_msk_cluster.billing.arn }
output "kafka_bootstrap_brokers_iam" {
  value       = aws_msk_cluster.billing.bootstrap_brokers_sasl_iam
  description = "Private TLS/IAM brokers, reachable from authorized VPC clients only."
}
output "payment_task_role_arn" { value = try(aws_iam_role.task["billing"].arn, null) }
output "log_group_names" { value = { for service, group in aws_cloudwatch_log_group.application : service => group.name } }
# Intentionally no secret values, origin authentication header or task-definition JSON outputs.
output "application_support_policies" {
  description = "Attach both to Carrier's single sign-on permission set for application support. Carrier assigns the people."
  value = {
    monitoring = aws_iam_policy.application_support_read_only.arn
    view_only  = "arn:${data.aws_partition.current.partition}:iam::aws:policy/job-function/ViewOnlyAccess"
  }
}

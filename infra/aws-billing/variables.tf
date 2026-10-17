variable "region" {
  type    = string
  default = "us-east-1"
  validation {
    condition     = contains(["us-east-1", "us-east-2", "us-west-2"], var.region)
    error_message = "This reference deployment is restricted to a US commercial region with three AZs."
  }
}
variable "name" {
  type    = string
  default = "invoice-payments"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,18}[a-z0-9]$", var.name))
    error_message = "Use 4-20 lowercase letters, digits or hyphens, starting with a letter and ending with a letter/digit."
  }
}
variable "environment" {
  type    = string
  default = "reference"
}
variable "vpc_cidr" {
  type    = string
  default = "10.72.0.0/16"
  validation {
    condition     = can(cidrsubnet(var.vpc_cidr, 8, 22))
    error_message = "Provide a valid IPv4 CIDR with room for /8-bit subnet subdivision."
  }
}
variable "route53_zone_id" {
  description = "Existing public hosted zone in this account, used to validate the regional API origin certificate."
  type        = string
}
variable "api_origin_hostname" {
  description = "Unused DNS name within route53_zone_id, e.g. billing-origin.example.com. Browser clients use CloudFront, not this origin."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.api_origin_hostname))
    error_message = "Supply a lowercase public DNS hostname, without protocol/path."
  }
}
variable "frontend_hostname" {
  description = "Unused public hostname in the same Route53 zone, e.g. billing.example.com. A custom certificate enforces viewer TLS1.2+."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.frontend_hostname)) && var.frontend_hostname != var.api_origin_hostname
    error_message = "Supply a frontend DNS hostname different from the API origin."
  }
}
variable "application_enabled" {
  description = "Enable only after images, separate database users/schemas, secrets and Kafka topics are initialized. Infrastructure bootstrap defaults to no running app tasks."
  type        = bool
  default     = false
}
variable "image_digests" {
  description = "Immutable sha256 digests pushed to this module's identity/billing ECR repositories."
  type        = map(string)
  default     = {}
  validation {
    condition = (!var.application_enabled || alltrue([
      for service in ["identity", "billing"] : can(regex("^sha256:[a-f0-9]{64}$", lookup(var.image_digests, service, "")))
    ]))
    error_message = "Enabling applications requires real sha256 image digests for identity and billing."
  }
}
variable "database_secret_arns" {
  description = "Existing SecretsManager secrets keyed identity/billing, each JSON with username and password. Never pass the RDS master secret to an application."
  type        = map(string)
  default     = {}
  validation {
    condition = !var.application_enabled || alltrue([
      for service in ["identity", "billing"] : can(regex("^arn:aws:secretsmanager:", lookup(var.database_secret_arns, service, "")))
    ])
    error_message = "Enabling applications requires two SecretsManager credential ARNs."
  }
}
variable "jwt_secret_arn" {
  description = "Existing SecretsManager secret whose entire value is the shared high-entropy JWT signing key."
  type        = string
  default     = ""
  validation {
    condition     = !var.application_enabled || can(regex("^arn:aws:secretsmanager:", var.jwt_secret_arn))
    error_message = "Enabling applications requires a SecretsManager JWT secret ARN."
  }
}
variable "stripe_secret_arn" {
  description = "Existing JSON secret containing secret_key and webhook_secret. Reference adapter uses Stripe TEST mode until its live eligibility gates are met."
  type        = string
  default     = ""
  validation {
    condition     = !var.application_enabled || can(regex("^arn:aws:secretsmanager:", var.stripe_secret_arn))
    error_message = "Enabling applications requires a SecretsManager Stripe secret ARN."
  }
}
variable "provider_merchant_id" {
  description = "Actual billing.merchants UUID bound to the single configured provider account. Required before enabling applications; never infer merchant ownership from caller headers."
  type        = string
  default     = ""
  validation {
    condition     = !var.application_enabled || can(regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$", var.provider_merchant_id))
    error_message = "Enabling applications requires the real provisioned merchant UUID for the configured provider account."
  }
}
variable "secret_kms_key_arns" {
  description = "Customer-managed KMS keys used by the referenced application secrets, if any. Leave empty for AWS-managed SecretsManager keys."
  type        = set(string)
  default     = []
}
variable "database_instance_class" {
  type    = string
  default = "db.m7g.large"
}
variable "database_engine_version" {
  description = "PostgreSQL 17 release available in the deployment region. Minor auto-upgrades are enabled."
  type        = string
  default     = "17"
  validation {
    condition     = can(regex("^17(\\.[0-9]+)?$", var.database_engine_version))
    error_message = "The parameter group is PostgreSQL17; use a compatible version."
  }
}
variable "kafka_version" {
  type    = string
  default = "3.9.x"
}
variable "kafka_broker_instance_type" {
  type    = string
  default = "kafka.m5.large"
}
variable "maximum_tasks_per_service" {
  type    = number
  default = 6
  validation {
    condition     = var.maximum_tasks_per_service >= 2 && var.maximum_tasks_per_service <= 12 && floor(var.maximum_tasks_per_service) == var.maximum_tasks_per_service
    error_message = "Choose 2-12 tasks; re-budget database connections before increasing this bound."
  }
}
variable "alarm_action_arns" {
  description = "Existing SNS topic ARNs for operational alarms; empty means alarms are visible in CloudWatch but do not notify anyone."
  type        = list(string)
  default     = []
}

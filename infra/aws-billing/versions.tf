terraform {
  required_version = ">= 1.13.5, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.27.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.7.2"
    }
  }
  # Supply a pre-existing, private, encrypted, versioned state bucket at init.
  # No credentials or bucket names are committed here.
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Application = var.name
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 3)
  services = {
    identity = { port = 8081, paths = ["/api/v1/auth", "/api/v1/auth/*"], priority = 10 }
    billing  = { port = 8084, paths = ["/api/v1/billing", "/api/v1/billing/*"], priority = 20 }
  }
  enabled_services = var.application_enabled ? local.services : {}
}

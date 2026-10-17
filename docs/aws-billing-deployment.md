# AWS reference infrastructure

This repository owns the complete Terraform root under `infra/aws-billing`. It describes infrastructure; no AWS resources have been deployed or tested by this extraction.

The reference uses CloudFront with a private S3 frontend origin and an HTTPS ALB API origin. The ALB routes authentication directly to identity and invoice operations directly to billing. The Java gateway is used by the local Compose stack; AWS routing does not require it. Each Java service has its own ECR image, Fargate tasks, database credentials and schema. Runtime images are nonroot and supply a writable `/tmp` volume for the read-only Fargate task filesystem.

The database configuration is private PostgreSQL 17 RDS MultiAZ with encryption, verified client TLS and retained backups. Kafka uses three MSK brokers and IAM/TLS. Application credentials come from supplied Secrets Manager references. CloudWatch logs and alarms are declared; notification destinations must be supplied and tested separately. Local Prometheus/Grafana provisioning is not an AWS managed monitoring deployment.

Run syntax and mocked contract checks without AWS credentials:

```powershell
cd infra/aws-billing
terraform init -backend=false
terraform fmt -check -recursive
terraform validate
terraform test
```

Providers and their checksums are committed in `.terraform.lock.hcl`. Test ARNs, account IDs and hostnames are fixtures. The backup helper tests shadow the AWS CLI and make no AWS requests. The SPA tests exercise the committed CloudFront function locally.

For a separately authorized deployment, create private encrypted versioned state storage first, then configure the S3 backend and ignored `local.auto.tfvars`. Keep `application_enabled=false` while preparing infrastructure. Initialize separate `identity_app` and `billing_app` roles and their respective schemas in the new `invoice_payments` database. Provision the two Kafka topics `billing.events.v1` and `billing.events.v1.DLT` with replication factor 3, minimum ISR 2 and clean leader election. Runtime IAM roles deliberately do not create topics.

Build `Dockerfile.java` targets `identity` and `billing` for the corresponding ECR repositories. Scan, push and record immutable digests under Terraform keys `identity` and `billing`. Populate separate database secret references and the shared signing-key reference; provision users, merchant and customer membership through a reviewed onboarding process. Local demo seed flags must remain disabled. The implemented Stripe adapter accepts test credentials only; it does not become a live payment adapter under the production profile. A real ERP consumer is not included.

Only enable applications after database, Kafka, identities, provider ownership and secrets are ready. Review an actual account-specific plan before applying anything. Build and upload the Angular assets to the frontend bucket and validate real edge routing, cookies, private readiness, IAM access, alert delivery and recovery. None of those cloud acceptance checks is established by local mock tests.

`scripts/backup-billing.ps1` can request an RDS snapshot under explicit account/database guards. It does not restore or delete databases. Local logical restoration is exercised separately by `scripts/verify-billing-stack.ps1`; local results do not establish AWS PITR, cross-region recovery, production latency or capacity.

# Invoice Payments Platform

A standalone reference application for a fictional US B2B supplier: customers pay invoices, and finance operators review refunds, reconcile bank evidence and investigate recovery. This is a personal engineering project, not a Carrier Global or bank production system.

Java 17 / Spring Boot, Angular, PostgreSQL, Kafka, Prometheus and Grafana. AWS infrastructure is described with Terraform. Local PSP, bank and ERP behavior is simulated; no real money moves.

## Run locally

Install Docker Desktop with Compose and PowerShell. From this repository:

```powershell
./scripts/start-billing.ps1
```

This builds all services, generates fresh ignored local secrets, and starts PostgreSQL, three Kafka brokers, scheduled recovery workers and monitoring. Open http://localhost:4400; Grafana is http://localhost:3200. The Grafana password is in the ignored `.env.billing.local` file. Demo accounts: `demo/demo123`, `other/other123`, `admin/admin123`, `reviewer/reviewer123`. These fixtures are explicitly enabled only for the local profile.

For repeatable failure, restart, restoration and dashboard checks:

```powershell
./scripts/start-billing.ps1 -ValidateOnly
./scripts/verify-billing-stack.ps1
```

For a faster integration smoke check, use `./scripts/verify-billing-stack.ps1 -Quick`; it checks login, a simulated capture delivered through real Kafka to the ERP double, and live Grafana queries. Quick mode explicitly skips outage, restart, alert-firing and backup-restoration drills.

The drill uses its own Compose project and volumes: app http://localhost:4500, Grafana http://localhost:3300, Prometheus http://localhost:9390. It deliberately stops only its own labeled containers. Reports and logical backups stay in ignored `.tmp-notes/billing-stack/`. Never point these scripts at a production database.

## Build and test

```powershell
mvn package -DskipTests
cd frontend
npm ci
npm test -- --watch=false
npm run build
```

For a complete Java/database/Kafka/synthetic-network suite with no host Java or PostgreSQL setup:

```powershell
./scripts/verify-container-tests.ps1
```

This builds a dedicated verification image, creates two disposable PostgreSQL databases inside Docker, and collects JUnit and synthetic lab reports under ignored `.tmp-notes/container-tests/`. It publishes no database ports and removes only its own test containers. Its fixed test password is a public fixture for these isolated, ephemeral databases.

`scripts/verify-billing.ps1` runs the curated service and Angular checks. Add `-Online` for a first Maven dependency download. Database checks require `-WithDatabase` and the exact disposable PostgreSQL database `invoice_payments_test` on port 15432, role `billing_test`, with `BILLING_TEST_JDBC_URL`, `BILLING_TEST_DB_USER` and `BILLING_TEST_DB_PASSWORD` set. They reset test tables. The separate synthetic network/load lab uses `invoice_payments_simulation_test` and the corresponding `BILLING_LAB_*` variables; run `scripts/run-billing-lab.ps1 -Online`. These are development checks, not production capacity measurements.

Run `scripts/check-repository.ps1` to verify repository boundaries and migration hashes. Add `-CheckArtifacts` after packaging to inspect the three executable JARs for legacy code and dependencies.

## Source layout

| Directory | Responsibility |
| --- | --- |
| `billing-service` | Invoice payments, provider adapter, ledger, refunds, reconciliation, outbox/inbox, Kafka and recovery |
| `identity-service` | Users, login, secure cookies and local demo fixtures |
| `api-gateway` | Authenticated routing to identity and billing |
| `frontend` | Customer invoices and finance operations only |
| `monitoring` | Prometheus rules, Grafana dashboard and datasource provisioning |
| `infra/aws-billing` | AWS reference deployment and mocked Terraform contract tests |
| `scripts` | Local startup, tests, backup and failure drills |

See [payment publication order and alert policy](docs/payment-ordering-and-alerts.md), [repository separation](docs/repository-separation.md) for the extraction boundary and [verification](docs/verification.md) for evidence and limits. Do not describe simulated results as live bank, Stripe, ERP or production validation.

For a step-by-step explanation of payment submission, crash recovery and transaction
propagation, read the [payment recovery walkthrough](docs/payment-recovery-walkthrough.md).

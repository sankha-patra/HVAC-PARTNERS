# Operational alerts on AWS

Every failure that needs a person reaches one encrypted SNS topic and is emailed to the subscribed group mailboxes. Three sources feed it.

## 1. ALERT log lines from the billing service

`OperationalAlert.raise(...)` writes one line per alert, in a fixed shape that CloudWatch metric filters match:

```
ALERT code=PAYMENT_OUTCOME_UNKNOWN severity=WARNING intentId=8643c96f-dc6e-4576-8576-ebb448f9a7c7 reason="Payment provider did not confirm the request (ProviderUncertainException); kept as UNKNOWN for recovery"
```

Inside a transaction the line is written only after commit, so a rolled-back change never alerts. Fields are identifiers and short reason text only; never provider messages, request bodies or personal data.

| Code | Severity | Raised by | Meaning |
| --- | --- | --- | --- |
| `PAYMENT_OUTCOME_UNKNOWN` | WARNING | `BillingService` submit | Provider gave no definite answer; payment kept `UNKNOWN` for recovery |
| `REFUND_OUTCOME_UNKNOWN` | WARNING | `BillingRefundService` | Same for a refund |
| `DATABASE_UNAVAILABLE` | CRITICAL | `BillingAdvice`, `CommerceAdvice`, `BillingWorkers`, `BillingRecovery` | The database could not be reached (connection failure, not a query error) |
| `FINANCE_CASE_OPENED` | WARNING | `BillingStore.caseFor` / `escalate` | A finance case was opened or reopened; `caseReason` names it |
| `RECOVERY_NEEDS_REVIEW` | WARNING | `RecoveryDispatchStore` | A Step Functions recovery ended without a money result |
| `OUTBOX_NOT_DRAINING` | WARNING | `OperationalWatch` | Oldest committed event older than 60 s (Kafka or Debezium down) |
| `RECOVERY_WORK_NOT_CLAIMED` | WARNING | `OperationalWatch` | Due payment/refund recovery unclaimed for over 180 s |
| `CDC_SLOT_INACTIVE` | CRITICAL | `OperationalWatch` | CDC expected but slot `billing_outbox_cdc` not active |
| `MONEY_OPERATION_STUCK` | WARNING | `OperationalWatch` | Payments/refunds out of automatic recovery for a technical reason |

`OperationalWatch` reads the existing `BillingMetrics` gauges every minute (thresholds match `monitoring/prometheus/billing-rules.yml`), raises when a condition starts, repeats every 15 minutes, and logs `RESOLVED alert code=<CODE>` when it clears. Enable it in exactly one deployment with `BILLING_ALERTS_WATCH_ENABLED=true`; on AWS the payment worker task sets it (`infra/aws-billing/workers.tf`).

## 2. AWS events and database log

`infra/aws-billing/alerts.tf`:

- Metric filter per code on `/<name>/billing` and `/<name>/payment-worker`, alarm per code (namespace `InvoicePayments/Alerts`). Each alarm description says what broke, why, what to check and how to find the line in Logs Insights.
- RDS PostgreSQL log filter for blocked deletes/rewrites (`cannot be deleted`, `is immutable`, `update or delete on table`) → `protected-row-change-blocked`.
- EventBridge → SNS with AWS's reason in the email: ECS task stopped (`EssentialContainerExited`, `TaskFailedToStart`), RDS availability/failover/failure/recovery/low-storage events, and, when `recovery_state_machine_arns` is set, failed/timed-out/aborted recovery executions. `recovery_function_names` adds a Lambda `Errors` alarm.

## 3. Infrastructure alarms

The alarms in `observability.tf` and `cdc-monitoring.tf` now notify the same topic (`local.alarm_actions`), plus any extra ARNs in `alarm_action_arns`.

## Turning it on

Set `alert_email_addresses` (group mailboxes; each must confirm the AWS subscription email), and optionally `recovery_state_machine_arns` / `recovery_function_names` for the separately deployed recovery stack. Tests: `terraform test` in `infra/aws-billing` (`tests/alerts.tftest.hcl`), `OperationalAlertTest` in the billing service.

## Verified so far

On 30 Oct 2026 the ALERT lines were produced by the running service for a provider outage, a database outage, a Kafka outage (raised after 62 s, resolved after a connector restart) and the stuck recovery work; the blocked deletes appeared in the PostgreSQL log. Evidence: `Documents/Codex/2026-10-26/pl/work/razorpay-failure-e2e/evidence/alert-lines.txt`. The SNS topic, alarms and EventBridge rules pass `terraform test` but have not been deployed. In that lab the replication slot is named `rzp_failures_outbox`, so `CDC_SLOT_INACTIVE` fired falsely there; production uses `billing_outbox_cdc`.

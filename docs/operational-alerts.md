# Operational alerts

Every failure that needs a person reaches one encrypted SNS topic and is emailed to the subscribed mailboxes, with what broke and why. Two deployments share the same application code:

- **Full AWS** (`infra/aws-billing/alerts.tf`): ECS, RDS, MSK. ALERT lines are matched by CloudWatch metric filters on the billing, worker and recovery-Lambda log groups; ECS task stops and RDS events come from EventBridge.
- **Hybrid lab** (`infra/aws-hybrid-alerts`, `infra/hybrid-lab`): Java, PostgreSQL, Kafka and Debezium on the laptop; Step Functions, the recovery Lambda, SNS, CloudWatch and EventBridge in AWS. Fluent Bit ships the laptop's billing and PostgreSQL logs and container stop events to CloudWatch Logs.

## 1. ALERT lines from the billing service

`OperationalAlert.raise(...)` writes one line on logger `operational-alerts`:

```
ALERT code=PAYMENT_OUTCOME_UNKNOWN severity=WARNING intentId=8643c96f-dc6e-4576-8576-ebb448f9a7c7 reason="Payment provider did not confirm the request (ProviderUncertainException); kept as UNKNOWN with its saved request for recovery"
```

Inside a transaction the line is written after commit; a rolled-back change never alerts. Fields are identifiers and short reason text only. Outage alerts raised on every request use `raiseAtMostEvery` (one line per source per minute).

**One-off events** (emailed one by one with the exact line):

| Code | Raised by | Meaning |
| --- | --- | --- |
| `PAYMENT_OUTCOME_UNKNOWN` / `REFUND_OUTCOME_UNKNOWN` | `BillingService` / `BillingRefundService` after the save | Provider gave no definite answer (kept UNKNOWN), or its answer could not be saved (stays SUBMITTING). Recovery asks again |
| `DATABASE_UNAVAILABLE` | HTTP advice, workers, scheduler error handler, recovery, the watch's `SELECT 1` probe (`source=`) | PostgreSQL unreachable; requests fail safely. In the hybrid stack this is an alarm (one email when it starts, one OK), not one email per line |
| `FINANCE_CASE_OPENED` | `BillingStore.caseFor/escalate`, settlement checks | A person must decide; `caseReason` says which |
| `RECOVERY_NEEDS_REVIEW` | `RecoveryDispatchStore` | Last recovery attempt ended without a money result; `cause` carries the workflow's error |
| `RECOVERY_DISPATCH_FAILING` | `RecoveryDispatcher` (3 consecutive AWS failures, repeats every 15 min) | Dispatcher cannot call Step Functions; `errorCode` says why |
| `PROVIDER_PAUSED` | `BillingService` admission (every 5 min at most) | New payments refused before anything is sent; nothing charged |

**Conditions** (written by `OperationalWatch` every check while they hold; the alarm emails once on start and once after five clear minutes):

| Code | Holds when |
| --- | --- |
| `OUTBOX_NOT_DRAINING` | Oldest committed event older than 60 s |
| `RECOVERY_WORK_NOT_CLAIMED` | Due recovery owned by no workflow, claim or finance case for over 180 s |
| `CDC_SLOT_INACTIVE` / `DEBEZIUM_STALLED` | CDC mode and the slot (`billing.cdc.slot-name`) is inactive / the Debezium heartbeat is older than 60 s |
| `DEBEZIUM_TASK_NOT_RUNNING` | Connect REST (`billing.alerts.connect-url`) reports the connector or a task not RUNNING (optional auto-restart) |
| `KAFKA_UNREACHABLE` | Broker not reachable twice in a row |
| `CDC_WAL_RETAINED` | Slot holds over 512 MB of WAL |
| `MONEY_OPERATION_STUCK` | A payment/refund uncertain over 15 min with no open finance case |
| `DEAD_LETTER_OPEN` | A Kafka event was parked (lasting failure; database outages are waited out for 6 h first) |
| `NOTIFICATION_DELIVERY_FAILING` | Emails retrying, given up, or dropped for lack of a recipient |
| `PROVIDER_CREDENTIALS_REJECTED` | Provider rejected the API key |
| `RECOVERY_CAMPAIGN_EXPIRING` | Campaign ends within a day or used 80% of its starts |

A value that cannot be read (database down) keeps and repeats its last state, so no false OK is sent. The watch also writes `HEARTBEAT service=billing db=up|down` every check. Enable it in exactly one deployment with `BILLING_ALERTS_WATCH_ENABLED=true`.

## 2. Hybrid stack (`infra/aws-hybrid-alerts`)

- KMS-encrypted SNS topic `payments-hybrid-alerts`, email subscriptions.
- Log groups `/payments-hybrid/{billing,postgres,docker-events,shipper}` (30 days).
- **Forwarder Lambda**: subscription filters on billing, the recovery Lambda log group, PostgreSQL (blocked delete/truncate, PANIC, shutdown, disk full) and container die/oom events. Each batch becomes one email with the lines and what to do.
- **Alarms**: one per condition code (plus `DATABASE_UNAVAILABLE`); heartbeat alarms with composites `payments-hybrid-billing-app-down` (app heartbeat missing while the shipper is alive: crash or hang; suppressed for 3 minutes when the shipper heartbeat also stops) and `payments-hybrid-laptop-telemetry-lost` (laptop asleep, Docker stopped, key revoked); recovery Lambda errors and throttles; forwarder errors.
- **EventBridge**: a recovery execution aborted by a person. Failed and timed-out executions are routine (a later attempt follows); the last attempt raises `RECOVERY_NEEDS_REVIEW` with the workflow's error. Delivery failures of the rule are an alarm.
- **IAM users** with narrow inline policies: `payments-hybrid-telemetry` (write log events to these groups only) and `payments-hybrid-dispatcher` (start and describe recovery executions only). Their access keys are created outside Terraform and kept in the lab's `private/` folder.

Laptop side (`infra/hybrid-lab`): `fluent-bit.conf` (forward input for the fluentd log driver, `docker_events`, a heartbeat, disk buffering) and `telemetry.yml` (compose overlay: Fluent Bit service, fluentd logging for billing and PostgreSQL, PostgreSQL `log_line_prefix` with user/database/application/client, `log_statement=ddl`, `max_slot_wal_keep_size`).

## 3. Scenario coverage

| Scenario | What emails |
| --- | --- |
| 1 Provider down | `PAYMENT_OUTCOME_UNKNOWN` (exact line); later `RECOVERY_NEEDS_REVIEW` + `FINANCE_CASE_OPENED` if all attempts fail; `PROVIDER_PAUSED` if the breaker opens |
| 2 Delete/truncate a saved order or attempt | PostgreSQL `cannot be deleted` / `cannot be truncated` line with user, application and client |
| 3 PostgreSQL down | `DATABASE_UNAVAILABLE` (watch probe, with or without traffic), `POSTGRES_FAILURE` from the database log, container stop event |
| 4 Spring Boot crash | `billing-app-down` composite (heartbeat), container stop event with exit code |
| 5 Kafka down after payment | `OUTBOX_NOT_DRAINING`, `KAFKA_UNREACHABLE`, kafka container stop event |
| 6 Kafka down during the Debezium write | `OUTBOX_NOT_DRAINING`, `DEBEZIUM_STALLED` / `DEBEZIUM_TASK_NOT_RUNNING`, `CDC_SLOT_INACTIVE` |

## Tests

`OperationalAlertTest`, `BillingAwsRecoveryDatabaseTest`, `terraform test` in `infra/aws-billing` and `infra/aws-hybrid-alerts`, `python -I -m unittest discover -s infra/aws-hybrid-alerts/forwarder -p 'test_*.py'`.

# Payment worker and Debezium relay

The same billing image has `api`, `worker`, and compatibility `combined` roles. The API accepts customer/staff requests and saves signed Stripe messages. The worker claims due payments/refunds, processes saved inbox messages, expires eligible never-submitted payments, and records observed Kafka delivery. Both use the same injected transactional command beans and financial tables.

Debezium has a separate responsibility: publish committed event-stream inserts from PostgreSQL WAL to Kafka. It cannot resolve an uncertain Stripe call or wake up merely because a retry timestamp has arrived.

## Local configuration

From the repository root, `scripts/start-billing.ps1 -ValidateOnly -DedicatedWorkers` checks the split API/worker Compose configuration. Add `-Cdc` to include the connector and local CDC bootstrap. Omitting `-ValidateOnly` starts that local stack; do not run it against an existing drill without a deliberate maintenance decision.

`docker-compose.workers.yml` keeps the worker private and uses the billing image for both roles. `docker-compose.cdc.yml` enables logical WAL in the local database and gates the worker/gateway on successful CDC bootstrap. The local bootstrap is restricted to its known Compose database and connector names. Existing secret files remain ignored and their values are never printed.

## Financial commit and delivery

1. Preparation saves the exact provider request, stable key and ownership claim in a short proxied transaction.
2. Stripe is called after commit, with no database transaction or pooled connection held across the network wait.
3. Verified financial effects and their canonical outbox event commit together. Under CDC, V23 inserts an immutable matching `billing.outbox_events` row in that same transaction and sets the canonical state to `CDC_PENDING`.
4. Debezium sends the stream event to `billing.events.v1`, keyed by the payment aggregate. A receipt consumer validates transport ID, key and canonical payload before marking the audit row `PUBLISHED`.
5. Each business consumer commits its own duplicate guard together with its own effect. `PUBLISHED` establishes observation in Kafka, not ERP posting, fulfilment, or bank settlement.

## Controlled relay switch

V23 defaults to `POLL`. AWS `cdc_enabled` defaults to false. No historical canonical rows are silently copied into the CDC stream.

Prepare the replication login/publication, compatible pinned plugin, topics, receipt consumer and monitoring first. Drain existing `NEW`, `IN_FLIGHT`, `CDC_PENDING`, and unresolved dead-letter work. Use `scripts/cdc/switch-relay.sql` with the expected mode, next mode and current generation. The database control-row lock serializes the switch with event insertion; generation rejects stale operator intent. Handover must use a READ COMMITTED physical transaction so the backlog check sees writers that committed while the lock was awaited. Stronger caller isolation is rejected by the application command.

The reverse switch obeys the same drain rule. Changing only an application environment variable is not a relay handover. Do not run both relays for the same backlog.

## Failure, replay and operations

- API failure does not erase saved work. Worker failure leaves durable claims/requests for recovery after lease expiry. Neither permits a fresh charge for an unknown operation.
- During connector/Kafka failure, payment remains committed. Monitor CDC pending count/oldest age, worker due-work age, replication-slot activity and retained WAL. A WAL retention cap can invalidate an unread slot; it does not make the backlog safe.
- Dead-letter replay creates a new transport ID while preserving the original business event identity. Receipt observation completes replay audit; consumer deduplication prevents a second accounting effect.
- A lost slot/offset requires an explicit reviewed full resnapshot of retained stream rows. Preserve per-aggregate order, expect duplicates, and verify downstream effects independently. `scripts/cdc/Restore-Connector.ps1` is for local/self-managed Kafka Connect only, requires an explicit review flag and does not drop replication slots itself. It does not implement AWS MSK Connect recovery. Managed pause/resume preserves offsets; it is not a source-offset reset.
- Retention is explicit maintenance. V23 refuses deletion of undelivered stream/canonical events and requires confirmed delivery, an eight-day age floor and the maintenance setting. Check Kafka/consumer lag, backups and rebuild requirements before cleanup.
- Budget database pools and Stripe permits across maximum API/worker tasks, including rollout copies. Breaker state and permits remain per process; configured fleet limits are not measured throughput or a distributed rate limiter.
- Worker shutdown stops new claims, drains bounded active work and releases unstarted queued claims. A timed-out external call remains uncertain and recoverable.

## Deployment boundary

AWS files define the private worker and optional MSK Connect resources; they are not a cloud deployment. Provision separate least-privilege worker and replication logins, actual secrets, CA bundle/plugin and alert destinations before enabling applications/CDC. The exact custom plugin and AWS configuration providers need a smoke test under the intended managed runtime. Real RDS failover, managed slot/offset recovery, cloud restore, live Stripe integration and capacity targets require separate evidence. Use AWS-supported connector APIs and a reviewed managed recovery runbook; the self-managed REST helper is not that runbook.

Carrier Entra, product/order/stock integration, Redis catalogue cache and real ERP/fulfilment remain proposed integrations. The current ERP consumer is a local simulator. A payment-captured event alone does not prove safe order fulfilment when late/unapplied cash exists.

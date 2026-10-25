# Payment publication order and stuck payment alert

## Transaction boundaries and ordering

V9 assigns an insertion sequence under a transaction-scoped lock per payment.
Claims select only the oldest active row for each payment, across publisher
threads and app instances. A leased head or a head waiting for retry blocks its
successors; other payments continue.

Publication has three steps:

1. A short database transaction claims the head with a random lease token and a
   30-second deadline. Each claim increments the attempt counter.
2. An autocommit lookup checks ownership and releases its connection. The Kafka
   send and bounded acknowledgement wait run **outside any database transaction**.
   The publisher rejects calls made inside an existing transaction.
3. A short transaction marks the row published only if its token, active state
   and unexpired lease still match. A stale success or failure cannot change a
   new owner's row.

No database connection or row lock is retained while awaiting Kafka. The regression
suite holds eight broker sends pending with a single-connection Hikari pool and
checks that no transaction is active, no connection is borrowed and the outbox
rows can still be locked independently.

**The lease fences database updates, not the broker send.** A worker paused after
its ownership check can send late, and an acknowledgement lost before the database
commit can cause a retry. Delivery is at least once; consumers deduplicate using
the original event ID. Do not claim absolute delivery order, global ordering or
exactly-once transport. Normal publication prevents successors overtaking a pending
head; quarantine and deliberate replay are explicit exceptions. Stateful consumers
must also handle old events safely; deduplication alone does not order distinct
state changes. Changing the Kafka partition count requires a controlled migration.
Historic rows are backfilled by creation time and ID; V9 cannot reconstruct an
unknown historical insertion order.

## Bounded retries and durable quarantine

V10 adds the terminal outbox state `DEAD_LETTER` and links publisher failures into
the existing `billing.dead_letters` table. The policy is:

- A broker outage (a Kafka retriable error, or a send that times out waiting) retries
  after 10 seconds and gives its claim back, so it never uses the budget. The event is
  not at fault: it waits, its successors wait behind it, and `BillingOutboxNotDraining`
  tells the app team. It does not become a dead letter, however long Kafka is down.
- Any other failure retries after 10 seconds, up to **50 claims**. Counting claims
  also bounds retries when workers crash or lose leases before recording a result.
- Record-too-large, serialization and invalid-topic failures quarantine immediately.
- Quarantine stores the original payload, payment key, authoritative merchant,
  source outbox ID and exception class in a short transaction, then changes the
  outbox state. Broker partition/offset are null because this record originates
  before confirmed Kafka publication. No Kafka send is needed to create the audit.
- If the audit write fails, the transaction rolls back; the original event stays
  pending and its successors remain blocked. Events without merchant ownership
  are retained for investigation rather than discarded without an audit.
- Only `NEW` and `IN_FLIGHT` rows participate in ordered claims and pending/age
  metrics. A quarantined row releases its successors and remains visible through
  the dead-letter queue and existing `BillingDeadLetterNeedsReview` alert.
- Assigned operations staff can replay a validated original through the existing
  authenticated replay endpoint after correcting its cause. Replays append a new
  queue entry with the original event ID/payload, so they can arrive after newer
  events. A failed replay reopens the same audit entry instead of creating cases
  indefinitely. Irreparably malformed data needs correction by its source owner.

The budget is a work limit for failures the app cannot explain, not an outage timer.
An expired claim (a worker that crashed or stalled) still counts, so a broker outage that
also crashes workers repeatedly can still exhaust it.
Money state and ledger entries are not rewritten by quarantine or replay.

## Producer configuration

`BillingKafkaConfiguration` already enabled idempotence and `acks=all` in every
billing profile, independently of production YAML. It now explicitly enforces a
positive retry budget and at most five requests in flight as well. Parameterized
factory tests cover default, billing, local-observability and production settings,
including conflicting input configuration. Idempotence applies to a producer
session; it does not deduplicate application retries across owners or manual replay.

## Who acts on an alert

Every alert has two routing labels. `responder: app-team` means the application team (the
service provider) sees it first, triages it and fixes its own defects. `decision` names the
client (Carrier) team that decides, or `none` for technical alerts: `carrier-finance`,
`carrier-treasury` or `carrier-business`. The application team never makes those decisions;
it hands the case over with evidence. `severity: critical` pages the app team's on-call,
`warning` notifies its channel without paging, and `info` goes only to a queue.
`monitoring/alertmanager/alertmanager.example.yml` is an unmounted template showing this
routing; it delivers nothing until real receivers are configured on a private network.
Its Carrier receivers send an email notice (counts only, no payment data) to placeholder
group mailboxes on the reserved `.invalid` domain. A release waiting for its second
approval goes to the approvers' mailbox, not to finance. The app team then sends the case
details by hand and records the handoff as a case note: see
[Carrier handoff runbook](carrier-handoff-runbook.md).

## Stuck payment alerts

Ages are measured from payment creation, so recovery retries cannot reset them. The combined
`billing_payments_stuck` gauge is kept for dashboards; each alert reads its own gauge:

| Alert | Gauge and state | Age | Decision |
| --- | --- | --- | --- |
| `BillingPaymentStuck` | `billing_payments_stuck_technical`: UNKNOWN, SUBMITTING | over 15 minutes | none (app team) |
| `BillingPaymentActionOverdue` (info) | `billing_payments_action_overdue`: REQUIRES_ACTION | over 24 hours | carrier-business |
| `BillingPaymentProcessingOverdue` | `billing_payments_processing_overdue`: PROCESSING | over 7 days | carrier-treasury |

A payment still waiting for the payer after 24 hours usually means recovery missed the
outcome, because hosted Checkout sessions expire after 24 hours by default. Processing age
alone is never a failure: never fail, reverse or replace a payment without verified provider
evidence. These are local thresholds, not a measured Stripe SLA. Prometheus firing alone is
not evidence of an external notification receiver.

## Stuck refund, webhook and release alerts

- `BillingRefundStuck` reads `billing_refunds_stuck_technical`: approved refunds not sent, or
  sent with no answer, after 15 minutes from approval or submission (app team).
- `BillingRefundProcessingOverdue` reads `billing_refunds_processing_overdue`: provider-pending
  for over seven days (carrier-treasury). A pending or failed refund never triggers another one.
- `BillingWebhookRetrying` fires when a saved provider event has kept failing for a technical
  reason for 10 minutes (app team). Temporary failures retry with backoff capped at five
  minutes and never become finance work, however often they repeat.
- `BillingWebhookNeedsMatching` fires only for verified evidence that disagrees with our
  records (carrier-finance).
- `BillingReleaseAwaitingApproval` fires when a release has waited 30 minutes for a second
  Carrier approver (carrier-finance).

## Provider alerts

They read `billing_provider_requests_total{outcome,category,operation}` and the
`billing_provider_request_seconds` histogram, plus two gauges set by the Stripe adapter.
All go to the app team. Card declines happen inside hosted Checkout and never reach them.

- `BillingProviderCredentialsRejected` (critical): Stripe refused our key or permissions.
  The cause is usually ours (a revoked, rotated or wrong key).
- `BillingProviderRequestsInvalid`: Stripe refused a request as invalid.
- `BillingProviderThrottled`: requests were rate limited. Check our own load and contention
  on one Stripe object before blaming Stripe.
- `BillingProviderOutcomeUncertain`: over 20% of calls ended without a clear answer, with at
  least 10 calls in five minutes.
- `BillingProviderLatencyHigh`: the 95th percentile is above 5 seconds.

New payments are refused before anything is saved, with HTTP 503 `PROVIDER_BUSY` and `Retry-After: 60`, when all
8 slots for new Stripe payments on a server are in use, or after 5 Stripe requests in a row timed out, failed on
Stripe's side or were throttled. That pause lasts 30 seconds, longer while failures continue; then one trial
payment decides. The payer is told nothing was charged, which is certain because nothing was saved or sent.
Payments already on their way are never refused. The pause is logged; `BillingProviderOutcomeUncertain` and
`BillingProviderThrottled` already alert on the failures that cause it.

## Captures and consumer lag

- `billing_payments_captured_total{method}` counts payments with a capture journal. Journals
  are immutable and unique per payment, so restarts and repeated webhooks cannot change it.
  Every replica reports the same database total: use `max`, never `sum`, across replicas.
  A failed database read repeats the last count, so the counter never goes backwards.
- Both billing consumer factories report Kafka client metrics, including
  `kafka_consumer_fetch_manager_records_lag_max`. Spring Boot attaches these only to the
  factory it builds itself, and billing builds its own.
- `BillingKafkaConsumerLagging` fires when one consumer stays more than 1,000 records behind
  for 10 minutes (app team). Nothing is lost while it lags; never reset offsets or skip records.

## Verification

Verification results and the full seven-check recovery drill are recorded in
[verification](verification.md). The drill records SHA-256 hashes of the publisher,
producer configuration, metrics, V9/V10, alert rules and runner so its evidence can
be checked against the published source.

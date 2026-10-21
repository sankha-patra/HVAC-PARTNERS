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

- Transient failures retry after 10 seconds, up to **50 claims**. Counting claims
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

The budget is a work limit, not a promised outage recovery time. A prolonged broker
outage can exhaust it, leaving durable entries requiring an operations replay.
Money state and ledger entries are not rewritten by quarantine or replay.

## Producer configuration

`BillingKafkaConfiguration` already enabled idempotence and `acks=all` in every
billing profile, independently of production YAML. It now explicitly enforces a
positive retry budget and at most five requests in flight as well. Parameterized
factory tests cover default, billing, local-observability and production settings,
including conflicting input configuration. Idempotence applies to a producer
session; it does not deduplicate application retries across owners or manual replay.

## Stuck payment alert

`billing_payments_stuck` counts overdue unresolved payments under the local policy:

| State | Age since creation |
| --- | --- |
| UNKNOWN, SUBMITTING | over 15 minutes |
| REQUIRES_ACTION | over 24 hours |
| PROCESSING | over 7 days |

`BillingPaymentStuck` fires when this count remains positive for two minutes and
resolves when it becomes zero. Created/captured/failed/returned/refunded payments
are excluded. Recovery retries cannot reset the timer. These are local operational
thresholds, not a measured Stripe SLA. Review provider evidence and the finance
queue; do not create an unverified replacement charge. Prometheus firing alone
is not evidence of an external notification receiver.

## Stuck refund and release alerts

`billing_refunds_stuck` counts approved refunds not submitted within 15 minutes, uncertain
or submitting refunds older than 15 minutes from submission, and provider-pending refunds
older than seven days. Ages come from immutable approval and submission times, so retries
cannot hide a refund. `BillingRefundStuck` fires after two minutes.
`BillingReleaseAwaitingApproval` fires when a requested release has waited 30 minutes for a
second approver. Both are local demonstration thresholds, not provider SLAs.

## Verification

Verification results and the full seven-check recovery drill are recorded in
[verification](verification.md). The drill records SHA-256 hashes of the publisher,
producer configuration, metrics, V9/V10, alert rules and runner so its evidence can
be checked against the published source.

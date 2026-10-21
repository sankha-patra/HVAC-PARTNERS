# First repair: recovering a payment after a crash

This change covers payment submission and transaction boundaries. Refund recovery,
employee case resolution and expiry of abandoned payments were added afterwards; see
[the second repair](payment-lifecycle-completion.md). The app
remains a reference implementation with simulated integrations and a Stripe test
adapter; this repair is not evidence of production readiness.

## Follow one $100 invoice

A supplier sells a $100 part to a repair shop. The supplier creates invoice
`INV-101`: the shop still has to pay $100.

When the shop clicks Pay, our app creates payment `PAY-501` in PostgreSQL.
It reserves $100 against that invoice. **Reserved means another payment cannot
claim the same unpaid amount. It does not mean the supplier received $100.**

The browser supplies a request key. Repeating that key with the same invoice
selection returns `PAY-501`. Our server also creates a separate provider key,
`billing_<payment UUID>`, for the request it sends to Stripe. These two keys
protect retries at different boundaries.

```mermaid
sequenceDiagram
    participant Shop
    participant App
    participant DB as PostgreSQL
    participant Stripe
    Shop->>App: Pay invoice INV-101
    App->>DB: Save PAY-501 and reserve $100
    Note over DB: Commit creation
    App->>DB: Save original submission request and mark SUBMITTING
    Note over DB: Commit TX1; return connection to pool
    App->>Stripe: Create hosted Checkout with stable provider key
    Stripe-->>App: Checkout reference and link, or uncertain outcome
    App->>DB: Save verified result
    Note over DB: Commit TX2; return connection to pool
    App-->>Shop: Show Checkout or pending payment
```

The first provider call normally creates a place for the customer to pay.
Receiving `cs_test_...` and a Checkout link does not establish that money was
collected. Only verified capture evidence moves the invoice amount from reserved
to paid and records the accounting entries. Those local changes commit together.

## What was broken and what changed

Previously, recovery only looked up a known Stripe reference. A crash after TX1
but before the call, or a lost response after Stripe accepted the call, could
leave the app without that reference. The worker returned UNKNOWN repeatedly.

TX1 now saves the exact command: payment ID, provider key, amount, currency,
payment method and return URL. It also saves a request-version marker and the
preparation time. The new V11 migration prevents updates to those fields, so a
retry cannot quietly change its parameters or reset its age.

| Situation | Current behavior |
| --- | --- |
| Crash before TX1 commits | No submission is committed; a normal submit can prepare it |
| TX1 commits, then process stops before provider call | Worker can submit the saved request within the safe replay window |
| Stripe accepts but response is lost | Worker repeats the same key and parameters, then reads current Checkout state |
| Checkout reference is known | Worker uses a read-only lookup |
| A status read fails after obtaining a verified reference | Save the reference with UNKNOWN so subsequent recovery can look it up |
| Stripe request is too old or its request version/credential has changed | Send no new POST; retain the reservation and open PAYMENT_REPLAY_UNSAFE for investigation |
| Attempt predates V11 and has no saved request | Remain lookup-only; do not invent the missing request |
| Claimed work has expired or another worker/webhook has replaced it | Skip the provider call; check the lease token again when applying results |
| Database commit fails after provider capture | Roll back local financial changes, retain the earlier attempt, and recover verified provider evidence later |

Stripe API v1 can discard idempotency keys once they are at least 24 hours old.
This implementation permits replay for **less than 23 hours from preparation**,
leaving a margin. The time limit applies to the initial saved submission too,
in case its process was paused for a long time. It does not extend on retries.
Read-only lookups using a known reference remain possible after this deadline.
See [Stripe's idempotent request contract](https://docs.stripe.com/api/idempotent_requests).

The request marker includes the pinned API version, a request-format version and
a SHA-256 fingerprint of the configured test credential; the credential itself
is not stored. This deliberately blocks missing-ID replay even after same-account
key rotation. Changing request serialization requires a format-version change.
Legacy and blocked requests can still be resolved by correlated, verified
webhook evidence. A complete employee resolution workflow remains outstanding.

The simulator keeps its keys indefinitely and can replay the saved command
without Stripe's retention deadline. That is a property of the local simulator,
not a promise about an external provider.

## Why propagation mattered in the interview

`TransactionTemplate` normally uses REQUIRED. If the caller already opened a
transaction, its blocks join that transaction. Returning from the first block
then does not necessarily commit the write or release the connection.

Payment submission, simulation, refund approval/submission and recovery entry
points now reject an existing transaction or synchronization scope before doing
work. The short `BillingStore.tx` blocks can still join each other for atomic
database operations. The guard works for direct Java calls as well as Spring
proxy calls.

Changing every block to REQUIRES_NEW would not solve the connection-holding
problem: a suspended outer transaction can still retain its resources while the
inner transaction uses another connection. See
[Spring's propagation documentation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

## What the tests establish

- PostgreSQL tests inject a process-stop error after TX1, restart the service
  objects, and recover one payment with one provider operation. The same browser
  key still returns the same payment; confirmed capture creates one journal.
- Other database tests cover unchanged saved parameters after a return-URL
  configuration change, immutable submission fields, unsafe replay escalation,
  legacy records and stale worker ownership.
- An actual one-connection HikariCP pool has zero borrowed connections at the
  provider-call boundary. A separate connection sees the committed TX1 record.
- Outer-transaction calls are rejected before payment submission, refund
  approval or provider recovery performs work.
- Stripe adapter tests use controlled HTTP responses to check identical request
  parameters, current-state lookup, retained references, credential/version
  changes and the 23-hour boundary. These are contract tests, not a live Stripe
  sandbox payment.

HikariCP is the pool that lends database connections to application work. The
test above checks connection ownership. It does not establish response latency,
TPS, or any of the old 43/380 TPS and 457/5.8 ms claims.

The key rule to explain in a pitch: **a database rollback cannot undo a payment
already accepted by an external provider. Keep the payment record, determine
what happened, and apply the verified local accounting effect once.**

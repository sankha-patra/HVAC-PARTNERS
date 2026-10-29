# Payment reliability update — 25 October 2026

The older lists describe earlier checkpoints. This update separates completed work, the newly implemented gaps and the remaining operating limits.

## The four review findings

| Earlier finding | Current position |
|---|---|
| Transaction annotation conversion was only a proposal | Production migration completed: all 51 transaction sites use named proxied commands, with dynamic deadlines and guarded I/O. The clean485-test/31-class run includes32dedicated boundary/crash checks. The earlier hybrid proof is superseded; see transactional-one-flow-proof.md and verification.md. |
| Circuit-breaker reset and trial ownership needed correction | Fixed. Only successful Checkout creation resets it. A trial owns its permit, late cleanup cannot clear another trial, and requests recheck the pause after waiting for capacity. |
| Concurrent clicks could say “nothing was charged” while a payment was already moving | Fixed. The payment is checked under its lock before admission. A duplicate during preparation now gets an explicit in-progress response, without waiting for the original row lock or provider call. |
| Disputes needed ID, ordering, compatibility and deadline safeguards | Fixed: documented du_ IDs, same-second ambiguity, no invented event time, compatible enrichment of old inbox records, readable amount/charge evidence, actionable-first finance listing and deadline alerts. |

## Claude's five earlier suggestions

| Suggestion | Current position |
|---|---|
| Page updates itself | Already implemented in 3ef85ab. |
| Busy/failing Stripe refused before submission is saved | Already implemented in 3ef85ab, then corrected by the later race/breaker fixes. |
| Answer a second identical click promptly | Newly implemented. PostgreSQL transaction-scoped try-locks identify an in-flight create/submit across application instances. HTTP409 REQUEST_IN_PROGRESS and Retry-After: 1 tell the caller to keep the original request. A committed create replay returns its original payment. The guarantee is no waiting behind the first save/provider call, not an absolute end-to-end latency guarantee. |
| One payment reference across retries | Newly implemented. A deliberate retry after confirmed failure creates a separate, unsubmitted attempt under the first payment's customer reference. Each attempt retains its own internal ID, provider key, journals, refunds and late evidence. |
| Amount due plus badge | Already implemented in 931149c. |

“Nothing from gap 1 is committed” was an earlier checkpoint. Closing unused Stripe pages was subsequently committed as **0d6ac1d**. The dispute/traffic changes and this new implementation remain uncommitted. Nothing was pushed or deployed by this task.

## What trying again means

1. A lost network reply resends the **same saved request and key**. It must not create a new attempt.
2. A payer deliberately trying again after a **confirmed failure** gets a new attempt and key, under the same customer reference.
3. That new attempt starts **ready to pay**. It does not charge automatically; the payer checks the amount and presses Pay.
4. UNKNOWN, SUBMITTING, PROCESSING and REQUIRES_ACTION cannot start another attempt. Finance-released uncertainty also cannot use this retry path.
5. The invoice set and exact amount must match. Account, merchant and payer permissions apply to new requests and saved-result replays.
6. Concurrent retries create one child. The same key returns that child; a different key cannot make a second child from the same failed attempt.
7. A known older capture blocks submitting a newer retry. Late evidence remains recordable against the original attempt; it is never blocked by a uniqueness rule on the shared reference.
8. The browser saves the retry target, key, method and invoices before sending. Timeout, reload and an in-progress reply preserve them. An identity conflict keeps the request for investigation and asks the payer to contact support.

V22 backfills old payments with their existing ID as reference. New rows default to their own ID. Database foreign keys prevent references or retry links from pointing at another account's payment. The upgrade test starts with real version-21 data before applying V22.

## Back pressure and transaction limits

Back pressure means refusing excess new work with a clear reply so the system can keep handling accepted work. The existing default allows eight simultaneous provider operations per instance. The breaker and this limit do not prove an account-wide requests-per-second capacity.

The admission wait still defaults to 100 ms. Application configuration now rejects a wait at or above the three-second database lock timeout. The 100 ms is only the additional wait for a provider slot; saving, commit and scheduling add time. The local pool is 20, and the AWS override is 10 per task.

The [one-flow transaction proof](transactional-one-flow-proof.md) explains why its bounded outer transaction remains: REQUIRED write methods join it, preserving the existing request-shortened physical deadline and PostgreSQL settings. A static annotation alone is not a mechanical replacement for those limits.

## Verification

| Check | Result |
|---|---|
| Complete Java suite | 458 tests across 23 classes; no failures, errors or skips |
| Complete frontend suite | 80 tests passed |
| Production frontend build | Passed |
| Prometheus tests/configuration | Passed; 27 rules |
| Alertmanager configuration/templates | Passed |
| Repository boundaries and preserved migrations | Passed |
| Tested build inputs | 125 backend input files match the tested container; 33 frontend source files match the tested snapshot; dependency manifests match the UI image |
| Earlier billing migrations | All 20 existing migration files through V21 retain their bytes |

Tests were written first. Old code reproduced the duplicate-wait and configuration failures; unannotated proof steps failed the transaction/checked-rollback checks; the retry stubs failed the intended backend/frontend cases. Independent review then reproduced and fixed the browser identity-conflict and saved-replay merchant checks. One old notice-count assertion was updated to continue checking every notice rather than assuming a fixed count.

Simulator, fake provider and isolated PostgreSQL only. These results do not establish live bank/Stripe behaviour, notification delivery or production capacity. No live provider/AWS call, application restart, commit or deployment was performed by this task. Uploaded master/pitch PDFs remain unchanged.

## Primary references used

- [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests) and [HTTP errors/recovery](https://docs.stripe.com/error-low-level): preserve identity when retrying an unresolved request.
- [PostgreSQL 17 advisory locks](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS): transaction-scoped try-locks return without waiting and release with the transaction.
- [Spring propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) and [Spring 6.1.1 Transactional attributes](https://docs.spring.io/spring-framework/docs/6.1.1/javadoc-api/org/springframework/transaction/annotation/Transactional.html): real proxies, REQUIRED participation and explicit checked-exception rollback.
- [AWS load shedding](https://aws.amazon.com/builders-library/using-load-shedding-to-avoid-overload/), [bulkheads](https://resilience4j.readme.io/docs/bulkhead) and [circuit breakers](https://resilience4j.readme.io/docs/circuitbreaker): concurrency admission, failure pauses and measured capacity are different concerns.

The earlier video comparison remains in docs/payment-dry-run.md. This task checked the primary documentation; it does not claim to have newly watched every linked video.

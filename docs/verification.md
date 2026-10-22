# Verification of the extracted repository

## Release proof, late evidence, transaction guard and remaining review risks — 22 October 2026

Remaining risks from the 21 October review, fixed in order of money impact. Every new test
failed first, then passed.

1. **A released payment could still be paid (risk 1).** A release now asks the provider first.
   Stripe expires the open Checkout Session (`POST .../expire`) and the release goes ahead
   only if the session is then expired and unpaid. Without a session ID, every session
   created in the 24-hour replay window is searched. If the provider has the payment, its
   status is recorded instead (`PROVIDER_HAS_OPERATION`). If it cannot prove absence,
   nothing is released (`PROVIDER_ABSENCE_NOT_CONFIRMED`).
2. **A released refund could still be paid out (risk 2).** A refund whose saved request can
   still be replayed cannot be released. Otherwise Stripe's refunds on the original payment
   are listed, and the release goes ahead only if none carries this refund ID.
3. **Release and recovery could act at once.** The release takes the operation's lease
   before asking the provider, is refused while recovery holds it, and is fenced by it.
4. **Late definitive evidence was discarded (risk 9).** A superseded worker's uncertain
   answer is still dropped, but CAPTURED, FAILED, RETURNED, REFUNDED and refund FAILED are
   booked, without taking the current owner's lease.
5. **Transaction loophole.** `journal`, `publish`, `escalate`, `resolveCases` and
   `caseAction` throw outside a transaction, like MANDATORY propagation.
6. **Lease (risk 6).** 45 s → 120 s; the slowest provider sequence is about 40 s.
7. **Lock order (risk 7).** Payment creation locked invoices in Java's signed UUID order.
   A test showed creation already holding the second invoice while it waited for the first
   (`could not obtain lock on row`). It now locks in PostgreSQL's order.
8. **Saved Stripe 500 (risk 4).** Stripe returns a saved server error on every replay of
   that key. Recovery now searches for the session or refund instead of replaying.
9. **Smaller items.** Scheduled jobs use 4 threads instead of 1. A temporary database
   failure leaves a settlement import RECEIVED for retry, with no false case. Unmatched
   webhook retries back off from 30 s to at most 1 hour. Refused (403) finance requests
   are written to `billing.denied_actions` (V16), which cannot be updated or deleted.

The full Java suite passed **367 tests, with zero failures, errors or skips**
(345 before, plus 22 new; batch 1 alone was 361). **47 frontend tests** passed,
including the new release messages. The Java test log contained no `sk_test_`, `whsec_`
or `Bearer` text. Simulator and Stripe adapter with a fake transport; no live Stripe call.

Not fixed in code:
- Risk 5 is only partly fixed. The application's database user still owns the audit tables,
  so it could `TRUNCATE` them. That needs a separate migration user in deployment.
- Risk 8 (two replicas) needs no change: claims use `SKIP LOCKED` or row locks, and
  waits are bounded by the 3-second lock timeout.
- Rotating the Stripe key makes saved requests unsafe to replay. This is deliberate
  (fail closed), and the cases are released with provider proof.

## Error classes, reopening cases, refund rejection, JSON library and timeouts — 22 October 2026

Five remaining review findings, fixed in order. Every new test below failed first
(timeout tests: the lock wait was still blocked when cut off at 8 s; refund rejection:
the method was a stub), then passed.

1. **Temporary failures are not evidence.** `BillingStore.isTransient` recognises lock,
   statement and transaction timeouts, deadlocks, serialization failures and lost
   connections. Recovery retries them without a `RECOVERY_EVIDENCE_MISMATCH` or
   `REFUND_UNRESOLVED` case. A webhook stays queued (retry in 10 s) and becomes
   UNMATCHED only after 10 temporary failures. A review case closes itself when its
   event later applies. `ProviderRejectedException` means the provider did nothing:
   the request was invalid and never sent, or Stripe answered 401, 403, or 400
   `invalid_request_error`. A first submission then fails at once and frees the
   invoice or refund amount. During recovery it stays uncertain, because an earlier
   attempt may have succeeded. A reused key (`idempotency_error`), 409, 429, 5xx and
   unreadable bodies stay uncertain.
2. **Closed cases reopen.** Problems that can recur (`RECOVERY_EVIDENCE_MISMATCH`,
   `ORIGINAL_PROVIDER_UNAVAILABLE`, `DISPUTE_REQUIRES_REVIEW`, `REFUND_LATER_FAILED`)
   use `escalate`, which reopens a resolved case with a `REOPENED` audit entry.
   One-time events keep one case.
3. **Refund requests can be rejected.** `POST /finance/refunds/{id}/reject` with a
   10–1000 character note, by the requester (FINANCE) or a REFUND_APPROVER of the
   merchant, only while REQUESTED. No money moves and the amount is available again.
   `V15__refund_rejection.sql` records who, when and why, and a trigger keeps a
   rejection final. The finance page has a Reject button.
4. **JSON library.** `org.json` 20180130 → 20260814 (fixes CVE-2022-45688 and
   CVE-2023-5072). No code change was needed; the full suite passed on the new version.
5. **Timeouts.** Each `BillingStore` transaction has a 10-second Spring deadline and
   sets, for that transaction only, `lock_timeout` 3 s, `statement_timeout` 10 s and
   `idle_in_transaction_session_timeout` 20 s. Statements outside transactions have
   a 15-second query timeout (`spring.jdbc.template.query-timeout`). A test holding a
   row lock from another connection now gets a temporary error after 3.6 s, with no
   change saved. Cost: one extra `set_config` statement per transaction (not measured).

The full Java suite passed **345 tests, with zero failures, errors or skips**
(331 before, plus 14 new; 139 financial database and 79 Stripe adapter tests).
The frontend production build passed and **46 frontend tests** passed (44 before,
plus two for refund rejection), run in a `node:24-alpine` container from
`frontend/Dockerfile`. The Java test log contained no `sk_test_`, `whsec_` or
`Bearer` text. Java tests ran in the Docker test containers with the same steps as
`scripts/verify-container-tests.ps1`, executed from Bash. Simulator only; no live Stripe call.

## Logging of swallowed failures — 22 October 2026

Code review found that payment submission, refund submission, payment and refund
recovery and the webhook inbox caught failures, turned them into UNKNOWN or UNMATCHED,
and wrote no log line. The cause of a stuck payment (for example a Stripe HTTP 401)
was lost. Each of these places now logs the payment, refund or event ID with the exception:

- Uncertain provider outcomes and replay-unsafe escalations log at WARN.
- Evidence that recovery could not record logs at ERROR, next to its finance case.
- An unmatched webhook logs at WARN on its first failure and at DEBUG on the 30-second
  retries. An expected evidence mismatch logs its reason without a stack trace.
  If recording the failure also fails, the original failure is attached as suppressed.

Four new database tests failed first (no log line was written), then passed. A new
Stripe adapter test checks that a 401 body echoing the key, an unreadable response
and a transport error all produce exceptions whose message, cause and stack trace
contain no key, `Bearer` header or response body. It passed before and after the
change, and guards that property. Logs contain identifiers, not credentials or card data.

The full Java suite passed **331 tests, with zero failures, errors or skips**
(326 before, plus five new tests; 128 financial database and 77 Stripe adapter tests).
The run's test log contained no `sk_test_`, `whsec_` or `Bearer` text. Run in the Docker
test containers with the same steps as `scripts/verify-container-tests.ps1`, executed
from Bash. Simulator only; no live Stripe call.

## Out-of-order refund events — 22 October 2026

Code review found that a provider "refund failed" event processed before an older
"refund succeeded" event turned the refund from FAILED to REFUNDED and posted money
that never left. Stripe does not guarantee event order. The cause: FAILED meant both
"the provider said it failed" and "two employees released it".

- New test `olderRefundSuccessArrivingAfterProviderFailureCannotPostMoney` failed first
  (`expected: "FAILED" but was: "REFUNDED"`), then passed after the fix.
- A staff release is now stored as RELEASED (`V14__separate_released_refunds.sql`,
  which also converts earlier releases). A provider FAILED is final: older success
  evidence posts nothing and writes a `REFUND_EVIDENCE_IGNORED` timeline entry.
- New test `releasedRefundIsNotAProviderFailureAndStillRecordsLateSuccess` shows a
  released refund still records a late provider success, as before.

The full Java suite passed **326 tests, with zero failures, errors or skips**
(324 before, plus the two new tests; 124 financial database tests). Run in the
Docker test containers (Java 17, PostgreSQL 17) with the same steps as
`scripts/verify-container-tests.ps1`, executed from Bash because Windows PowerShell 5.1
stopped that script on Docker's progress output. Simulator only; no live Stripe call.

## Payment timeline index — 22 October 2026

The full Java suite passed **324 tests, with zero failures, errors or skips**,
including a new real-HTTP test for latest-100 timeline ordering, account isolation,
anonymous rejection and unchanged financial snapshots. The additive V13 index and
test were included; all 104 build/source input hashes matched before and after.

A separate authenticated HTTP experiment compared the original V12 schema with
only the timeline index added. Across six paired rounds at 100,000 events and
eight clients, aggregate payment-detail read rate was **111.35 → 404.73 requests/s**
and pooled p95 was **124.76 → 63.75 ms**. Every raw sample was retained and independently
recomputed. Across the main and smaller sensitivity datasets, 50,676 measured
responses had zero errors or response-hash mismatches. The smaller dataset showed
no established throughput benefit and a worse observed p95; see the full report.

These are warm-cache local endpoint measurements on a shared host, with the load
generator sharing the app's CPU quota. They are not captured-payment TPS or a
production capacity measurement. Claude reviewed the protocol, implemented the
separate creation harness, and critically reviewed the completed read evidence.

See [the benchmark report](payment-timeline-benchmark-2026-10-22.md) and
[brief interview notes](payment-timeline-interview-notes-2026-10-22.md).
The report retains failed/interrupted attempts and their exclusion reasons.
The guarded creation experiment also passed: 6,144 measured creates and 6,656
responses including warmups, with zero invalid responses and verified financial
invariants. Its performance varied across paired rounds, so the read result is
the recommended resume claim. The insert probe records added WAL/storage cost.
The [verification manifest](payment-timeline-verification-2026-10-22.json) checks
both experiments, input hashes, raw-sample audits and restored execution state.

## Manual reference validation and refund review — 21 October 2026

The complete Java suite passed **323 tests, with zero failures, errors or skips**, across
17 classes. This adds 55 cases to the preceding 268-test suite: 22 manual-reference
database cases, 28 Stripe adapter cases and five refund review cases. The final run
includes 122 financial database tests and 76 Stripe adapter tests.

- Matching keys cannot conceal a wrong amount, currency, intent, payment method, refund
  ID or original payment. Rejected evidence leaves the local records and case unchanged.
- Verified references settle once. Provider calls have no active transaction; caller
  transactions are rejected. The existing one-connection test confirms committed
  preparation and zero borrowed connections during the payment provider call.
- Refund REVIEW preserves FAILED, REFUNDED, PROCESSING and UNKNOWN states, money and
  capacity. The inbox retains the evidence and opens one review case on repeated delivery.
- All seven synthetic network/load scenarios passed, including ERP backlog drainage.

An earlier 316-test run had one failure: the overload scenario observed one unsuccessful
admitted workflow. Its cause was not established. The final full run passed with the
same load test, assertions and limits; seven payment-method tests were added between runs.

The final run compiled the current read-only sources into empty build directories in
Docker, using Java 17.0.20.1, PostgreSQL 17.11 and embedded Kafka. All 102 input hashes
matched before and after the run. The repository boundary check and nine preserved
migration hashes passed. Temporary test containers and their network were removed.

See the [verification manifest](payment-reference-review-verification-2026-10-21.json)
and [repair details](payment-lifecycle-completion.md#recovery-validation-and-refund-review-repair).
Raw reports and the exact runner are in `output/collaboration/2026-10-21/`.
The standard reproduction command is `./scripts/verify-container-tests.ps1`.

These results use simulated provider, bank and ERP responses. No live Stripe payment,
frontend acceptance check or production throughput measurement was performed for this repair.
Unmatched review events retain the existing 30-second retry schedule.

## Refund recovery, case resolution and expiry — 21 October 2026

The complete Java suite passed **268 tests, with zero failures, errors or skips**
(229 before this change, plus 39 new): 117 financial database tests, 48 Stripe adapter
tests and 12 HTTP security tests. Vitest passed 44 frontend tests, and promtool 3.13.4
passed the rule check and the rule tests, including the two new alerts. PostgreSQL 17.10
and embedded Kafka ran locally; Stripe, bank and ERP responses were simulated.

The new checks cover:

- A refund that crashes before the network call, or that loses its response, is replayed
  from its saved request, and exactly one provider refund results.
- Past the 23-hour window, a refund escalates and its amount stays committed.
- A stale recovery claim cannot call the provider.
- Unresolved refunds back off, then escalate.
- The stuck-refund gauge.
- An unverified provider reference changes nothing.
- A release needs a different approver.
- Unresolved cases cannot be closed.
- The audit history cannot be changed.
- Provider evidence resolves a case automatically.
- An abandoned unsubmitted payment expires.
- Expiry and submit race safely (checked five times).

Mutation checks confirmed that the new tests fail when the guards they protect are removed.
The nine preserved migration hashes and the V11 hash are unchanged; V12 is additive.

See [what changed](payment-lifecycle-completion.md) and the
[verification manifest with source hashes](payment-lifecycle-verification-2026-10-21.json).

This run used Maven outside the Docker harness. `./scripts/verify-container-tests.ps1`,
the full-stack drill and a live Stripe test-mode payment were not run for this change.
The [Stripe test-mode runbook](stripe-test-mode-runbook.md) is the next piece of evidence.

## Payment submission recovery — 21 October 2026

The full rebuilt disposable Docker suite passed **229 tests, with zero failures,
errors or skips**. This includes 86 financial database tests, 41 Stripe adapter
tests and seven synthetic network/load scenarios. PostgreSQL 17 and embedded
Kafka ran locally; Stripe, bank and ERP responses were simulated.

The new checks cover a crash after preparation but before provider contact,
saved-request replay, immutable parameters, stale worker ownership, the 23-hour
Stripe replay cutoff, retaining a verified reference after a failed status read,
and rejecting outer transactions. A one-connection Hikari pool had zero borrowed
connections at the provider-call boundary, with TX1 visible from another connection.
The HTTP lab recovers an accepted payment after a lost response and listener
restart, and checks that retries and duplicate webhooks retain one financial effect.

See the [walkthrough](payment-recovery-walkthrough.md) and the
[verification manifest with source hashes](payment-recovery-verification-2026-10-21.json).
Raw evidence is in ignored `.tmp-notes/container-tests/20261021T104211006Z`.
Run `./scripts/verify-container-tests.ps1` to reproduce. The repository boundary
check and preserved migration hashes also passed; V11 is an additive migration.

This validates the working-tree source identified by the manifest, not a new
deployed application. No live Stripe transaction, frontend acceptance check,
full-stack outage drill or production load test was performed for this repair.
Refund recovery and the employee investigation/resolution workflow remained open
at that point; the entry above covers them.

## Outbox and alert fixes — 17 October 2026

Verified code commit: [`bdbb284ed080`](https://github.com/sankha-patra/HVAC-PARTNERS/commit/bdbb284ed080756acbb1c3fd0309ea123c9c05e6).
The full drill ran against a clean working tree at that commit and recorded hashes
of the publisher, Kafka configuration, metrics, simulator, V9/V10, alert rules and
runner. Each hash was checked against the Git blob before this report was written.
The subsequent documentation commit does not change the tested application code.

| Check | Result |
| --- | --- |
| Complete Java suite | 210 passed; zero failures, errors or skips; 16 classes |
| Financial database tests | 78 of the 210 |
| Identity tests | 25 of the 210 |
| Real embedded three-broker Kafka transport and DLT | 4 of the 210 |
| Producer safety settings independent of production YAML | 4 of the 210 |
| Synthetic network and bounded-load scenarios | 7 of the 210 |
| Eight pending Kafka sends with a one-connection Hikari pool | PASS; no borrowed connection or transaction during broker waits; outbox rows remained lockable |
| Poison event, attempt budget, expired owner and audit-write failure | PASS; durable quarantine, successor release, fenced updates and rollback on failed audit |
| Prometheus rule tests | PASS; grace, firing, resolution and transient-spike cases |
| Source separation and nine preserved migration hashes | PASS |
| Full seven-check recovery drill after V9 and V10 | PASS; rebuilt application images; no Quick flag |

| Full drill check | Result |
| --- | --- |
| Full stack, browser assets, identity, CSRF and customer API | PASS |
| Background payment recovery survives an abrupt application restart | PASS |
| Kafka outage retains outbox, fires a real alert and drains after restart | PASS |
| Application outage fires and resolves the scrape alert | PASS |
| Database outage rejects writes and recovery preserves committed money | PASS |
| Logical backup restores every identity and billing table into a separate database | PASS |
| Provisioned Grafana dashboard queries live Prometheus data | PASS |

Sanitized, shareable evidence: [verification manifest](outbox-verification-2026-10-17.json).
Raw JUnit evidence stays in ignored `.tmp-notes/container-tests/20261017T155048291Z`;
the full drill report and local backup stay in ignored `.tmp-notes/billing-stack/20261017T162933603Z`.
Do not publish local secrets, backups or identity data.

The Java rerun compiled the current billing source mounted read-only into the
cached verification runtime, against fresh disposable PostgreSQL databases. The
application images were rebuilt from `7f3c11e3e977`. The final full drill
reused those images with `-SkipBuild` because only the runner's test-session renewal
changed; the application/build-source trees were checked identical between that
build and the verified commit. The tests did not use host PostgreSQL.

To reproduce from a fresh checkout:

```powershell
./scripts/verify-container-tests.ps1
./scripts/start-billing.ps1 -ValidateOnly
./scripts/verify-billing-stack.ps1
```

The full drill intentionally stops its own labeled services. Quick mode covers
only three integration checks and cannot establish outage or restore results.
The drill uses app http://localhost:4500, Grafana http://localhost:3300 and
Prometheus http://localhost:9390. It reloads current monitoring rules and checks
that the stuck-payment gauge and alert rule are present and healthy.

## Interpretation and limits

See [publication and alert policy](payment-ordering-and-alerts.md). Kafka sends
take place outside database transactions. Lease tokens fence database completion,
not the send itself. Transport remains at least once; consumers deduplicate event
IDs. Normal retries retain queue order, but quarantine releases successors and
manual replay or a paused owner can produce late records. Do not claim absolute
order, exactly-once transport or automatic recovery from every permanent error.

PSP, bank and ERP are simulated. There was no live Stripe sandbox transaction,
AWS runtime, real bank or external ERP validation. One local logical backup/restore
does not establish production RPO/RTO, multi-host failover or PITR. Synthetic local
load results do not establish production capacity or latency. API/asset checks do
not establish visual browser acceptance. Prometheus firing does not prove an
external notification receiver delivered a page.

## Earlier extraction checkpoint

The [initial extraction manifest](verification-results-2026-10-17.json) records
181 passing Java tests and the earlier independent build/source checks. At that
checkpoint the Angular suite had 39 passing tests and an optimized build, and the
three-check smoke run passed. These frontend checks were not rerun for the backend
outbox changes. The current results above supersede the earlier statement that only
the smoke runner had executed after extraction.

An early extraction attempt exited with code 137 and Docker became unavailable;
the cause was not established. That failed attempt is not counted as passing.
The revised outbox verification initially found assertion type mismatches and an
existing simulator race on its second unique index; both were corrected before
the complete passing rerun recorded above. An initial full drill passed four checks
then stopped because its test JWT expired before the database-outage phase. The
runner now renews its session and CSRF cookie between phases and before the
restored-history check, and waits for the first rule evaluation after Prometheus restart;
the application's 15-minute token lifetime was not increased. That failed drill
is not counted as a full pass.

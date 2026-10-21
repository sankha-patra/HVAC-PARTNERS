# Verification of the extracted repository

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

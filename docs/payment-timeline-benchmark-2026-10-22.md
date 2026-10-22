# Payment timeline indexing — measured evidence

Date: 22 October 2026, India time. The suite/read runs started on 21 October UTC;
the completed guarded creation run started on 22 October UTC.

## Finding and fix

- The payment-detail endpoint calls `BillingService.view`, whose timeline query is:
  `WHERE intent_id=? ORDER BY id DESC LIMIT 100`.
- Schema V1–V12 had only the timeline primary key on `id`. With ten matching events,
  the original plan scanned all 100,000 events to find them.
- V13 adds `billing_timeline_intent_order(intent_id,id DESC)`.
- This experiment changes only that index. Application behavior and transaction
  boundaries are identical between A and B. The baseline is the current hardened
  working tree with the original V12 schema, rather than a deliberately degraded app.
- `create` also calls `view` inside its reservation transaction. The creation
  experiment below checks that endpoint separately.

## Completed payment-detail read result

Actual signed-JWT HTTP `GET /api/v1/billing/intents/{id}` requests; PostgreSQL 17.11,
Java 17.0.20.1, eight closed-loop clients, Hikari eight connections, six alternating
AB/BA pairs. Each variant has six ten-second measurement windows. Warmups, index
builds, checkpoints, response baselines and query plans are outside timing.

Fixture: 10,000 synthetic CREATED intents, ten interleaved events each. These are
100,000 timeline events, not 100,000 payments or completed captures.

| All-round aggregate | Original schema A | Index B |
| --- | ---: | ---: |
| Successful completions / measured window | 111.35 requests/s | 404.73 requests/s |
| Pooled p50 HTTP latency | 79.73 ms | 10.72 ms |
| Pooled p95 HTTP latency | 124.76 ms | 63.75 ms |
| Pooled p99 HTTP latency | 184.32 ms | 76.51 ms |
| Retained measured requests, including drain | 6,729 | 24,332 |
| Response failures / mismatches | 0 | 0 |

The aggregate observed rate increased **3.63×**; pooled p95 decreased **48.9%**.
These use every primary round, including the slower initial indexed rounds.

| Pair | Order | A requests/s | B requests/s | A p95 ms | B p95 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | AB | 108.3 | 300.2 | 123.52 | 72.06 |
| 2 | BA | 112.3 | 276.7 | 121.31 | 74.56 |
| 3 | AB | 103.8 | 449.2 | 153.41 | 56.92 |
| 4 | BA | 112.3 | 451.6 | 119.23 | 58.60 |
| 5 | AB | 124.4 | 468.0 | 108.36 | 56.95 |
| 6 | BA | 107.0 | 482.7 | 153.15 | 58.86 |

Every primary pair improved, but the magnitude varied. The median paired rate
ratio was 3.89×, with a descriptive six-pair bootstrap interval of 2.62–4.42×.
The aggregate ratio, median paired ratio, and ratio of median rates are different
statistics; do not mix their labels. The initial indexed-round drift is unexplained.

## Query-plan proof

Representative round-zero, first-payment plans:

- A: sequential scan; **99,990 rows discarded**, ten returned; 1,518 shared-buffer
  hits; 8.577 ms execution time.
- B: bitmap index/heap scan using the new index; ten rows; 14 shared-buffer hits;
  0.260 ms execution time.
- B still sorted the ten fetched rows. Do not claim that this observed plan removed
  sorting or used an index-only scan.
- EXPLAIN execution time is elapsed query time, not a CPU-time measurement or HTTP
  latency. Old, middle and recent targets were sampled in every round.

## Smaller-history sensitivity — retain the weaker result

At 1,000 intents / 10,000 events, two paired rounds produced:

| Aggregate | A | B |
| --- | ---: | ---: |
| Observed read rate | 485.90 requests/s | 493.25 requests/s |
| Pooled p95 | 47.64 ms | 54.71 ms |

The two rate ratios disagreed (0.87× and 1.17×), while both indexed p95 values were
higher. This does not establish a throughput benefit at 10,000 events. The observed
tail regression is retained; two pairs cannot distinguish its cause reliably.

## Correctness and source verification

- **324 Java tests passed**, zero failures, errors or skips, against current source
  including V13 and the new payment-timeline HTTP regression test.
- The new regression checks latest-100 ordering, account isolation, anonymous
  rejection and unchanged financial-table snapshots.
- The read experiment retained **50,676 measured requests** across both sizes:
  zero HTTP failures or response-hash mismatches. Each target's canonical body was
  independently checked before timing, and both variants had to match it.
- Account-isolation and anonymous-denial checks passed outside measurement.
- Financial counts, reservation sums and accounting/provider/outbox counts remained
  unchanged within each read dataset. This is an aggregate-invariant check, rather
  than an exhaustive byte comparison of every database row.
- Source hashes before and after both the full suite and read experiment matched.
- The Python auditor recomputed every recorded round and pooled aggregate from the
  raw CSV, including latency ranks, active-window rates and response hashes.

## Conditions, limits and index cost

- One local shared host; the user's existing application stack stayed running.
  App/load-generator container: two CPU quota, 1,792 MiB limit, 768 MiB test heap.
  PostgreSQL: two CPU quota, 768 MiB limit, disposable storage.
- The HTTP load generator runs inside the application test JVM and shares its CPU
  quota. Its overhead and shared-host interference affect the result. No CPU-
  throttling diagnosis is proven by the retained evidence.
- These are warm-cache, fixed-concurrency observed endpoint rates. They do not
  establish maximum capacity, captured-payment TPS, production performance, or an
  external provider's throughput. The benchmark bypasses gateway, browser and PSP.
- The index occupied **4,087,808 bytes** (~3.90 MiB) alongside a 12,435,456-byte
  timeline heap. Each event insert must maintain another B-tree and generate WAL.
- Ordinary `CREATE INDEX` can block writes while building. Production rollout
  requires an assessed migration window or a separately verified concurrent,
  nontransactional migration procedure. No production deployment occurred.
- An ascending two-column B-tree could also support backward traversal. The
  experiment does not prove that `DESC`, or the second column alone, caused the gain.

## Completed creation experiment

Actual signed-JWT HTTP `POST /api/v1/billing/accounts/{account}/intents`, with one
fresh invoice and key per request. Eight clients use eight independent accounts;
this avoids same-account serialization and does not model a contended customer.
Six alternating AB/BA pairs, 512 creates per variant per pair, identical historical
fixture and application. Phase fixtures are verified and cleaned after timing.

- **6,144 measured creates**, 3,072 per variant; **6,656 total retained responses**
  including the declared warmups; zero invalid responses.
- Every response's account, invoice, amount, allocation, state and timeline was
  independently parsed from its retained body and compared with the fixture.
- All reservation/allocation invariants and base-restoration checks passed.
  These are CREATED intents: no provider operation, capture journal or outbox event
  is expected or claimed.
- Wall/monotonic clocks agreed within **16.56 ms** in every phase. The independent
  audit also checked each request's clocks and bracketed its server creation time.
- Source snapshots matched; all 102 common application/build files match the full
  324-test run and both benchmark runs. The prior execution-state request was
  successfully restored, and the experiment's containers were removed.

| All-round aggregate | Original A | Index B |
| --- | ---: | ---: |
| Observed created-intent request rate | 36.44 requests/s | 45.31 requests/s |
| Pooled p50 HTTP latency | 179.65 ms | 135.31 ms |
| Pooled p95 HTTP latency | 460.53 ms | 392.28 ms |
| Invalid measured responses | 0 | 0 |

| Pair | Order | A requests/s | B requests/s | A p95 ms | B p95 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | AB | 24.78 | 28.51 | 584.63 | 519.56 |
| 2 | BA | 28.13 | 46.36 | 492.91 | 285.73 |
| 3 | AB | 37.13 | 32.97 | 423.71 | 507.01 |
| 4 | BA | 44.08 | 58.92 | 404.66 | 227.64 |
| 5 | AB | 43.33 | 75.45 | 356.73 | 190.15 |
| 6 | BA | 62.37 | 65.69 | 218.97 | 251.69 |

**Do not use the creation gain as the resume headline.** The median paired rate
ratio was 1.24×, with a descriptive bootstrap interval **0.97–1.69×**. The p95 effect
also changed direction: median paired reduction 26.6%, interval **−17.3% to 45.2%**.
One pair had a lower indexed rate and two had worse indexed p95. Strong within-run
drift, short warmups, six pairs and the shared host limit precision. The all-round
aggregate is retained as an observation, not promoted to a stable capacity claim.

### Separate index insert cost

Four paired AB/BA slots per design; 512 single-event transactions per slot, one
thread, through `BillingStore.event`, with no timeline read. Every slot begins
after a forced checkpoint; both variants use the same shuffled target sequence.

| Insert probe | A | B |
| --- | ---: | ---: |
| Median slot WAL bytes / append | 4,250.64 | 9,045.37 |
| Pooled p95 append latency | 3.05 ms | 3.28 ms |
| Total retained appends | 2,048 | 2,048 |

The index approximately doubled WAL in this **post-checkpoint** probe. Full-page
images, checkpoint frequency and event distribution affect the cost; it is not a
universal per-event production overhead. Insert latency varied across the four
pairs. WAL start/end arithmetic and every append's timing/clock bracket were
independently recomputed. Hikari diagnostic means include authorization and
measurement borrows, so they are not transaction-hold percentiles.

### Excluded creation attempts and diagnostic repair

Two creation attempts are **invalid and excluded**:

- `run-20261021T210029904Z`: roughly seven-hour clock gap during the first indexed
  measured phase, then 158 HTTP 401s and nine transport errors. Fixture JWTs expire
  after two hours. Its partial 51.7 requests/s A result is not a completed comparison.
- `run-20261022T040835173Z`: final baseline phase had seven request timeouts and one
  I/O exception. Successful response timestamps span 110.50 seconds, including a
  95.61-second gap, while that phase's monotonic timer spans about 15.45 seconds.
  These inconsistent elapsed clocks invalidate comparability. The exact OS/VM
  cause is unproven; restricted system-event-log access did not establish it.

Claude added wall/monotonic clock evidence and a two-second consistency guard to
the creation harness. The runner requests system wakefulness only during its
execution and restores its prior thread request in `finally`. Request timeouts,
token lifetime, payloads, sample counts and design order remain the same. The
independent auditor rejects a synthetic reproduction of the 95-second mismatch.
The subsequent guarded run completed successfully as reported above. Failed
attempts remain excluded in full; no successful phase was substituted into it.

## What the collaboration challenged

- Claude reviewed the proposed bottleneck, implemented the real creation HTTP
  harness and challenged the read claim's scope, shared CPU quota, write cost,
  small-size regression and early-round drift.
- Codex implemented the index/read experiment, ran the full suite and experiments,
  independently audited raw samples, and challenged two inferences in Claude's review.
- Claude withdrew its CPU-capacity estimate based on EXPLAIN time: elapsed query
  time is not a measured CPU-time budget.
- Claude also withdrew the claim that CPU co-location necessarily understates the
  p95 benefit. Its effect and the direction of any bias were not established.
- Both agree that the completed read experiment supports a scoped local endpoint
  claim, while capture TPS, production capacity and transaction-hold percentiles
  remain outside its evidence.
- Claude's final review found no blocking factual errors in the completed report
  or scoped bullet. We accepted its endpoint-name, test-dataset and transaction-
  timing wording corrections. The read result is the headline; the weaker creation
  result remains supporting/limiting evidence.

## Evidence and reproduction

- [Declared protocol](../output/benchmarks/payment-read-20261022/PROTOCOL.md)
- [Read results](../output/benchmarks/payment-read-20261022/run-20261021T205205146Z/results.json)
- [Independent raw-sample audit](../output/benchmarks/payment-read-20261022/run-20261021T205205146Z/independent-audit.json)
- [Raw read requests](../output/benchmarks/payment-read-20261022/run-20261021T205205146Z/requests.csv)
- [Full Java-suite summary](../output/collaboration/2026-10-21/final-source-tests-20261021T203854792Z/summary.json)
- [Shareable verification manifest](payment-timeline-verification-2026-10-22.json)
- [Completed creation results](../output/benchmarks/payment-read-20261022/run-20261022T044330106Z/create-results.json)
- [Independent creation audit](../output/benchmarks/payment-read-20261022/run-20261022T044330106Z/independent-create-audit.json)
- [Claude's critical read review](../output/collaboration/2026-10-22/38-claude-read-evidence-review.md)
- [Invalid interrupted creation result](../output/benchmarks/payment-read-20261022/run-20261021T210029904Z/create-results.json)
- [Second invalid creation result](../output/benchmarks/payment-read-20261022/run-20261022T040835173Z/create-results.json)
- [Claude's diagnostic fix and corrections](../output/collaboration/2026-10-22/40-claude-clock-diagnostics.md)
- [Claude's final evidence/wording review](../output/collaboration/2026-10-22/42-claude-final-evidence-review.md)

```powershell
./output/benchmarks/payment-read-20261022/run-benchmark.ps1 -BenchmarkClass PaymentReadBenchmarkTest
./output/benchmarks/payment-read-20261022/run-benchmark.ps1 -BenchmarkClass PaymentCreateBenchmarkTest
# Run the corresponding audit_read.py / audit_create.py with its resulting run directory.
```

Run the experiments sequentially in their own fresh disposable databases. Cached
image IDs are pinned by the runner; Maven may resolve missing build dependencies
before measurements. The initial offline-only runner failure is retained in
`run-20261021T204855689Z`; no endpoint measurement started in that attempt.

## Existing resume claims

This index experiment provides new endpoint evidence. It does not validate the
older 43→380 payment-TPS claim or combine a separate outage drill and extrapolated
hourly rate into “5,000 payments during a complete three-broker outage.” Those
claims require their own matching evidence. Keep payment captures, created
intents, status reads, timeline events and outbox events distinct.

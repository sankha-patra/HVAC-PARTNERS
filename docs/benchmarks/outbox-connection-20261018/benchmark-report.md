# Outbox connection benchmark — 18 October 2026

## Result

The current publisher released database connections while awaiting the injected broker acknowledgement.
Both JUnit checks passed, with zero failures, errors or skips. Each design completed all 384 measured publishes.

| Metric | Reconstructed inside-transaction control | Current outside-transaction publisher |
|---|---:|---:|
| Median total connection-hold time per event | 204.447 ms | 2.751 ms |
| p95 total connection-hold time per event | 215.624 ms | 7.803 ms |
| p95 hold per connection checkout | 215.624 ms | 4.161 ms |
| p95 publisher invocation duration | 215.651 ms | 208.915 ms |
| Median observed acknowledgement wait | 200.307 ms | 200.286 ms |
| Connections held while all 8 acknowledgements were gated | 8 | 0 |
| Independent database SELECT during gated wait | Timed out at the configured 500 ms pool limit | Succeeded |

Pooled p95 connection-hold time per event fell by **96.38%** in this experiment.
The per-event measure sums every connection borrow for one publish: one long borrow in the control versus two short borrows in the current design.
This avoids an artificial improvement caused by comparing different numbers of borrows.

## Matched setup

- Unmodified current `BillingWorkers.publishOne` and `markPublished`, compiled from the saved source snapshot.
- Reconstructed control: ownership read with `FOR UPDATE`, the same acknowledgement wait, and `markPublished` inside one transaction. It is not a historical committed version.
- Real PostgreSQL 17 and HikariCP, 8 fully warmed connections, 8 publisher threads, identical JSON payloads and a scheduled 200 ms acknowledgement delay.
- Two warmup rounds of 16 events per design, excluded from the result.
- Six measured rounds of 64 events per design, alternating AB/BA execution order. Total: 768 measured publishes.
- Every measured event checked for exactly one sender invocation and a final `PUBLISHED` state with cleared lease ownership.
- Additional success and transient-timeout cases verified 8/8 events became `PUBLISHED` or retryable `NEW`, respectively, with all connections returned.
- Existing 8-send / 1-connection regression also passed against the same disposable database.
- Docker Desktop, 8 logical CPUs, about 6.74 GiB available to its engine. Benchmark container limit 1.5 GiB, PostgreSQL limit 512 MiB, test JVM heap 512 MiB.
- The user's existing application stack remained running, so results include shared local host noise.

## All measured rounds

Total connection-hold time per event, p95 in milliseconds:

| Round | Control | Current |
|---|---:|---:|
| 1 | 221.869 | 7.803 |
| 2 | 214.484 | 7.196 |
| 3 | 207.666 | 6.421 |
| 4 | 212.456 | 9.037 |
| 5 | 221.285 | 7.740 |
| 6 | 207.133 | 8.706 |

## Measurement definitions and scope

Connection hold is measured directly from successful Hikari checkout until `close()` returns. Acquisition wait is recorded separately.
The per-event total includes the current publisher's ownership read and completion transaction. It excludes event seeding, claiming, executor queue time and warmup.
Percentiles use nearest rank. Raw samples and independently recalculated statistics are retained.

The 200 ms wait is injected through a KafkaTemplate test double. This experiment measures database resource isolation. It does not establish real Kafka latency, payment TPS, user capacity, customer volume or production performance.
The publisher still waits for acknowledgement. Releasing its database connection does not remove that broker wait.
The comparison uses a reconstructed flawed transaction pattern because that intermediate implementation was not committed.
Gated probe elapsed times are not a general latency speedup: the baseline probe intentionally reaches the pool timeout before acknowledgements are released.
Timeout checks inject a transient failure, rather than waiting for the application's full 12-second deadline.
No payment processing, consumer deduplication, full seven-check failure drill, or external payment provider was exercised by this benchmark.

## Resume wording

Diagnosed HikariCP connection retention and moved Kafka waits outside Spring Boot transactions, cutting p95 database connection-hold time per event from 215.6 ms to 7.8 ms across 768 publishes with a controlled 200 ms acknowledgement delay.

Use this as a controlled benchmark result. Keep the scope if quoting the numbers.

## Reproduction and evidence

- Harness: `OutboxConnectionBenchmarkTest.java` in this directory.
- Raw data: `raw-results.json`; independently recomputed statistics: `independently-recalculated.json`.
- JUnit XML reports and Maven logs are retained. The first offline attempt stopped because its test runner dependency was missing; no benchmark measurements were taken in that attempt.
- Source hashes: `source-manifest.json`. All original snapshot files matched these hashes after the run.
- Local snapshot and compose project: `C:\Users\sankh\ebusiness-platform\.tmp-notes\outbox-benchmark-20261018`.
- `compose.yml` mounts the sibling `snapshot` folder. The evidence copy is archival; run from the original folder above, or recreate the snapshot first.
- Start only the disposable PostgreSQL service, then run the benchmark service. No host ports or application data volumes are used. The database is held in tmpfs.

Raw measurement interval: 2026-10-17T20:56:06.187662498Z to 2026-10-17T20:56:32.257008994Z.

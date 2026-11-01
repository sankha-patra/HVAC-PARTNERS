# Benchmark and drill evidence for resume claims

Each number below was measured on this application. Use it only with its scope.

## Where these folders came from

Both folders were first saved under `C:\Users\sankh\ebusiness-platform\output\benchmarks\` because the runs were launched from there. They measured this repository's `billing-service`, not the ebusiness app:

- `outbox-connection-20261018/`: all 65 files in `source-manifest.json` are byte-identical to commit `4776ab3` (17 Oct 2026, "Record full recovery verification for the revised outbox").
- `payment-capacity-20261018/`: real billing HTTP endpoints, PostgreSQL 17, Kafka 3.9.1 and the scheduled outbox publisher. Only the report and phase CSV are copied here. Full evidence (load test source, attempt logs, cleanup check) is still in the original folder.

## Claim → evidence

| Claim | Evidence | Measured | Not shown |
| --- | --- | --- | --- |
| Kafka waits moved outside DB transactions cut connection hold 96% | `outbox-connection-20261018/benchmark-report.md`, `raw-results.json` | p95 connection-hold per event 215.6 → 7.8 ms (96.38%), 768 publishes, 8 threads, 200 ms simulated Kafka acknowledgement. With 8 waits inside transactions all 8 connections were held and an unrelated query timed out | Faster event delivery (publisher p95 stays ~209 ms), real Kafka latency, production load. The publisher is now the fallback when Debezium CDC is off |
| Outbox kept events through a full Kafka outage | `../outbox-verification-2026-10-17.json` | All 3 brokers stopped, 1 payment captured, event kept, alert fired at 256 s, drained 33 s after restart, one ERP effect | Volume (one payment), Debezium (this ran the polling publisher), live provider |
| Debezium CDC survives Kafka and connector crashes | `../aws-hybrid-recovery-test-plan.md` §13 (30 Oct) | Scenarios 5 and 6 with real Razorpay test payments: Kafka down after capture, Connect killed mid-write; exactly one receipt and one email each | Three brokers (lab had one), volume |
| Payment throughput | `payment-capacity-20261018/report.md` | 2.81 payments/s sustained for 120 s, 1.87/s fixed-arrival, 783 captures, zero bad effects, one broker, 2 × 200 ms simulated provider calls | Anything per hour: "6,480 per hour" is arithmetic, not measured. Not during an outage |
| Status-read index | `../payment-timeline-benchmark-2026-10-22.md` | 111 → 405 status reads/s (3.6×), p95 −49%, 100,000 timeline rows | Checkout or payment TPS |

Not backed by any evidence in this repository: "5,000 payments in one hour during a three-broker outage", and "43 → 380 TPS per pod" / "457 → 5.8 ms HikariCP hold". The second set came from an earlier checkout study whose results were not saved.

## Rerun on current code (1 Nov 2026)

The same harness (adapted to today's `@Transactional` outbox commands; now `billing-service/src/test/.../OutboxConnectionBenchmarkTest.java`, run with `-Dbilling.benchmark=outbox`) on commit `1259f62`. Result file: `outbox-connection-20261018/rerun-2026-11-01-current-code.json`.

| Measure (per event, 384 publishes per design, 200 ms simulated Kafka acknowledgement) | Inside the transaction | Current (outside) |
| --- | --- | --- |
| p95 total connection-hold time | 346.0 ms | 115.5 ms (−67%) |
| Median total connection-hold time | 266.9 ms | 47.9 ms (−82%) |
| Connections held while 8 sends wait | 8 of 8 | 0 |
| Unrelated query during the wait | timed out at 500 ms | answered in 8–11 ms |

The pool-exhaustion result reproduces exactly. The absolute times are higher than on 17 Oct (215.6 → 7.8 ms) because two unrelated crash-looping lab containers used about 74% CPU during the run and the current publisher does more database work per event (lease checks, CDC fields). Quote the 17 Oct figures with their run, or the quiet rerun below.

Quiet rerun the same evening (two crash-looping containers stopped; `outbox-connection-20261018/rerun-2026-11-01-quiet.json`): p95 total connection-hold per event **237.8 → 70.4 ms (−70%)**, median **223.2 → 19.8 ms (−91%)**, mean 222.7 → 27.7 ms; 8 waiting sends held 8 vs 0 connections; unrelated query timed out vs answered in 2–6 ms. The remaining gap to 17 Oct's 7.8 ms is the current publisher's extra database work per event (ownership/lease checks and CDC fields).

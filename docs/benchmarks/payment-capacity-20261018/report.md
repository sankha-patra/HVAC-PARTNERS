# Local payment capacity experiment — 18 October 2026

**Qualified under the declared local conditions.** Final run `capacity-e631abd4`; outcome `LOCAL_CONDITIONS_MET`; external test exit `0`.

Conservative planning input: **1.8 completed payments/s**, rounded down to 0.1 from successful fixed-arrival completions inside its 60-second window and capped by the sustained observed rate.

## Scope and method

An unchanged application snapshot exercised real billing HTTP endpoints and bearer-token validation, PostgreSQL 17, Kafka 3.9.1, the scheduled outbox publisher and the built-in database ERP simulator. Signed test identities bypassed login, the API gateway and browser. No TLS, live Stripe/bank or external ERP was measured.

The loopback provider injected 200 ms into each of two sequential HTTP calls per normal submit (about 400 ms total). Attempt 2 retained the actual Stripe adapter's 10-second request timeout. Kafka used one broker, six partitions and replication factor one. The application, generator, provider fixture and existing containers shared one host and Docker resources.

Docker Desktop 29.6.2 reported eight logical CPUs and 7,241,068,544 bytes of RAM (6.74 GiB). The test-runner container limit was 1.75 GiB with a 768 MiB test JVM heap, PostgreSQL was capped at 512 MiB, and the test Kafka broker at 768 MiB. The pre-existing application stack remained running throughout.

Each workflow creates an intent, submits it, reads/polls status until capture and reads history. Closed-loop users allow one unfinished workflow and wait two seconds between payments. Fixture setup is excluded. Primary rates count successful workflows or HTTP responses ending inside the active window; completions after the deadline and rates including drain are retained in the CSV. Workflow completion observes capture; ERP completion is checked separately through pipeline drain and database effects.

The fixed-arrival row's 128 users is a configured pool of identities and worker slots, not 128 simultaneous payers. Raw start/end timestamp inspection found 112 distinct identities and a peak of three simultaneous workflows during that phase. The sustained eight-user phase peaked at eight simultaneous workflows. Each successful workflow normally makes four billing API requests; provider calls and Kafka events are additional internal work.

Attempt 2 warmed up for 60 seconds at one user then 30 seconds at four users. Thirty-second stages use 1/4/8/16/32/64/128 users, stopping at the first failure. The highest passing level is repeated for 120 seconds; fixed arrivals run for 60 seconds at 70% of the lower stage/repeat workflow rate (a 0.1/s floor is recorded if applied).

Targets: HTTP errors <1%; no workflow failures/timeouts; HTTP p95 <1,000 ms; workflow and scheduled-to-completion p95 <5,000 ms; pipeline drain ≤30 s; fixed-arrival drops zero and start-lag p95 <100 ms. Outbox age must be ≤10 s throughout and ≤5 s in the final half; persistent final-half growth and monitoring errors fail a phase. These are experiment thresholds, not an existing production SLA.

## Every attempt and phase

HTTP/s means completed HTTP responses; payment/s means successful workflows. Latency p95 includes all recorded completions, including those after the active window. Warm-up failures remain visible and are not capacity stages.

| Attempt / phase | Users | Seconds | Payment/s | HTTP/s | HTTP p95 ms | Workflow p95 ms | Failures / drops | Drained | Result |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| attempt-1 / warmup | 4 | 15 | 0.00 | 0.53 | 5,231.6 | 15,032.9 | 12 / 0 | no | fail |
| attempt-1 / stage-1 | 1 | 30 | 0.30 | 1.20 | 1,129.3 | 1,814.5 | 0 / 0 | yes | fail |
| attempt-2 / warmup-cold | 1 | 60 | 0.30 | 1.20 | 992.7 | 5,802.7 | 0 / 0 | yes | fail |
| attempt-2 / warmup-four | 4 | 30 | 1.40 | 5.67 | 690.4 | 1,844.8 | 0 / 0 | yes | pass |
| attempt-2 / stage-1 | 1 | 30 | 0.37 | 1.47 | 599.4 | 1,031.4 | 0 / 0 | yes | pass |
| attempt-2 / stage-4 | 4 | 30 | 1.30 | 5.23 | 923.4 | 1,699.8 | 0 / 0 | yes | pass |
| attempt-2 / stage-8 | 8 | 30 | 2.67 | 10.70 | 835.8 | 1,620.1 | 0 / 0 | yes | pass |
| attempt-2 / stage-16 | 16 | 30 | 4.10 | 16.97 | 1,435.2 | 3,606.4 | 0 / 0 | yes | fail |
| attempt-2 / sustained-8 | 8 | 120 | 2.81 | 11.25 | 724.2 | 1,294.3 | 0 / 0 | yes | pass |
| attempt-2 / fixed-arrival | 128 | 60 | 1.87 | 7.47 | 641.6 | 1,006.7 | 0 / 0 | yes | pass |

Attempt 1 retained the older fixture's 800 ms provider timeout and a 15-second, four-user warm-up. It did not qualify. Attempt 2 changes fixture timeout, warm-up and the benchmark runtime location to Docker's native filesystem; acceptance thresholds are unchanged. Its initial Windows-mounted launch was stopped during startup before measurement. See the retained attempt evidence for cold-start failures and the one-user latency failure. These setup changes are not an application performance improvement measurement.

## Validation and failure curve

- **sustained-8**: 337 successful payments inside the window, 2 after it; 2.81 payment/s versus 11.25 HTTP/s. HTTP/workflow p95 724.2/1,294.3 ms; including-drain rate 2.81 payment/s. Offered/started/dropped: 339/339/0; scheduled completion/start-lag p95 1,294.3/0.0 ms.
- **fixed-arrival**: 112 successful payments inside the window, 0 after it; 1.87 payment/s versus 7.47 HTTP/s. HTTP/workflow p95 641.6/1,006.7 ms; including-drain rate 1.86 payment/s. Offered/started/dropped: 112/112/0; scheduled completion/start-lag p95 1,007.5/11.3 ms.
- attempt-1, stage-1: HTTP p95 reached 1000ms.
- attempt-1 run failure: `AssertionError: No stage met the predeclared capacity conditions; inspect the JSON evidence`.
- attempt-2, stage-16: HTTP p95 reached 1000ms.

The first failing stage bounds this tested curve; it does not prove a specific CPU, database, provider or Kafka bottleneck. Closed-loop users self-throttle. Fixed arrivals validate only their measured offered rate.

## Financial and audit evidence

Final capture count: **783**. Database checks: bad effects `0`, unbalanced journals `0`, bad allocations `0`, duplicate ERP effects `0`; passed `True`.

For captures, the SQL checks require one simulated provider payment record, capture journal, acknowledged ERP posting, published capture event and ERP inbox effect; journal entries must balance and allocations/invoice totals must match. This evidence applies to the measured fixture and captured records. It is not a live-provider reconciliation or settlement claim.

The independent JSON audit recalculates counts, active-window rates and nearest-rank percentiles from raw timings, checks offered-work accounting and backlog trends, and compares reported values. It relies on the report for database results and some elapsed clocks; external process exit status is checked separately.

| Qualification gate | Result |
|---|---|
| Report finished | pass |
| No run failure | pass |
| Final financial correctness passed | pass |
| 120-second sustained phase passed | pass |
| 60-second fixed-arrival phase passed | pass |
| Independent audit passed | pass |
| Audit matches this run | pass |
| External test process exit code is zero | pass |
| Both warm-up phases drained | pass |
| Warm-up completed capture through ERP | pass |
| Post-warm-up completed capture through ERP | pass |
| Validation phases have positive active completions | pass |

sustained-8 ERP final-half pending: first/last/max 1/0/6; net change -1; positive drift `True`; review required `False`. Final drain alone does not establish long-term queue stability.

fixed-arrival ERP final-half pending: first/last/max 0/0/1; net change 0; positive drift `False`; review required `False`. Final drain alone does not establish long-term queue stability.

## Conditional planning estimates

Using X = 1.8 payment/s: **6,480 payments in one hour** and **51,840 in eight hours**. These are arithmetic extrapolations, not measured endurance volumes.

User equivalents = X × 3,600 × H ÷ (P × K), where H=8 business hours, P=payments per user per day and K=5 peak-to-average load:

| Payments/user/day (P) | Conditional daily user equivalents |
|---:|---:|
| 1 | 10,368 |
| 5 | 2,073 |

Assumptions: unchanged workload, provider latency, hardware, competing load, data size and service behavior. These are workload equivalents, not measured adoption or concurrent-user capacity. No multi-host scaling, proportional scaling, availability or endurance guarantee follows from this short single-host test. The separate connection-hold benchmark of 216 → 7.8 ms does not establish payment TPS.

## Reproducible evidence

- [Raw final JSON](evidence/payment-capacity-report.json), [independent audit](evidence/independently-recalculated.json), [audit program](evidence/audit_capacity.py), [external exit code](evidence/test-exit-code.txt).
- [Independent final database counts](evidence/final-database-check.txt), [test execution log](evidence/run-native.log), [test provider fixture](evidence/LocalPaymentLab.java), [native filesystem runner](evidence/run-capacity.sh).
- [Protocol](evidence/protocol.md), [measurement harness](evidence/PaymentCapacityLoadTest.java), [phase CSV](phase-summary.csv), [Docker topology](evidence/compose.yml), [source manifest](evidence/source-manifest.json).
- [attempt-1 raw JSON](evidence/attempt-1/payment-capacity-report.json), [attempt-1 protocol](evidence/attempt-1/protocol.md), [attempt-1 harness](evidence/attempt-1/PaymentCapacityLoadTest.java).

# Payment timeline optimization — interview notes

## Resume bullet

Diagnosed full-table timeline scans in the Spring Boot payment-detail API and
added a composite PostgreSQL index, raising observed reads from 111 to 405
requests/s (3.6×) and cutting p95 latency from 125 to 64 ms in local benchmarks
with 8 clients and 100,000 timeline events.

## The short explanation

“The payment-detail endpoint fetched ten timeline events, but the table had no
index on the payment ID. In a 100,000-event test dataset, PostgreSQL scanned the
whole event table for each lookup. I
added a composite index through Flyway. In paired local HTTP tests with eight
clients, the observed read rate rose from about 111 to 405 requests per second,
and p95 response latency fell from 125 to 64 ms. Both versions returned identical
response hashes. All 324 Java tests passed.”

## Numbers you can defend

- **111 → 405 requests/s:** aggregate successful payment-detail read completions across
  sixty measured seconds per version; six paired AB/BA rounds.
- **125 → 64 ms p95:** pooled end-to-end HTTP response time over those primary
  rounds. Neither a SQL timing nor a connection-hold percentile.
- **100,000:** total synthetic timeline rows across 10,000 intents. Ten events
  per intent; these fixtures do not represent captured payments.
- **31,061:** primary measured requests, including requests finishing during
  drain; zero mismatches/errors. Including the smaller sensitivity experiment:
  50,676 measured requests.
- **324:** passing Java tests, with zero failures, errors or skips. Includes a
  new real-HTTP check for latest-100 ordering and account access.
- **4.09 MB:** additional index storage for this fixture; more write/WAL work
  accompanies each timeline insert.

## Questions to expect

**Why did LIMIT 100 not help?** Only ten events matched the payment. The database
still had to prove there were no more matches by scanning the remaining table.

**Why did the existing primary key not help?** It indexed `id`, whereas the filter
was `intent_id`. The new index makes the payment's events directly addressable.

**Did the index eliminate sorting?** The retained plans used a bitmap lookup and
still sorted ten rows. The demonstrated improvement is avoiding the full-table
scan. `DESC` is not essential; B-trees can be traversed backwards.

**What exactly changed?** One additive V13 Flyway index migration. Same Java
application, responses, security rules and transaction boundaries in both arms.

**Does this demonstrate 405 captured payments per second?** The measured operation
is an authenticated payment-detail read. Creation and capture are different paths.

**Is 405 the service's maximum capacity?** It is the observed rate at eight
closed-loop clients on one shared local host. A separate arrival-rate/saturation
experiment and production validation would be needed for a capacity claim.

**How fair was the comparison?** Same application/JVM, connection pool, fixture,
client count, shuffled target ordering and response checks; only the index was
toggled. Alternating AB/BA order and all-round reporting reduce order bias.

**What weaknesses remain in the experiment?** One JVM, six primary pairs, shared
host, and load generator sharing the application's CPU quota. Early indexed rounds
were slower than later ones for an unexplained reason; all are included.

**Did it help a smaller database?** The 10,000-event run showed similar aggregate
rate (486 → 493 requests/s) and worse p95 (48 → 55 ms). Two pairs do not establish
the cause. Do not promise a gain for every database size or workload.

**Why is the API p95 larger than the SQL timing?** HTTP includes authentication,
several queries, connection acquisition, scheduling, serialization and the local
client. The retained evidence does not isolate each contributor.

**How does this relate to transactions?** Creation and cancellation build the
response inside a database transaction. The index removes a full-table timeline
scan from that path. I did not measure transaction hold time or its percentiles.
This index fix does not choose between `@Transactional` and `TransactionTemplate`.

**Did you test the creation path too?** Yes: 6,144 measured real HTTP creates, plus
warmups, with no invalid responses and correct reservations. Its observed rate
was 36.4 → 45.3 requests/s, but the paired uncertainty interval included no gain
and some pairs regressed. I use the stronger, consistent read result in the resume.

**What was the write cost?** In a separate four-pair probe after forced checkpoints,
median WAL per event append rose from about 4,251 to 9,045 bytes. Pooled insert p95
was 3.05 → 3.28 ms. Checkpoint timing and workload affect these numbers; they are
not universal production costs. The additional index occupied about 4.09 MB.

**What about deployment?** The change was evaluated locally. An ordinary index
build blocks writes during construction. Assess a migration window or verify a
concurrent/nontransactional Flyway rollout before production use.

## Evidence

See [the full benchmark report](payment-timeline-benchmark-2026-10-22.md) for every
round, retained weaker results, raw-sample audit, source hashes and Claude review.
Both invalid interrupted creation attempts are excluded. The complete guarded
creation run and independent raw-response audit are linked in the report.

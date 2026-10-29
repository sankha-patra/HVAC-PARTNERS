# Production Spring transaction boundaries and verification

Updated 25 October 2026. The earlier hybrid one-flow proof is superseded: production billing now uses named
`@Transactional` commands. There is no production `TransactionTemplate` reference or `BillingStore.tx` API.
All 51 former transaction sites are extracted, and the simulator's 4 independent write units use separate proxies.

## Actual boundaries

Unannotated payment/refund/recovery/publisher flows reject an ambient transaction or synchronization scope.
They invoke injected public, non-final command beans through Spring's proxies. Commands use
`@Transactional(transactionManager="transactionManager", propagation=REQUIRED, rollbackFor=Exception.class,
timeout=10, label="billing")`. Shared financial/audit/event and lock participants use MANDATORY plus an actual
transaction assertion. Reads do not create synchronization scopes.

Preparation commits before Stripe or Kafka I/O. Recording starts a separate transaction. When a recorder is
called by inbox or case processing it joins that transaction, keeping money, case/inbox state and outbox atomic.
Private helpers participate in the already active unit; they do not claim independently advised boundaries.

`BillingDeadlineTransactionManager` retains the remaining request/lease budget and PostgreSQL local limits only
for billing-labelled definitions, with Boot customizers preserved. Setup statements have a query timeout before
Spring installs its holder deadline. Setup failures roll back and return a clean connection. The flow owns an
admission permit through the entire proxy invocation, including a commit failure after the method body returns.

The simulator's separate `SimulatorOperationStore` uses unlabelled REQUIRES_NEW/timeout10 units. Its accepted
operation commits before a simulated lost reply is thrown, so caller rollback cannot undo external state.

## Final evidence

One clean Maven verify passed **485 tests in 31 classes**, zero failures/errors/skips, and packaged all 3 Java apps.
All 155 compiled source/build inputs match the checkout, with no missing/extra inputs. All 33 UI source files match
the earlier 80-test/build checkpoint, and all 22 migration inputs in the preserved manifest match byte-for-byte.

| Dedicated checks | Count | Meaning |
|---|---:|---|
| BillingAnnotationHarnessDatabaseTest |8| Proxy ownership, physical joins, checked rollback, deadlines, local limits, begin-failure cleanup and unlabelled limits |
| BillingTransactionalProofDatabaseTest |5| Actual production payment commands, committed preparation/connection release, atomic recording and commit-failure permit cleanup |
| BillingInboxTransactionDatabaseTest / BillingCaseTransactionDatabaseTest |2/2| Separate receipt; joined financial, case and audit effects; failure bookkeeping after rollback |
| BillingSimulatorTransactionDatabaseTest |1| Independent provider storage proxies |
| BillingTransactionExtractionDatabaseTest |9| Refunds, recovery, outbox, reconciliation, retry and consumers |
| BillingCommitAcknowledgmentDatabaseTest |2| Commit succeeds then acknowledgment is lost; persisted preparation/money must be re-read |
| BillingProcessCrashDatabaseTest |1| Actual child JVM killed after fake Session creation; same-key recovery, then a separate paid-evidence race |
| BillingTransactionPolicyTest |2| Effective attributes on production entries, proxyable methods, no flow/transport dependencies, no production template API |

These 32 checks are included in 485, not added to it. The process test distinguishes one fake Checkout Session from
one local capture journal. It is not a live-provider or whole-machine power-loss guarantee. No throughput or
fleet-capacity comparison is claimed. Eight permits remain per instance; a stalled-trial watchdog and recovery
fairness need separate work. Missed events for terminal payments need the separately specified catch-up route.

Durable logs, JUnit XML, input and artifact hashes: `.tmp-notes/annotation-migration-codex-final/`.
The full results and historical baseline are recorded in [verification.md](verification.md).

Framework sources: [Spring propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html),
[Spring 6.1.1 annotation attributes](https://docs.spring.io/spring-framework/docs/6.1.1/javadoc-api/org/springframework/transaction/annotation/Transactional.html).

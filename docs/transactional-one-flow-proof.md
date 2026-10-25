# One payment flow with real Spring transaction proxies

This proof runs the real payment service against an isolated PostgreSQL database. It uses a separate Spring bean with `@Transactional(propagation = REQUIRED, rollbackFor = Exception.class)` for the write steps.

Production payment orchestration still uses `TransactionTemplate`. The proof keeps its bounded outer transaction, so an annotated write method joins the same physical transaction and keeps the existing request deadline and PostgreSQL limits. A wider conversion has not been made.

## The boundaries

1. Start a short transaction with the remaining request time.
2. Call the annotated write bean through its Spring proxy.
3. Save the original command, key and lease, then commit.
4. Call Stripe outside a transaction, with no database connection borrowed.
5. Start another short transaction and record the result through the proxy.
6. Commit the financial changes, event and outbox record together.

The orchestrator retains `requireNoTransaction()`. Adding `NEVER` to it is not an equivalent replacement: Spring synchronization also matters to connection release.

## What the five tests establish

| Check | Evidence |
|---|---|
| Advice really runs | The separate bean is a real Spring proxy and starts a transaction when called by itself. |
| Checked exceptions roll back | A checked failure after an invoice write leaves the original version unchanged. |
| Preparation commits before Stripe | A separate connection sees the saved attempt during the provider call; the Hikari pool has zero borrowed connections. Preparation and recording use different transaction IDs. |
| Recording is atomic | A failure after capture effects rolls back the journal, outbox, allocation and payment changes. Later reconciliation records the capture once. |
| Existing limits survive | A short request budget shortens the physical transaction deadline and the PostgreSQL statement/lock settings. |

Tests: `BillingTransactionalProofDatabaseTest`. This is a compatibility proof for one flow, not evidence that changing an annotation alone improves throughput or replaces provider recovery.

## Why the outer bounded transaction remains

Spring `REQUIRED` joins an existing transaction. Its inner timeout does not replace the outer transaction's settings. A static annotation is also not the same thing as today's per-request remaining-time calculation. Keeping the current outer boundary makes those protections explicit while testing annotation-based write steps.

Sources: [Spring propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html), [Spring 6.1.1 Transactional attributes](https://docs.spring.io/spring-framework/docs/6.1.1/javadoc-api/org/springframework/transaction/annotation/Transactional.html).

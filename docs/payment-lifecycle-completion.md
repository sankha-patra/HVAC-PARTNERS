# Second repair: refunds, case resolution and abandoned payments

The first repair made payment submission crash-safe. Three gaps remained in the rest of the
lifecycle. This change closes them with the same rules: save the request before provider
I/O, never treat a timeout as failure, never delete evidence, and let no single employee
move money alone.

## What was broken and what changed

| Gap | Before | Now |
| --- | --- | --- |
| Refund with no provider ID | Recovery could only look refunds up by ID. A refund whose response was lost stayed UNKNOWN until a webhook arrived, and forever if Stripe never received it. No backoff, no case, no alert | The exact refund request, its provider format and its age are saved before the call. Recovery replays it with the same idempotency key inside the 23-hour window, then reads the current state. Retries back off (10 s doubling to about 21 min); after five unresolved attempts a `REFUND_UNRESOLVED` case opens; past the window a `REFUND_REPLAY_UNSAFE` case opens instead of another POST. `billing_refunds_stuck` and the `BillingRefundStuck` alert report overdue refunds |
| Cases could only be viewed | `cases.status` could never become RESOLVED | Staff resolve cases through recorded actions (below). Provider evidence that ends the uncertainty resolves the case automatically; if the problem returns, the case reopens |
| Abandoned unsubmitted payments | A CREATED intent held its invoice reservation until the customer cancelled | A worker cancels CREATED intents after 30 minutes (`billing.unsubmitted-ttl-minutes`) and releases the reservation. Row locks make this race-safe with submit and cancel |

## How a case is resolved

Nothing in the workflow can mark a payment paid. There are three ways out, and each writes
an entry to `billing.case_actions`, which the database refuses to update or delete.

| Action | Who | What happens |
| --- | --- | --- |
| Attach provider reference | FINANCE or OPS | The server asks the provider whether that reference belongs to this exact payment or refund. Only a verified answer is recorded, through the normal path: capture once, journal once. An unverified reference changes nothing |
| Request, then approve, a release | Request: FINANCE or OPS. Approve: REFUND_APPROVER, and never the requester | For a payment the provider never received: reservations are released and the payment becomes FAILED. For a refund the provider never created: its amount becomes available again. Late provider evidence is still recorded; a late capture becomes unapplied cash with its own case, never another invoice's money |
| Close after review | FINANCE or OPS, with a note | Only for cases that describe something already settled (for example, a late capture booked as unapplied cash). Refused while the payment or refund is still unresolved |

Unknown cases and cases of another merchant both return 404.

## Where to read the code

- `BillingRecovery.recoverRefund`: owned claim, saved-request replay, backoff, escalation.
- `BillingRefundService.submitApproved`: the saved refund request (`V12` adds the columns and an immutability trigger).
- `BillingCaseService`: the three resolution paths, role checks and lock order (payment, then refund, then case).
- `BillingService.expireUnsubmitted`: expiry under `FOR UPDATE SKIP LOCKED`.
- `BillingStore.escalate` and `resolveCases`: reopenable stuck cases and audited resolution.

## What the tests establish

- A refund that crashes before the network, or loses its response, is completed by replaying the saved request, with exactly one provider refund.
- Past the replay window, a refund escalates and its amount stays committed until resolved.
- A stale recovery claim cannot call the provider.
- A wrong provider reference changes nothing; a verified one captures once.
- A release needs a second, different approver; late evidence after a release cannot take the invoice from a newer payment.
- Stuck cases cannot be closed without resolving them; audit history cannot be changed.
- Expiry and submit racing for the same intent always leave either no provider operation and no reservation, or one operation with its reservation.

Simulated provider, bank and ERP; no live Stripe call yet. See `docs/stripe-test-mode-runbook.md`.

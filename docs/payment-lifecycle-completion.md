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

Staff cannot declare a payment paid without verified provider evidence. There are three ways out, and each writes
an entry to `billing.case_actions`, which the database refuses to update or delete.

| Action | Who | What happens |
| --- | --- | --- |
| Attach provider reference | FINANCE | The server asks the provider whether that reference belongs to this exact payment or refund. Only a verified answer is recorded, through the normal path: capture once, journal once. An unverified reference changes nothing |
| Request, then approve, a release | Request: FINANCE. Approve: REFUND_APPROVER, and never the requester | Before releasing, the provider must prove nothing exists: an open Checkout Session is expired first, and a refund is looked up on the original payment. An empty search is never proof: it stays UNKNOWN and releases nothing. A refund that recovery can still replay cannot be released. If the provider has the operation, its status is recorded and nothing is released. For a payment the provider never received: reservations are released and the payment becomes FAILED. For a refund the provider never created: the refund becomes RELEASED (not FAILED) and its amount becomes available again. Late provider evidence is still recorded; a late capture becomes unapplied cash with its own case, never another invoice's money |
| Release on a recorded Stripe confirmation | Record: FINANCE. Approve: a different REFUND_APPROVER | Only when Stripe's own search found nothing, or could not search the original account because the credentials changed. FINANCE records what Stripe showed in the Dashboard or support: source, original account, what was searched, the result, a support ticket if any, when it was checked and a SHA-256 of the saved evidence. A payment qualifies only 48 hours after it was first sent (the last resend is at 23 hours and a Checkout page expires 24 hours later). The check must be newer than 7 days and contain no card or bank account numbers. At approval the app checks Stripe again: if Stripe now finds the operation, its evidence is recorded and nothing is released; if it gives no answer, nothing is released. The resolution is `RELEASED_EXTERNAL_CONFIRMATION`. A capture that appears later still becomes unapplied cash with its own case |
| Close after review | FINANCE, with a note | Only for cases that describe something already settled (for example, a late capture booked as unapplied cash). Refused while the payment or refund is still unresolved |

Unknown cases and cases of another merchant both return 404.

**Who decides.** FINANCE and REFUND_APPROVER are the client's (Carrier's) finance staff and make every decision.
OPS is the application team, a service provider: it can read cases and staff-only provider evidence, add notes
(`ADDED_NOTE`, which never changes money or status) and replay a failed technical message, but it cannot verify,
request, approve, reject or close anything. Carrier's identity team assigns every role, including the application
team's own accounts.

## Recovery validation and refund review repair

- A manually supplied reference now uses a separate verification operation. Providers that
  do not implement it refuse the reference by default.
- Payment verification compares the local amount, currency, intent, operation key and payment method.
  Refund verification also compares the refund ID and its original provider payment.
- Stripe links the refund's PaymentIntent to the saved original Checkout Session using GET.
  Manual verification never submits another payment or refund.
- Mismatched, unverified or unsupported evidence leaves the case open without financial or audit changes.
  A refund that still requires action cannot close its case as if the uncertainty were resolved.
- Refund `REVIEW` and unrecognised result states are rejected before terminal-state shortcuts.
  Webhook processing rolls back, retains the event as `UNMATCHED`, and creates one review case
  per event. Reprocessing the event does not change the refund, money or reserved capacity.
- Stripe does not deliver events in order, so arrival order cannot say which of two
  contradictory refund results is current. A "succeeded" event for a refund recorded as FAILED
  posts no money, opens a `REFUND_EVIDENCE_CONFLICT` case and holds further refunds on that
  payment until a REFUND_APPROVER verifies the original refund with Stripe. A refund the
  provider confirms FAILED opens a `REFUND_FAILED` case for finance. A staff release is stored as RELEASED
  (`V14`), so a late provider success for a released refund is still recorded.
- Existing transaction boundaries remain: provider calls happen outside a transaction;
  accepted evidence, financial effects and case audit commit together. Caller transactions
  are rejected before provider I/O. Named proxied `@Transactional` commands now own the production units; see `transactional-one-flow-proof.md` for the completed migration and verification.

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
- A refund failure processed before an older success event stays FAILED, with no journal, refund amount or ERP event.
- Stuck cases cannot be closed without resolving them; audit history cannot be changed.
- Expiry and submit racing for the same intent always leave either no provider operation and no reservation, or one operation with its reservation.

Simulated provider, bank and ERP; no live Stripe call yet. See `docs/stripe-test-mode-runbook.md`.

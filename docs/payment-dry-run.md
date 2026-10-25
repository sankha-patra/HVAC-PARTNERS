# Payment dry run: one $100 invoice, step by step

Every number below was worked out by hand first, then checked by a test that runs the real code against a real
PostgreSQL database: `BillingFinancialDatabaseTest`, tests `dryRun1` to `dryRun9`. Each test step prints the actual
rows (log lines starting `DRYRUN|`). All 9 tests and all 27 steps pass (25 Oct 2026).

The tests use the built-in simulator in place of Stripe. With real Stripe, step "payer presses Pay" shows
`REQUIRES_ACTION` (the payer still has to finish Stripe's page) instead of the simulator's `PROCESSING`.

## The tables, in plain words

There is **one row per payment, in one table**, and it is never deleted or moved. Its `status` changes in place.
There is no history table and no second table for "real" payments. The other tables hang off that one row:

| Table | One row means | Columns that matter here |
|---|---|---|
| `invoices` | One invoice | `total`, `paid`, `held` (column `reserved_minor`) |
| `intents` | One payment (our order ID) | `status`, Stripe's ID (`provider_id`), the busy sign (`lease_until`) |
| `allocations` | "This payment holds this much of this invoice" | `state`: RESERVED (held), POSTED (paid), RELEASED (let go) |
| `attempts` | The exact request we will send to Stripe, with its idempotency key, saved **before** sending | one per payment, at most |
| `timeline` | A diary line: what happened and when | never changes |
| `journals` | The money record: one per captured payment | never changes, can't be added twice |
| `provider_operations` | Test only: what the simulated Stripe knows | one per Stripe payment |

**Left to pay** = total − paid − held. This is what the app checks. Payers see one number instead, **Amount due**
(total − paid), with a **Payment in progress** badge while money is held.

**"Held"** means the invoice money is set aside for one payment, so a second payment can't pay the same invoice at
the same time. **"Released" (freed)** means that hold is taken off: `held` goes back to 0, so left to pay is $100
again and the payer can start a new payment. The old payment row stays, marked CANCELLED or FAILED.

**The busy sign (lease)** is `lease_until` on the payment row: "a worker is handling this until 10:02". Nobody else
touches the payment until then. If the worker crashes, the sign simply runs out and another worker takes over.

## The nine scenarios (real values from the database)

Money in dollars. "At Stripe" = payments Stripe knows about. "Saved requests" = rows in `attempts`.

### 1. Everything works

| Step | Payment rows | Status | Stripe ID | Held money | Paid | Held | Left | Saved requests | At Stripe | Money records |
|---|---|---|---|---|---|---|---|---|---|---|
| Before anything | 0 | — | — | — | 0 | 0 | 100 | 0 | 0 | 0 |
| Payer presses Review payment | 1 | CREATED | — | RESERVED | 0 | 100 | 0 | 0 | 0 | 0 |
| Payer presses Pay: request saved, then sent | 1 | PROCESSING | saved | RESERVED | 0 | 100 | 0 | 1 | 1 | 0 |
| Payer paid; we record it | 1 | CAPTURED | saved | POSTED | 100 | 0 | 0 | 1 | 1 | 1 |
| The same news arrives again | 1 | CAPTURED | saved | POSTED | 100 | 0 | 0 | 1 | 1 | **1** |

The repeat changes nothing: still one money record, still $100 paid, not $200.

### 2. Crash in the middle of saving the payment

| Step | Payment rows | Status | Held | Left |
|---|---|---|---|---|
| Save fails halfway | **0** | — | 0 | 100 |
| Payer tries again (same key) | 1 | CREATED | 100 | 0 |

A database save is all or nothing: half a save is undone completely, so there is nothing to clean up.

### 3. Payer presses Review payment, then leaves

| Step | Payment rows | Status | Held money | Held | Left |
|---|---|---|---|---|---|
| Review payment, then leaves | 1 | CREATED | RESERVED | 100 | 0 |
| After 30 minutes | 1 | **CANCELLED** | RELEASED | 0 | **100** |
| Payer comes back, starts again | **2** | CREATED (the new one) | RESERVED | 100 | 0 |

Nothing was ever sent to Stripe, so cancelling is safe. The old row stays as history.

### 4. Server dies after saving the request, before asking Stripe

| Step | Status | Stripe ID | Busy sign | Held | Saved requests | At Stripe |
|---|---|---|---|---|---|---|
| Request saved, server dies | SUBMITTING | — | set | 100 | 1 | **0** |
| Payer presses Pay again, or starts a new payment | SUBMITTING | — | set | 100 | 1 | 0 |
| Busy sign still valid: no worker touches it | SUBMITTING | — | set | 100 | 1 | 0 |
| Sign runs out; one worker sends the saved request once | PROCESSING | saved | — | 100 | 1 | **1** |
| Payer paid; we record it | CAPTURED | saved | — | 0 (paid 100) | 1 | 1 |

Pressing Pay again returns the same payment. A new payment for the same invoice is refused (HTTP 409), because
left to pay is 0. Only one worker can take the payment when the sign runs out; a second one gets nothing.
This payment is **not** cancelled after 30 minutes: the request was saved, so Stripe might have it.

### 5. Stripe got the request but the reply was lost (timeout)

| Step | Status | Stripe ID | Held | At Stripe | Money records |
|---|---|---|---|---|---|
| Stripe has it, we only saw a timeout | **UNKNOWN** | — | 100 | 1 | 0 |
| Payer presses Pay again | UNKNOWN | — | 100 | **1** (nothing sent) | 0 |
| Worker asks again with the same key | CAPTURED | saved | 0 (paid 100) | **1** (same payment) | 1 |

A timeout is never treated as a failure. The worker sends the **same key**, so Stripe returns the payment it
already has instead of making a second one.

### 6. Server dies while saving Stripe's answer

| Step | Status | Held | Paid | Money records |
|---|---|---|---|---|
| Stripe says paid; our save fails and is undone | PROCESSING | 100 | 0 | 0 |
| Next worker run saves it | CAPTURED | 0 | 100 | **1** |

### 7. The payment fails

| Step | Payment rows | Status | Held money | Held | Left |
|---|---|---|---|---|---|
| Stripe confirms it failed | 1 | FAILED | RELEASED | 0 | **100** |
| Payer tries again | **2** | CREATED (the new one) | RESERVED | 100 | 0 |

A payment is marked FAILED only when Stripe confirms the failure, never because of a timeout.

When the payer presses **Try again** on the failed payment, the new payment row keeps the **same payment
reference** (the first payment's id), so the payer quotes one reference however many tries it takes. It covers
the same invoices and amount, and the payer chooses card or bank again. It only prepares the payment: the payer
still checks the amount and presses Pay. The failed row is never changed or reused, and keeps its own Stripe
request, so any late word from Stripe about it is still recorded against it.

Two kinds of "again", not to be confused:

- **Same request sent again** (a lost reply, a reload, a double click): the browser resends the saved request with
  the same key, and the server answers with the same payment. No second payment is made.
- **Try again after a failure**: a new payment with a new key and its own Stripe request, under the same reference.

Try again is refused (nothing is created) while any try under the reference is not FAILED, after any try was
paid, after Carrier finance closed a payment without Stripe confirming it failed, if the payment was already tried
again, or if the invoices' amount due has changed. If an earlier try is found to be paid after a new try was
prepared, Pay on the new try is refused and the payer is told; they can cancel the unsent try.

### 8. Payments are very busy, or Stripe keeps failing

| Step | Status | Held | Saved requests | At Stripe |
|---|---|---|---|---|
| Pay refused before anything is saved | CREATED | 100 | 0 | 0 |
| Payer presses Pay again a minute later | PROCESSING | 100 | 1 | 1 |

The payer sees: "We're very busy right now. Nothing was charged. Please try again in a minute." That sentence is
always true, because the refusal happens before anything is saved or sent.

- **Busy:** all 8 slots for new Stripe payments on this server are in use for more than 100 ms.
- **Failing:** 5 Stripe requests in a row timed out, failed on Stripe's side or were throttled, with no new payment
  page created in between. Checks of saved payments that work, and a refused key, do not break the run. New payments
  then pause for 30 seconds, longer while failures continue. After the pause one trial payment is let through: if
  Stripe creates its page, payments resume; if the trial fails, they pause again. Only a created page ends the pause.
- **Pressed twice:** a second Pay click on a payment that is already being sent is shown that payment, never the
  busy message, because the busy check happens while the payment is locked and still new.
- Payments already on their way are never refused: they are still checked, and that is how Stripe's recovery is noticed.

### 9. The payer opens Stripe's payment page, then leaves

| Step | Status | Held money | Held | Left |
|---|---|---|---|---|
| Payer presses Pay: Stripe opens the page | REQUIRES_ACTION | RESERVED | 100 | 0 |
| After 10 minutes: still open, next check set for the 30-minute mark | REQUIRES_ACTION | RESERVED | 100 | 0 |
| After 31 minutes: page closed unpaid, invoice free | FAILED | RELEASED | 0 | **100** |

Stripe keeps an unused page open for 24 hours by default. The app closes it 30 minutes after it opened, with
Stripe's expire step, and a page that is expired and unpaid is a failed payment, so the invoice is free again.
If the payer paid at the last moment, the expire step reports the payment instead and it is recorded as paid. If
Stripe cannot confirm the close, the page keeps waiting and is tried again. When Stripe itself says a page expired
(`checkout.session.expired`), the invoice is freed at once.

The page is closed by the app rather than by Stripe's own `expires_at` setting on purpose: that setting is part of
the saved request, and Stripe accepts it only if it is at least 30 minutes in the future. A request retried more than
a few minutes late, after an outage, could then never open its page.

## What the payer sees while waiting

The payer page checks the payment by itself, so nobody is asked to refresh: every 3 seconds for the first minute,
then every 15 seconds, for at most ten minutes. It reloads invoices only when the status changes.

| Situation | Message |
|---|---|
| Being confirmed, first minute | Processing your payment. Please don't close this page or pay again. |
| Being confirmed, after a minute | This is taking longer than usual. You can close this page safely; the result will appear in your recent payments. Please don't pay again. |
| On Stripe's payment page | Finish paying on the secure payment page. This page will update by itself when you're done. |
| Paid | Payment successful. Thank you. |
| Busy or Stripe failing | We're very busy right now. Nothing was charged. Please try again in a minute. |

## The rule behind all nine

1. Save first, then call Stripe. The saved request has one key, made from our payment ID, and it never changes.
2. Never delete a payment row. Its status changes; the row stays.
3. A crash during a save undoes that save completely.
4. Not sure what happened? The status is UNKNOWN, never FAILED. A worker asks Stripe again with the same key.
5. Release the held invoice money only when we know nothing was charged: never sent (scenario 3) or a confirmed
   failure (scenario 7).
6. The busy sign stops two workers from handling the same payment, and runs out by itself if a worker dies.
7. Refuse a new payment only before anything is saved, so "nothing was charged" is always true (scenario 8).
8. Never hold an invoice for an unused payment page longer than about 30 minutes (scenario 9).

## The four videos against this logic

All four were transcribed in full (YouTube's automatic captions, about 9,700 words) and read end to end.

| Video | What it says | In our app |
|---|---|---|
| Design a Payment System (Code with Lucian, 32 min) | Save the payment event first, then call the provider; use the provider's own payment page; a timeout means "we don't know", and marking it failed invites a double charge; the idempotency key is usually the payment order ID, enforced by a unique database constraint; a repeat returns the latest status; retry with exponential backoff and jitter; dead-letter queue for messages that always fail; Kafka so events are not lost; reconciliation | All present. Difference: for two identical requests at the same moment it suggests answering the second with 429; ours makes the second wait, then returns the same payment |
| Idempotency and Intelligent Retry, PayPal (ByteMonk, 10 min) | The key is stored with the result; a repeat returns the stored result; retries reuse the same key; exponential backoff with a maximum number of tries; keys expire after about 24 hours (Stripe removes them after 24 hours), after which a repeat counts as new | Same. Because of the 24-hour expiry we stop resending at 23 hours and check by ID or search instead (test `replayAtOrAfterTwentyThreeHoursNeverPosts`) |
| Retry with Backoff in Java (Jayasekhar K, 18 min) | Retry only temporary failures; never retry a decline; backoff plus jitter; "charged but reply lost" looks exactly like a timeout; the fix is a key made from the order number only, built once outside the retry; the far end must keep the record | Same: the key is made from the payment ID and saved before the first call (scenarios 4 and 5) |
| Why Payment Webhooks Fail (The Java Loom, 9 min) | Signature checks plus a timestamp to stop replayed messages; idempotency key; circuit breaker; backoff with jitter; saga with compensating steps | Signature checked with a 5-minute window; each Stripe event stored once by its ID. A simple circuit breaker: after 5 Stripe failures in a row new payments pause before anything is saved, and one trial payment decides when to resume (scenario 8). No saga: one provider, and a refund (approved by Carrier) is the compensation |

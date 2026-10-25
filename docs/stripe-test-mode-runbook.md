# Stripe test-mode run: one real payment, end to end

Goal: replace "simulated provider only" with evidence from one real Stripe **test-mode**
payment, refund and recovery drill through the complete application. No real money moves.
The adapter refuses live keys (`sk_live_…`); only `sk_test_…` keys are accepted.

## Already checked without a Stripe account (21 Oct 2026)

The Checkout request the adapter sends was compared with Stripe's API reference for the
pinned version `2026-09-30.endive`:

| Request field | Status in that API version |
| --- | --- |
| `ui_mode=hosted_page` | Valid; it is the default hosted page |
| `success_url`, `cancel_url` | Allowed with `hosted_page` |
| `allowed_payment_method_types[0]` = `card` / `us_bank_account` | Replaces `payment_method_types`, which now returns HTTP 400 |
| `client_reference_id`, `metadata`, `payment_intent_data[metadata]`, `line_items[0][price_data]` | Valid |
| Session expiry | Not sent, so Stripe's default applies: 24 hours |

Reference: Stripe API, *Create a Checkout Session* (`api-version=2026-09-30.endive`) and the
changelog entry *Removes the payment method types parameter from Checkout Sessions*.

This is a document check, not a live call. The steps below are the live proof.

## Before you start

- **A Stripe account with test mode.** Stripe accounts are invite-only in India, so a new
  Indian business cannot sign up on the website. Use an existing test account (for example,
  your company's sandbox) or request access through Stripe. Never use live keys here.
- The **Stripe CLI**, logged in to that account (`stripe login`).
- Docker Desktop running, and this repository on your machine.

## 1. Forward signed webhooks

```powershell
stripe listen --forward-to http://localhost:4400/api/v1/billing/webhooks/stripe `
  --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,refund.created,refund.updated,refund.failed
```

Copy the `whsec_…` signing secret it prints. Leave this window open.

`checkout.session.expired` frees the invoice as soon as a payment page closes unused. The app closes a page itself
30 minutes after Stripe opened it (Stripe's own default is 24 hours), so an abandoned payment holds the invoice for
about half an hour, not a day.

## 2. Configure the provider

Edit the ignored `.env.billing.local` (created by `scripts/start-billing.ps1`):

```
BILLING_PROVIDER=STRIPE
BILLING_STRIPE_SECRET_KEY=sk_test_…
BILLING_STRIPE_WEBHOOK_SECRET=whsec_…
```

Never commit this file. Then start or rebuild the stack:

```powershell
./scripts/start-billing.ps1
```

## 3. Pay one invoice

1. Open http://localhost:4400 and sign in as `demo / demo123`.
2. Select one invoice, choose **Card**, create the payment, then submit it.
3. You are redirected to Stripe's hosted page. Pay with test card `4242 4242 4242 4242`,
   any future expiry, any CVC.
4. Back in the app, refresh the payment until it shows **CAPTURED**.

Check:

- The payment timeline shows SUBMITTING, then CAPTURED; the invoice shows PAID.
- The `stripe listen` window shows the webhook answered with HTTP 202.
- The timeline later shows `ERP_ACKNOWLEDGED` (outbox → Kafka → ERP simulator).
- In the Stripe dashboard, the Checkout Session metadata holds the same `intentId`.

## 4. Refund it with two people

1. Sign in as `admin / admin123` (finance). Request a partial refund with a reason.
2. Sign in as `reviewer / reviewer123` (approver). Approve it. The requester cannot.
3. Refresh until the refund shows **REFUNDED**; the invoice shows the refunded amount open again.

## 5. Recovery drills

| Drill | How | Expected |
| --- | --- | --- |
| Webhooks lost | Stop `stripe listen`, pay another invoice, wait up to five minutes | The recovery worker reads the session and records CAPTURED without any webhook |
| Card declined | Pay with `4000 0000 0000 0002` | Stripe shows the decline; the payment waits (REQUIRES_ACTION); nothing is captured |
| Customer abandons | Open Checkout, close the tab | The payment waits; after the session expires (24 h) recovery records FAILED and releases the invoice |
| Unsubmitted intent | Create a payment, do not submit, wait 30 minutes | The intent becomes CANCELLED with an EXPIRED timeline entry; the invoice is free again |

## 6. Record the evidence

Add a dated entry to `docs/verification.md` with: the Stripe API version, the Checkout
Session and refund IDs (`cs_test_…`, `re_…`), what each step showed, and which drills
passed. Do not record keys, webhook secrets or customer data.

## Return to the simulator

Set `BILLING_PROVIDER=SIMULATOR` in `.env.billing.local` and run `./scripts/start-billing.ps1`.

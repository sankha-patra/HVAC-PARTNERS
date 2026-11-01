# Razorpay test-mode integration

This adapter uses Razorpay Orders for INR card checkout. The immutable financial intent retains its own identity;
`provider_id` is the Razorpay **order** ID. A customer's failed payment attempt leaves that order payable.
It does not mean that the local intent failed, and does not release its invoice or stock reservation.

The initial owner saves a request in one local transaction, sends its create request outside SQL, and records the
answer in another short transaction. Recovery never creates a replacement Razorpay order. It discovers by receipt,
filters exact receipt/intent notes/operation key/amount/currency, and verifies the matching order directly. Zero,
multiple, mismatched or truncated discoveries remain uncertain. The Orders receipt filter is a substring search.

The provider's `paid` order state is checked against its separately fetched captured payment. A reference without
the saved local amount/currency is insufficient. The adapter requires the original saved request on every recovery.
Ordinary Orders have no implemented Checkout Session expiry action. Stripe replay windows, ACH returns and page
expiry rules are not applied to this adapter.

## Runtime configuration

Select `BILLING_PROVIDER=RAZORPAY` and provide `BILLING_RAZORPAY_KEY_ID` and `BILLING_RAZORPAY_KEY_SECRET` through
private local environment configuration. Only `rzp_test_` credentials are accepted. The public checkout key is
returned to an authorized payer; the secret is never part of that response. Existing simulator mode remains
available for deterministic failure tests. Existing USD invoice examples remain separate from the INR catalog.

For webhook ingestion, configure `BILLING_RAZORPAY_WEBHOOK_SECRET` and `BILLING_RAZORPAY_ACCOUNT_ID` for the expected
merchant. The account scope is mandatory when a signing secret is configured. The test Dashboard must deliver to
`POST /api/v1/billing/webhooks/razorpay` through an explicitly configured HTTPS ingress. A private laptop-to-Lambda
database tunnel does not provide a public Razorpay webhook endpoint.

Raw request bytes are authenticated using `X-Razorpay-Signature`; the durable inbox uses `X-Razorpay-Event-Id`.
Changing body whitespace invalidates the signature. Payment notes supplied from Checkout are not trusted to select
a local intent. The verified provider order ID must match exactly one persisted local mapping. An early webhook
waits durably until mapping is available. Redelivery and out-of-order failures cannot duplicate or undo a capture.
The original `pay_` identity is also retained. An unexpected additional distinct capture is booked to unapplied
cash with a review case and cannot fulfil or pay the invoice again.

The browser's success callback requests `POST /api/v1/billing/intents/{id}/reconcile`; it never marks money received.
The server performs a read-only provider lookup and records verified capture. Uncertain browser-triggered reads
cannot clear a recovery worker's live lease.

## Refunds

Refunds require the existing independent finance approval. The adapter verifies the original order and captured
payment, submits a normal refund with the saved amount, receipt and `X-Refund-Idempotency` using the refund UUID.
Its recovery remains read-only. A refund receipt alone does not prove the original parent payment: the inbox
orchestrator verifies the approved saved request and parent through the API outside SQL, then rechecks every local
identity while committing the refund and inbox result together. A processed refund maps to the core's `REFUNDED`.
Refunds preserve order history and never automatically restock products.

## Evidence boundaries

`RazorpayBillingProviderTest` and `RazorpayWebhookVerifierTest` use controlled HTTP data/signatures.
`BillingRazorpayInboxDatabaseTest` uses actual isolated PostgreSQL with controlled provider results.
These are distinct from a real test-mode Checkout payment, a provider-origin webhook, a real test refund and a
managed AWS workflow execution. Passing them does not establish those external outcomes.

## Public provider contracts

- [Create order and order states](https://github.com/razorpay/markdown-docs/blob/master/api/orders/create.md)
- [Fetch all orders and receipt filtering](https://razorpay.com/docs/api/orders/fetch-all/)
- [Normal refunds](https://razorpay.com/docs/api/refunds/create-normal/)
- [Refund idempotency](https://github.com/razorpay/markdown-docs/blob/master/api/refunds/normal-refunds-idempotent.md)
- [Webhook signature validation](https://razorpay.com/docs/webhooks/validate-test/)

## Webhooks in the hybrid lab (1 Nov 2026)

Razorpay test webhooks reach the laptop through Tailscale Funnel, which publishes exactly one path:

```
tailscale funnel --bg --set-path /api/v1/billing/webhooks/razorpay http://127.0.0.1:28380/api/v1/billing/webhooks/razorpay
```

- Public URL: `https://<laptop>.<tailnet>.ts.net/api/v1/billing/webhooks/razorpay` (through the gateway, which lets only this POST through unauthenticated). Every other path returns 404 from the public side; PostgreSQL's Tailscale serve stays tailnet-only.
- The app needs `BILLING_RAZORPAY_WEBHOOK_SECRET` (the secret entered in the Razorpay Dashboard webhook) and `BILLING_RAZORPAY_ACCOUNT_ID` (`acc_…`); events from another account or with a wrong signature are rejected with 400. Supported events: `payment.authorized`, `payment.captured`, `payment.failed`, `order.paid`, `refund.created`, `refund.processed`, `refund.failed`; others are accepted and ignored.
- Webhooks are the fast path; recovery polling (Step Functions) still confirms anything a webhook misses.
- Verified 1 Nov through the public relay: wrong signature 400, other account 400, valid signature 202.
- Rejected webhooks log `Razorpay webhook rejected stage=<HEADERS|SIGNATURE|ENVELOPE|ACCOUNT|PAYLOAD>` and raise `WEBHOOK_REJECTED` (at most once per stage per 15 minutes; emailed by the hybrid forwarder). On 1 Nov this found that the Dashboard had saved the whole `KEY=value` line as the secret; the app's secret was matched to it, Razorpay's retries were then accepted, and a fresh ₹1 payment was captured from the webhook about 4 s after paying.

/**
 * Provider boundary for the invoice billing reference application.
 *
 * <p>The simulator commits its own durable operations independently of application transactions.
 * A timeout does not mean failure. Stripe uses a stable operation key, performs no automatic
 * POST retry, and cannot recover an unknown Checkout Session/refund ID by GET. Such outcomes
 * require a verified webhook or manual provider reconciliation; creating a replacement operation
 * could charge or refund twice.
 *
 * <p>Stripe is deliberately TEST MODE only. It uses hosted Checkout for CARD/ACH, API version
 * 2026-09-30.endive, and USD. The merchant must enable the requested methods in its test account.
 * Configure snapshot webhooks for Checkout Session completion/async success/async failure and
 * refund created/updated/failed using the same API version. Forward the unmodified body bytes
 * and Stripe-Signature header to the verifier. The inbox owns durable event replay detection;
 * the five-minute signature tolerance is not an exactly-once guarantee.
 *
 * <p>CAPTURED is a provider payment result, not bank settlement. A signed dispute or
 * uncorrelated/contradictory financial event is REVIEW evidence, never an invented ACH return.
 * A refund may fail after being reported succeeded; the domain must preserve the prior journal
 * and record an exception/compensating event instead of erasing history.
 *
 * @see <a href="https://docs.stripe.com/changelog/endive/2026-09-30/remove-payment-method-types-checkout-sessions">
 *     Stripe Endive Checkout change</a>
 * @see <a href="https://docs.stripe.com/api/payment_intents/object">Stripe payment evidence</a>
 * @see <a href="https://docs.stripe.com/api/refunds/create">Stripe refunds</a>
 */
package com.invoicepayments.billing.provider;

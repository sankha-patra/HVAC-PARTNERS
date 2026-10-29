-- Additive provider/currency support; historical financial rows and migrations remain unchanged.
ALTER TABLE billing.invoices DROP CONSTRAINT invoices_currency_check;
ALTER TABLE billing.invoices ADD CONSTRAINT invoices_currency_check CHECK(currency IN ('USD','INR'));
ALTER TABLE billing.intents DROP CONSTRAINT intents_currency_check;
ALTER TABLE billing.intents ADD CONSTRAINT intents_currency_check CHECK(currency IN ('USD','INR'));
ALTER TABLE billing.intents DROP CONSTRAINT intents_provider_check;
ALTER TABLE billing.intents ADD CONSTRAINT intents_provider_check CHECK(provider IN ('SIMULATOR','STRIPE','RAZORPAY'));
ALTER TABLE billing.journals DROP CONSTRAINT journals_currency_check;
ALTER TABLE billing.journals ADD CONSTRAINT journals_currency_check CHECK(currency IN ('USD','INR'));
ALTER TABLE billing.settlement_reports ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency IN ('USD','INR'));
CREATE TABLE billing.provider_payment_evidence (
 provider TEXT NOT NULL, payment_id TEXT NOT NULL, intent_id UUID NOT NULL REFERENCES billing.intents(id),
 provider_order_id TEXT NOT NULL, amount_minor BIGINT NOT NULL CHECK(amount_minor>0), currency CHAR(3) NOT NULL,
 primary_capture BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(provider,payment_id)
);
CREATE INDEX provider_payment_evidence_intent ON billing.provider_payment_evidence(intent_id);
CREATE UNIQUE INDEX one_primary_capture_identity ON billing.provider_payment_evidence(intent_id) WHERE primary_capture;

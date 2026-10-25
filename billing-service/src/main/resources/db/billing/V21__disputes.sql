-- Bank disputes and inquiries reported by the payment provider. They are never money evidence: Carrier finance answers
-- each one in the provider's dashboard before evidence_due_by. The app keeps the deadline and stage so finance is warned
-- in time. Details the provider sent that could not be read stay NULL; last_event_at is the provider's own event time.
-- status_unclear marks messages that could not be ordered (no time, or two different results in the same second).
CREATE TABLE billing.disputes (
 provider TEXT NOT NULL, id TEXT NOT NULL, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 payment_reference TEXT, charge_reference TEXT,
 amount_minor BIGINT CHECK (amount_minor>=0), currency TEXT CHECK (currency ~ '^[A-Z]{3}$'),
 reason TEXT, status TEXT NOT NULL, status_unclear BOOLEAN NOT NULL DEFAULT FALSE, evidence_due_by TIMESTAMPTZ,
 last_event_id TEXT NOT NULL, last_event_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY (provider,id)
);
CREATE INDEX billing_disputes_due ON billing.disputes(evidence_due_by) WHERE status IN ('needs_response','warning_needs_response');

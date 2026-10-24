-- Protected operational evidence, never included in customer timelines or high-cardinality metrics.
CREATE TABLE billing.provider_evidence (
    id BIGSERIAL PRIMARY KEY,
    intent_id UUID NOT NULL REFERENCES billing.intents(id),
    refund_id UUID REFERENCES billing.refunds(id),
    diagnostics JSONB NOT NULL,
    evidence_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (intent_id, evidence_hash)
);
CREATE INDEX provider_evidence_intent_recent ON billing.provider_evidence(intent_id, created_at DESC, id DESC);

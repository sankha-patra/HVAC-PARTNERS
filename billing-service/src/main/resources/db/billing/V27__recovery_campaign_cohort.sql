-- Cloud test campaigns must explicitly own their fixture IDs; legacy/local pending data is excluded.
CREATE TABLE billing.recovery_campaign_operations (
    campaign_id UUID NOT NULL REFERENCES billing.recovery_campaign(id),
    operation_kind TEXT NOT NULL CHECK(operation_kind IN ('PAYMENT','REFUND')),
    operation_id UUID NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(campaign_id,operation_kind,operation_id)
);

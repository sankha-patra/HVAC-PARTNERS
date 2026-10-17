ALTER TABLE billing.settlement_reports ADD COLUMN created_by VARCHAR(160);
ALTER TABLE billing.settlement_reports ADD COLUMN bank_confirmed_by VARCHAR(160);
ALTER TABLE billing.settlement_reports ADD COLUMN bank_evidence_hash CHAR(64);
ALTER TABLE billing.settlement_reports ADD COLUMN status TEXT NOT NULL DEFAULT 'REPORTED' CHECK(status IN ('REPORTED','MATCHED','EXCEPTION'));
ALTER TABLE billing.settlement_reports ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE TABLE billing.reconciliation_imports (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 external_id TEXT NOT NULL, source TEXT NOT NULL CHECK(source IN ('SIMULATOR','PROVIDER')),
 evidence JSONB NOT NULL, evidence_hash CHAR(64) NOT NULL, created_by VARCHAR(160) NOT NULL,
 state TEXT NOT NULL DEFAULT 'RECEIVED' CHECK(state IN ('RECEIVED','MATCHED','EXCEPTION')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(merchant_id,source,external_id)
);

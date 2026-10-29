-- Durable orchestration ownership only. Financial status remains authoritative in intents/refunds.
CREATE TABLE billing.recovery_campaign (
    id UUID PRIMARY KEY,
    workflow_version TEXT NOT NULL,
    max_starts INTEGER NOT NULL CHECK(max_starts BETWEEN 1 AND 100),
    max_submissions INTEGER NOT NULL CHECK(max_submissions BETWEEN 1 AND 200),
    max_describes INTEGER NOT NULL CHECK(max_describes BETWEEN 1 AND 10000),
    max_inflight INTEGER NOT NULL CHECK(max_inflight BETWEEN 1 AND 2),
    deadline TIMESTAMPTZ NOT NULL,
    starts INTEGER NOT NULL DEFAULT 0,
    submissions INTEGER NOT NULL DEFAULT 0,
    describes INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE billing.recovery_dispatch (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES billing.recovery_campaign(id),
    operation_kind TEXT NOT NULL CHECK(operation_kind IN ('PAYMENT','REFUND')),
    operation_id UUID NOT NULL,
    workflow_version TEXT NOT NULL,
    execution_name TEXT NOT NULL UNIQUE,
    execution_arn TEXT NOT NULL UNIQUE,
    input_text TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','STARTING','RUNNING','DONE','REVIEW')),
    owner TEXT NOT NULL DEFAULT 'DISPATCH',
    token UUID,
    claim_until TIMESTAMPTZ,
    next_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submissions INTEGER NOT NULL DEFAULT 0,
    remote_status TEXT,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(campaign_id,operation_kind,operation_id)
);
-- A different campaign cannot silently take an operation from an unresolved earlier workflow.
CREATE UNIQUE INDEX recovery_dispatch_owner ON billing.recovery_dispatch(operation_kind,operation_id)
    WHERE state IN ('PENDING','STARTING','RUNNING','REVIEW');
CREATE INDEX recovery_dispatch_due ON billing.recovery_dispatch(campaign_id,next_at)
    WHERE state IN ('PENDING','STARTING','RUNNING');
CREATE TABLE billing.recovery_dispatch_audit (
    id BIGSERIAL PRIMARY KEY,
    dispatch_id UUID NOT NULL REFERENCES billing.recovery_dispatch(id),
    kind TEXT NOT NULL,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

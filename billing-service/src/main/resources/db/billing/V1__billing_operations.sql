CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.merchants (
 id UUID PRIMARY KEY, name TEXT NOT NULL
);
CREATE TABLE billing.accounts (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 name TEXT NOT NULL, external_reference TEXT NOT NULL UNIQUE,
 UNIQUE(id, merchant_id)
);
CREATE TABLE billing.memberships (
 account_id UUID NOT NULL REFERENCES billing.accounts(id), user_id VARCHAR(160) NOT NULL,
 role TEXT NOT NULL CHECK (role IN ('PAYER','ACCOUNT_ADMIN')),
 PRIMARY KEY(account_id,user_id)
);
CREATE TABLE billing.staff (
 merchant_id UUID NOT NULL REFERENCES billing.merchants(id), user_id VARCHAR(160) NOT NULL,
 role TEXT NOT NULL CHECK (role IN ('FINANCE','REFUND_APPROVER','OPS')),
 PRIMARY KEY(merchant_id,user_id,role)
);
CREATE TABLE billing.invoices (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES billing.accounts(id),
 number VARCHAR(100) NOT NULL, currency CHAR(3) NOT NULL CHECK (currency='USD'),
 total_minor BIGINT NOT NULL CHECK (total_minor>0 AND total_minor<=1000000000000),
 paid_minor BIGINT NOT NULL DEFAULT 0 CHECK (paid_minor>=0),
 reserved_minor BIGINT NOT NULL DEFAULT 0 CHECK (reserved_minor>=0),
 version BIGINT NOT NULL DEFAULT 1, due_date DATE NOT NULL,
 erp_version BIGINT NOT NULL DEFAULT 1,
 CHECK (paid_minor+reserved_minor<=total_minor), UNIQUE(account_id,number)
);
CREATE TABLE billing.intents (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES billing.accounts(id),
 idempotency_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
 amount_minor BIGINT NOT NULL CHECK (amount_minor>0 AND amount_minor<=1000000000000),
 currency CHAR(3) NOT NULL CHECK (currency='USD'),
 method TEXT NOT NULL CHECK (method IN ('CARD','ACH')),
 status TEXT NOT NULL CHECK (status IN ('CREATED','SUBMITTING','REQUIRES_ACTION','PROCESSING','UNKNOWN','CAPTURED','FAILED','RETURNED','REFUNDED')),
 provider TEXT NOT NULL CHECK(provider IN ('SIMULATOR','STRIPE')),
 provider_id TEXT, checkout_url TEXT, lease_token UUID, lease_until TIMESTAMPTZ,
 next_recovery_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), recovery_count INTEGER NOT NULL DEFAULT 0,
 refunded_minor BIGINT NOT NULL DEFAULT 0 CHECK(refunded_minor>=0 AND refunded_minor<=amount_minor),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(account_id,idempotency_key), UNIQUE(provider,provider_id)
);
CREATE INDEX billing_due_recovery ON billing.intents(next_recovery_at,id)
 WHERE status IN ('SUBMITTING','REQUIRES_ACTION','PROCESSING','UNKNOWN');
CREATE TABLE billing.allocations (
 intent_id UUID NOT NULL REFERENCES billing.intents(id), invoice_id UUID NOT NULL REFERENCES billing.invoices(id),
 amount_minor BIGINT NOT NULL CHECK(amount_minor>0), reversed_minor BIGINT NOT NULL DEFAULT 0,
 state TEXT NOT NULL CHECK(state IN ('RESERVED','POSTED','RELEASED','REVERSED')),
 CHECK(reversed_minor>=0 AND reversed_minor<=amount_minor), PRIMARY KEY(intent_id,invoice_id)
);
CREATE TABLE billing.attempts (
 id UUID PRIMARY KEY, intent_id UUID NOT NULL UNIQUE REFERENCES billing.intents(id),
 provider TEXT NOT NULL, operation_key TEXT NOT NULL UNIQUE,
 status TEXT NOT NULL, provider_id TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE billing.provider_operations (
 idempotency_key TEXT PRIMARY KEY, provider_id TEXT NOT NULL UNIQUE,
 intent_id UUID NOT NULL, amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
 currency TEXT NOT NULL, method TEXT NOT NULL, operation_kind TEXT NOT NULL DEFAULT 'PAYMENT',
 state TEXT NOT NULL, reference_id TEXT, parent_provider_id TEXT,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE billing.webhook_inbox (
 provider TEXT NOT NULL, event_id TEXT NOT NULL, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 payload JSONB NOT NULL, payload_hash CHAR(64) NOT NULL,
 state TEXT NOT NULL DEFAULT 'NEW' CHECK(state IN ('NEW','PROCESSED','UNMATCHED')),
 received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, PRIMARY KEY(provider,event_id)
);
CREATE INDEX billing_inbox_due ON billing.webhook_inbox(next_attempt_at,received_at) WHERE state<>'PROCESSED';
CREATE TABLE billing.journals (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 intent_id UUID REFERENCES billing.intents(id), effect_key TEXT NOT NULL UNIQUE,
 currency CHAR(3) NOT NULL CHECK(currency='USD'), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 created_tx BIGINT NOT NULL DEFAULT txid_current()
);
CREATE TABLE billing.ledger_entries (
 journal_id UUID NOT NULL REFERENCES billing.journals(id), line INTEGER NOT NULL,
 account TEXT NOT NULL CHECK(account IN ('CUSTOMER_RECEIVABLES','PROVIDER_CLEARING','BANK_CASH','PROVIDER_FEES','UNAPPLIED_CASH')),
 side TEXT NOT NULL CHECK(side IN ('DEBIT','CREDIT')), amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
 PRIMARY KEY(journal_id,line)
);
CREATE FUNCTION billing.immutable_money() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Financial journal history is immutable'; END $$;
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON billing.journals
 FOR EACH ROW EXECUTE FUNCTION billing.immutable_money();
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON billing.ledger_entries
 FOR EACH ROW EXECUTE FUNCTION billing.immutable_money();
CREATE FUNCTION billing.only_current_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM billing.journals WHERE id=NEW.journal_id AND created_tx=txid_current()) THEN
  RAISE EXCEPTION 'Cannot append to historical journal';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER current_journal BEFORE INSERT ON billing.ledger_entries
 FOR EACH ROW EXECUTE FUNCTION billing.only_current_journal();
CREATE FUNCTION billing.balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE balance NUMERIC; lines INTEGER;
BEGIN
 SELECT COALESCE(SUM(CASE side WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END),0),COUNT(*)
 INTO balance,lines FROM billing.ledger_entries WHERE journal_id=NEW.id;
 IF balance<>0 OR lines<2 THEN RAISE EXCEPTION 'Journal must balance and contain at least two entries'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON billing.journals
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION billing.balanced_journal();
CREATE TABLE billing.outbox (
 id UUID PRIMARY KEY, aggregate_id UUID NOT NULL, type TEXT NOT NULL, payload JSONB NOT NULL,
 state TEXT NOT NULL DEFAULT 'NEW' CHECK(state IN ('NEW','IN_FLIGHT','PUBLISHED')),
 lease_token UUID, lease_until TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 attempts INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), published_at TIMESTAMPTZ
);
CREATE INDEX billing_outbox_due ON billing.outbox(next_attempt_at,created_at) WHERE state<>'PUBLISHED';
CREATE TABLE billing.consumer_inbox (
 consumer TEXT NOT NULL, event_id UUID NOT NULL, processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(consumer,event_id)
);
CREATE TABLE billing.erp_postings (
 intent_id UUID NOT NULL REFERENCES billing.intents(id), event_id UUID NOT NULL UNIQUE,
 kind TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', external_reference TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), acknowledged_at TIMESTAMPTZ,
 PRIMARY KEY(intent_id,kind,event_id)
);
CREATE TABLE billing.cases (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 intent_id UUID REFERENCES billing.intents(id), reason TEXT NOT NULL, reference TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), resolved_at TIMESTAMPTZ, resolved_by TEXT,
 UNIQUE(reason,reference)
);
CREATE TABLE billing.timeline (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, intent_id UUID NOT NULL REFERENCES billing.intents(id),
 type TEXT NOT NULL, message TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE billing.refunds (
 id UUID PRIMARY KEY, intent_id UUID NOT NULL REFERENCES billing.intents(id),
 amount_minor BIGINT NOT NULL CHECK(amount_minor>0), reason VARCHAR(500) NOT NULL,
 requested_by VARCHAR(160) NOT NULL, approved_by VARCHAR(160), provider_id TEXT,
 status TEXT NOT NULL CHECK(status IN ('REQUESTED','APPROVED','SUBMITTING','PROCESSING','UNKNOWN','REFUNDED','FAILED','REJECTED')),
 lease_token UUID, lease_until TIMESTAMPTZ, next_recovery_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 idempotency_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CHECK(approved_by IS NULL OR approved_by<>requested_by), UNIQUE(intent_id,idempotency_key)
);
CREATE INDEX billing_refund_due ON billing.refunds(next_recovery_at) WHERE status IN ('SUBMITTING','PROCESSING','UNKNOWN');
CREATE TABLE billing.settlement_reports (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 external_id TEXT NOT NULL, source TEXT NOT NULL CHECK(source IN ('SIMULATOR','PROVIDER')),
 gross_minor BIGINT NOT NULL, fee_minor BIGINT NOT NULL CHECK(fee_minor>=0), net_minor BIGINT NOT NULL,
 bank_reference TEXT NOT NULL, bank_credit_minor BIGINT,
 CHECK(gross_minor>0 AND net_minor>0 AND gross_minor-fee_minor=net_minor), UNIQUE(merchant_id,source,external_id)
);
CREATE TABLE billing.settlement_lines (
 report_id UUID NOT NULL REFERENCES billing.settlement_reports(id), intent_id UUID NOT NULL REFERENCES billing.intents(id),
 amount_minor BIGINT NOT NULL CHECK(amount_minor>0), PRIMARY KEY(report_id,intent_id), UNIQUE(intent_id)
);

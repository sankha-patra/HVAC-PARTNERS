-- Refused staff and finance requests are audited too. The refused action itself rolls back, so this
-- row is written separately, after the refusal. Like the case audit, it cannot be updated or deleted.
CREATE TABLE billing.denied_actions (
 id UUID PRIMARY KEY, actor VARCHAR(160) NOT NULL, method VARCHAR(10) NOT NULL, path VARCHAR(500) NOT NULL,
 code VARCHAR(60) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX billing_denied_actions_actor ON billing.denied_actions(actor,created_at);
CREATE FUNCTION billing.immutable_denied_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Denied action audit is immutable'; END $$;
CREATE TRIGGER immutable_denied_actions BEFORE UPDATE OR DELETE ON billing.denied_actions
 FOR EACH ROW EXECUTE FUNCTION billing.immutable_denied_audit();

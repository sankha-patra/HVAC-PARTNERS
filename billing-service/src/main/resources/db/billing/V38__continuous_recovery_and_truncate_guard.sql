-- Continuous AWS recovery: campaigns can cover ordinary traffic (auto-enrolment), and an operation whose workflow ended
-- because of an infrastructure failure gets a later workflow instead of a permanent review. Each workflow of one
-- operation is a numbered attempt with its own execution name; only one attempt per operation is ever live.
ALTER TABLE billing.recovery_campaign
    DROP CONSTRAINT recovery_campaign_max_starts_check,
    DROP CONSTRAINT recovery_campaign_max_submissions_check,
    DROP CONSTRAINT recovery_campaign_max_describes_check,
    DROP CONSTRAINT recovery_campaign_max_inflight_check,
    ADD CONSTRAINT recovery_campaign_max_starts_check CHECK(max_starts BETWEEN 1 AND 100000),
    ADD CONSTRAINT recovery_campaign_max_submissions_check CHECK(max_submissions BETWEEN 1 AND 200000),
    ADD CONSTRAINT recovery_campaign_max_describes_check CHECK(max_describes BETWEEN 1 AND 10000000),
    ADD CONSTRAINT recovery_campaign_max_inflight_check CHECK(max_inflight BETWEEN 1 AND 8);

ALTER TABLE billing.recovery_dispatch
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1 CHECK(attempt BETWEEN 1 AND 10),
    ADD COLUMN remote_error TEXT CHECK(remote_error ~ '^[A-Za-z0-9_.:-]{1,120}$'),
    DROP CONSTRAINT recovery_dispatch_state_check,
    ADD CONSTRAINT recovery_dispatch_state_check CHECK(state IN ('PENDING','STARTING','RUNNING','DONE','REVIEW','SUPERSEDED')),
    DROP CONSTRAINT recovery_dispatch_campaign_id_operation_kind_operation_id_key,
    ADD CONSTRAINT recovery_dispatch_attempt_key UNIQUE(campaign_id,operation_kind,operation_id,attempt);

-- Scenario 2 also covers bulk removal: row triggers do not fire on TRUNCATE, so the money and order tables refuse it.
-- A maintenance job that really must empty them (test databases) sets billing.maintenance='truncate' for its own
-- transaction. This stops accidents and is logged; a separate runtime role without TRUNCATE is the access control.
CREATE FUNCTION billing.refuse_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('billing.maintenance',true) IS DISTINCT FROM 'truncate' THEN
  RAISE EXCEPTION 'Table %.% cannot be truncated: it holds payment or order records', TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
DO $$
DECLARE t TEXT;
BEGIN
 FOREACH t IN ARRAY ARRAY['billing.intents','billing.attempts','billing.allocations','billing.provider_operations','billing.journals',
   'billing.ledger_entries','billing.refunds','billing.outbox','billing.outbox_events','billing.cases','billing.case_actions',
   'billing.timeline','commerce.orders','commerce.order_lines','commerce.order_bills','commerce.stock_reservations',
   'commerce.order_event_receipts'] LOOP
  EXECUTE format('CREATE TRIGGER refuse_truncate BEFORE TRUNCATE ON %s FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_truncate()',t);
 END LOOP;
END $$;

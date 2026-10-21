-- Completes the payment lifecycle: refund recovery, audited case resolution and
-- expiry of unsubmitted intents. Additive only; earlier migrations are unchanged.

-- Refund recovery mirrors payment submission. The exact refund request, its provider
-- format and its age are saved before provider I/O, so a crash or a lost response can
-- replay the same idempotent request inside the provider's window, and only then.
ALTER TABLE billing.refunds ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE billing.refunds ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE billing.refunds ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE billing.refunds ADD COLUMN original_request JSONB;
ALTER TABLE billing.refunds ADD COLUMN request_version TEXT;
ALTER TABLE billing.refunds ADD CONSTRAINT refund_submission_complete
 CHECK (original_request IS NULL OR submitted_at IS NOT NULL);

CREATE FUNCTION billing.preserve_refund_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.original_request IS NOT NULL AND (NEW.original_request IS DISTINCT FROM OLD.original_request
    OR NEW.request_version IS DISTINCT FROM OLD.request_version
    OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
  RAISE EXCEPTION 'Original refund submission is immutable';
 END IF;
 IF OLD.approved_at IS NOT NULL AND NEW.approved_at IS DISTINCT FROM OLD.approved_at THEN
  RAISE EXCEPTION 'Refund approval time is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preserve_refund_submission BEFORE UPDATE ON billing.refunds
 FOR EACH ROW EXECUTE FUNCTION billing.preserve_refund_submission();

-- Cases can now be resolved, but only through recorded actions. There is no action that
-- marks a payment paid: resolution is verified provider evidence, an approved release by
-- two different employees, or a review note on a case that moves no money.
ALTER TABLE billing.cases ADD COLUMN refund_id UUID REFERENCES billing.refunds(id);
ALTER TABLE billing.cases ADD COLUMN resolution TEXT CHECK (resolution IN
 ('PROVIDER_EVIDENCE','STAFF_VERIFIED_PROVIDER_REFERENCE','RELEASED_NO_PROVIDER_OPERATION','CLOSED_AFTER_REVIEW'));
ALTER TABLE billing.cases ADD COLUMN release_requested_by VARCHAR(160);
ALTER TABLE billing.cases ADD COLUMN release_note VARCHAR(1000);
ALTER TABLE billing.cases ADD COLUMN release_requested_at TIMESTAMPTZ;
ALTER TABLE billing.cases ADD CONSTRAINT case_resolution_complete CHECK (
 (status='OPEN' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution IS NULL) OR
 (status='RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution IS NOT NULL));
ALTER TABLE billing.cases ADD CONSTRAINT case_release_request_complete CHECK (
 (release_requested_by IS NULL) = (release_requested_at IS NULL)
 AND (release_requested_by IS NULL OR release_note IS NOT NULL));
UPDATE billing.cases c SET refund_id=r.id FROM billing.refunds r
 WHERE c.reason IN ('REFUND_UNRESOLVED','REFUND_LATER_FAILED','ORIGINAL_PROVIDER_UNAVAILABLE')
   AND c.reference=r.id::text;
CREATE INDEX billing_open_cases ON billing.cases(merchant_id,created_at) WHERE status='OPEN';

CREATE TABLE billing.case_actions (
 id UUID PRIMARY KEY, case_id UUID NOT NULL REFERENCES billing.cases(id),
 action TEXT NOT NULL CHECK (action IN ('VERIFIED_PROVIDER_REFERENCE','REQUESTED_RELEASE','APPROVED_RELEASE',
   'REJECTED_RELEASE','CLOSED_AFTER_REVIEW','AUTO_RESOLVED','REOPENED')),
 actor VARCHAR(160) NOT NULL, note VARCHAR(1000), evidence JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX billing_case_actions_case ON billing.case_actions(case_id,created_at);
CREATE FUNCTION billing.immutable_case_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Case audit history is immutable'; END $$;
CREATE TRIGGER immutable_case_actions BEFORE UPDATE OR DELETE ON billing.case_actions
 FOR EACH ROW EXECUTE FUNCTION billing.immutable_case_audit();

-- Unsubmitted intents hold invoice reservations. The expiry worker scans by age.
CREATE INDEX billing_unsubmitted_age ON billing.intents(created_at,id) WHERE status='CREATED';

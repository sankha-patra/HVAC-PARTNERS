-- A refund request that is never approved can be rejected by an approver, or withdrawn by its
-- requester. No money moves and its amount becomes available again. The rejection is final.
ALTER TABLE billing.refunds ADD COLUMN rejected_by VARCHAR(160);
ALTER TABLE billing.refunds ADD COLUMN rejected_at TIMESTAMPTZ;
ALTER TABLE billing.refunds ADD COLUMN rejection_note VARCHAR(1000);
ALTER TABLE billing.refunds ADD CONSTRAINT refund_rejection_complete CHECK (
 (status='REJECTED') = (rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_note IS NOT NULL));

-- Same rules as V12, plus: a rejected refund can never be revived or have its rejection rewritten.
CREATE OR REPLACE FUNCTION billing.preserve_refund_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.original_request IS NOT NULL AND (NEW.original_request IS DISTINCT FROM OLD.original_request
    OR NEW.request_version IS DISTINCT FROM OLD.request_version
    OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
  RAISE EXCEPTION 'Original refund submission is immutable';
 END IF;
 IF OLD.approved_at IS NOT NULL AND NEW.approved_at IS DISTINCT FROM OLD.approved_at THEN
  RAISE EXCEPTION 'Refund approval time is immutable';
 END IF;
 IF OLD.status='REJECTED' AND (NEW.status<>'REJECTED' OR NEW.rejected_by IS DISTINCT FROM OLD.rejected_by
    OR NEW.rejected_at IS DISTINCT FROM OLD.rejected_at OR NEW.rejection_note IS DISTINCT FROM OLD.rejection_note) THEN
  RAISE EXCEPTION 'A rejected refund is final';
 END IF;
 RETURN NEW;
END $$;

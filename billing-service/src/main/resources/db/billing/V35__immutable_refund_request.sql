-- Approval always refers to the original requested refund. Keep that request and
-- the first approver immutable even before provider submission is prepared.
CREATE FUNCTION billing.preserve_refund_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id
    OR NEW.intent_id IS DISTINCT FROM OLD.intent_id
    OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
    OR NEW.reason IS DISTINCT FROM OLD.reason
    OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
    OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
    OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
    OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
  RAISE EXCEPTION 'Original refund request is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.approved_by IS NOT NULL AND NEW.approved_by IS DISTINCT FROM OLD.approved_by THEN
  RAISE EXCEPTION 'Refund approver is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preserve_refund_request BEFORE UPDATE ON billing.refunds
 FOR EACH ROW EXECUTE FUNCTION billing.preserve_refund_request();

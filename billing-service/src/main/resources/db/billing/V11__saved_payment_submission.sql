-- Legacy attempts have no trustworthy request snapshot and remain lookup-only.
ALTER TABLE billing.attempts ADD COLUMN original_request JSONB;
ALTER TABLE billing.attempts ADD COLUMN request_version TEXT;

-- Recovery must reuse the original parameters and age, even after configuration changes.
CREATE FUNCTION billing.preserve_payment_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.original_request IS DISTINCT FROM OLD.original_request
    OR NEW.request_version IS DISTINCT FROM OLD.request_version
    OR NEW.operation_key IS DISTINCT FROM OLD.operation_key
    OR NEW.provider IS DISTINCT FROM OLD.provider
    OR NEW.intent_id IS DISTINCT FROM OLD.intent_id
    OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
  RAISE EXCEPTION 'Original payment submission is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER preserve_payment_submission BEFORE UPDATE ON billing.attempts
 FOR EACH ROW EXECUTE FUNCTION billing.preserve_payment_submission();

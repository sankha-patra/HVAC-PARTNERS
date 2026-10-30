-- V11 keeps the saved request unchanged on UPDATE, but the row itself could still be deleted. Recovery and the
-- buyer's return verify a provider payment against it, so a deleted attempt left captured provider money
-- unrecordable ("No submitted operation is available for verification").
CREATE FUNCTION billing.keep_payment_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Saved payment attempt cannot be deleted' USING ERRCODE='23514'; END $$;
CREATE TRIGGER keep_payment_attempt BEFORE DELETE ON billing.attempts
 FOR EACH ROW EXECUTE FUNCTION billing.keep_payment_attempt();

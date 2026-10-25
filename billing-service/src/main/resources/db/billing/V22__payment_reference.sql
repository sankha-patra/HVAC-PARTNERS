-- A payer's retry after a payment failed for certain is a new payment linked to the failed one, shown under the same
-- payment reference: the first payment's id, which payers already see as their reference. Every payment keeps its
-- own id, key, Stripe operation and money records; nothing about an existing payment changes.
--
-- There is deliberately no "one open payment per reference" constraint: provider evidence for an older attempt
-- (a late capture, a return) must always be recordable. The retry service enforces that rule under row locks.
ALTER TABLE billing.intents ADD COLUMN payment_reference UUID;
ALTER TABLE billing.intents ADD COLUMN retry_of UUID;
UPDATE billing.intents SET payment_reference=id;
ALTER TABLE billing.intents ALTER COLUMN payment_reference SET NOT NULL;

-- A reference or a retry link can only point at a payment of the same customer account.
ALTER TABLE billing.intents ADD CONSTRAINT intents_account_id_key UNIQUE (account_id,id);
ALTER TABLE billing.intents ADD CONSTRAINT intents_payment_reference_same_account
 FOREIGN KEY (account_id,payment_reference) REFERENCES billing.intents(account_id,id);
ALTER TABLE billing.intents ADD CONSTRAINT intents_retry_of_same_account
 FOREIGN KEY (account_id,retry_of) REFERENCES billing.intents(account_id,id);
-- A failed payment is tried again at most once; the chain continues from the newest attempt.
ALTER TABLE billing.intents ADD CONSTRAINT intents_retry_of_key UNIQUE (retry_of);
ALTER TABLE billing.intents ADD CONSTRAINT intents_retry_not_itself CHECK (retry_of<>id);
CREATE INDEX billing_intents_payment_reference ON billing.intents(payment_reference);

-- A new payment is its own reference unless a retry sets one, so existing insert paths need no change.
CREATE FUNCTION billing.default_payment_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.payment_reference := COALESCE(NEW.payment_reference, NEW.id);
  RETURN NEW;
END $$;
CREATE TRIGGER billing_default_payment_reference BEFORE INSERT ON billing.intents
 FOR EACH ROW EXECUTE FUNCTION billing.default_payment_reference();

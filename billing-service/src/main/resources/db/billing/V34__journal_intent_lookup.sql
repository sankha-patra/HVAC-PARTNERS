-- Customer order summaries read capture/return evidence for a bounded set of payment attempts.
-- PostgreSQL does not create an index automatically for the referencing side of a foreign key.
CREATE INDEX billing_journals_intent ON billing.journals(intent_id) WHERE intent_id IS NOT NULL;

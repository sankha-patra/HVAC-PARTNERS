CREATE UNIQUE INDEX erp_external_effect ON billing.erp_postings(external_reference);
CREATE INDEX billing_unresolved_age ON billing.intents(created_at) WHERE status IN ('UNKNOWN','SUBMITTING','PROCESSING','REQUIRES_ACTION');

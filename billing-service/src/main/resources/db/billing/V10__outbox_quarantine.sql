ALTER TABLE billing.outbox DROP CONSTRAINT outbox_state_check;
ALTER TABLE billing.outbox ADD CONSTRAINT outbox_state_check
 CHECK(state IN ('NEW','IN_FLIGHT','PUBLISHED','DEAD_LETTER'));
ALTER TABLE billing.dead_letters ADD COLUMN source_outbox_id UUID UNIQUE REFERENCES billing.outbox(id);
ALTER TABLE billing.dead_letters ADD COLUMN failure_reason TEXT;
ALTER TABLE billing.dead_letters ALTER COLUMN partition_id DROP NOT NULL;
ALTER TABLE billing.dead_letters ALTER COLUMN offset_id DROP NOT NULL;
ALTER TABLE billing.dead_letters ADD CONSTRAINT dead_letter_source_location CHECK (
 (source_outbox_id IS NULL AND partition_id IS NOT NULL AND offset_id IS NOT NULL) OR
 (source_outbox_id IS NOT NULL AND partition_id IS NULL AND offset_id IS NULL));
DROP INDEX billing.billing_outbox_aggregate_pending;
CREATE INDEX billing_outbox_aggregate_pending ON billing.outbox(aggregate_id,publish_sequence)
 WHERE state IN ('NEW','IN_FLIGHT');
DROP INDEX billing.billing_outbox_due;
CREATE INDEX billing_outbox_due ON billing.outbox(next_attempt_at,created_at)
 WHERE state IN ('NEW','IN_FLIGHT');

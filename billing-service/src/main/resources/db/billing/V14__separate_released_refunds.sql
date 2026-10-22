-- A refund released by two employees is a staff decision, not a provider failure.
-- Keep them apart: a provider failure is final, so an older "succeeded" event that
-- arrives later posts nothing, while a released refund still records late evidence.
ALTER TABLE billing.refunds DROP CONSTRAINT refunds_status_check;
ALTER TABLE billing.refunds ADD CONSTRAINT refunds_status_check CHECK(status IN
 ('REQUESTED','APPROVED','SUBMITTING','PROCESSING','UNKNOWN','REFUNDED','FAILED','REJECTED','RELEASED'));

-- Earlier releases were stored as FAILED. The approved release case identifies them.
UPDATE billing.refunds r SET status='RELEASED',updated_at=NOW() FROM billing.cases c
 WHERE c.refund_id=r.id AND c.resolution='RELEASED_NO_PROVIDER_OPERATION' AND r.status='FAILED';

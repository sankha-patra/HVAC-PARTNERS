-- Approval is a durable recovery point, including a crash before provider submission.
DROP INDEX billing.billing_refund_due;
CREATE INDEX billing_refund_due ON billing.refunds(next_recovery_at,id)
 WHERE status IN ('APPROVED','SUBMITTING','PROCESSING','UNKNOWN');

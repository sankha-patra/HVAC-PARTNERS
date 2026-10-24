-- Keep conflicting provider evidence in the existing immutable case audit.
-- No financial balances or historical provider outcomes are rewritten by this migration.
ALTER TABLE billing.case_actions DROP CONSTRAINT case_actions_action_check;
ALTER TABLE billing.case_actions ADD CONSTRAINT case_actions_action_check CHECK (action IN
 ('VERIFIED_PROVIDER_REFERENCE','REQUESTED_RELEASE','APPROVED_RELEASE','REJECTED_RELEASE',
  'CLOSED_AFTER_REVIEW','AUTO_RESOLVED','REOPENED','CONTRADICTORY_PROVIDER_EVIDENCE',
  'VERIFIED_REFUND_CONFLICT','RECONCILIATION_PENDING','RECONCILIATION_BLOCKED','RECONCILED_REFUND'));

ALTER TABLE billing.cases DROP CONSTRAINT cases_resolution_check;
ALTER TABLE billing.cases ADD CONSTRAINT cases_resolution_check CHECK (resolution IN
 ('PROVIDER_EVIDENCE','STAFF_VERIFIED_PROVIDER_REFERENCE','RELEASED_NO_PROVIDER_OPERATION',
  'CLOSED_AFTER_REVIEW','VERIFIED_REFUND_RECONCILIATION'));

CREATE UNIQUE INDEX billing_refund_conflict_evidence
 ON billing.case_actions(case_id,(evidence->>'evidenceKeyHash'))
 WHERE action='CONTRADICTORY_PROVIDER_EVIDENCE';

CREATE INDEX billing_open_refund_conflicts ON billing.cases(intent_id)
 WHERE reason='REFUND_EVIDENCE_CONFLICT' AND status='OPEN';

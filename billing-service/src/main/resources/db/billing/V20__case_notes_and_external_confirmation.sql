-- The application team can add notes to a case; Carrier finance can release a stuck case on recorded Stripe
-- confirmation when Stripe's own search finds nothing. Both are kept in the existing immutable case audit.
-- No balances, statuses or earlier actions are changed by this migration.
ALTER TABLE billing.case_actions DROP CONSTRAINT case_actions_action_check;
ALTER TABLE billing.case_actions ADD CONSTRAINT case_actions_action_check CHECK (action IN
 ('VERIFIED_PROVIDER_REFERENCE','REQUESTED_RELEASE','APPROVED_RELEASE',
  'REJECTED_RELEASE','CLOSED_AFTER_REVIEW','AUTO_RESOLVED','REOPENED','CONTRADICTORY_PROVIDER_EVIDENCE',
  'VERIFIED_REFUND_CONFLICT','RECONCILIATION_PENDING','RECONCILIATION_BLOCKED','RECONCILED_REFUND',
  'ADDED_NOTE','RECORDED_EXTERNAL_CONFIRMATION','APPROVED_EXTERNAL_RELEASE'));

ALTER TABLE billing.cases DROP CONSTRAINT cases_resolution_check;
ALTER TABLE billing.cases ADD CONSTRAINT cases_resolution_check CHECK (resolution IN
 ('PROVIDER_EVIDENCE','STAFF_VERIFIED_PROVIDER_REFERENCE','RELEASED_NO_PROVIDER_OPERATION',
  'CLOSED_AFTER_REVIEW','VERIFIED_REFUND_RECONCILIATION','RELEASED_EXTERNAL_CONFIRMATION'));

-- Bank reference semantics depend on the issuing institution. Keep the identifier exact;
-- case folding could merge distinct credits or make a valid case-sensitive reference fail.
DROP INDEX billing.one_confirmed_bank_credit;
CREATE UNIQUE INDEX one_confirmed_bank_credit
 ON billing.settlement_reports(merchant_id,bank_reference COLLATE "C") WHERE status='MATCHED';

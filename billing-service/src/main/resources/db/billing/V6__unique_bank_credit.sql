CREATE UNIQUE INDEX one_confirmed_bank_credit
 ON billing.settlement_reports(merchant_id,upper(btrim(bank_reference))) WHERE status='MATCHED';

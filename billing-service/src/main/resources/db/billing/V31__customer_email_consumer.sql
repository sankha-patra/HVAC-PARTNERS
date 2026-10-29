-- The initiating payer is server-owned metadata; addresses never enter the Kafka payment payload.
CREATE TABLE billing.payment_notification_owners (
 intent_id UUID PRIMARY KEY, account_id UUID NOT NULL, user_id VARCHAR(160) NOT NULL,
 FOREIGN KEY(account_id,intent_id) REFERENCES billing.intents(account_id,id)
);
CREATE TABLE billing.customer_notification_contacts (
 account_id UUID NOT NULL REFERENCES billing.accounts(id), user_id VARCHAR(160) NOT NULL,
 recipient VARCHAR(254) NOT NULL CHECK(recipient !~ '[\r\n]' AND recipient LIKE '%@%'),
 enabled BOOLEAN NOT NULL DEFAULT FALSE, verified_at TIMESTAMPTZ,
 eligible_from TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(account_id,user_id)
);
ALTER TABLE billing.notification_deliveries ADD COLUMN audience TEXT NOT NULL DEFAULT 'FINANCE' CHECK(audience IN ('FINANCE','CUSTOMER'));
ALTER TABLE billing.notification_deliveries ADD COLUMN account_id UUID REFERENCES billing.accounts(id);
ALTER TABLE billing.notification_deliveries ADD COLUMN recipient_user_id VARCHAR(160);
ALTER TABLE billing.notification_deliveries ADD COLUMN amount_minor BIGINT CHECK(amount_minor>0);
ALTER TABLE billing.notification_deliveries ADD COLUMN currency CHAR(3) CHECK(currency IN ('USD','INR'));
ALTER TABLE billing.notification_deliveries ADD CONSTRAINT notification_customer_scope CHECK(
 (audience='FINANCE' AND account_id IS NULL AND recipient_user_id IS NULL)
 OR (audience='CUSTOMER' AND account_id IS NOT NULL));
CREATE VIEW billing.active_notification_destinations AS
 SELECT r.merchant_id,'FINANCE'::text AS audience,NULL::uuid AS account_id,NULL::varchar(160) AS user_id,r.recipient
 FROM billing.notification_routes r WHERE r.enabled
 UNION ALL
 SELECT a.merchant_id,'CUSTOMER'::text,c.account_id,c.user_id,c.recipient
 FROM billing.customer_notification_contacts c JOIN billing.accounts a ON a.id=c.account_id
 JOIN billing.memberships m ON m.account_id=c.account_id AND m.user_id=c.user_id
 WHERE c.enabled AND c.verified_at IS NOT NULL;

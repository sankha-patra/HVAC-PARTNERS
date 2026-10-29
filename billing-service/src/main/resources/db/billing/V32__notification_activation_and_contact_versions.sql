CREATE TABLE billing.notification_activation (
 consumer TEXT PRIMARY KEY, minimum_sequence BIGINT NOT NULL CHECK(minimum_sequence>0),
 activated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Use the same writer gate as relay handover. Old committed events remain pre-activation.
DO $$ BEGIN
 PERFORM 1 FROM billing.outbox_relay_control WHERE id FOR UPDATE;
 INSERT INTO billing.notification_activation(consumer,minimum_sequence)
 SELECT 'CUSTOMER_EMAIL_V1',COALESCE(MAX(publish_sequence),0)+1 FROM billing.outbox;
END $$;
ALTER TABLE billing.customer_notification_contacts ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0);
ALTER TABLE billing.notification_deliveries ADD COLUMN contact_revision BIGINT NOT NULL DEFAULT 0;
CREATE SEQUENCE billing.customer_contact_revision;
SELECT setval('billing.customer_contact_revision',GREATEST(
 (SELECT COALESCE(MAX(revision),0) FROM billing.customer_notification_contacts),
 (SELECT COALESCE(MAX(contact_revision),0) FROM billing.notification_deliveries))+1,false);
CREATE FUNCTION billing.version_customer_contact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN NEW.revision=nextval('billing.customer_contact_revision'); RETURN NEW; END IF;
 IF NEW.recipient IS DISTINCT FROM OLD.recipient AND NEW.verified_at IS NOT DISTINCT FROM OLD.verified_at THEN NEW.verified_at=NULL; END IF;
 IF ROW(OLD.recipient,OLD.enabled,OLD.verified_at,OLD.eligible_from) IS DISTINCT FROM ROW(NEW.recipient,NEW.enabled,NEW.verified_at,NEW.eligible_from) THEN
  NEW.revision=nextval('billing.customer_contact_revision');
  IF NEW.enabled AND (NOT OLD.enabled OR NEW.recipient IS DISTINCT FROM OLD.recipient OR (OLD.verified_at IS NULL AND NEW.verified_at IS NOT NULL)) THEN
   NEW.eligible_from=clock_timestamp();
  END IF;
 ELSE NEW.revision=OLD.revision;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER version_customer_contact BEFORE UPDATE ON billing.customer_notification_contacts FOR EACH ROW EXECUTE FUNCTION billing.version_customer_contact();
CREATE TRIGGER version_customer_contact_insert BEFORE INSERT ON billing.customer_notification_contacts FOR EACH ROW EXECUTE FUNCTION billing.version_customer_contact();
CREATE OR REPLACE VIEW billing.active_notification_destinations AS
 SELECT r.merchant_id,'FINANCE'::text AS audience,NULL::uuid AS account_id,NULL::varchar(160) AS user_id,r.recipient,0::bigint AS contact_revision
 FROM billing.notification_routes r WHERE r.enabled
 UNION ALL
 SELECT a.merchant_id,'CUSTOMER'::text,c.account_id,c.user_id,c.recipient,c.revision
 FROM billing.customer_notification_contacts c JOIN billing.accounts a ON a.id=c.account_id
 JOIN billing.memberships m ON m.account_id=c.account_id AND m.user_id=c.user_id
 WHERE c.enabled AND c.verified_at IS NOT NULL;
CREATE FUNCTION billing.immutable_notification_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Payment notification owner is immutable'; END $$;
CREATE TRIGGER immutable_notification_owner BEFORE UPDATE OR DELETE ON billing.payment_notification_owners FOR EACH ROW EXECUTE FUNCTION billing.immutable_notification_owner();

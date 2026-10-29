-- Keep suppression evidence and bind a notification to the durable refund transition version.
ALTER TABLE billing.refunds ADD COLUMN notification_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing.notification_deliveries ADD COLUMN event_version BIGINT;
ALTER TABLE billing.notification_deliveries ALTER COLUMN recipient DROP NOT NULL;
ALTER TABLE billing.notification_deliveries ADD CONSTRAINT notification_address_required CHECK(state='SUPPRESSED' OR recipient IS NOT NULL);
CREATE UNIQUE INDEX notification_event_recipient_identity ON billing.notification_deliveries(merchant_id,event_key,recipient) NULLS NOT DISTINCT;
CREATE FUNCTION billing.version_refund_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN NEW.notification_version=1;
 ELSIF OLD.status IS DISTINCT FROM NEW.status THEN NEW.notification_version=OLD.notification_version+1;
 ELSE NEW.notification_version=OLD.notification_version;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER version_refund_notification BEFORE INSERT OR UPDATE OF status ON billing.refunds
FOR EACH ROW EXECUTE FUNCTION billing.version_refund_notification();
CREATE OR REPLACE FUNCTION billing.queue_finance_notification(merchant UUID, intent UUID, operation UUID, kind TEXT, event TEXT)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE version BIGINT;
BEGIN
 IF kind LIKE 'REFUND_%' THEN SELECT notification_version INTO version FROM billing.refunds WHERE id=operation; END IF;
 INSERT INTO billing.notification_deliveries(merchant_id,event_key,event_type,event_version,intent_id,operation_id,recipient,state,last_error)
 SELECT merchant,event,kind,version,intent,operation,r.recipient,
 CASE WHEN r.recipient IS NULL THEN 'SUPPRESSED' ELSE 'PENDING' END,
 CASE WHEN r.recipient IS NULL THEN 'NO_ACTIVE_ROUTE' ELSE NULL END
 FROM (SELECT 1) AS singleton LEFT JOIN billing.notification_routes r ON r.merchant_id=merchant AND r.enabled
 ON CONFLICT DO NOTHING;
END $$;
CREATE OR REPLACE FUNCTION billing.notify_refund_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE merchant UUID;
BEGIN
 IF TG_OP='UPDATE' THEN IF OLD.status IS NOT DISTINCT FROM NEW.status THEN RETURN NEW; END IF; END IF;
 IF NEW.status IN ('REQUESTED','APPROVED','REJECTED','REFUNDED','FAILED','RELEASED') THEN
  SELECT a.merchant_id INTO STRICT merchant FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=NEW.intent_id;
  PERFORM billing.queue_finance_notification(merchant,NEW.intent_id,NEW.id,'REFUND_'||NEW.status,'refund:'||NEW.id::text||':'||NEW.notification_version::text);
 END IF;
 RETURN NEW;
END $$;

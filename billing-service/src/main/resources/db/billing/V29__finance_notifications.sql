-- Notification delivery is independent of money. Enqueue with the business commit; never send in SQL.
CREATE TABLE billing.notification_routes (
 merchant_id UUID PRIMARY KEY REFERENCES billing.merchants(id),
 recipient VARCHAR(254) NOT NULL CHECK(recipient !~ '[\r\n]' AND recipient LIKE '%@%'),
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE billing.notification_deliveries (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 event_key TEXT NOT NULL, event_type TEXT NOT NULL, intent_id UUID NOT NULL REFERENCES billing.intents(id),
 operation_id UUID NOT NULL, recipient VARCHAR(254) NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','IN_FLIGHT','SENT','REVIEW','SUPPRESSED')),
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 next_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), lease_token UUID, lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), accepted_at TIMESTAMPTZ, last_error TEXT,
 UNIQUE(merchant_id,event_key,recipient),
 CHECK(recipient !~ '[\r\n]' AND recipient LIKE '%@%')
);
CREATE INDEX notification_due ON billing.notification_deliveries(next_at,id) WHERE state IN ('PENDING','IN_FLIGHT');
CREATE FUNCTION billing.queue_finance_notification(merchant UUID, intent UUID, operation UUID, kind TEXT, event TEXT)
RETURNS void LANGUAGE sql AS $$
 INSERT INTO billing.notification_deliveries(merchant_id,event_key,event_type,intent_id,operation_id,recipient)
 SELECT merchant,event,kind,intent,operation,recipient FROM billing.notification_routes
 WHERE merchant_id=merchant AND enabled ON CONFLICT DO NOTHING;
$$;
CREATE FUNCTION billing.notify_refund_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE merchant UUID;
BEGIN
 IF TG_OP='UPDATE' THEN IF OLD.status IS NOT DISTINCT FROM NEW.status THEN RETURN NEW; END IF; END IF;
 IF NEW.status IN ('REQUESTED','APPROVED','REJECTED','REFUNDED','FAILED','RELEASED') THEN
  SELECT a.merchant_id INTO STRICT merchant FROM billing.intents i JOIN billing.accounts a ON a.id=i.account_id WHERE i.id=NEW.intent_id;
  PERFORM billing.queue_finance_notification(merchant,NEW.intent_id,NEW.id,'REFUND_'||NEW.status,'refund:'||gen_random_uuid()::text);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notify_refund_transition AFTER INSERT OR UPDATE OF status ON billing.refunds
FOR EACH ROW EXECUTE FUNCTION billing.notify_refund_transition();
CREATE FUNCTION billing.notify_finance_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.intent_id IS NOT NULL THEN
  PERFORM billing.queue_finance_notification(NEW.merchant_id,NEW.intent_id,NEW.id,'FINANCE_REVIEW','case:'||NEW.id::text);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notify_finance_case AFTER INSERT ON billing.cases FOR EACH ROW EXECUTE FUNCTION billing.notify_finance_case();
CREATE FUNCTION billing.notify_case_approval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item billing.cases%ROWTYPE;
BEGIN
 IF NEW.action IN ('REQUESTED_RELEASE','APPROVED_RELEASE','REJECTED_RELEASE','REOPENED') THEN
  SELECT * INTO STRICT item FROM billing.cases WHERE id=NEW.case_id;
  IF item.intent_id IS NOT NULL THEN
   PERFORM billing.queue_finance_notification(item.merchant_id,item.intent_id,item.id,'CASE_'||NEW.action,'action:'||NEW.id::text);
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notify_case_approval AFTER INSERT ON billing.case_actions FOR EACH ROW EXECUTE FUNCTION billing.notify_case_approval();

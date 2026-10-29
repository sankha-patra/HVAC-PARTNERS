-- A newly enrolled destination must not receive historical events through a backdated insert.
-- Administratively delaying eligibility is allowed; moving it backwards is not.
CREATE OR REPLACE FUNCTION billing.version_customer_contact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  NEW.revision=nextval('billing.customer_contact_revision');
  NEW.eligible_from=GREATEST(NEW.eligible_from,clock_timestamp());
  RETURN NEW;
 END IF;
 NEW.eligible_from=GREATEST(NEW.eligible_from,OLD.eligible_from);
 IF NEW.recipient IS DISTINCT FROM OLD.recipient AND NEW.verified_at IS NOT DISTINCT FROM OLD.verified_at THEN NEW.verified_at=NULL; END IF;
 IF ROW(OLD.recipient,OLD.enabled,OLD.verified_at,OLD.eligible_from) IS DISTINCT FROM ROW(NEW.recipient,NEW.enabled,NEW.verified_at,NEW.eligible_from) THEN
  NEW.revision=nextval('billing.customer_contact_revision');
  IF NEW.enabled AND (NOT OLD.enabled OR NEW.recipient IS DISTINCT FROM OLD.recipient OR (OLD.verified_at IS NULL AND NEW.verified_at IS NOT NULL)) THEN
   NEW.eligible_from=GREATEST(NEW.eligible_from,clock_timestamp());
  END IF;
 ELSE NEW.revision=OLD.revision;
 END IF;
 RETURN NEW;
END $$;

-- A -> B -> A and delete/recreate must not reactivate a delivery for a previous route lifecycle.
CREATE SEQUENCE billing.finance_route_revision;
ALTER TABLE billing.notification_routes ADD COLUMN revision BIGINT NOT NULL
 DEFAULT nextval('billing.finance_route_revision') CHECK(revision>0);
CREATE FUNCTION billing.version_finance_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN NEW.revision=nextval('billing.finance_route_revision');
 ELSIF ROW(NEW.recipient,NEW.enabled) IS DISTINCT FROM ROW(OLD.recipient,OLD.enabled) THEN
  NEW.revision=nextval('billing.finance_route_revision');
 ELSE NEW.revision=OLD.revision;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER version_finance_route BEFORE INSERT OR UPDATE ON billing.notification_routes
 FOR EACH ROW EXECUTE FUNCTION billing.version_finance_route();

-- Preserve pending pre-migration work only for its exact still-enabled destination.
-- Already suppressed/sent history is never revived or rewritten.
UPDATE billing.notification_deliveries n SET contact_revision=r.revision
 FROM billing.notification_routes r
 WHERE n.audience='FINANCE' AND n.contact_revision=0 AND n.state IN ('PENDING','IN_FLIGHT')
 AND n.merchant_id=r.merchant_id AND n.recipient=r.recipient AND r.enabled;

CREATE OR REPLACE VIEW billing.active_notification_destinations AS
 SELECT r.merchant_id,'FINANCE'::text AS audience,NULL::uuid AS account_id,NULL::varchar(160) AS user_id,r.recipient,r.revision AS contact_revision
 FROM billing.notification_routes r WHERE r.enabled
 UNION ALL
 SELECT a.merchant_id,'CUSTOMER'::text,c.account_id,c.user_id,c.recipient,c.revision
 FROM billing.customer_notification_contacts c JOIN billing.accounts a ON a.id=c.account_id
 JOIN billing.memberships m ON m.account_id=c.account_id AND m.user_id=c.user_id
 WHERE c.enabled AND c.verified_at IS NOT NULL;

CREATE OR REPLACE FUNCTION billing.queue_finance_notification(merchant UUID, intent UUID, operation UUID, kind TEXT, event TEXT)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE version BIGINT;
BEGIN
 IF kind LIKE 'REFUND_%' THEN SELECT notification_version INTO version FROM billing.refunds WHERE id=operation; END IF;
 INSERT INTO billing.notification_deliveries(merchant_id,event_key,event_type,event_version,intent_id,operation_id,recipient,state,last_error,contact_revision)
 SELECT merchant,event,kind,version,intent,operation,r.recipient,
 CASE WHEN r.recipient IS NULL THEN 'SUPPRESSED' ELSE 'PENDING' END,
 CASE WHEN r.recipient IS NULL THEN 'NO_ACTIVE_ROUTE' ELSE NULL END,COALESCE(r.revision,0)
 FROM (SELECT 1) AS singleton LEFT JOIN billing.notification_routes r ON r.merchant_id=merchant AND r.enabled
 ON CONFLICT DO NOTHING;
END $$;

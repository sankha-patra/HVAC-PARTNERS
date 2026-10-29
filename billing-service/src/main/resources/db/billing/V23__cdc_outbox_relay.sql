-- The canonical outbox and all old FK/audit contracts remain. Only the append-only
-- stream is captured by Debezium. No historical rows are silently re-emitted.
CREATE TABLE billing.outbox_relay_control (
 id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(id),
 mode TEXT NOT NULL DEFAULT 'POLL' CHECK(mode IN ('POLL','CDC')),
 generation BIGINT NOT NULL DEFAULT 1 CHECK(generation>0),
 changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO billing.outbox_relay_control(id) VALUES(TRUE);
ALTER TABLE billing.outbox DROP CONSTRAINT outbox_state_check;
ALTER TABLE billing.outbox ADD CONSTRAINT outbox_state_check
 CHECK(state IN ('NEW','IN_FLIGHT','PUBLISHED','DEAD_LETTER','CDC_PENDING'));
CREATE TABLE billing.outbox_events (
 id UUID PRIMARY KEY REFERENCES billing.outbox(id) ON DELETE CASCADE,
 aggregatetype TEXT NOT NULL DEFAULT 'billing',
 aggregateid UUID NOT NULL,
 type TEXT NOT NULL,
 payload JSONB NOT NULL CHECK(octet_length(payload::text)<=262144),
 publish_sequence BIGINT NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL
);
CREATE FUNCTION billing.choose_outbox_relay() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE relay TEXT;
BEGIN
 -- A mode switch locks this row exclusively, waits for old event transactions
 -- to finish, and checks the drain. New event writers then see the new mode.
 SELECT mode INTO STRICT relay FROM billing.outbox_relay_control WHERE id FOR SHARE;
 IF relay='CDC' THEN NEW.state:='CDC_PENDING'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER billing_outbox_relay BEFORE INSERT ON billing.outbox
 FOR EACH ROW EXECUTE FUNCTION billing.choose_outbox_relay();
CREATE FUNCTION billing.copy_cdc_outbox_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state='CDC_PENDING' THEN
  INSERT INTO billing.outbox_events(id,aggregateid,type,payload,publish_sequence,created_at)
   VALUES(NEW.id,NEW.aggregate_id,NEW.type,NEW.payload,NEW.publish_sequence,NEW.created_at);
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER billing_cdc_outbox_insert AFTER INSERT ON billing.outbox
 FOR EACH ROW EXECUTE FUNCTION billing.copy_cdc_outbox_event();
CREATE FUNCTION billing.reject_cdc_event_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'CDC outbox events are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER billing_cdc_outbox_immutable BEFORE UPDATE ON billing.outbox_events
 FOR EACH ROW EXECUTE FUNCTION billing.reject_cdc_event_update();
CREATE INDEX billing_outbox_cdc_pending ON billing.outbox(created_at) WHERE state='CDC_PENDING';
-- Connector-only heartbeat writes advance its database WAL position during quiet
-- periods. The bootstrap publication includes this table; it is not a business event.
CREATE TABLE billing.cdc_heartbeat(id INTEGER PRIMARY KEY CHECK(id=1),touched_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
INSERT INTO billing.cdc_heartbeat(id) VALUES(1);
-- Retention is an explicit maintenance operation, never an acknowledgment.
-- The age floor also covers this reference's seven-day Kafka retention. Operators
-- must verify consumer lag, snapshots and backup/rebuild requirements before enabling cleanup.
CREATE FUNCTION billing.protect_cdc_retention() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE eligible BOOLEAN;
BEGIN
 IF TG_TABLE_NAME='outbox' THEN
  IF NOT EXISTS(SELECT 1 FROM billing.outbox_events WHERE id=OLD.id) THEN RETURN OLD; END IF;
  eligible:=OLD.state='PUBLISHED' AND OLD.published_at<NOW()-INTERVAL '8 days';
 ELSE
  SELECT state='PUBLISHED' AND published_at<NOW()-INTERVAL '8 days' INTO eligible
   FROM billing.outbox WHERE id=OLD.id;
  IF NOT FOUND AND pg_trigger_depth()>1 THEN RETURN OLD; END IF;
 END IF;
 IF current_setting('billing.cdc_maintenance',TRUE) IS DISTINCT FROM 'on' OR eligible IS DISTINCT FROM TRUE THEN
  RAISE EXCEPTION 'CDC events require confirmed delivery and reviewed retention maintenance' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END;
$$;
CREATE TRIGGER billing_outbox_retention BEFORE DELETE ON billing.outbox
 FOR EACH ROW EXECUTE FUNCTION billing.protect_cdc_retention();
CREATE TRIGGER billing_cdc_event_retention BEFORE DELETE ON billing.outbox_events
 FOR EACH ROW EXECUTE FUNCTION billing.protect_cdc_retention();

-- Existing rows receive a deterministic historical order; future inserts serialize
-- per payment before allocating their position, including operations/DLT replay.
ALTER TABLE billing.outbox ADD COLUMN publish_sequence BIGINT;
WITH ordered AS (
 SELECT id,ROW_NUMBER() OVER (ORDER BY created_at,id) AS position FROM billing.outbox
)
UPDATE billing.outbox o SET publish_sequence=ordered.position FROM ordered WHERE o.id=ordered.id;
CREATE SEQUENCE billing.outbox_publish_sequence;
SELECT setval('billing.outbox_publish_sequence',COALESCE(MAX(publish_sequence),0)+1,false) FROM billing.outbox;
ALTER TABLE billing.outbox ALTER COLUMN publish_sequence SET NOT NULL;
CREATE UNIQUE INDEX billing_outbox_publish_sequence ON billing.outbox(publish_sequence);
CREATE INDEX billing_outbox_aggregate_pending ON billing.outbox(aggregate_id,publish_sequence) WHERE state<>'PUBLISHED';
CREATE FUNCTION billing.assign_outbox_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- The lock lasts until commit, preventing an uncommitted earlier event from
 -- being overtaken by a later insert for the same payment in another session.
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.aggregate_id::text,0));
 NEW.publish_sequence := nextval('billing.outbox_publish_sequence');
 RETURN NEW;
END;
$$;
CREATE TRIGGER billing_outbox_sequence BEFORE INSERT ON billing.outbox
 FOR EACH ROW EXECUTE FUNCTION billing.assign_outbox_sequence();

-- Payment views filter by intent and return the newest 100 events. The primary
-- key alone can scan unrelated payments' entire history when there are fewer
-- than 100 matching events. Keep the original query and financial behavior.
CREATE INDEX billing_timeline_intent_order ON billing.timeline(intent_id, id DESC);

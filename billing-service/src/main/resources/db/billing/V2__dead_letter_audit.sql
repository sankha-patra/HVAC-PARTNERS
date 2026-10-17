CREATE TABLE billing.dead_letters (
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES billing.merchants(id),
 topic TEXT NOT NULL, partition_id INTEGER NOT NULL, offset_id BIGINT NOT NULL,
 event_key TEXT, raw_payload BYTEA NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 state TEXT NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','REPLAY_QUEUED','REPLAYED')),
 replay_outbox_id UUID REFERENCES billing.outbox(id), replayed_by TEXT,
 UNIQUE(topic,partition_id,offset_id)
);

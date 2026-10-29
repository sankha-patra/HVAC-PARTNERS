\set ON_ERROR_STOP on
\if :{?replication_user}
\else
\echo Supply -v replication_user=the_provisioned_replication_login
\quit 1
\endif
-- Execute as the designated DBA/schema owner on the private database after V23.
-- The login and its password are provisioned separately into Secrets Manager.
BEGIN;
GRANT rds_replication TO :"replication_user";
GRANT USAGE ON SCHEMA billing TO :"replication_user";
GRANT SELECT ON billing.outbox_events,billing.cdc_heartbeat TO :"replication_user";
GRANT UPDATE(touched_at) ON billing.cdc_heartbeat TO :"replication_user";
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_publication WHERE pubname='billing_outbox_cdc') THEN
  CREATE PUBLICATION billing_outbox_cdc FOR TABLE billing.outbox_events,billing.cdc_heartbeat WITH (publish='insert,update');
 END IF;
END $$;
COMMIT;
-- Verify membership and publication contents. Do not add every application table.
SELECT pubname,schemaname,tablename FROM pg_publication_tables WHERE pubname='billing_outbox_cdc';

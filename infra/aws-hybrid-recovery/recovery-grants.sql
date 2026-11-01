-- Local disposable database only. Root creates the login/password privately before applying this file.
-- Grants for the recovery Lambda's database role (failure_recovery) in the hybrid lab database. Run as the schema owner.
-- No credential belongs in this script, template, log or chat. This is not a migration.
-- Refunds: refund recovery reads and updates billing.refunds (its absence failed a real refund recovery on 30 Oct).
BEGIN;
DO $$
BEGIN
 IF current_database()<>'invoice_payments_test' THEN RAISE EXCEPTION 'Wrong failure-lab database'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='failure_recovery' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
 THEN RAISE EXCEPTION 'Restricted failure_recovery role must already exist'; END IF;
 IF EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='failure_recovery')
 THEN RAISE EXCEPTION 'Failure role must not inherit another role'; END IF;
 IF has_schema_privilege('failure_recovery','billing','CREATE') OR has_schema_privilege('failure_recovery','commerce','CREATE') OR has_schema_privilege('failure_recovery','public','CREATE')
 THEN RAISE EXCEPTION 'Failure role must not have schema CREATE privileges'; END IF;
END $$;
GRANT CONNECT ON DATABASE invoice_payments_test TO failure_recovery;
GRANT USAGE ON SCHEMA billing TO failure_recovery;
GRANT SELECT ON
 billing.accounts,billing.intents,billing.attempts,billing.allocations,billing.invoices,
 billing.journals,billing.ledger_entries,billing.provider_payment_evidence,billing.provider_evidence,
 billing.timeline,billing.cases,billing.case_actions,billing.recovery_campaign,
 billing.recovery_campaign_operations,billing.notification_routes,billing.notification_deliveries,
 billing.outbox,billing.outbox_events,billing.outbox_relay_control,billing.refunds
 TO failure_recovery;
GRANT UPDATE ON billing.intents,billing.attempts,billing.allocations,billing.invoices,billing.cases,billing.refunds TO failure_recovery;
GRANT INSERT ON
 billing.timeline,billing.journals,billing.ledger_entries,billing.provider_payment_evidence,
 billing.provider_evidence,billing.cases,billing.case_actions,billing.outbox,billing.outbox_events,
 billing.notification_deliveries TO failure_recovery;
-- The outbox trigger SELECT...FOR SHARE requires UPDATE privilege on some column.
-- id is the constrained singleton boolean key; this grants no privilege to change relay mode/generation.
GRANT UPDATE(id) ON billing.outbox_relay_control TO failure_recovery;
GRANT USAGE ON SEQUENCE billing.timeline_id_seq,billing.outbox_publish_sequence,billing.provider_evidence_id_seq TO failure_recovery;
-- No commerce writes are used by RecoveryTask -> recoverPayment -> recordResult/capture.
-- Refund rows are updated (recovery records the provider's answer), never created. No campaign writes, DDL, DELETE,
-- TRUNCATE, grant option or ownership is granted.
ALTER ROLE failure_recovery SET statement_timeout='8s';
ALTER ROLE failure_recovery SET lock_timeout='3s';
ALTER ROLE failure_recovery SET idle_in_transaction_session_timeout='10s';
COMMIT;

-- Save this non-secret privilege inventory as evidence after applying, without reading pg_authid/passwords.
SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname='failure_recovery';
SELECT table_schema,table_name,privilege_type FROM information_schema.role_table_grants WHERE grantee='failure_recovery' ORDER BY 1,2,3;
SELECT table_schema,table_name,column_name,privilege_type FROM information_schema.role_column_grants WHERE grantee='failure_recovery' AND table_name='outbox_relay_control' ORDER BY 1,2,3,4;

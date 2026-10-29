\set ON_ERROR_STOP on
\if :{?expected_mode}
\else
\quit 1
\endif
\if :{?next_mode}
\else
\quit 1
\endif
\if :{?generation}
\else
\quit 1
\endif
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT set_config('billing.expected_mode',:'expected_mode',true),set_config('billing.next_mode',:'next_mode',true),set_config('billing.expected_generation',:'generation',true);
DO $$ DECLARE control billing.outbox_relay_control; target TEXT; BEGIN
 target:=current_setting('billing.next_mode');
 IF target NOT IN ('POLL','CDC') THEN RAISE EXCEPTION 'Invalid relay mode'; END IF;
 SELECT * INTO STRICT control FROM billing.outbox_relay_control WHERE id FOR UPDATE;
 IF control.mode<>current_setting('billing.expected_mode') OR control.generation<>current_setting('billing.expected_generation')::bigint THEN
  RAISE EXCEPTION 'Relay changed; re-read mode and generation';
 END IF;
 IF control.mode=target THEN RETURN; END IF;
 IF EXISTS(SELECT 1 FROM billing.outbox o WHERE state IN ('NEW','IN_FLIGHT','CDC_PENDING') OR
   (state='DEAD_LETTER' AND NOT EXISTS(SELECT 1 FROM billing.dead_letters d WHERE d.source_outbox_id=o.id AND d.state='REPLAYED'))) THEN
  RAISE EXCEPTION 'Relay work must drain and quarantined events must be resolved first';
 END IF;
 UPDATE billing.outbox_relay_control SET mode=target,generation=generation+1,changed_at=NOW() WHERE id;
END $$;
COMMIT;
SELECT mode,generation,changed_at FROM billing.outbox_relay_control WHERE id;

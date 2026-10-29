"""Local Compose bootstrap only. Cloud publication/login/cutover are reviewed operator steps."""
import json, os, subprocess, time, urllib.request, urllib.error

if (os.environ.get('PGHOST'), os.environ.get('PGDATABASE'), os.environ.get('BILLING_CDC_CONNECT_URL')) != ('postgres', 'invoice_payments', 'http://debezium:8083'):
    raise SystemExit('This bootstrap is restricted to the local Compose database and connector')

def sql(statement):
    result = subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-c', statement], capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Database CDC bootstrap failed; inspect database logs and relay backlog')
    return result.stdout.strip()

sql("""
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_publication WHERE pubname='billing_outbox_cdc') THEN
  CREATE PUBLICATION billing_outbox_cdc FOR TABLE billing.outbox_events,billing.cdc_heartbeat WITH (publish='insert,update');
 END IF;
END $$;
""")

base = os.environ['BILLING_CDC_CONNECT_URL']
def request(method, path, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(base+path, data=body, method=method, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return response.read()

with open('/opt/billing-cdc/connector.json') as source:
    config = json.load(source)['config']
config.update({'database.hostname': os.environ['PGHOST'], 'database.port': os.environ['PGPORT'],
               'database.dbname': os.environ['PGDATABASE'], 'database.user': os.environ['PGUSER'],
               'database.password': os.environ['PGPASSWORD']})
request('PUT', '/connectors/billing-outbox/config', config)
deadline = time.monotonic()+180
while time.monotonic() < deadline:
    try:
        status = json.loads(request('GET', '/connectors/billing-outbox/status'))
        if status['connector']['state']=='RUNNING' and status['tasks'] and all(t['state']=='RUNNING' for t in status['tasks']):
            break
    except (urllib.error.URLError, TimeoutError):
        pass
    time.sleep(2)
else:
    raise SystemExit('Connector did not become ready; no relay handover was made')

# This is the same conservative drain rule as BillingCdcCommands. The row lock
# serializes with event inserts, so older transactions cannot straddle handover.
sql("""
BEGIN ISOLATION LEVEL READ COMMITTED;
DO $$ DECLARE old_mode TEXT; BEGIN
 SELECT mode INTO STRICT old_mode FROM billing.outbox_relay_control WHERE id FOR UPDATE;
 IF old_mode='CDC' THEN RETURN; END IF;
 IF EXISTS(SELECT 1 FROM billing.outbox o WHERE state IN ('NEW','IN_FLIGHT','CDC_PENDING') OR
   (state='DEAD_LETTER' AND NOT EXISTS(SELECT 1 FROM billing.dead_letters d WHERE d.source_outbox_id=o.id AND d.state='REPLAYED'))) THEN
  RAISE EXCEPTION 'Drain or resolve the existing relay before handover';
 END IF;
 UPDATE billing.outbox_relay_control SET mode='CDC',generation=generation+1,changed_at=NOW() WHERE id;
END $$;
COMMIT;
""")
print('Local CDC connector is running; database handover completed')

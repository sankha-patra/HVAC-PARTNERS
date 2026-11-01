"""Run: python -I -m unittest discover -s infra/aws-hybrid-alerts/forwarder -p 'test_*.py'"""
import base64
import gzip
import json
import os
import sys
import types
import unittest

published = []
sys.modules["boto3"] = types.SimpleNamespace(client=lambda name: types.SimpleNamespace(publish=lambda **kw: published.append(kw)))
os.environ.update(TOPIC_ARN="arn:aws:sns:ap-southeast-2:123456789012:t", STACK_NAME="payments-hybrid",
                  WATCHED_CONTAINERS=json.dumps({"billing": "payments-rzp-failures-billing-1"}))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import forwarder  # noqa: E402


def event(group, *messages):
    data = {"messageType": "DATA_MESSAGE", "logGroup": group, "logStream": "s",
            "logEvents": [{"id": str(i), "timestamp": 0, "message": m} for i, m in enumerate(messages)]}
    return {"awslogs": {"data": base64.b64encode(gzip.compress(json.dumps(data).encode())).decode()}}


class ForwarderTest(unittest.TestCase):
    def setUp(self):
        published.clear()

    def test_alert_line_is_emailed_with_its_reason_and_ids(self):
        line = '2026-11-01T10:00:00.000+05:30  WARN 1 --- [billing] [exec-1] operational-alerts : ALERT code=PAYMENT_OUTCOME_UNKNOWN severity=WARNING intentId=8643c96f reason="Payment provider did not confirm the request (ProviderUncertainException); kept as UNKNOWN with its saved request for recovery"'
        self.assertEqual(forwarder.handler(event("/payments-hybrid/billing", line), None)["codes"], ["PAYMENT_OUTCOME_UNKNOWN"])
        self.assertEqual(published[0]["Subject"], "[payments-hybrid] WARNING PAYMENT_OUTCOME_UNKNOWN")
        self.assertIn("intentId=8643c96f", published[0]["Message"])
        self.assertIn("idempotency key", published[0]["Message"])

    def test_condition_codes_and_noise_are_not_emailed(self):
        self.assertEqual(forwarder.handler(event("/payments-hybrid/billing", "operational-alerts : ALERT code=OUTBOX_NOT_DRAINING severity=WARNING oldestSeconds=90 reason=\"x\"", "HEARTBEAT service=billing db=up"), None)["sent"], 0)
        self.assertEqual(published, [])

    def test_blocked_delete_and_truncate_are_critical(self):
        forwarder.handler(event("/payments-hybrid/postgres", '2026-11-01 04:30:00.000 UTC [147] user=billing_test db=invoice_payments_test app=psql client=172.18.0.1 ERROR:  Saved payment attempt cannot be deleted'), None)
        self.assertEqual(published[0]["Subject"], "[payments-hybrid] CRITICAL PROTECTED_ROW_CHANGE_BLOCKED")
        self.assertIn("app=psql", published[0]["Message"])

    def test_only_watched_containers_that_died_are_emailed_with_the_exit_code(self):
        # Docker 29 event shape: Type/Action, no legacy status field.
        died = json.dumps({"Type": "container", "Action": "die", "Actor": {"Attributes": {"name": "payments-rzp-failures-billing-1", "exitCode": "137"}}})
        other = json.dumps({"Type": "container", "Action": "die", "Actor": {"Attributes": {"name": "payments-implementation-e2e-billing-1", "exitCode": "0"}}})
        self.assertEqual(forwarder.handler(event("/payments-hybrid/docker-events", other), None)["sent"], 0)
        forwarder.handler(event("/payments-hybrid/docker-events", died), None)
        self.assertIn("billing container payments-rzp-failures-billing-1 event=die exitCode=137", published[0]["Message"])


if __name__ == "__main__":
    unittest.main()

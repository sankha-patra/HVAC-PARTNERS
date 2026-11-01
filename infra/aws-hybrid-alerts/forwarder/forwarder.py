"""Emails one-off failures with the exact log line, so the reader sees what broke, why, and the ids.

Invoked by CloudWatch Logs subscription filters (forwarder.tf). One invocation is one batch of matching lines from
one log stream; it becomes at most one email. Lines are already sanitised by the app (OperationalAlert) or come from
PostgreSQL / Docker events; each is cut to 600 characters.
"""
import base64
import gzip
import json
import os
import re

import boto3

TOPIC_ARN = os.environ["TOPIC_ARN"]
STACK = os.environ.get("STACK_NAME", "payments-hybrid")
WATCHED = json.loads(os.environ.get("WATCHED_CONTAINERS", "{}"))  # role -> container name
sns = boto3.client("sns")

# What the reader should know and do, per event. The line itself carries the ids and the specific cause.
EXPLAIN = {
    "PAYMENT_OUTCOME_UNKNOWN": "The payment provider did not confirm a payment request (timeout, outage or a database failure right after the call). The payment is kept as UNKNOWN with its saved request and idempotency key; the buyer is told not to pay again. Step Functions recovery asks the provider and records it once. Act only if a finance case follows.",
    "REFUND_OUTCOME_UNKNOWN": "The payment provider did not confirm a refund request. The refund is kept as UNKNOWN with its saved request; recovery asks the provider and records it once. Act only if a finance case follows.",
    "FINANCE_CASE_OPENED": "A payment or refund needs a human decision; caseReason says which. Carrier finance decides in the Finance screen; engineering checks the matching recovery email for a defect.",
    "RECOVERY_NEEDS_REVIEW": "A Step Functions recovery workflow ended without a money result; cause says why (WORKFLOW_FAILED with the execution's error, CHECK_LIMIT, CAMPAIGN_DEADLINE). The payment/refund stays reserved and goes to finance review.",
    "RECOVERY_DISPATCH_FAILING": "The app's recovery dispatcher cannot call AWS Step Functions (error code in the line, for example ExpiredToken or AccessDeniedException). Uncertain payments wait unrecovered until it works again. Check the dispatcher's AWS access key.",
    "PROVIDER_PAUSED": "The app paused new calls to the payment provider after repeated failures (circuit breaker). Buyers get a 'try again shortly' answer and nothing is charged. It resumes by itself after the pause.",
    "WEBHOOK_REJECTED": "The app refused a payment provider webhook; stage says which check failed (SIGNATURE: the secret saved in the provider's webhook differs from the app's; ACCOUNT: another merchant account). Payments are still confirmed by recovery polling, only more slowly. Fix the webhook secret or account setting. (At most one email per stage every 15 minutes.)",
    "PROTECTED_ROW_CHANGE_BLOCKED": "PostgreSQL refused to delete, truncate or rewrite a protected payment/order row (saved payment request, journal, order). The data is intact. The line shows the database user, application and client that tried it: find out why and stop it.",
    "POSTGRES_FAILURE": "The laptop PostgreSQL shut down, crashed or ran out of disk. Payments fail safely while it is down; anything in flight is recovered after it returns. Check the postgres container and disk space.",
    "CONTAINER_STOPPED": "A laptop container stopped. Exit code 0 or 143 means it was stopped on purpose; 137 means it was killed (SIGKILL or out of memory); anything else is a crash. A payment in flight stays saved and is recovered after restart.",
}
CODE = re.compile(r"ALERT code=([A-Z][A-Z0-9_]{2,63}) ")
POSTGRES_BLOCKED = re.compile(r"cannot be deleted|cannot be truncated|is immutable|update or delete on table")


def classify(group, message):
    """Returns (code, line) or None when the line is not an emailable event."""
    if group.endswith("/docker-events"):
        try:
            event = json.loads(message)
        except ValueError:
            return None
        attributes = event.get("Actor", {}).get("Attributes", {})
        name = attributes.get("name", "")
        roles = [role for role, container in WATCHED.items() if container == name]
        action = event.get("Action") or event.get("status")  # Docker 25+ dropped the legacy status field
        if event.get("Type", "container") != "container" or action not in ("die", "oom") or not roles:
            return None
        return "CONTAINER_STOPPED", f"{roles[0]} container {name} event={action} exitCode={attributes.get('exitCode', 'n/a')}"
    if group.endswith("/postgres"):
        return ("PROTECTED_ROW_CHANGE_BLOCKED" if POSTGRES_BLOCKED.search(message) else "POSTGRES_FAILURE"), message
    match = CODE.search(message)
    if not match or match.group(1) not in EXPLAIN:
        return None
    return match.group(1), message[message.index("ALERT code="):]


def handler(event, _context):
    payload = json.loads(gzip.decompress(base64.b64decode(event["awslogs"]["data"])))
    if payload.get("messageType") != "DATA_MESSAGE":
        return {"sent": 0}
    group = payload["logGroup"]
    found = [hit for hit in (classify(group, e["message"].strip()) for e in payload["logEvents"]) if hit]
    if not found:
        return {"sent": 0}
    codes = list(dict.fromkeys(code for code, _ in found))
    critical = any(code in ("DATABASE_UNAVAILABLE", "POSTGRES_FAILURE", "CONTAINER_STOPPED", "PROTECTED_ROW_CHANGE_BLOCKED") or "severity=CRITICAL" in line for code, line in found)
    subject = f"[{STACK}] {'CRITICAL' if critical else 'WARNING'} {', '.join(codes)}"[:99]
    body = [f"Source: {group} / {payload['logStream']}", ""]
    for code in codes:
        body += [f"== {code}", EXPLAIN[code], "", "What the system logged:"]
        lines = [line for c, line in found if c == code]
        body += [f"  {line[:600]}" for line in lines[:20]]
        if len(lines) > 20:
            body.append(f"  ... and {len(lines) - 20} more in the log group")
        body.append("")
    sns.publish(TopicArn=TOPIC_ARN, Subject=subject, Message="\n".join(body))
    return {"sent": 1, "codes": codes}

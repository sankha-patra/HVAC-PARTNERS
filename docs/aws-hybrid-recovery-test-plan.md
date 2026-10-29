# Hybrid recovery test on AWS: plan for approval

Status: **proposal, nothing created yet.** Written 27 Oct 2026.

**Goal:** run the same 11 recovery cases as the local lab, with **real AWS Step Functions and real AWS Lambda**. Everything else stays on the laptop. The local emulators could not give us four things this test checks:
- how Step Functions really retries and catches errors;
- the error types Lambda actually reports;
- AWS replacing a crashed Lambda environment;
- real cold starts and throttling.

## 1. What runs where

```
Laptop (Windows, Docker)                         AWS, Sydney (ap-southeast-2)
-----------------------------------------        -----------------------------------------------
Postgres (lab DB, 127.0.0.1:55433)               Step Functions STANDARD state machine "recovery-lab"
Fake Stripe (127.0.0.1:12111)                      |  Task -> Lambda, Retry/Catch, Wait loop
lab.py harness + fault injection                   v
Dispatcher + reconciler  --(AWS API, HTTPS)-->   Lambda "recovery-lab-recover" (Java 21, arm64, 1 GB)
Tailscale client                                   - real recovery code (same snapshot as the local lab)
  tailscale serve tcp 5432  -> localhost:55433     - Tailscale in userspace mode, started by the function
  tailscale serve tcp 12111 -> localhost:12111     - JDBC and Stripe HTTP go through Tailscale's local proxies
        ^                                                 |
        +---------- WireGuard tunnel (Tailscale) ---------+
```

- The Lambda is **not** in a VPC and has **no** function URL, so nothing on the internet can call it.
- The laptop exposes **no** public port. The only way in is the tailnet, and the tailnet policy lets the Lambda reach two ports only.
- No real Stripe and no `sk_test_` key are involved. The fake Stripe stays on the laptop.

## 2. Tunnel choice

| Option | Exposes the lab DB to the public internet? | Lambda side | Cost | Verdict |
|---|---|---|---|---|
| **Tailscale** (userspace, documented for Lambda) | **No.** Only tailnet devices, limited further by the policy | `tailscaled --tun=userspace-networking`, SOCKS5 and HTTP proxies on localhost | Free Personal plan; 1,000 ephemeral-device minutes a month | **Chosen** |
| ngrok TCP endpoint | Yes. A public host:port, protected by the DB password only | Plain JDBC | Free, but TCP needs a card on file | Rejected |
| Cloudflare Tunnel | No, but raw TCP needs `cloudflared` on the client too | Same complexity as Tailscale, less documented for Lambda | Free | Rejected |
| Router port forward or public IP | Yes | Plain JDBC | Free | Rejected |

## 3. Security

1. **Tailnet policy first.** The default Tailscale policy lets every device reach every other device. Replace it **before** creating the Lambda's key:

   ```json
   {
     "tagOwners": { "tag:recovery-lab": ["autogroup:admin"] },
     "hosts":     { "lab-laptop": "100.x.y.z" },
     "grants": [
       { "src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["*"] },
       { "src": ["tag:recovery-lab"], "dst": ["lab-laptop"], "ip": ["tcp:5432", "tcp:12111"] }
     ]
   }
   ```

   - Your own devices keep talking to each other.
   - The Lambda (tagged `tag:recovery-lab`) can reach only Postgres and the fake Stripe on the laptop.
   - Nothing can connect *to* the Lambda.
   - `100.x.y.z` is the laptop's Tailscale address, shown after install.
2. **Auth key** — you create it; I never see it.
   - Settings: reusable, **ephemeral**, pre-approved, tagged `tag:recovery-lab`, expiry 7 days.
   - Store it in the Sydney console as an SSM Parameter Store **SecureString** named `/recovery-lab/tailscale-auth-key`. It's encrypted with the AWS-managed `aws/ssm` key, and standard parameters are free.
   - The Lambda role can read only that one parameter.
   - Revoke the key after the test.
3. **What is exposed:** only the disposable lab database (no real data, lab-only password) and the fake Stripe, and only to the tagged Lambda nodes. The repo's billing database and any other services on the laptop are not reachable.
4. **Encryption:** traffic is end-to-end encrypted by WireGuard. Inside the tunnel, the JDBC connection is plain TCP to the lab DB.
5. **AWS permissions:** two new roles, each with one job.
   - The Lambda role can write its own logs and read that one parameter.
   - The Step Functions role can invoke that one function.
   - Trust policies use `aws:SourceAccount` and `aws:SourceArn` conditions.
   - The project's built-in guardrails stay in place.
6. **Teardown:**
   - delete the CloudFormation stack, which removes every AWS resource listed below;
   - revoke the auth key;
   - run `tailscale serve reset`;
   - ephemeral Lambda devices remove themselves.

## 4. Exact AWS resources: one CloudFormation stack, `recovery-lab`, in ap-southeast-2

| # | Type | Name | Settings |
|---|---|---|---|
| 1 | S3 bucket | `recovery-lab-artifacts-014805809443` | Holds the two deployment zips (CloudFormation needs code in S3). Public access blocked, SSE-S3 encryption, objects expire after 7 days. Emptied by the teardown script |
| 2 | Lambda layer | `recovery-lab-tailscale` | Official Tailscale static arm64 binaries (`tailscale`, `tailscaled`), checksum checked against pkgs.tailscale.com |
| 3 | IAM role and inline policy | `recovery-lab-lambda` | Trust: lambda.amazonaws.com. Allows: `logs:CreateLogStream`/`PutLogEvents` on its log group, `ssm:GetParameter` on the one parameter, `kms:Decrypt` only via SSM |
| 4 | CloudWatch log group | `/aws/lambda/recovery-lab-recover` | Retention 1 day |
| 5 | Lambda function | `recovery-lab-recover` | Java 21, arm64, 1024 MB, timeout 35 s, no VPC, no function URL, no reserved concurrency (the account limit is 10, and AWS keeps at least 10 unreserved). Environment: lab JDBC URL (laptop Tailscale IP), lab DB user and password, fake-Stripe URL, `LAB_MAX_WAIT=5`, `LAB_BROKEN=false`, parameter name |
| 6 | IAM role and inline policy | `recovery-lab-sfn` | Trust: states.amazonaws.com. Allows: `lambda:InvokeFunction` on function 5 only |
| 7 | Step Functions state machine | `recovery-lab` | STANDARD. Same definition as the local lab (`recovery-workflow.asl.json`) with the real function ARN. No CloudWatch logging (execution history is kept free for 90 days) |
| 8 | AWS Budget | `recovery-lab-5usd` | Monthly cost budget of $5, **credits excluded** so it tracks real usage. Email at 50% and 100% to your address |

**You create one item outside the stack:** the SSM parameter in section 3.2, so the key never passes through me or the stack.

## 5. Cost, using the Sydney Price List API (checked 27 Oct 2026)

| Item | Price | Full 11-case run (estimate) |
|---|---|---|
| Lambda | $0.20 per 1M requests; $0.0000133 per GB-second (arm64) | ~3,000 calls × 1 GB × ~1 s = ~3,000 GB-seconds. Always-free tier is 1M requests and 400,000 GB-seconds a month, so **$0** |
| Step Functions STANDARD | $0.000025 per state transition | Up to ~9,000 transitions (3 per loop). The first 4,000 a month are free, so **≤ $0.13** |
| CloudWatch Logs | Always-free 5 GB a month | < 100 MB, so **$0** |
| S3 | Pennies per GB-month | ~70 MB for under 7 days, so **< $0.01** |
| SSM SecureString (standard), KMS `aws/ssm` | Free tier | **$0** |
| Data out to the internet (through Tailscale) | 100 GB a month always free | A few MB, so **$0** |
| **Total** | | **≈ $0.00–0.15 per full run, $0 while idle** |

**Tailscale** is free on the Personal plan. The limit to watch is **1,000 ephemeral-device minutes a month**. Each warm Lambda environment counts while connected, so a full run (~90 min × ~3 environments) uses about 270–400 minutes. That allows about two full runs a month. I'll check usage in the Tailscale admin console after the first run.

## 6. How each case is produced on AWS

| Case | Fault on AWS | Fault on the laptop |
|---|---|---|
| lost_reply, stripe_outage, unknown_forever | | Fake Stripe drop, outage or down |
| kill_mid_recovery | Lab flag makes the handler call `Runtime.halt(1)` mid-recovery. Real Lambda reports `Runtime.ExitError` and replaces the environment | Fake Stripe hang |
| tx2_fails_once | | `lab.tx2` trigger in Postgres |
| two_drivers | Two executions for one payment | |
| missed_start | | Dispatcher crashes after StartExecution; the reconciler must notice |
| all_down | `put-function-concurrency 0` throttles the function (real `Lambda.TooManyRequestsException`), then it's restored | |
| db_down | | Stop the Postgres container |
| webhook_race | | Signed paid event during recovery |
| bad_deploy | `update-function-configuration LAB_BROKEN=true`, then reverted | |

**Money rules checked in every case** (same as local): one Stripe object per payment, never a wrong FAILED, the case opened when it should be, and capture recorded exactly once.

## 7. What this shows and what it doesn't

- **Shows:**
  - real Step Functions Standard semantics: Retry backoff, Catch, Wait, execution-name idempotency, history;
  - real Lambda error mapping, timeouts, crashed-environment replacement and throttling;
  - Java 21 arm64 cold starts;
  - whether the dispatcher plus reconciler design survives real AWS behaviour.
- **Doesn't show:**
  - the production network path (Lambda in a VPC to RDS or RDS Proxy);
  - realistic latency. Every SQL round trip goes Sydney ↔ India, so timings are not production numbers.

  Lambda freeze and thaw can also briefly break the tunnel. That would show up as retried Lambda errors that production wouldn't have. I'll report those separately, not as recovery failures.
- **Upgrade path:** the all-AWS variant (RDS micro plus a small EC2 instance for the fake Stripe, ≈ $0.06/h) covers the network path if we need it later.

## 8. Lab-only code changes

All changes go in the scratchpad lab snapshot. The repo is untouched.

1. **`LabTailnet`:** on first invocation it does the following:
   - reads the auth key from SSM with the Lambda's own role;
   - starts `tailscaled --tun=userspace-networking --state=mem: --socks5-server=localhost:1055 --outbound-http-proxy-listen=localhost:1056`;
   - runs `tailscale up --auth-key=file:… --advertise-tags=tag:recovery-lab`;
   - waits until the laptop answers.

   JDBC uses the SOCKS proxy (`socksProxyHost` system property; the URL uses the laptop's 100.x IP, not a hostname). The fake-Stripe HttpClient uses the HTTP proxy.
2. **`LabRecoveryHandler`:** adds the `halt` fault hook for kill_mid_recovery.
3. **Lambda package:** drop the jars the recovery path doesn't load (kafka-clients, tomcat, zstd, snappy and others). It's currently 49.8 MB zipped.
4. **`lab.py`:** new option `B-aws`:
   - real Step Functions client (`payments-lab` profile, Sydney);
   - real function ARN;
   - the AWS-side faults from section 6;
   - evidence includes each execution's history events.

## 9. Order of work and approval points

| Step | Who | Needs your OK |
|---|---|---|
| 1. Create a Tailscale account (with MFA) and install Tailscale on the laptop | You (I can run `winget install Tailscale.Tailscale` if you prefer) | — |
| 2. Paste the tailnet policy from section 3.1; tell me the laptop's 100.x address | You | — |
| 3. Create the auth key; store it as the SSM SecureString in the Sydney console | You | — |
| 4. Download the Tailscale arm64 binaries from pkgs.tailscale.com (checksum checked); make the lab code changes; build zips; `validate-template` (read-only) | Me | **Yes:** download |
| 5. Deploy stack `recovery-lab` (resources 1–8) | Me | **Yes:** create; confirm the budget email address |
| 6. Smoke test: one invocation proves the tunnel works, then `lost_reply`, then all 11 cases | Me | — |
| 7. Report: AWS results next to the local A/B/C results | Me | — |
| 8. Teardown: delete the stack, revoke the key, `tailscale serve reset` | Me and you | **Yes:** delete |

## 10. Local three-way results (emulators, 27 Oct 2026)

Every option held the money rules in every case that applies to it (exactly one Stripe object per payment, no wrong FAILED). Every case resolved without a person, except `unknown_forever`, which correctly opens a case. Times are seconds from the fault clearing to the payment being resolved:

| Case | A: Spring worker | B: Step Functions Local + Lambda emulator | C: scheduled Lambda (2 s ticks) |
|---|---|---|---|
| lost_reply | 1.2 | 4.6 | 3.7 |
| kill_mid_recovery | 16.0 | 11.0 | 3.6 |
| tx2_fails_once | 1.2 | 2.3 | 1.2 |
| stripe_outage | 3.5 | 14.2 | 5.6 |
| unknown_forever (case opened after) | 8.4 | 25.7 | 9.3 |
| two_drivers | 1.2 | 4.5 | 2.7 |
| missed_start | n/a | 1.2 (reconciler) | n/a |
| all_down | 13.5 | 0.2 | 3.5 |
| db_down | 1.2 | 5.5 | 1.3 |
| webhook_race (captured after) | 0.1 | 0.1 | 0.2 |
| bad_deploy | 17.6 | 0.1 | 13.4 (rerun) |

These are emulator numbers with lab-sized waits, not production figures. Three failures during the runs came from the lab itself, not the designs; all are fixed:
- the Lambda emulator crashed after containers were recreated;
- my proxy dropped Step Functions Local's chunked request bodies;
- deleting and immediately recreating a state machine lost the new one.

## 11. First AWS run: cold-start connection failure (29 Oct 2026)

The first Step Functions run failed before claiming the payment. It was a **warm** Lambda resumed after an idle freeze: the log shows a Hikari clock-leap warning and a connection-acquisition timeout of about 3 s. The order stayed saved with zero captures and stayed owned by review. Code changes after it:

- **No blanket eviction.** The connection gate no longer soft-evicts every idle connection per invocation. Hikari already validates a connection idle past its bypass window when it is borrowed, so the eviction only forced a new tunnel handshake each time.
- **Wait for the peer, not just the daemon.** `tailscale status` reporting `Running` means the control plane accepted the node, not that a WireGuard path to the laptop exists. Bootstrap now TSMP-pings the laptop, retrying for up to 8 s, before any JDBC connect.
- **Cold start in the init phase.** The handler starts the tunnel and the Spring context while Lambda constructs it, so that work no longer comes out of the invocation time that claim admission (≥ 20 s remaining) measures. Failures there are ignored and repeated, then reported, by the invocation.

**Deployment for the retest:** reuse the Codex kit in `Documents/Codex/2026-10-26/pl/work/aws-hybrid-runtime/application` (CloudFormation template, `recovery.asl.json`, deploy and run scripts). Its Lambda timeout is already 60 s; the pool's `connectionTimeout` is now 10 s instead of the 3 s that timed out.

## 12. AWS retest with the fixes (29 Oct 2026, 18:51–19:06 UTC): all passed

Code from commit `e7b164f`, deployed as stack `payments-recovery-failure-lab-v2` (Lambda version 1, Standard workflow version 1, Sydney) with the Codex kit copied to `aws-hybrid-runtime/application-v2`. Laptop database, fake Razorpay, app, Kafka, Debezium and mail ran locally; the Lambda reached them over Tailscale.

| Check | Result |
|---|---|
| Direct guards: extra input field; operation outside the campaign | Both rejected, no money or provider change |
| App stopped, "workflow started" reply lost and resent | Captured once (1 Lambda attempt) |
| Postgres killed mid-payment, restarted | Captured once |
| App JVM killed mid-payment | Captured once |
| Lambda process exits after claiming | Captured once after retries (5 attempts, real `Runtime.ExitError`) |
| Lambda times out after claiming | Captured once after retries (4 attempts) |
| Lambda throws after claiming | No capture; handed to finance review |
| Razorpay has no record of the payment | GET only, no new payment, stays UNKNOWN for review |
| Expired campaign | Rejected |
| **Warm Lambda, idle DB socket killed (the original failure)** | **Same execution environment detected the dead socket, reconnected, answered correctly** |
| Downstream: Kafka → buyer order → email | 5 recovered payments became one PAID order and one confirmation email each; 2 unresolved produced no capture and no receipt |

Usage: 6 workflow starts (7 submissions), 6 direct calls, 15 Lambda requests. The campaign journal keeps one released reservation: a direct call the AWS CLI rejected locally before sending.

Harness fixes needed on this machine: Docker nanosecond timestamps, and `lambda invoke` needing `--function-name` as a flag on AWS CLI 2.38. The resumed run skipped the warm probe, so it was run separately (`run_warm_probe.py`); it kills any idle pooled socket, since the fixed gate reuses connections instead of opening a fresh one.

Cleanup verified: stack, bucket, function, layer, workflow, roles and fixture parameter gone (only the pre-existing Tailscale key remains); Tailscale routes removed; lab database role and its local credential removed; lab containers stopped with volumes kept.

Still not covered: real Razorpay (a synthetic provider stood in), the production `StepFunctionsRecoveryRemote` SDK transport (a CLI bridge drove the real dispatcher), and the production network path (Lambda in a VPC to RDS).

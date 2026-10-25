# Handing a decision to Carrier

The application team (LTIMindtree) flags. Carrier decides. This runbook says how a flagged item reaches the right
Carrier person, with everything they need, and how we record that we handed it over.

## Who does what

- **Application team:** sees every alert first, checks it is not our defect, collects the facts, emails them to
  Carrier, records the handoff on the case, and follows up. We never approve, reject, release or refund, and we
  never contact a bank, Stripe on Carrier's behalf, or the paying company.
- **Carrier:** decides and acts in the finance workspace with its own sign-in and role: FINANCE (verify, request
  release, close, record a Stripe confirmation) or REFUND_APPROVER (approve or reject, always a different person
  from the requester).

## Two emails

1. **Automatic notice** from Alertmanager to the Carrier group mailbox for the alert's `decision` label. It has the
   alert name, summary, start time and environment. Metrics carry counts only, so it has no case IDs and no payment
   data. Config: `monitoring/alertmanager/alertmanager.example.yml`; text: `monitoring/alertmanager/templates/billing-handoff.tmpl`.
2. **Handoff email** written by the app team engineer, one per case, with the details below. This is the email the
   approver works from.

Carrier provides one group mailbox (or ticket queue) per role. Never send to a personal address you picked
yourself: Carrier decides who is responsible, and people change.

| What fired | Carrier role that decides | Mailbox (placeholder) |
|---|---|---|
| `BillingReleaseAwaitingApproval` | REFUND_APPROVER, not the person who requested the release | `carrier-billing-approvers` |
| An approved refund that cannot be sent and is not our defect (after `BillingRefundStuck`) | REFUND_APPROVER: reject it or keep it | `carrier-billing-approvers` |
| `BillingWebhookNeedsMatching`, or an open finance case that needs a reference verified or a release requested | FINANCE | `carrier-billing-finance` |
| `BillingPaymentProcessingOverdue`, `BillingRefundProcessingOverdue` | Treasury: contact the bank or Stripe | `carrier-billing-treasury` |
| `BillingPaymentActionOverdue` | Business: contact the paying company | `carrier-billing-business` |
| Any alert with `decision: none` | Nobody at Carrier. The app team fixes it. | none |

## Before you send

- Check it is not our defect: application logs by the case's reference ID, the metric behind the alert, dead
  letters, recent deployments. If it is our defect, fix it; do not hand it over.
- Open the case in the finance workspace (staff-only provider evidence is visible to the OPS role).
- Collect the facts in the template. Copy IDs from the screen; do not retype them.

## Handoff email template

```
Subject: [Decision needed] <alert or case reason> - case <case ID> - <environment>

What happened:     <one sentence, e.g. "Approved refund was never sent; Stripe has no record of it.">
Case:              <case ID>, reason <case reason>, opened <UTC time>
Payment / refund:  payment <payment ID>, refund <refund ID if any>, invoice(s) <numbers>
Accounts:          paying <account>, receiving <account>
Amount:            <amount> USD
Our status:        <status in our records> since <UTC time> (<age>)
What we checked:   <Stripe search result and when>, <webhook inbox state>, Stripe request IDs <IDs>,
                   log reference IDs <IDs>, checked at <UTC time>
What we did not do: no money moved, nothing released, nothing rejected
Decision needed:   <the actions the finance workspace offers for this case, e.g. approve release / reject>
Where to act:      Finance workspace > Cases requiring review > case <case ID>
Alert:             <alert name and link>
Contact:           <app team on-call>
```

**Never include** card numbers, bank account or routing numbers, Stripe keys, webhook secrets, passwords or full
provider payloads. The application's note and confirmation forms reject long digit runs for the same reason.

## After you send

1. Add a note on the case (Add note): `Handed to Carrier <role> by email <message or ticket ID> at <UTC time>.`
   The note is kept in the case history with your name, so the handoff is auditable.
2. Follow up at the alert's repeat interval if there is no answer (demo defaults: 12 h finance and approvers,
   24 h treasury and business). Response times and escalation contacts are Carrier's to set.
3. When Carrier has acted, check that the case is closed and the alert has resolved. Add a final note if the
   outcome needs explaining.

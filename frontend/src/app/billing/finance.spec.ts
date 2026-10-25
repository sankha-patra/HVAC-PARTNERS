import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../environments/environment';
import { FinanceComponent } from './finance';
import { AuthService } from '../shared/auth.service';
import { signal } from '@angular/core';
import { FinanceCase, FinancePayment, FinanceRefund } from './billing-api';

describe('Finance cases', () => {
  let component: FinanceComponent;
  let http: HttpTestingController;
  const url = `${environment.apiUrl}/billing/finance/cases`;
  const base = `${environment.apiUrl}/billing/finance`;
  const payment: FinancePayment = { id: 'intent-a', accountId: 'account-a', merchantId: 'merchant-a', accountName: 'Example Mechanical',
    amountMinor: 12525, refundedMinor: 0, availableRefundMinor: 12525, currency: 'USD', status: 'CAPTURED',
    method: 'CARD', createdAt: '2026-10-17T00:00:00Z', canRequestRefund: true };
  const refund: FinanceRefund = { id: 'refund-a', intentId: payment.id, amountMinor: 110, reason: 'Duplicate service charge',
    requestedBy: 'requester', status: 'REQUESTED', createdAt: '2026-10-17T00:00:00Z', canApprove: false };
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { user: signal({ userId: 'finance-user' }) } }] });
    component = TestBed.runInInjectionContext(() => new FinanceComponent());
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { component.ngOnDestroy(); http.verify(); sessionStorage.clear(); });
  function initialize() {
    component.ngOnInit();
    http.expectOne(url).flush([]);
    http.expectOne(`${base}/payments`).flush([payment]);
    http.expectOne(`${base}/refunds`).flush([refund]);
  }
  function draft() {
    component.beginRefund(payment.id);
    component.refundAmount = '1.10';
    component.refundReason = refund.reason;
  }
  function refreshLists(refunds = [refund]) {
    http.expectOne(`${base}/refunds`).flush(refunds);
    http.expectOne(`${base}/payments`).flush([payment]);
  }
  it('shows access required instead of treating a forbidden queue as empty', () => {
    component.load();
    http.expectOne(url).flush({}, { status: 403, statusText: 'Forbidden' });
    expect(component.loaded()).toBe(false);
    expect(component.error()).toContain('Finance access is required');
  });
  it('shows an empty queue only after a successful server response', () => {
    component.load();
    http.expectOne(url).flush([], { status: 200, statusText: 'OK' });
    expect(component.loaded()).toBe(true);
    expect(component.cases()).toEqual([]);
    component.load();
    http.expectOne(url).flush({}, { status: 503, statusText: 'Unavailable' });
    expect(component.loaded()).toBe(false);
    expect(component.error()).toContain('does not mean there are no exceptions');
  });

  it('retains the exact refund amount, reason and key after a lost request response', () => {
    initialize(); draft(); component.sendRefundRequest();
    const first = http.expectOne(`${base}/intents/intent-a/refunds`);
    const key = first.request.headers.get('Idempotency-Key');
    expect(first.request.body).toEqual({ amountMinor: 110, reason: refund.reason });
    first.error(new ProgressEvent('error'));
    component.refundAmount = '50.00'; component.refundReason = 'Changed draft';
    component.closeRefundPanel();
    expect(component.refundRequest()?.key).toBe(key);
    component.sendRefundRequest();
    const retry = http.expectOne(`${base}/intents/intent-a/refunds`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual({ amountMinor: 110, reason: refund.reason });
    retry.flush(refund); refreshLists();
    expect(component.refundRequest()?.refundId).toBe(refund.id);
    expect(component.actionMessage()).toContain('separate from approval');
  });

  it('recovers an uncertain refund after reload without changing the operation key', () => {
    initialize(); draft(); component.sendRefundRequest();
    const first = http.expectOne(`${base}/intents/intent-a/refunds`);
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 503, statusText: 'Unavailable' });
    component.ngOnDestroy();
    component = TestBed.runInInjectionContext(() => new FinanceComponent());
    initialize();
    expect(component.refundRequest()?.key).toBe(key);
    component.sendRefundRequest();
    const retry = http.expectOne(`${base}/intents/intent-a/refunds`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(refund); refreshLists();
  });

  it('rejects fractional cents and over-refunds before any request', () => {
    initialize(); draft();
    for (const amount of ['1.101', '0', '-1.00', '1e2', '125.26']) {
      component.refundAmount = amount;
      component.sendRefundRequest();
      expect(component.refundRequest()).toBeNull();
      http.expectNone(`${base}/intents/intent-a/refunds`);
    }
  });

  it('honors server action flags instead of deriving refund authority from visible rows', () => {
    initialize();
    component.payments.set([{ ...payment, canRequestRefund: false }]);
    component.beginRefund(payment.id);
    expect(component.draftPayment()).toBeNull();
    component.approveRefund(refund.id);
    http.expectNone(`${base}/refunds/refund-a/approve`);
    expect(component.uncertainApprovals()).toEqual([]);
  });

  it('lets an eligible checker approve and preserves the server unknown result', () => {
    initialize();
    component.refunds.set([{ ...refund, requestedBy: 'another-finance-user', canApprove: true }]);
    component.approveRefund(refund.id);
    const approval = http.expectOne(`${base}/refunds/refund-a/approve`);
    expect(approval.request.method).toBe('POST');
    approval.flush({ ...refund, approvedBy: 'finance-user', status: 'UNKNOWN' });
    refreshLists([{ ...refund, approvedBy: 'finance-user', status: 'UNKNOWN', canApprove: false }]);
    expect(component.actionMessage()).toContain('final outcome is not confirmed');
    expect(component.refunds()[0].status).toBe('UNKNOWN');
    expect(component.refunds()[0].canApprove).toBe(false);
    component.approveRefund(refund.id);
    http.expectNone(`${base}/refunds/refund-a/approve`);
  });

  it('blocks repeat approval after a lost response until the server status is refreshed', () => {
    initialize();
    component.refunds.set([{ ...refund, canApprove: true }]);
    component.approveRefund(refund.id);
    http.expectOne(`${base}/refunds/refund-a/approve`).flush({}, { status: 504, statusText: 'Timeout' });
    expect(component.uncertainApprovals()).toEqual([refund.id]);
    expect(component.actionError()).toContain('no confirmed response');
    component.approveRefund(refund.id);
    http.expectNone(`${base}/refunds/refund-a/approve`);
    component.loadRefunds();
    http.expectOne(`${base}/refunds`).flush([{ ...refund, approvedBy: 'finance-user', status: 'PROCESSING', canApprove: false }]);
    expect(component.uncertainApprovals()).toEqual([]);
    expect(component.refunds()[0].status).toBe('PROCESSING');
    component.approveRefund(refund.id);
    http.expectNone(`${base}/refunds/refund-a/approve`);
  });

  it('does not call a failed approval successful or overwrite the server maker-checker denial', () => {
    initialize();
    component.refunds.set([{ ...refund, canApprove: true }]);
    component.approveRefund(refund.id);
    http.expectOne(`${base}/refunds/refund-a/approve`).flush({ code: 'SEPARATION_OF_DUTIES' }, { status: 403, statusText: 'Forbidden' });
    expect(component.actionMessage()).toBe('');
    expect(component.refunds()[0].status).toBe('REQUESTED');
    expect(component.uncertainApprovals()).toEqual([refund.id]);
  });

  it('rejects a requested refund with a recorded reason and refreshes balances', () => {
    initialize();
    component.refunds.set([{ ...refund, canReject: true }]);
    component.beginRefundRejection(refund.id);
    expect(component.rejectingRefund()).toBe(refund.id);
    component.refundRejectionNote = 'too short';
    component.rejectRefund();
    http.expectNone(`${base}/refunds/refund-a/reject`);
    expect(component.actionError()).toContain('10 to 1000 characters');
    component.refundRejectionNote = '  Customer withdrew the refund request  ';
    component.rejectRefund();
    const rejection = http.expectOne(`${base}/refunds/refund-a/reject`);
    expect(rejection.request.method).toBe('POST');
    expect(rejection.request.body).toEqual({ note: 'Customer withdrew the refund request' });
    rejection.flush({ ...refund, status: 'REJECTED' });
    refreshLists([{ ...refund, status: 'REJECTED', canReject: false }]);
    expect(component.actionMessage()).toContain('No money moved');
    expect(component.rejectingRefund()).toBeNull();
    expect(component.refunds()[0].status).toBe('REJECTED');
  });

  it('offers rejection only when the server grants it and keeps the form after a conflict', () => {
    initialize();
    component.beginRefundRejection(refund.id);
    expect(component.rejectingRefund()).toBeNull();
    component.refunds.set([{ ...refund, canReject: true }]);
    component.beginRefundRejection(refund.id);
    component.refundRejectionNote = 'Customer withdrew the refund request';
    component.rejectRefund();
    http.expectOne(`${base}/refunds/refund-a/reject`).flush({ code: 'CONFLICT' }, { status: 409, statusText: 'Conflict' });
    expect(component.actionError()).toContain('changed since it was loaded');
    expect(component.rejectingRefund()).toBe(refund.id);
    expect(component.actionMessage()).toBe('');
  });

  it('only clears an unrecorded request when the server explicitly reports no refund was created', () => {
    initialize(); draft(); component.sendRefundRequest();
    http.expectOne(`${base}/intents/intent-a/refunds`).flush({ code: 'REFUND_UNAVAILABLE' }, { status: 409, statusText: 'Conflict' });
    expect(component.refundRequest()?.definitiveRejection).toBe(true);
    component.closeRefundPanel();
    http.expectOne(`${base}/payments`).flush([payment]);
    expect(component.refundRequest()).toBeNull();
  });

  it('preserves a generic conflict as unresolved instead of issuing a new refund key', () => {
    initialize(); draft(); component.sendRefundRequest();
    const first = http.expectOne(`${base}/intents/intent-a/refunds`);
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({ code: 'CONFLICT' }, { status: 409, statusText: 'Conflict' });
    component.closeRefundPanel();
    expect(component.refundRequest()?.key).toBe(key);
    component.sendRefundRequest();
    const retry = http.expectOne(`${base}/intents/intent-a/refunds`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(refund); refreshLists();
  });

  const stuckCase: FinanceCase = { id: 'case-a', intentId: payment.id, reason: 'PAYMENT_REPLAY_UNSAFE', status: 'OPEN',
    createdAt: '2026-10-21T00:00:00Z', canVerifyReference: true, canRequestRelease: true, canApproveRelease: false, canClose: false };
  function refreshAfterCaseAction() {
    http.expectOne(url).flush([]);
    http.expectOne(`${base}/payments`).flush([payment]);
    http.expectOne(`${base}/refunds`).flush([refund]);
  }

  it('explains when the provider blocks a release, without calling it approved', () => {
    initialize();
    const pending: FinanceCase = { ...stuckCase, releaseRequestedBy: 'another-user', canApproveRelease: true };
    for (const [code, text] of [['PROVIDER_HAS_OPERATION', 'nothing was released'], ['PROVIDER_ABSENCE_NOT_CONFIRMED', 'could not confirm']]) {
      component.beginCaseAction(pending, 'approve-release');
      component.submitCaseAction();
      http.expectOne(`${base}/cases/case-a/release-approval`).flush({ code }, { status: 409, statusText: 'Conflict' });
      expect(component.caseError()).toContain(text);
      expect(component.caseMessage()).toBe('');
    }
  });

  it('offers a case action only when the server grants it to this user', () => {
    initialize();
    component.beginCaseAction(stuckCase, 'approve-release');
    expect(component.activeCase()).toBeNull();
    component.beginCaseAction(stuckCase, 'close');
    expect(component.activeCase()).toBeNull();
    component.beginCaseAction(stuckCase, 'verify');
    expect(component.caseCommand()).toBe('verify');
  });

  it('sends a provider reference with the reviewer note, then refreshes every list', () => {
    initialize();
    component.beginCaseAction(stuckCase, 'verify');
    component.caseReference = 'cs_test_found123';
    component.caseNote = 'Found the session by intent metadata in the dashboard';
    component.submitCaseAction();
    const request = http.expectOne(`${base}/cases/case-a/provider-reference`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ reference: 'cs_test_found123', note: 'Found the session by intent metadata in the dashboard' });
    request.flush({ ...stuckCase, status: 'RESOLVED', resolution: 'STAFF_VERIFIED_PROVIDER_REFERENCE' });
    refreshAfterCaseAction();
    expect(component.activeCase()).toBeNull();
    expect(component.caseMessage()).toContain('provider confirmed');
  });

  it('keeps the case open and says nothing changed when the provider does not verify the reference', () => {
    initialize();
    component.beginCaseAction(stuckCase, 'verify');
    component.caseReference = 'cs_test_wrong';
    component.caseNote = 'Copied from the dashboard search results';
    component.submitCaseAction();
    http.expectOne(`${base}/cases/case-a/provider-reference`)
      .flush({ code: 'PROVIDER_REFERENCE_NOT_VERIFIED' }, { status: 409, statusText: 'Conflict' });
    expect(component.activeCase()?.id).toBe('case-a');
    expect(component.caseError()).toContain('Nothing was changed');
  });

  it('requires a meaningful note before any request leaves the browser', () => {
    initialize();
    component.beginCaseAction(stuckCase, 'request-release');
    component.caseNote = 'too short';
    component.submitCaseAction();
    http.expectNone(`${base}/cases/case-a/release-request`);
    expect(component.caseError()).toContain('10 to 1000');
  });

  it('approves a release without a note and explains a separation-of-duties refusal', () => {
    initialize();
    const pending = { ...stuckCase, canVerifyReference: false, canRequestRelease: false, canApproveRelease: true,
      releaseRequestedBy: 'finance-maker', releaseNote: 'Provider has no payment for this intent' };
    component.beginCaseAction(pending, 'approve-release');
    component.submitCaseAction();
    const request = http.expectOne(`${base}/cases/case-a/release-approval`);
    expect(request.request.body).toEqual({});
    request.flush({ code: 'SEPARATION_OF_DUTIES' }, { status: 403, statusText: 'Forbidden' });
    expect(component.caseError()).toContain('different authorized employee');
  });

  it('lets the application team add a note but offers it no decision', () => {
    initialize();
    const supportView: FinanceCase = { ...stuckCase, canVerifyReference: false, canRequestRelease: false, canAddNote: true };
    component.beginCaseAction(supportView, 'verify');
    expect(component.activeCase()).toBeNull();
    component.beginCaseAction(supportView, 'external-confirmation');
    expect(component.activeCase()).toBeNull();
    component.beginCaseAction(supportView, 'note');
    component.caseNote = 'Stripe timed out twice; see provider evidence for request IDs';
    component.submitCaseAction();
    const request = http.expectOne(`${base}/cases/case-a/notes`);
    expect(request.request.body).toEqual({ note: 'Stripe timed out twice; see provider evidence for request IDs' });
    request.flush({ ...stuckCase });
    refreshAfterCaseAction();
    expect(component.caseMessage()).toContain('Note added');
  });

  it('records a Stripe confirmation with every field and keeps card numbers out', () => {
    initialize();
    const eligible: FinanceCase = { ...stuckCase, canRecordExternalConfirmation: true };
    component.beginCaseAction(eligible, 'external-confirmation');
    component.confirmation = { source: 'STRIPE_DASHBOARD', account: 'acct_1ExampleOriginal',
      searched: 'Card 4242 4242 4242 4242 searched in the Dashboard', result: 'No matching Checkout Session or payment',
      ticket: '', checkedAt: '2026-10-24T10:00', evidenceSha256: 'ab'.repeat(32) };
    component.caseNote = 'Carrier finance checked the original account in the Dashboard';
    component.submitCaseAction();
    http.expectNone(`${base}/cases/case-a/external-confirmation`);
    expect(component.caseError()).toContain('card or bank account numbers');
    component.confirmation.searched = 'Searched sessions and payments by payment ID and date';
    component.confirmation.source = 'STRIPE_SUPPORT';
    component.submitCaseAction();
    http.expectNone(`${base}/cases/case-a/external-confirmation`);
    expect(component.caseError()).toContain('support ticket');
    component.confirmation.ticket = 'SUP-12345';
    component.submitCaseAction();
    const request = http.expectOne(`${base}/cases/case-a/external-confirmation`);
    expect(request.request.body).toEqual({ source: 'STRIPE_SUPPORT', account: 'acct_1ExampleOriginal',
      searched: 'Searched sessions and payments by payment ID and date', result: 'No matching Checkout Session or payment',
      ticket: 'SUP-12345', checkedAt: new Date('2026-10-24T10:00').toISOString(), evidenceSha256: 'ab'.repeat(32),
      note: 'Carrier finance checked the original account in the Dashboard' });
    request.flush({ ...stuckCase, releaseRequestedBy: 'finance-user' });
    refreshAfterCaseAction();
    expect(component.caseMessage()).toContain('different authorized approver');
  });

  it('shows the approver what Stripe confirmed and explains an expired confirmation', () => {
    initialize();
    const pending: FinanceCase = { ...stuckCase, canApproveRelease: true, releaseRequestedBy: 'finance-maker',
      externalConfirmation: { source: 'STRIPE_DASHBOARD', account: 'acct_1ExampleOriginal', searched: 'Sessions by payment ID',
        result: 'No matching session', ticket: null, checkedAt: '2026-10-24T10:00:00Z', evidenceSha256: 'ab'.repeat(32) } };
    expect(component.confirmationSummary(pending)).toContain('acct_1ExampleOriginal');
    expect(component.confirmationSummary(pending)).toContain('Stripe Dashboard');
    component.beginCaseAction(pending, 'approve-release');
    component.submitCaseAction();
    http.expectOne(`${base}/cases/case-a/release-approval`).flush({ code: 'STRIPE_CONFIRMATION_EXPIRED' }, { status: 409, statusText: 'Conflict' });
    expect(component.caseError()).toContain('more than 7 days old');
  });

  // Disputes are answered in the Stripe Dashboard. The list shows the deadline; there is no case history to open.
  it('shows when a dispute must be answered in Stripe, and opens no case history for it', () => {
    initialize();
    const dispute: FinanceCase = { id: 'dispute:STRIPE:dp_example', intentId: '', reason: 'DISPUTE', status: 'OPEN',
      createdAt: '2026-10-25T00:00:00Z', disputeStatus: 'needs_response', evidenceDueBy: '2026-10-30T23:59:59Z',
      paymentReference: 'pi_example', amountMinor: 12345 };
    expect(component.disputeDeadline(dispute)).toContain('Dispute: submit evidence in the Stripe Dashboard by Oct 30, 2026');
    expect(component.disputeDeadline(dispute)).toContain('UTC; with no response it is lost');
    // An inquiry takes no money yet, but can become a chargeback if it is not answered.
    expect(component.disputeDeadline({ ...dispute, disputeStatus: 'warning_needs_response' }))
      .toContain('Inquiry: answer in the Stripe Dashboard by Oct 30, 2026');
    expect(component.disputeDeadline({ ...dispute, disputeStatus: 'warning_needs_response' })).toContain('so it does not become a chargeback');
    expect(component.disputeDeadline({ ...dispute, evidenceDueBy: null })).toBe('Dispute: no deadline was given; check it in the Stripe Dashboard');
    expect(component.disputeDeadline({ ...dispute, statusUnclear: true }))
      .toBe('Stripe sent conflicting updates; check this dispute in the Stripe Dashboard');
    expect(component.disputeDeadline({ ...dispute, disputeStatus: 'under_review' })).toBe('Evidence sent; waiting for the bank');
    expect(component.disputeDeadline({ ...dispute, reason: 'PAYMENT_UNRESOLVED' })).toBe('');
    component.showHistory(dispute);
    http.expectNone(request => request.url.includes('/history'));
  });

  it('fails closed on a corrupt stored refund record', () => {
    sessionStorage.setItem('invoice-payments.billing.refund.v1:finance-user', '{invalid');
    initialize(); draft(); component.sendRefundRequest();
    expect(component.storageError()).toContain('could not be recovered');
    http.expectNone(`${base}/intents/intent-a/refunds`);
  });
});

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

  it('fails closed on a corrupt stored refund record', () => {
    sessionStorage.setItem('invoice-payments.billing.refund.v1:finance-user', '{invalid');
    initialize(); draft(); component.sendRefundRequest();
    expect(component.storageError()).toContain('could not be recovered');
    http.expectNone(`${base}/intents/intent-a/refunds`);
  });
});

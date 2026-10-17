import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService } from '../shared/auth.service';
import { InvoicesComponent } from './invoices';
import { BillingAccount, BillingIntent, Invoice } from './billing-api';

describe('Invoice payment recovery', () => {
  const base = `${environment.apiUrl}/billing`;
  const account: BillingAccount = { id: 'account-a', name: 'Example Mechanical', merchantId: 'merchant-a', role: 'PAYER' };
  const invoice: Invoice = { id: 'invoice-a', number: 'INV-001', currency: 'USD', totalMinor: 12525, paidMinor: 2500, reservedMinor: 0, status: 'OPEN', dueDate: '2026-10-31' };
  const created: BillingIntent = { id: 'intent-a', accountId: account.id, currency: 'USD', amountMinor: 10025, status: 'CREATED', provider: 'SIMULATOR', simulatorEnabled: true };
  let component: InvoicesComponent;
  let http: HttpTestingController;
  let currentUser: ReturnType<typeof signal<any>>;
  let route: { snapshot: { queryParamMap: ReturnType<typeof convertToParamMap> } };

  beforeEach(() => {
    sessionStorage.clear();
    currentUser = signal({ userId: 'user-a' });
    route = { snapshot: { queryParamMap: convertToParamMap({}) } };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { user: currentUser } }, { provide: ActivatedRoute, useValue: route }] });
    component = TestBed.runInInjectionContext(() => new InvoicesComponent());
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { component.ngOnDestroy(); http.verify(); sessionStorage.clear(); });

  function flushBalances(invoices = [invoice], history: BillingIntent[] = []) {
    http.expectOne(`${base}/accounts/${account.id}/invoices`).flush(invoices);
    http.expectOne({ method: 'GET', url: `${base}/accounts/${account.id}/intents` }).flush(history);
  }

  function initialize(accounts = [account]) {
    component.ngOnInit();
    http.expectOne(`${base}/accounts`).flush(accounts);
    flushBalances();
  }
  function prepare(status = 'CREATED') {
    component.toggleInvoice(invoice, true);
    component.createIntent();
    http.expectOne(`${base}/accounts/${account.id}/intents`).flush({ ...created, status });
    flushBalances();
  }

  it('reuses the same key and original invoice payload after a lost create response', () => {
    initialize();
    component.toggleInvoice(invoice, true);
    component.createIntent();
    const first = http.expectOne(`${base}/accounts/${account.id}/intents`);
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    expect(first.request.body).toEqual({ invoiceIds: ['invoice-a'], method: 'CARD' });
    first.error(new ProgressEvent('error'));
    expect(component.intent()).toBeNull();
    expect(component.selectionLocked()).toBe(true);
    component.toggleInvoice(invoice, false);
    component.method.set('ACH'); // Frozen saved payload, even if UI state is changed.
    component.createIntent();
    const retry = http.expectOne(`${base}/accounts/${account.id}/intents`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual({ invoiceIds: ['invoice-a'], method: 'CARD' });
    retry.flush(created);
    flushBalances();
    expect(component.intent()?.amountMinor).toBe(10025);
  });

  it('recovers the same request key after the component is destroyed and recreated', () => {
    initialize();
    component.toggleInvoice(invoice, true);
    component.createIntent();
    const first = http.expectOne(`${base}/accounts/${account.id}/intents`);
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 503, statusText: 'Unavailable' });
    component.ngOnDestroy();
    component = TestBed.runInInjectionContext(() => new InvoicesComponent());
    initialize();
    expect(component.selected()).toEqual(['invoice-a']);
    component.createIntent();
    const retry = http.expectOne(`${base}/accounts/${account.id}/intents`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(created);
    flushBalances();
  });

  it('keeps a lost submit response uncertain and permits refresh without a new payment', () => {
    initialize();
    prepare();
    component.submitIntent();
    http.expectOne(`${base}/intents/intent-a/submit`).flush({}, { status: 504, statusText: 'Timeout' });
    expect(component.canSubmit()).toBe(false);
    expect(component.submissionUncertain()).toBe(true);
    expect(component.terminal()).toBe(false);
    component.submitIntent();
    component.startAnother();
    http.expectNone(`${base}/intents/intent-a/submit`);
    expect(component.request()?.intentId).toBe('intent-a');
    component.refreshIntent();
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'UNKNOWN' });
    flushBalances();
    expect(component.canSubmit()).toBe(false);
    expect(component.statusExplanation()).toContain('do not create another payment');
  });

  it('uses a known intent lookup on reload rather than recreating or resubmitting it', () => {
    initialize();
    prepare('PROCESSING');
    component.ngOnDestroy();
    component = TestBed.runInInjectionContext(() => new InvoicesComponent());
    initialize();
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'PROCESSING' });
    flushBalances();
    http.expectNone(`${base}/accounts/${account.id}/intents`);
    http.expectNone(`${base}/intents/intent-a/submit`);
    expect(component.terminal()).toBe(false);
    expect(component.canSubmit()).toBe(false);
  });

  it('displays server outcomes without calling processing or unknown successful', () => {
    initialize();
    prepare('PROCESSING');
    expect(component.statusExplanation()).toContain('processing');
    expect(component.terminal()).toBe(false);
    component.refreshIntent();
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'CAPTURED' });
    flushBalances([{ ...invoice, paidMinor: 12525 }]);
    expect(component.statusExplanation()).toContain('separate from bank payout settlement');
    expect(component.terminal()).toBe(true);
    expect(component.canSubmit()).toBe(false);
    component.startAnother();
    flushBalances([{ ...invoice, paidMinor: 12525 }]);
    expect(component.request()).toBeNull();
    expect(component.payable({ ...invoice, paidMinor: 12525 })).toBe(false);
  });

  it('does not turn a rejected or unavailable create request into a fresh request', () => {
    initialize();
    component.toggleInvoice(invoice, true);
    component.createIntent();
    const first = http.expectOne(`${base}/accounts/${account.id}/intents`);
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({ message: 'Conflict' }, { status: 409, statusText: 'Conflict' });
    component.startAnother();
    component.createIntent();
    const retry = http.expectOne(`${base}/accounts/${account.id}/intents`);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({}, { status: 503, statusText: 'Unavailable' });
  });

  it('does not expose simulation actions for a real provider or a disabled simulator', () => {
    initialize();
    prepare('PROCESSING');
    component.intent.set({ ...created, status: 'PROCESSING', provider: 'STRIPE', simulatorEnabled: true });
    component.simulate();
    expect(component.simulator()).toBe(false);
    component.intent.set({ ...created, status: 'PROCESSING', simulatorEnabled: false });
    component.simulate();
    http.expectNone(`${base}/intents/intent-a/simulate`);
  });

  it('sends explicit simulator outcomes and keeps a lost-response scenario unknown', () => {
    initialize();
    prepare('PROCESSING');
    component.simulationOutcome = 'TIMEOUT_AFTER_ACCEPT';
    component.simulate();
    const request = http.expectOne(`${base}/intents/intent-a/simulate`);
    expect(request.request.body).toEqual({ outcome: 'TIMEOUT_AFTER_ACCEPT' });
    request.flush({ ...created, status: 'UNKNOWN' });
    flushBalances();
    expect(component.terminal()).toBe(false);
    expect(component.canSubmit()).toBe(false);
  });

  it('only offers an HTTPS checkout link on the trusted provider host', () => {
    component.intent.set({ ...created, status: 'REQUIRES_ACTION', checkoutUrl: 'https://checkout.stripe.com/c/pay/example' });
    expect(component.checkoutUrl()).toContain('https://checkout.stripe.com/');
    for (const checkoutUrl of ['javascript:alert(1)', 'https://checkout.stripe.com.evil.example/pay', 'http://checkout.stripe.com/pay', 'https://user@checkout.stripe.com/pay']) {
      component.intent.set({ ...created, status: 'REQUIRES_ACTION', checkoutUrl });
      expect(component.checkoutUrl()).toBeNull();
    }
  });

  it('does not recover one signed-in user request under another identity', () => {
    sessionStorage.setItem('invoice-payments.billing.v1:user-other:account-a', JSON.stringify({ accountId: account.id, invoiceIds: [invoice.id], method: 'CARD', key: 'other-key' }));
    initialize();
    expect(component.request()).toBeNull();
    expect(component.selected()).toEqual([]);
  });

  it('blocks a fresh payment when a stored recovery record is corrupt', () => {
    sessionStorage.setItem('invoice-payments.billing.v1:user-a:account-a', '{broken');
    initialize();
    component.toggleInvoice(invoice, true);
    component.createIntent();
    expect(component.storageError()).toContain('could not be recovered');
    http.expectNone(`${base}/accounts/${account.id}/intents`);
  });

  it('keeps finance-only account access read-only and never trusts a client amount', () => {
    initialize([{ ...account, role: 'FINANCE' }]);
    component.toggleInvoice(invoice, true);
    component.createIntent();
    expect(component.selected()).toEqual([]);
    expect(component.canPay()).toBe(false);
    http.expectNone(`${base}/accounts/${account.id}/intents`);
  });

  it('recovers a provider-return reference without creating or submitting a payment', () => {
    route.snapshot.queryParamMap = convertToParamMap({ intentId: 'intent-a' });
    component.ngOnInit();
    http.expectOne(`${base}/accounts`).flush([account]);
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'PROCESSING', method: 'ACH', allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'RESERVED' }] });
    flushBalances();
    expect(component.request()?.intentId).toBe('intent-a');
    expect(component.method()).toBe('ACH');
    expect(component.canSubmit()).toBe(false);
    http.expectNone(`${base}/accounts/${account.id}/intents`);
    http.expectNone(`${base}/intents/intent-a/submit`);
  });

  it('never overwrites a different uncertain request when opening a provider return', () => {
    const saved = { accountId: account.id, invoiceIds: ['other-invoice'], method: 'CARD', key: 'original-key' };
    sessionStorage.setItem('invoice-payments.billing.v1:user-a:account-a', JSON.stringify(saved));
    route.snapshot.queryParamMap = convertToParamMap({ intentId: 'intent-a' });
    component.ngOnInit();
    http.expectOne(`${base}/accounts`).flush([account]);
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'PROCESSING', method: 'CARD', allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'RESERVED' }] });
    flushBalances();
    expect(component.request()?.key).toBe('original-key');
    expect(component.intent()).toBeNull();
    expect(component.error()).toContain('Recover it first');
    expect(JSON.parse(sessionStorage.getItem('invoice-payments.billing.v1:user-a:account-a')!).key).toBe('original-key');
  });

  it('only permits selection reset on an explicit no-operation rejection code', () => {
    initialize();
    component.toggleInvoice(invoice, true);
    component.createIntent();
    http.expectOne(`${base}/accounts/${account.id}/intents`).flush({ code: 'INVOICE_UNAVAILABLE' }, { status: 409, statusText: 'Conflict' });
    expect(component.request()?.definitiveRejection).toBe(true);
    component.startAnother();
    flushBalances();
    expect(component.request()).toBeNull();
    expect(component.intent()).toBeNull();
  });

  it('resumes an abandoned unsubmitted request from server history without a new operation', () => {
    component.ngOnInit();
    http.expectOne(`${base}/accounts`).flush([account]);
    flushBalances([invoice], [created]);
    component.resumeIntent(created.id);
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, method: 'CARD', allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'RESERVED' }] });
    flushBalances([invoice], [created]);
    expect(component.request()?.intentId).toBe(created.id);
    expect(component.canSubmit()).toBe(true);
    http.expectNone({ method: 'POST', url: `${base}/accounts/${account.id}/intents` });
  });

  it('cancels only an unsubmitted request and obtains released balances from the server', () => {
    initialize(); prepare();
    component.cancelIntent();
    http.expectOne(`${base}/intents/intent-a/cancel`).flush({ ...created, status: 'CANCELLED' });
    flushBalances([invoice], [{ ...created, status: 'CANCELLED' }]);
    expect(component.terminal()).toBe(true);
    expect(component.canSubmit()).toBe(false);
    expect(component.statusExplanation()).toContain('No payment was submitted');
    expect(component.receiptUrl()).toBeNull();
    component.cancelIntent();
    component.submitIntent();
    http.expectNone(`${base}/intents/intent-a/cancel`);
    http.expectNone(`${base}/intents/intent-a/submit`);
  });

  it('keeps a lost cancellation uncertain until a fresh status confirms the outcome', () => {
    initialize(); prepare();
    component.cancelIntent();
    http.expectOne(`${base}/intents/intent-a/cancel`).flush({}, { status: 504, statusText: 'Timeout' });
    expect(component.submissionUncertain()).toBe(true);
    expect(component.canSubmit()).toBe(false);
    component.cancelIntent(); component.submitIntent(); component.startAnother();
    http.expectNone(`${base}/intents/intent-a/cancel`);
    http.expectNone(`${base}/intents/intent-a/submit`);
    expect(component.request()?.intentId).toBe(created.id);
    component.refreshIntent();
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'CANCELLED' });
    flushBalances();
    expect(component.terminal()).toBe(true);
  });

  it('does not cancel submitted or uncertain payments and only links receipts after capture states', () => {
    initialize(); prepare('PROCESSING');
    for (const status of ['SUBMITTING', 'PROCESSING', 'UNKNOWN', 'REQUIRES_ACTION']) {
      component.intent.set({ ...created, status });
      component.cancelIntent();
      expect(component.receiptUrl()).toBeNull();
    }
    http.expectNone(`${base}/intents/intent-a/cancel`);
    for (const status of ['CAPTURED', 'RETURNED', 'REFUNDED']) {
      component.intent.set({ ...created, status });
      expect(component.receiptUrl()).toBe('/api/v1/billing/intents/intent-a/receipt');
    }
  });
});

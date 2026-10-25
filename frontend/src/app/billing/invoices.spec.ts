import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService } from '../shared/auth.service';
import { InvoicesComponent, PAYER_NOTICES, STATUS_CHECKS } from './invoices';
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

  // Payers are not technical: one plain sentence per status, and only "successful" when the payment is confirmed.
  it('explains every payment status in plain words', () => {
    const plain: Record<string, string> = {
      CREATED: 'Check the amount, then press Pay.',
      REQUIRES_ACTION: "Finish paying on the secure payment page. This page will update by itself when you're done.",
      SUBMITTING: "Processing your payment. Please don't close this page or pay again.",
      UNKNOWN: "Processing your payment. Please don't close this page or pay again.",
      PROCESSING: 'Your bank payment is on its way. This can take a few days.',
      CAPTURED: 'Payment successful. Thank you.',
      FAILED: "Your payment didn't go through and you weren't charged. You can try again.",
      CANCELLED: 'This payment was never started, so nothing was charged. You can start again.',
      RETURNED: 'Your bank sent this payment back. The invoice is unpaid again. Please contact your finance team.',
      REFUNDED: 'This payment was refunded.',
    };
    for (const [status, message] of Object.entries(plain)) {
      component.intent.set({ ...created, status } as BillingIntent);
      expect(component.statusExplanation(), status).toBe(message);
    }
    component.intent.set(null);
    expect(component.statusExplanation()).toBe("We're checking this payment. Please don't pay again yet.");
    const jargon = /provider|server|capture|settlement|reserv|submi|request|ERP|outcome|recorded|refresh/i;
    for (const status of [...Object.keys(plain), 'SOMETHING_NEW']) {
      component.intent.set({ ...created, status } as BillingIntent);
      expect(component.statusExplanation(), status).not.toMatch(jargon);
      if (status !== 'CAPTURED') expect(component.statusExplanation(), status).not.toContain('successful');
    }
  });

  // The payer never has to refresh: the page keeps checking while a payment is being confirmed.
  describe('while a payment is being confirmed', () => {
    const processing = "Processing your payment. Please don't close this page or pay again.";
    const checkPath = `${base}/intents/intent-a`;
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());
    function pay(answer: Partial<BillingIntent>) {
      initialize(); prepare();
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-a/submit`).flush({ ...created, ...answer });
      flushBalances();
    }

    it('updates by itself, without reloading anything until the status changes, and stops at the result', () => {
      pay({ status: 'UNKNOWN' });
      expect(component.statusExplanation()).toBe(processing);
      vi.advanceTimersByTime(STATUS_CHECKS.fastEveryMs);
      http.expectOne(checkPath).flush({ ...created, status: 'UNKNOWN' });
      http.expectNone(`${base}/accounts/${account.id}/invoices`);
      vi.advanceTimersByTime(STATUS_CHECKS.fastEveryMs);
      http.expectOne(checkPath).flush({ ...created, status: 'CAPTURED' });
      flushBalances([{ ...invoice, paidMinor: 12525 }]);
      expect(component.statusExplanation()).toBe('Payment successful. Thank you.');
      vi.advanceTimersByTime(STATUS_CHECKS.stopAfterMs);
      http.expectNone(checkPath);
    });

    it('checks every 3 seconds for a minute, then says it is taking longer and stops after ten minutes', () => {
      pay({ status: 'SUBMITTING' });
      let checks = 0;
      const answer = () => { for (const check of http.match(checkPath)) { check.flush({ ...created, status: 'SUBMITTING' }); checks++; } };
      const longer = "This is taking longer than usual. You can close this page safely; the result will appear in your recent payments. Please don't pay again.";
      for (let second = 1; second <= 30; second++) { vi.advanceTimersByTime(1000); answer(); }
      expect(component.statusExplanation()).toBe(processing);
      for (let second = 31; second <= 60; second++) { vi.advanceTimersByTime(1000); answer(); }
      expect(checks).toBe(20);
      expect(component.statusExplanation()).toBe(longer);
      for (let second = 61; second <= 660; second++) { vi.advanceTimersByTime(1000); answer(); }
      expect(checks).toBe(20 + 36);
      expect(component.statusExplanation()).toBe(longer);
      vi.advanceTimersByTime(STATUS_CHECKS.slowEveryMs * 4);
      http.expectNone(checkPath);
    });

    it('keeps checking after a lost reply instead of asking the payer to refresh', () => {
      initialize(); prepare();
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-a/submit`).flush({}, { status: 504, statusText: 'Timeout' });
      expect(component.error()).toBe(PAYER_NOTICES.payNotConfirmed);
      expect(component.canSubmit()).toBe(false);
      vi.advanceTimersByTime(STATUS_CHECKS.fastEveryMs);
      http.expectOne(checkPath).flush({ ...created, status: 'UNKNOWN' });
      flushBalances();
      expect(component.statusExplanation()).toBe(processing);
    });

    it('notices a payment finished on the secure payment page without a refresh', () => {
      pay({ status: 'REQUIRES_ACTION', checkoutUrl: 'https://checkout.stripe.com/c/pay/cs_test_a1' });
      expect(component.statusExplanation()).toContain('This page will update by itself');
      vi.advanceTimersByTime(STATUS_CHECKS.fastEveryMs);
      http.expectOne(checkPath).flush({ ...created, status: 'CAPTURED' });
      flushBalances();
      expect(component.statusExplanation()).toBe('Payment successful. Thank you.');
    });

    it('says nothing was charged when payments are busy, and lets the payer try again without checking', () => {
      initialize(); prepare();
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-a/submit`)
        .flush({ code: 'PROVIDER_BUSY', message: 'Nothing was charged' }, { status: 503, statusText: 'Service Unavailable' });
      expect(component.error()).toBe(PAYER_NOTICES.busyTryAgain);
      expect(component.submissionUncertain()).toBe(false);
      expect(component.canSubmit()).toBe(true);
      expect(JSON.parse(sessionStorage.getItem('invoice-payments.billing.v1:user-a:account-a')!).uncertainSubmit).toBe(false);
      vi.advanceTimersByTime(STATUS_CHECKS.stopAfterMs);
      http.expectNone(checkPath);
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-a/submit`).flush({ ...created, status: 'UNKNOWN' });
      flushBalances();
      expect(component.error()).toBe('');
    });

    it('stops checking when the payer leaves the page', () => {
      pay({ status: 'UNKNOWN' });
      component.ngOnDestroy();
      vi.advanceTimersByTime(STATUS_CHECKS.stopAfterMs);
      http.expectNone(checkPath);
    });
  });

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
    expect(component.statusExplanation()).toContain('pay again');
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
    expect(component.statusExplanation()).toContain('on its way');
    expect(component.terminal()).toBe(false);
    component.refreshIntent();
    http.expectOne(`${base}/intents/intent-a`).flush({ ...created, status: 'CAPTURED' });
    flushBalances([{ ...invoice, paidMinor: 12525 }]);
    expect(component.statusExplanation()).toBe('Payment successful. Thank you.');
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
    expect(component.storageError()).toContain("couldn't reopen your earlier payment");
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
    expect(component.error()).toContain('Please finish that one first');
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
    expect(component.statusExplanation()).toContain('nothing was charged');
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

  // Trying again after a failure: a new attempt under the same payment reference. The payer still presses Pay.
  describe('retrying a failed payment', () => {
    const storageKey = 'invoice-payments.billing.v1:user-a:account-a';
    const retryPath = `${base}/intents/intent-a/retry`;
    const retried: BillingIntent = { ...created, id: 'intent-b', method: 'ACH', paymentReference: 'intent-a', retryOf: 'intent-a',
      allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'RESERVED' }] };
    const failedFirst: BillingIntent = { ...created, status: 'FAILED', paymentReference: 'intent-a', retryOf: null };

    function retryWith(method: 'CARD' | 'ACH' = 'ACH') {
      initialize(); prepare('FAILED');
      component.retryPayment(method);
      return http.expectOne(retryPath);
    }

    it('keeps a retry identity conflict pending rather than replacing the request', () => {
      const first = retryWith('ACH');
      const key = first.request.headers.get('Idempotency-Key');
      first.flush({ code: 'IDEMPOTENCY_CONFLICT' }, { status: 409, statusText: 'Conflict' });
      expect(component.pendingRetry()).not.toBeNull();
      expect(JSON.parse(sessionStorage.getItem(storageKey)!).definitiveRejection).not.toBe(true);
      component.startAnother();
      component.method.set('CARD');
      component.createIntent();
      const again = http.expectOne(retryPath);
      expect(again.request.headers.get('Idempotency-Key')).toBe(key);
      expect(again.request.body).toEqual({ method: 'ACH' });
      again.flush(retried);
      flushBalances();
    });

    it('shows the payment reference, and the payment id for answers that have none', () => {
      expect(component.paymentReference({ ...created, paymentReference: 'intent-first' })).toBe('intent-first');
      expect(component.paymentReference(created)).toBe('intent-a');
      expect(component.paymentReference(null)).toBe('');
    });

    it('creates one retry with a fresh key and the method the payer chose, saved before it is sent', () => {
      initialize(); prepare('FAILED');
      const originalKey = JSON.parse(sessionStorage.getItem(storageKey)!).key;
      expect(component.canRetry()).toBe(true);
      component.retryPayment('ACH');
      const sent = http.expectOne(retryPath);
      expect(sent.request.method).toBe('POST');
      expect(sent.request.body).toEqual({ method: 'ACH' });
      const key = sent.request.headers.get('Idempotency-Key');
      expect(key).toBeTruthy();
      expect(key).not.toBe(originalKey);
      // Saved before any answer: a lost reply or a reload sends exactly this again.
      const saved = JSON.parse(sessionStorage.getItem(storageKey)!);
      expect(saved).toMatchObject({ retryOf: 'intent-a', key, method: 'ACH', invoiceIds: [invoice.id] });
      expect(saved.intentId).toBeUndefined();
      component.retryPayment('ACH');
      http.expectNone(retryPath);
      sent.flush(retried);
      flushBalances();
      expect(component.intent()?.id).toBe('intent-b');
      expect(component.intent()?.status).toBe('CREATED');
      http.expectNone(`${base}/intents/intent-b/submit`);
    });

    it('sends the same retry again after a lost reply, even if the payer changed the method', () => {
      const first = retryWith('ACH');
      const key = first.request.headers.get('Idempotency-Key');
      first.error(new ProgressEvent('error'));
      component.method.set('CARD');
      component.retryPayment('CARD');
      const again = http.expectOne(retryPath);
      expect(again.request.headers.get('Idempotency-Key')).toBe(key);
      expect(again.request.body).toEqual({ method: 'ACH' });
      again.flush(retried);
      flushBalances();
      expect(component.intent()?.id).toBe('intent-b');
    });

    it('sends the same retry again after the page is reloaded', () => {
      const first = retryWith('ACH');
      const key = first.request.headers.get('Idempotency-Key');
      first.flush({}, { status: 503, statusText: 'Unavailable' });
      component.ngOnDestroy();
      component = TestBed.runInInjectionContext(() => new InvoicesComponent());
      initialize();
      component.createIntent();
      http.expectNone(`${base}/accounts/${account.id}/intents`);
      const again = http.expectOne(retryPath);
      expect(again.request.headers.get('Idempotency-Key')).toBe(key);
      expect(again.request.body).toEqual({ method: 'ACH' });
      again.flush(retried);
      flushBalances();
      expect(component.intent()?.id).toBe('intent-b');
    });

    it('keeps the reference but pays, checks and links the receipt with the new attempt', () => {
      retryWith('ACH').flush(retried);
      flushBalances();
      expect(component.paymentReference(component.intent())).toBe('intent-a');
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-b/submit`).flush({ ...retried, status: 'CAPTURED' });
      flushBalances();
      http.expectNone(`${base}/intents/intent-a/submit`);
      expect(component.receiptUrl()).toBe('/api/v1/billing/intents/intent-b/receipt');
      expect(component.paymentReference(component.intent())).toBe('intent-a');
    });

    it('offers no retry while a payment is not certainly failed', () => {
      for (const status of ['CREATED', 'SUBMITTING', 'REQUIRES_ACTION', 'PROCESSING', 'UNKNOWN', 'CAPTURED', 'RETURNED', 'REFUNDED', 'CANCELLED']) {
        component.ngOnDestroy(); sessionStorage.clear();
        component = TestBed.runInInjectionContext(() => new InvoicesComponent());
        initialize(); prepare(status);
        expect(component.canRetry(), status).toBe(false);
        component.retryPayment('CARD');
        http.expectNone(retryPath);
      }
    });

    // Pay was refused because an earlier try was paid. This try was not sent, but the payer must not be told
    // "nothing was charged": the earlier one was. They can see it in recent payments and cancel this one.
    it('explains when Pay was refused because an earlier try was paid, and still lets the payer cancel', () => {
      retryWith('ACH').flush(retried);
      flushBalances();
      component.submitIntent();
      http.expectOne(`${base}/intents/intent-b/submit`)
        .flush({ code: 'ALREADY_PAID', message: 'An earlier attempt was paid' }, { status: 409, statusText: 'Conflict' });
      expect(component.error()).toBe(PAYER_NOTICES.earlierAttemptPaid);
      expect(PAYER_NOTICES.earlierAttemptPaid).not.toMatch(/nothing was charged|weren't charged/i);
      expect(component.submissionUncertain()).toBe(false);
      expect(component.canSubmit()).toBe(false);
      expect(component.canCancel()).toBe(true);
      expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toMatchObject({ uncertainSubmit: false, earlierAttemptPaid: true });
      component.cancelIntent();
      http.expectOne(`${base}/intents/intent-b/cancel`).flush({ ...retried, status: 'CANCELLED' });
      flushBalances();
      expect(component.intent()?.status).toBe('CANCELLED');
    });

    it('keeps every attempt in recent payments under the shared reference', () => {
      retryWith('ACH').flush(retried);
      flushBalances([invoice], [{ ...retried }, failedFirst]);
      expect(component.history().map(row => row.id)).toEqual(['intent-b', 'intent-a']);
      expect(component.history().map(row => component.paymentReference(row))).toEqual(['intent-a', 'intent-a']);
    });
  });
});

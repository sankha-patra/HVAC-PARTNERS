import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService } from '../shared/auth.service';
import { InvoicesComponent, PAYER_NOTICES } from './invoices';
import { BillingAccount, BillingIntent, Invoice } from './billing-api';

// Words a payer should never need to understand. Text the server sends (payment messages, the timeline)
// is shown as it comes and is not part of this check.
const JARGON = /provider|server|unsubmitted|allocation|reserv|capture|settlement|asynchronous|JSON|membership|request|recover|simulator/i;
const ACRONYMS = /\bACH\b|\bERP\b/;

describe('Payer screen wording', () => {
  const base = `${environment.apiUrl}/billing`;
  const account: BillingAccount = { id: 'account-a', name: 'Example Mechanical', merchantId: 'merchant-a', role: 'PAYER' };
  const invoice: Invoice = { id: 'invoice-a', number: 'INV-001', currency: 'USD', totalMinor: 12525, paidMinor: 2500, reservedMinor: 0, status: 'OPEN', dueDate: '2026-10-31' };
  const created: BillingIntent = { id: 'intent-a', accountId: account.id, currency: 'USD', amountMinor: 10025, status: 'CREATED', provider: 'STRIPE', method: 'CARD' };
  let fixture: ComponentFixture<InvoicesComponent>;
  let component: InvoicesComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    // jsdom has no ResizeObserver; Material form fields only need it to exist.
    (globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { user: signal({ userId: 'user-a' }) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } }] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(InvoicesComponent);
    component = fixture.componentInstance;
  });
  afterEach(() => { http.verify(); sessionStorage.clear(); });

  function open(accounts = [account], history: BillingIntent[] = []) {
    fixture.detectChanges();
    http.expectOne(`${base}/accounts`).flush(accounts);
    if (accounts.length) {
      http.expectOne(`${base}/accounts/${account.id}/invoices`).flush([invoice]);
      http.expectOne({ method: 'GET', url: `${base}/accounts/${account.id}/intents` }).flush(history);
    }
    fixture.detectChanges();
  }
  function show(intent: Partial<BillingIntent>) {
    component.intent.set({ ...created, ...intent });
    fixture.detectChanges();
  }
  function text() { return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ').trim(); }
  function labels() {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button, a')].map(e => e.textContent!.replace(/\s+/g, ' ').trim());
  }
  function expectPlain() {
    expect(text()).not.toMatch(JARGON);
    expect(text()).not.toMatch(ACRONYMS);
  }

  it('shows the invoice list in plain words', () => {
    open();
    expect(text()).toContain('Pay your invoices');
    expect(text()).toContain('Choose your business, pick the invoices to pay, then press Review payment.');
    for (const heading of ['Paid', 'In progress', 'Left to pay', 'Total about', 'Your recent payments', 'No payments yet for this account.'])
      expect(text()).toContain(heading);
    expect(labels()).toContain('Review payment');
    expectPlain();
    component.method.set('ACH');
    fixture.detectChanges();
    expect(text()).toContain('Bank payments can take a few days.');
    expectPlain();
  });

  it('explains missing access plainly', () => {
    open([]);
    expect(text()).toContain('No business account yet');
    expectPlain();
  });

  it('explains view-only access plainly', () => {
    open([{ ...account, role: 'FINANCE' }]);
    expect(text()).toContain("You can see these invoices but can't pay them.");
    expectPlain();
  });

  it('names a new payment and its buttons plainly', () => {
    open();
    show({ status: 'CREATED' });
    expect(text()).toContain('Your payment');
    expect(text()).toContain('Ready to pay');
    expect(text()).toContain('Paying by: Card');
    expect(labels()).toEqual(expect.arrayContaining(['Pay', 'Cancel', 'Refresh payment']));
    expectPlain();
    show({ status: 'CREATED', provider: 'SIMULATOR', simulatorEnabled: true });
    expect(labels()).toContain('Pay (test)');
    expect(text()).toContain('Test mode');
    expectPlain();
  });

  it('sends the payer to the secure payment page, or says plainly that it is missing', () => {
    open();
    show({ status: 'REQUIRES_ACTION', checkoutUrl: 'https://checkout.stripe.com/c/pay/cs_test_a1' });
    expect(text()).toContain('Waiting for you');
    expect(labels()).toContain('Go to secure payment page');
    expectPlain();
    show({ status: 'REQUIRES_ACTION', checkoutUrl: undefined });
    expect(text()).toContain("The secure payment page isn't available right now.");
    expectPlain();
  });

  it('warns against paying twice when the last step is unconfirmed', () => {
    open();
    show({ status: 'CREATED' });
    component.submissionUncertain.set(true);
    fixture.detectChanges();
    expect(text()).toContain("We couldn't confirm your last step yet. We're checking; please don't pay again.");
    expect(text()).not.toMatch(/press refresh/i);
    expectPlain();
  });

  it('shows confirmed and in-progress payments plainly', () => {
    open();
    show({ status: 'PROCESSING', method: 'ACH', allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'RESERVED' }] });
    expect(text()).toContain('On its way');
    expect(text()).toContain('Paying by: US bank account');
    expect(text()).toContain('INV-001');
    expect(text()).toContain('held for this payment');
    expectPlain();
    show({ status: 'CAPTURED', allocations: [{ invoiceId: invoice.id, amountMinor: 10025, state: 'POSTED' }] });
    expect(text()).toContain('Payment successful. Thank you.');
    expect(labels()).toEqual(expect.arrayContaining(['Download receipt', 'Back to invoices']));
    expectPlain();
  });

  it('shows test controls without technical words', () => {
    open();
    show({ status: 'PROCESSING', provider: 'SIMULATOR', simulatorEnabled: true });
    expect(text()).toContain('Test scenarios');
    expect(labels()).toContain('Run test');
    expectPlain();
  });

  it('explains a payment that could not start, or one already in progress', () => {
    open();
    component.request.set({ accountId: account.id, key: 'key-a', invoiceIds: [invoice.id], method: 'CARD', definitiveRejection: true });
    fixture.detectChanges();
    expect(text()).toContain('This payment could not be started');
    expect(text()).toContain('Nothing was charged.');
    expect(labels()).toContain('Back to invoices');
    expectPlain();
    component.request.set({ accountId: account.id, key: 'key-a', invoiceIds: [invoice.id], method: 'CARD', intentId: 'intent-a' });
    fixture.detectChanges();
    expect(text()).toContain('You have a payment in progress');
    expect(labels()).toContain('Continue this payment');
    expectPlain();
    component.request.set({ accountId: account.id, key: 'key-a', invoiceIds: [invoice.id], method: 'CARD' });
    fixture.detectChanges();
    expect(labels()).toContain('Continue this payment');
    expectPlain();
  });

  it('shows a returned payment reference with a plain button', () => {
    open();
    component.returnedIntentId.set('intent-z');
    fixture.detectChanges();
    expect(text()).toContain('Payment reference: intent-z');
    expect(labels()).toContain('Check payment status');
    expectPlain();
  });

  it('lists recent payments with plain statuses', () => {
    open([account], [{ ...created, status: 'UNKNOWN' }]);
    expect(text()).toContain('Confirming');
    expect(labels()).toContain('Open');
    expectPlain();
  });

  // Every notice set by the page, including those only shown after a failure.
  it('words every notice for a payer', () => {
    const notices = Object.entries(PAYER_NOTICES)
      .map(([name, notice]) => [name, typeof notice === 'function' ? notice('intent-z') : notice] as const);
    expect(notices.length).toBe(19);
    for (const [name, notice] of notices) {
      expect(notice, name).not.toMatch(JARGON);
      expect(notice, name).not.toMatch(ACRONYMS);
      expect(notice, name).not.toContain('successful');
    }
    expect(PAYER_NOTICES.otherPaymentInProgress('intent-z')).toContain('intent-z');
  });
});

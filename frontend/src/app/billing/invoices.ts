import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import { AuthService } from '../shared/auth.service';
import { BillingAccount, BillingApi, BillingIntent, IntentRequest, Invoice, PaymentMethod, SimulationOutcome } from './billing-api';

interface SavedRequest extends IntentRequest {
  accountId: string;
  key: string;
  intentId?: string;
  uncertainSubmit?: boolean;
  definitiveRejection?: boolean;
}

/**
 * Every notice the payer can see on this page, in one place so the wording can be checked. Payers are not
 * technical: say what happened and what to do next, and never invite a second payment while one may go through.
 */
export const PAYER_NOTICES = {
  accountsUnavailable: "We couldn't load your business accounts. Refresh the page to try again.",
  paymentNotInYourAccounts: "We couldn't find this payment in your business accounts. Please contact support and give them the payment reference.",
  otherPaymentInProgress: (reference: string) => `You already have a payment in progress for this account. Please finish that one first. Payment reference: ${reference}.`,
  paymentLookupFailed: "We couldn't check this payment right now. Press Check payment status to try again. Please don't pay again yet.",
  invoicesUnavailable: "We couldn't load your invoices. Press Refresh to try again. You can pay once they load.",
  historyUnavailable: "We couldn't load your recent payments. Press Refresh before paying again, so you don't pay twice.",
  paymentNotStarted: 'This payment could not be started and nothing was charged. Go back to your invoices, check your choice and try again.',
  startNotConfirmed: "We couldn't confirm that your payment was started. Press Continue this payment below. Please don't start a new payment for these invoices.",
  statusUnavailable: "We couldn't check your payment just now. This doesn't mean it failed. We'll keep checking; please don't pay again.",
  payNotConfirmed: "We're checking whether your payment went through. Please don't close this page or pay again.",
  cancelNotConfirmed: "We couldn't confirm the cancellation. Press Refresh payment before doing anything else.",
  testResultNotConfirmed: "We couldn't confirm the test result. Press Refresh payment. No real money is used in test mode.",
  browserStorageBlockedReload: 'Your browser is blocking saved data for this site. Write down your payment reference, then reload the page.',
  answerDidNotMatch: "Something didn't match your business account. Please contact support and don't start a new payment.",
  signInAgain: 'Please sign in again before paying.',
  browserStorageBlocked: 'Your browser is blocking saved data for this site. Allow it, then try again. It lets you continue a payment if the page closes.',
  earlierPaymentUnreadable: "We couldn't reopen your earlier payment. Please contact support before paying again for this account.",
  payingBlocked: 'You can pay again once your earlier payment is sorted out.',
  busyTryAgain: "We're very busy right now. Nothing was charged. Please try again in a minute.",
};

/** How often the page checks a payment that is still being confirmed, so the payer never has to refresh. */
export const STATUS_CHECKS = { fastEveryMs: 3_000, fastForMs: 60_000, slowEveryMs: 15_000, stopAfterMs: 600_000 };

@Component({
  selector: 'app-invoices',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatCardModule, MatCheckboxModule,
    MatFormFieldModule, MatSelectModule, MatProgressBarModule],
  templateUrl: './invoices.html',
  styleUrl: './billing.scss'
})
export class InvoicesComponent implements OnInit, OnDestroy {
  private readonly api = inject(BillingApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyed = new Subject<void>();

  readonly accounts = signal<BillingAccount[]>([]);
  readonly accountId = signal('');
  readonly invoices = signal<Invoice[]>([]);
  readonly history = signal<BillingIntent[]>([]);
  readonly historyLoading = signal(false);
  readonly historyError = signal('');
  readonly selected = signal<string[]>([]);
  readonly method = signal<PaymentMethod>('CARD');
  readonly request = signal<SavedRequest | null>(null);
  readonly intent = signal<BillingIntent | null>(null);
  readonly loadingAccounts = signal(true);
  readonly loadingInvoices = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly invoiceError = signal('');
  readonly storageError = signal('');
  readonly submissionUncertain = signal(false);
  readonly returnedIntentId = signal('');
  /** True once a payment has been confirming for over a minute: the payer may leave, the result is kept. */
  readonly confirmingSlowly = signal(false);
  private readonly recoveryBlocked = signal(false);
  private watchTimer: ReturnType<typeof setTimeout> | null = null;
  private watchStartedAt = 0;
  readonly canPay = computed(() => this.accounts().find(a => a.id === this.accountId())?.role === 'PAYER');
  readonly selectedTotal = computed(() => this.invoices().filter(i => this.selected().includes(i.id))
    .reduce((total, i) => total + this.available(i), 0));
  readonly selectionLocked = computed(() => !!this.request() || this.busy());
  readonly canSubmit = computed(() => this.canPay() && this.intent()?.status === 'CREATED' && !this.busy() && !this.submissionUncertain());
  readonly terminal = computed(() => ['CAPTURED', 'FAILED', 'RETURNED', 'REFUNDED', 'CANCELLED'].includes(this.intent()?.status || ''));
  readonly receiptUrl = computed(() => this.intent() && ['CAPTURED', 'RETURNED', 'REFUNDED'].includes(this.intent()!.status)
    ? this.api.receiptUrl(this.intent()!.id) : null);
  readonly simulator = computed(() => this.intent()?.provider === 'SIMULATOR' && this.intent()?.simulatorEnabled === true);
  readonly checkoutUrl = computed(() => {
    const value = this.intent()?.checkoutUrl;
    if (this.intent()?.status !== 'REQUIRES_ACTION' || !value) return null;
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && url.hostname === 'checkout.stripe.com'
        && !url.username && !url.password && (!url.port || url.port === '443') ? url.href : null;
    } catch { return null; }
  });
  simulationOutcome: SimulationOutcome = 'PROCESSING';

  ngOnInit() {
    this.api.accounts().pipe(takeUntil(this.destroyed)).subscribe({
      next: accounts => {
        this.accounts.set(accounts);
        this.loadingAccounts.set(false);
        const returnedId = this.route.snapshot.queryParamMap.get('intentId');
        if (returnedId) {
          this.returnedIntentId.set(returnedId);
          this.recoverReturnedIntent();
          return;
        }
        let previous: string | null = null;
        try { previous = sessionStorage.getItem(this.storagePrefix() + ':account'); } catch { /* See create guard. */ }
        const account = accounts.find(a => a.id === previous) || accounts[0];
        if (account) this.selectAccount(account.id);
      },
      error: () => {
        this.loadingAccounts.set(false);
        this.error.set(PAYER_NOTICES.accountsUnavailable);
      }
    });
  }

  ngOnDestroy() { this.stopWatching(); this.destroyed.next(); this.destroyed.complete(); }

  recoverReturnedIntent() {
    const id = this.returnedIntentId();
    if (!id || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.api.intent(id).pipe(takeUntil(this.destroyed)).subscribe({
      next: intent => {
        this.busy.set(false);
        if (intent.id !== id || !this.accounts().some(a => a.id === intent.accountId)) {
          this.error.set(PAYER_NOTICES.paymentNotInYourAccounts);
          return;
        }
        this.accountId.set(intent.accountId);
        this.request.set(null);
        this.recoveryBlocked.set(false);
        this.storageError.set('');
        this.restoreRequest();
        if (this.recoveryBlocked()) return;
        const saved = this.request();
        const invoiceIds = (intent.allocations || []).map(a => a.invoiceId).sort();
        // Matching invoice contents cannot prove that an unknown create request is this intent.
        const matchesSaved = saved?.intentId === intent.id;
        if (saved && !matchesSaved) {
          this.returnedIntentId.set('');
          this.error.set(PAYER_NOTICES.otherPaymentInProgress(id));
          this.selected.set(saved.invoiceIds);
          this.loadInvoices();
          return;
        }
        const request: SavedRequest = saved || { accountId: intent.accountId, key: `recovered-${intent.id}`,
          invoiceIds, method: intent.method || 'CARD', intentId: intent.id };
        this.request.set(request);
        this.selected.set(request.invoiceIds);
        this.method.set(request.method);
        this.returnedIntentId.set('');
        try { sessionStorage.setItem(this.storagePrefix() + ':account', intent.accountId); } catch { /* Persist reports failure. */ }
        this.acceptIntent(intent);
      },
      error: () => {
        this.busy.set(false);
        this.error.set(PAYER_NOTICES.paymentLookupFailed);
      }
    });
  }

  selectAccount(id: string) {
    if (this.busy() || this.returnedIntentId() || !this.accounts().some(a => a.id === id)) return;
    this.accountId.set(id);
    this.invoices.set([]);
    this.history.set([]);
    this.selected.set([]);
    this.intent.set(null);
    this.request.set(null);
    this.submissionUncertain.set(false);
    this.stopWatching();
    this.recoveryBlocked.set(false);
    this.storageError.set('');
    this.error.set('');
    try { sessionStorage.setItem(this.storagePrefix() + ':account', id); } catch { /* Create remains guarded. */ }
    this.restoreRequest();
    this.loadInvoices();
    if (this.request()?.intentId) this.refreshIntent();
  }

  loadInvoices() {
    if (!this.accountId()) return;
    const accountId = this.accountId();
    this.loadHistory();
    this.loadingInvoices.set(true);
    this.invoiceError.set('');
    this.api.invoices(accountId).pipe(takeUntil(this.destroyed)).subscribe({
      next: invoices => {
        if (this.accountId() !== accountId) return;
        this.invoices.set(invoices);
        this.loadingInvoices.set(false);
        if (!this.request()) this.selected.update(ids => ids.filter(id => invoices.some(i => i.id === id && this.payable(i))));
      },
      error: () => {
        if (this.accountId() !== accountId) return;
        this.loadingInvoices.set(false);
        this.invoiceError.set(PAYER_NOTICES.invoicesUnavailable);
      }
    });
  }

  loadHistory() {
    const accountId = this.accountId();
    if (!accountId) return;
    this.historyLoading.set(true); this.historyError.set('');
    this.api.history(accountId).pipe(takeUntil(this.destroyed)).subscribe({
      next: intents => {
        if (accountId !== this.accountId()) return;
        this.history.set(intents); this.historyLoading.set(false);
      },
      error: () => {
        if (accountId !== this.accountId()) return;
        this.historyLoading.set(false);
        this.historyError.set(PAYER_NOTICES.historyUnavailable);
      }
    });
  }

  resumeIntent(id: string) {
    if (this.busy() || !this.history().some(intent => intent.id === id && intent.accountId === this.accountId())) return;
    this.returnedIntentId.set(id);
    this.recoverReturnedIntent();
  }

  available(invoice: Invoice) { return Math.max(0, invoice.totalMinor - invoice.paidMinor - invoice.reservedMinor); }
  // Payers see one number, what they still owe, and a badge while a payment holds the invoice.
  // Held and paid amounts stay on the finance screen.
  amountDue(invoice: Invoice) { return Math.max(0, invoice.totalMinor - invoice.paidMinor); }
  inProgress(invoice: Invoice) { return invoice.reservedMinor > 0; }
  invoiceStatusLabel(invoice: Invoice) {
    if (['VOID', 'CANCELLED'].includes(invoice.status)) return 'Cancelled';
    if (this.amountDue(invoice) === 0) return 'Paid';
    return invoice.paidMinor > 0 ? 'Part paid' : 'To pay';
  }
  payable(invoice: Invoice) {
    return invoice.currency === 'USD' && Number.isSafeInteger(this.available(invoice)) && this.available(invoice) > 0
      && !['VOID', 'CANCELLED'].includes(invoice.status);
  }
  toggleInvoice(invoice: Invoice, checked: boolean) {
    if (!this.canPay() || this.selectionLocked() || !this.payable(invoice)) return;
    this.selected.update(ids => checked ? [...new Set([...ids, invoice.id])] : ids.filter(id => id !== invoice.id));
  }

  createIntent() {
    if (this.busy() || this.returnedIntentId() || this.recoveryBlocked() || !this.accountId()) return;
    const existing = this.request();
    if (existing?.definitiveRejection) return;
    if (existing?.intentId) { this.refreshIntent(); return; }
    if (!existing) {
      if (!this.canPay() || this.loadingInvoices() || this.invoiceError()) return;
      const invoiceIds = [...this.selected()].sort();
      if (!invoiceIds.length || !invoiceIds.every(id => this.invoices().some(i => i.id === id && this.payable(i)))) return;
      const request: SavedRequest = { accountId: this.accountId(), invoiceIds, method: this.method(), key: crypto.randomUUID() };
      // Persist before sending: a lost response must never silently become a fresh operation.
      if (!this.persist(request)) return;
      this.request.set(request);
    }
    const request = this.request()!;
    this.busy.set(true);
    this.error.set('');
    this.api.create(request.accountId, { invoiceIds: request.invoiceIds, method: request.method }, request.key)
      .pipe(takeUntil(this.destroyed)).subscribe({
        next: intent => this.acceptIntent(intent),
        error: failure => {
          this.busy.set(false);
          if ([400, 409].includes(failure.status)
            && ['INVOICE_UNAVAILABLE', 'INVALID_REQUEST'].includes(failure.error?.code)) {
            const rejected = { ...request, definitiveRejection: true };
            this.request.set(rejected);
            this.persist(rejected);
            this.error.set(PAYER_NOTICES.paymentNotStarted);
            return;
          }
          this.error.set(PAYER_NOTICES.startNotConfirmed);
        }
      });
  }

  refreshIntent() {
    const id = this.request()?.intentId;
    if (!id || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.api.intent(id).pipe(takeUntil(this.destroyed)).subscribe({
      next: intent => this.acceptIntent(intent),
      error: () => {
        this.busy.set(false);
        this.error.set(PAYER_NOTICES.statusUnavailable);
      }
    });
  }

  submitIntent() {
    if (!this.canSubmit()) return;
    const intent = this.intent()!;
    const request = { ...this.request()!, uncertainSubmit: true };
    if (!this.persist(request)) return;
    this.request.set(request);
    this.submissionUncertain.set(true);
    this.busy.set(true);
    this.error.set('');
    this.api.submit(intent.id).pipe(takeUntil(this.destroyed)).subscribe({
      next: result => this.acceptIntent(result),
      error: failure => {
        this.busy.set(false);
        // Refused before anything was saved: certain that nothing was charged, so Pay can be pressed again.
        if (failure.status === 503 && failure.error?.code === 'PROVIDER_BUSY') {
          const refused = { ...request, uncertainSubmit: false };
          this.request.set(refused);
          this.persist(refused);
          this.submissionUncertain.set(false);
          this.error.set(PAYER_NOTICES.busyTryAgain);
          return;
        }
        this.error.set(PAYER_NOTICES.payNotConfirmed);
        this.watchPayment();
      }
    });
  }

  cancelIntent() {
    // Only an unsubmitted CREATED operation can release invoice reservations.
    if (!this.canSubmit()) return;
    const intent = this.intent()!;
    const request = { ...this.request()!, uncertainSubmit: true };
    if (!this.persist(request)) return;
    this.request.set(request); this.submissionUncertain.set(true);
    this.busy.set(true); this.error.set('');
    this.api.cancel(intent.id).pipe(takeUntil(this.destroyed)).subscribe({
      next: result => this.acceptIntent(result),
      error: () => {
        this.busy.set(false);
        this.error.set(PAYER_NOTICES.cancelNotConfirmed);
      }
    });
  }

  simulate() {
    if (!this.simulator() || this.busy() || !this.intent() || ['CREATED', 'CANCELLED'].includes(this.intent()!.status)) return;
    this.busy.set(true);
    this.error.set('');
    this.api.simulate(this.intent()!.id, this.simulationOutcome).pipe(takeUntil(this.destroyed)).subscribe({
      next: result => this.acceptIntent(result),
      error: () => {
        this.busy.set(false);
        this.error.set(PAYER_NOTICES.testResultNotConfirmed);
      }
    });
  }

  startAnother() {
    if ((!this.terminal() && !this.request()?.definitiveRejection) || this.busy()) return;
    try { sessionStorage.removeItem(this.storageKey()); } catch {
      this.storageError.set(PAYER_NOTICES.browserStorageBlockedReload);
      return;
    }
    this.request.set(null);
    this.intent.set(null);
    this.selected.set([]);
    this.submissionUncertain.set(false);
    this.stopWatching();
    this.error.set('');
    this.loadInvoices();
  }

  // Payers are not technical. Say what happened and what to do next; never call a payment successful
  // before it is confirmed, and never invite a second payment while one may still go through.
  statusExplanation() {
    switch (this.intent()?.status) {
      case 'CREATED': return 'Check the amount, then press Pay.';
      case 'REQUIRES_ACTION': return "Finish paying on the secure payment page. This page will update by itself when you're done.";
      case 'SUBMITTING':
      case 'UNKNOWN': return this.confirmingSlowly()
        ? "This is taking longer than usual. You can close this page safely; the result will appear in your recent payments. Please don't pay again."
        : "Processing your payment. Please don't close this page or pay again.";
      case 'PROCESSING': return 'Your bank payment is on its way. This can take a few days.';
      case 'CAPTURED': return 'Payment successful. Thank you.';
      case 'FAILED': return "Your payment didn't go through and you weren't charged. You can try again.";
      case 'CANCELLED': return 'This payment was never started, so nothing was charged. You can start again.';
      case 'RETURNED': return 'Your bank sent this payment back. The invoice is unpaid again. Please contact your finance team.';
      case 'REFUNDED': return 'This payment was refunded.';
      default: return "We're checking this payment. Please don't pay again yet.";
    }
  }

  statusLabel(status = this.intent()?.status) {
    switch (status) {
      case 'CREATED': return 'Ready to pay';
      case 'REQUIRES_ACTION': return 'Waiting for you';
      case 'SUBMITTING':
      case 'UNKNOWN': return 'Confirming';
      case 'PROCESSING': return 'On its way';
      case 'CAPTURED': return 'Paid';
      case 'FAILED': return 'Not paid';
      case 'CANCELLED': return 'Cancelled';
      case 'RETURNED': return 'Sent back';
      case 'REFUNDED': return 'Refunded';
      default: return 'Checking';
    }
  }
  methodLabel(method?: PaymentMethod) { return method === 'CARD' ? 'Card' : method === 'ACH' ? 'US bank account' : ''; }
  allocationLabel(state: string) {
    switch (state) {
      case 'RESERVED': return 'held for this payment';
      case 'POSTED': return 'paid';
      case 'RELEASED': return 'released';
      case 'REVERSED': return 'reversed';
      default: return state.toLowerCase();
    }
  }
  invoiceNumber(id: string) { return this.invoices().find(invoice => invoice.id === id)?.number || id; }

  private acceptIntent(intent: BillingIntent) {
    this.busy.set(false);
    if (!intent?.id || intent.accountId !== this.accountId()
      || (!!this.request()?.intentId && this.request()!.intentId !== intent.id)) {
      this.error.set(PAYER_NOTICES.answerDidNotMatch);
      return;
    }
    // A new status starts a new wait: the payer gets a fresh minute before "taking longer".
    if (intent.status !== this.intent()?.status) this.stopWatching();
    this.intent.set(intent);
    this.submissionUncertain.set(false);
    const request = { ...this.request()!, intentId: intent.id, uncertainSubmit: false };
    this.request.set(request);
    this.persist(request);
    this.loadInvoices();
    this.watchPayment();
  }

  /**
   * Keeps checking a payment that is still being confirmed, so the payer never has to refresh: every 3 seconds
   * for a minute, then every 15 seconds, for at most ten minutes. Invoices reload only when the status changes.
   */
  private watchPayment() {
    const id = this.request()?.intentId;
    const confirming = this.submissionUncertain() || ['SUBMITTING', 'UNKNOWN', 'REQUIRES_ACTION'].includes(this.intent()?.status || '');
    if (!id || !confirming) { this.stopWatching(); return; }
    if (this.watchTimer) return;
    if (!this.watchStartedAt) this.watchStartedAt = Date.now();
    const waited = Date.now() - this.watchStartedAt;
    this.confirmingSlowly.set(waited >= STATUS_CHECKS.fastForMs);
    if (waited >= STATUS_CHECKS.stopAfterMs) return;
    this.watchTimer = setTimeout(() => {
      this.watchTimer = null;
      if (this.busy()) { this.watchPayment(); return; }
      this.api.intent(id).pipe(takeUntil(this.destroyed)).subscribe({
        next: intent => intent.status !== this.intent()?.status || this.submissionUncertain()
          ? this.acceptIntent(intent) : this.watchPayment(),
        error: () => this.watchPayment()
      });
    }, waited < STATUS_CHECKS.fastForMs ? STATUS_CHECKS.fastEveryMs : STATUS_CHECKS.slowEveryMs);
  }
  private stopWatching() {
    if (this.watchTimer) clearTimeout(this.watchTimer);
    this.watchTimer = null;
    this.watchStartedAt = 0;
    this.confirmingSlowly.set(false);
  }

  private storagePrefix() { return `invoice-payments.billing.v1:${this.auth.user()?.userId || 'unavailable'}`; }
  private storageKey() { return `${this.storagePrefix()}:${this.accountId()}`; }
  private persist(request: SavedRequest) {
    if (!this.auth.user()?.userId) { this.storageError.set(PAYER_NOTICES.signInAgain); return false; }
    try {
      sessionStorage.setItem(this.storageKey(), JSON.stringify(request));
      this.storageError.set('');
      return true;
    } catch {
      this.storageError.set(PAYER_NOTICES.browserStorageBlocked);
      return false;
    }
  }
  private restoreRequest() {
    try {
      const raw = sessionStorage.getItem(this.storageKey());
      if (!raw) return;
      const saved: SavedRequest = JSON.parse(raw);
      if (saved.accountId !== this.accountId() || typeof saved.key !== 'string' || !saved.key
        || !Array.isArray(saved.invoiceIds) || (!saved.invoiceIds.length && !saved.intentId) || !saved.invoiceIds.every(id => typeof id === 'string')
        || !['CARD', 'ACH'].includes(saved.method)) {
        throw new Error('Invalid saved request');
      }
      this.request.set(saved);
      this.selected.set(saved.invoiceIds);
      this.method.set(saved.method);
      this.submissionUncertain.set(saved.uncertainSubmit === true);
    } catch {
      this.recoveryBlocked.set(true);
      this.storageError.set(PAYER_NOTICES.earlierPaymentUnreadable);
      // A corrupt recovery record cannot safely be replaced with a new operation.
      this.invoiceError.set(PAYER_NOTICES.payingBlocked);
    }
  }
}

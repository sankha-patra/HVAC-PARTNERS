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
  private readonly recoveryBlocked = signal(false);
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
        this.error.set('Business accounts could not be loaded. Refresh the page to try again.');
      }
    });
  }

  ngOnDestroy() { this.destroyed.next(); this.destroyed.complete(); }

  recoverReturnedIntent() {
    const id = this.returnedIntentId();
    if (!id || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.api.intent(id).pipe(takeUntil(this.destroyed)).subscribe({
      next: intent => {
        this.busy.set(false);
        if (intent.id !== id || !this.accounts().some(a => a.id === intent.accountId)) {
          this.error.set('The returned payment does not belong to an available business account. Contact support with this reference.');
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
          this.error.set(`Another payment request is saved for this account. Recover it first. The returned payment reference is ${id}.`);
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
        this.error.set('The returned payment could not be looked up. Refresh this reference before preparing another payment.');
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
        this.invoiceError.set('Invoice balances could not be refreshed. No new payment can be prepared until they are available.');
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
        this.historyError.set('Recent payment requests could not be loaded. Keep any existing reference and refresh before starting a replacement payment.');
      }
    });
  }

  resumeIntent(id: string) {
    if (this.busy() || !this.history().some(intent => intent.id === id && intent.accountId === this.accountId())) return;
    this.returnedIntentId.set(id);
    this.recoverReturnedIntent();
  }

  available(invoice: Invoice) { return Math.max(0, invoice.totalMinor - invoice.paidMinor - invoice.reservedMinor); }
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
            this.error.set('The server rejected this request before creating a payment. Return to invoice selection to refresh balances and correct your selection.');
            return;
          }
          this.error.set('The payment request could not be confirmed. Recover this same request below; do not start another payment for these invoices.');
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
        this.error.set('Payment status is unavailable. Keep this reference and refresh again; this does not mean the payment failed.');
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
      error: () => {
        this.busy.set(false);
        this.error.set('Submission could not be confirmed. Refresh this payment to recover its outcome before taking another action.');
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
        this.error.set('Cancellation could not be confirmed. Refresh this payment before submitting, cancelling again, or preparing another request.');
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
        this.error.set('The simulator result could not be confirmed. Refresh the payment; no real money is collected by the simulator.');
      }
    });
  }

  startAnother() {
    if ((!this.terminal() && !this.request()?.definitiveRejection) || this.busy()) return;
    try { sessionStorage.removeItem(this.storageKey()); } catch {
      this.storageError.set('Browser recovery storage is unavailable. Keep this payment reference and reload before continuing.');
      return;
    }
    this.request.set(null);
    this.intent.set(null);
    this.selected.set([]);
    this.submissionUncertain.set(false);
    this.error.set('');
    this.loadInvoices();
  }

  statusExplanation() {
    switch (this.intent()?.status) {
      case 'CREATED': return 'Your invoices are reserved for this request. Review the server-calculated amount before submitting.';
      case 'SUBMITTING': return 'Submission is in progress. Refresh this payment; do not create a second request.';
      case 'REQUIRES_ACTION': return 'Continue through the provider checkout. Returning here or closing checkout does not confirm payment.';
      case 'PROCESSING': return 'The payment is processing. Bank payments can take time and may later be returned.';
      case 'UNKNOWN': return 'The provider outcome is unknown. Keep this reference and refresh; do not create another payment for these invoices.';
      case 'CAPTURED': return 'Capture has been recorded. This is separate from bank payout settlement and ERP posting. Bank payments can still be returned.';
      case 'FAILED': return 'The server recorded a failed attempt. Refresh invoice balances before preparing another request.';
      case 'RETURNED': return 'A return has been recorded. Review the refreshed invoice balance and contact receivables if needed.';
      case 'REFUNDED': return 'A refund has been recorded. Its bank arrival and invoice treatment are separate steps.';
      case 'CANCELLED': return 'The server cancelled this unsubmitted request and released its invoice reservations. No payment was submitted by this request.';
      default: return 'No confirmed outcome is available. Refresh this payment before taking another action.';
    }
  }

  private acceptIntent(intent: BillingIntent) {
    this.busy.set(false);
    if (!intent?.id || intent.accountId !== this.accountId()
      || (!!this.request()?.intentId && this.request()!.intentId !== intent.id)) {
      this.error.set('The response could not be matched to this business account. Keep the existing request and contact support.');
      return;
    }
    this.intent.set(intent);
    this.submissionUncertain.set(false);
    const request = { ...this.request()!, intentId: intent.id, uncertainSubmit: false };
    this.request.set(request);
    this.persist(request);
    this.loadInvoices();
  }

  private storagePrefix() { return `invoice-payments.billing.v1:${this.auth.user()?.userId || 'unavailable'}`; }
  private storageKey() { return `${this.storagePrefix()}:${this.accountId()}`; }
  private persist(request: SavedRequest) {
    if (!this.auth.user()?.userId) { this.storageError.set('Sign in again before preparing a payment.'); return false; }
    try {
      sessionStorage.setItem(this.storageKey(), JSON.stringify(request));
      this.storageError.set('');
      return true;
    } catch {
      this.storageError.set('Browser recovery storage is unavailable. Enable it before submitting a payment, so an interrupted request can be recovered.');
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
      this.storageError.set('A saved payment request could not be recovered. Contact support before creating another payment for this account.');
      // A corrupt recovery record cannot safely be replaced with a new operation.
      this.invoiceError.set('Payment preparation is unavailable until the previous request is recovered.');
    }
  }
}

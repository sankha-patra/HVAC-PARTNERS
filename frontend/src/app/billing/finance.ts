import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subject, takeUntil } from 'rxjs';
import { AuthService } from '../shared/auth.service';
import { BillingApi, FinanceCase, FinancePayment, FinanceRefund } from './billing-api';

interface SavedRefundRequest {
  intentId: string; key: string; amountMinor: number; reason: string;
  refundId?: string; definitiveRejection?: boolean;
}

@Component({
  selector: 'app-finance', standalone: true,
  imports: [CommonModule, FormsModule, MatCardModule, MatButtonModule, MatProgressBarModule,
    MatFormFieldModule, MatInputModule],
  styleUrl: './billing.scss',
  template: `
    <div class="billing-page">
      <header class="page-heading"><p class="eyebrow">Merchant receivables</p><h1>Finance workspace</h1>
        <p>Review payments, refunds, and exceptions within your assigned merchant scope. Access is verified by the server.</p></header>
      <p class="notice">Reference policy: refunding a card receipt that was applied to invoices reopens the corresponding debt. Unapplied receipts are handled separately. ACH returns use a separate workflow. No card or bank details are collected here.</p>
      <p class="muted">Entries marked SIMULATOR are fictional and move no real money.</p>
      @if (loading() || paymentsLoading() || refundsLoading() || actionBusy()) { <mat-progress-bar mode="indeterminate" aria-label="Loading finance information" /> }
      @if (actionError()) { <p class="notice error" role="alert">{{ actionError() }}</p> }
      @if (storageError()) { <p class="notice error" role="alert">{{ storageError() }}</p> }
      @if (actionMessage()) { <p class="notice" role="status">{{ actionMessage() }}</p> }
      @if (uncertainApprovals().length) { <p class="notice">An approval response was interrupted. Refresh refunds to recover the recorded status; an interrupted response does not prove success or failure.</p> }

      @if (draftPayment() || refundRequest()) {
        <mat-card class="panel"><mat-card-content>
          <h2>{{ refundRequest() ? 'Refund request recovery' : 'Request a card refund' }}</h2>
          <p class="reference">Payment reference: {{ refundRequest()?.intentId || draftPayment()?.id }}</p>
          @if (!refundRequest()) {
            <p>Available to request: {{ draftPayment()!.availableRefundMinor / 100 | currency:'USD' }}. The server checks existing refund requests again.</p>
            <mat-form-field appearance="outline"><mat-label>Refund amount (USD)</mat-label>
              <input matInput inputmode="decimal" [(ngModel)]="refundAmount" [disabled]="actionBusy()" placeholder="25.00" autocomplete="off">
            </mat-form-field>
            <mat-form-field appearance="outline" class="refund-reason"><mat-label>Reason for refund</mat-label>
              <textarea matInput [(ngModel)]="refundReason" maxlength="500" rows="3" [disabled]="actionBusy()"></textarea>
            </mat-form-field>
            <p class="muted">A different authorized employee must approve the request. Requesting a refund does not send money.</p>
            <div class="actions"><button mat-flat-button (click)="sendRefundRequest()" [disabled]="actionBusy() || !!storageError()">Request approval</button>
              <button mat-button (click)="closeRefundPanel()" [disabled]="actionBusy()">Cancel draft</button></div>
          } @else {
            <p>{{ refundRequest()!.amountMinor / 100 | currency:'USD' }} · {{ refundRequest()!.reason }}</p>
            <p class="reference">Request reference: {{ refundRequest()!.key }}</p>
            @if (refundRequest()!.refundId) {
              <p class="reference">Refund reference: {{ refundRequest()!.refundId }}</p>
              <p>The request was recorded. Follow its server-reported status in the refund queue below. Approval and bank receipt are separate steps.</p>
              <button mat-stroked-button (click)="loadRefunds()" [disabled]="refundsLoading()">Refresh refunds</button>
              <button mat-button (click)="closeRefundPanel()" [disabled]="actionBusy()">Close recorded request</button>
            } @else if (refundRequest()!.definitiveRejection) {
              <p>The server rejected this request before creating a refund. Refresh payment balances before preparing a replacement.</p>
              <button mat-stroked-button (click)="closeRefundPanel()" [disabled]="actionBusy()">Return to payments</button>
            } @else {
              <p>This request has no confirmed response. Recovery reuses its original amount, reason, and request key.</p>
              <button mat-flat-button (click)="sendRefundRequest()" [disabled]="actionBusy()">Recover same refund request</button>
            }
          }
        </mat-card-content></mat-card>
      }

      <mat-card class="panel"><mat-card-content>
        <div class="section-heading"><h2>Payments</h2><button mat-stroked-button (click)="loadPayments()" [disabled]="paymentsLoading() || actionBusy()">Refresh payments</button></div>
        @if (paymentsError()) { <p class="notice error" role="alert">{{ paymentsError() }}</p> }
        @if (paymentsLoaded()) {
          @if (!payments().length) { <p>No payments were returned for your authorized scope.</p> }
          @else { <div class="table-scroll"><table><thead><tr><th scope="col">Account / payment</th><th scope="col">Amount</th><th scope="col">Refunded</th><th scope="col">Available to refund</th><th scope="col">Method / status</th><th scope="col">Action</th></tr></thead><tbody>
            @for (payment of payments(); track payment.id) { <tr><th scope="row">{{ payment.accountName }}<p class="reference">{{ payment.id }}</p></th>
              <td>{{ payment.amountMinor / 100 | currency:payment.currency }}</td><td>{{ payment.refundedMinor / 100 | currency:payment.currency }}</td>
              <td>{{ payment.availableRefundMinor / 100 | currency:payment.currency }}</td><td>{{ payment.method }} · {{ payment.status }}<p class="muted">{{ payment.provider || 'Provider not reported' }}</p></td><td>
              @if (payment.canRequestRefund === true) { <button mat-button (click)="beginRefund(payment.id)" [disabled]="actionBusy() || !!refundRequest() || !!storageError()">Request refund</button> }
              @else { <span class="muted">No refund action available</span> }</td></tr> }
          </tbody></table></div> }
        }
      </mat-card-content></mat-card>

      <mat-card class="panel"><mat-card-content>
        <div class="section-heading"><h2>Refund requests</h2><button mat-stroked-button (click)="loadRefunds()" [disabled]="refundsLoading() || actionBusy()">Refresh refunds</button></div>
        <p class="muted">Approval is available only when the server grants it to a different authorized employee. Processing or unknown status does not mean the customer received money.</p>
        @if (refundsError()) { <p class="notice error" role="alert">{{ refundsError() }}</p> }
        @if (refundsLoaded()) {
          @if (!refunds().length) { <p>No refund requests were returned for your authorized scope.</p> }
          @else { <div class="table-scroll"><table><thead><tr><th scope="col">Refund / payment</th><th scope="col">Amount</th><th scope="col">Reason</th><th scope="col">Requested by</th><th scope="col">Approved by</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead><tbody>
            @for (refund of refunds(); track refund.id) { <tr><th scope="row" class="reference">{{ refund.id }}<p>{{ refund.intentId }}</p></th><td>{{ refund.amountMinor / 100 | currency:'USD' }}</td>
              <td class="reason-cell">{{ refund.reason }}</td><td>{{ refund.requestedBy }}</td><td>{{ refund.approvedBy || 'Not recorded' }}</td><td>{{ refund.status }}<p class="muted">{{ refund.provider || 'Provider not reported' }}</p></td>
              <td>@if (refund.canApprove === true) { <button mat-stroked-button (click)="approveRefund(refund.id)" [disabled]="actionBusy() || !!storageError() || uncertainApprovals().includes(refund.id)">Approve refund</button> }
              @else { <span class="muted">No approval action available</span> }</td></tr> }
          </tbody></table></div> }
        }
      </mat-card-content></mat-card>
      @if (error()) { <p class="notice error" role="alert">{{ error() }}</p> }
      <mat-card class="panel"><mat-card-content>
        <div class="section-heading"><h2>Cases requiring review</h2><button mat-stroked-button (click)="load()" [disabled]="loading()">Refresh cases</button></div>
        <p class="muted">This queue is read-only. A case is evidence to investigate, not permission to overwrite payment history.</p>
        @if (loaded()) {
          @if (!cases().length) { <p>No cases were returned for your authorized scope.</p> }
          @else {
            <div class="table-scroll"><table><thead><tr><th scope="col">Case</th><th scope="col">Payment reference</th><th scope="col">Reason</th><th scope="col">Status</th><th scope="col">Opened</th></tr></thead>
              <tbody>@for (item of cases(); track item.id) {
                <tr><th scope="row" class="reference">{{ item.id }}</th><td class="reference">{{ item.intentId }}</td><td>{{ item.reason }}</td><td>{{ item.status }}</td><td>{{ item.createdAt | date:'medium' }}</td></tr>
              }</tbody></table></div>
          }
        }
      </mat-card-content></mat-card>
    </div>
  `
})
export class FinanceComponent implements OnInit, OnDestroy {
  private readonly api = inject(BillingApi);
  private readonly auth = inject(AuthService);
  private readonly destroyed = new Subject<void>();
  readonly cases = signal<FinanceCase[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly error = signal('');
  readonly payments = signal<FinancePayment[]>([]);
  readonly refunds = signal<FinanceRefund[]>([]);
  readonly paymentsLoading = signal(false);
  readonly paymentsLoaded = signal(false);
  readonly paymentsError = signal('');
  readonly refundsLoading = signal(false);
  readonly refundsLoaded = signal(false);
  readonly refundsError = signal('');
  readonly actionBusy = signal(false);
  readonly actionError = signal('');
  readonly actionMessage = signal('');
  readonly storageError = signal('');
  readonly draftPayment = signal<FinancePayment | null>(null);
  readonly refundRequest = signal<SavedRefundRequest | null>(null);
  readonly uncertainApprovals = signal<string[]>([]);
  private recoveryBlocked = false;
  refundAmount = '';
  refundReason = '';

  ngOnInit() { this.restore(); this.load(); this.loadPayments(); this.loadRefunds(); }
  ngOnDestroy() { this.destroyed.next(); this.destroyed.complete(); }
  load() {
    if (this.loading()) return;
    this.loading.set(true);
    this.loaded.set(false);
    this.cases.set([]);
    this.error.set('');
    this.api.cases().pipe(takeUntil(this.destroyed)).subscribe({
      next: cases => { this.cases.set(cases); this.loading.set(false); this.loaded.set(true); },
      error: error => {
        this.loading.set(false);
        this.error.set(error.status === 403 ? 'Finance access is required. Contact your merchant administrator if you need this role.'
          : 'Cases could not be loaded. Refresh to try again; an unavailable queue does not mean there are no exceptions.');
      }
    });
  }

  loadPayments() {
    if (this.paymentsLoading()) return;
    this.paymentsLoading.set(true); this.paymentsLoaded.set(false); this.paymentsError.set('');
    this.api.financePayments().pipe(takeUntil(this.destroyed)).subscribe({
      next: rows => { this.payments.set(rows); this.paymentsLoading.set(false); this.paymentsLoaded.set(true); },
      error: error => {
        this.paymentsLoading.set(false);
        this.paymentsError.set(error.status === 403 ? 'Merchant finance access is required to view these payments.' : 'Payments could not be loaded. Refresh before preparing a refund.');
      }
    });
  }

  loadRefunds() {
    if (this.refundsLoading()) return;
    this.refundsLoading.set(true); this.refundsLoaded.set(false); this.refundsError.set('');
    this.api.refunds().pipe(takeUntil(this.destroyed)).subscribe({
      next: rows => {
        this.refunds.set(rows); this.refundsLoading.set(false); this.refundsLoaded.set(true);
        this.uncertainApprovals.update(ids => ids.filter(id => !rows.some(row => row.id === id)));
        this.persistApprovals();
      },
      error: error => {
        this.refundsLoading.set(false);
        this.refundsError.set(error.status === 403 ? 'Merchant finance or refund approval access is required.' : 'Refund status is unavailable. Keep the existing references; no outcome can be inferred from this interruption.');
      }
    });
  }

  beginRefund(intentId: string) {
    if (this.actionBusy() || this.refundRequest() || this.recoveryBlocked || !this.paymentsLoaded()) return;
    const payment = this.payments().find(row => row.id === intentId);
    if (payment?.canRequestRefund !== true) return;
    this.draftPayment.set(payment);
    this.refundAmount = this.formatMinor(payment.availableRefundMinor);
    this.refundReason = '';
    this.actionError.set(''); this.actionMessage.set('');
  }

  sendRefundRequest() {
    if (this.actionBusy() || this.recoveryBlocked) return;
    let request = this.refundRequest();
    if (request?.refundId || request?.definitiveRejection) return;
    if (!request) {
      const payment = this.draftPayment();
      const latest = this.payments().find(row => row.id === payment?.id);
      const amountMinor = this.parseMinor(this.refundAmount);
      const reason = this.refundReason.trim();
      if (!payment || latest?.canRequestRefund !== true || !this.paymentsLoaded()) return;
      if (amountMinor === null || amountMinor > latest.availableRefundMinor || !reason || reason.length > 500) {
        this.actionError.set('Enter a positive USD amount with at most two decimal places, within the available refund balance, and a reason of at most 500 characters.');
        return;
      }
      request = { intentId: payment.id, amountMinor, reason, key: crypto.randomUUID() };
      if (!this.persistRequest(request)) return;
      this.refundRequest.set(request);
    }
    this.actionBusy.set(true); this.actionError.set(''); this.actionMessage.set('');
    const original = request;
    this.api.requestRefund(original.intentId, { amountMinor: original.amountMinor, reason: original.reason }, original.key)
      .pipe(takeUntil(this.destroyed)).subscribe({
        next: result => {
          this.actionBusy.set(false);
          if (!result.id || result.intentId !== original.intentId || result.amountMinor !== original.amountMinor) {
            this.actionError.set('The response could not be matched to this refund request. Recover the same request or contact support.'); return;
          }
          const saved = { ...original, refundId: result.id };
          this.refundRequest.set(saved); this.persistRequest(saved);
          this.actionMessage.set(`Refund request recorded. Server status: ${result.status}. A request is separate from approval and confirmed refund payment.`);
          this.loadRefunds(); this.loadPayments();
        },
        error: failure => {
          this.actionBusy.set(false);
          if ([400, 409].includes(failure.status) && ['INVALID_REQUEST', 'REFUND_UNAVAILABLE'].includes(failure.error?.code)) {
            const saved = { ...original, definitiveRejection: true };
            this.refundRequest.set(saved); this.persistRequest(saved);
            this.actionError.set('The server rejected this request before creating a refund. Return to payments and refresh the available balance.');
            return;
          }
          this.actionError.set('The refund request outcome is unconfirmed. Recover the same request; do not submit a replacement with a new reference.');
        }
      });
  }

  approveRefund(id: string) {
    if (this.actionBusy() || this.recoveryBlocked || !this.refundsLoaded() || this.uncertainApprovals().includes(id)
      || this.refunds().find(row => row.id === id)?.canApprove !== true) return;
    this.uncertainApprovals.update(ids => [...ids, id]);
    if (!this.persistApprovals()) return;
    this.actionBusy.set(true); this.actionError.set(''); this.actionMessage.set('');
    this.api.approveRefund(id).pipe(takeUntil(this.destroyed)).subscribe({
      next: result => {
        this.actionBusy.set(false);
        if (result.id !== id) { this.actionError.set('The approval response could not be matched. Refresh refunds before taking another action.'); return; }
        this.uncertainApprovals.update(ids => ids.filter(value => value !== id));
        this.persistApprovals();
        this.refunds.update(rows => rows.map(row => row.id === id ? { ...row, ...result, canApprove: false } : row));
        this.actionMessage.set(this.approvalMessage(result.status));
        this.loadRefunds(); this.loadPayments();
      },
      error: () => {
        this.actionBusy.set(false);
        this.actionError.set('Approval has no confirmed response. Refresh refunds to recover the server status; do not infer that money was returned.');
      }
    });
  }

  closeRefundPanel() {
    if (this.actionBusy() || (this.refundRequest() && !this.refundRequest()?.refundId && !this.refundRequest()?.definitiveRejection)) return;
    try { sessionStorage.removeItem(this.storageKey()); } catch { this.storageError.set('Recovery storage is unavailable. Keep the request reference before continuing.'); return; }
    this.refundRequest.set(null); this.draftPayment.set(null);
    this.refundAmount = ''; this.refundReason = ''; this.actionError.set(''); this.actionMessage.set('');
    this.loadPayments();
  }

  private approvalMessage(status: string) {
    if (status === 'REFUNDED') return 'The server recorded a confirmed refund. Applied invoice receipts are reversed under this reference policy; bank arrival is separate. Simulator entries move no real money.';
    if (status === 'UNKNOWN' || status === 'PROCESSING' || status === 'SUBMITTING') return `Refund status: ${status}. The final outcome is not confirmed. Refresh this same refund; do not create a replacement.`;
    return `The server recorded refund status: ${status}. This does not by itself confirm that the customer received funds.`;
  }

  private parseMinor(text: string): number | null {
    const match = /^(0|[1-9]\d{0,10})(?:\.(\d{1,2}))?$/.exec(text.trim());
    if (!match) return null;
    const amount = BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0'));
    return amount > 0n && amount <= 1_000_000_000_000n ? Number(amount) : null;
  }
  private formatMinor(minor: number) { return `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`; }
  private storageKey() { return `invoice-payments.billing.refund.v1:${this.auth.user()?.userId || 'unavailable'}`; }
  private persistRequest(request: SavedRefundRequest) {
    if (!this.auth.user()?.userId) { this.storageError.set('Sign in again before requesting a refund.'); return false; }
    try { sessionStorage.setItem(this.storageKey(), JSON.stringify(request)); this.storageError.set(''); return true; }
    catch { this.storageError.set('Browser recovery storage is unavailable. Enable it before submitting a refund request.'); return false; }
  }
  private persistApprovals() {
    if (!this.auth.user()?.userId) { this.storageError.set('Sign in again before approving a refund.'); return false; }
    try { sessionStorage.setItem(this.storageKey() + ':approvals', JSON.stringify(this.uncertainApprovals())); return true; }
    catch { this.storageError.set('Browser recovery storage is unavailable. Enable it and refresh refunds before approving.'); return false; }
  }
  private restore() {
    try {
      const raw = sessionStorage.getItem(this.storageKey());
      if (raw) {
        const saved: SavedRefundRequest = JSON.parse(raw);
        if (!saved.intentId || typeof saved.intentId !== 'string' || !saved.key || typeof saved.key !== 'string'
          || !Number.isSafeInteger(saved.amountMinor) || saved.amountMinor <= 0 || saved.amountMinor > 1_000_000_000_000
          || typeof saved.reason !== 'string' || !saved.reason.trim() || saved.reason.length > 500) throw new Error('Invalid refund recovery record');
        this.refundRequest.set(saved);
      }
      const approvals: unknown = JSON.parse(sessionStorage.getItem(this.storageKey() + ':approvals') || '[]');
      if (!Array.isArray(approvals) || !approvals.every(id => typeof id === 'string')) throw new Error('Invalid approval recovery record');
      this.uncertainApprovals.set(approvals);
    } catch {
      this.recoveryBlocked = true;
      this.storageError.set('A saved refund operation could not be recovered. Contact support before requesting another refund.');
    }
  }
}

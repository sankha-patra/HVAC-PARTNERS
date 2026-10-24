import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';

export type PaymentMethod = 'CARD' | 'ACH';
export type SimulationOutcome = 'CAPTURED' | 'FAILED' | 'PROCESSING' | 'RETURNED' | 'TIMEOUT_AFTER_ACCEPT';
export interface BillingAccount { id: string; name: string; merchantId: string; role: string; }
export interface Invoice {
  id: string; number: string; currency: string; totalMinor: number; paidMinor: number;
  reservedMinor: number; status: string; dueDate: string;
}
export interface BillingIntent {
  id: string; accountId: string; currency: string; amountMinor: number; status: string;
  provider: string; simulatorEnabled?: boolean; checkoutUrl?: string; message?: string;
  method?: PaymentMethod;
  createdAt?: string;
  allocations?: { invoiceId: string; amountMinor: number; state: string }[];
  timeline?: { type: string; at: string; message: string }[];
}
export interface FinanceCase {
  id: string; intentId: string; reason: string; status: string; createdAt: string;
  refundId?: string | null; paymentStatus?: string | null; refundStatus?: string | null;
  resolution?: string | null; resolvedBy?: string | null; resolvedAt?: string | null;
  releaseRequestedBy?: string | null; releaseNote?: string | null; releaseRequestedAt?: string | null;
  canVerifyReference?: boolean; canRequestRelease?: boolean; canApproveRelease?: boolean; canClose?: boolean;
  canAddNote?: boolean; canRecordExternalConfirmation?: boolean; externalConfirmation?: ExternalConfirmation | null;
}
/** What Carrier finance saw in the Stripe Dashboard or heard from Stripe support. */
export interface ExternalConfirmation {
  source: string; account: string; searched: string; result: string; ticket: string | null; checkedAt: string; evidenceSha256: string;
}
export interface CaseAction { action: string; actor: string; note?: string | null; evidence?: string | null; createdAt: string; }
export interface ProviderEvidence {
  id: number; refundId?: string; createdAt: string; diagnostics: string;
}
export type CaseCommand = 'verify' | 'request-release' | 'approve-release' | 'reject-release' | 'close' | 'note' | 'external-confirmation';
export interface FinancePayment {
  id: string; accountId: string; merchantId: string; accountName: string; amountMinor: number; refundedMinor: number;
  availableRefundMinor: number; currency: string; status: string; method: PaymentMethod; createdAt: string;
  canRequestRefund: boolean;
  provider?: string;
}
export interface FinanceRefund {
  id: string; intentId: string; amountMinor: number; reason: string; requestedBy: string;
  approvedBy?: string; status: string; createdAt: string; canApprove?: boolean; canReject?: boolean;
  provider?: string;
}
export interface FinanceMerchant { id: string; name: string; canImport: boolean; canConfirm: boolean; }
export interface SettlementReport {
  id: string; merchantId: string; currency: string; externalId: string; source: string;
  grossMinor: number; feeMinor: number; netMinor: number; bankReference: string; status: string;
  createdAt: string; canConfirm: boolean; paymentIds: string[];
}
export interface SettlementLine { intentId: string; amountMinor: number; }
export interface SettlementImport {
  merchantId: string; externalId: string; source: 'SIMULATOR' | 'PROVIDER'; grossMinor: number;
  feeMinor: number; netMinor: number; bankReference: string; lines: SettlementLine[];
}
export interface BankCreditConfirmation { bankReference: string; amountMinor: number; evidenceHash: string; }
export interface IntentRequest { invoiceIds: string[]; method: PaymentMethod; }

@Injectable({ providedIn: 'root' })
export class BillingApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/billing`;

  accounts() { return this.http.get<BillingAccount[]>(`${this.base}/accounts`); }
  invoices(accountId: string) {
    return this.http.get<Invoice[]>(`${this.base}/accounts/${encodeURIComponent(accountId)}/invoices`);
  }
  create(accountId: string, request: IntentRequest, key: string) {
    return this.http.post<BillingIntent>(`${this.base}/accounts/${encodeURIComponent(accountId)}/intents`, request,
      { headers: { 'Idempotency-Key': key } });
  }
  intent(id: string) { return this.http.get<BillingIntent>(`${this.base}/intents/${encodeURIComponent(id)}`); }
  history(accountId: string) {
    return this.http.get<BillingIntent[]>(`${this.base}/accounts/${encodeURIComponent(accountId)}/intents`);
  }
  submit(id: string) { return this.http.post<BillingIntent>(`${this.base}/intents/${encodeURIComponent(id)}/submit`, {}); }
  cancel(id: string) { return this.http.post<BillingIntent>(`${this.base}/intents/${encodeURIComponent(id)}/cancel`, {}); }
  receiptUrl(id: string) { return `${this.base}/intents/${encodeURIComponent(id)}/receipt`; }
  simulate(id: string, outcome: SimulationOutcome) {
    return this.http.post<BillingIntent>(`${this.base}/intents/${encodeURIComponent(id)}/simulate`, { outcome });
  }
  cases() { return this.http.get<FinanceCase[]>(`${this.base}/finance/cases`); }
  caseHistory(caseId: string) { return this.http.get<CaseAction[]>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/actions`); }
  verifyCaseReference(caseId: string, body: { reference: string; note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/provider-reference`, body);
  }
  requestCaseRelease(caseId: string, body: { note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/release-request`, body);
  }
  approveCaseRelease(caseId: string) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/release-approval`, {});
  }
  rejectCaseRelease(caseId: string, body: { note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/release-rejection`, body);
  }
  closeCase(caseId: string, body: { note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/close`, body);
  }
  addCaseNote(caseId: string, body: { note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/notes`, body);
  }
  recordExternalConfirmation(caseId: string, body: ExternalConfirmation & { note: string }) {
    return this.http.post<FinanceCase>(`${this.base}/finance/cases/${encodeURIComponent(caseId)}/external-confirmation`, body);
  }
  financePayments() { return this.http.get<FinancePayment[]>(`${this.base}/finance/payments`); }
  providerEvidence(intentId: string) {
    return this.http.get<ProviderEvidence[]>(`${this.base}/finance/payments/${encodeURIComponent(intentId)}/provider-evidence`);
  }
  refunds() { return this.http.get<FinanceRefund[]>(`${this.base}/finance/refunds`); }
  requestRefund(intentId: string, body: { amountMinor: number; reason: string }, key: string) {
    return this.http.post<FinanceRefund>(`${this.base}/finance/intents/${encodeURIComponent(intentId)}/refunds`, body,
      { headers: { 'Idempotency-Key': key } });
  }
  approveRefund(refundId: string) {
    return this.http.post<FinanceRefund>(`${this.base}/finance/refunds/${encodeURIComponent(refundId)}/approve`, {});
  }
  rejectRefund(refundId: string, body: { note: string }) {
    return this.http.post<FinanceRefund>(`${this.base}/finance/refunds/${encodeURIComponent(refundId)}/reject`, body);
  }
  reconciliationMerchants() { return this.http.get<FinanceMerchant[]>(`${this.base}/finance/merchants`); }
  settlements() { return this.http.get<SettlementReport[]>(`${this.base}/finance/settlements`); }
  importSettlement(body: SettlementImport) {
    return this.http.post<{ id: string; externalId: string; source: string; state: string }>(`${this.base}/finance/reconciliation/import`, body);
  }
  confirmBankCredit(reportId: string, body: BankCreditConfirmation) {
    return this.http.post<SettlementReport>(`${this.base}/finance/settlements/${encodeURIComponent(reportId)}/bank-confirmation`, body);
  }
}

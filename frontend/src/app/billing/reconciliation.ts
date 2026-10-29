import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Subject, takeUntil } from 'rxjs';
import { AuthService } from '../shared/auth.service';
import { BillingApi, FinanceMerchant, FinancePayment, SettlementImport, SettlementReport } from './billing-api';

interface SavedImport { merchantId: string; body: SettlementImport; }
interface SavedBankCheck { reportId: string; bankReference: string; amountMinor: number; evidenceHash: string; }

@Component({
  selector: 'app-reconciliation', standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatProgressBarModule, MatSelectModule],
  template: `
    <div class="billing-page">
      <header class="page-heading"><p class="eyebrow">Merchant cash operations</p><h1>Settlement reconciliation</h1>
        <p>Import a manually normalized capture report, then have a separate authorized reviewer match the reported net credit to independently checked bank evidence.</p></header>
      <p class="notice">Enter details from a payment-provider report and an independently checked bank statement. This workspace records your review; it does not connect to your bank or verify the source document.</p>
      @if (loading() || busy()) { <mat-progress-bar mode="indeterminate" aria-label="Loading reconciliation" /> }
      @if (error()) { <p class="notice error" role="alert">{{ error() }}</p> }
      @if (message()) { <p class="notice" role="status">{{ message() }}</p> }
      @if (!loading() && !merchants().length && !error()) { <p class="notice">No merchant finance or reviewer assignment was returned for this sign-in.</p> }
      @if (merchants().length) {
        <mat-form-field appearance="outline" class="account-picker">
          <mat-label>Merchant scope</mat-label>
          <mat-select [value]="merchantId()" (selectionChange)="selectMerchant($event.value)" [disabled]="busy() || !!savedImport()">
            @for (merchant of merchants(); track merchant.id) { <mat-option [value]="merchant.id">{{ merchant.name }}</mat-option> }
          </mat-select>
        </mat-form-field>
        @if (selectedMerchant()?.canImport) {
          <mat-card class="panel"><mat-card-content>
            <h2>Import normalized capture report</h2>
            <p>Choose captured payment rows from this merchant. The report total is calculated from their original capture amounts; enter the report fee and provider/accounting references.</p>
            <mat-form-field appearance="outline"><mat-label>Report currency</mat-label><mat-select [value]="reportCurrency()" (selectionChange)="selectCurrency($event.value)" [disabled]="busy() || !!savedImport()">
              @for (currency of reportCurrencies(); track currency) { <mat-option [value]="currency">{{ currency }}</mat-option> }
            </mat-select></mat-form-field>
            <p class="muted">A simulator source is marked SIMULATOR. A PROVIDER source is still a manual assertion and does not mean the application fetched a provider settlement report.</p>
            @if (!capturedPayments().length) { <p>No captured payments are available in this authorized merchant scope.</p> }
            @else { <div class="table-scroll"><table><thead><tr><th scope="col">Select</th><th scope="col">Payment</th><th scope="col">Provider</th><th scope="col">Captured gross</th></tr></thead><tbody>
              @for (payment of capturedPayments(); track payment.id) { <tr><td><input type="checkbox" [aria-label]="'Include payment ' + payment.id" [checked]="selectedPayments().includes(payment.id)" [disabled]="busy() || !!savedImport()" (change)="togglePayment(payment.id, $any($event.target).checked)"></td><th scope="row" class="reference">{{ payment.id }}</th><td>{{ payment.provider }}</td><td>{{ payment.amountMinor / 100 | currency:payment.currency }}</td></tr> }
            </tbody></table></div> }
            <p><strong>Gross:</strong> {{ selectedGrossMinor() / 100 | currency:reportCurrency() }} · <strong>Net:</strong> {{ computedNetMinor() / 100 | currency:reportCurrency() }} · <strong>Payments:</strong> {{ selectedPayments().length }}</p>
            <div class="form-row">
              <mat-form-field appearance="outline"><mat-label>Report reference</mat-label><input matInput [(ngModel)]="externalId" maxlength="150" [disabled]="busy() || !!savedImport()"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Bank deposit reference</mat-label><input matInput [(ngModel)]="bankReference" maxlength="150" [disabled]="busy() || !!savedImport()"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Provider fee ({{ reportCurrency() }})</mat-label><input matInput inputmode="decimal" [(ngModel)]="feeAmount" placeholder="0.00" [disabled]="busy() || !!savedImport()"></mat-form-field>
            </div>
            @if (savedImport()) { <p class="notice">The report response was interrupted. Retry the same report reference and exact saved evidence; do not create a replacement report.</p>
              <button mat-flat-button (click)="submitImport()" [disabled]="busy()">Recover this report import</button>
            } @else {
              <button mat-flat-button (click)="submitImport()" [disabled]="busy() || !canImport()">Import manually normalized report</button>
            }
          </mat-card-content></mat-card>
        }

        @if (selectedMerchant()?.canConfirm && confirmReport()) {
          <mat-card class="panel"><mat-card-content>
            <h2>Check bank credit</h2>
            <p>Use a separately authorized reviewer. Confirm the exact report net and bank reference after checking the source statement outside this app.</p>
            <p class="reference">Report: {{ confirmReport()!.externalId }} · Net {{ confirmReport()!.netMinor / 100 | currency:confirmReport()!.currency }}</p>
            <div class="form-row">
              <mat-form-field appearance="outline"><mat-label>Statement bank reference</mat-label><input matInput [(ngModel)]="confirmationReference" maxlength="150" [disabled]="busy() || !!savedBankCheck()"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Confirmed credit ({{ confirmReport()!.currency }})</mat-label><input matInput inputmode="decimal" [(ngModel)]="confirmationAmount" [disabled]="busy() || !!savedBankCheck()"></mat-form-field>
              <mat-form-field appearance="outline" class="hash-field"><mat-label>Statement SHA-256 digest</mat-label><input matInput [(ngModel)]="evidenceHash" maxlength="64" [disabled]="busy() || !!savedBankCheck()"></mat-form-field>
            </div>
            @if (savedBankCheck()) { <p class="notice">The confirmation response was interrupted. Retry the exact saved bank evidence or refresh the report status.</p>
              <button mat-flat-button (click)="submitBankCheck()" [disabled]="busy()">Recover this bank check</button>
            } @else {
              <button mat-flat-button (click)="submitBankCheck()" [disabled]="busy() || !canConfirm()">Record independent bank check</button>
            }
          </mat-card-content></mat-card>
        }

        <mat-card class="panel"><mat-card-content>
          <div class="section-heading"><h2>Imported reports</h2><button mat-stroked-button (click)="refresh()" [disabled]="busy()">Refresh reports</button></div>
          <p class="muted">MATCHED means the report lines matched recorded captures and a different reviewer entered a matching bank reference, amount, and evidence digest. It does not mean the app connected to the bank.</p>
          @if (!reports().length) { <p>No reports are available for the selected merchant.</p> }
          @else { <div class="table-scroll"><table><thead><tr><th scope="col">Report</th><th scope="col">Source</th><th scope="col">Gross</th><th scope="col">Fee</th><th scope="col">Net</th><th scope="col">Bank reference</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead><tbody>
            @for (report of reports(); track report.id) { <tr><th scope="row" class="reference">{{ report.externalId }}<p>{{ report.createdAt | date:'medium' }}</p></th><td>{{ report.source }}</td><td>{{ report.grossMinor / 100 | currency:report.currency }}</td><td>{{ report.feeMinor / 100 | currency:report.currency }}</td><td>{{ report.netMinor / 100 | currency:report.currency }}</td><td>{{ report.bankReference }}</td><td>{{ report.status }}</td><td>@if (report.canConfirm && report.status !== 'MATCHED') { <button mat-button (click)="prepareBankCheck(report)" [disabled]="busy()">Check bank credit</button> } @else { <span class="muted">No action available</span> }</td></tr> }
          </tbody></table></div> }
        </mat-card-content></mat-card>
      }
    </div>
  `,
  styleUrl: './billing.scss'
})
export class ReconciliationComponent implements OnInit, OnDestroy {
  private readonly api = inject(BillingApi);
  private readonly auth = inject(AuthService);
  private readonly destroyed = new Subject<void>();
  readonly merchants = signal<FinanceMerchant[]>([]);
  readonly merchantId = signal('');
  readonly payments = signal<FinancePayment[]>([]);
  readonly reports = signal<SettlementReport[]>([]);
  readonly selectedPayments = signal<string[]>([]);
  readonly reportCurrency = signal('USD');
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly confirmReport = signal<SettlementReport | null>(null);
  readonly savedImport = signal<SavedImport | null>(null);
  readonly savedBankCheck = signal<SavedBankCheck | null>(null);
  readonly selectedMerchant = computed(() => this.merchants().find(m => m.id === this.merchantId()) || null);
  readonly reportCurrencies = computed(() => [...new Set(this.payments().filter(p => p.merchantId === this.merchantId() && p.status === 'CAPTURED' && ['INR','USD'].includes(p.currency)).map(p => p.currency))].sort());
  readonly capturedPayments = computed(() => this.payments().filter(p => p.merchantId === this.merchantId() && p.status === 'CAPTURED' && p.currency === this.reportCurrency()));
  readonly selectedGrossMinor = computed(() => this.capturedPayments().filter(p => this.selectedPayments().includes(p.id)).reduce((sum,p)=>sum+p.amountMinor,0));
  computedNetMinor() { return Math.max(0,this.selectedGrossMinor()-(this.parseMinor(this.feeAmount) ?? 0)); }
  externalId: string = crypto.randomUUID();
  bankReference = '';
  feeAmount = '0.00';
  confirmationReference = '';
  confirmationAmount = '';
  evidenceHash = '';
  private readonly storageKey: string;

  constructor() {
    const user = this.auth.user();
    this.storageKey = `billing-reconciliation:v1:${encodeURIComponent(user?.username || user?.userId || 'unknown')}`;
  }
  ngOnInit() { this.restore(); this.loadBase(); }
  ngOnDestroy() { this.destroyed.next(); this.destroyed.complete(); }
  selectMerchant(id: string) {
    if(this.busy() || this.savedImport() || !this.merchants().some(m=>m.id===id)) return;
    this.merchantId.set(id);this.selectedPayments.set([]);this.confirmReport.set(null);this.loadPayments();this.loadReports();
  }
  togglePayment(id: string, checked: boolean) {
    if(this.savedImport() || !this.capturedPayments().some(p=>p.id===id)) return;
    this.selectedPayments.update(ids=>checked?[...new Set([...ids,id])]:ids.filter(x=>x!==id));
  }
  selectCurrency(currency: string) {
    if(this.busy() || this.savedImport() || !this.reportCurrencies().includes(currency)) return;
    this.reportCurrency.set(currency);this.selectedPayments.set([]);this.feeAmount='0.00';
  }
  canImport() {
    const merchant=this.selectedMerchant(), ids=this.selectedPayments();
    const fee=this.parseMinor(this.feeAmount);const gross=this.selectedGrossMinor();
    return !!merchant?.canImport && ids.length>0 && ids.every(id=>this.capturedPayments().some(p=>p.id===id))
      && new Set(this.capturedPayments().filter(p=>ids.includes(p.id)).map(p=>p.provider || 'UNKNOWN')).size===1
      && Number.isSafeInteger(gross) && fee!==null && fee>=0 && fee<gross && this.computedNetMinor()>0
      && this.validReference(this.bankReference) && this.validReference(this.externalId);
  }
  submitImport() {
    if(this.busy()) return;
    if(!this.savedImport()) {
      if(!this.canImport()) {this.error.set('Select captured payments from one merchant, currency and provider, enter a valid report and bank reference, and keep provider fees below the captured gross.');return;}
      const chosen=this.capturedPayments().filter(p=>this.selectedPayments().includes(p.id));
      const source: 'SIMULATOR'|'PROVIDER'=chosen.every(p=>p.provider==='SIMULATOR')?'SIMULATOR':'PROVIDER';
      if(chosen.some(p=>(p.provider==='SIMULATOR') !== (source==='SIMULATOR'))) {this.error.set('A report must contain captures from one provider source.');return;}
      const fee=this.parseMinor(this.feeAmount)!;
      const body:SettlementImport={merchantId:this.merchantId(),externalId:this.externalId,source,
        grossMinor:this.selectedGrossMinor(),feeMinor:fee,netMinor:this.selectedGrossMinor()-fee,
        bankReference:this.bankReference,lines:chosen.map(p=>({intentId:p.id,amountMinor:p.amountMinor})).sort((a,b)=>a.intentId.localeCompare(b.intentId))};
      const saved={merchantId:this.merchantId(),body};
      if(!this.persist('import',saved)) return;
      this.savedImport.set(saved);
    }
    const saved=this.savedImport()!;this.busy.set(true);this.error.set('');this.message.set('');
    this.api.importSettlement(saved.body).pipe(takeUntil(this.destroyed)).subscribe({
      next: result=>{this.busy.set(false);this.message.set(`Report ${result.externalId} was recorded with status ${result.state}. Refresh to inspect the finance record.`);this.clearSaved('import');this.savedImport.set(null);this.selectedPayments.set([]);this.externalId=crypto.randomUUID();this.refresh();},
      error:()=>{this.busy.set(false);this.error.set('The report import outcome is unconfirmed. Recover the exact saved reference and payload; do not edit it or create a replacement.');}
    });
  }
  prepareBankCheck(report: SettlementReport) {
    if(this.busy() || report.merchantId!==this.merchantId() || !report.canConfirm || report.status==='MATCHED') return;
    this.confirmReport.set(report);this.confirmationReference=report.bankReference;this.confirmationAmount=this.formatMinor(report.netMinor);this.evidenceHash='';
    this.message.set('Check the bank statement independently, then enter its SHA-256 digest and exact credit.');this.error.set('');
  }
  canConfirm() {
    const report=this.confirmReport();
    return !!report?.canConfirm && report.status!=='MATCHED' && !!this.confirmationReference.trim()
      && this.validReference(this.confirmationReference) && this.parseMinor(this.confirmationAmount)===report.netMinor
      && /^[a-fA-F0-9]{64}$/.test(this.evidenceHash.trim());
  }
  submitBankCheck() {
    if(this.busy()) return;
    if(!this.savedBankCheck()) {
      if(!this.canConfirm()) {this.error.set('Enter the report’s exact net credit, matching bank reference, and the 64-character SHA-256 digest of the independently checked statement.');return;}
      const report=this.confirmReport()!;
      const saved:SavedBankCheck={reportId:report.id,bankReference:this.confirmationReference,amountMinor:this.parseMinor(this.confirmationAmount)!,evidenceHash:this.evidenceHash.trim().toLowerCase()};
      if(!this.persist('bank',saved)) return;
      this.savedBankCheck.set(saved);
    }
    const saved=this.savedBankCheck()!;this.busy.set(true);this.error.set('');this.message.set('');
    this.api.confirmBankCredit(saved.reportId,{bankReference:saved.bankReference,amountMinor:saved.amountMinor,evidenceHash:saved.evidenceHash})
      .pipe(takeUntil(this.destroyed)).subscribe({
        next:result=>{this.busy.set(false);this.message.set(`Bank evidence recorded with status ${result.status}. Refresh to inspect the report.`);this.clearSaved('bank');this.savedBankCheck.set(null);this.confirmReport.set(null);this.refresh();},
        error:()=>{this.busy.set(false);this.error.set('The bank-check outcome is unconfirmed. Recover the exact saved confirmation or refresh the report status.');}
      });
  }
  refresh() {this.loadReports();this.loadPayments();}
  private loadBase() {
    this.loading.set(true);this.error.set('');
    this.api.reconciliationMerchants().pipe(takeUntil(this.destroyed)).subscribe({
      next: rows=>{this.merchants.set(rows);this.loading.set(false);const saved=this.savedImport();
        const preferred=saved?.merchantId || rows.find(m=>m.canImport)?.id || rows[0]?.id || '';
        if(preferred && rows.some(m=>m.id===preferred)){this.merchantId.set(preferred);this.loadPayments();this.loadReports();}},
      error:failure=>{this.loading.set(false);this.error.set(failure.status===403?'Merchant finance or reviewer access is required for reconciliation.':'Merchant assignments could not be loaded. Refresh before recording report evidence.');}
    });
  }
  private loadPayments() {
    this.api.financePayments().pipe(takeUntil(this.destroyed)).subscribe({
      next:rows=>{this.payments.set(rows);this.selectedPayments.update(ids=>ids.filter(id=>rows.some(p=>p.id===id&&p.merchantId===this.merchantId()&&p.status==='CAPTURED')));
        const savedCurrency=rows.find(p=>this.selectedPayments().includes(p.id))?.currency;
        if(savedCurrency && this.reportCurrencies().includes(savedCurrency)) this.reportCurrency.set(savedCurrency);
        else if(!this.reportCurrencies().includes(this.reportCurrency())) this.reportCurrency.set(this.reportCurrencies()[0] || 'USD');},
      error:()=>this.error.set('Captured payment evidence could not be loaded. No report can be prepared until it is available.')
    });
  }
  private loadReports() {
    this.api.settlements().pipe(takeUntil(this.destroyed)).subscribe({
      next:rows=>{const scoped=rows.filter(r=>r.merchantId===this.merchantId());this.reports.set(scoped);
        const pending=this.savedImport();if(pending && pending.merchantId===this.merchantId() && scoped.some(r=>r.externalId===pending.body.externalId&&r.source===pending.body.source&&r.grossMinor===pending.body.grossMinor&&r.feeMinor===pending.body.feeMinor&&r.netMinor===pending.body.netMinor&&r.bankReference===pending.body.bankReference&&JSON.stringify([...r.paymentIds].sort())===JSON.stringify(pending.body.lines.map(l=>l.intentId).sort()))){this.savedImport.set(null);this.clearSaved('import');this.message.set('The saved report was found in the server record after the interrupted response.');}
        const check=this.savedBankCheck();if(check && scoped.some(r=>r.id===check.reportId&&r.status==='MATCHED')){this.savedBankCheck.set(null);this.clearSaved('bank');this.message.set('The saved bank check is recorded as MATCHED.');}
        const current=this.confirmReport();if(current){const refreshed=scoped.find(r=>r.id===current.id);this.confirmReport.set(refreshed?.canConfirm&&refreshed.status!=='MATCHED'?refreshed:null);}
      },error:()=>this.error.set('Settlement reports could not be refreshed. Existing evidence remains saved; no outcome is inferred.')
    });
  }
  private parseMinor(value: string): number|null {const match=/^(0|[1-9]\d{0,9})(?:\.(\d{1,2}))?$/.exec(value.trim());if(!match)return null;const n=Number(match[1])*100+Number((match[2]||'').padEnd(2,'0'));return Number.isSafeInteger(n)?n:null;}
  private validReference(value:string) {return value.length>0&&value.length<=150&&value===value.trim()&&![...value].some(ch=>/[\u0000-\u001f\u007f-\u009f]/.test(ch));}
  private formatMinor(value: number) {return `${Math.floor(value/100)}.${String(value%100).padStart(2,'0')}`;}
  private persist(name:'import'|'bank',value:SavedImport|SavedBankCheck):boolean {
    try {const state=this.readStorage();if(name==='import')state.pendingImport=value as SavedImport;else state.pendingBank=value as SavedBankCheck;sessionStorage.setItem(this.storageKey,JSON.stringify(state));return true;}
    catch {this.error.set('Browser session storage is unavailable. Preserve this reference and do not continue with an untracked request.');return false;}
  }
  private clearSaved(name:'import'|'bank') {try{const state=this.readStorage();delete (state as any)[name==='import'?'pendingImport':'pendingBank'];sessionStorage.setItem(this.storageKey,JSON.stringify(state));}catch{this.error.set('The server response was received, but this browser could not clear its recovery record. Refresh before another action.');}}
  private readStorage():{pendingImport?:SavedImport;pendingBank?:SavedBankCheck} {const raw=sessionStorage.getItem(this.storageKey);if(!raw)return {};const value=JSON.parse(raw);if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('invalid local state');return value;}
  private restore() {try{const value=this.readStorage();if(value.pendingImport?.body?.merchantId&&value.pendingImport.body.externalId){this.savedImport.set(value.pendingImport);this.merchantId.set(value.pendingImport.merchantId);this.externalId=value.pendingImport.body.externalId;this.bankReference=value.pendingImport.body.bankReference;this.feeAmount=this.formatMinor(value.pendingImport.body.feeMinor);this.selectedPayments.set(value.pendingImport.body.lines.map(l=>l.intentId));}if(value.pendingBank?.reportId){this.savedBankCheck.set(value.pendingBank);this.confirmationReference=value.pendingBank.bankReference;this.confirmationAmount=this.formatMinor(value.pendingBank.amountMinor);this.evidenceHash=value.pendingBank.evidenceHash;}}catch{this.error.set('Saved reconciliation recovery data is unreadable. Do not create a replacement report or bank check; contact finance support.');}}
}

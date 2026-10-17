import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { environment } from '../../environments/environment';
import { AuthService } from '../shared/auth.service';
import { BillingApi, FinanceMerchant, FinancePayment, SettlementReport } from './billing-api';
import { ReconciliationComponent } from './reconciliation';

describe('Settlement reconciliation',()=>{
  const base=`${environment.apiUrl}/billing/finance`;
  const merchant:FinanceMerchant={id:'merchant-a',name:'Northstar HVAC',canImport:true,canConfirm:false};
  const payment:FinancePayment={id:'payment-a',accountId:'account-a',merchantId:'merchant-a',accountName:'Harbor',amountMinor:12525,
    refundedMinor:0,availableRefundMinor:12525,currency:'USD',status:'CAPTURED',method:'CARD',createdAt:'2026-10-17T00:00:00Z',canRequestRefund:true,provider:'SIMULATOR'};
  let component:ReconciliationComponent;let http:HttpTestingController;
  beforeEach(()=>{
    sessionStorage.clear();
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:AuthService,useValue:{user:signal({username:'finance-user',userId:'user-a'})}}]});
    component=TestBed.runInInjectionContext(()=>new ReconciliationComponent());http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>{component.ngOnDestroy();http.verify();sessionStorage.clear();});
  function initialize(scope=merchant,reports:SettlementReport[] = []) {
    component.ngOnInit();http.expectOne(`${base}/merchants`).flush([scope]);
    http.expectOne(`${base}/payments`).flush([payment]);http.expectOne(`${base}/settlements`).flush(reports);
  }
  function prepare() {
    initialize();component.togglePayment(payment.id,true);component.bankReference='BANK-009';component.feeAmount='1.25';
    expect(component.canImport()).toBe(true);
  }
  const report:SettlementReport={id:'report-a',merchantId:'merchant-a',currency:'USD',externalId:'provider-batch-1',source:'SIMULATOR',
    grossMinor:12525,feeMinor:125,netMinor:12400,bankReference:'BANK-009',status:'MATCHED',createdAt:'2026-10-17T00:00:00Z',canConfirm:false,paymentIds:['payment-a']};

  it('persists and reuses the exact report evidence after an interrupted import',()=>{
    prepare();component.submitImport();
    const first=http.expectOne(`${base}/reconciliation/import`);const body=first.request.body;first.error(new ProgressEvent('error'));
    component.bankReference='changed';component.feeAmount='99.00';component.externalId='another-report';
    component.submitImport();const retry=http.expectOne(`${base}/reconciliation/import`);
    expect(retry.request.body).toEqual(body);expect(body).toEqual({merchantId:'merchant-a',externalId:body.externalId,source:'SIMULATOR',grossMinor:12525,feeMinor:125,netMinor:12400,bankReference:'BANK-009',lines:[{intentId:'payment-a',amountMinor:12525}]});
    retry.flush({id:'report-a',externalId:body.externalId,source:'SIMULATOR',state:'MATCHED'});
    http.expectOne(`${base}/settlements`).flush([report]);http.expectOne(`${base}/payments`).flush([payment]);
    expect(component.savedImport()).toBeNull();expect(component.reports()[0].status).toBe('MATCHED');
  });

  it('restores the same import after a browser component reload',()=>{
    prepare();component.submitImport();const first=http.expectOne(`${base}/reconciliation/import`);const saved=first.request.body;first.flush({}, {status:503,statusText:'Unavailable'});
    component.ngOnDestroy();component=TestBed.runInInjectionContext(()=>new ReconciliationComponent());
    component.ngOnInit();http.expectOne(`${base}/merchants`).flush([merchant]);
    http.expectOne(`${base}/payments`).flush([payment]);http.expectOne(`${base}/settlements`).flush([]);
    component.submitImport();const retry=http.expectOne(`${base}/reconciliation/import`);expect(retry.request.body).toEqual(saved);retry.flush({id:'report-a',externalId:saved.externalId,source:'SIMULATOR',state:'MATCHED'});
    http.expectOne(`${base}/settlements`).flush([report]);http.expectOne(`${base}/payments`).flush([payment]);
  });

  it('requires different reviewer assignment and exact statement digest before bank confirmation',()=>{
    const checker={...merchant,canImport:false,canConfirm:true};
    const outstanding={...report,status:'REPORTED',canConfirm:true};initialize(checker,[outstanding]);
    component.prepareBankCheck(outstanding);component.evidenceHash='f'.repeat(64);
    expect(component.canConfirm()).toBe(true);component.submitBankCheck();
    const first=http.expectOne(`${base}/settlements/${outstanding.id}/bank-confirmation`);const body=first.request.body;
    first.error(new ProgressEvent('error'));component.confirmationAmount='1.00';component.evidenceHash='0'.repeat(64);
    component.submitBankCheck();const retry=http.expectOne(`${base}/settlements/${outstanding.id}/bank-confirmation`);expect(retry.request.body).toEqual(body);
    retry.flush({...outstanding,status:'MATCHED',canConfirm:false});http.expectOne(`${base}/settlements`).flush([{...outstanding,status:'MATCHED',canConfirm:false}]);http.expectOne(`${base}/payments`).flush([payment]);
    expect(component.savedBankCheck()).toBeNull();
  });

  it('prevents importing an amount that is not an exact positive-cent fee below the captured gross',()=>{
    initialize();component.togglePayment(payment.id,true);component.bankReference='BANK-009';component.feeAmount='125.251';
    expect(component.canImport()).toBe(false);component.feeAmount='125.25';expect(component.canImport()).toBe(false);
    component.feeAmount='1.25';expect(component.canImport()).toBe(true);
  });
});

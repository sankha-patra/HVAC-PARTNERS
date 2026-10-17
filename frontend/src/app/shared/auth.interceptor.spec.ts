import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

describe('Billing authorization boundary', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: AuthService, useValue: { ready: () => true, hasRole: () => false, logout: () => {} } }] });
    http = TestBed.inject(HttpTestingController); client = TestBed.inject(HttpClient);
  });
  afterEach(() => http.verify());
  it('allows a non-ADMIN JWT to reach server-authorized billing finance endpoints', () => {
    for (const url of ['/api/v1/billing/finance/refunds/refund-id/approve', '/api/v1/billing/finance/reconciliation/reports/report-id/review', '/api/v1/billing/finance/settlements']) {
      client.post(url, {}).subscribe();
      const request = http.expectOne(url);
      expect(request.request.withCredentials).toBe(true);
      request.flush({ status: 'SERVER_DECIDES' });
    }
  });
  it('retains legacy admin gating outside the billing API', () => {
    let status = 0;
    client.post('/api/v1/reconciliation/replay', {}).subscribe({ error: error => status = error.status });
    expect(status).toBe(403);
    http.expectNone('/api/v1/reconciliation/replay');
  });
});

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from './auth.service';
import { staffGuard } from './auth.guard';
import { environment } from '../../environments/environment';

describe('Merchant workspace navigation',()=>{
  const base=environment.apiUrl;
  const buyer={userId:'buyer',username:'buyer',email:'buyer@example.test',displayName:'Buyer',role:'USER'};
  const approver={...buyer,userId:'approver'};
  let auth:AuthService,http:HttpTestingController;
  beforeEach(()=>{
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),provideRouter([])]});
    auth=TestBed.inject(AuthService);http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>http.verify());
  it('keeps buyer navigation free of staff tools and supports assigned approvers with USER tokens',()=>{
    auth.bootstrap().subscribe();http.expectOne(`${base}/auth/me`).flush(buyer);
    http.expectOne(`${base}/billing/access`).flush({staffWorkspace:false});expect(auth.staffWorkspace()).toBe(false);
    auth.login('approver','fictional').subscribe();http.expectOne(`${base}/auth/login`).flush({user:approver});
    http.expectOne(`${base}/billing/access`).flush({staffWorkspace:true});expect(auth.staffWorkspace()).toBe(true);
  });
  it('cannot restore staff navigation from a delayed result belonging to the previous user',()=>{
    auth.bootstrap().subscribe();http.expectOne(`${base}/auth/me`).flush(approver);
    const previous=http.expectOne(`${base}/billing/access`);
    auth.login('buyer','fictional').subscribe();http.expectOne(`${base}/auth/login`).flush({user:buyer});
    http.expectOne(`${base}/billing/access`).flush({staffWorkspace:false});previous.flush({staffWorkspace:true});
    expect(auth.staffWorkspace()).toBe(false);
  });
  it('redirects a buyer away from a directly entered finance URL',()=>{
    const result=TestBed.runInInjectionContext(()=>staffGuard({} as never,{} as never)) as Observable<unknown>;
    let destination:unknown;result.subscribe(value=>destination=value);
    http.expectOne(`${base}/billing/access`).flush({staffWorkspace:false});
    expect(TestBed.inject(Router).serializeUrl(destination as never)).toBe('/shop');
  });
  it('fails closed when the workspace lookup fails',()=>{
    auth.bootstrap().subscribe();http.expectOne(`${base}/auth/me`).flush(approver);
    http.expectOne(`${base}/billing/access`).flush({}, {status:503,statusText:'Unavailable'});
    expect(auth.staffWorkspace()).toBe(false);
  });
  it('cannot overwrite a newer login with an older session bootstrap response',()=>{
    auth.bootstrap().subscribe();const old=http.expectOne(`${base}/auth/me`);
    auth.login('buyer','fictional').subscribe();http.expectOne(`${base}/auth/login`).flush({user:buyer});
    http.expectOne(`${base}/billing/access`).flush({staffWorkspace:false});
    old.flush(approver);
    expect(auth.user()?.userId).toBe('buyer');expect(auth.staffWorkspace()).toBe(false);
    http.expectNone(`${base}/billing/access`);
  });
  it('cannot restore a signed-out user from an older session bootstrap response',()=>{
    auth.bootstrap().subscribe();const old=http.expectOne(`${base}/auth/me`);
    const router=TestBed.inject(Router);vi.spyOn(router,'navigateByUrl').mockResolvedValue(true);
    auth.logout();http.expectOne(`${base}/auth/logout`).flush({});old.flush(approver);
    expect(auth.user()).toBeNull();expect(auth.staffWorkspace()).toBe(false);
    http.expectNone(`${base}/billing/access`);
  });
});

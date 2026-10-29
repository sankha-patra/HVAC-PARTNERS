import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NavigationEnd, Router } from '@angular/router';
import { signal } from '@angular/core';
import { of, Subject } from 'rxjs';
import { App } from './app';
import { AuthService } from './shared/auth.service';
import { MessageService } from './shared/message.service';
import { routes } from './app.routes';

describe('Invoice application shell',()=>{
  it('updates navigation after login without polling unrelated APIs',()=>{
    const events=new Subject<NavigationEnd>();
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),
      {provide:AuthService,useValue:{user:signal({userId:'payer'}),bootstrap:()=>of(true),isLoggedIn:()=>true}},
      {provide:Router,useValue:{events,url:'/login'}},
      {provide:MessageService,useValue:{message:signal(null)}}]});
    const app=TestBed.runInInjectionContext(()=>new App());
    const http=TestBed.inject(HttpTestingController);
    app.ngOnInit();expect(app.showChrome()).toBe(false);
    events.next(new NavigationEnd(1,'/invoices','/invoices'));expect(app.showChrome()).toBe(true);
    events.next(new NavigationEnd(2,'/login','/login'));expect(app.showChrome()).toBe(false);
    http.verify();app.ngOnDestroy();
  });
  it('contains the authenticated shop and the existing billing routes',()=>{
    expect(routes.map(route=>route.path)).toEqual(['login','','shop','orders','invoices','finance','reconciliation','**']);
    expect(routes.find(route=>route.path==='shop')?.canActivate).toHaveLength(1);
    expect(routes.find(route=>route.path==='')?.redirectTo).toBe('/shop');
  });
});

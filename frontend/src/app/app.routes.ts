import { Routes } from '@angular/router';
import { LoginComponent } from './login/login';
import { authGuard, guestGuard, staffGuard } from './shared/auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: '', redirectTo: '/shop', pathMatch: 'full' },
  { path: 'shop', loadComponent: () => import('./commerce/shop').then(m => m.ShopComponent), canActivate: [authGuard] },
  { path: 'orders', loadComponent: () => import('./commerce/shop').then(m => m.ShopComponent), data: { view: 'orders' }, canActivate: [authGuard] },
  { path: 'invoices', loadComponent: () => import('./billing/invoices').then(m => m.InvoicesComponent), canActivate: [authGuard,staffGuard] },
  { path: 'finance', loadComponent: () => import('./billing/finance').then(m => m.FinanceComponent), canActivate: [authGuard,staffGuard] },
  { path: 'reconciliation', loadComponent: () => import('./billing/reconciliation').then(m => m.ReconciliationComponent), canActivate: [authGuard,staffGuard] },
  { path: '**', redirectTo: '/shop' },
];

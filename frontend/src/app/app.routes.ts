import { Routes } from '@angular/router';
import { LoginComponent } from './login/login';
import { authGuard, guestGuard } from './shared/auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: '', redirectTo: '/invoices', pathMatch: 'full' },
  { path: 'invoices', loadComponent: () => import('./billing/invoices').then(m => m.InvoicesComponent), canActivate: [authGuard] },
  { path: 'finance', loadComponent: () => import('./billing/finance').then(m => m.FinanceComponent), canActivate: [authGuard] },
  { path: 'reconciliation', loadComponent: () => import('./billing/reconciliation').then(m => m.ReconciliationComponent), canActivate: [authGuard] },
  { path: '**', redirectTo: '/invoices' },
];

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

function isAdminOnlyRequest(method: string, url: string): boolean {
  const path = new URL(url, 'https://local.invalid').pathname;
  // Billing uses account membership and merchant permissions from its database.
  // A JWT's legacy application role cannot decide finance authorization.
  if (path === '/api/v1/billing' || path.startsWith('/api/v1/billing/')) return false;
  const m = method.toUpperCase();
  if ((m === 'POST' || m === 'PUT') && url.includes('/products')) {
    return true;
  }
  if (m === 'PUT' && url.includes('/orders')) {
    return true;
  }
  return (
    url.includes('/settlements') ||
    url.includes('/reconciliation') ||
    url.includes('/load-test') ||
    url.includes('/experiments')
  );
}

/**
 * Sends the httpOnly JWT cookie, blocks admin-only calls in the UI when the
 * session is a buyer, and returns to login on 401. The API still enforces roles.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const withCreds = req.clone({ withCredentials: true });

  if (auth.ready() && isAdminOnlyRequest(withCreds.method, withCreds.url) && !auth.hasRole('ADMIN')) {
    return throwError(
      () =>
        new HttpErrorResponse({
          status: 403,
          statusText: 'Forbidden',
          url: withCreds.url,
          error: { message: 'Admin role required' }
        })
    );
  }

  return next(withCreds).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !withCreds.url.includes('/auth/')) {
        auth.logout();
      }
      return throwError(() => err);
    })
  );
};

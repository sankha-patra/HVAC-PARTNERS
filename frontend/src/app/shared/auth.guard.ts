import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map, catchError, of } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { safeReturnUrl } from './return-url';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const signIn = () => router.createUrlTree(['/login'], { queryParams: { returnUrl: safeReturnUrl(state.url) } });

  if (auth.ready() && auth.isLoggedIn()) {
    return true;
  }
  if (auth.ready() && !auth.isLoggedIn()) {
    return signIn();
  }

  return auth.bootstrap().pipe(
    map((ok) => (ok ? true : signIn()))
  );
};

export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.ready() && auth.isLoggedIn()) {
    return router.createUrlTree(['/shop']);
  }
  if (auth.ready() && !auth.isLoggedIn()) {
    return true;
  }

  return auth.bootstrap().pipe(
    map((ok) => (ok ? router.createUrlTree(['/shop']) : true))
  );
};

/** Uses assigned merchant access, including approvers with a legacy USER role. */
export const staffGuard: CanActivateFn = () => {
  const http=inject(HttpClient),router=inject(Router);
  return http.get<{staffWorkspace:boolean}>(`${environment.apiUrl}/billing/access`).pipe(
    map(access=>access.staffWorkspace===true ? true : router.createUrlTree(['/shop'])),
    catchError(()=>of(router.createUrlTree(['/shop'])))
  );
};

/** UI convenience only — Spring Security is the real role check. */
export const roleGuard = (role: string): CanActivateFn => () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const allow = () => (auth.hasRole(role) ? true : router.createUrlTree(['/invoices']));

  if (auth.ready() && auth.isLoggedIn()) {
    return allow();
  }
  if (auth.ready() && !auth.isLoggedIn()) {
    return router.createUrlTree(['/login']);
  }

  return auth.bootstrap().pipe(
    map((ok) => (ok ? allow() : router.createUrlTree(['/login'])))
  );
};

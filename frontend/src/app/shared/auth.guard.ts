import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.ready() && auth.isLoggedIn()) {
    return true;
  }
  if (auth.ready() && !auth.isLoggedIn()) {
    return router.createUrlTree(['/login']);
  }

  return auth.bootstrap().pipe(
    map((ok) => (ok ? true : router.createUrlTree(['/login'])))
  );
};

export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.ready() && auth.isLoggedIn()) {
    return router.createUrlTree(['/invoices']);
  }
  if (auth.ready() && !auth.isLoggedIn()) {
    return true;
  }

  return auth.bootstrap().pipe(
    map((ok) => (ok ? router.createUrlTree(['/invoices']) : true))
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

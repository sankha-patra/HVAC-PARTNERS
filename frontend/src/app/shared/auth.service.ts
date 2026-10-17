import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { LoginResponse, UserDto } from './models';

/**
 * JWT is never written to localStorage/sessionStorage.
 * Backend sets an httpOnly cookie; browser sends it automatically withCredentials.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly apiUrl = environment.apiUrl;

  readonly user = signal<UserDto | null>(null);
  readonly ready = signal(false);

  /** True only right after a successful login in this tab session (for one-time greeting). */
  private greetPending = false;

  bootstrap(): Observable<boolean> {
    return this.http.get<UserDto>(`${this.apiUrl}/auth/me`, { withCredentials: true }).pipe(
      tap((u) => {
        this.user.set(u);
        this.ready.set(true);
      }),
      map(() => true),
      catchError(() => {
        this.user.set(null);
        this.ready.set(true);
        return of(false);
      })
    );
  }

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(
        `${this.apiUrl}/auth/login`,
        { username, password },
        { withCredentials: true }
      )
      .pipe(
        tap((res) => {
          this.user.set(res.user);
          this.greetPending = true;
          this.ready.set(true);
        })
      );
  }

  logout(): void {
    this.http.post(`${this.apiUrl}/auth/logout`, {}, { withCredentials: true }).subscribe({
      next: () => this.afterLogout(),
      error: () => this.afterLogout()
    });
  }

  consumeGreetingFlag(): boolean {
    if (!this.greetPending) {
      return false;
    }
    this.greetPending = false;
    return true;
  }

  isLoggedIn(): boolean {
    return !!this.user();
  }

  hasRole(role: string): boolean {
    return this.user()?.role?.toUpperCase() === role.toUpperCase();
  }

  email(): string | null {
    return this.user()?.email ?? null;
  }

  private afterLogout() {
    this.user.set(null);
    this.greetPending = false;
    this.router.navigateByUrl('/login');
  }
}

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
  readonly staffWorkspace = signal(false);

  /** True only right after a successful login in this tab session (for one-time greeting). */
  private greetPending = false;
  private sessionGeneration = 0;

  bootstrap(): Observable<boolean> {
    const generation = this.sessionGeneration;
    return this.http.get<UserDto>(`${this.apiUrl}/auth/me`, { withCredentials: true }).pipe(
      tap((u) => {
        if (generation !== this.sessionGeneration) return;
        this.user.set(u);
        this.loadWorkspace(u);
        this.ready.set(true);
      }),
      map(() => this.isLoggedIn()),
      catchError(() => {
        if (generation !== this.sessionGeneration) return of(this.isLoggedIn());
        this.user.set(null);
        this.staffWorkspace.set(false);
        this.ready.set(true);
        return of(false);
      })
    );
  }

  login(username: string, password: string): Observable<LoginResponse> {
    const generation = ++this.sessionGeneration;
    return this.http
      .post<LoginResponse>(
        `${this.apiUrl}/auth/login`,
        { username, password },
        { withCredentials: true }
      )
      .pipe(
        tap((res) => {
          if (generation !== this.sessionGeneration) return;
          this.user.set(res.user);
          this.loadWorkspace(res.user);
          this.greetPending = true;
          this.ready.set(true);
        })
      );
  }

  logout(): void {
    const generation = ++this.sessionGeneration;
    this.http.post(`${this.apiUrl}/auth/logout`, {}, { withCredentials: true }).subscribe({
      next: () => { if (generation === this.sessionGeneration) this.afterLogout(); },
      error: () => { if (generation === this.sessionGeneration) this.afterLogout(); }
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
    this.staffWorkspace.set(false);
    this.greetPending = false;
    this.router.navigateByUrl('/login');
  }

  private loadWorkspace(user: UserDto) {
    this.staffWorkspace.set(false);
    this.http.get<{staffWorkspace: boolean}>(`${this.apiUrl}/billing/access`, {withCredentials:true})
      .pipe(catchError(()=>of({staffWorkspace:false}))).subscribe(access=>{
        if(this.user()===user)this.staffWorkspace.set(access.staffWorkspace===true);
      });
  }
}

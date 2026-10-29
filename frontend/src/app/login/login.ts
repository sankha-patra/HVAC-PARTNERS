import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../shared/auth.service';
import { MessageService } from '../shared/message.service';
import { safeReturnUrl } from '../shared/return-url';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly messages = inject(MessageService);

  username = 'demo';
  password = 'demo123';
  loading = false;
  error = '';

  submit() {
    this.error = '';
    this.loading = true;
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigateByUrl(safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')));
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.status === 401 ? 'The username or password is incorrect.' : 'Sign-in is unavailable. Please try again.';
        this.messages.show({
          title: 'Login failed',
          body: this.error,
          tone: 'error',
          actionLabel: 'OK'
        });
      }
    });
  }
}

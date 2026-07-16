import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="login-wrapper">
      <div class="login-card card-custom">
        @if (!emailSent()) {
          <div class="login-header">
            <h1>Esqueci a Senha</h1>
            <p class="text-muted-custom">Informe seu username para recuperar o acesso</p>
          </div>

          <form (ngSubmit)="onSubmit()" class="login-form">
            <div class="form-group">
              <label for="username">Username</label>
              <input id="username" type="text" [(ngModel)]="username" name="username"
                     placeholder="seu.usuario" autocomplete="username" required autofocus>
            </div>

            @if (errorMsg()) {
              <div class="msg-error">{{ errorMsg() }}</div>
            }

            <button type="submit" class="btn-primary-custom btn-login" [disabled]="loading()">
              {{ loading() ? 'Enviando...' : 'Enviar link de recuperação' }}
            </button>

            <div class="back-link">
              <a routerLink="/login">Voltar ao login</a>
            </div>
          </form>
        } @else {
          <div class="login-header">
            <div class="success-icon">&#10003;</div>
            <h1>E-mail enviado!</h1>
          </div>
          <div class="success-body">
            <p>Enviamos um link de recuperação de senha para:</p>
            <p class="email-masked">{{ maskedEmail() }}</p>
            <p class="text-muted-custom">Verifique sua caixa de entrada e spam. O link expira em 30 minutos.</p>
            <div class="back-link" style="margin-top: 24px;">
              <a routerLink="/login">Voltar ao login</a>
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .login-wrapper {
      display: grid;
      place-items: center;
      min-height: 100vh;
      padding: 16px;
    }
    .login-card {
      width: 100%;
      max-width: 420px;
    }
    .login-header {
      text-align: center;
      margin-bottom: 24px;
    }
    .login-header h1 {
      font-size: clamp(18px, 2.4vw, 22px);
      margin: 0 0 4px;
      font-weight: 700;
    }
    .login-header p { margin: 0; font-size: 0.875rem; }
    .login-form {
      display: grid;
      gap: 14px;
    }
    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .form-group label {
      font-size: 0.9375rem;
      font-weight: 500;
      color: var(--text);
    }
    .form-group input { width: 100%; }
    .btn-login { width: 100%; margin-top: 8px; }
    .msg-error {
      background: #fef2f2;
      color: #b91c1c;
      border: 1px solid #fca5a5;
      border-radius: 8px;
      padding: 10px 14px;
      font-size: 0.875rem;
      text-align: center;
    }
    .back-link {
      text-align: center;
      margin-top: 12px;
    }
    .back-link a {
      color: var(--primary);
      text-decoration: none;
      font-size: 0.875rem;
    }
    .back-link a:hover { text-decoration: underline; }
    .success-icon {
      width: 56px;
      height: 56px;
      background: #009739;
      color: #fff;
      border-radius: 50%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 28px;
      margin-bottom: 12px;
    }
    .success-body {
      text-align: center;
    }
    .success-body p {
      margin: 8px 0;
      font-size: 0.9375rem;
    }
    .email-masked {
      font-weight: 700;
      font-size: 1.1rem !important;
      color: var(--primary);
    }
    .text-muted-custom { color: var(--muted); }
  `],
})
export class ForgotPasswordComponent {
  private api = inject(ApiService);
  private router = inject(Router);

  username = '';
  loading = signal(false);
  errorMsg = signal('');
  emailSent = signal(false);
  maskedEmail = signal('');

  onSubmit(): void {
    if (!this.username.trim()) {
      this.errorMsg.set('Informe seu username.');
      return;
    }

    this.loading.set(true);
    this.errorMsg.set('');

    this.api.post<{ ok: boolean; email_masked?: string }>('/api/password/forgot', {
      username: this.username.trim(),
    }).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.email_masked) {
          this.maskedEmail.set(res.email_masked);
          this.emailSent.set(true);
        } else {
          this.errorMsg.set('Username não encontrado.');
        }
      },
      error: () => {
        this.loading.set(false);
        this.errorMsg.set('Erro ao processar. Tente novamente.');
      },
    });
  }
}

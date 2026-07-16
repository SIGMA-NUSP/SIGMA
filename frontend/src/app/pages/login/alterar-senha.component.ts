import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { homeRouteForRole } from '../../core/helpers/auth.helpers';

@Component({
  selector: 'app-alterar-senha',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="login-wrapper">
      <div class="login-card card-custom">
        @if (sucesso()) {
          <div class="login-header">
            <div class="success-icon">&#10003;</div>
            <h1>Senha alterada!</h1>
          </div>
          <div class="success-body">
            <p>Sua senha foi atualizada com sucesso.</p>
            <p class="text-muted-custom">Redirecionando para o sistema...</p>
          </div>
        } @else {
          <div class="login-header">
            <h1>Defina sua nova senha</h1>
            <p class="text-muted-custom">
              Olá, <strong>{{ nome() }}</strong>. Como este é seu primeiro acesso,
              é necessário definir uma nova senha antes de continuar.
            </p>
          </div>

          <form (ngSubmit)="onSubmit()" class="login-form">
            <div class="form-group">
              <label for="novaSenha">Nova senha</label>
              <div class="password-wrapper">
                <input id="novaSenha" [type]="showPassword() ? 'text' : 'password'"
                       [(ngModel)]="novaSenha" name="novaSenha"
                       placeholder="Mínimo 6 caracteres" autocomplete="new-password" required autofocus>
                <button type="button" class="toggle-password" (click)="showPassword.set(!showPassword())">
                  <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    @if (showPassword()) {
                      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>
                      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
                      <line x1="1" y1="1" x2="23" y2="23"/>
                    } @else {
                      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                      <circle cx="12" cy="12" r="3"/>
                    }
                  </svg>
                </button>
              </div>
            </div>

            <div class="form-group">
              <label for="confirmarSenha">Confirmar nova senha</label>
              <div class="password-wrapper">
                <input id="confirmarSenha" [type]="showConfirm() ? 'text' : 'password'"
                       [(ngModel)]="confirmarSenha" name="confirmarSenha"
                       placeholder="Repita a senha" autocomplete="new-password" required>
                <button type="button" class="toggle-password" (click)="showConfirm.set(!showConfirm())">
                  <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    @if (showConfirm()) {
                      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>
                      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
                      <line x1="1" y1="1" x2="23" y2="23"/>
                    } @else {
                      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                      <circle cx="12" cy="12" r="3"/>
                    }
                  </svg>
                </button>
              </div>
            </div>

            @if (errorMsg()) {
              <div class="msg-error">{{ errorMsg() }}</div>
            }

            <button type="submit" class="btn-primary-custom btn-login" [disabled]="loading()">
              {{ loading() ? 'Salvando...' : 'Salvar nova senha' }}
            </button>

            <div class="back-link">
              <a href="#" (click)="sair($event)">Sair sem trocar</a>
            </div>
          </form>
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
      max-width: 460px;
    }
    .login-header {
      text-align: center;
      margin-bottom: 24px;
    }
    .login-header h1 {
      font-size: clamp(18px, 2.4vw, 22px);
      margin: 0 0 8px;
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
    .password-wrapper { position: relative; }
    .password-wrapper input { width: 100%; padding-right: 40px; }
    .toggle-password {
      position: absolute;
      right: 10px;
      top: 50%;
      transform: translateY(-50%);
      background: none;
      border: none;
      cursor: pointer;
      font-size: 1rem;
      padding: 0;
    }
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
    .text-muted-custom { color: var(--muted); }
  `],
})
export class AlterarSenhaComponent {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private router = inject(Router);

  novaSenha = '';
  confirmarSenha = '';
  showPassword = signal(false);
  showConfirm = signal(false);
  loading = signal(false);
  errorMsg = signal('');
  sucesso = signal(false);

  nome = () => this.auth.user()?.nome || this.auth.user()?.name || this.auth.user()?.username || '';

  constructor() {
    if (!this.auth.isLoggedIn()) {
      this.router.navigate(['/login']);
      return;
    }
    if (!this.auth.senhaProvisoria()) {
      this.router.navigate([homeRouteForRole(this.auth.role())]);
    }
  }

  onSubmit(): void {
    if (!this.novaSenha || !this.confirmarSenha) {
      this.errorMsg.set('Preencha todos os campos.');
      return;
    }
    if (this.novaSenha.length < 6) {
      this.errorMsg.set('A senha deve ter no mínimo 6 caracteres.');
      return;
    }
    if (this.novaSenha !== this.confirmarSenha) {
      this.errorMsg.set('As senhas não coincidem.');
      return;
    }

    this.loading.set(true);
    this.errorMsg.set('');

    this.api.post<{ ok: boolean; message: string }>('/api/auth/change-password', {
      novaSenha: this.novaSenha,
    }).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.ok) {
          this.auth.clearSenhaProvisoria();
          this.sucesso.set(true);
          const role = this.auth.role();
          setTimeout(() => this.router.navigate([homeRouteForRole(role)]), 1500);
        } else {
          this.errorMsg.set(res.message || 'Erro ao trocar senha.');
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMsg.set(err.error?.message || 'Falha ao trocar senha.');
      },
    });
  }

  sair(ev: Event): void {
    ev.preventDefault();
    this.auth.logout();
  }
}

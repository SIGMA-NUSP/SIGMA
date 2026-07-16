import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { homeRouteForRole } from '../../core/helpers/auth.helpers';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="login-wrapper">
      <div class="login-card card-custom">
        <div class="login-header">
          <h1>Acessar o Sistema</h1>
          <p class="text-muted-custom">Informe suas credenciais</p>
        </div>

        <form (ngSubmit)="onLogin()" class="login-form">
          <div class="form-group">
            <label for="username">Login</label>
            <input id="username" type="text" [(ngModel)]="usuario" name="usuario"
                   placeholder="seu.usuario" autocomplete="username" required autofocus>
          </div>

          <div class="form-group">
            <label for="password">Senha</label>
            <div class="password-wrapper">
              <input id="password" [type]="showPassword() ? 'text' : 'password'"
                     [(ngModel)]="senha" name="senha" placeholder="********"
                     autocomplete="current-password" required>
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

          @if (errorMsg()) {
            <div class="login-error">{{ errorMsg() }}</div>
          }

          <button type="submit" class="btn-primary-custom btn-login" [disabled]="loading()">
            {{ loading() ? 'Entrando...' : 'Entrar' }}
          </button>

          <div class="forgot-link">
            <a routerLink="/forgot-password">Esqueci a senha</a>
          </div>
        </form>
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
    .password-wrapper {
      position: relative;
    }
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
    .login-error {
      background: #fef2f2;
      color: #b91c1c;
      border: 1px solid #fca5a5;
      border-radius: 8px;
      padding: 10px 14px;
      font-size: 0.875rem;
      text-align: center;
    }
    .forgot-link {
      text-align: center;
      margin-top: 4px;
    }
    .forgot-link a {
      color: var(--primary);
      text-decoration: none;
      font-size: 0.875rem;
    }
    .forgot-link a:hover { text-decoration: underline; }
    .text-muted-custom { color: var(--muted); }
  `],
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  usuario = '';
  senha = '';
  showPassword = signal(false);
  loading = signal(false);
  errorMsg = signal('');

  constructor() {
    if (this.auth.isLoggedIn()) {
      if (this.auth.senhaProvisoria()) {
        this.router.navigate(['/alterar-senha']);
      } else {
        this.router.navigate([homeRouteForRole(this.auth.role())]);
      }
    }
  }

  onLogin(): void {
    if (!this.usuario.trim() || !this.senha.trim()) {
      this.errorMsg.set('Preencha usuário e senha.');
      return;
    }

    this.loading.set(true);
    this.errorMsg.set('');

    this.auth.login(this.usuario.trim(), this.senha).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.token) {
          if (res.user?.senhaProvisoria) {
            this.router.navigate(['/alterar-senha']);
            return;
          }
          const role = res.role || res.user?.role || 'operador';
          this.router.navigate([homeRouteForRole(role)]);
        } else {
          this.errorMsg.set('Usuário ou senha incorretos.');
        }
      },
      error: () => {
        this.loading.set(false);
        this.errorMsg.set('Usuário ou senha incorretos.');
      },
    });
  }
}

import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="login-wrapper">
      <div class="login-card card-custom">
        @if (validating()) {
          <div class="login-header">
            <h1>Verificando link...</h1>
            <p class="text-muted-custom">Aguarde enquanto validamos seu token.</p>
          </div>
        } @else if (tokenInvalid()) {
          <div class="login-header">
            <div class="error-icon">&#10007;</div>
            <h1>Link inválido</h1>
          </div>
          <div class="success-body">
            <p>Este link de recuperação é inválido ou já expirou.</p>
            <p class="text-muted-custom">Solicite um novo link na página "Esqueci a Senha".</p>
            <div class="back-link" style="margin-top: 24px;">
              <a routerLink="/forgot-password">Solicitar novo link</a>
            </div>
          </div>
        } @else if (resetDone()) {
          <div class="login-header">
            <div class="success-icon">&#10003;</div>
            <h1>Senha redefinida!</h1>
          </div>
          <div class="success-body">
            <p>Sua senha foi alterada com sucesso.</p>
            <p class="text-muted-custom">Você já pode acessar o sistema com a nova senha.</p>
            <div class="back-link" style="margin-top: 24px;">
              <a routerLink="/login" class="btn-primary-custom btn-login" style="display:inline-block; text-decoration:none;">
                Ir para o Login
              </a>
            </div>
          </div>
        } @else {
          <div class="login-header">
            <h1>Nova Senha</h1>
            <p class="text-muted-custom">Defina sua nova senha de acesso</p>
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
              {{ loading() ? 'Salvando...' : 'Redefinir Senha' }}
            </button>

            <div class="back-link">
              <a routerLink="/login">Voltar ao login</a>
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
    .error-icon {
      width: 56px;
      height: 56px;
      background: #b91c1c;
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
export class ResetPasswordComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  token = '';
  novaSenha = '';
  confirmarSenha = '';
  showPassword = signal(false);
  showConfirm = signal(false);
  loading = signal(false);
  errorMsg = signal('');
  validating = signal(true);
  tokenInvalid = signal(false);
  resetDone = signal(false);

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') || '';
    if (!this.token) {
      this.validating.set(false);
      this.tokenInvalid.set(true);
      return;
    }

    this.api.get<{ ok: boolean; valid: boolean }>('/api/password/validate-token', { token: this.token })
      .subscribe({
        next: (res) => {
          this.validating.set(false);
          if (!res.valid) this.tokenInvalid.set(true);
        },
        error: () => {
          this.validating.set(false);
          this.tokenInvalid.set(true);
        },
      });
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

    this.api.post<{ ok: boolean; message: string }>('/api/password/reset', {
      token: this.token,
      novaSenha: this.novaSenha,
    }).subscribe({
      next: (res) => {
        this.loading.set(false);
        if (res.ok) {
          this.resetDone.set(true);
        } else {
          this.errorMsg.set(res.message || 'Erro ao redefinir senha.');
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMsg.set(err.error?.message || 'Token inválido ou expirado.');
      },
    });
  }
}

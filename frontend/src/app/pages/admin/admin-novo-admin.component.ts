import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-admin-novo-admin',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card-custom" style="max-width:600px; margin:0 auto">
      <h1>Cadastrar Novo Administrador</h1>
      <p class="muted">Os campos marcados com <strong>*</strong> são obrigatórios.</p>

      <form (ngSubmit)="onSubmit()">
        <div class="form-row"><label>Nome Completo *</label><input [(ngModel)]="nomeCompleto" name="nome_completo" required></div>
        <div class="form-row"><label>E-mail institucional *</label><input type="email" [(ngModel)]="email" name="email" required></div>
        <div class="form-row"><label>Nome de usuário *</label><input [(ngModel)]="username" name="username" required pattern="[a-zA-Z0-9._-]{3,}"></div>
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
          <div class="form-row"><label>Senha *</label><input type="password" [(ngModel)]="senha" name="senha" required minlength="6"></div>
          <div class="form-row"><label>Confirmar Senha *</label><input type="password" [(ngModel)]="confirmarSenha" name="confirmar_senha" required></div>
        </div>

        @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }
        @if (successMsg()) { <div class="success-box">{{ successMsg() }}</div> }

        <div style="display:flex; justify-content:space-between; margin-top:20px">
          <a routerLink="/admin" class="btn-secondary-custom">← Voltar</a>
          <button type="submit" class="btn-primary-custom" [disabled]="saving()">
            {{ saving() ? 'Salvando...' : 'Salvar' }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [`
    .muted { color: #6b7280; font-size: .875rem; margin-bottom: 16px; }
  `],
})
export class AdminNovoAdminComponent {
  private api = inject(ApiService);
  private router = inject(Router);

  nomeCompleto = '';
  email = '';
  username = '';
  senha = '';
  confirmarSenha = '';
  saving = signal(false);
  errorMsg = signal('');
  successMsg = signal('');

  onSubmit(): void {
    this.errorMsg.set('');
    this.successMsg.set('');

    if (!this.nomeCompleto.trim() || !this.email.trim() || !this.username.trim() || !this.senha) {
      this.errorMsg.set('Preencha todos os campos obrigatórios.');
      return;
    }
    if (!/^[a-zA-Z0-9._-]{3,}$/.test(this.username)) {
      this.errorMsg.set('Usuário deve ter no mínimo 3 caracteres (letras, números, . _ -).');
      return;
    }
    if (this.senha.length < 6) {
      this.errorMsg.set('Senha deve ter no mínimo 6 caracteres.');
      return;
    }
    if (this.senha !== this.confirmarSenha) {
      this.errorMsg.set('As senhas não conferem.');
      return;
    }

    this.saving.set(true);
    this.api.post<any>('/api/admin/admins/novo', {
      nome_completo: this.nomeCompleto.trim(),
      email: this.email.trim(),
      username: this.username.trim(),
      senha: this.senha,
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.successMsg.set('Administrador cadastrado com sucesso!');
          this.nomeCompleto = '';
          this.email = '';
          this.username = '';
          this.senha = '';
          this.confirmarSenha = '';
        } else {
          this.errorMsg.set(res.message || res.error || 'Erro ao cadastrar.');
        }
      },
      error: err => {
        this.saving.set(false);
        const e = err.error;
        if (err.status === 403) this.errorMsg.set('Acesso negado. Somente o administrador master pode cadastrar novos administradores.');
        else if (err.status === 409) this.errorMsg.set(e?.message || 'E-mail ou usuário já cadastrado.');
        else this.errorMsg.set(e?.message || e?.error || 'Erro ao cadastrar.');
      },
    });
  }
}

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { aceitarFoto, FOTO_ACCEPT } from '../../core/helpers/foto.helpers';

@Component({
  selector: 'app-admin-novo-operador',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card-custom" style="max-width:600px; margin:0 auto">
      <h1>Cadastrar Novo Operador</h1>

      <form (ngSubmit)="onSubmit()">
        <div class="form-row"><label>Nome Completo *</label><input [(ngModel)]="nomeCompleto" name="nome_completo" required></div>
        <div class="form-row"><label>Nome de Chamada *</label><input [(ngModel)]="nomeExibicao" name="nome_exibicao" required></div>
        <div class="form-row"><label>E-mail *</label><input type="email" [(ngModel)]="email" name="email" required></div>
        <div class="form-row"><label>Usuário *</label><input [(ngModel)]="username" name="username" required pattern="[a-zA-Z0-9._-]{3,}"></div>
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
          <div class="form-row"><label>Senha *</label><input type="password" [(ngModel)]="senha" name="senha" required minlength="6"></div>
          <div class="form-row"><label>Confirmar Senha *</label><input type="password" [(ngModel)]="confirmarSenha" name="confirmar_senha" required></div>
        </div>
        <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px">
          <input type="checkbox" [(ngModel)]="plenarioPrincipal" name="plenario_principal"
                 id="plenario_principal" (change)="onPlenarioChange()"
                 style="width:auto; margin:0">
          <label for="plenario_principal" style="margin:0; cursor:pointer; font-weight:500; font-size:.9375rem">Apto a operar no Plenário Principal</label>
        </div>
        <div style="display:flex; align-items:center; gap:8px; margin-bottom:14px; padding-left:24px">
          <input type="checkbox" [(ngModel)]="plenarioPrincipalFixo" name="plenario_principal_fixo"
                 id="plenario_principal_fixo" [disabled]="!plenarioPrincipal"
                 style="width:auto; margin:0">
          <label for="plenario_principal_fixo" style="margin:0; cursor:pointer; font-weight:500; font-size:.9375rem"
                 [style.color]="plenarioPrincipal ? null : '#94a3b8'">Operador fixo do Plenário Principal</label>
        </div>
        <div class="form-row">
          <label>Foto (opcional)</label>
          <input type="file" (change)="onFileSelect($event)" [accept]="fotoAccept">
        </div>

        @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

        <div style="display:flex; justify-content:space-between; margin-top:20px">
          <a routerLink="/admin/gestao-pessoas" class="btn-secondary-custom">← Voltar</a>
          <button type="submit" class="btn-primary-custom" [disabled]="saving()">
            {{ saving() ? 'Salvando...' : 'Salvar' }}
          </button>
        </div>
      </form>
    </div>
  `,
})
export class AdminNovoOperadorComponent {
  private api = inject(ApiService);
  private router = inject(Router);

  nomeCompleto=''; nomeExibicao=''; email=''; username=''; senha=''; confirmarSenha='';
  plenarioPrincipal=false; plenarioPrincipalFixo=false;
  foto: File | null = null;
  readonly fotoAccept = FOTO_ACCEPT;
  saving = signal(false);
  errorMsg = signal('');

  onFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.foto = aceitarFoto(input, this.errorMsg);
  }

  onPlenarioChange(): void {
    if (!this.plenarioPrincipal) this.plenarioPrincipalFixo = false;
  }

  onSubmit(): void {
    this.errorMsg.set('');
    if (this.senha.length < 6) { this.errorMsg.set('Senha deve ter no mínimo 6 caracteres.'); return; }
    if (this.senha !== this.confirmarSenha) { this.errorMsg.set('As senhas não conferem.'); return; }
    if (!/^[a-zA-Z0-9._-]{3,}$/.test(this.username)) { this.errorMsg.set('Usuário deve ter no mínimo 3 caracteres (letras, números, . _ -).'); return; }

    this.saving.set(true);
    const fd = new FormData();
    fd.append('nome_completo', this.nomeCompleto);
    fd.append('nome_exibicao', this.nomeExibicao);
    fd.append('email', this.email);
    fd.append('username', this.username);
    fd.append('senha', this.senha);
    fd.append('plenario_principal', String(this.plenarioPrincipal));
    fd.append('plenario_principal_fixo', String(this.plenarioPrincipal && this.plenarioPrincipalFixo));
    if (this.foto) fd.append('foto', this.foto);

    this.api.postForm<any>('/api/admin/operadores/novo', fd).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) { alert('Operador cadastrado com sucesso!'); this.router.navigate(['/admin/gestao-pessoas']); }
        else this.errorMsg.set(res.message || res.error || 'Erro ao cadastrar.');
      },
      error: err => {
        this.saving.set(false);
        const e = err.error;
        if (err.status === 409) this.errorMsg.set(e?.message || 'E-mail ou usuário já cadastrado.');
        else this.errorMsg.set(e?.message || e?.error || 'Erro ao cadastrar.');
      },
    });
  }
}

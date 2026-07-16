import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { aceitarFoto, FOTO_ACCEPT, FOTO_ANONIMA, fotoErrorFallback, resolverFotoUrl }
  from '../../core/helpers/foto.helpers';
import { HORA_RE, HoraMaskDirective } from '../../shared/directives/hora-mask.directive';

type TipoPessoa = 'operador' | 'tecnico' | 'administrador';

/**
 * Perfil de pessoa (operador, técnico ou administrador). O tipo vem de route.data.tipo.
 * - Técnico: sem "Nome de Chamada", sem os 3 checkboxes (Apto/Fixo PP e Participa
 *   da Escala) e turno opcional (pode ficar vazio).
 * - Administrador (rota só do master): sem "Nome de Chamada" e sem os 3 checkboxes;
 *   tem o checkbox "Servidor Público" (logo após o e-mail) e turno com a opção extra
 *   "Integral" (I). Quando servidor público, oculta turno/carga/horário (gravados NULL).
 */
@Component({
  selector: 'app-admin-pessoa-perfil',
  standalone: true,
  imports: [FormsModule, HoraMaskDirective],
  template: `
    <div class="card-custom perfil-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!op()) {
        <div class="error-box">{{ errorMsg() || 'Registro não encontrado.' }}</div>
        <div class="perfil-acoes">
          <button class="btn-secondary-custom" (click)="voltar()">← Voltar</button>
        </div>
      } @else {
        <!-- Cabeçalho: foto + nome -->
        <div class="perfil-header">
          <div class="avatar-wrap">
            <img class="avatar-lg" [src]="fotoUrl() || ANONIMO" (error)="onImgError($event)" alt="Foto">
            @if (editing()) {
              <label class="btn-troca-foto">
                Trocar foto
                <input type="file" [accept]="fotoAccept" (change)="onFileSelect($event)" hidden>
              </label>
            }
          </div>
          <h1 class="perfil-nome">{{ op()!['nome_completo'] }}</h1>
        </div>

        <!-- Campos -->
        <div class="perfil-campos">
          <div class="perfil-row">
            <span class="perfil-label">Nome:</span>
            @if (editing()) {
              <input class="perfil-input" [(ngModel)]="nomeCompleto">
            } @else {
              <span class="perfil-valor">{{ op()!['nome_completo'] }}</span>
            }
          </div>

          @if (tipo === 'operador') {
            <div class="perfil-row">
              <span class="perfil-label">Nome de Chamada:</span>
              @if (editing()) {
                <input class="perfil-input" [(ngModel)]="nomeExibicao">
              } @else {
                <span class="perfil-valor">{{ op()!['nome_exibicao'] }}</span>
              }
            </div>
          }

          <div class="perfil-row">
            <span class="perfil-label">E-mail:</span>
            @if (editing()) {
              <input class="perfil-input" type="email" [(ngModel)]="email">
            } @else {
              <span class="perfil-valor">{{ op()!['email'] }}</span>
            }
          </div>

          <!-- Servidor Público (somente administrador) -->
          @if (tipo === 'administrador') {
            <div class="perfil-check">
              <input type="checkbox" id="chk-servidor" [(ngModel)]="servidorPublico" [disabled]="!editing()">
              <label for="chk-servidor">Servidor Público</label>
            </div>
          }

          <!-- Turno / Carga / Horário: ocultos para admin servidor público -->
          @if (mostrarJornada) {
          <div class="perfil-row">
            <span class="perfil-label">Turno:</span>
            @if (editing()) {
              <select class="perfil-input perfil-input-sm" [(ngModel)]="turno">
                @if (tipo !== 'operador') { <option value="">—</option> }
                <option value="M">Matutino</option>
                <option value="V">Vespertino</option>
                @if (tipo === 'administrador') { <option value="I">Integral</option> }
              </select>
            } @else {
              <span class="perfil-valor">{{ turnoLabel() }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Carga Horária:</span>
            @if (editing()) {
              <select class="perfil-input perfil-input-sm" [(ngModel)]="cargaHoraria">
                <option value="">—</option>
                <option value="30">30H</option>
                <option value="40">40H</option>
              </select>
            } @else {
              <span class="perfil-valor">{{ cargaLabel() }}</span>
            }
          </div>

          <div class="perfil-row">
            <span class="perfil-label">Horário de Trabalho:</span>
            @if (editing()) {
              <span class="horario-edit">
                <input class="perfil-input perfil-input-hora" [value]="horaInicio" appHoraMask (horaChange)="horaInicio = $event" inputmode="numeric" placeholder="HH:MM" maxlength="5">
                <span class="horario-as">às</span>
                <input class="perfil-input perfil-input-hora" [value]="horaFim" appHoraMask (horaChange)="horaFim = $event" inputmode="numeric" placeholder="HH:MM" maxlength="5">
              </span>
            } @else {
              <span class="perfil-valor">{{ horarioLabel() }}</span>
            }
          </div>
          }

          <!-- Checkboxes (somente operador) -->
          @if (tipo === 'operador') {
            <div class="perfil-check">
              <input type="checkbox" id="chk-apto" [(ngModel)]="plenarioPrincipal" [disabled]="!editing()" (change)="onPlenarioChange()">
              <label for="chk-apto">Apto a operar no Plenário Principal</label>
            </div>
            <div class="perfil-check perfil-check-indent">
              <input type="checkbox" id="chk-fixo" [(ngModel)]="plenarioPrincipalFixo" [disabled]="!editing() || !plenarioPrincipal">
              <label for="chk-fixo" [style.color]="plenarioPrincipal ? null : '#94a3b8'">Operador fixo do Plenário Principal</label>
            </div>
            <div class="perfil-check">
              <input type="checkbox" id="chk-escala" [(ngModel)]="participaEscala" [disabled]="!editing()">
              <label for="chk-escala">Participa da Escala</label>
            </div>
          }
        </div>

        @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

        <!-- Ações -->
        <div class="perfil-acoes">
          @if (editing()) {
            <button class="btn-secondary-custom" (click)="cancelar()" [disabled]="saving()">Cancelar</button>
            <button class="btn-primary-custom" (click)="salvar()" [disabled]="saving()">{{ saving() ? 'Salvando...' : 'Salvar' }}</button>
          } @else {
            <button class="btn-secondary-custom" (click)="voltar()">← Voltar</button>
            <button class="btn-primary-custom" (click)="entrarEdicao()">Editar</button>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .perfil-card { max-width: 640px; margin: 0 auto; }
    .perfil-header { display:flex; align-items:center; gap:24px; margin-bottom:28px; flex-wrap:wrap; }
    .avatar-wrap { display:flex; flex-direction:column; align-items:center; gap:8px; }
    .avatar-lg { width:120px; height:120px; border-radius:50%; object-fit:cover; border:3px solid var(--senado-azul); }
    .btn-troca-foto { font-size:.75rem; color:var(--primary); cursor:pointer; border:1px solid var(--border); border-radius:999px; padding:4px 12px; background:#fff; }
    .btn-troca-foto:hover { background:var(--row-hover); }
    .perfil-nome { margin:0; font-size:1.5rem; }
    .perfil-campos { display:flex; flex-direction:column; gap:14px; }
    .perfil-row { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
    .perfil-label { font-weight:500; color:var(--text); min-width:150px; }
    .perfil-valor { font-weight:700; }
    .perfil-input { flex:1; min-width:200px; }
    .perfil-input-sm { flex:0 0 auto; min-width:110px; }
    .perfil-input-hora { flex:0 0 auto; width:80px; min-width:0; text-align:center; }
    .horario-edit { display:flex; align-items:center; gap:8px; }
    .horario-as { color:var(--muted); }
    .perfil-check { display:flex; align-items:center; gap:8px; }
    .perfil-check input { width:auto; margin:0; cursor:pointer; }
    .perfil-check input:disabled { cursor:default; }
    .perfil-check label { font-weight:500; cursor:pointer; }
    .perfil-check-indent { padding-left:24px; }
    .perfil-acoes { display:flex; justify-content:space-between; margin-top:28px; gap:12px; }
    .error-box { margin-top:16px; }
  `],
})
export class AdminPessoaPerfilComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);

  tipo: TipoPessoa = 'operador';
  private id = '';
  op = signal<Record<string, any> | null>(null);
  loading = signal(true);
  editing = signal(false);
  saving = signal(false);
  errorMsg = signal('');

  // Campos editáveis
  nomeCompleto = '';
  nomeExibicao = '';
  email = '';
  turno = '';
  cargaHoraria = '';   // '', '30' ou '40'
  horaInicio = '';
  horaFim = '';
  plenarioPrincipal = false;
  plenarioPrincipalFixo = false;
  participaEscala = false;
  servidorPublico = false;   // só administrador

  foto: File | null = null;
  readonly fotoAccept = FOTO_ACCEPT;
  fotoPreview = signal('');

  /**
   * Turno/Carga/Horário aparecem para operador e técnico sempre; para o
   * administrador, só quando NÃO for servidor público (em edição segue o
   * checkbox; em visualização, o valor carregado).
   */
  get mostrarJornada(): boolean {
    if (this.tipo !== 'administrador') return true;
    return this.editing() ? !this.servidorPublico : !this.op()?.['servidor_publico'];
  }

  readonly ANONIMO = FOTO_ANONIMA;

  /** Se a foto cadastrada não existir (404), cai para a imagem anônima (sem loop). */
  onImgError(event: Event): void {
    fotoErrorFallback(event);
  }

  ngOnInit(): void {
    this.tipo = (this.route.snapshot.data['tipo'] as TipoPessoa) || 'operador';
    this.id = this.route.snapshot.queryParamMap.get('id') || '';
    if (!this.id) { this.errorMsg.set('Registro não informado.'); this.loading.set(false); return; }
    this.carregar();
  }

  private carregar(): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/${this.tipo}/${this.id}`).subscribe({
      next: res => { const o = this.unwrapPessoa(res); this.op.set(o); this.popularCampos(o); this.loading.set(false); },
      error: () => { this.errorMsg.set('Erro ao carregar o registro.'); this.loading.set(false); },
    });
  }

  private popularCampos(o: Record<string, any>): void {
    this.nomeCompleto = o['nome_completo'] || '';
    this.nomeExibicao = o['nome_exibicao'] || '';
    this.email = o['email'] || '';
    this.turno = o['turno'] || (this.tipo === 'operador' ? 'M' : '');
    this.cargaHoraria = o['carga_horaria'] != null ? String(o['carga_horaria']) : '';
    this.horaInicio = o['horario_trabalho_inicio'] || '';
    this.horaFim = o['horario_trabalho_fim'] || '';
    this.plenarioPrincipal = this.coerceBool(o['plenario_principal']);
    this.plenarioPrincipalFixo = this.coerceBool(o['plenario_principal_fixo']);
    this.participaEscala = this.coerceBool(o['participa_escala']);
    this.servidorPublico = this.coerceBool(o['servidor_publico']);
  }

  private coerceBool(v: unknown): boolean {
    return v === true || v === 1;
  }

  private unwrapPessoa(res: any): Record<string, any> {
    return res?.operador ?? res?.tecnico ?? res?.administrador ?? res;
  }

  // Foto exibida: preview da nova foto > foto atual > '' (cai para a anônima)
  fotoUrl = computed(() => {
    if (this.fotoPreview()) return this.fotoPreview();
    return resolverFotoUrl(this.op()?.['foto_url']);
  });

  turnoLabel = computed(() => {
    const t = this.op()?.['turno'];
    return t === 'V' ? 'Vespertino' : t === 'M' ? 'Matutino' : t === 'I' ? 'Integral' : '—';
  });

  cargaLabel = computed(() => {
    const c = this.op()?.['carga_horaria'];
    return c != null ? c + 'H' : '—';
  });

  horarioLabel = computed(() => {
    const i = this.op()?.['horario_trabalho_inicio'];
    const f = this.op()?.['horario_trabalho_fim'];
    if (i && f) return `${i} às ${f}`;
    if (i || f) return `${i || '—'} às ${f || '—'}`;
    return '—';
  });

  entrarEdicao(): void {
    const o = this.op(); if (o) this.popularCampos(o);
    this.foto = null; this.fotoPreview.set(''); this.errorMsg.set('');
    this.editing.set(true);
  }

  cancelar(): void {
    const o = this.op(); if (o) this.popularCampos(o);
    this.foto = null; this.fotoPreview.set(''); this.errorMsg.set('');
    this.editing.set(false);
  }

  onPlenarioChange(): void {
    if (!this.plenarioPrincipal) this.plenarioPrincipalFixo = false;
  }

  onFileSelect(event: Event): void {
    const file = aceitarFoto(event.target as HTMLInputElement, this.errorMsg);
    this.foto = file;
    if (file) {
      const reader = new FileReader();
      reader.onload = () => this.fotoPreview.set(reader.result as string);
      reader.readAsDataURL(file);
    } else {
      this.fotoPreview.set('');
    }
  }

  salvar(): void {
    this.errorMsg.set('');
    if (!this.nomeCompleto.trim() || !this.email.trim() || (this.tipo === 'operador' && !this.nomeExibicao.trim())) {
      this.errorMsg.set(this.tipo === 'operador'
        ? 'Nome, Nome de Chamada e E-mail são obrigatórios.'
        : 'Nome e E-mail são obrigatórios.');
      return;
    }
    if (this.mostrarJornada) {
      if (this.horaInicio && !HORA_RE.test(this.horaInicio)) { this.errorMsg.set('Horário de início inválido (use HH:MM).'); return; }
      if (this.horaFim && !HORA_RE.test(this.horaFim)) { this.errorMsg.set('Horário de fim inválido (use HH:MM).'); return; }
    }

    this.saving.set(true);
    const fd = new FormData();
    fd.append('nome_completo', this.nomeCompleto.trim());
    fd.append('email', this.email.trim());
    fd.append('turno', this.turno);
    fd.append('carga_horaria', this.cargaHoraria);
    fd.append('horario_trabalho_inicio', this.horaInicio.trim());
    fd.append('horario_trabalho_fim', this.horaFim.trim());
    if (this.tipo === 'operador') {
      fd.append('nome_exibicao', this.nomeExibicao.trim());
      fd.append('plenario_principal', String(this.plenarioPrincipal));
      fd.append('plenario_principal_fixo', String(this.plenarioPrincipal && this.plenarioPrincipalFixo));
      fd.append('participa_escala', String(this.participaEscala));
    }
    if (this.tipo === 'administrador') {
      fd.append('servidor_publico', String(this.servidorPublico));
    }
    if (this.foto) fd.append('foto', this.foto);

    this.api.postForm<any>(`/api/admin/${this.tipo}/${this.id}/atualizar`, fd).subscribe({
      next: res => {
        this.saving.set(false);
        const o = this.unwrapPessoa(res);
        this.op.set(o); this.popularCampos(o);
        // Se editou o próprio registro, reflete a (eventual) nova foto no header sem re-login
        if (this.id === this.auth.currentUserId()) this.auth.setFotoUrl(o['foto_url'] || '');
        this.foto = null; this.fotoPreview.set('');
        this.editing.set(false);
      },
      error: err => {
        this.saving.set(false);
        this.errorMsg.set(httpErrorMsg(err, err?.status === 409 ? 'E-mail já cadastrado para outro usuário.' : 'Erro ao salvar.', ['message']));
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/admin/gestao-pessoas']);
  }
}

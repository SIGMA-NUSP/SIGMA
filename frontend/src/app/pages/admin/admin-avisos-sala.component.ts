import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef } from '../../shared/components/column-filter.component';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { getDistinct } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ToastService } from '../../shared/components/toast.component';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';

interface AvisoRow {
  id: string;
  numero: number;
  tipo: string;        // já vem como label ("Verificação")
  criado_em: string;
  criado_por: string;
  expira_em: string | null;
  status: 'Ativo' | 'Expirado' | 'Desativado';
  permanente: number;  // 0/1
}

@Component({
  selector: 'app-admin-avisos-sala',
  standalone: true,
  imports: [FormsModule, RouterLink, PaginationComponent, ColumnFilterComponent, MultiSelectDropdownComponent,
    ErroCargaComponent, FmtDatePipe],
  template: `
    <h1>Inserir Avisos</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <!-- ════════════ FORMULÁRIO DE CADASTRO ════════════ -->
    <section class="card-custom" style="max-width:720px; margin: 16px auto 24px;">
      <div class="form-row">
        <label>Local <span class="req">*</span></label>
        <app-multi-select-dropdown
          [options]="salaOptions()"
          [selected]="selectedSalaIds"
          [lockedIds]="lockedSalaIds()"
          placeholder="Selecione um ou mais locais..."
          (selectionChange)="selectedSalaIds = $event" />
      </div>

      @if (erroSalasOcupadas()) {
        <!-- FAIL-CLOSED (C18/F67): sem saber quais salas já têm aviso ativo, o multi-select mostraria
             TODAS como livres — a pior direção da mentira. O erro é anunciado e o envio, bloqueado;
             preencher pode, enviar não. -->
        <div class="form-row">
          <app-erro-carga [mensagem]="erroSalasOcupadas()" (tentarNovamente)="loadSalasOcupadas()" />
        </div>
      }

      @for (msg of mensagens; track $index) {
        <div class="form-row">
          <label>{{ $index + 1 }}º Aviso <span class="req">*</span></label>
          <textarea [(ngModel)]="mensagens[$index]" [name]="'msg_' + $index" rows="2"></textarea>
        </div>
      }

      <div class="msg-actions">
        @if (mensagens.length < MAX_MENSAGENS) {
          <button type="button" class="btn-outline" (click)="addMensagem()">+ Novo Aviso</button>
        }
        @if (mensagens.length > 1) {
          <button type="button" class="btn-outline" (click)="removerUltimaMensagem()">Remover</button>
        }
      </div>

      <div class="form-row" style="margin-top:14px">
        <label>Aviso permanente <span class="req">*</span></label>
        <div class="radio-row">
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="true"> Sim</label>
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="false"> Não</label>
          @if (!permanente) {
            <span class="duracao-inline">
              <label>Duração (dias) <span class="req">*</span></label>
              <input type="number" min="1" max="30" [(ngModel)]="duracaoDias" name="duracao_dias">
            </span>
          }
        </div>
      </div>

      <div class="form-row">
        <label class="check-opt">
          <input type="checkbox" [(ngModel)]="manterAposCiencia" name="manter">
          Manter aviso após ciência do operador
        </label>
      </div>

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div style="display:flex; justify-content:flex-end; margin-top:12px">
        <!-- [disabled] também por salasOcupadasIndisponiveis (C18/F67): o gate fail-closed do lock
             "1 aviso ativo por sala" — camada de UI; a trava real é o guard do onSubmit(). -->
        <button class="btn-primary-custom" [disabled]="saving() || salasOcupadasIndisponiveis()" (click)="onSubmit()">
          {{ saving() ? 'Salvando...' : 'Cadastrar Aviso' }}
        </button>
      </div>
    </section>

    <!-- ════════════ LISTAGEM ════════════ -->
    <section>
      <div class="section-header">
        <h2>Avisos Cadastrados</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="ctrl.searchText" (input)="ctrl.onSearch()" placeholder="Buscar por autor ou nº do cadastro..." class="search-input search-wide">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>Cadastro nº</th>
            <th>
              <app-column-filter [col]="cols[0]"
                [distinctValues]="gd(ctrl.meta(),'tipo')"
                [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[1]"
                [distinctValues]="gd(ctrl.meta(),'data')"
                [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[2]"
                [distinctValues]="gd(ctrl.meta(),'criado_por')"
                [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[3]"
                [distinctValues]="gd(ctrl.meta(),'expira')"
                [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="cols[4]"
                [distinctValues]="gd(ctrl.meta(),'status')"
                [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" />
            </th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (ctrl.erro()) {
              <!-- Canal de erro (C7/C13b): "Nenhum aviso cadastrado." numa carga que FALHOU esconde
                   os avisos ATIVOS — e o admin cadastra por cima, ou deixa de desativar o que devia. -->
              <tr><td colspan="7">
                <app-erro-carga [mensagem]="ctrl.erro()" (tentarNovamente)="ctrl.load()" />
              </td></tr>
            } @else if (ctrl.rows().length === 0) {
              <tr><td colspan="7" class="empty-state">{{ ctrl.loading() ? 'Carregando...' : 'Nenhum aviso cadastrado.' }}</td></tr>
            } @else {
              @for (a of ctrl.rows(); track a.id) {
                <tr class="row-clickable" (dblclick)="abrirDetalhe(a)" title="Duplo-clique para ver o detalhe">
                  <td>{{ a.numero }}</td>
                  <td>{{ a.tipo }}</td>
                  <td>{{ a.criado_em | fmtDate }}</td>
                  <td>{{ a.criado_por }}</td>
                  <td>{{ a.permanente ? '—' : (a.expira_em | fmtDate) }}</td>
                  <td>
                    <span class="status-dot" [attr.data-status]="a.status"></span>
                    {{ a.status }}
                  </td>
                  <td>
                    @if (a.status === 'Ativo') {
                      <button class="btn-xs" (click)="desativar(a); $event.stopPropagation()">Desativar</button>
                    } @else { — }
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="ctrl.meta()!"
                      (pageChange)="ctrl.onPage($event)"
                      (limitChange)="ctrl.onLimit($event)" />
    </section>
  `,
  styles: [`
    section { margin-bottom: 28px; }
    /* especificidade > .duracao-inline input{width:90px}: mantém o campo "Duração" em 100% (a global .form-row input não venceria) */
    .form-row input[type="number"] { width:100%; }
    .form-row textarea { resize: vertical; }
    .req { color:#dc2626; }
    .msg-actions { display:flex; gap:8px; margin-bottom:4px; }
    .radio-row { display:flex; align-items:center; gap:18px; flex-wrap:wrap; }
    .radio-opt { display:flex; align-items:center; gap:6px; font-weight:500; margin:0; cursor:pointer; }
    .radio-opt input { width:auto; }
    .duracao-inline { display:flex; align-items:center; gap:8px; }
    .duracao-inline label { margin:0; white-space:nowrap; }
    .duracao-inline input { width:90px; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
    .row-clickable { cursor: pointer; }
    .status-dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:6px; vertical-align:middle; }
    .status-dot[data-status="Ativo"]      { background: var(--color-green, #16a34a); }
    .status-dot[data-status="Expirado"]   { background: #9ca3af; }
    .status-dot[data-status="Desativado"] { background: #111827; }
  `],
})
export class AdminAvisosSalaComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);
  lookup = inject(LookupService);

  readonly MAX_MENSAGENS = 10;

  // ── Form ──
  // Tipo travado em VERIFICACAO nesta versão (enviado literal no payload).
  // A escolha de tipo terá outra UI numa próxima entrega.
  selectedSalaIds: string[] = [];
  mensagens: string[] = [''];
  permanente = true;
  duracaoDias: number | null = null;
  manterAposCiencia = false;
  saving = signal(false);
  errorMsg = signal('');
  // sala_id (string) → nº do cadastro ativo que a ocupa (Fix: 1 aviso ativo por sala)
  salasOcupadas = signal<Record<string, number>>({});
  /** Canal de erro da carga das salas-com-aviso (C18/F67): '' = sem erro. Limpo a cada disparo. */
  erroSalasOcupadas = signal('');
  loadingSalasOcupadas = signal(true);
  /** Token de recência (C18/F67): o retry reclicado põe duas cargas em voo — um erro velho não
   *  pode religar o bloqueio que o sucesso mais novo já destravou. */
  private seqSalasOcupadas = 0;
  /**
   * FAIL-CLOSED (C18/F67 — decisão do Douglas): enquanto a carga das salas-com-aviso não tiver
   * SUCEDIDO (em voo OU falhou), o lock "1 aviso ativo por sala" não é confiável e o CADASTRO fica
   * bloqueado — a tela nunca exibe salas como livres sem saber. Um mapa vazio NÃO serve de proxy:
   * `{}` também é o vazio legítimo (nenhuma sala ocupada).
   */
  salasOcupadasIndisponiveis = computed(() =>
    this.loadingSalasOcupadas() || !!this.erroSalasOcupadas());

  // ── Listagem ──
  cols: ColumnFilterDef[] = [
    { key: 'tipo',       label: 'Tipo de Aviso',  type: 'text' },
    { key: 'data',       label: 'Data',           type: 'date' },
    { key: 'criado_por', label: 'Cadastrado por', type: 'text' },
    { key: 'expira',     label: 'Expira em',      type: 'date' },
    { key: 'status',     label: 'Status',         type: 'text' },
  ];
  ctrl = new TableStateController<AvisoRow>(this.api, {
    endpoint: '/api/admin/avisos/list', defaultSort: 'data', defaultDir: 'desc',
  });

  ngOnInit(): void {
    if (this.lookup.salas().length === 0) this.lookup.loadSalas();
    this.loadSalasOcupadas();
    this.ctrl.load();
  }

  /** Carga do lock "1 aviso ativo por sala"; também é o retry da caixa de erro (C18/F67). */
  loadSalasOcupadas(): void {
    const seq = ++this.seqSalasOcupadas;
    this.erroSalasOcupadas.set('');
    this.loadingSalasOcupadas.set(true);
    this.api.get<any>('/api/admin/avisos/salas-ocupadas').subscribe({
      next: res => {
        if (seq !== this.seqSalasOcupadas) return;   // obsoleta: uma carga mais nova está em voo
        const map: Record<string, number> = {};
        (res?.data || []).forEach((r: any) => { map[String(r.sala_id)] = r.numero; });
        this.salasOcupadas.set(map);
        this.loadingSalasOcupadas.set(false);
      },
      error: err => {
        if (seq !== this.seqSalasOcupadas) return;   // a falha velha não religa o bloqueio destravado
        this.loadingSalasOcupadas.set(false);
        this.erroSalasOcupadas.set(erroCargaMsg(err,
          'Não foi possível verificar quais locais já têm aviso ativo. O cadastro fica bloqueado até recarregar — um local ocupado apareceria como livre.'));
      },
    });
  }

  /** Salas com aviso ativo ganham "— Cadastro nº X" no rótulo e ficam desabilitadas. */
  salaOptions = computed<MultiSelectOption[]>(() => {
    const ocup = this.salasOcupadas();
    return this.lookup.salas().map(s => {
      const id = String(s.id);
      const num = ocup[id];
      return num != null
        ? { id, label: `${s.nome} — Cadastro nº ${num}` }
        : { id, label: s.nome };
    });
  });

  lockedSalaIds = computed<string[]>(() => Object.keys(this.salasOcupadas()));

  addMensagem(): void {
    if (this.mensagens.length < this.MAX_MENSAGENS) this.mensagens.push('');
  }

  removerUltimaMensagem(): void {
    if (this.mensagens.length > 1) this.mensagens.pop();
  }

  gd = getDistinct;

  onSubmit(): void {
    // FAIL-CLOSED (C18/F67): defesa dupla — o [disabled] do botão é só a camada de UI.
    if (this.salasOcupadasIndisponiveis()) return;
    this.errorMsg.set('');
    if (this.selectedSalaIds.length === 0) { this.errorMsg.set('Selecione ao menos um local.'); return; }
    const msgs = this.mensagens.map(m => m.trim());
    if (msgs.some(m => !m)) { this.errorMsg.set('Preencha todas as mensagens.'); return; }
    if (!this.permanente && (!this.duracaoDias || this.duracaoDias < 1 || this.duracaoDias > 30)) {
      this.errorMsg.set('A duração deve estar entre 1 e 30 dias.'); return;
    }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos', {
      tipo: 'VERIFICACAO',
      permanente: this.permanente,
      duracao_dias: this.permanente ? null : this.duracaoDias,
      manter_apos_ciencia: this.manterAposCiencia,
      mensagens: msgs,
      alvo_tipo: 'SALA',
      sala_ids: this.selectedSalaIds.map(Number),
      operador_ids: [],
      tecnico_ids: [],
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.resetForm();
          this.loadSalasOcupadas();
          this.ctrl.state.page = 1;
          this.ctrl.load();
        } else {
          this.errorMsg.set(res.message || res.error || 'Erro ao cadastrar.');
        }
      },
      error: err => {
        this.saving.set(false);
        this.errorMsg.set(httpErrorMsg(err, 'Erro ao cadastrar.'));
      },
    });
  }

  resetForm(): void {
    this.selectedSalaIds = [];
    this.mensagens = [''];
    this.permanente = true;
    this.duracaoDias = null;
    this.manterAposCiencia = false;
  }

  abrirDetalhe(a: AvisoRow): void {
    this.router.navigate(['/admin/aviso/detalhe'], { queryParams: { id: a.id } });
  }

  desativar(a: AvisoRow): void {
    if (!confirm(`Desativar o cadastro nº ${a.numero}?`)) return;
    this.api.patch(`/api/admin/avisos/${a.id}/desativar`, {}).subscribe({
      next: () => { this.toast.success('Aviso desativado.'); this.ctrl.load(); },
      error: err => this.toast.error(httpErrorMsg(err, 'Erro ao desativar.', ['message'])),
    });
  }
}

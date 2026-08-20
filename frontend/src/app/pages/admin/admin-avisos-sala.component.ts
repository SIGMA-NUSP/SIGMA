import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { AvisoEscalaFormComponent } from './aviso-escala-form.component';
import { AvisoAgendaFormComponent } from './aviso-agenda-form.component';
import { AvisoPessoalFormComponent } from './aviso-pessoal-form.component';
import { AvisoRow, ComunicacoesTabelaComponent, STATUS_ATIVOS, STATUS_INATIVOS } from './comunicacoes-tabela.component';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ToastService } from '../../shared/components/toast.component';

@Component({
  selector: 'app-admin-avisos-sala',
  standalone: true,
  imports: [FormsModule, RouterLink, MultiSelectDropdownComponent, ErroCargaComponent,
    ComunicacoesTabelaComponent, AvisoEscalaFormComponent, AvisoAgendaFormComponent,
    AvisoPessoalFormComponent],
  template: `
    <h1>Comunicações</h1>
    <a routerLink="/admin/gestao-pessoas" class="back-link">&larr; Voltar</a>

    <!-- ════════════ CARDS DE SELEÇÃO (1 ativo por vez; reclicar oculta o painel) ════════════ -->
    <div class="grid-cards cols-auto cards-aviso">
      <button class="card-custom card-pick" [class.active]="activeCard() === 'verificacao'" (click)="toggleCard('verificacao')">
        <strong>Verificação</strong><span class="text-muted-sm">Cadastro de aviso no formulário de verificação</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeCard() === 'escala'" (click)="toggleCard('escala')">
        <strong>Escala</strong><span class="text-muted-sm">Cadastro de aviso por escala semanal</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeCard() === 'agenda'" (click)="toggleCard('agenda')">
        <strong>Agenda</strong><span class="text-muted-sm">Cadastro de comunicado - Agenda Legislativa</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeCard() === 'pessoal'" (click)="toggleCard('pessoal')">
        <strong>Pessoal</strong><span class="text-muted-sm">Cadastro de mensagem individual ou por grupos</span>
      </button>
    </div>

    <!-- ════════════ PAINEL VERIFICAÇÃO (form atual — comportamento intocado) ════════════ -->
    @if (activeCard() === 'verificacao') {
    <section class="card-custom" style="max-width:720px; margin: 16px auto 24px;">
      <div class="form-row">
        <label>Local <span class="req">*</span></label>
        <app-multi-select-dropdown
          [options]="salaOptions()"
          [selected]="selectedSalaIds"
          [lockedIds]="lockedSalaIds()"
          placeholder="Selecione um ou mais locais..."
          (selectionChange)="selectedSalaIds = $event" />
        @if (lookup.erroSalas()) {
          <app-erro-carga [mensagem]="lookup.erroSalas()" (tentarNovamente)="lookup.loadSalas()" />
        }
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
          <label>{{ $index + 1 }}º Texto <span class="req">*</span></label>
          <textarea [(ngModel)]="mensagens[$index]" [name]="'msg_' + $index" rows="2"></textarea>
        </div>
      }

      <div class="msg-actions">
        @if (mensagens.length < MAX_MENSAGENS) {
          <button type="button" class="btn-outline" (click)="addMensagem()">+ Novo Texto</button>
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
    }

    <!-- ════════════ PAINÉIS NOVOS (Escala / Agenda / Pessoal) — atrás da flag inserirAvisos via a rota ════════════ -->
    @if (activeCard() === 'escala') { <app-aviso-escala-form (cadastrado)="onCadastrado()" /> }
    @if (activeCard() === 'agenda') { <app-aviso-agenda-form (cadastrado)="onCadastrado()" /> }
    @if (activeCard() === 'pessoal') { <app-aviso-pessoal-form (cadastrado)="onCadastrado()" /> }

    <!-- ════════════ LISTAGEM — as em curso à vista; as encerradas, ao alcance de um clique ════════════ -->
    <section>
      <div class="section-header">
        <h2>Comunicações Ativas</h2>
      </div>
      <app-comunicacoes-tabela [ctrl]="ctrlAtivas" [statusDaTabela]="ATIVOS"
                               vazio="Nenhuma comunicação ativa."
                               (abrir)="abrirDetalhe($event)" (desativar)="desativar($event)" />
    </section>

    <section>
      <div class="section-header">
        <h2 class="titulo-recolhivel" (click)="toggleInativas()">
          <span class="ind">{{ inativasAbertas() ? '▼' : '▶' }}</span>
          Comunicações Inativas@if (totalInativas() !== null) { ({{ totalInativas() }}) }
        </h2>
      </div>
      @if (inativasAbertas()) {
        <app-comunicacoes-tabela [ctrl]="ctrlInativas" [statusDaTabela]="INATIVOS"
                                 vazio="Nenhuma comunicação inativa."
                                 (abrir)="abrirDetalhe($event)" (desativar)="desativar($event)" />
      }
    </section>
  `,
  styles: [`
    section { margin-bottom: 28px; }
    .cards-aviso { margin: 16px 0 8px; }
    /* especificidade > .duracao-inline input{width:90px}: mantém o campo "Duração" em 100% (a global .form-row input não venceria) */
    .form-row input[type="number"] { width:100%; }
    .form-row textarea { resize: vertical; }
    .req { color:#dc2626; }
    .msg-actions { display:flex; gap:8px; margin-bottom:4px; }
    .radio-row { display:flex; align-items:center; gap:18px; flex-wrap:wrap; }
    .duracao-inline { display:flex; align-items:center; gap:8px; }
    .duracao-inline label { margin:0; white-space:nowrap; }
    .duracao-inline input { width:90px; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
    .titulo-recolhivel { cursor: pointer; user-select: none; display: flex; align-items: center; gap: 8px; }
    .titulo-recolhivel .ind { font-size: .8rem; color: var(--muted); }
  `],
})
export class AdminAvisosSalaComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);
  lookup = inject(LookupService);

  readonly MAX_MENSAGENS = 10;

  /** Card de seleção ativo (1 por vez; reclicar oculta). Verificação abre por padrão (o form já existente). */
  activeCard = signal<'verificacao' | 'escala' | 'agenda' | 'pessoal' | null>('verificacao');

  // ── Form Verificação (inline, intocado) ──
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

  // ── Listagem: duas tabelas sobre o mesmo endpoint, separadas pelo status ──
  protected readonly ATIVOS = STATUS_ATIVOS;
  protected readonly INATIVOS = STATUS_INATIVOS;

  ctrlAtivas = this.criarCtrl(STATUS_ATIVOS);
  ctrlInativas = this.criarCtrl(STATUS_INATIVOS);

  /** As inativas nascem recolhidas: quem abre a página quer ver o que está no ar. */
  inativasAbertas = signal(false);
  /** Quantas inativas existem — pedido só pelo total, para o título saber o número sem carregar a tabela. */
  totalInativas = signal<number | null>(null);
  private inativasCarregadas = false;

  private criarCtrl(status: string[]): TableStateController<AvisoRow> {
    const ctrl = new TableStateController<AvisoRow>(this.api, {
      endpoint: '/api/admin/avisos/list', defaultSort: 'data', defaultDir: 'desc',
    });
    ctrl.filters['status'] = { values: status };
    return ctrl;
  }

  ngOnInit(): void {
    if (this.lookup.salas().length === 0) this.lookup.loadSalas();
    this.loadSalasOcupadas();
    this.ctrlAtivas.load();
    this.carregarTotalInativas();
  }

  /** Abre e fecha a lista de inativas; os dados só são buscados na primeira abertura. */
  toggleInativas(): void {
    const abrir = !this.inativasAbertas();
    this.inativasAbertas.set(abrir);
    if (abrir && !this.inativasCarregadas) {
      this.inativasCarregadas = true;
      this.ctrlInativas.load();
    }
  }

  /**
   * Só o total das inativas (1 linha pedida). Falha silenciosa: o título fica sem o número, e a
   * tabela — que é o que importa — continua abrindo.
   */
  private carregarTotalInativas(): void {
    this.api.getList('/api/admin/avisos/list', {
      page: 1, limit: 1, filters: { status: { values: STATUS_INATIVOS } },
    }).subscribe({
      next: r => this.totalInativas.set(r.meta?.total ?? 0),
      error: () => this.totalInativas.set(null),
    });
  }

  /** Recarrega o que estiver à vista depois de uma mudança de status. */
  private recarregarListagens(): void {
    this.ctrlAtivas.load();
    this.carregarTotalInativas();
    if (this.inativasCarregadas) this.ctrlInativas.load();
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
          'Não foi possível concluir a operação.'));
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
          this.onCadastrado();
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

  /** Abre o card; reclicar no ativo fecha (acordeão de 1 aberto). O estado dos forms fica no
   *  componente/sub-componentes, então trocar de card não descarta rascunho já digitado. */
  toggleCard(card: 'verificacao' | 'escala' | 'agenda' | 'pessoal'): void {
    this.activeCard.update(cur => (cur === card ? null : card));
  }

  /** Um sub-painel (Escala/Agenda/Pessoal) cadastrou um aviso → recarrega a listagem do topo. */
  onCadastrado(): void {
    this.ctrlAtivas.state.page = 1;
    this.ctrlAtivas.load();
  }

  abrirDetalhe(a: AvisoRow): void {
    this.router.navigate(['/admin/aviso/detalhe'], { queryParams: { id: a.id } });
  }

  desativar(a: AvisoRow): void {
    if (!confirm(`Desativar o cadastro nº ${a.numero}?`)) return;
    this.api.patch(`/api/admin/avisos/${a.id}/desativar`, {}).subscribe({
      next: () => { this.toast.success('Aviso desativado.'); this.recarregarListagens(); },
      error: err => this.toast.error(httpErrorMsg(err, 'Erro ao desativar.', ['message'])),
    });
  }
}

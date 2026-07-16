import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { ApiService } from '../../core/services/api.service';
import { FeatureToggleDirective } from '../../shared/directives/feature-toggle.directive';
import { PaginationMeta } from '../../core/models/user.model';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef } from '../../shared/components/column-filter.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { getDistinct, buildReportParams } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { erroCargaMsg } from '../../core/helpers/http.helpers';
import { truncate, formatEvento as formatEventoDe } from '../../core/helpers/format.helpers';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

interface OperadorTurno { id: string; nome: string; turno: string; }
interface EscalaResumoItem { sala_nome: string; operadores: string; operadores_ids: string[]; operadores_detalhe?: OperadorTurno[]; }
interface EscalaResumoRow { left: EscalaResumoItem; right: EscalaResumoItem | null; }
interface EscalaFuncoes { apoio: EscalaResumoItem | null; fechamento: EscalaResumoItem | null; }

const FUNCAO_LABELS = {
  apoio: 'Apoio às Comissões',
  fechamento: 'Fechamento dos Plenários',
} as const;

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, PaginationComponent, ColumnFilterComponent, ErroCargaComponent, FmtDatePipe, FmtTimePipe, FeatureToggleDirective],
  template: `
    <h1>Página Principal</h1>

    <div class="grid-cards">
      <a routerLink="/checklist" class="card-custom card-stack">
        <strong>Formulário de Verificação de Plenários</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <a routerLink="/operacao" class="card-custom card-stack">
        <strong>Registro de Operação de Áudio</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <a routerLink="/agenda" class="card-custom card-stack">
        <strong>Agenda Legislativa</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <a [featureToggle]="'pontoBanco'" #fPonto="featureToggle"
         [routerLink]="fPonto.enabled() ? '/ponto' : null" class="card-custom card-stack">
        <strong>Ponto e Banco</strong>
        <span class="text-muted-sm">{{ fPonto.enabled() ? 'Abrir' : 'Indisponível' }}</span>
      </a>
      @if (auth.isAdmin()) {
        <a routerLink="/admin" class="card-custom card-stack card-admin">
          <strong>Painel Administrativo</strong>
          <span class="text-muted-sm">Voltar para Admin</span>
        </a>
      }
    </div>

    <!-- ═══ Escala Semanal ═══ -->
    <section class="escala-section">
      <h2>Escala</h2>

      @if (escalaLoading() && escalas().length === 0) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (erroEscala()) {
        <!-- Canal de erro (C18/F65): a carga que falhou NÃO pode se passar por "Nenhuma escala
             cadastrada." — o operador concluiria que não está escalado, o oposto de "não sabemos".
             Meta limpo no erro: a paginação não exibe um total que a tela não tem. -->
        <app-erro-carga [mensagem]="erroEscala()" (tentarNovamente)="loadEscalas()" />
      } @else if (escalas().length === 0) {
        <p class="text-muted-sm">Nenhuma escala cadastrada.</p>
      } @else {
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width:30px"></th>
                <th>Período</th>
                <th>Criado em</th>
              </tr>
            </thead>
            <tbody>
              @for (esc of escalas(); track esc['id']) {
                <tr class="escala-row" (click)="toggleEscala(esc)">
                  <td>{{ esc['_expanded'] ? '\u25BC' : '\u25B6' }}</td>
                  <td><strong>{{ esc['data_inicio'] | fmtDate }} — {{ esc['data_fim'] | fmtDate }}</strong></td>
                  <td>{{ esc['criado_em'] | fmtDate }}</td>
                </tr>
                @if (esc['_expanded']) {
                  <tr class="accordion-row">
                    <td colspan="3">
                      @if (esc['_erroResumo']) {
                        <!-- Erro POR LINHA (C18/F65): a falha do resumo não pode virar "Nenhum operador
                             escalado." — e como nada é gravado em _resumoRows no erro, reabrir refaz o GET
                             (morreu o vazio sticky). O retry recarrega SÓ esta escala. -->
                        <app-erro-carga [mensagem]="esc['_erroResumo']" (tentarNovamente)="carregarResumoEscala(esc)" />
                      } @else if (!esc['_resumoRows']) {
                        <p class="text-muted-sm">Carregando...</p>
                      } @else if (asEscalaRows(esc['_resumoRows']).length === 0) {
                        <p class="text-muted-sm">Nenhum operador escalado.</p>
                      } @else {
                        <table class="sub-table escala-sub-table">
                          <thead>
                            <tr>
                              <th rowspan="2">Sala</th><th colspan="2">Operadores</th>
                              <th rowspan="2" class="td-half">Sala</th><th colspan="2">Operadores</th>
                            </tr>
                            <tr>
                              <th>Manhã</th><th class="td-tarde">Tarde</th>
                              <th>Manhã</th><th class="td-tarde">Tarde</th>
                            </tr>
                          </thead>
                          <tbody>
                            @for (row of asEscalaRows(esc['_resumoRows']); track $index) {
                              <tr>
                                <td><strong>{{ row.left.sala_nome }}</strong></td>
                                <td>
                                  @for (o of porTurno(row.left, 'M'); track o.id; let last = $last) {
                                    <span [class.operador-destaque]="ehUsuarioId(o.id)">{{ o.nome }}</span>@if (!last) {<span>, </span>}
                                  }
                                </td>
                                <td class="td-tarde">
                                  @for (o of porTurno(row.left, 'V'); track o.id; let last = $last) {
                                    <span [class.operador-destaque]="ehUsuarioId(o.id)">{{ o.nome }}</span>@if (!last) {<span>, </span>}
                                  }
                                </td>
                                @if (row.right) {
                                  <td class="td-half"><strong>{{ row.right.sala_nome }}</strong></td>
                                  <td>
                                    @for (o of porTurno(row.right!, 'M'); track o.id; let last = $last) {
                                      <span [class.operador-destaque]="ehUsuarioId(o.id)">{{ o.nome }}</span>@if (!last) {<span>, </span>}
                                    }
                                  </td>
                                  <td class="td-tarde">
                                    @for (o of porTurno(row.right!, 'V'); track o.id; let last = $last) {
                                      <span [class.operador-destaque]="ehUsuarioId(o.id)">{{ o.nome }}</span>@if (!last) {<span>, </span>}
                                    }
                                  </td>
                                } @else {
                                  <td class="td-half"></td><td></td><td class="td-tarde"></td>
                                }
                              </tr>
                            }
                          </tbody>
                        </table>
                        @if (temFuncoes(esc)) {
                          <table class="sub-table escala-sub-table escala-funcoes-table">
                            <thead><tr>
                              <th>Apoio às Comissões</th>
                              <th>Fechamento dos Plenários</th>
                            </tr></thead>
                            <tbody>
                              <tr>
                                <td>
                                  @if (asFuncoes(esc['_funcoes'])?.apoio; as item) {
                                    @for (nome of splitNomes(item.operadores); track $index; let last = $last) {
                                      <span [class.operador-destaque]="ehUsuarioLogado(item, $index)">{{ nome }}</span>@if (!last) {<span>, </span>}
                                    }
                                  }
                                </td>
                                <td>
                                  @if (asFuncoes(esc['_funcoes'])?.fechamento; as item) {
                                    @for (nome of splitNomes(item.operadores); track $index; let last = $last) {
                                      <span [class.operador-destaque]="ehUsuarioLogado(item, $index)">{{ nome }}</span>@if (!last) {<span>, </span>}
                                    }
                                  }
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        }
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <app-pagination [meta]="escalaMeta()!"
          (pageChange)="escalaState.page = $event; loadEscalas()"
          (limitChange)="escalaState.limit = $event; escalaState.page = 1; loadEscalas()" />
      }
    </section>

    <!-- ═══ Meus Checklists ═══ -->
    <section>
      <div class="section-header">
        <h2>Verificação de Salas</h2>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th><app-column-filter [col]="chkCols[0]" [distinctValues]="gd(chkCtrl.meta(),'sala')" [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction" (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="chkCols[1]" [distinctValues]="gd(chkCtrl.meta(),'data')" [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction" (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" /></th>
              <th style="text-align:center"><span class="sort-header" (click)="chkCtrl.onSort({sort:'qtde_ok', direction: chkCtrl.state.sort==='qtde_ok' && chkCtrl.state.direction==='asc' ? 'desc' : 'asc'})">Qtde. OK <span class="sort-arrow">{{ chkCtrl.state.sort==='qtde_ok' ? (chkCtrl.state.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><span class="sort-header" (click)="chkCtrl.onSort({sort:'qtde_falha', direction: chkCtrl.state.sort==='qtde_falha' && chkCtrl.state.direction==='asc' ? 'desc' : 'asc'})">Qtde. Falha <span class="sort-arrow">{{ chkCtrl.state.sort==='qtde_falha' ? (chkCtrl.state.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th>Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (chkCtrl.loading()) {
              <tr><td colspan="5" class="empty-state">Carregando verificações...</td></tr>
            } @else if (chkCtrl.erro()) {
              <!-- Canal de erro (C7/C13b): a carga que falhou NÃO pode se passar por "nenhuma
                   verificação" — cada tabela tem o seu canal, e o rodapé não mente (meta limpo). -->
              <tr><td colspan="5">
                <app-erro-carga [mensagem]="chkCtrl.erro()" (tentarNovamente)="chkCtrl.load()" />
              </td></tr>
            } @else if (chkCtrl.rows().length === 0) {
              <tr><td colspan="5" class="empty-state">Nenhuma verificação encontrada.</td></tr>
            } @else {
              @for (chk of chkCtrl.rows(); track chk['id']) {
                <tr>
                  <td><strong>{{ chk['sala_nome'] }}</strong></td>
                  <td>{{ chk['data'] | fmtDate }}</td>
                  <td [style.color]="intVal(chk, 'qtde_ok') > 0 ? 'var(--color-green)' : '#334155'"
                      style="text-align:center; font-weight:bold">{{ chk['qtde_ok'] || 0 }}</td>
                  <td [style.color]="intVal(chk, 'qtde_falha') > 0 ? 'var(--color-red)' : '#334155'"
                      style="text-align:center; font-weight:bold">{{ chk['qtde_falha'] || 0 }}</td>
                  <td><button class="btn-xs" (click)="openChecklist(chk)">Formulário</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="table-footer">
        <button class="btn-report" (click)="gerarRelatorioChecklists()">Gerar Relatório</button>
        <app-pagination [meta]="chkCtrl.meta()!" (pageChange)="chkCtrl.onPage($event)" (limitChange)="chkCtrl.onLimit($event)" />
      </div>
    </section>

    <!-- ═══ Minhas Operações ═══ -->
    <section>
      <div class="section-header">
        <h2>Registros de Operação de Áudio</h2>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th><app-column-filter [col]="opCols[0]" [distinctValues]="gd(opCtrl.meta(),'sala')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="opCols[1]" [distinctValues]="gd(opCtrl.meta(),'data')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th>Evento</th>
              <th style="text-align:center"><span class="sort-header" (click)="opCtrl.onSort({sort:'hora_entrada', direction: opCtrl.state.sort==='hora_entrada' && opCtrl.state.direction==='asc' ? 'desc' : 'asc'})">Início Operação <span class="sort-arrow">{{ opCtrl.state.sort==='hora_entrada' ? (opCtrl.state.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><span class="sort-header" (click)="opCtrl.onSort({sort:'hora_saida', direction: opCtrl.state.sort==='hora_saida' && opCtrl.state.direction==='asc' ? 'desc' : 'asc'})">Fim Operação <span class="sort-arrow">{{ opCtrl.state.sort==='hora_saida' ? (opCtrl.state.direction==='asc' ? '\u25B2' : '\u25BC') : '\u25BD' }}</span></span></th>
              <th style="text-align:center"><app-column-filter [col]="opCols[2]" [distinctValues]="gd(opCtrl.meta(),'anormalidade')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th>Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (opCtrl.loading()) {
              <tr><td colspan="7" class="empty-state">Carregando operações...</td></tr>
            } @else if (opCtrl.erro()) {
              <tr><td colspan="7">
                <app-erro-carga [mensagem]="opCtrl.erro()" (tentarNovamente)="opCtrl.load()" />
              </td></tr>
            } @else if (opCtrl.rows().length === 0) {
              <tr><td colspan="7" class="empty-state">Nenhuma operação encontrada.</td></tr>
            } @else {
              @for (op of opCtrl.rows(); track op['id'] || op['entrada_id']) {
                <tr>
                  <td><strong>{{ op['sala'] || op['sala_nome'] }}</strong></td>
                  <td>{{ op['data'] | fmtDate }}</td>
                  <td [title]="formatEvento(op)">{{ truncate(formatEvento(op), 30) }}</td>
                  <td style="text-align:center">{{ op['hora_entrada'] | fmtTime }}</td>
                  <td style="text-align:center">{{ op['hora_saida'] | fmtTime }}</td>
                  <td style="text-align:center">
                    @if (op['anormalidade'] || op['houve_anormalidade']) {
                      @if (op['anormalidade_id']) {
                        <button class="btn-xs btn-anom-sim" (click)="openAnormalidade(op)">SIM</button>
                      } @else {
                        <span class="badge-falha" style="font-weight:700">SIM</span>
                      }
                    } @else {
                      <span class="badge-ok">Não</span>
                    }
                  </td>
                  <td><button class="btn-xs" (click)="openOperacao(op)">Formulário</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="table-footer">
        <button class="btn-report" (click)="gerarRelatorioOperacoes()">Gerar Relatório</button>
        <app-pagination [meta]="opCtrl.meta()!" (pageChange)="opCtrl.onPage($event)" (limitChange)="opCtrl.onLimit($event)" />
      </div>
    </section>
  `,
  styles: [`
    .table-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 8px;
    }
    .grid-cards {
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 16px;
      margin-bottom: 32px;
    }
    section { margin-bottom: 32px; }
    .sort-header {
      cursor: pointer; user-select: none; white-space: nowrap;
      &:hover { color: var(--primary); }
    }
    .sort-arrow { font-size: .7rem; }
    .btn-anom-sim {
      background: #fef2f2;
      border-color: #fca5a5;
      color: #b91c1c;
      font-weight: 700;
      &:hover { background: #fee2e2; }
    }
    .escala-section { margin-bottom: 32px; }
    .escala-row { cursor: pointer; }
    .escala-row:hover { background: var(--bg-hover, #f1f5f9); }
    .operador-destaque { font-weight: bold; color: var(--color-blue, #003b63); }
    .escala-sub-table {
      th, td { padding: 6px 12px; }
      thead th { text-align: center; }
      th.td-half, td.td-half { border-left: 2px solid var(--border); }
      th.td-tarde, td.td-tarde { border-left: 1px solid var(--border); }
    }
    .escala-funcoes-table {
      margin-top: 12px;
      th, td { width: 50%; text-align: center; }
      th:nth-child(2), td:nth-child(2) { border-left: 2px solid var(--border); }
      th:nth-child(3), td:nth-child(3) { border-left: none; }
    }
  `],
})
export class HomeComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(ApiService);

  // ── Column defs ──
  chkCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Sala', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  opCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Sala', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'anormalidade', label: 'Anormalidade?', type: 'text' },
  ];

  // ── Checklists state ──
  chkCtrl = new TableStateController(this.api, {
    endpoint: '/api/operador/meus-checklists', defaultSort: 'data', defaultDir: 'desc',
  });

  // ── Escala state ──
  escalas = signal<Record<string, any>[]>([]);
  escalaMeta = signal<PaginationMeta | null>(null);
  escalaState = { page: 1, limit: 10 };
  escalaLoading = signal(true);
  /** Canal de erro da carga da Escala (C18/F65): '' = sem erro. Limpo no início de cada carga. */
  erroEscala = signal('');
  /**
   * Token de recência (C18/F65 — a faceta F61 viva fora do motor): a Escala é paginada server-side
   * e dois cliques rápidos de página deixam duas cargas em voo — sem o token, a resposta VELHA
   * vencia se chegasse por último. Guard no `next` E no `error`.
   */
  private seqEscala = 0;

  // ── Operações state ──
  opCtrl = new TableStateController(this.api, {
    endpoint: '/api/operador/minhas-operacoes', defaultSort: 'data', defaultDir: 'desc',
  });

  ngOnInit(): void {
    if (!this.auth.user()) {
      this.auth.whoAmI().subscribe(() => { this.loadEscalas(); this.chkCtrl.load(); this.opCtrl.load(); });
    } else {
      this.loadEscalas();
      this.chkCtrl.load();
      this.opCtrl.load();
    }
  }

  // ── Checklists ──

  openChecklist(chk: Record<string, unknown>): void {
    if (chk['id']) window.open(`/checklist/edit?checklist_id=${chk['id']}`, '_blank');
  }

  gerarRelatorioChecklists(): void {
    this.gerarRelatorio(this.chkCtrl, '/api/operador/meus-checklists/relatorio');
  }

  private gerarRelatorio(ctrl: TableStateController, endpoint: string): void {
    this.api.openPdfInline(endpoint, buildReportParams('pdf', ctrl.state.sort, ctrl.state.direction, undefined, ctrl.filters));
  }

  // ── Operações ──

  openOperacao(op: Record<string, unknown>): void {
    const id = op['id'] || op['entrada_id'];
    if (id) window.open(`/operacao/edit?entrada_id=${id}`, '_blank');
  }

  gerarRelatorioOperacoes(): void {
    this.gerarRelatorio(this.opCtrl, '/api/operador/minhas-operacoes/relatorio');
  }

  openAnormalidade(op: Record<string, unknown>): void {
    if (op['anormalidade_id']) window.open(`/anormalidade/detalhe?id=${op['anormalidade_id']}`, '_blank');
  }

  // ── Escala ──

  /** Carga da listagem de escalas; também é o retry da caixa de erro (C18/F65). */
  loadEscalas(): void {
    const seq = ++this.seqEscala;
    this.escalaLoading.set(true);
    this.erroEscala.set('');
    this.api.getList('/api/escala/list', this.escalaState).subscribe({
      next: (res: any) => {
        if (seq !== this.seqEscala) return;   // obsoleta: uma carga mais nova está em voo
        this.escalas.set(res.data || []);
        this.escalaMeta.set(res.meta || null);
        this.escalaLoading.set(false);
      },
      error: err => {
        if (seq !== this.seqEscala) return;   // a falha velha não apaga o que a carga nova trouxe
        this.escalas.set([]);
        this.escalaMeta.set(null);
        this.escalaLoading.set(false);
        this.erroEscala.set(erroCargaMsg(err,
          'Não foi possível carregar a Escala. Você pode estar escalado mesmo sem ela aparecer aqui — tente novamente.'));
      },
    });
  }

  toggleEscala(esc: Record<string, any>): void {
    esc['_expanded'] = !esc['_expanded'];
    // No erro nada é gravado em `_resumoRows` (C18/F65): reabrir o acordeão volta a tentar.
    if (esc['_expanded'] && !esc['_resumoRows']) this.carregarResumoEscala(esc);
  }

  /** GET do resumo de UMA escala — também é o retry da caixa de erro da linha (C18/F65). */
  carregarResumoEscala(esc: Record<string, any>): void {
    // Recência POR LINHA: o retry reclicado põe duas cargas da MESMA escala em voo — um erro velho
    // não pode sobrescrever o sucesso mais novo (nem o contrário). O token vive no próprio objeto,
    // que é a identidade da linha.
    const seq = (esc['_seqResumo'] = (((esc['_seqResumo'] as number) || 0) + 1));
    esc['_erroResumo'] = '';
    this.escalas.set([...this.escalas()]);
    this.api.get<any>(`/api/escala/${esc['id']}`).subscribe({
      next: (res: any) => {
        if (seq !== esc['_seqResumo']) return;
        const resumo: EscalaResumoItem[] = res.data?.resumo || [];
        // Separar plenários (qualquer item que NÃO seja função) das duas funções
        const plenarios = resumo.filter(r =>
          r.sala_nome !== FUNCAO_LABELS.apoio && r.sala_nome !== FUNCAO_LABELS.fechamento);
        const funcoes: EscalaFuncoes = {
          apoio: resumo.find(r => r.sala_nome === FUNCAO_LABELS.apoio) || null,
          fechamento: resumo.find(r => r.sala_nome === FUNCAO_LABELS.fechamento) || null,
        };
        // Dividir os plenários em 2 metades para layout lado a lado
        const half = Math.ceil(plenarios.length / 2);
        const rows: EscalaResumoRow[] = [];
        for (let i = 0; i < half; i++) {
          rows.push({ left: plenarios[i], right: plenarios[i + half] || null });
        }
        esc['_resumoRows'] = rows;
        esc['_funcoes'] = funcoes;
        this.escalas.set([...this.escalas()]);
      },
      error: err => {
        if (seq !== esc['_seqResumo']) return;
        esc['_erroResumo'] = erroCargaMsg(err, 'Não foi possível carregar os operadores desta escala.');
        this.escalas.set([...this.escalas()]);
      },
    });
  }

  asFuncoes(v: unknown): EscalaFuncoes | null {
    if (v && typeof v === 'object' && ('apoio' in v || 'fechamento' in v)) return v as EscalaFuncoes;
    return null;
  }

  temFuncoes(esc: Record<string, any>): boolean {
    const f = this.asFuncoes(esc['_funcoes']);
    return !!f && (!!f.apoio || !!f.fechamento);
  }

  asEscalaRows(v: unknown): EscalaResumoRow[] { return Array.isArray(v) ? v : []; }

  splitNomes(operadores: string): string[] {
    return (operadores || '').split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  ehUsuarioLogado(item: EscalaResumoItem, idx: number): boolean {
    const userId = this.auth.user()?.id;
    return !!userId && item.operadores_ids?.[idx] === userId;
  }

  /** Operadores de um plenário filtrados por turno ('M'/'V') — para as colunas Manhã/Tarde. */
  porTurno(item: EscalaResumoItem, turno: string): OperadorTurno[] {
    return (item?.operadores_detalhe || []).filter(o => o.turno === turno);
  }

  ehUsuarioId(id: string): boolean {
    const userId = this.auth.user()?.id;
    return !!userId && id === userId;
  }

  // ── Helpers ──

  intVal(obj: Record<string, unknown>, key: string): number {
    const v = obj[key];
    return typeof v === 'number' ? v : parseInt(String(v || '0'), 10);
  }

  truncate = truncate;
  formatEvento = (op: Record<string, unknown>): string => formatEventoDe(op, 'nome_evento');

  gd = getDistinct;
}

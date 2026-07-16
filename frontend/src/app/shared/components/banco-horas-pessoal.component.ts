import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { getDistinct } from '../../core/helpers/table.helpers';
import { formatarSaldoMin } from '../../core/helpers/format.helpers';
import { formatarDataBr, toISODate } from '../../core/helpers/date.helpers';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';
import { ColumnFilterComponent, ColumnFilterDef } from './column-filter.component';
import { ErroCargaComponent } from './erro-carga.component';
import { PaginationComponent } from './pagination.component';
import { MiniCalendarioComponent, DiaEstado } from './mini-calendario.component';
import { MesAnoSelectorComponent, MesAno, anosNavegaveis } from './mes-ano-selector.component';
import { ToastService } from './toast.component';
import { FmtDatePipe } from '../pipes/fmt-date.pipe';

/**
 * Único erro FATAL do GET /api/ponto/banco: o gate de carga horária (Q17) — NULL ou fora de
 * {30, 40} — responde **409** (`BancoHorasService.cargaObrigatoria`). Sem jornada válida não
 * existe banco de horas, e nenhum retry resolve: só a Gestão de Pessoas. Todo o resto (rede,
 * timeout, 5xx) é TRANSITÓRIO e não pode matar o card (F44).
 */
const HTTP_CONFLITO = 409;

/** Guia da tela na falha TRANSITÓRIA da carga do mês (F44) — o detalhe do backend vem anexado. */
const GUIA_CARGA =
  'Não foi possível carregar o seu banco de horas deste mês. Sem ele não dá para marcar dias — ' +
  'tente novamente ou escolha outro mês.';

/** Fallback do erro FATAL: a mensagem do Q17 vem do backend; isto só cobre um 409 sem corpo. */
const MSG_BLOQUEIO = 'Erro ao carregar o banco de horas.';

/** Guia da tela na falha da tabela "Minhas Solicitações" (canal de erro do C7). */
const GUIA_SOLICITACOES =
  'Não foi possível carregar as suas solicitações de folga. Pode haver pedidos pendentes ou já ' +
  'aprovados que não aparecem aqui.';

/** GET /api/ponto/banco — saldo + situação do mês pedido (E7). */
interface BancoInfo {
  saldo_min: number;
  saldo_fmt: string;
  sem_folha_oficial?: boolean;
  carga_horaria: number;
  folgas_mes: number;
  dias_bloqueados: { data: string; motivo: string }[];
}

interface SolicitacaoRow {
  id: string;
  data_folga: string;
  status: 'PENDENTE' | 'APROVADO' | 'REJEITADO' | 'CANCELADO';
  deliberado_por?: string;
  motivo?: string;
}

/**
 * Banco de Horas pessoal (Bloco C / E7) — shared: consumido pelo card "Banco
 * de horas" do /ponto (operador/técnico) e, no E9, pelo /admin/ponto (admin
 * com folha). Ordem C-2: saldo + Solicitar/Cancelar → seletor mês/ano →
 * Folgas → calendário multi (retângulo-×, decremento visual, C-3) → tabela
 * "Minhas Solicitações" (C-4). Dono resolvido pelo backend via principal.
 */
@Component({
  selector: 'app-banco-horas-pessoal',
  standalone: true,
  imports: [ColumnFilterComponent, ErroCargaComponent, PaginationComponent, MiniCalendarioComponent,
    MesAnoSelectorComponent, FmtDatePipe],
  template: `
    @if (erroBloqueio()) {
      <!-- FATAL (Q17 → 409): sem CARGA_HORARIA válida não há banco de horas e nenhum retry
           resolve — só a Gestão de Pessoas. É o ÚNICO caso que substitui o card (F44). -->
      <div class="error-box">{{ erroBloqueio() }}</div>
    } @else {
      <!-- C-2.1: saldo + ações (aparecem só com ≥1 dia marcado — C-5.1). Sem payload aplicado
           não há saldo na tela: uma carga que falhou não pode virar um "+00:00" convincente. -->
      @if (dados(); as d) {
        <div class="saldo-row">
          <span class="saldo-label">Saldo:
            <strong class="saldo-valor" [class.negativo]="saldoVisualMin() < 0">{{ saldoVisualFmt() }}</strong>
          </span>
          @if (recarregando()) {
            <!-- F45: o saldo na tela ainda é o do payload anterior enquanto a recarga voa -->
            <span class="text-muted-sm">(atualizando...)</span>
          }
          @if (d.sem_folha_oficial) {
            <span class="text-muted-sm">(sem folha de ponto oficial — saldo de abertura zerado)</span>
          }
          @if (selecionados().size > 0) {
            <span class="saldo-acoes">
              <button class="btn-primary-custom" [disabled]="enviando()" (click)="solicitar()">
                {{ enviando() ? 'Enviando...' : 'Solicitar' }}
              </button>
              <button class="btn-outline" [disabled]="enviando()" (click)="limparSelecao()">Cancelar</button>
            </span>
          }
        </div>
      }

      <!-- C-2.2: seletor mês/ano (relógio local — T-3.1; [F37]: em janeiro alcança dezembro, o
           mês da folha recém-publicada). NUNCA sai do DOM: junto com o retry da caixa de erro, é
           um dos dois gatilhos de nova carga — e o pai mantém este componente montado em
           [hidden], então fechar/reabrir o card não o remontaria (F44). -->
      <app-mes-ano-selector [anos]="anosSeletor" (mudou)="onMesAno($event)" />

      @if (erroCarga()) {
        <!-- TRANSITÓRIO (rede/timeout/5xx): caixa DENTRO do card, com retry (F44) -->
        <app-erro-carga [mensagem]="erroCarga()" (tentarNovamente)="carregarBanco()" />
      }

      @if (carregando()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (dados()) {
        <!-- C-2.3: folgas APROVADAS do mês exibido (Q13) -->
        <p class="folgas">Folgas: <strong>{{ dados()?.folgas_mes ?? 0 }}</strong></p>

        @if (erroAcao()) { <div class="error-box">{{ erroAcao() }}</div> }

        <!-- C-2.4: calendário 7×6 com retângulo-× (C-3.2); mês travado no seletor. Enquanto a
             recarga voa, o estadoDia desabilita todo dia (F45) — nada de clique sobre o payload
             velho, que produziria um 400 logo depois do toast de sucesso. -->
        <div class="cal-wrap">
          <app-mini-calendario
            [multiSelecao]="true"
            [estadoDia]="estadoDia"
            [valorSelecionado]="primeiroDiaMes()"
            [min]="primeiroDiaMes()"
            [max]="ultimoDiaMes()"
            (dataSelecionada)="toggleDia($event)" />
        </div>
      }

      <!-- C-2.5: tabela "Minhas Solicitações" (layout das tabelas da home — C-4.1). Carga própria:
           não some quando o GET do banco falha (e vice-versa). -->
      <section>
        <div class="section-header"><h2>Minhas Solicitações</h2></div>
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th><app-column-filter [col]="cols[0]" [distinctValues]="gd(ctrl.meta(), 'data_folga')"
                      [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                      (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
                <th><app-column-filter [col]="cols[1]" [distinctValues]="gd(ctrl.meta(), 'status')"
                      [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                      (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
                <th>Deliberado por</th>
                <th>Motivo</th>
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              @if (ctrl.loading()) {
                <tr><td colspan="5" class="empty-state">Carregando solicitações...</td></tr>
              } @else if (ctrl.erro()) {
                <!-- Canal de erro (C7): a leitura que falhou NÃO pode se passar por "nenhuma
                     solicitação" — o usuário pediria de novo um dia que já tem pedido vivo. -->
                <tr><td colspan="5">
                  <app-erro-carga [mensagem]="ctrl.erro()" (tentarNovamente)="ctrl.load()" />
                </td></tr>
              } @else if (ctrl.rows().length === 0) {
                <tr><td colspan="5" class="empty-state">Nenhuma solicitação registrada.</td></tr>
              } @else {
                @for (r of ctrl.rows(); track r.id) {
                  <tr>
                    <td>{{ r.data_folga | fmtDate }}</td>
                    <td><span class="st" [attr.data-st]="r.status">{{ statusLabel(r.status) }}</span></td>
                    <td>{{ r.deliberado_por || '—' }}</td>
                    <td>{{ r.motivo || '—' }}</td>
                    <td>
                      @if (r.status === 'PENDENTE') {
                        <button class="btn-xs" [disabled]="cancelando()" (click)="cancelarSolicitacao(r)">Cancelar</button>
                      } @else { — }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <div class="table-footer">
          <app-pagination [meta]="ctrl.meta()!" (pageChange)="ctrl.onPage($event)" (limitChange)="ctrl.onLimit($event)" />
        </div>
      </section>
    }
  `,
  styles: [`
    .saldo-row {
      display:flex; align-items:center; gap:14px; flex-wrap:wrap; margin-bottom:10px;
    }
    .saldo-label { font-size:1.05rem; }
    .saldo-valor { font-variant-numeric: tabular-nums; }
    .saldo-valor.negativo { color: var(--color-red); }
    .saldo-acoes { display:flex; gap:8px; margin-left:auto; }
    .folgas { margin:0 0 10px; text-align:center; }
    .cal-wrap { display:flex; justify-content:center; margin-bottom:18px; }
    section { margin-top:8px; }
    .st { font-weight:600; }
    .st[data-st="APROVADO"]  { color: var(--color-blue); }
    .st[data-st="REJEITADO"] { color: var(--color-red); }
    .st[data-st="CANCELADO"] { color: #9ca3af; }
    .table-footer {
      display:flex; align-items:center; justify-content:flex-end;
      gap:12px; flex-wrap:wrap; margin-top:10px;
    }
    @media (max-width: 640px) {
      .saldo-acoes { margin-left:0; width:100%; }
      .saldo-acoes button { flex:1; }
    }
  `],
})
export class BancoHorasPessoalComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  dados = signal<BancoInfo | null>(null);

  /** GET /api/ponto/banco em voo — vale para TODA recarga (troca de mês, retry, pós-mutação). */
  private cargaEmVoo = signal(true);
  /** Carga em voo SEM nada para exibir (1ª carga ou seu retry): o miolo do card diz "Carregando...". */
  carregando = computed(() => this.cargaEmVoo() && !this.dados());
  /** Recarga sobre um payload já exibido: o saldo na tela é o ANTERIOR até a resposta chegar (F45). */
  recarregando = computed(() => this.cargaEmVoo() && !!this.dados());

  /**
   * Erro FATAL — só o gate de carga horária do backend (Q17 → 409). Substitui o card inteiro:
   * sem jornada válida não há banco de horas, e nenhum retry do usuário resolve.
   */
  erroBloqueio = signal('');
  /**
   * Erro TRANSITÓRIO da carga do mês (rede/timeout/5xx) — canal SEPARADO do fatal (F44): vira
   * caixa com retry DENTRO do card, sem derrubar o seletor de mês.
   */
  erroCarga = signal('');
  /** Erro das ações de solicitação (ex.: 400 amigável do backend). */
  erroAcao = signal('');
  enviando = signal(false);
  cancelando = signal(false);

  /**
   * Token de recência das cargas (F43): só a resposta do pedido MAIS RECENTE é aplicada. O guard
   * antigo comparava ano/mês do pedido com o exibido — o que descarta a resposta de um mês
   * abandonado, mas aceita as DUAS respostas de dois pedidos do MESMO mês (cancelar duas
   * solicitações em sequência já basta): a mais velha chegava por último e vencia, deixando o
   * saldo defasado e um dia já liberado ainda "bloqueado".
   */
  private seqCarga = 0;

  private hoje = new Date();
  /** Mês/ano exibidos ({ano, mes} do seletor — inicia no mês corrente, como o seletor). */
  anoMes = signal<MesAno>({ ano: this.hoje.getFullYear(), mes: this.hoje.getMonth() + 1 });
  /** Anos ofertados no seletor (F37/C14): a virada do ano só se abre em dezembro e janeiro. */
  readonly anosSeletor = anosNavegaveis(this.hoje);

  /** Mês/ano cuja resposta do GET está aplicada em `dados`. Zerado no disparo de TODA recarga
   *  (F45) — enquanto não houver payload do mês exibido, o calendário fica desabilitado. */
  private mesCarregado = signal<MesAno | null>(null);

  /** Dias marcados no calendário (ISO 'YYYY-MM-DD' — gotcha 4). */
  selecionados = signal<Set<string>>(new Set());

  // ── Derivados do calendário/saldo ──
  primeiroDiaMes = computed(() => new Date(this.anoMes().ano, this.anoMes().mes - 1, 1));
  ultimoDiaMes = computed(() => new Date(this.anoMes().ano, this.anoMes().mes, 0));

  /** Débito visual por dia (C-3.2): carga 30 → 6h; 40 → 8h (o backend congela o real — Q3). */
  private debitoPorDia = computed(() => (this.dados()?.carga_horaria === 30 ? 360 : 480));
  /** Saldo exibido = saldo do backend − dias marcados × débito (decremento visual — C-3.2). */
  saldoVisualMin = computed(() => (this.dados()?.saldo_min ?? 0) - this.selecionados().size * this.debitoPorDia());
  saldoVisualFmt = computed(() => formatarSaldoMin(this.saldoVisualMin()));
  /** C-3.3: sem saldo para mais um dia → retângulos ainda não marcados desabilitam. */
  private podeMarcarMais = computed(() => this.saldoVisualMin() >= this.debitoPorDia());

  private bloqueadosPorDia = computed(() => {
    const m = new Map<string, string>();
    for (const b of this.dados()?.dias_bloqueados ?? []) m.set(b.data, b.motivo);
    return m;
  });

  /**
   * Estado por-dia do MiniCalendario (Q24): referência estável — os signals
   * lidos aqui dentro são rastreados pelo computed do calendário.
   */
  protected readonly estadoDia = (d: Date): DiaEstado | null => {
    const mc = this.mesCarregado();
    const ma = this.anoMes();
    if (!mc || mc.ano !== ma.ano || mc.mes !== ma.mes) {
      // Sem payload do mês exibido, nenhum dia é clicável — vale para TODA recarga (F45),
      // inclusive a pós-mutação, e não só para a troca de mês.
      return this.erroCarga()
        ? { desabilitado: true, rotulo: 'Não foi possível carregar este mês' }
        : { desabilitado: true, rotulo: 'Carregando...' };
    }
    const iso = toISODate(d);
    if (this.selecionados().has(iso)) {
      return { selecionado: true, marcado: true, rotulo: 'Selecionado — clique para desmarcar' };
    }
    const motivo = this.bloqueadosPorDia().get(iso);
    if (motivo) return { desabilitado: true, rotulo: motivo };
    if (!this.podeMarcarMais()) return { desabilitado: true, rotulo: 'Saldo insuficiente para mais um dia' };
    return null;
  };

  // ── Tabela "Minhas Solicitações" (C-4) ──
  cols: ColumnFilterDef[] = [
    { key: 'data_folga', label: 'Dia solicitado', type: 'date' },
    { key: 'status',     label: 'Status',         type: 'text' },
  ];
  ctrl = new TableStateController<SolicitacaoRow>(this.api, {
    endpoint: '/api/ponto/banco/solicitacoes', defaultSort: 'data_folga', defaultDir: 'desc',
    erroMsg: GUIA_SOLICITACOES,
  });
  gd = getDistinct;

  ngOnInit(): void {
    this.carregarBanco();
    this.ctrl.load();
  }

  onMesAno(ma: MesAno): void {
    this.anoMes.set(ma);
    this.limparSelecao();   // a seleção pertence ao mês exibido — navegar desmarca (restaura o saldo)
    this.carregarBanco();
  }

  /**
   * Caminho ÚNICO de recarga do mês exibido — troca de mês, retry da caixa de erro, pós-solicitar
   * e pós-cancelar passam TODOS por aqui, e por isso todos herdam as três propriedades do C8:
   *
   * 1. **Sinaliza a carga e invalida o mês carregado** (F45): o guard "Carregando..." do
   *    `estadoDia` — que existe para impedir clique sobre payload velho — passa a disparar em
   *    toda recarga, não só na troca de mês.
   * 2. **Descarta o obsoleto por token de recência** (F43): nenhuma resposta velha sobrescreve
   *    uma mais nova, INCLUSIVE entre dois pedidos do mesmo mês. (Token, e não `switchMap`, para
   *    manter o `subscribe({next,error})` do resto do código; o custo é não abortar o XHR antigo.)
   * 3. **Separa fatal de transitório** (F44): só o 409 do gate de carga horária (Q17) bloqueia o
   *    card; erro de transporte vai para a caixa com retry, e o seletor de mês continua na tela.
   */
  carregarBanco(): void {
    const pedido = this.anoMes();
    const seq = ++this.seqCarga;
    this.mesCarregado.set(null);
    this.erroCarga.set('');
    this.cargaEmVoo.set(true);

    this.api.get<any>('/api/ponto/banco', { ano: pedido.ano, mes: pedido.mes }).subscribe({
      next: res => {
        if (seq !== this.seqCarga) return;   // obsoleta: mês abandonado OU recarga mais nova do mesmo mês
        this.dados.set(res.data as BancoInfo);
        this.mesCarregado.set(pedido);
        this.erroBloqueio.set('');
        this.cargaEmVoo.set(false);
      },
      error: err => {
        if (seq !== this.seqCarga) return;
        this.cargaEmVoo.set(false);
        if (err?.status === HTTP_CONFLITO) {
          this.erroBloqueio.set(httpErrorMsg(err, MSG_BLOQUEIO));     // Q17 — fatal
        } else {
          this.erroCarga.set(erroCargaMsg(err, GUIA_CARGA));          // transitório — com retry
        }
      },
    });
  }

  toggleDia(d: Date): void {
    const iso = toISODate(d);
    this.erroAcao.set('');
    this.selecionados.update(sel => {
      const novo = new Set(sel);
      if (novo.has(iso)) novo.delete(iso);
      else if (!this.bloqueadosPorDia().has(iso) && this.podeMarcarMais()) novo.add(iso);
      return novo;
    });
  }

  /** "Cancelar" do topo (C-5.2): desmarca tudo e restaura o saldo exibido. */
  limparSelecao(): void {
    this.selecionados.set(new Set());
    this.erroAcao.set('');
  }

  /** "Solicitar" (C-5.3): 1 linha PENDENTE por dia; o backend revalida tudo (Q10). */
  solicitar(): void {
    const dias = Array.from(this.selecionados()).sort();
    if (dias.length === 0) return;
    this.enviando.set(true);
    this.erroAcao.set('');
    this.api.post<any>('/api/ponto/banco/solicitar', { dias }).subscribe({
      next: () => {
        this.enviando.set(false);
        this.toast.success(`Solicitação enviada (${dias.length} dia${dias.length > 1 ? 's' : ''}).`);
        this.selecionados.set(new Set());
        this.carregarBanco();
        this.ctrl.load();
      },
      error: err => {
        this.enviando.set(false);
        this.erroAcao.set(httpErrorMsg(err, 'Erro ao enviar a solicitação.'));
      },
    });
  }

  /** Cancela uma solicitação PENDENTE (Q19) — o saldo volta (estorno implícito). */
  cancelarSolicitacao(r: SolicitacaoRow): void {
    if (this.cancelando()) return;
    if (!confirm(`Cancelar a solicitação do dia ${formatarDataBr(r.data_folga)}?`)) return;
    this.cancelando.set(true);
    this.api.patch<any>(`/api/ponto/banco/solicitacao/${r.id}/cancelar`, {}).subscribe({
      next: () => {
        this.cancelando.set(false);
        this.toast.success('Solicitação cancelada.');
        this.carregarBanco();
        this.ctrl.load();
      },
      error: err => {
        this.cancelando.set(false);
        this.toast.error(httpErrorMsg(err, 'Erro ao cancelar.'));
      },
    });
  }

  protected statusLabel(s: string): string {
    switch (s) {
      case 'PENDENTE':  return 'Pendente';
      case 'APROVADO':  return 'Aprovado';
      case 'REJEITADO': return 'Rejeitado';
      case 'CANCELADO': return 'Cancelado';
      default: return s;
    }
  }
}

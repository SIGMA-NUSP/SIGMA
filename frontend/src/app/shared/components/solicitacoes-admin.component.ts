import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { getDistinct, buildReportParams } from '../../core/helpers/table.helpers';
import { formatarSaldoMin, rotuloStatusSolicitacao } from '../../core/helpers/format.helpers';
import { formatarDataBr, formatarDataExtensoBr } from '../../core/helpers/date.helpers';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { ColumnFilterComponent, ColumnFilterDef } from './column-filter.component';
import { ErroCargaComponent } from './erro-carga.component';
import { PaginationComponent } from './pagination.component';
import { ToastService } from './toast.component';

/** Um dia dentro da solicitação; a intenção do admin sobre ele vive em `_voto`. */
interface DiaSolicitado {
  id: string;
  data_folga: string;
  status: 'PENDENTE' | 'APROVADO' | 'REJEITADO' | 'CANCELADO';
  motivo?: string | null;
  /** Marcação do admin antes de deliberar: nada, aprovar ou rejeitar. */
  _voto?: 'aprovar' | 'rejeitar';
}

/** Linha da fila de deliberação (GET /api/admin/ponto/banco/solicitacoes) — uma por ENVIO. */
interface SolicitacaoAdminRow {
  id: string;
  pessoa_id: string;
  pessoa_tipo: 'OPERADOR' | 'TECNICO' | 'ADMINISTRADOR';
  nome: string;
  saldo_min: number | null;
  data_solicitacao: string;
  /** Da solicitação inteira; PARCIAL = parte dos dias aprovada, parte rejeitada. */
  status: 'PENDENTE' | 'APROVADO' | 'REJEITADO' | 'CANCELADO' | 'PARCIAL';
  deliberado_por?: string;
  motivo?: string;
  dias: DiaSolicitado[];
  /** T-1.2/T-1.3 pré-computadas pelo backend para o caller (Q34). */
  pode_deliberar: boolean;
  /** Algum dia ainda pendente já transcorreu (Q11). */
  atrasada: boolean;
  /** Linha expandida (acordeão) — campo de tela, não vem do backend. */
  _exp?: boolean;
}

/**
 * Tabela "Solicitações" do admin (Bloco D / E8) — card "Banco de Horas" do
 * /admin/ponto. Fila de todos os funcionários via TableStateController (D-1),
 * uma linha por SOLICITAÇÃO (Nome/Saldo/Data da solicitação/Status), busca +
 * PDF/DOCX no header (D-1.3). A linha expande e mostra os dias do envio.
 *
 * <p>A deliberação é sempre do envio inteiro, nunca de parte dele: ou o admin
 * responde tudo pela linha-mãe (✅/❌), ou marca dia a dia na expansão e conclui
 * no botão "Deliberar", que só destrava com todos os dias marcados. Ações
 * desabilitadas quando pode_deliberar=false (Q34, garantido no backend — T-1.4).
 */
@Component({
  selector: 'app-solicitacoes-admin',
  standalone: true,
  imports: [FormsModule, ColumnFilterComponent, ErroCargaComponent, PaginationComponent],
  template: `
    <section>
      <div class="section-header">
        <h2>Solicitações</h2>
        <div class="header-actions">
          <input type="text" class="search-input" [(ngModel)]="ctrl.searchText" (input)="ctrl.onSearch()"
                 placeholder="Buscar por nome...">
          <button class="btn-report" (click)="gerarRelatorio('pdf')">PDF</button>
          <button class="btn-report" (click)="gerarRelatorio('docx')">DOCX</button>
        </div>
      </div>

      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width:34px"></th>
              <th><app-column-filter [col]="cols[0]" [distinctValues]="gd(ctrl.meta(), 'nome')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th style="text-align:right">Saldo</th>
              <th><app-column-filter [col]="cols[1]" [distinctValues]="gd(ctrl.meta(), 'data_solicitacao')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="cols[2]" [distinctValues]="gd(ctrl.meta(), 'status')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th style="width:120px; text-align:center">Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (ctrl.loading()) {
              <tr><td colspan="6" class="empty-state">Carregando solicitações...</td></tr>
            } @else if (ctrl.erro()) {
              <!-- Canal de erro (C7/F46): a fila que falhou NÃO pode se passar por fila vazia -->
              <tr><td colspan="6">
                <app-erro-carga [mensagem]="ctrl.erro()" (tentarNovamente)="ctrl.load()" />
              </td></tr>
            } @else if (ctrl.rows().length === 0) {
              <tr><td colspan="6" class="empty-state">Nenhuma solicitação registrada.</td></tr>
            } @else {
              @for (r of ctrl.rows(); track r.id) {
                <tr class="row-clickable" [class.linha-atrasada]="r.atrasada"
                    [title]="r.atrasada ? 'Pendente com data já transcorrida' : ''"
                    (click)="toggleSolicitacao(r)">
                  <td><span class="btn-toggle">{{ r._exp ? '▼' : '▶' }}</span></td>
                  <td>{{ r.nome }}</td>
                  <td style="text-align:right">
                    <span class="saldo-cell" [class.negativo]="(r.saldo_min ?? 0) < 0">{{ saldoFmt(r) }}</span>
                  </td>
                  <td>{{ dataSolicitacao(r.data_solicitacao) }}</td>
                  <td><span class="st" [attr.data-st]="r.status">{{ statusLabel(r.status) }}</span></td>
                  <td style="text-align:center" (click)="$event.stopPropagation()">
                    @if (r.status !== 'PENDENTE') { — }
                    @else if (temVoto(r)) {
                      <!-- Marcada a intenção de algum dia, a resposta passa a ser pelo conjunto. -->
                      <button class="btn-outline btn-deliberar" [disabled]="!podeConcluir(r) || deliberando()"
                              [title]="podeConcluir(r) ? '' : 'Marque todos os dias'"
                              (click)="abrirConfirmacao(r)">Deliberar</button>
                    } @else {
                      <button class="btn-acao aprovar" title="Aprovar"
                              [disabled]="!r.pode_deliberar || deliberando()" (click)="aprovarTudo(r)">✅</button>
                      <button class="btn-acao rejeitar" title="Rejeitar"
                              [disabled]="!r.pode_deliberar || deliberando()" (click)="rejeitarTudo(r)">❌</button>
                    }
                  </td>
                </tr>

                @if (r._exp) {
                  <tr class="accordion-row">
                    <td colspan="6">
                      <table class="data-table sub">
                        <thead><tr>
                          <th>Dia solicitado</th><th>Status</th>
                          <th style="width:96px; text-align:center">Ação</th>
                        </tr></thead>
                        <tbody>
                          @for (d of r.dias; track d.id) {
                            <tr>
                              <td>{{ diaSolicitado(d.data_folga) }}</td>
                              <td><span class="st" [attr.data-st]="d.status">{{ statusLabel(d.status) }}</span></td>
                              <td style="text-align:center">
                                @if (d.status !== 'PENDENTE' || !r.pode_deliberar) { — }
                                @else {
                                  <!-- Reclicar desmarca: os dois botões voltam. -->
                                  @if (d._voto !== 'rejeitar') {
                                    <button class="btn-acao aprovar" title="Aprovar este dia"
                                            [class.marcado]="d._voto === 'aprovar'" [disabled]="deliberando()"
                                            (click)="votar(r, d, 'aprovar')">✅</button>
                                  }
                                  @if (d._voto !== 'aprovar') {
                                    <button class="btn-acao rejeitar" title="Rejeitar este dia"
                                            [class.marcado]="d._voto === 'rejeitar'" [disabled]="deliberando()"
                                            (click)="votar(r, d, 'rejeitar')">❌</button>
                                  }
                                }
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </td>
                  </tr>
                }
              }
            }
          </tbody>
        </table>
      </div>

      <div class="table-footer">
        <app-pagination [meta]="ctrl.meta()!" (pageChange)="ctrl.onPage($event)" (limitChange)="ctrl.onLimit($event)" />
      </div>
    </section>

    <!-- D-3: janela da deliberação (padrão overlay+card global — Q42/E4) -->
    @if (alvo(); as a) {
      <div class="modal-overlay">
        <div class="card-custom modal-card">
          <h2 class="modal-title">Confirmar ação</h2>
          <p class="text-muted-sm" style="margin:0 0 10px">{{ a.nome }}</p>
          @if (diasAprovados(a).length) {
            <p style="margin:0 0 6px">Aprovar o(s) dia(s) {{ diasEmTexto(diasAprovados(a)) }}</p>
          }
          @if (diasRejeitados(a).length) {
            <p style="margin:0 0 6px">Rejeitar o(s) dia(s) {{ diasEmTexto(diasRejeitados(a)) }}</p>
            <div class="form-row">
              <label for="motivo-rejeicao">Motivação para a(s) rejeição(ões)</label>
              <!-- 300 caracteres: MOTIVO_REJEICAO é VARCHAR2(1000) em BYTES — sem o teto, um motivo
                   colado de um e-mail estourava a coluna e a rejeição virava um 500 sem pista (F47). -->
              <textarea id="motivo-rejeicao" rows="4" maxlength="300" [(ngModel)]="motivoRejeicao"></textarea>
            </div>
          }
          <div class="modal-actions" style="gap:8px">
            <button class="btn-secondary-custom" [disabled]="deliberando()" (click)="fecharConfirmacao()">Cancelar</button>
            <button class="btn-primary-custom" [disabled]="!podeEnviar() || deliberando()"
                    (click)="confirmar()">Enviar</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    section { margin-top:8px; }
    .st { font-weight:600; }
    .st[data-st="APROVADO"]  { color: var(--color-blue); }
    .st[data-st="REJEITADO"] { color: var(--color-red); }
    .st[data-st="CANCELADO"] { color: #9ca3af; }
    .st[data-st="PARCIAL"]   { color: #b45309; }
    .saldo-cell { font-variant-numeric: tabular-nums; }
    .saldo-cell.negativo { color: var(--color-red); }
    .row-clickable { cursor: pointer; }
    .sub { margin: 0; }
    .sub th, .sub td { padding: 6px 10px; font-size: .85rem; }
    /* Q11: destaque das pendentes com dia já transcorrido */
    .linha-atrasada { background: #fef3c7; }
    .btn-acao {
      border:none; background:transparent; cursor:pointer; font-size:1.05rem;
      padding:2px 6px; border-radius:6px; line-height:1;
    }
    .btn-acao.aprovar  { color: var(--color-green, #16a34a); }
    .btn-acao.rejeitar { color: var(--color-red, #dc2626); }
    .btn-acao:hover:not(:disabled) { background:#f1f5f9; }
    .btn-acao:disabled { opacity:.35; cursor:not-allowed; }
    .btn-acao.marcado { background:#e2e8f0; }
    .btn-deliberar { padding:3px 12px; font-size:.85rem; }
    .table-footer {
      display:flex; align-items:center; justify-content:flex-end;
      gap:12px; flex-wrap:wrap; margin-top:10px;
    }
    @media (max-width: 640px) {
      .header-actions { flex-wrap:wrap; }
      .search-input { flex:1 1 100%; }
    }
  `],
})
export class SolicitacoesAdminComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  /** Trava única de qualquer deliberação em curso. */
  deliberando = signal(false);
  /** Solicitação da janela de confirmação (null = fechada). */
  alvo = signal<SolicitacaoAdminRow | null>(null);
  motivoRejeicao = '';

  cols: ColumnFilterDef[] = [
    { key: 'nome',             label: 'Nome',                type: 'text' },
    { key: 'data_solicitacao', label: 'Data da solicitação', type: 'date' },
    { key: 'status',           label: 'Status',              type: 'text' },
  ];
  ctrl = new TableStateController<SolicitacaoAdminRow>(this.api, {
    endpoint: '/api/admin/ponto/banco/solicitacoes', defaultSort: 'data_solicitacao', defaultDir: 'desc',
    erroMsg: 'Não foi possível carregar as solicitações. A fila pode ter pedidos aguardando deliberação.',
  });
  gd = getDistinct;

  ngOnInit(): void {
    this.ctrl.load();
  }

  protected saldoFmt(r: SolicitacaoAdminRow): string {
    return r.saldo_min == null ? '--' : formatarSaldoMin(r.saldo_min);
  }

  /** "Dia solicitado" no formato "Dia-da-Semana, dd/mm/aaaa" (D-1.2). */
  protected diaSolicitado(s: string): string {
    return formatarDataExtensoBr(s);
  }

  protected dataSolicitacao(s: string): string {
    return formatarDataBr(s);
  }

  protected statusLabel(s: string): string {
    return rotuloStatusSolicitacao(s);
  }

  // ── Expansão e marcação dia a dia ──

  toggleSolicitacao(r: SolicitacaoAdminRow): void {
    r._exp = !r._exp;
    this.repintar();
  }

  /** Marca (ou desmarca, reclicando) a intenção sobre um dia. */
  votar(r: SolicitacaoAdminRow, d: DiaSolicitado, voto: 'aprovar' | 'rejeitar'): void {
    if (this.deliberando()) return;
    d._voto = d._voto === voto ? undefined : voto;
    this.repintar();
  }

  protected temVoto(r: SolicitacaoAdminRow): boolean {
    return r.dias.some(d => d._voto);
  }

  /** Deliberar é do envio inteiro: enquanto faltar um dia pendente sem marca, o botão fica travado. */
  protected podeConcluir(r: SolicitacaoAdminRow): boolean {
    return r.pode_deliberar && this.pendentes(r).every(d => !!d._voto);
  }

  protected diasAprovados(r: SolicitacaoAdminRow): DiaSolicitado[] {
    return this.pendentes(r).filter(d => d._voto === 'aprovar');
  }

  protected diasRejeitados(r: SolicitacaoAdminRow): DiaSolicitado[] {
    return this.pendentes(r).filter(d => d._voto === 'rejeitar');
  }

  protected diasEmTexto(dias: DiaSolicitado[]): string {
    return dias.map(d => formatarDataBr(d.data_folga)).join(', ');
  }

  private pendentes(r: SolicitacaoAdminRow): DiaSolicitado[] {
    return r.dias.filter(d => d.status === 'PENDENTE');
  }

  /** As linhas são mutadas no lugar (o acordeão e os votos moram nelas) — o signal precisa saber. */
  private repintar(): void {
    this.ctrl.rows.set([...this.ctrl.rows()]);
  }

  // ── Deliberação ──

  /** Fluxo total: a janela abre com todos os dias pendentes de um lado só. */
  aprovarTudo(r: SolicitacaoAdminRow): void {
    this.marcarTodos(r, 'aprovar');
  }

  rejeitarTudo(r: SolicitacaoAdminRow): void {
    this.marcarTodos(r, 'rejeitar');
  }

  private marcarTodos(r: SolicitacaoAdminRow, voto: 'aprovar' | 'rejeitar'): void {
    if (this.deliberando() || !r.pode_deliberar) return;
    this.pendentes(r).forEach(d => d._voto = voto);
    this.repintar();
    this.abrirConfirmacao(r);
  }

  abrirConfirmacao(r: SolicitacaoAdminRow): void {
    if (this.deliberando() || !this.podeConcluir(r)) return;
    this.motivoRejeicao = '';
    this.alvo.set(r);
  }

  /** Fechar a janela desfaz as marcas: nada de deliberação pela metade esperando na tela. */
  fecharConfirmacao(): void {
    const alvo = this.alvo();
    if (alvo) alvo.dias.forEach(d => d._voto = undefined);
    this.alvo.set(null);
    this.repintar();
  }

  /** Sem rejeição não há motivo a exigir; havendo, ele é obrigatório (o backend também valida). */
  protected podeEnviar(): boolean {
    const alvo = this.alvo();
    if (!alvo) return false;
    return !this.diasRejeitados(alvo).length || !!this.motivoRejeicao.trim();
  }

  confirmar(): void {
    const alvo = this.alvo();
    if (!alvo || !this.podeEnviar() || this.deliberando()) return;
    const aprovados = this.diasAprovados(alvo).map(d => d.id);
    const rejeitados = this.diasRejeitados(alvo).map(d => d.id);
    const corpo: Record<string, unknown> = { aprovados, rejeitados };
    if (rejeitados.length) corpo['motivo'] = this.motivoRejeicao.trim();

    this.deliberando.set(true);
    this.api.post<any>(`/api/admin/ponto/banco/solicitacao/${alvo.id}/deliberar`, corpo).subscribe({
      next: () => {
        this.deliberando.set(false);
        this.toast.success('Solicitação deliberada.');
        this.alvo.set(null);
        this.ctrl.load();   // o reload traz o saldo atualizado da pessoa (rejeição estorna)
      },
      error: err => {
        this.deliberando.set(false);
        this.toast.error(httpErrorMsg(err, 'Erro ao processar a deliberação.'));
        this.alvo.set(null);
        this.ctrl.load();   // reflete o que porventura tenha sido processado
      },
    });
  }

  /** "PDF"/"DOCX" (D-1.3/Q27): honra sort + busca + filtros de coluna aplicados. */
  gerarRelatorio(format: string): void {
    this.api.downloadReport('/api/admin/ponto/banco/solicitacoes/relatorio',
        buildReportParams(format, this.ctrl.state.sort, this.ctrl.state.direction,
            this.ctrl.state.search, this.ctrl.filters));
  }
}

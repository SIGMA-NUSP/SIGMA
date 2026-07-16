import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { getDistinct, buildReportParams } from '../../core/helpers/table.helpers';
import { formatarSaldoMin } from '../../core/helpers/format.helpers';
import { formatarDataBr, formatarDataExtensoBr } from '../../core/helpers/date.helpers';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { ColumnFilterComponent, ColumnFilterDef } from './column-filter.component';
import { ErroCargaComponent } from './erro-carga.component';
import { PaginationComponent } from './pagination.component';
import { ToastService } from './toast.component';

/** Linha da fila de deliberação (GET /api/admin/ponto/banco/solicitacoes). */
interface SolicitacaoAdminRow {
  id: string;
  pessoa_id: string;
  pessoa_tipo: 'OPERADOR' | 'TECNICO' | 'ADMINISTRADOR';
  nome: string;
  saldo_min: number | null;
  data_folga: string;
  status: 'PENDENTE' | 'APROVADO' | 'REJEITADO' | 'CANCELADO';
  deliberado_por?: string;
  motivo?: string;
  /** T-1.2/T-1.3 pré-computadas pelo backend para o caller (Q34). */
  pode_deliberar: boolean;
  /** PENDENTE com dia já transcorrido (Q11). */
  atrasada: boolean;
}

/**
 * Tabela "Solicitações" do admin (Bloco D / E8) — card "Banco de Horas" do
 * /admin/ponto. Fila de todos os funcionários via TableStateController (D-1),
 * com Nome/Saldo/Dia/Status (D-1.2), busca + PDF/DOCX no header (D-1.3),
 * ordenação default D-4.1 (pendentes primeiro). Aprovar = confirm() nativo
 * (Q14); Rejeitar = modal com motivação (D-3). Ações desabilitadas quando
 * pode_deliberar=false (Q34, garantido no backend — T-1.4). Deliberação
 * aceita lista de ids (Q18: lote futuro); a UI atual chama sempre com 1.
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
              <th><app-column-filter [col]="cols[0]" [distinctValues]="gd(ctrl.meta(), 'nome')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th style="text-align:right">Saldo</th>
              <th><app-column-filter [col]="cols[1]" [distinctValues]="gd(ctrl.meta(), 'data_folga')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="cols[2]" [distinctValues]="gd(ctrl.meta(), 'status')"
                    [currentSort]="ctrl.state.sort" [currentDir]="ctrl.state.direction"
                    (sortChange)="ctrl.onSort($event)" (filterChange)="ctrl.onFilter($event)" /></th>
              <th style="width:96px; text-align:center">Ação</th>
            </tr>
          </thead>
          <tbody>
            @if (ctrl.loading()) {
              <tr><td colspan="5" class="empty-state">Carregando solicitações...</td></tr>
            } @else if (ctrl.erro()) {
              <!-- Canal de erro (C7/F46): a fila que falhou NÃO pode se passar por fila vazia -->
              <tr><td colspan="5">
                <app-erro-carga [mensagem]="ctrl.erro()" (tentarNovamente)="ctrl.load()" />
              </td></tr>
            } @else if (ctrl.rows().length === 0) {
              <tr><td colspan="5" class="empty-state">Nenhuma solicitação registrada.</td></tr>
            } @else {
              @for (r of ctrl.rows(); track r.id) {
                <tr [class.linha-atrasada]="r.atrasada"
                    [title]="r.atrasada ? 'Pendente com data já transcorrida' : ''">
                  <td>{{ r.nome }}</td>
                  <td style="text-align:right">
                    <span class="saldo-cell" [class.negativo]="(r.saldo_min ?? 0) < 0">{{ saldoFmt(r) }}</span>
                  </td>
                  <td>{{ diaSolicitado(r.data_folga) }}</td>
                  <td><span class="st" [attr.data-st]="r.status">{{ statusLabel(r.status) }}</span></td>
                  <td style="text-align:center">
                    @if (r.status === 'PENDENTE') {
                      <button class="btn-acao aprovar" title="Aprovar"
                              [disabled]="!r.pode_deliberar || deliberando()" (click)="aprovar(r)">✅</button>
                      <button class="btn-acao rejeitar" title="Rejeitar"
                              [disabled]="!r.pode_deliberar || deliberando()" (click)="abrirRejeicao(r)">❌</button>
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

    <!-- D-3: modal de rejeição (padrão overlay+card global — Q42/E4) -->
    @if (alvoRejeicao(); as alvo) {
      <div class="modal-overlay">
        <div class="card-custom modal-card">
          <h2 class="modal-title">Rejeitar solicitação</h2>
          <p class="text-muted-sm" style="margin:0 0 10px">
            {{ alvo.nome }} — {{ diaSolicitado(alvo.data_folga) }}
          </p>
          <div class="form-row">
            <label for="motivo-rejeicao">Motivação</label>
            <!-- 300 caracteres: MOTIVO_REJEICAO é VARCHAR2(1000) em BYTES — sem o teto, um motivo
                 colado de um e-mail estourava a coluna e a rejeição virava um 500 sem pista (F47). -->
            <textarea id="motivo-rejeicao" rows="4" maxlength="300" [(ngModel)]="motivoRejeicao"></textarea>
          </div>
          <div class="modal-actions" style="gap:8px">
            <button class="btn-secondary-custom" [disabled]="deliberando()" (click)="fecharRejeicao()">Cancelar</button>
            <button class="btn-primary-custom" [disabled]="!motivoRejeicao.trim() || deliberando()"
                    (click)="confirmarRejeicao()">Rejeitar</button>
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
    .saldo-cell { font-variant-numeric: tabular-nums; }
    .saldo-cell.negativo { color: var(--color-red); }
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

  /** Trava única de qualquer deliberação em curso (aprovar/rejeitar/lote). */
  deliberando = signal(false);
  /** Linha alvo do modal de rejeição (null = fechado). */
  alvoRejeicao = signal<SolicitacaoAdminRow | null>(null);
  motivoRejeicao = '';

  cols: ColumnFilterDef[] = [
    { key: 'nome',       label: 'Nome',           type: 'text' },
    { key: 'data_folga', label: 'Dia solicitado', type: 'date' },
    { key: 'status',     label: 'Status',         type: 'text' },
  ];
  // defaultSort 'padrao' → ordenação composta D-4.1 no backend (pendentes primeiro, por dia).
  ctrl = new TableStateController<SolicitacaoAdminRow>(this.api, {
    endpoint: '/api/admin/ponto/banco/solicitacoes', defaultSort: 'padrao', defaultDir: 'asc',
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

  protected statusLabel(s: string): string {
    switch (s) {
      case 'PENDENTE':  return 'Pendente';
      case 'APROVADO':  return 'Aprovado';
      case 'REJEITADO': return 'Rejeitado';
      case 'CANCELADO': return 'Cancelado';
      default: return s;
    }
  }

  // ── Deliberação ──

  /** Aprovar (Q14): confirmação nativa → aprova. Chama a via de lote com 1 id (Q18). */
  aprovar(r: SolicitacaoAdminRow): void {
    if (this.deliberando()) return;
    if (!confirm(`Aprovar a folga de ${r.nome} em ${formatarDataBr(r.data_folga)}?`)) return;
    this.executarDeliberacao([r.id], 'aprovar', {}, 'Solicitação aprovada.');
  }

  abrirRejeicao(r: SolicitacaoAdminRow): void {
    if (this.deliberando()) return;
    this.motivoRejeicao = '';
    this.alvoRejeicao.set(r);
  }

  fecharRejeicao(): void {
    this.alvoRejeicao.set(null);
  }

  /** Rejeitar (D-3.2): motivo obrigatório; o backend também valida (T-1.4). */
  confirmarRejeicao(): void {
    const alvo = this.alvoRejeicao();
    const motivo = this.motivoRejeicao.trim();
    if (!alvo || !motivo || this.deliberando()) return;
    this.executarDeliberacao([alvo.id], 'rejeitar', { motivo }, 'Solicitação rejeitada.',
        () => this.fecharRejeicao());
  }

  /**
   * Executa a deliberação de 1+ solicitações (Q18: preparado para lote futuro;
   * a UI atual passa sempre 1 id). Recarrega a lista ao final — o reload traz o
   * saldo atualizado das linhas da mesma pessoa (rejeição estorna).
   */
  private executarDeliberacao(ids: string[], acao: 'aprovar' | 'rejeitar', body: unknown,
                              msgSucesso: string, onSucesso?: () => void): void {
    if (!ids.length || this.deliberando()) return;
    this.deliberando.set(true);
    forkJoin(ids.map(id =>
        this.api.post<any>(`/api/admin/ponto/banco/solicitacao/${id}/${acao}`, body))).subscribe({
      next: () => {
        this.deliberando.set(false);
        this.toast.success(msgSucesso);
        onSucesso?.();
        this.ctrl.load();
      },
      error: err => {
        this.deliberando.set(false);
        this.toast.error(httpErrorMsg(err, 'Erro ao processar a deliberação.'));
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

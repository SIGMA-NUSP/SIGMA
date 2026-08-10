import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from '../../shared/components/column-filter.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { CategoriaSeloComponent } from '../../shared/components/categoria-selo.component';
import { getDistinct } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';

/** Linha da listagem de comunicações (a mesma nas duas tabelas). */
export interface AvisoRow {
  id: string;
  numero: number;
  // Categoria da comunicação (selo) + o contexto que a qualifica ("Escala"); Mensagem vem sem contexto.
  categoria: string;
  tipo: string | null;
  // Salas da verificação de sala, separadas por vírgula; os demais tipos não são de sala e vêm nulos.
  local: string | null;
  criado_em: string;
  criado_por: string;
  expira_em: string | null;
  // Verificação/Pessoal/Geral: gravado (Ativo/Expirado/Desativado). Escala: calculado das datas
  // (Pendente/Ativo/Expirado). Agenda: '—' (sem ciclo temporal) ou Desativado.
  status: string;
  permanente: number;  // 0/1
}

/** Status que uma comunicação em curso pode ter — o '—' é o da Agenda, que não tem ciclo temporal. */
export const STATUS_ATIVOS = ['Ativo', 'Pendente', '—'];
/** Status de quem já cumpriu seu papel: saiu de cartaz por data ou pela mão do admin. */
export const STATUS_INATIVOS = ['Expirado', 'Desativado'];

/**
 * Tabela de comunicações cadastradas, com busca, filtros de coluna, ordenação e paginação próprios.
 * Serve às duas listas da página — as ativas e as inativas —, cada uma com seu controlador sobre o
 * mesmo endpoint.
 *
 * <p>O recorte de cada tabela é um filtro fixo de status ({@link statusDaTabela}), e o painel da
 * coluna Status só reparte o que já está dentro dele: limpar aquele filtro devolve o conjunto da
 * tabela, nunca a lista inteira.
 */
@Component({
  selector: 'app-comunicacoes-tabela',
  standalone: true,
  imports: [FormsModule, PaginationComponent, ColumnFilterComponent, ErroCargaComponent,
    CategoriaSeloComponent, FmtDatePipe],
  template: `
    <div class="tabela-actions">
      <input type="text" [(ngModel)]="ctrl().searchText" (input)="ctrl().onSearch()"
             placeholder="Buscar por autor ou nº do cadastro..." class="search-input search-wide">
    </div>
    <div class="table-container">
      <table class="data-table">
        <thead><tr>
          <th>Cadastro nº</th>
          @for (c of cols; track c.key) {
            <th>
              <app-column-filter [col]="c"
                [distinctValues]="gd(ctrl().meta(), c.key)"
                [currentSort]="ctrl().state.sort" [currentDir]="ctrl().state.direction"
                (sortChange)="ctrl().onSort($event)" (filterChange)="onFiltro(c.key, $event)" />
            </th>
            <!-- Local vem logo depois do Tipo e é só exibição: não ordena nem filtra. -->
            @if (c.key === 'tipo') { <th>Local</th> }
          }
          <th>Ação</th>
        </tr></thead>
        <tbody>
          @if (ctrl().erro()) {
            <!-- Canal de erro (C7/C13b): "Nenhum aviso cadastrado." numa carga que FALHOU esconde
                 os avisos ATIVOS — e o admin cadastra por cima, ou deixa de desativar o que devia. -->
            <tr><td colspan="8">
              <app-erro-carga [mensagem]="ctrl().erro()" (tentarNovamente)="ctrl().load()" />
            </td></tr>
          } @else if (ctrl().rows().length === 0) {
            <tr><td colspan="8" class="empty-state">{{ ctrl().loading() ? 'Carregando...' : vazio() }}</td></tr>
          } @else {
            @for (a of ctrl().rows(); track a.id) {
              <tr class="row-clickable" (dblclick)="abrir.emit(a)" title="Duplo-clique para ver o detalhe">
                <td>{{ a.numero }}</td>
                <td class="col-tipo">
                  <app-categoria-selo [categoria]="a.categoria" />
                  <span class="contexto">{{ a.tipo }}</span>
                </td>
                <td>{{ a.local || '—' }}</td>
                <td>{{ a.criado_em | fmtDate }}</td>
                <td>{{ a.criado_por }}</td>
                <!-- Escala manda DATA_FIM (permanente no banco); Agenda manda —. Exibe a data sempre que houver. -->
                <td>{{ a.expira_em ? (a.expira_em | fmtDate) : '—' }}</td>
                <td>
                  @if (a.status !== '—') { <span class="status-dot" [attr.data-status]="a.status"></span> }
                  {{ a.status }}
                </td>
                <td>
                  @if (podeDesativar(a)) {
                    <button class="btn-xs" (click)="desativar.emit(a); $event.stopPropagation()">Desativar</button>
                  } @else { — }
                </td>
              </tr>
            }
          }
        </tbody>
      </table>
    </div>
    <app-pagination [meta]="ctrl().meta()!"
                    (pageChange)="ctrl().onPage($event)"
                    (limitChange)="ctrl().onLimit($event)" />
  `,
  styles: [`
    :host { display: block; }
    .tabela-actions { display: flex; justify-content: flex-end; margin-bottom: 10px; }
    .row-clickable { cursor: pointer; }
    /* Selo em cima e contexto embaixo, em TODAS as linhas: a linha do contexto é reservada mesmo
       quando ele não existe (Mensagem), para que a altura das linhas da tabela não varie. */
    .col-tipo { display:flex; flex-direction:column; align-items:flex-start; gap:4px; }
    .col-tipo .contexto { min-height:1.2em; line-height:1.2; }
    .status-dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:6px; vertical-align:middle; }
    .status-dot[data-status="Ativo"]      { background: var(--color-green, #16a34a); }
    .status-dot[data-status="Pendente"]   { background: #f59e0b; }
    .status-dot[data-status="Expirado"]   { background: #9ca3af; }
    .status-dot[data-status="Desativado"] { background: #111827; }
  `],
})
export class ComunicacoesTabelaComponent {
  ctrl = input.required<TableStateController<AvisoRow>>();
  /** Conjunto de status desta tabela; o filtro da coluna Status nunca sai dele. */
  statusDaTabela = input.required<string[]>();
  /** Texto de lista vazia (a de ativas e a de inativas dizem coisas diferentes). */
  vazio = input('Nenhuma comunicação cadastrada.');

  abrir = output<AvisoRow>();
  desativar = output<AvisoRow>();

  cols: ColumnFilterDef[] = [
    { key: 'tipo', label: 'Tipo', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'criado_por', label: 'Cadastrado por', type: 'text' },
    { key: 'expira', label: 'Expira em', type: 'date' },
    { key: 'status', label: 'Status', type: 'text' },
  ];

  gd = getDistinct;

  /** Desativável = ainda não desativado nem expirado (cobre Ativo, Pendente e o Agenda "—"). */
  podeDesativar(a: AvisoRow): boolean {
    return a.status !== 'Desativado' && a.status !== 'Expirado';
  }

  onFiltro(key: string, ev: { key: string; state: ColumnFilterState | null }): void {
    if (key !== 'status') { this.ctrl().onFilter(ev); return; }
    const escolhidos = ev.state?.values?.filter(v => this.statusDaTabela().includes(v)) ?? [];
    this.ctrl().onFilter({
      key,
      state: { values: escolhidos.length ? escolhidos : this.statusDaTabela() },
    });
  }
}

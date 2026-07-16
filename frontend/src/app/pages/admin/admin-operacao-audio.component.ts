import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef } from '../../shared/components/column-filter.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { getDistinct, buildReportParams, mesNome } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { asArray, truncate, formatEvento as formatEventoDe } from '../../core/helpers/format.helpers';
import { duracaoHms } from '../../core/helpers/date.helpers';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtDateTimePipe } from '../../shared/pipes/fmt-datetime.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

@Component({
  selector: 'app-admin-operacao-audio',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent, ErroCargaComponent,
    FmtDatePipe, FmtTimePipe, FmtDateTimePipe],
  template: `
    <h1>Operação de Áudio</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <!-- Cards de navegação -->
    <div class="grid-cards cols-4">
      <a routerLink="/admin/form-edit" class="card-custom card-nav">Edição de Formulários</a>
    </div>

    <!-- ════════════ REGISTROS DE OPERAÇÃO ════════════ -->
    <section>
      <div class="section-header">
        <h2>Registros de Operação</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="opCtrl.searchText" (input)="opCtrl.onSearch()" placeholder="Buscar por local, operador, data, ..." class="search-input search-wide">
        </div>
      </div>

      <!-- Controles: Agrupar + RDS -->
      <div class="controls-row">
        <div class="controls-left">
          <label class="check-label">
            <input type="checkbox" [(ngModel)]="groupBySala" (change)="onGroupChange()">
            Agrupar por local
          </label>
        </div>
        <div class="controls-right">
          <select [(ngModel)]="rdsAno" (change)="onAnoChange()" class="ctrl-select">
            <option value="">Ano</option>
            @for (a of rdsAnos(); track a) { <option [value]="a">{{ a }}</option> }
          </select>
          <select [(ngModel)]="rdsMes" [disabled]="!rdsAno" class="ctrl-select">
            <option value="">Mês</option>
            @for (m of rdsMeses(); track m) { <option [value]="m">{{ mesNome(m) }}</option> }
          </select>
          <button class="btn-rds" [disabled]="!rdsAno || !rdsMes" (click)="gerarRds()">Gerar RDS</button>
        </div>
      </div>

      <!-- MODO AGRUPADO -->
      @if (groupBySala) {
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th style="width:30px"></th>
              <th><app-column-filter [col]="sessCols[0]" [distinctValues]="gd(opCtrl.meta(),'sala')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="sessCols[1]" [distinctValues]="gd(opCtrl.meta(),'data')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th>Evento</th>
              <th>Pauta</th>
              <th class="th-sort" (click)="toggleOpSort('inicio')">Início <span class="sort-arrow" [attr.data-state]="sortState('inicio')">{{ sortGlyph('inicio') }}</span></th>
              <th class="th-sort" (click)="toggleOpSort('fim')">Fim <span class="sort-arrow" [attr.data-state]="sortState('fim')">{{ sortGlyph('fim') }}</span></th>
              <th>Verificação</th>
            </tr></thead>
            <tbody>
              @if (opCtrl.erro()) {
                <!-- Canal de erro (C7/C13b): a carga que falhou NÃO pode se passar por "nenhuma
                     sessão" — cada LISTAGEM desta tela tem o seu canal (o erro de uma não contamina
                     as outras) e o rodapé não mente (o motor limpa o meta). ⚠️ As sub-tabelas dos
                     acordeões (entradas da sessão, itens do checklist) ainda NÃO têm canal: elas
                     seguem virando "vazio" na falha — F66, fora do escopo do C13b. -->
                <tr><td colspan="8">
                  <app-erro-carga [mensagem]="opCtrl.erro()" (tentarNovamente)="opCtrl.load()" />
                </td></tr>
              } @else if (opCtrl.rows().length === 0) {
                <tr><td colspan="8" class="empty-state">{{ opCtrl.loading() ? 'Carregando...' : 'Nenhuma sessão.' }}</td></tr>
              } @else {
                @for (s of opCtrl.rows(); track s['id']) {
                  <tr class="row-clickable" [class.row-editado]="s['editado']" (click)="toggleSessao(s)">
                    <td><span class="btn-toggle">{{ s['_exp'] ? '▼' : '▶' }}</span></td>
                    <td>
                      <strong>{{ s['sala_nome'] }}</strong>
                      @if (s['editado']) {
                        <span class="badge-editado" [title]="'Contém entrada(s) editada(s) — última em ' + (s['ultima_edicao_em'] | fmtDateTime)"></span>
                      }
                    </td>
                    <td>{{ s['data'] | fmtDate }}</td>
                    <td [title]="formatEvento(s)">{{ truncate(formatEvento(s), 30) }}</td>
                    <td>{{ s['ultimo_pauta'] | fmtTime }}</td>
                    <td>{{ s['ultimo_inicio'] | fmtTime }}</td>
                    <td>{{ s['ultimo_termino'] | fmtTime }}</td>
                    <td [style.color]="s['checklist_do_dia_ok'] ? 'var(--color-green)' : 'var(--muted)'">{{ s['checklist_do_dia_ok'] ? 'Realizado' : 'Não Realizado' }}</td>
                  </tr>
                  @if (s['_exp']) {
                    <tr class="accordion-row">
                      <td colspan="8">
                        @if (!s['_entradas']) {
                          <p class="text-muted-sm">Carregando entradas...</p>
                        } @else if (asArr(s['_entradas']).length === 0) {
                          <p class="text-muted-sm">Nenhuma entrada registrada nesta sessão.</p>
                        } @else if (s['_is_plenario_principal']) {
                          <!-- Subtabela: Plenário Principal -->
                          @for (e of asArr(s['_entradas']); track e['id']||$index) {
                            <table class="sub-table">
                              <thead><tr><th>Operador</th><th>Anom?</th></tr></thead>
                              <tbody>
                                @if (asArr(e['operadores']).length > 0) {
                                  @for (op of asArr(e['operadores']); track $index) {
                                    <tr class="row-clickable" [class.row-editado]="$first && e['editado']" (dblclick)="openEntrada(e)">
                                      <td>
                                        {{ op }}
                                        @if ($first && e['editado']) {
                                          <span class="badge-editado" [title]="'Editado em ' + (e['ultima_edicao_em'] | fmtDateTime)"></span>
                                        }
                                      </td>
                                      @if ($first) {
                                        <td [attr.rowspan]="asArr(e['operadores']).length" [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'" style="vertical-align:middle">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                      }
                                    </tr>
                                  }
                                } @else {
                                  <tr class="row-clickable" [class.row-editado]="e['editado']" (dblclick)="openEntrada(e)">
                                    <td>
                                      {{ e['preenchido_por'] }}
                                      @if (e['editado']) {
                                        <span class="badge-editado" [title]="'Editado em ' + (e['ultima_edicao_em'] | fmtDateTime)"></span>
                                      }
                                    </td>
                                    <td [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                  </tr>
                                }
                              </tbody>
                            </table>
                          }
                        } @else {
                          <!-- Subtabela: Plenários numerados -->
                          <table class="sub-table">
                            <thead><tr><th>Nº</th><th>Operador</th><th>Início Operação</th><th>Fim Operação</th><th>Observações</th><th>Anom?</th></tr></thead>
                            <tbody>
                              @for (e of asArr(s['_entradas']); track e['id']||$index) {
                                <tr class="row-clickable" [class.row-editado]="e['editado']" (dblclick)="openEntrada(e)">
                                  <td>
                                    {{ e['ordem'] }}º
                                    @if (e['editado']) {
                                      <span class="badge-editado" [title]="'Editado em ' + (e['ultima_edicao_em'] | fmtDateTime)"></span>
                                    }
                                  </td>
                                  <td>{{ e['operador'] }}</td>
                                  <td>{{ e['hora_entrada'] | fmtTime }}</td>
                                  <td>{{ e['hora_saida'] | fmtTime }}</td>
                                  <td [title]="e['observacoes']">{{ truncate(e['observacoes'], 20) }}</td>
                                  <td [class]="e['anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['anormalidade'] ? 'SIM' : 'Não' }}</td>
                                </tr>
                              }
                            </tbody>
                          </table>
                        }
                      </td>
                    </tr>
                  }
                }
              }
            </tbody>
          </table>
        </div>
      } @else {
        <!-- MODO LISTA PLANA -->
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th><app-column-filter [col]="entCols[0]" [distinctValues]="gd(opCtrl.meta(),'sala')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th><app-column-filter [col]="entCols[1]" [distinctValues]="gd(opCtrl.meta(),'data')" [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction" (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" /></th>
              <th>Operador</th><th>Tipo</th><th>Evento</th><th>Pauta</th>
              <th class="th-sort" (click)="toggleOpSort('inicio')">Início <span class="sort-arrow" [attr.data-state]="sortState('inicio')">{{ sortGlyph('inicio') }}</span></th>
              <th class="th-sort" (click)="toggleOpSort('fim')">Fim <span class="sort-arrow" [attr.data-state]="sortState('fim')">{{ sortGlyph('fim') }}</span></th>
              <th>Anom?</th>
            </tr></thead>
            <tbody>
              @if (opCtrl.erro()) {
                <!-- mesmo canal do modo agrupado (é o MESMO controlador — só muda o endpoint) -->
                <tr><td colspan="9">
                  <app-erro-carga [mensagem]="opCtrl.erro()" (tentarNovamente)="opCtrl.load()" />
                </td></tr>
              } @else if (opCtrl.rows().length === 0) {
                <tr><td colspan="9" class="empty-state">{{ opCtrl.loading() ? 'Carregando...' : 'Nenhuma entrada.' }}</td></tr>
              } @else {
                @for (e of opCtrl.rows(); track e['id']) {
                  <tr class="row-clickable" [class.row-editado]="e['editado']" (dblclick)="openEntrada(e)">
                    <td>
                      <strong>{{ e['sala_nome'] }}</strong>
                      @if (e['editado']) {
                        <span class="badge-editado" [title]="'Editado em ' + (e['ultima_edicao_em'] | fmtDateTime)"></span>
                      }
                    </td>
                    <td>{{ e['data'] | fmtDate }}</td>
                    <td>{{ e['operador_nome'] }}</td>
                    <td>{{ e['tipo_evento'] }}</td>
                    <td>{{ e['nome_evento'] }}</td>
                    <td>{{ e['horario_pauta'] | fmtTime }}</td>
                    <td>{{ e['horario_inicio'] | fmtTime }}</td>
                    <td>{{ e['horario_termino'] | fmtTime }}</td>
                    <td [class]="e['houve_anormalidade'] ? 'badge-falha' : 'badge-ok'">{{ e['houve_anormalidade'] ? 'SIM' : 'Não' }}</td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Paginação + Relatório -->
      <div class="pag-report-row">
        <div class="report-controls">
          <select [(ngModel)]="reportFormat" class="ctrl-select">
            <option value="">Selecione a extensão...</option>
            <option value="pdf">PDF</option>
            <option value="docx">DOCX</option>
          </select>
          <button class="btn-report" [disabled]="!reportFormat" (click)="gerarRelatorioOp()">Gerar Relatório</button>
        </div>
        <app-pagination [meta]="opCtrl.meta()!" (pageChange)="opCtrl.onPage($event)" (limitChange)="opCtrl.onLimit($event)" />
      </div>
    </section>

    <!-- ════════════ RELATÓRIOS DE ANORMALIDADES ════════════ -->
    <section>
      <div class="section-header">
        <h2>Relatórios de Anormalidades</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="anomCtrl.searchText" (input)="anomCtrl.onSearch()" placeholder="Buscar por data, local, ..." class="search-input search-wide">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th><app-column-filter [col]="anomCols[0]" [distinctValues]="gd(anomCtrl.meta(),'data')" [currentSort]="anomCtrl.state.sort" [currentDir]="anomCtrl.state.direction" (sortChange)="anomCtrl.onSort($event)" (filterChange)="anomCtrl.onFilter($event)" /></th>
            <th><app-column-filter [col]="anomCols[1]" [distinctValues]="gd(anomCtrl.meta(),'sala')" [currentSort]="anomCtrl.state.sort" [currentDir]="anomCtrl.state.direction" (sortChange)="anomCtrl.onSort($event)" (filterChange)="anomCtrl.onFilter($event)" /></th>
            <th>Registrado por</th>
            <th>Descrição</th>
            <th>Solucionada</th>
            <th>Prejuízo</th>
            <th>Reclamação</th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (anomCtrl.erro()) {
              <tr><td colspan="8">
                <app-erro-carga [mensagem]="anomCtrl.erro()" (tentarNovamente)="anomCtrl.load()" />
              </td></tr>
            } @else if (anomCtrl.rows().length === 0) {
              <tr><td colspan="8" class="empty-state">{{ anomCtrl.loading() ? 'Carregando...' : 'Nenhuma anormalidade.' }}</td></tr>
            } @else {
              @for (a of anomCtrl.rows(); track a['id']) {
                <tr>
                  <td>{{ a['data'] | fmtDate }}</td>
                  <td>{{ a['sala_nome'] }}</td>
                  <td>{{ a['registrado_por'] }}</td>
                  <td [title]="a['descricao_anormalidade']">{{ truncate(a['descricao_anormalidade'], 50) }}</td>
                  <td [class]="a['resolvida_pelo_operador'] ? 'badge-ok' : 'badge-falha'" style="font-weight:700">{{ a['resolvida_pelo_operador'] ? 'Sim' : 'Não' }}</td>
                  <td [class]="a['houve_prejuizo'] ? 'badge-falha' : ''" [style.font-weight]="a['houve_prejuizo'] ? '700' : '400'">{{ a['houve_prejuizo'] ? 'Sim' : 'Não' }}</td>
                  <td [class]="a['houve_reclamacao'] ? 'badge-falha' : ''" [style.font-weight]="a['houve_reclamacao'] ? '700' : '400'">{{ a['houve_reclamacao'] ? 'Sim' : 'Não' }}</td>
                  <td><button class="btn-xs" (click)="openAnormalidade(a)">Detalhes</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <div class="pag-report-row">
        <div class="report-controls">
          <select [(ngModel)]="anomReportFormat" class="ctrl-select">
            <option value="">Selecione a extensão...</option>
            <option value="pdf">PDF</option>
            <option value="docx">DOCX</option>
          </select>
          <button class="btn-report" [disabled]="!anomReportFormat" (click)="gerarRelatorioAnom()">Gerar Relatório</button>
        </div>
        <app-pagination [meta]="anomCtrl.meta()!" (pageChange)="anomCtrl.onPage($event)" (limitChange)="anomCtrl.onLimit($event)" />
      </div>
    </section>

    <!-- ════════════ VERIFICAÇÃO DE PLENÁRIOS ════════════ -->
    <section>
      <div class="section-header">
        <h2>Verificação de Plenários</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="chkCtrl.searchText" (input)="chkCtrl.onSearch()" placeholder="Buscar..." class="search-input">
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/checklists/relatorio', 'pdf')">PDF</button>
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/checklists/relatorio', 'docx')">DOCX</button>
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th style="width:30px"></th>
            <th>
              <app-column-filter [col]="chkCols[0]"
                [distinctValues]="gd(chkCtrl.meta(), 'sala')"
                [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction"
                (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="chkCols[1]"
                [distinctValues]="gd(chkCtrl.meta(), 'data')"
                [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction"
                (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="chkCols[2]"
                [distinctValues]="gd(chkCtrl.meta(), 'nome')"
                [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction"
                (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" />
            </th>
            <th>Início</th>
            <th>Término</th>
            <th>Duração</th>
            <th>
              <app-column-filter [col]="chkCols[3]"
                [distinctValues]="gd(chkCtrl.meta(), 'status')"
                [currentSort]="chkCtrl.state.sort" [currentDir]="chkCtrl.state.direction"
                (sortChange)="chkCtrl.onSort($event)" (filterChange)="chkCtrl.onFilter($event)" />
            </th>
            <th>Ação</th>
          </tr></thead>
          <tbody>
            @if (chkCtrl.erro()) {
              <tr><td colspan="9">
                <app-erro-carga [mensagem]="chkCtrl.erro()" (tentarNovamente)="chkCtrl.load()" />
              </td></tr>
            } @else if (chkCtrl.rows().length === 0) {
              <tr><td colspan="9" class="empty-state">{{ chkCtrl.loading() ? 'Carregando...' : 'Nenhum checklist encontrado.' }}</td></tr>
            } @else {
              @for (chk of chkCtrl.rows(); track chk['id']) {
                <tr [class.row-editado]="chk['editado']">
                  <td><button class="btn-toggle" (click)="toggleAccordion(chk)">{{ chk['_expanded'] ? '▼' : '▶' }}</button></td>
                  <td>
                    <strong>{{ chk['sala_nome'] || chk['sala'] }}</strong>
                    @if (chk['editado']) {
                      <span class="badge-editado" [title]="'Editado em ' + (chk['ultima_edicao_em'] | fmtDateTime)"></span>
                    }
                  </td>
                  <td>{{ chk['data'] | fmtDate }}</td>
                  <td>{{ chk['operador_nome'] }}</td>
                  <td>{{ chk['hora_inicio_testes'] | fmtTime }}</td>
                  <td>{{ chk['hora_termino_testes'] | fmtTime }}</td>
                  <td>{{ calcDuracaoChk(chk) }}</td>
                  <td [class]="chk['status'] === 'Falha' ? 'badge-falha' : 'badge-ok'">{{ chk['status'] || 'Ok' }}</td>
                  <td><button class="btn-xs" (click)="openChecklistDetail(chk)">Formulário</button></td>
                </tr>
                @if (chk['_expanded']) {
                  <tr class="accordion-row">
                    <td colspan="9">
                      <strong class="accordion-title">Detalhes da Verificação:</strong>
                      @if (!chk['itens']) {
                        <p class="text-muted-sm">Carregando...</p>
                      } @else if (asArr(chk['itens']).length === 0) {
                        <p class="text-muted-sm">Nenhum item encontrado.</p>
                      } @else {
                        <table class="sub-table">
                          <thead><tr><th>Item verificado</th><th>Status</th><th>Descrição</th></tr></thead>
                          <tbody>
                            @for (it of asArr(chk['itens']); track it['id'] || $index) {
                              <tr>
                                <td>{{ it['item_nome'] || it['item'] }}</td>
                                <td [class]="it['tipo_widget'] === 'text' ? '' : (it['status'] === 'Falha' ? 'badge-falha' : 'badge-ok')">{{ it['tipo_widget'] === 'text' ? 'Texto' : it['status'] }}</td>
                                <td>{{ it['descricao_falha'] || it['valor_texto'] || '-' }}</td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      }
                    </td>
                  </tr>
                }
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="chkCtrl.meta()!" (pageChange)="chkCtrl.onPage($event)" (limitChange)="chkCtrl.onLimit($event)" />
    </section>
  `,
  styles: [`
    .grid-cards { margin:16px 0 28px; }
    section { margin-bottom:32px; }
    .controls-row { display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:8px; }
    .controls-left { display:flex; align-items:center; gap:12px; }
    .controls-right { display:flex; align-items:center; gap:6px; }
    .check-label { display:flex; align-items:center; gap:6px; font-size:.85rem; cursor:pointer; input{cursor:pointer;} }
    .btn-rds { background:#16a34a; color:#fff; border:none; border-radius:6px; padding:5px 14px; font-size:.8rem; font-weight:600; cursor:pointer; &:hover{background:#15803d;} &:disabled{opacity:.5;cursor:not-allowed;} }
    .th-sort { cursor:pointer; user-select:none; white-space:nowrap; &:hover{background:var(--row-hover);} }
    .sort-arrow { font-size:.7rem; margin-left:4px; color:var(--muted); }
    .sort-arrow[data-state="asc"], .sort-arrow[data-state="desc"] { color:#000; }
  `],
})
export class AdminOperacaoAudioComponent implements OnInit {
  private api = inject(ApiService);

  // ── Column definitions ──
  sessCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  entCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
  ];
  anomCols: ColumnFilterDef[] = [
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'sala', label: 'Local', type: 'text' },
  ];
  chkCols: ColumnFilterDef[] = [
    { key: 'sala', label: 'Local', type: 'text' },
    { key: 'data', label: 'Data', type: 'date' },
    { key: 'nome', label: 'Verificado por', type: 'text' },
    { key: 'status', label: 'Status', type: 'text', sortable: false },
  ];

  // ── Checklists ──
  chkCtrl = new TableStateController(this.api, {
    endpoint: '/api/admin/dashboard/checklists', defaultSort: 'data', defaultDir: 'desc',
  });

  // ── Operações (endpoint dinâmico: agrupado por sala × lista plana) ──
  groupBySala = true;
  opCtrl = new TableStateController(this.api, {
    endpoint: () => this.groupBySala
      ? '/api/admin/dashboard/operacoes'
      : '/api/admin/dashboard/operacoes/entradas',
    defaultSort: 'data', defaultDir: 'desc',
  });
  reportFormat = '';

  // ── RDS ──
  rdsAnos = signal<number[]>([]);
  rdsMeses = signal<number[]>([]);
  rdsAno = '';
  rdsMes = '';

  // ── Anormalidades ──
  anomCtrl = new TableStateController(this.api, {
    endpoint: '/api/admin/dashboard/anormalidades/lista', defaultSort: 'data', defaultDir: 'desc',
  });
  anomReportFormat = '';

  ngOnInit(): void {
    this.opCtrl.load();
    this.anomCtrl.load();
    this.chkCtrl.load();
    this.loadRdsAnos();
  }

  // ═══ OPERAÇÕES ═══

  onGroupChange(): void {
    this.opCtrl.state.page = 1;
    this.opCtrl.filters = {};
    this.opCtrl.load();
  }

  toggleSessao(s: any): void {
    s['_exp'] = !s['_exp'];
    if (s['_exp'] && !s['_entradas']) {
      this.api.get<any>('/api/admin/dashboard/operacoes/entradas-sessao', { registro_id: s['id'] as number }).subscribe({
        next: (res: any) => {
          s['_entradas'] = res?.data ?? [];
          s['_is_plenario_principal'] = res?.is_plenario_principal ?? false;
          this.opCtrl.rows.set([...this.opCtrl.rows()]);
        },
        error: () => { s['_entradas'] = []; this.opCtrl.rows.set([...this.opCtrl.rows()]); },
      });
    }
  }

  openEntrada(e: any): void {
    const id = e['id'];
    if (id) window.open(`/admin/operacao/detalhe?entrada_id=${id}`, '_blank');
  }

  // Toggle asc → desc → off (off volta ao default: data desc)
  toggleOpSort(key: string): void {
    const st = this.opCtrl.state;
    if (st.sort !== key) { st.sort = key; st.direction = 'asc'; }
    else if (st.direction === 'asc') { st.direction = 'desc'; }
    else { st.sort = 'data'; st.direction = 'desc'; }
    st.page = 1;
    this.opCtrl.load();
  }
  sortState(key: string): 'asc' | 'desc' | 'off' {
    if (this.opCtrl.state.sort !== key) return 'off';
    return this.opCtrl.state.direction === 'asc' ? 'asc' : 'desc';
  }
  sortGlyph(key: string): string {
    const s = this.sortState(key);
    return s === 'asc' ? '▲' : s === 'desc' ? '▼' : '▽';
  }

  gerarRelatorioOp(): void {
    const endpoint = this.groupBySala
      ? '/api/admin/dashboard/operacoes/relatorio'
      : '/api/admin/dashboard/operacoes/entradas/relatorio';
    this.api.downloadReport(endpoint, buildReportParams(this.reportFormat, this.opCtrl.state.sort, this.opCtrl.state.direction, this.opCtrl.state.search, this.opCtrl.filters));
  }

  // ═══ RDS ═══

  loadRdsAnos(): void {
    this.api.get<any>('/api/admin/operacoes/rds/anos').subscribe({
      next: (res: any) => { this.rdsAnos.set(res?.data || res?.anos || []); },
      error: () => {},
    });
  }

  onAnoChange(): void {
    this.rdsMes = '';
    this.rdsMeses.set([]);
    if (!this.rdsAno) return;
    this.api.get<any>('/api/admin/operacoes/rds/meses', { ano: +this.rdsAno }).subscribe({
      next: (res: any) => { this.rdsMeses.set(res?.data || res?.meses || []); },
      error: () => {},
    });
  }

  gerarRds(): void {
    if (!this.rdsAno || !this.rdsMes) return;
    this.api.getBlob('/api/admin/operacoes/rds/gerar', { ano: this.rdsAno, mes: this.rdsMes })
      .subscribe(blob => this.api.baixarBlob(blob, `RDS_${this.rdsAno}-${String(this.rdsMes).padStart(2, '0')}.xlsx`));
  }

  // ═══ ANORMALIDADES ═══

  openAnormalidade(a: any): void {
    if (a['id']) window.open(`/admin/anormalidade/detalhe?id=${a['id']}`, '_blank');
  }

  gerarRelatorioAnom(): void {
    this.api.downloadReport('/api/admin/dashboard/anormalidades/lista/relatorio',
      buildReportParams(this.anomReportFormat, this.anomCtrl.state.sort, this.anomCtrl.state.direction, this.anomCtrl.state.search, this.anomCtrl.filters));
  }

  // ═══ CHECKLISTS (Verificação de Plenários) ═══

  toggleAccordion(row: Record<string,unknown>): void {
    row['_expanded'] = !row['_expanded'];
    if (row['_expanded'] && !row['itens']) {
      this.api.get<any>('/api/admin/checklist/detalhe', { checklist_id: row['id'] as number }).subscribe({
        next: (res: any) => {
          const data = res?.data ?? res;
          row['itens'] = data?.itens ?? [];
          this.chkCtrl.rows.set([...this.chkCtrl.rows()]);
        },
        error: () => { row['itens'] = []; this.chkCtrl.rows.set([...this.chkCtrl.rows()]); },
      });
    }
  }
  openChecklistDetail(chk: Record<string,unknown>): void {
    window.open(`/admin/checklist/detalhe?checklist_id=${chk['id']}`, '_blank');
  }
  calcDuracaoChk(chk: Record<string, unknown>): string {
    return duracaoHms(String(chk['hora_inicio_testes'] || ''), String(chk['hora_termino_testes'] || ''));
  }
  downloadReport(endpoint: string, format: string): void { this.api.downloadReport(endpoint, { format }); }

  // ═══ HELPERS (delegam para table.helpers.ts) ═══

  mesNome = mesNome;
  gd = getDistinct;
  asArr = asArray;
  truncate = truncate;
  formatEvento = (s: Record<string, unknown>): string => formatEventoDe(s, 'ultimo_evento');
}

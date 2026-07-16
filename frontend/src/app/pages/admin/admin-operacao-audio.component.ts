import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef } from '../../shared/components/column-filter.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { getDistinct, buildReportParams, mesNome } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { erroCargaMsg } from '../../core/helpers/http.helpers';
import { ToastService } from '../../shared/components/toast.component';
import { asArray, asString, truncate, formatEvento as formatEventoDe } from '../../core/helpers/format.helpers';
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

      @if (erroRds()) {
        <!-- Canal de erro dos selects do RDS (C18/F68): antes, a falha de anos/meses deixava os
             selects vazios e o botão desabilitado SEM explicação. O retry refaz a carga que falhou. -->
        <div style="margin-bottom:10px">
          <app-erro-carga [mensagem]="erroRds()" (tentarNovamente)="retryRds()" />
        </div>
      }

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
                     as outras) e o rodapé não mente (o motor limpa o meta). As sub-tabelas dos
                     acordeões (entradas da sessão, itens do checklist) ganharam canal POR LINHA no
                     C18 (F66). -->
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
                        @if (s['_erroEntradas']) {
                          <!-- Erro POR LINHA (C18/F66): a falha do drill-down não pode virar "Nenhuma
                               entrada registrada nesta sessão." (a linha só está na lista porque HÁ
                               entradas). Nada é gravado em _entradas no erro → reabrir refaz o GET. -->
                          <app-erro-carga [mensagem]="asStr(s['_erroEntradas'])" (tentarNovamente)="carregarEntradasSessao(s)" />
                        } @else if (!s['_entradas']) {
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
                      @if (chk['_erroItens']) {
                        <!-- Erro POR LINHA (C18/F66): mesma cura do acordeão das sessões — sem gravar
                             o array vazio no erro, "Nenhum item encontrado." fica reservado ao vazio REAL. -->
                        <app-erro-carga [mensagem]="asStr(chk['_erroItens'])" (tentarNovamente)="carregarItensChecklist(chk)" />
                      } @else if (!chk['itens']) {
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
  private toast = inject(ToastService);

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
  /**
   * Canal de erro das DUAS cargas dos selects do RDS (C18/F68): '' = sem erro. As cargas são
   * mutuamente exclusivas na prática (sem anos carregados não há como pedir meses), por isso um
   * signal único; `erroRdsOrigem` diz qual carga o retry deve refazer.
   */
  erroRds = signal('');
  private erroRdsOrigem: 'anos' | 'meses' = 'anos';
  /** Tokens de recência (C18/F68): retry reclicado / troca rápida de ano deixam cargas em voo. */
  private seqRdsAnos = 0;
  private seqRdsMeses = 0;

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
    // No erro nada é gravado em `_entradas` (C18/F66): reabrir o acordeão volta a tentar.
    if (s['_exp'] && !s['_entradas']) this.carregarEntradasSessao(s);
  }

  /** GET das entradas de UMA sessão — também é o retry da caixa de erro da linha (C18/F66). */
  carregarEntradasSessao(s: any): void {
    // Recência POR LINHA: o retry reclicado põe duas cargas da MESMA sessão em voo — um erro velho
    // não pode sobrescrever o sucesso mais novo. O token vive no objeto, que é a identidade da linha.
    const seq = (s['_seqEntradas'] = (((s['_seqEntradas'] as number) || 0) + 1));
    s['_erroEntradas'] = '';
    this.opCtrl.rows.set([...this.opCtrl.rows()]);
    this.api.get<any>('/api/admin/dashboard/operacoes/entradas-sessao', { registro_id: s['id'] as number }).subscribe({
      next: (res: any) => {
        if (seq !== s['_seqEntradas']) return;
        s['_entradas'] = res?.data ?? [];
        s['_is_plenario_principal'] = res?.is_plenario_principal ?? false;
        this.opCtrl.rows.set([...this.opCtrl.rows()]);
      },
      error: err => {
        if (seq !== s['_seqEntradas']) return;
        s['_erroEntradas'] = erroCargaMsg(err, 'Não foi possível carregar as entradas desta sessão.');
        this.opCtrl.rows.set([...this.opCtrl.rows()]);
      },
    });
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

  /** Anos disponíveis para o RDS; também é o retry da caixa de erro quando ela veio daqui (C18/F68). */
  loadRdsAnos(): void {
    const seq = ++this.seqRdsAnos;
    this.erroRds.set('');
    this.api.get<any>('/api/admin/operacoes/rds/anos').subscribe({
      next: (res: any) => {
        if (seq !== this.seqRdsAnos) return;
        this.rdsAnos.set(res?.data || res?.anos || []);
      },
      error: err => {
        if (seq !== this.seqRdsAnos) return;   // um erro velho não sobrescreve o sucesso mais novo
        this.erroRdsOrigem = 'anos';
        this.erroRds.set(erroCargaMsg(err, 'Não foi possível carregar os anos do RDS.'));
      },
    });
  }

  onAnoChange(): void {
    this.rdsMes = '';
    this.rdsMeses.set([]);
    // O bump vem ANTES do early-return: voltar o select ao placeholder também invalida a carga
    // de meses em voo — a resposta do ano abandonado não pode pintar caixa órfã nem popular meses.
    const seq = ++this.seqRdsMeses;
    // Só o erro de MESES fica obsoleto quando o ano muda; um erro de ANOS continua valendo
    // (sem anos carregados o select nem teria opção para disparar este handler).
    if (this.erroRdsOrigem === 'meses') this.erroRds.set('');
    if (!this.rdsAno) return;
    this.api.get<any>('/api/admin/operacoes/rds/meses', { ano: +this.rdsAno }).subscribe({
      next: (res: any) => {
        if (seq !== this.seqRdsMeses) return;   // troca rápida de ano: só a resposta mais nova vale
        this.rdsMeses.set(res?.data || res?.meses || []);
      },
      error: err => {
        if (seq !== this.seqRdsMeses) return;
        this.erroRdsOrigem = 'meses';
        this.erroRds.set(erroCargaMsg(err, 'Não foi possível carregar os meses do RDS.'));
      },
    });
  }

  /** Retry da caixa do RDS: refaz a carga que FALHOU (anos ou meses do ano corrente). */
  retryRds(): void {
    if (this.erroRdsOrigem === 'meses') this.onAnoChange();
    else this.loadRdsAnos();
  }

  gerarRds(): void {
    if (!this.rdsAno || !this.rdsMes) return;
    this.api.getBlob('/api/admin/operacoes/rds/gerar', { ano: this.rdsAno, mes: this.rdsMes }).subscribe({
      next: blob => this.api.baixarBlob(blob, `RDS_${this.rdsAno}-${String(this.rdsMes).padStart(2, '0')}.xlsx`),
      // C18/F68 (idioma do F38/C13b): sem handler, a falha era um unhandled do RxJS — nada na tela,
      // e o admin reclicava achando que o clique não pegou. Ação pontual → toast basta.
      error: err => this.toast.error(erroCargaMsg(err, 'Não foi possível gerar o RDS.')),
    });
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
    // No erro nada é gravado em `itens` (C18/F66): reabrir o acordeão volta a tentar.
    if (row['_expanded'] && !row['itens']) this.carregarItensChecklist(row);
  }

  /** GET dos itens de UM checklist — também é o retry da caixa de erro da linha (C18/F66). */
  carregarItensChecklist(row: Record<string,unknown>): void {
    const seq = (row['_seqItens'] = (((row['_seqItens'] as number) || 0) + 1));   // recência por linha
    row['_erroItens'] = '';
    this.chkCtrl.rows.set([...this.chkCtrl.rows()]);
    this.api.get<any>('/api/admin/checklist/detalhe', { checklist_id: row['id'] as number }).subscribe({
      next: (res: any) => {
        if (seq !== row['_seqItens']) return;
        const data = res?.data ?? res;
        row['itens'] = data?.itens ?? [];
        this.chkCtrl.rows.set([...this.chkCtrl.rows()]);
      },
      error: err => {
        if (seq !== row['_seqItens']) return;
        row['_erroItens'] = erroCargaMsg(err, 'Não foi possível carregar os itens desta verificação.');
        this.chkCtrl.rows.set([...this.chkCtrl.rows()]);
      },
    });
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
  asStr = asString;
  truncate = truncate;
  formatEvento = (s: Record<string, unknown>): string => formatEventoDe(s, 'ultimo_evento');
}

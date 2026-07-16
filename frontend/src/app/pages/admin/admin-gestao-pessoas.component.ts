import { Component, inject, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PaginationComponent } from '../../shared/components/pagination.component';
import { ColumnFilterComponent, ColumnFilterDef } from '../../shared/components/column-filter.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { getDistinct } from '../../core/helpers/table.helpers';
import { TableStateController } from '../../core/helpers/table-state.controller';
import { AuthService } from '../../core/services/auth.service';
import { FeatureToggleDirective } from '../../shared/directives/feature-toggle.directive';

@Component({
  selector: 'app-admin-gestao-pessoas',
  standalone: true,
  imports: [RouterLink, FormsModule, PaginationComponent, ColumnFilterComponent, ErroCargaComponent, FeatureToggleDirective],
  template: `
    <h1>Gestão de pessoas</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <!-- Cards de navegação -->
    <div class="grid-cards cols-3">
      <a routerLink="/admin/novo-operador" class="card-custom card-nav">Cadastro de Operador</a>
      <a routerLink="/admin/novo-tecnico" class="card-custom card-nav">Cadastro de Técnico</a>
      <a [featureToggle]="'inserirAvisos'" #fAvisos="featureToggle" [routerLink]="fAvisos.enabled() ? '/admin/avisos-sala' : null" class="card-custom card-nav">Inserir Avisos</a>
      <a routerLink="/admin/escala" class="card-custom card-nav">Escala Semanal</a>
      <a [featureToggle]="'pontoBanco'" #fPonto="featureToggle" [routerLink]="fPonto.enabled() ? '/admin/ponto' : null" class="card-custom card-nav">Ponto e Banco</a>
    </div>

    <!-- ═══ Operadores ═══ -->
    <section>
      <div class="section-header">
        <h2>Operadores de Áudio</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="opCtrl.searchText" (input)="opCtrl.onSearch()" placeholder="Buscar..." class="search-input">
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/operadores/relatorio', 'pdf')">PDF</button>
          <button class="btn-report" (click)="downloadReport('/api/admin/dashboard/operadores/relatorio', 'docx')">DOCX</button>
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>
              <app-column-filter [col]="opCols[0]"
                [distinctValues]="getDistinct(opCtrl.meta(), 'nome')"
                [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction"
                (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="opCols[1]"
                [distinctValues]="getDistinct(opCtrl.meta(), 'email')"
                [currentSort]="opCtrl.state.sort" [currentDir]="opCtrl.state.direction"
                (sortChange)="opCtrl.onSort($event)" (filterChange)="opCtrl.onFilter($event)" />
            </th>
            <th style="width:110px">Ação</th>
          </tr></thead>
          <tbody>
            @if (opCtrl.erro()) {
              <!-- Canal de erro (C7/C13b): "Nenhum operador encontrado." numa carga que FALHOU é
                   mentira — e aqui ela leva o admin a recadastrar quem já existe. Um canal por
                   tabela: o erro dos operadores não apaga a lista de técnicos ao lado. -->
              <tr><td colspan="3">
                <app-erro-carga [mensagem]="opCtrl.erro()" (tentarNovamente)="opCtrl.load()" />
              </td></tr>
            } @else if (opCtrl.rows().length === 0) {
              <tr><td colspan="3" class="empty-state">{{ opCtrl.loading() ? 'Carregando...' : 'Nenhum operador encontrado.' }}</td></tr>
            } @else {
              @for (op of opCtrl.rows(); track op['id']) {
                <tr>
                  <td><strong>{{ op['nome_completo'] || op['nome'] }}</strong></td>
                  <td>{{ op['email'] }}</td>
                  <td><button class="btn-xs" (click)="abrirPerfil('operador', op)">Perfil</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="opCtrl.meta()!" (pageChange)="opCtrl.onPage($event)" (limitChange)="opCtrl.onLimit($event)" />
    </section>

    <!-- ═══ Técnicos ═══ -->
    <section>
      <div class="section-header">
        <h2>Técnicos</h2>
        <div class="header-actions">
          <input type="text" [(ngModel)]="tecCtrl.searchText" (input)="tecCtrl.onSearch()" placeholder="Buscar..." class="search-input">
        </div>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead><tr>
            <th>
              <app-column-filter [col]="tecCols[0]"
                [distinctValues]="getDistinct(tecCtrl.meta(), 'nome')"
                [currentSort]="tecCtrl.state.sort" [currentDir]="tecCtrl.state.direction"
                (sortChange)="tecCtrl.onSort($event)" (filterChange)="tecCtrl.onFilter($event)" />
            </th>
            <th>
              <app-column-filter [col]="tecCols[1]"
                [distinctValues]="getDistinct(tecCtrl.meta(), 'email')"
                [currentSort]="tecCtrl.state.sort" [currentDir]="tecCtrl.state.direction"
                (sortChange)="tecCtrl.onSort($event)" (filterChange)="tecCtrl.onFilter($event)" />
            </th>
            <th style="width:110px">Ação</th>
          </tr></thead>
          <tbody>
            @if (tecCtrl.erro()) {
              <tr><td colspan="3">
                <app-erro-carga [mensagem]="tecCtrl.erro()" (tentarNovamente)="tecCtrl.load()" />
              </td></tr>
            } @else if (tecCtrl.rows().length === 0) {
              <tr><td colspan="3" class="empty-state">{{ tecCtrl.loading() ? 'Carregando...' : 'Nenhum técnico cadastrado.' }}</td></tr>
            } @else {
              @for (t of tecCtrl.rows(); track t['id']) {
                <tr>
                  <td><strong>{{ t['nome_completo'] || t['nome'] }}</strong></td>
                  <td>{{ t['email'] }}</td>
                  <td><button class="btn-xs" (click)="abrirPerfil('tecnico', t)">Perfil</button></td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      <app-pagination [meta]="tecCtrl.meta()!" (pageChange)="tecCtrl.onPage($event)" (limitChange)="tecCtrl.onLimit($event)" />
    </section>

    <!-- ═══ Administradores do Sistema (somente o master) ═══ -->
    @if (isMaster()) {
      <section>
        <div class="section-header">
          <h2>Administradores do Sistema</h2>
          <div class="header-actions">
            <button class="btn-xs" (click)="novoAdmin()">Novo Admin</button>
          </div>
        </div>
        <div class="table-container">
          <table class="data-table">
            <thead><tr>
              <th>
                <app-column-filter [col]="admCols[0]"
                  [distinctValues]="getDistinct(admCtrl.meta(), 'nome')"
                  [currentSort]="admCtrl.state.sort" [currentDir]="admCtrl.state.direction"
                  (sortChange)="admCtrl.onSort($event)" (filterChange)="admCtrl.onFilter($event)" />
              </th>
              <th>
                <app-column-filter [col]="admCols[1]"
                  [distinctValues]="getDistinct(admCtrl.meta(), 'email')"
                  [currentSort]="admCtrl.state.sort" [currentDir]="admCtrl.state.direction"
                  (sortChange)="admCtrl.onSort($event)" (filterChange)="admCtrl.onFilter($event)" />
              </th>
              <th style="width:110px">Ação</th>
            </tr></thead>
            <tbody>
              @if (admCtrl.erro()) {
                <tr><td colspan="3">
                  <app-erro-carga [mensagem]="admCtrl.erro()" (tentarNovamente)="admCtrl.load()" />
                </td></tr>
              } @else if (admCtrl.rows().length === 0) {
                <tr><td colspan="3" class="empty-state">{{ admCtrl.loading() ? 'Carregando...' : 'Nenhum administrador encontrado.' }}</td></tr>
              } @else {
                @for (a of admCtrl.rows(); track a['id']) {
                  <tr>
                    <td><strong>{{ a['nome_completo'] || a['nome'] }}</strong></td>
                    <td>{{ a['email'] }}</td>
                    <td><button class="btn-xs" (click)="abrirPerfil('administrador', a)">Perfil</button></td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <app-pagination [meta]="admCtrl.meta()!" (pageChange)="admCtrl.onPage($event)" (limitChange)="admCtrl.onLimit($event)" />
      </section>
    }
  `,
  styles: [`
    .grid-cards { margin:16px 0 28px; }
    section { margin-bottom:28px; }
  `],
})
export class AdminGestaoPessoasComponent implements OnInit {
  private api = inject(ApiService);
  private router = inject(Router);
  private auth = inject(AuthService);
  isMaster = this.auth.isMaster;

  // ── Column definitions ──
  opCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  tecCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];
  admCols: ColumnFilterDef[] = [
    { key: 'nome', label: 'Nome', type: 'text' },
    { key: 'email', label: 'E-mail', type: 'text' },
  ];

  // ── Operadores ──
  opCtrl = new TableStateController(this.api, {
    endpoint: '/api/admin/dashboard/operadores', defaultSort: 'nome', defaultDir: 'asc',
  });

  // ── Técnicos ──
  tecCtrl = new TableStateController(this.api, {
    endpoint: '/api/admin/dashboard/tecnicos', defaultSort: 'nome', defaultDir: 'asc',
  });

  // ── Administradores (somente master; tabela sem busca global) ──
  admCtrl = new TableStateController(this.api, {
    endpoint: '/api/admin/dashboard/administradores', defaultSort: 'nome', defaultDir: 'asc',
  });

  // ── Navegação para o Perfil (operador ou técnico) ──
  abrirPerfil(tipo: 'operador' | 'tecnico' | 'administrador', row: Record<string,unknown>): void {
    this.router.navigate([`/admin/${tipo}/perfil`], { queryParams: { id: row['id'] } });
  }

  ngOnInit(): void { this.opCtrl.load(); this.tecCtrl.load(); if (this.isMaster()) this.admCtrl.load(); }

  novoAdmin(): void { this.router.navigate(['/admin/novo-admin']); }

  // ── Relatórios ──
  downloadReport(endpoint: string, format: string): void { this.api.downloadReport(endpoint, { format }); }

  // ── Helpers ──
  getDistinct = getDistinct;
}

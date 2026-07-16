import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SafeResourceUrl } from '@angular/platform-browser';
import { EMPTY, catchError, switchMap } from 'rxjs';
import { MetabaseService } from '../../core/services/metabase.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Painel Administrativo</h1>

    <!-- Cards de navegação (3 colunas × 2 linhas) -->
    <div class="grid-cards cols-3">
      <a routerLink="/home" class="card-custom card-nav">Página Inicial dos Operadores</a>
      <a routerLink="/admin/operacao-audio" class="card-custom card-nav">Operação de Áudio</a>
      <a routerLink="/admin/agenda" class="card-custom card-nav">Agenda Legislativa</a>
      <a routerLink="/tecnico" class="card-custom card-nav">Página Inicial dos Técnicos</a>
      <a routerLink="/admin/area-tecnica" class="card-custom card-nav">Área Técnica</a>
      <a routerLink="/admin/gestao-pessoas" class="card-custom card-nav">Gestão de Pessoas</a>
    </div>

    <!-- Indicadores (dashboard Metabase embutido) -->
    <section class="dash-section">
      @if (erroEmbed()) {
        <div class="erro-embed">{{ erroEmbed() }}</div>
      } @else if (iframeUrl()) {
        <iframe [src]="iframeUrl()!" title="Indicadores" frameborder="0" allowtransparency></iframe>
      } @else {
        <div class="info-embed">Carregando indicadores…</div>
      }
    </section>
  `,
  styles: [`
    .grid-cards { margin-bottom:28px; }
    .dash-section {
      background:var(--card); border:1px solid var(--border); border-radius:8px;
      overflow:hidden; min-height:400px; display:flex;
    }
    iframe { width:100%; height:2200px; border:0; }
    .info-embed, .erro-embed {
      flex:1; display:flex; align-items:center; justify-content:center;
      font-size:.9rem; color:var(--muted); padding:24px; text-align:center; min-height:400px;
    }
    .erro-embed { color:#b00020; }
  `],
})
export class AdminDashboardComponent implements OnInit {
  private metabase = inject(MetabaseService);

  iframeUrl = signal<SafeResourceUrl | null>(null);
  erroEmbed = signal<string | null>(null);

  ngOnInit(): void {
    // ordem error→message preservada em cada fallback (inversa à default do helper)
    this.metabase.listarDashboards().pipe(
      catchError(e => {
        this.erroEmbed.set(httpErrorMsg(e, 'Não foi possível carregar os indicadores.', ['error', 'message']));
        return EMPTY;
      }),
      switchMap(lista => {
        if (!lista.length) {
          this.erroEmbed.set('Nenhum dashboard de indicadores configurado.');
          return EMPTY;
        }
        return this.metabase.embedUrl(lista[0].id).pipe(
          catchError(e => {
            this.erroEmbed.set(httpErrorMsg(e, 'Não foi possível abrir o painel de indicadores.', ['error', 'message']));
            return EMPTY;
          }),
        );
      }),
    ).subscribe(safeUrl => this.iframeUrl.set(safeUrl));
  }
}

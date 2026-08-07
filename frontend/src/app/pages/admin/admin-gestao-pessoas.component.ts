import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SafeResourceUrl } from '@angular/platform-browser';
import { EMPTY, catchError, switchMap } from 'rxjs';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { FeatureToggleDirective } from '../../shared/directives/feature-toggle.directive';
import { FeatureFlagService } from '../../core/services/feature-flags.service';
import { MetabaseService } from '../../core/services/metabase.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { AdminTabelasPessoalComponent } from './admin-tabelas-pessoal.component';

/** Página do catálogo de dashboards a que os indicadores desta tela pertencem. */
const CONTEXTO_DASHBOARD = 'GESTAO_PESSOAS';

/**
 * Gestão de pessoas — cards de navegação e, no corpo, os indicadores por operador.
 *
 * Enquanto os indicadores estiverem desligados, o corpo continua sendo as três
 * listagens de pessoal, e o card que leva ao Quadro de Pessoal não aparece: a tela
 * fica exatamente como era antes de existir dashboard.
 */
@Component({
  selector: 'app-admin-gestao-pessoas',
  standalone: true,
  imports: [RouterLink, ErroCargaComponent, FeatureToggleDirective, AdminTabelasPessoalComponent],
  template: `
    <h1>Gestão de pessoas</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <!-- Cards de navegação -->
    <div class="grid-cards cols-3">
      <a routerLink="/admin/novo-operador" class="card-custom card-nav">Cadastro de Operador</a>
      <a routerLink="/admin/novo-tecnico" class="card-custom card-nav">Cadastro de Técnico</a>
      <a [featureToggle]="'inserirAvisos'" #fAvisos="featureToggle" [routerLink]="fAvisos.enabled() ? '/admin/avisos-sala' : null" class="card-custom card-nav">Comunicações</a>
      <a routerLink="/admin/escala" class="card-custom card-nav">Escala Semanal</a>
      <a [featureToggle]="'pontoBanco'" #fPonto="featureToggle" [routerLink]="fPonto.enabled() ? '/admin/ponto' : null" class="card-custom card-nav">Ponto e Banco</a>
      @if (dashboardAtivo) {
        <a routerLink="/admin/quadro-pessoal" class="card-custom card-nav">Quadro de Pessoal</a>
      }
    </div>

    @if (dashboardAtivo) {
      <!-- Indicadores (dashboard Metabase embutido) -->
      @if (erroEmbed()) {
        <app-erro-carga [mensagem]="erroEmbed()" (tentarNovamente)="carregarIndicadores()" />
      } @else {
        <section class="dash-section">
          @if (iframeUrl()) {
            <iframe [src]="iframeUrl()!" title="Indicadores de gestão de pessoas" frameborder="0" allowtransparency></iframe>
          } @else {
            <div class="info-embed">Carregando indicadores…</div>
          }
        </section>
      }
    } @else {
      <app-admin-tabelas-pessoal />
    }
  `,
  styles: [`
    .grid-cards { margin:16px 0 28px; }
    .dash-section { background:var(--card); border:1px solid var(--border); border-radius:8px; overflow:hidden; min-height:400px; display:flex; }
    iframe { width:100%; height:3000px; border:0; }
    .info-embed { flex:1; display:flex; align-items:center; justify-content:center; font-size:.9rem; color:var(--muted); padding:24px; text-align:center; min-height:400px; }
  `],
})
export class AdminGestaoPessoasComponent implements OnInit {
  private flags = inject(FeatureFlagService);
  private metabase = inject(MetabaseService);

  readonly dashboardAtivo = this.flags.isEnabled('dashboardPessoas');

  iframeUrl = signal<SafeResourceUrl | null>(null);
  erroEmbed = signal<string>('');

  ngOnInit(): void {
    if (this.dashboardAtivo) this.carregarIndicadores();
  }

  carregarIndicadores(): void {
    this.erroEmbed.set('');
    this.iframeUrl.set(null);
    this.metabase.listarDashboards(CONTEXTO_DASHBOARD).pipe(
      catchError(e => {
        // A ordem ['error','message'] é a inversa da default do helper: o corpo do backend
        // traz a mensagem útil, e o texto genérico do HttpErrorResponse não ajuda ninguém.
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

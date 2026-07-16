import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SafeResourceUrl } from '@angular/platform-browser';
import { DashboardCard, MetabaseService } from '../../core/services/metabase.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';

/**
 * Página /admin/analise — exibe dashboards Metabase embutidos via static
 * embedding. Sidebar à esquerda lista os dashboards cadastrados em
 * INT_METABASE_DASHBOARD; o card selecionado aparece em iframe na área principal.
 */
@Component({
  selector: 'app-admin-analise',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Painel de Indicadores</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel Administrativo</a>

    <div class="analise-layout">
      <aside class="sidebar">
        @if (carregandoLista()) {
          <p class="info">Carregando…</p>
        } @else if (erroLista()) {
          <p class="erro">{{ erroLista() }}</p>
        } @else if (dashboards().length === 0) {
          <p class="info">Nenhum dashboard cadastrado.</p>
        } @else {
          <ul class="card-list">
            @for (d of dashboards(); track d.id) {
              <li>
                <button type="button"
                        class="card-item"
                        [class.ativo]="d.id === selecionadoId()"
                        (click)="selecionar(d)">
                  <span class="titulo">{{ d.titulo }}</span>
                  @if (d.descricao) {
                    <span class="descricao">{{ d.descricao }}</span>
                  }
                </button>
              </li>
            }
          </ul>
        }
      </aside>

      <main class="conteudo">
        @if (erroEmbed()) {
          <div class="erro-embed">{{ erroEmbed() }}</div>
        } @else if (carregandoEmbed()) {
          <div class="info-embed">Carregando dashboard…</div>
        } @else if (iframeUrl()) {
          <iframe [src]="iframeUrl()!"
                  title="Dashboard"
                  frameborder="0"
                  allowtransparency></iframe>
        } @else {
          <div class="info-embed">Selecione um dashboard à esquerda.</div>
        }
      </main>
    </div>
  `,
  styles: [`
    :host { display:flex; flex-direction:column; height:calc(100vh - 120px); }
    .analise-layout { display:flex; gap:16px; flex:1; min-height:0; }
    .sidebar {
      width:280px; min-width:240px; flex-shrink:0;
      background:var(--card); border:1px solid var(--border); border-radius:8px;
      padding:14px; overflow-y:auto;
    }
    .info, .erro { font-size:.85rem; margin:8px 0; }
    .erro { color:#b00020; }
    .card-list { list-style:none; padding:0; margin:0; display:flex; flex-direction:column; gap:8px; }
    .card-item {
      width:100%; display:flex; flex-direction:column; gap:2px;
      padding:10px 12px; border:1px solid var(--border); border-radius:6px;
      background:#fff; color:var(--text); text-align:left; cursor:pointer;
      font-size:.85rem; transition:background .15s, border-color .15s;
      &:hover { background:#f5f7fa; border-color:var(--primary); }
      &.ativo { background:var(--primary); color:#fff; border-color:var(--primary);
        .descricao { color:rgba(255,255,255,.85); }
      }
    }
    .titulo { font-weight:600; }
    .descricao { font-size:.75rem; color:var(--muted); line-height:1.2; }
    .conteudo {
      flex:1; min-width:0;
      background:var(--card); border:1px solid var(--border); border-radius:8px;
      overflow:hidden; display:flex;
    }
    iframe { width:100%; height:100%; border:0; }
    .info-embed, .erro-embed {
      flex:1; display:flex; align-items:center; justify-content:center;
      font-size:.9rem; color:var(--muted); padding:24px; text-align:center;
    }
    .erro-embed { color:#b00020; }
  `],
})
export class AdminAnaliseComponent implements OnInit {
  private metabase = inject(MetabaseService);

  dashboards = signal<DashboardCard[]>([]);
  carregandoLista = signal(true);
  erroLista = signal<string | null>(null);

  selecionadoId = signal<string | null>(null);
  iframeUrl = signal<SafeResourceUrl | null>(null);
  carregandoEmbed = signal(false);
  erroEmbed = signal<string | null>(null);

  ngOnInit(): void {
    this.carregarLista();
  }

  private carregarLista(): void {
    this.metabase.listarDashboards().subscribe({
      next: lista => {
        this.dashboards.set(lista);
        this.carregandoLista.set(false);
        if (lista.length > 0) {
          this.selecionar(lista[0]);
        }
      },
      error: e => {
        this.carregandoLista.set(false);
        // ordem error→message preservada (era o extrairMensagem local)
        this.erroLista.set(httpErrorMsg(e, 'Não foi possível carregar os dashboards.', ['error', 'message']));
      },
    });
  }

  selecionar(d: DashboardCard): void {
    if (this.selecionadoId() === d.id) return;
    this.selecionadoId.set(d.id);
    this.iframeUrl.set(null);
    this.erroEmbed.set(null);
    this.carregandoEmbed.set(true);
    this.metabase.embedUrl(d.id).subscribe({
      next: safeUrl => {
        this.carregandoEmbed.set(false);
        this.iframeUrl.set(safeUrl);
      },
      error: e => {
        this.carregandoEmbed.set(false);
        this.erroEmbed.set(httpErrorMsg(e, 'Não foi possível abrir o dashboard.', ['error', 'message']));
      },
    });
  }
}

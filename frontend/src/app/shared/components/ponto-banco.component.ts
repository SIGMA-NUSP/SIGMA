import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { homeRouteForRole } from '../../core/helpers/auth.helpers';
import { erroCargaMsg } from '../../core/helpers/http.helpers';
import { FeatureToggleDirective } from '../directives/feature-toggle.directive';
import { AjudaChatComponent } from './ajuda-chat.component';
import { BancoHorasPessoalComponent } from './banco-horas-pessoal.component';
import { ErroCargaComponent } from './erro-carga.component';
import { FolhasPontoListaComponent, MinhaFolha } from './folhas-ponto-lista.component';
import { RegistroManualPontoComponent } from './registro-manual-ponto.component';

/**
 * Página "Ponto e Banco" compartilhada por operadores, técnicos e admins com
 * folha (card "Meu Ponto e Banco" do /admin/ponto). Navegação por cards (mesmo
 * padrão de /admin/ponto): cada card abre o seu conteúdo; clicar em outro card
 * oculta o anterior. O backend (/api/ponto/**) usa o usuário autenticado,
 * então o mesmo componente serve os três papéis.
 */
@Component({
  selector: 'app-ponto-banco',
  standalone: true,
  imports: [AjudaChatComponent, RouterLink, BancoHorasPessoalComponent, ErroCargaComponent,
    FeatureToggleDirective, FolhasPontoListaComponent, RegistroManualPontoComponent],
  template: `
    <h1>Ponto e Banco de Horas</h1>
    <a [routerLink]="backLink()" class="back-link">&larr; Voltar</a>

    <!-- ═══ Acordeão: o conteúdo de cada card abre logo abaixo dele e empurra os demais ═══ -->
    <div class="acordeao">
      <!-- Minhas folhas de ponto -->
      <button class="card-custom card-pick" [class.active]="activeCard() === 'folhas'" (click)="toggleCard('folhas')">
        <strong>Minhas folhas de ponto</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'folhas'">
        @if (loading()) {
          <p class="text-muted-sm">Carregando...</p>
        } @else if (erro()) {
          <!-- Canal de erro (C7/F42): a carga que falhou NÃO pode se passar por "não há folhas" -->
          <app-erro-carga [mensagem]="erro()" (tentarNovamente)="carregarFolhas()" />
        } @else if (folhas().length === 0) {
          <p class="text-muted-sm">Nenhuma folha de ponto disponível.</p>
        } @else {
          <app-folhas-ponto-lista [folhas]="folhas()" />
        }
      </div>

      <!-- Solicitação de folga pelo saldo de banco de horas — card visível porém inerte enquanto a flag estiver desligada -->
      <button class="card-custom card-pick" [featureToggle]="'solicitarBancoHoras'" #fBanco="featureToggle"
              [class.active]="activeCard() === 'banco'" [disabled]="!fBanco.enabled()"
              (click)="toggleCard('banco')">
        <strong>Solicitar Banco de Horas</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'banco'">
        <!-- O card fechado devolve a seleção de dias ao zero: o componente fica oculto, nunca destruído. -->
        <app-banco-horas-pessoal [aberto]="activeCard() === 'banco'" />
      </div>

      <!-- Registro manual de ponto — card visível porém inerte enquanto a flag estiver desligada -->
      <button class="card-custom card-pick" [featureToggle]="'registroManualPonto'" #fManual="featureToggle"
              [class.active]="activeCard() === 'manual'" [disabled]="!fManual.enabled()"
              (click)="toggleCard('manual')">
        <strong>Registro manual de ponto</strong>
      </button>
      <div class="painel" [hidden]="activeCard() !== 'manual'">
        <app-registro-manual-ponto />
      </div>
    </div>

    <!-- Chat de ajuda com IA (teste piloto: só esta página) — se auto-esconde sem a flag 'ajudaIa' -->
    <app-ajuda-chat pagina="ponto-banco" titulo="Ajuda — Ponto e Banco" />
  `,
  styles: [`
    .acordeao { display:flex; flex-direction:column; gap:12px; margin-bottom:24px; }
    .card-pick { width:100%; }
    /* Painel oculto não ocupa espaço (sem gap residual no flex). Mantém o DOM montado
       p/ preservar o que o usuário digitou ao fechar/reabrir. */
    .painel[hidden] { display:none; }
  `],
})
export class PontoBancoComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);

  // Navegação por cards (mesmo padrão de /admin/ponto)
  activeCard = signal<'folhas' | 'manual' | 'banco' | null>(null);

  folhas = signal<MinhaFolha[]>([]);
  loading = signal(true);
  /** Canal de erro da carga (C7/F42): '' = sem erro. Distingue a falha do "não há folhas". */
  erro = signal('');
  /** Admin chega aqui pelo card "Meu Ponto e Banco" — o Voltar retorna a /admin/ponto. */
  backLink = computed(() =>
    this.auth.role() === 'administrador' ? '/admin/ponto' : homeRouteForRole(this.auth.role()));

  ngOnInit(): void {
    this.carregarFolhas();
  }

  /** Carga das folhas publicadas do usuário; também é o retry da caixa de erro (C7/F42). */
  carregarFolhas(): void {
    this.loading.set(true);
    this.erro.set('');
    this.api.get<any>('/api/ponto/minhas-folhas').subscribe({
      next: res => { this.folhas.set(res.data || []); this.loading.set(false); },
      error: err => {
        this.folhas.set([]);
        this.loading.set(false);
        this.erro.set(erroCargaMsg(err,
          'Não foi possível carregar as folhas de ponto.'));
      },
    });
  }

  /**
   * Abre o card; clicar de novo no mesmo card fecha (acordeão de 1 aberto por vez).
   * O conteúdo é ocultado via [hidden] no template — o componente permanece montado,
   * preservando o que o usuário já digitou (ex.: horas no registro manual).
   */
  toggleCard(card: 'folhas' | 'manual' | 'banco'): void {
    this.activeCard.update(cur => (cur === card ? null : card));
  }
}

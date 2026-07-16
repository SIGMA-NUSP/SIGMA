import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { FeatureToggleDirective } from '../../shared/directives/feature-toggle.directive';

@Component({
  selector: 'app-tecnico-home',
  standalone: true,
  imports: [RouterLink, FeatureToggleDirective],
  template: `
    <h1>Página Principal — Técnicos</h1>

    <div class="grid-cards cols-3">
      <a [featureToggle]="'pontoBanco'" #fPonto="featureToggle"
         [routerLink]="fPonto.enabled() ? '/ponto' : null" class="card-custom card-stack">
        <strong>Ponto e Banco</strong>
        <span class="text-muted-sm">{{ fPonto.enabled() ? 'Abrir' : 'Indisponível' }}</span>
      </a>
      <a routerLink="/tecnico/agenda" class="card-custom card-stack">
        <strong>Agenda Legislativa</strong>
        <span class="text-muted-sm">Abrir</span>
      </a>
      <div class="card-custom card-placeholder">
        <strong>Em construção</strong>
        <span class="text-muted-sm">Novas funcionalidades em breve.</span>
      </div>
      @if (auth.isAdmin()) {
        <a routerLink="/admin" class="card-custom card-stack card-admin">
          <strong>Painel Administrativo</strong>
          <span class="text-muted-sm">Voltar para Admin</span>
        </a>
      }
    </div>
  `,
  styles: [`
    .grid-cards { margin-top:16px; }
    .card-placeholder { display:flex; flex-direction:column; gap:4px; opacity:.7; }
  `],
})
export class TecnicoHomeComponent {
  auth = inject(AuthService);
}

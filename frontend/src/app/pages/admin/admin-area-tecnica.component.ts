import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-admin-area-tecnica',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Área Técnica</h1>
    <a routerLink="/admin" class="back-link">&larr; Voltar ao Painel</a>

    <p class="placeholder">Nenhuma ferramenta disponível no momento.</p>
  `,
  styles: [`
    .placeholder { color:var(--muted); margin-top:16px; font-size:.9rem; }
  `],
})
export class AdminAreaTecnicaComponent {}

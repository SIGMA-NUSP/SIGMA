import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Detalhe de um cadastro de aviso. Aberto por duplo-clique numa linha da tabela
 * "Avisos Cadastrados". Nesta primeira entrega em homologação é apenas um
 * placeholder "em construção"; a tela definitiva (que varia conforme o tipo do
 * aviso) será montada numa próxima atualização e consumirá
 * GET /api/admin/avisos/{id}/detalhe.
 */
@Component({
  selector: 'app-admin-aviso-detalhe',
  standalone: true,
  imports: [RouterLink],
  template: `
    <h1>Detalhe do Aviso</h1>
    <a routerLink="/admin/avisos-sala" class="back-link">&larr; Voltar</a>

    <div class="card-custom em-construcao">
      <div class="ec-icon">🚧</div>
      <h2>Em construção</h2>
      <p class="text-muted-sm">
        Esta página exibirá os detalhes do cadastro de aviso (mensagens, locais/destinatários
        e operadores que marcaram ciência), conforme o tipo do aviso.
        Disponível em uma próxima atualização do sistema.
      </p>
    </div>
  `,
  styles: [`
    .em-construcao { max-width: 560px; margin: 24px auto; text-align: center; padding: 48px 24px; }
    .ec-icon { font-size: 2.5rem; margin-bottom: 8px; }
    .em-construcao h2 { color: var(--muted, #6b7280); font-weight: 500; margin: 0 0 8px; }
    .text-muted-sm { color: var(--muted, #6b7280); font-size: .9rem; line-height: 1.5; }
  `],
})
export class AdminAvisoDetalheComponent {}

import { Component, computed, input } from '@angular/core';
import { categoriaAviso } from '../../core/helpers/aviso-categoria.helpers';

/**
 * Selo de uma categoria de comunicação: pill com ícone + nome em caixa alta. Usado pelo popup, pela
 * tabela de comunicações e pela página de detalhe — é o que garante que o mesmo aviso se identifique
 * igual nas três telas.
 *
 * O host carrega a classe de cor da categoria, então as cores (variáveis de `styles.scss`) valem
 * mesmo onde não há um container com a categoria — como uma célula de tabela.
 */
@Component({
  selector: 'app-categoria-selo',
  standalone: true,
  host: { '[class]': 'cat().classe' },
  template: `
    <span class="cat-selo">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        @for (d of cat().icone; track $index) { <path [attr.d]="d"></path> }
      </svg>
      {{ cat().label }}
    </span>
  `,
  styles: [`:host { display: inline-flex; }`],
})
export class CategoriaSeloComponent {
  /** Código da categoria vindo do backend; ausente ou desconhecido cai em Aviso. */
  categoria = input<string | null | undefined>();

  cat = computed(() => categoriaAviso(this.categoria()));
}

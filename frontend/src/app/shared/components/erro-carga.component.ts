import { Component, input, output } from '@angular/core';

/**
 * Caixa de erro de CARGA, com ação de retry — o idioma único do "canal de erro"
 * das listagens (C7): falha de leitura precisa ficar visivelmente distinta de
 * "não há dados", com mensagem e um gatilho para tentar de novo.
 *
 * Usada pelas telas que consomem o signal `erro` do TableStateController e pelos
 * handlers de carga do módulo Ponto que não usam o controlador (mesmo idioma,
 * outra instância). Serve tanto em bloco quanto dentro de um <td> de tabela.
 */
@Component({
  selector: 'app-erro-carga',
  standalone: true,
  template: `
    <div class="error-box erro-carga" role="alert">
      <span class="erro-carga-msg">{{ mensagem() }}</span>
      <button type="button" class="btn-xs" (click)="tentarNovamente.emit()">Tentar novamente</button>
    </div>
  `,
  styles: [`
    .erro-carga { display:flex; align-items:center; justify-content:space-between; gap:12px; text-align:left; }
    .erro-carga-msg { flex:1; }
  `],
})
export class ErroCargaComponent {
  mensagem = input<string>('');
  tentarNovamente = output<void>();
}

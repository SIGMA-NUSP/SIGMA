import { Component } from '@angular/core';

@Component({
  selector: 'app-footer',
  standalone: true,
  template: `
    <footer class="site-footer">
      <div class="site-footer__inner">
        Senado Federal - Praça dos Três Poderes - Brasília DF - CEP 70165-900
      </div>
    </footer>
  `,
  styles: [`
    .site-footer {
      position: fixed;
      bottom: 0;
      left: 0;
      right: 0;
      height: 32px;
      background: var(--senado-azul);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 100;
    }
    .site-footer__inner {
      color: #fff;
      font-size: 11px;
      text-align: center;
    }
  `],
})
export class FooterComponent {}

import { Component, inject } from '@angular/core';
import { EnvService } from '../../core/services/env.service';

@Component({
  selector: 'app-env-banner',
  standalone: true,
  template: `
    @if (env.label()) {
      <div class="env-banner" role="status" aria-label="Ambiente não-produção">
        ⚠ AMBIENTE DE {{ env.label().toUpperCase() }} ⚠
      </div>
    }
  `,
  styles: [`
    .env-banner {
      background: #f2c94c;
      color: #003b63;
      text-align: center;
      font-weight: 700;
      font-size: 13px;
      letter-spacing: 1.5px;
      padding: 4px 12px;
      border-bottom: 1px solid rgba(0, 0, 0, 0.15);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
      position: relative;
      z-index: 1000;
    }
  `],
})
export class EnvBannerComponent {
  env = inject(EnvService);
}

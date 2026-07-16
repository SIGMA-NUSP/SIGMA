import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { EnvBannerComponent } from './shared/components/env-banner.component';
import { EnvService } from './core/services/env.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, EnvBannerComponent],
  template: `
    <app-env-banner />
    <router-outlet />
  `,
})
export class App {
  constructor() {
    inject(EnvService).load();
  }
}

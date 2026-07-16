import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './header.component';
import { FooterComponent } from './footer.component';
import { ToastComponent } from '../shared/components/toast.component';
import { AvisoPopupComponent } from '../shared/components/aviso-popup.component';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent, FooterComponent, ToastComponent, AvisoPopupComponent],
  template: `
    <app-toast />
    <app-aviso-popup />
    <app-header />
    <main class="main-content">
      <router-outlet />
    </main>
    <app-footer />
  `,
})
export class MainLayoutComponent {}

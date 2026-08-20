import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './header.component';
import { FooterComponent } from './footer.component';

/**
 * Layout das telas de autenticação (login, esqueci/redefinir/alterar senha):
 * mesmo header e footer do app, sem o popup de avisos (exige sessão) e sem toast.
 * O header sozinho omite foto, nome e botão Sair quando não há usuário logado.
 */
@Component({
  selector: 'app-auth-layout',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent, FooterComponent],
  template: `
    <app-header />
    <main>
      <router-outlet />
    </main>
    <app-footer />
  `,
})
export class AuthLayoutComponent {}

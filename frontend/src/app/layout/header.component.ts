import { Component, computed, inject } from '@angular/core';
import { AuthService } from '../core/services/auth.service';
import { FOTO_ANONIMA, fotoErrorFallback, resolverFotoUrl } from '../core/helpers/foto.helpers';

@Component({
  selector: 'app-header',
  standalone: true,
  template: `
    <header class="site-header">
      <div class="site-header__inner">
        <div class="logo-container">
          <img class="site-logo" src="assets/imgs/header-senado.png" alt="Senado Federal">
        </div>
        @if (auth.isLoggedIn()) {
          <div class="site-header__right">
            <img class="user-avatar" [src]="userFoto() || ANONIMO" (error)="onImgError($event)" alt="Foto">
            <span class="user-greeting">{{ userName() }}</span>
            <button class="btn-logout" (click)="auth.logout()">Sair</button>
          </div>
        }
      </div>
    </header>
  `,
  styles: [`
    .site-header {
      background: linear-gradient(to bottom, var(--senado-azul) 86%, var(--senado-amarelo) 86% 93%, var(--senado-verde) 93%);
      height: 54px;
      position: relative;
    }
    .site-header__inner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 47px;
      padding: 0 24px;
    }
    .site-logo { width: 180px; height: 45px; }
    .site-header__right {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }
    .user-avatar {
      width: 32px; height: 32px; border-radius: 50%; object-fit: cover;
      border: 2px solid rgba(255,255,255,.5);
      flex-shrink: 0;
    }
    .user-greeting {
      color: #fff; font-size: 0.9rem; font-weight: 600;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
      max-width: 180px;
    }
    .btn-logout {
      background: #7f1d1d;
      color: #fee2e2;
      border: 1px solid #fca5a5;
      border-radius: 999px;
      padding: 5px 18px;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
      flex-shrink: 0;
      &:hover { background: #991b1b; }
    }
    @media (max-width: 480px) {
      .site-header__inner { padding: 0 10px; }
      .site-logo { width: 140px; height: 35px; }
      .site-header__right { gap: 8px; }
      .user-greeting { max-width: 90px; font-size: .8rem; }
      .user-avatar { width: 28px; height: 28px; }
      .btn-logout { padding: 4px 12px; font-size: .75rem; }
    }
  `],
})
export class HeaderComponent {
  auth = inject(AuthService);
  readonly ANONIMO = FOTO_ANONIMA;

  /** Se a foto cadastrada não existir (404), cai para a imagem anônima (sem loop). */
  onImgError(event: Event): void {
    fotoErrorFallback(event);
  }

  userName = computed(() => {
    const u = this.auth.user();
    return u?.nome || u?.name || u?.username || '';
  });
  userFoto = computed(() => resolverFotoUrl(this.auth.user()?.foto_url));
}

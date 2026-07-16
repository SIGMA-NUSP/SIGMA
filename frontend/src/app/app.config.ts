import { ApplicationConfig, ErrorHandler, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withNavigationErrorHandler, TitleStrategy } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { PrefixedTitleStrategy } from './core/title-strategy';
import { FeatureFlagService } from './core/services/feature-flags.service';

/**
 * Carrega as feature flags de `/config.json` (gerado pelo container por env var) ANTES do
 * bootstrap, para que cards e guards já tenham o valor na primeira navegação. Fail-safe:
 * qualquer falha (fetch, JSON inválido, arquivo ausente) → nenhuma flag ligada.
 */
function loadFeatureFlags(): Promise<void> {
  const flags = inject(FeatureFlagService);
  return fetch('/config.json', { cache: 'no-store' })
    .then(res => (res.ok ? res.json() : null))
    .then(cfg => flags.setFlags(cfg?.features))
    .catch(() => flags.setFlags({}));
}

// Detecta falha de carregamento de chunk lazy (bundle antigo em cache após
// um deploy tenta importar arquivo que não existe mais) e recarrega a página
// uma única vez para forçar download do build atual. Sem isso, o usuário fica
// preso na rota anterior — sintoma reportado em Chrome iOS/Android.
const RELOAD_KEY = 'app-chunk-reload-at';
const RELOAD_COOLDOWN_MS = 30_000;

function isChunkLoadError(error: unknown): boolean {
  const err = error as { name?: string; message?: string } | null;
  const msg = err?.message ?? '';
  return err?.name === 'ChunkLoadError'
    || /Loading chunk [^ ]+ failed/i.test(msg)
    || /Failed to fetch dynamically imported module/i.test(msg)
    || /Importing a module script failed/i.test(msg);
}

function reloadIfChunkError(error: unknown): boolean {
  if (!isChunkLoadError(error)) return false;
  const last = parseInt(sessionStorage.getItem(RELOAD_KEY) ?? '0', 10);
  if (Date.now() - last < RELOAD_COOLDOWN_MS) return false;
  sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
  window.location.reload();
  return true;
}

class AppErrorHandler implements ErrorHandler {
  handleError(error: unknown): void {
    if (reloadIfChunkError(error)) return;
    console.error(error);
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideAppInitializer(loadFeatureFlags),
    provideRouter(routes, withNavigationErrorHandler(reloadIfChunkError)),
    provideHttpClient(withInterceptors([authInterceptor])),
    { provide: ErrorHandler, useClass: AppErrorHandler },
    { provide: TitleStrategy, useClass: PrefixedTitleStrategy },
  ],
};

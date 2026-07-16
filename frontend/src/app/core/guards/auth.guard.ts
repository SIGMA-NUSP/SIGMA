import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Route, Router, UrlSegment } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { FeatureFlagService, FeatureFlag } from '../services/feature-flags.service';
import { homeRouteForRole } from '../helpers/auth.helpers';

/** Garante apenas que o usuário está logado. Bloqueia acesso ao app se senha for provisória. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isLoggedIn()) {
    router.navigate(['/login']);
    return false;
  }
  if (auth.senhaProvisoria()) {
    router.navigate(['/alterar-senha']);
    return false;
  }
  return true;
};

/**
 * Guard único para controle de acesso por papel.
 * Lê `data.roles: string[]` da rota. Administrador tem acesso universal.
 * Usuário sem permissão é redirecionado para a home do próprio papel.
 *
 * Uso: `{ path: 'home', canActivate: [roleGuard], data: { roles: ['operador'] } }`
 */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isLoggedIn()) { router.navigate(['/login']); return false; }
  if (auth.isAdmin()) return true;

  const allowed = (route.data?.['roles'] as string[] | undefined) ?? [];
  const role = auth.role();
  if (role && allowed.includes(role)) return true;

  router.navigate([homeRouteForRole(role)]);
  return false;
};

/**
 * Versão CanMatchFn do roleGuard, para usar em rotas com `redirectTo`.
 * Lê `data.roles: string[]` da rota. Não navega — apenas devolve true/false.
 */
export const matchByRole: CanMatchFn = (route: Route, _segments: UrlSegment[]) => {
  const allowed = (route.data?.['roles'] as string[] | undefined) ?? [];
  const role = inject(AuthService).role();
  return !!role && allowed.includes(role);
};

/**
 * Guard de FEATURE FLAG (fábrica). Se a flag estiver **ligada**, libera — deixando o
 * `roleGuard` seguinte (quando houver) aplicar as regras normais de papel. Se **desligada**,
 * nega para TODOS os papéis — inclusive o admin, que tem acesso universal no roleGuard —
 * redirecionando à home do papel e fechando o acesso por link direto na URL.
 * Controlado em runtime por `/config.json` (env FEATURE_* no container).
 *
 * Uso: `{ path: 'ponto', canActivate: [featureFlagGuard('pontoBanco'), roleGuard], data: { roles: [...] } }`
 */
export function featureFlagGuard(flag: FeatureFlag): CanActivateFn {
  return () => {
    if (inject(FeatureFlagService).isEnabled(flag)) return true;

    const auth = inject(AuthService);
    const router = inject(Router);
    if (!auth.isLoggedIn()) { router.navigate(['/login']); return false; }
    router.navigate([homeRouteForRole(auth.role())]);
    return false;
  };
}

/** Regra especial: só o admin master (criar admins). */
export const masterGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn() && auth.isAdmin() && auth.user()?.isMaster) return true;
  if (auth.isLoggedIn()) router.navigate([homeRouteForRole(auth.role())]);
  else router.navigate(['/login']);
  return false;
};

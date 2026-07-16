import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authGuard, roleGuard, matchByRole, masterGuard, featureFlagGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';
import { FeatureFlagService } from '../services/feature-flags.service';

/**
 * T18 (§5.6) — os 4 guards funcionais executados via
 * `TestBed.runInInjectionContext` (usam `inject()` internamente).
 * `AuthService` e `Router` são mockados; os computeds do AuthService viram
 * funções (`vi.fn()`), como no código real (signals são chamados como função).
 */
describe('auth.guard', () => {
  const auth = {
    isLoggedIn: vi.fn(),
    isAdmin: vi.fn(),
    senhaProvisoria: vi.fn(),
    role: vi.fn(),
    user: vi.fn(),
  };
  const router = { navigate: vi.fn() };
  const featureFlags = { isEnabled: vi.fn() };

  beforeEach(() => {
    Object.values(auth).forEach(fn => fn.mockReset());
    router.navigate.mockReset();
    featureFlags.isEnabled.mockReset();
    auth.user.mockReturnValue(null); // default seguro (evita undefined?.isMaster inesperado)
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: FeatureFlagService, useValue: featureFlags },
      ],
    });
  });

  const run = <T>(guard: (...a: any[]) => T, ...args: any[]): T =>
    TestBed.runInInjectionContext(() => guard(...args));

  const rota = (roles?: string[]) => ({ data: roles ? { roles } : {} }) as any;

  describe('authGuard', () => {
    it('deslogado — redireciona para /login e nega', () => {
      auth.isLoggedIn.mockReturnValue(false);
      expect(run(authGuard, rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('senha provisória — redireciona para /alterar-senha e nega', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.senhaProvisoria.mockReturnValue(true);
      expect(run(authGuard, rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/alterar-senha']);
    });

    it('logado e senha definitiva — libera sem navegar', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.senhaProvisoria.mockReturnValue(false);
      expect(run(authGuard, rota(), {})).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('roleGuard', () => {
    it('deslogado — redireciona para /login e nega', () => {
      auth.isLoggedIn.mockReturnValue(false);
      expect(run(roleGuard, rota(['operador']), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('administrador — acesso universal, ignora a lista de papéis', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(true);
      expect(run(roleGuard, rota([]), {})).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('papel na lista permitida — libera', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(false);
      auth.role.mockReturnValue('operador');
      expect(run(roleGuard, rota(['operador']), {})).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('papel fora da lista — nega e redireciona para a home do próprio papel', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(false);
      auth.role.mockReturnValue('tecnico');
      expect(run(roleGuard, rota(['operador']), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/tecnico']); // homeRouteForRole('tecnico')
    });
  });

  describe('matchByRole (CanMatchFn — não navega)', () => {
    it('papel na lista — true', () => {
      auth.role.mockReturnValue('operador');
      expect(run(matchByRole, rota(['operador']), [])).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('papel fora da lista — false, sem navegar', () => {
      auth.role.mockReturnValue('tecnico');
      expect(run(matchByRole, rota(['operador']), [])).toBe(false);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('sem papel (null) — false, sem navegar', () => {
      auth.role.mockReturnValue(null);
      expect(run(matchByRole, rota(['operador']), [])).toBe(false);
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('masterGuard (4 combinações)', () => {
    it('logado + admin + master — libera sem navegar', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(true);
      auth.user.mockReturnValue({ isMaster: true });
      expect(run(masterGuard, rota(), {})).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('logado + admin + NÃO master — nega e vai para a home de admin', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(true);
      auth.user.mockReturnValue({ isMaster: false });
      auth.role.mockReturnValue('administrador');
      expect(run(masterGuard, rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/admin']);
    });

    it('logado + não-admin — nega e vai para a home do próprio papel', () => {
      auth.isLoggedIn.mockReturnValue(true);
      auth.isAdmin.mockReturnValue(false);
      auth.role.mockReturnValue('operador');
      expect(run(masterGuard, rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/home']);
    });

    it('deslogado — nega e vai para /login', () => {
      auth.isLoggedIn.mockReturnValue(false);
      expect(run(masterGuard, rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });
  });

  describe('featureFlagGuard (fábrica — bloqueia quando a flag está desligada)', () => {
    it('flag ligada — libera sem navegar (roleGuard seguinte é quem decide o papel)', () => {
      featureFlags.isEnabled.mockReturnValue(true);
      expect(run(featureFlagGuard('pontoBanco'), rota(), {})).toBe(true);
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('flag desligada + deslogado — nega e vai para /login', () => {
      featureFlags.isEnabled.mockReturnValue(false);
      auth.isLoggedIn.mockReturnValue(false);
      expect(run(featureFlagGuard('pontoBanco'), rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('flag desligada + administrador (acesso universal no roleGuard) — nega mesmo assim e vai para /admin', () => {
      featureFlags.isEnabled.mockReturnValue(false);
      auth.isLoggedIn.mockReturnValue(true);
      auth.role.mockReturnValue('administrador');
      expect(run(featureFlagGuard('inserirAvisos'), rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/admin']);
    });

    it('flag desligada + operador — nega e vai para /home', () => {
      featureFlags.isEnabled.mockReturnValue(false);
      auth.isLoggedIn.mockReturnValue(true);
      auth.role.mockReturnValue('operador');
      expect(run(featureFlagGuard('pontoBanco'), rota(), {})).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/home']);
    });
  });
});

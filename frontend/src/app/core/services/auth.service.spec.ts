import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { User, LoginResponse } from '../models/user.model';

/**
 * T18 (§5.5 — escopo MÍNIMO) — AuthService.
 *
 * O construtor executa `loadFromStorage()` + `trackActivity()`, então
 * `localStorage` é limpo e os fake timers instalados ANTES de instanciar (o
 * service só é criado dentro de cada teste, via `criar()`, depois de semear o
 * storage). Cobre `parseJwt`, os computeds a partir de estado semeado e o
 * `login` (HttpTestingController + `verify()` no afterEach — padrão T21).
 * FORA (briefing): refresh, inatividade e ciclo de vida do timer.
 */
const BASE = environment.apiBaseUrl; // 'http://localhost:8000' (tracked/limpo — obs. T19+T20)

/** JWT sintético: header.payload.assinatura, ambos em base64url (- e _). */
function fakeJwt(payload: Record<string, unknown>): string {
  const seg = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${seg({ alg: 'HS256', typ: 'JWT' })}.${seg(payload)}.assinatura`;
}

/** exp no futuro relativo ao relógio (congelado) — mantém o token válido em loadFromStorage. */
function futuro(): number {
  return Math.floor(Date.now() / 1000) + 3600;
}

describe('AuthService', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-15T12:00:00-03:00'));
    TestBed.configureTestingModule({
      providers: [
        AuthService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();       // nenhuma request pendente (o interval de refresh é fake e não dispara)
    vi.useRealTimers();
    localStorage.clear();
  });

  /** Instancia o service DEPOIS de semear o storage (o construtor lê o storage). */
  function criar(): AuthService {
    return TestBed.inject(AuthService);
  }

  function seedLogado(role: string, user: Record<string, unknown>): void {
    localStorage.setItem('auth_token', fakeJwt({ perfil: role, exp: futuro() }));
    localStorage.setItem('auth_user', JSON.stringify({ user, role }));
  }

  describe('parseJwt', () => {
    it('token válido — devolve o payload decodificado', () => {
      const payload = { sub: 'u1', perfil: 'operador', exp: futuro() };
      expect(criar().parseJwt(fakeJwt(payload))).toEqual(payload);
    });

    it('base64url — decodifica o alfabeto url-safe (- e _)', () => {
      const service = criar();
      const payload = { a: '>>>???' }; // gera + e / no base64 padrão → - e _ no url-safe
      const token = fakeJwt(payload);
      // sanidade: o segmento do payload de fato usa o alfabeto url-safe
      expect(token.split('.')[1]).toMatch(/[-_]/);
      expect(service.parseJwt(token)).toEqual(payload);
    });

    it('lixo — devolve null (partes ≠ 3 ou base64 inválido)', () => {
      const service = criar();
      expect(service.parseJwt('lixo')).toBeNull();
      expect(service.parseJwt('a.b')).toBeNull();
      expect(service.parseJwt('aaa.@@@.ccc')).toBeNull();
    });
  });

  describe('computeds a partir do estado semeado no storage', () => {
    it('sem storage — deslogado, sem papel algum', () => {
      const service = criar();
      expect(service.isLoggedIn()).toBe(false);
      expect(service.isAdmin()).toBe(false);
      expect(service.isTecnico()).toBe(false);
      expect(service.isMaster()).toBe(false);
      expect(service.temFolhaPonto()).toBe(false);
    });

    it('administrador master — isLoggedIn/isAdmin/isMaster true', () => {
      seedLogado('administrador', { id: 'a1', username: 'adm', email: 'a@x', isMaster: true });
      const service = criar();
      expect(service.isLoggedIn()).toBe(true);
      expect(service.isAdmin()).toBe(true);
      expect(service.isTecnico()).toBe(false);
      expect(service.isMaster()).toBe(true);
    });

    it('administrador NÃO master — isAdmin true, isMaster false', () => {
      seedLogado('administrador', { id: 'a1', username: 'adm', email: 'a@x', isMaster: false });
      const service = criar();
      expect(service.isAdmin()).toBe(true);
      expect(service.isMaster()).toBe(false);
    });

    it('técnico — isTecnico true; isMaster exige admin, logo false mesmo com a flag', () => {
      seedLogado('tecnico', { id: 't1', username: 'tec', email: 't@x', isMaster: true });
      const service = criar();
      expect(service.isTecnico()).toBe(true);
      expect(service.isAdmin()).toBe(false);
      expect(service.isMaster()).toBe(false);
    });

    it('temFolhaPonto reflete a flag tem_folha_ponto do usuário', () => {
      seedLogado('operador', { id: 'o1', username: 'op', email: 'o@x', tem_folha_ponto: true });
      const service = criar();
      expect(service.temFolhaPonto()).toBe(true);
    });
  });

  describe('login', () => {
    it('POST /api/login com campos pt-BR; grava token e user no storage e nos signals', () => {
      const service = criar();
      const token = fakeJwt({ perfil: 'operador', exp: futuro() });
      const user: User = { id: 'o1', username: 'joao', email: 'joao@x', role: 'operador', tem_folha_ponto: true };
      const resp: LoginResponse = { ok: true, token, user, role: 'operador' };

      let emitido = false;
      service.login('joao', 'senha-x').subscribe(() => (emitido = true));

      const req = http.expectOne(`${BASE}/api/login`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ usuario: 'joao', senha: 'senha-x' }); // campos pt-BR
      req.flush(resp);

      expect(emitido).toBe(true);
      expect(localStorage.getItem('auth_token')).toBe(token);
      expect(service.getToken()).toBe(token);
      expect(service.isLoggedIn()).toBe(true);
      expect(service.role()).toBe('operador');
      expect(service.temFolhaPonto()).toBe(true);
      expect(JSON.parse(localStorage.getItem('auth_user')!)).toEqual({ user, role: 'operador' });
    });
  });
});

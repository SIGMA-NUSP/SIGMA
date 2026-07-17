import { TestBed } from '@angular/core/testing';
import { HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { of } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

/**
 * authInterceptor (HttpInterceptorFn funcional). Com token: clona a request
 * adicionando Authorization Bearer + cabeçalhos anti-cache. Sem token: repassa a
 * MESMA referência (assert por identidade, `toBe`). Executado dentro de
 * `runInInjectionContext` (o interceptor usa `inject(AuthService)`); `next` é um
 * espião que captura a request repassada.
 */
describe('authInterceptor', () => {
  const auth = { getToken: vi.fn() };

  beforeEach(() => {
    vi.restoreAllMocks();
    auth.getToken.mockReset();
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
  });

  /** Executa o interceptor com um `next` espião e devolve a request que ele recebeu. */
  function intercept(req: HttpRequest<unknown>): { next: ReturnType<typeof vi.fn>; passed: HttpRequest<unknown> } {
    const next = vi.fn().mockReturnValue(of(null)) as unknown as HttpHandlerFn;
    TestBed.runInInjectionContext(() => authInterceptor(req, next));
    const spy = next as unknown as ReturnType<typeof vi.fn>;
    return { next: spy, passed: spy.mock.calls[0][0] as HttpRequest<unknown> };
  }

  it('com token — clona a request com Authorization Bearer e cabeçalhos anti-cache', () => {
    auth.getToken.mockReturnValue('meu-token');
    const req = new HttpRequest('GET', '/api/x');

    const { next, passed } = intercept(req);

    expect(next).toHaveBeenCalledTimes(1);
    expect(passed).not.toBe(req); // clonada, não a original
    expect(passed.headers.get('Authorization')).toBe('Bearer meu-token');
    expect(passed.headers.get('Cache-Control')).toBe('no-cache, no-store, must-revalidate');
    expect(passed.headers.get('Pragma')).toBe('no-cache');
    // a request original permanece intacta
    expect(req.headers.has('Authorization')).toBe(false);
  });

  it('sem token — repassa a request original pela MESMA referência (sem clonar)', () => {
    auth.getToken.mockReturnValue(null);
    const req = new HttpRequest('GET', '/api/x');

    const { next, passed } = intercept(req);

    expect(next).toHaveBeenCalledTimes(1);
    expect(passed).toBe(req); // identidade — nada foi adicionado
    expect(passed.headers.has('Authorization')).toBe(false);
  });
});

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Title } from '@angular/platform-browser';
import { environment } from '../../../environments/environment';
import { EnvService } from './env.service';

/**
 * T21 — EnvService (§5.5/B4): GET /api/health; sucesso seta `label` e prefixa o
 * document.title com `[LABEL] `; label vazio NÃO prefixa; erro → ''. Usa HttpClient
 * DIRETO (não ApiService) → provideHttpClientTesting; `verify()` em afterEach (B7).
 * O prefixo é observado no `Title` real (mesma instância injetada no service).
 */
const HEALTH = `${environment.apiBaseUrl}/api/health`;

describe('EnvService', () => {
  let svc: EnvService;
  let http: HttpTestingController;
  let title: Title;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [EnvService, provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(EnvService);
    http = TestBed.inject(HttpTestingController);
    title = TestBed.inject(Title);
    title.setTitle('SIGMA'); // título base determinístico por teste
  });

  afterEach(() => {
    http.verify();
  });

  it('label inicial é vazio antes de load()', () => {
    expect(svc.label()).toBe('');
  });

  it('sucesso com envLabel → seta label e prefixa o título com [LABEL]', () => {
    svc.load();
    http.expectOne(HEALTH).flush({ ok: true, envLabel: 'homolog' });
    expect(svc.label()).toBe('homolog');
    expect(title.getTitle()).toBe('[HOMOLOG] SIGMA');
  });

  it('envLabel com espaços é trimado; o prefixo usa upper-case', () => {
    svc.load();
    http.expectOne(HEALTH).flush({ ok: true, envLabel: '  homolog  ' });
    expect(svc.label()).toBe('homolog');
    expect(title.getTitle()).toBe('[HOMOLOG] SIGMA');
  });

  it('envLabel ausente → label vazio e título NÃO prefixado', () => {
    svc.load();
    http.expectOne(HEALTH).flush({ ok: true }); // sem envLabel
    expect(svc.label()).toBe('');
    expect(title.getTitle()).toBe('SIGMA');
  });

  it('erro HTTP → label vazio (branch de erro) e título intocado', () => {
    svc.load();
    http.expectOne(HEALTH).flush('falha', { status: 500, statusText: 'Server Error' });
    expect(svc.label()).toBe('');
    expect(title.getTitle()).toBe('SIGMA');
  });

  it('não duplica o prefixo se o título já começa com [LABEL] (idempotência)', () => {
    svc.load();
    http.expectOne(HEALTH).flush({ ok: true, envLabel: 'homolog' });
    expect(title.getTitle()).toBe('[HOMOLOG] SIGMA');
    svc.load(); // segunda carga com o mesmo label não re-prefixa
    http.expectOne(HEALTH).flush({ ok: true, envLabel: 'homolog' });
    expect(title.getTitle()).toBe('[HOMOLOG] SIGMA');
  });
});

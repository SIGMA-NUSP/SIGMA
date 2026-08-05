import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DomSanitizer } from '@angular/platform-browser';
import { ApiService } from './api.service';
import { MetabaseService } from './metabase.service';

/**
 * MetabaseService: só `embedUrl` tem lógica própria (extrai `r.url` e passa por
 * DomSanitizer.bypassSecurityTrustResourceUrl). `listarDashboards` é delegação
 * pura → no máximo um verify de endpoint (assertar o retorno testaria o mock).
 */
describe('MetabaseService', () => {
  let svc: MetabaseService;
  let get: ReturnType<typeof vi.fn>;
  let bypass: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    get = vi.fn();
    TestBed.configureTestingModule({
      providers: [MetabaseService, { provide: ApiService, useValue: { get } }],
    });
    svc = TestBed.inject(MetabaseService);
    bypass = vi.spyOn(TestBed.inject(DomSanitizer), 'bypassSecurityTrustResourceUrl');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('embedUrl extrai r.url e devolve o resultado de bypassSecurityTrustResourceUrl', () => {
    bypass.mockReturnValue('SAFE' as never);
    get.mockReturnValue(of({ url: 'https://mb/embed/token' }));
    let emitido: unknown;
    svc.embedUrl('42').subscribe(v => (emitido = v));
    expect(get).toHaveBeenCalledWith('/api/admin/metabase/dashboards/42/embed-url');
    expect(bypass).toHaveBeenCalledWith('https://mb/embed/token');
    expect(emitido).toBe('SAFE');
  });

  it('listarDashboards consulta o endpoint do catálogo (delegação pura — só verify)', () => {
    get.mockReturnValue(of([]));
    svc.listarDashboards().subscribe();
    expect(get).toHaveBeenCalledWith('/api/admin/metabase/dashboards');
  });

  it('listarDashboards com página pedida manda o contexto como parâmetro', () => {
    get.mockReturnValue(of([]));
    svc.listarDashboards('GESTAO_PESSOAS').subscribe();
    expect(get).toHaveBeenCalledWith('/api/admin/metabase/dashboards', { contexto: 'GESTAO_PESSOAS' });
  });

  it('sem página pedida NÃO manda parâmetro nenhum — o backend é quem resolve o padrão', () => {
    get.mockReturnValue(of([]));
    svc.listarDashboards(undefined).subscribe();
    expect(get).toHaveBeenCalledWith('/api/admin/metabase/dashboards');
    expect(get.mock.calls[0]).toHaveLength(1);
  });
});

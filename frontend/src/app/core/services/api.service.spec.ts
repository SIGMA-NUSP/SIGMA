import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { ApiService } from './api.service';

/**
 * ApiService — a borda HTTP do app. Foco na LÓGICA do `getList` (serialização JSON
 * de filters/periodo e OMISSÃO de params falsy) e nos métodos de blob com efeito
 * colateral; os verbos one-liner (put/patch/delete/postForm) são delegação pura ao
 * HttpClient — casos representativos (post + get com params) provam o prefixo
 * `url()`. `HttpTestingController.verify()` em todo afterEach: zero request pendente.
 */
const BASE = environment.apiBaseUrl; // 'http://localhost:8000'

describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();      // B7: nenhuma request pendente
    vi.useRealTimers(); // idempotente onde não há fake timer
    vi.restoreAllMocks();
  });

  describe('getList — serialização e omissão de params', () => {
    it('serializa filters e periodo com JSON.stringify e envia os params truthy', () => {
      const filters = { sala: { values: ['1', '2'] } };
      const periodo = { inicio: '2026-01-01', fim: '2026-01-31' };
      api
        .getList('/api/x', {
          page: 2, limit: 20, sort: 'nome', direction: 'desc', search: 'ana', filters, periodo,
        })
        .subscribe();

      const req = http.expectOne(r => r.url === `${BASE}/api/x`);
      const p = req.request.params;
      expect(p.get('page')).toBe('2');
      expect(p.get('limit')).toBe('20');
      expect(p.get('sort')).toBe('nome');
      expect(p.get('direction')).toBe('desc');
      expect(p.get('search')).toBe('ana');
      expect(p.get('filters')).toBe(JSON.stringify(filters));
      expect(p.get('periodo')).toBe(JSON.stringify(periodo));
      req.flush({ ok: true, data: [], meta: {} });
    });

    it('omite todos os params quando ausentes (getList sem params)', () => {
      api.getList('/api/x').subscribe();
      const req = http.expectOne(r => r.url === `${BASE}/api/x`);
      expect(req.request.params.keys().length).toBe(0);
      req.flush({ ok: true, data: [], meta: {} });
    });

    it('valores falsy (page/limit = 0, search = "") não viram param', () => {
      api.getList('/api/x', { page: 0, limit: 0, search: '' }).subscribe();
      const req = http.expectOne(r => r.url === `${BASE}/api/x`);
      expect(req.request.params.has('page')).toBe(false);
      expect(req.request.params.has('limit')).toBe(false);
      expect(req.request.params.has('search')).toBe(false);
      req.flush({ ok: true, data: [], meta: {} });
    });

    it('filters vazio ({}) é omitido — o código checa Object.keys().length', () => {
      api.getList('/api/x', { filters: {} }).subscribe();
      const req = http.expectOne(r => r.url === `${BASE}/api/x`);
      expect(req.request.params.has('filters')).toBe(false);
      req.flush({ ok: true, data: [], meta: {} });
    });

    it('assimetria filters-vs-periodo: periodo {} É serializado (não checa keys)', () => {
      // No código, `filters` só entra se `Object.keys(...).length`, mas `periodo` entra
      // por mera truthiness (`if (params.periodo)`) → um objeto-vazio vira `periodo={}`.
      // Comportamento real do arquivo (api.service.ts:34-37); caracterizado, não é bug.
      api.getList('/api/x', { periodo: {} }).subscribe();
      const req = http.expectOne(r => r.url === `${BASE}/api/x`);
      expect(req.request.params.get('periodo')).toBe('{}');
      req.flush({ ok: true, data: [], meta: {} });
    });

    it('faz GET no endpoint prefixado por url() e devolve o PagedResponse', () => {
      const resp = { ok: true, data: [{ id: 1 }], meta: { page: 1, limit: 10, total: 1, pages: 1 } };
      let got: unknown;
      api.getList('/api/y').subscribe(r => (got = r));
      const req = http.expectOne(`${BASE}/api/y`);
      expect(req.request.method).toBe('GET');
      req.flush(resp);
      expect(got).toEqual(resp);
    });
  });

  describe('verbos representativos — prova do prefixo url() (demais são delegação pura)', () => {
    it('get<T> monta params simples via Object.entries e prefixa a URL', () => {
      api.get('/api/detalhe', { id: 7, tipo: 'x' }).subscribe();
      const req = http.expectOne(r => r.url === `${BASE}/api/detalhe`);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('id')).toBe('7');
      expect(req.request.params.get('tipo')).toBe('x');
      req.flush({ ok: true });
    });

    it('post<T> envia o body no endpoint prefixado', () => {
      const body = { nome: 'ana' };
      api.post('/api/salvar', body).subscribe();
      const req = http.expectOne(`${BASE}/api/salvar`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(body);
      req.flush({ ok: true });
    });

    it('getBlob usa responseType blob, aceita params e prefixa a URL', () => {
      let blob: Blob | undefined;
      api.getBlob('/api/rel', { format: 'pdf' }).subscribe(b => (blob = b));
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      expect(req.request.method).toBe('GET');
      expect(req.request.responseType).toBe('blob');
      expect(req.request.params.get('format')).toBe('pdf');
      req.flush(new Blob(['x'], { type: 'application/pdf' }));
      expect(blob).toBeInstanceOf(Blob);
    });
  });

  describe('métodos de blob — efeito colateral (B2)', () => {
    let createObj: ReturnType<typeof vi.fn>;
    let revoke: ReturnType<typeof vi.fn>;
    let openSpy: ReturnType<typeof vi.spyOn>;
    const origCreate = URL.createObjectURL;
    const origRevoke = URL.revokeObjectURL;

    beforeEach(() => {
      vi.useFakeTimers();
      createObj = vi.fn(() => 'blob:fake-url');
      revoke = vi.fn();
      URL.createObjectURL = createObj as unknown as typeof URL.createObjectURL;
      URL.revokeObjectURL = revoke as unknown as typeof URL.revokeObjectURL;
      openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    });

    afterEach(() => {
      URL.createObjectURL = origCreate;
      URL.revokeObjectURL = origRevoke;
    });

    it('abrirBlobInline abre nova aba e revoga a URL exatamente aos 60s', () => {
      const blob = new Blob(['x']);
      api.abrirBlobInline(blob);
      expect(createObj).toHaveBeenCalledWith(blob);
      expect(openSpy).toHaveBeenCalledWith('blob:fake-url', '_blank');
      expect(revoke).not.toHaveBeenCalled();
      vi.advanceTimersByTime(59_999);
      expect(revoke).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);
      expect(revoke).toHaveBeenCalledWith('blob:fake-url');
    });

    it('baixarBlob cria <a> com href/download, clica e revoga a URL imediatamente', () => {
      const anchor = document.createElement('a');
      const clickSpy = vi.spyOn(anchor, 'click').mockImplementation(() => {});
      vi.spyOn(document, 'createElement').mockReturnValue(anchor);
      const blob = new Blob(['x']);

      api.baixarBlob(blob, 'relatorio.docx');

      expect(createObj).toHaveBeenCalledWith(blob);
      expect(anchor.download).toBe('relatorio.docx');
      expect(clickSpy).toHaveBeenCalledTimes(1);
      expect(revoke).toHaveBeenCalledWith('blob:fake-url'); // imediato, sem timer
    });
  });

  describe('downloadReport / openPdfInline — decisão pdf-inline vs baixarBlob', () => {
    it('openPdfInline força format=pdf e abre inline', () => {
      const inline = vi.spyOn(api, 'abrirBlobInline').mockImplementation(() => {});
      api.openPdfInline('/api/rel', { sort: 'nome' });
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      expect(req.request.params.get('format')).toBe('pdf');
      const blob = new Blob(['pdf']);
      req.flush(blob);
      expect(inline).toHaveBeenCalledWith(blob);
    });

    it('downloadReport com format=pdf → abrirBlobInline (não baixa)', () => {
      const inline = vi.spyOn(api, 'abrirBlobInline').mockImplementation(() => {});
      const baixar = vi.spyOn(api, 'baixarBlob').mockImplementation(() => {});
      api.downloadReport('/api/rel', { format: 'pdf' });
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      const blob = new Blob(['pdf']);
      req.flush(blob);
      expect(inline).toHaveBeenCalledWith(blob);
      expect(baixar).not.toHaveBeenCalled();
    });

    it('downloadReport sem format → default pdf → abrirBlobInline', () => {
      const inline = vi.spyOn(api, 'abrirBlobInline').mockImplementation(() => {});
      api.downloadReport('/api/rel', {});
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      req.flush(new Blob(['pdf']));
      expect(inline).toHaveBeenCalledTimes(1);
    });

    it('downloadReport com format=docx → baixarBlob("relatorio.docx")', () => {
      const inline = vi.spyOn(api, 'abrirBlobInline').mockImplementation(() => {});
      const baixar = vi.spyOn(api, 'baixarBlob').mockImplementation(() => {});
      api.downloadReport('/api/rel', { format: 'docx' });
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      const blob = new Blob(['docx']);
      req.flush(blob);
      expect(baixar).toHaveBeenCalledWith(blob, 'relatorio.docx');
      expect(inline).not.toHaveBeenCalled();
    });

    it('downloadReport com format=xlsx → baixarBlob("relatorio.xlsx")', () => {
      const baixar = vi.spyOn(api, 'baixarBlob').mockImplementation(() => {});
      api.downloadReport('/api/rel', { format: 'xlsx' });
      const req = http.expectOne(r => r.url === `${BASE}/api/rel`);
      const blob = new Blob(['xlsx']);
      req.flush(blob);
      expect(baixar).toHaveBeenCalledWith(blob, 'relatorio.xlsx');
    });
  });
});

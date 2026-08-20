import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ApiService } from './api.service';
import { LookupService } from './lookup.service';

/**
 * LookupService: 5 loaders + loadAll. Cada loader chama ApiService.get num
 * endpoint fixo e muta o SIGNAL certo com `res.data || []`. ApiService mockado
 * com `of({data:[...]})`; assertamos endpoint + signal, nunca o valor do mock em si.
 * Canal de erro POR RECURSO: o loader zera a mensagem ao iniciar e a seta na falha,
 * sem tocar no dado (cache anterior segue servindo); recarregar é rechamar o loader.
 */
describe('LookupService', () => {
  let svc: LookupService;
  let get: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    get = vi.fn();
    TestBed.configureTestingModule({
      providers: [LookupService, { provide: ApiService, useValue: { get } }],
    });
    svc = TestBed.inject(LookupService);
  });

  it('loadSalas → GET /api/forms/lookup/salas e popula o signal salas', () => {
    const dados = [{ id: 1, nome: 'Plenário' }];
    get.mockReturnValue(of({ data: dados }));
    svc.loadSalas();
    expect(get).toHaveBeenCalledWith('/api/forms/lookup/salas');
    expect(svc.salas()).toEqual(dados);
  });

  it('loadOperadores → GET /api/forms/lookup/operadores e popula operadores', () => {
    const dados = [{ id: 2, nome: 'Ana' }];
    get.mockReturnValue(of({ data: dados }));
    svc.loadOperadores();
    expect(get).toHaveBeenCalledWith('/api/forms/lookup/operadores');
    expect(svc.operadores()).toEqual(dados);
  });

  it('loadComissoes → GET /api/forms/lookup/comissoes e popula comissoes', () => {
    const dados = [{ id: 3, nome: 'CCJ' }];
    get.mockReturnValue(of({ data: dados }));
    svc.loadComissoes();
    expect(get).toHaveBeenCalledWith('/api/forms/lookup/comissoes');
    expect(svc.comissoes()).toEqual(dados);
  });

  it('loadSalasOperador → GET /api/operacao/lookup/salas e popula salas (filtradas por permissão)', () => {
    const dados = [{ id: 4, nome: 'Sala 4' }];
    get.mockReturnValue(of({ data: dados }));
    svc.loadSalasOperador();
    expect(get).toHaveBeenCalledWith('/api/operacao/lookup/salas');
    expect(svc.salas()).toEqual(dados);
  });

  it('loadOperadoresPlenario → GET /api/operacao/lookup/operadores-plenario e popula operadoresPlenario', () => {
    const dados = [{ id: 5, nome: 'Bruno' }];
    get.mockReturnValue(of({ data: dados }));
    svc.loadOperadoresPlenario();
    expect(get).toHaveBeenCalledWith('/api/operacao/lookup/operadores-plenario');
    expect(svc.operadoresPlenario()).toEqual(dados);
  });

  it('res.data ausente → o signal recebe [] (fallback `|| []`)', () => {
    get.mockReturnValue(of({})); // sem data
    svc.loadSalas();
    expect(svc.salas()).toEqual([]);
  });

  it('loadAll dispara os 3 loaders principais na ordem salas → operadores → comissoes', () => {
    get.mockReturnValue(of({ data: [] }));
    svc.loadAll();
    expect(get).toHaveBeenCalledTimes(3);
    expect(get.mock.calls.map(c => c[0])).toEqual([
      '/api/forms/lookup/salas',
      '/api/forms/lookup/operadores',
      '/api/forms/lookup/comissoes',
    ]);
  });

  describe('canal de erro por recurso', () => {
    it('falha de carga → mensagem no canal do recurso, sem tocar no cache anterior', () => {
      const dados = [{ id: 1, nome: 'Plenário' }];
      get.mockReturnValueOnce(of({ data: dados }));
      svc.loadSalas();
      get.mockReturnValueOnce(throwError(() => new Error('500')));
      svc.loadSalas();
      expect(svc.erroSalas()).toBe('Não foi possível carregar a lista.');
      expect(svc.salas()).toEqual(dados);
    });

    it('o canal é POR RECURSO: falha em operadores não suja o de salas', () => {
      get.mockReturnValueOnce(of({ data: [] }));
      svc.loadSalas();
      get.mockReturnValueOnce(throwError(() => new Error('500')));
      svc.loadOperadores();
      expect(svc.erroSalas()).toBe('');
      expect(svc.erroOperadores()).toBe('Não foi possível carregar a lista.');
    });

    it('recarga com sucesso limpa o erro e popula o signal', () => {
      get.mockReturnValueOnce(throwError(() => new Error('500')));
      svc.loadComissoes();
      expect(svc.erroComissoes()).not.toBe('');
      const dados = [{ id: 3, nome: 'CCJ' }];
      get.mockReturnValueOnce(of({ data: dados }));
      svc.loadComissoes();
      expect(svc.erroComissoes()).toBe('');
      expect(svc.comissoes()).toEqual(dados);
    });

    it('loadSalasOperador falho liga o MESMO canal de salas (as duas rotas alimentam o mesmo select)', () => {
      get.mockReturnValue(throwError(() => new Error('500')));
      svc.loadSalasOperador();
      expect(svc.erroSalas()).toBe('Não foi possível carregar a lista.');
    });

    it('loadOperadoresPlenario falho liga o canal próprio', () => {
      get.mockReturnValue(throwError(() => new Error('500')));
      svc.loadOperadoresPlenario();
      expect(svc.erroOperadoresPlenario()).toBe('Não foi possível carregar a lista.');
    });
  });
});

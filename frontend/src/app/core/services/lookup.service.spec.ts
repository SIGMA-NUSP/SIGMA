import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiService } from './api.service';
import { LookupService } from './lookup.service';

/**
 * LookupService: 5 loaders + loadAll. Cada loader chama ApiService.get num
 * endpoint fixo e muta o SIGNAL certo com `res.data || []`. ApiService mockado
 * com `of({data:[...]})`; assertamos endpoint + signal, nunca o valor do mock em si.
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
});

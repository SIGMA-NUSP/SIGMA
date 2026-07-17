import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../services/api.service';
import { TableStateController, TableStateOptions } from './table-state.controller';

/**
 * TableStateController — motor de estado das listagens server-side. Cobre estado
 * inicial, load, gatilhos de recarga (sort/filtro/página/limite/busca), o canal de
 * erro de `load()` (rows zeradas, `meta` LIMPO, `erro` preenchido; limpo a cada nova
 * carga — as telas apenas o consomem) e o token de recência (vence o pedido MAIS
 * NOVO, não a resposta mais lenta).
 * Instanciado via TestBed.runInInjectionContext (o construtor chama inject(DestroyRef)
 * para limpar o debounce). ApiService.getList mockado. `onSearch` com debounce de
 * 400 ms provado com fake timers (vi.useRealTimers em afterEach). Asserções sobre o
 * próprio `state`/signals (evita a armadilha da referência compartilhada de `state`
 * entre chamadas de getList).
 */
describe('TableStateController', () => {
  let getList: ReturnType<typeof vi.fn>;

  function criar(opts?: Partial<TableStateOptions>) {
    const full: TableStateOptions = {
      endpoint: '/api/lista',
      defaultSort: 'nome',
      defaultDir: 'asc',
      ...opts,
    };
    return TestBed.runInInjectionContext(
      () => new TableStateController({ getList } as unknown as ApiService, full),
    );
  }

  beforeEach(() => {
    getList = vi.fn().mockReturnValue(of({ data: [], meta: null }));
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('constrói o estado inicial a partir das opções (loading true, rows vazias)', () => {
    const c = criar({ limit: 25, defaultSort: 'data', defaultDir: 'desc' });
    expect(c.state).toEqual({ page: 1, limit: 25, sort: 'data', direction: 'desc', search: '' });
    expect(c.loading()).toBe(true);
    expect(c.rows()).toEqual([]);
    expect(c.meta()).toBeNull();
  });

  it('limit default é 10 quando as opções não o informam', () => {
    expect(criar().state.limit).toBe(10);
  });

  it('load povoa rows/meta, desliga loading e chama getList(endpoint, state)', () => {
    const data = [{ id: 1 }, { id: 2 }];
    const meta = { page: 1, limit: 10, total: 2, pages: 1 };
    getList.mockReturnValue(of({ data, meta }));
    const c = criar();
    c.load();
    expect(getList).toHaveBeenCalledTimes(1);
    expect(getList.mock.calls[0][0]).toBe('/api/lista');
    expect(getList.mock.calls[0][1]).toBe(c.state); // passa o próprio state (mesma referência)
    expect(c.rows()).toEqual(data);
    expect(c.meta()).toEqual(meta);
    expect(c.loading()).toBe(false);
  });

  it('load com data/meta ausentes → rows [] e meta null', () => {
    getList.mockReturnValue(of({}));
    const c = criar();
    c.load();
    expect(c.rows()).toEqual([]);
    expect(c.meta()).toBeNull();
    expect(c.loading()).toBe(false);
  });

  // ═══════════════════════════════════════════════════════════════════
  // Canal de erro — falha de leitura NÃO pode virar "lista vazia"
  // ═══════════════════════════════════════════════════════════════════
  describe('canal de erro', () => {
    const META = { page: 1, limit: 10, total: 42, pages: 5 };

    /** Controlador com uma carga bem-sucedida no histórico (rows + meta povoados). */
    function carregado() {
      getList.mockReturnValue(of({ data: [{ id: 1 }], meta: META }));
      const c = criar();
      c.load();
      return c;
    }

    it('erro nasce vazio e um load bem-sucedido o mantém vazio', () => {
      const c = criar();
      expect(c.erro()).toBe('');
      c.load();
      expect(c.erro()).toBe('');
    });

    it('load em erro → rows [], meta LIMPO, loading false e erro preenchido', () => {
      const c = carregado();
      expect(c.meta()).toEqual(META);   // rodapé com o total da carga anterior

      getList.mockReturnValue(throwError(() => ({ status: 502, error: { message: 'Bad gateway' } })));
      c.load();

      expect(c.rows()).toEqual([]);
      expect(c.loading()).toBe(false);
      expect(c.meta()).toBeNull();      // o rodapé não pode seguir exibindo "42 registros"
      expect(c.erro()).toBe('Não foi possível carregar a lista. (Bad gateway)');   // guia da tela + detalhe
    });

    it('erro sem corpo cai no fallback padrão', () => {
      getList.mockReturnValue(throwError(() => ({ status: 500 })));
      const c = criar();
      c.load();
      expect(c.erro()).toBe('Não foi possível carregar a lista.');
    });

    it('erroMsg das opções substitui a mensagem padrão', () => {
      getList.mockReturnValue(throwError(() => ({ status: 500 })));
      const c = criar({ erroMsg: 'Não foi possível carregar as solicitações.' });
      c.load();
      expect(c.erro()).toBe('Não foi possível carregar as solicitações.');
    });

    it('no 500 REAL do backend a orientação da tela sobrevive, com o detalhe anexado', () => {
      // O `GlobalExceptionHandler` responde `{ok:false, error:"Erro interno do servidor"}` em TODO
      // 500 — e `httpErrorMsg` prioriza o corpo. Se o canal usasse só ele, a orientação da tela
      // (o que está em jogo, o que fazer) nunca apareceria no caso mais comum. Daí o `erroCargaMsg`.
      getList.mockReturnValue(throwError(() => ({ status: 500, error: { ok: false, error: 'Erro interno do servidor' } })));
      const c = criar({ erroMsg: 'Não foi possível carregar as solicitações.' });
      c.load();
      expect(c.erro()).toBe('Não foi possível carregar as solicitações. (Erro interno do servidor)');
    });

    it('a carga seguinte (retry) limpa o erro ANTES de pedir — e o sucesso o deixa limpo', () => {
      getList.mockReturnValue(throwError(() => ({ status: 503 })));
      const c = criar();
      c.load();
      expect(c.erro()).not.toBe('');

      const emVoo = new Subject<any>();
      getList.mockReturnValue(emVoo);
      c.load();                                        // retry: o erro some já no disparo
      expect(c.erro()).toBe('');
      expect(c.loading()).toBe(true);

      emVoo.next({ data: [{ id: 9 }], meta: META });   // sucesso repovoa rows/meta
      expect(c.erro()).toBe('');
      expect(c.rows()).toEqual([{ id: 9 }]);
      expect(c.meta()).toEqual(META);
    });

    it.each([
      ['onSort',   (c: TableStateController) => c.onSort({ sort: 'data', direction: 'desc' })],
      ['onFilter', (c: TableStateController) => c.onFilter({ key: 'sala', state: { values: ['1'] } })],
      ['onPage',   (c: TableStateController) => c.onPage(2)],
      ['onLimit',  (c: TableStateController) => c.onLimit(25)],
    ])('%s (gatilho de recarga) limpa o erro anterior — todos passam por load()', (_nome, gatilho) => {
      getList.mockReturnValue(throwError(() => ({ status: 500 })));
      const c = criar();
      c.load();
      expect(c.erro()).not.toBe('');

      getList.mockReturnValue(of({ data: [], meta: null }));
      gatilho(c);
      expect(c.erro()).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Recência: vence o pedido MAIS NOVO, não a resposta mais lenta
  // ═══════════════════════════════════════════════════════════════════
  describe('token de recência', () => {
    const META_2 = { page: 2, limit: 10, total: 42, pages: 5 };
    const META_3 = { page: 3, limit: 10, total: 42, pages: 5 };

    /** Duas cargas em voo (dois cliques de paginação): devolve os Subjects na ordem do disparo. */
    function duasCargasEmVoo() {
      const velha = new Subject<any>();
      const nova = new Subject<any>();
      getList.mockReturnValueOnce(velha).mockReturnValueOnce(nova);
      const c = criar();
      c.onPage(2);   // carga VELHA (página 2)
      c.onPage(3);   // carga NOVA  (página 3) — a que o usuário está vendo no rodapé
      return { c, velha, nova };
    }

    it('sucesso × sucesso: a resposta VELHA que chega por último é descartada — valem rows/meta da NOVA', () => {
      // ⚠️ O sintoma REAL (o código venceu o prompt): `rows` e `meta` são gravados pela MESMA
      // resposta, então o rodapé nunca fica "Página 3" com as linhas da 2 — o par é coerente, só que
      // do pedido ERRADO: o usuário clicou na 3 e a tela inteira volta para a 2 (linhas + rodapé),
      // sem nada indicando isso. Na corrida sort→filtro é pior: o chip do filtro fica ativo e as
      // linhas exibidas são as NÃO filtradas.
      const { c, velha, nova } = duasCargasEmVoo();

      nova.next({ data: [{ id: 'pag3' }], meta: META_3 });
      velha.next({ data: [{ id: 'pag2' }], meta: META_2 });   // chega depois, mas é do pedido antigo

      expect(c.rows()).toEqual([{ id: 'pag3' }]);
      expect(c.meta()).toEqual(META_3);
      expect(c.loading()).toBe(false);
    });

    it('PIOR FACETA — a carga velha FALHA depois de a nova suceder: nada é apagado', () => {
      // Sem o guard no `error`, isto era destrutivo: rows zeradas, meta NULL (rodapé some)
      // e uma caixa de erro sobre uma lista que tinha acabado de carregar com sucesso.
      const { c, velha, nova } = duasCargasEmVoo();

      nova.next({ data: [{ id: 'pag3' }], meta: META_3 });
      velha.error({ status: 500, error: { ok: false, error: 'Erro interno do servidor' } });

      expect(c.erro()).toBe('');
      expect(c.rows()).toEqual([{ id: 'pag3' }]);
      expect(c.meta()).toEqual(META_3);
      expect(c.loading()).toBe(false);
    });

    it('a carga NOVA falha e a velha sucede depois: o erro da nova PERMANECE (a resposta velha não "cura")', () => {
      const { c, velha, nova } = duasCargasEmVoo();

      nova.error({ status: 503 });
      velha.next({ data: [{ id: 'pag2' }], meta: META_2 });

      expect(c.erro()).toBe('Não foi possível carregar a lista.');
      expect(c.rows()).toEqual([]);
      expect(c.meta()).toBeNull();
    });

    it('loading só é desligado pela resposta CORRENTE (a obsoleta não o toca)', () => {
      const { c, velha, nova } = duasCargasEmVoo();

      velha.next({ data: [{ id: 'pag2' }], meta: META_2 });   // obsoleta chega primeiro
      expect(c.loading()).toBe(true);                          // a carga corrente ainda voa
      expect(c.rows()).toEqual([]);                            // e não escreveu nada

      nova.next({ data: [{ id: 'pag3' }], meta: META_3 });
      expect(c.loading()).toBe(false);
    });

    it('a corrida também é vencida pelo pedido novo quando os gatilhos se MISTURAM (sort → filtro)', () => {
      const doSort = new Subject<any>();
      const doFiltro = new Subject<any>();
      getList.mockReturnValueOnce(doSort).mockReturnValueOnce(doFiltro);
      const c = criar();

      c.onSort({ sort: 'data', direction: 'desc' });
      c.onFilter({ key: 'sala', state: { values: ['1'] } });

      doFiltro.next({ data: [{ id: 'filtrado' }], meta: META_2 });
      doSort.next({ data: [{ id: 'ordenado' }], meta: META_3 });   // a resposta do sort chega atrasada

      expect(c.rows()).toEqual([{ id: 'filtrado' }]);
    });

    it('uma carga que já venceu a corrida não bloqueia a SEGUINTE (o token não trava o motor)', () => {
      const { c, velha, nova } = duasCargasEmVoo();
      nova.next({ data: [{ id: 'pag3' }], meta: META_3 });
      velha.next({ data: [{ id: 'pag2' }], meta: META_2 });

      getList.mockReturnValue(of({ data: [{ id: 'pag4' }], meta: META_2 }));
      c.onPage(4);

      expect(c.rows()).toEqual([{ id: 'pag4' }]);
      expect(c.loading()).toBe(false);
    });
  });

  it('endpoint dinâmico (função) é resolvido a cada load', () => {
    let sala = 'A';
    const c = criar({ endpoint: () => `/api/sala/${sala}` });
    c.load();
    expect(getList.mock.calls[0][0]).toBe('/api/sala/A');
    sala = 'B';
    c.load();
    expect(getList.mock.calls[1][0]).toBe('/api/sala/B');
  });

  it('onSort atualiza sort/direction, reseta a página e recarrega', () => {
    const c = criar();
    c.state.page = 5;
    c.onSort({ sort: 'data', direction: 'desc' });
    expect(c.state.sort).toBe('data');
    expect(c.state.direction).toBe('desc');
    expect(c.state.page).toBe(1);
    expect(getList).toHaveBeenCalledTimes(1);
  });

  it('onFilter com state adiciona o filtro (via buildFilters no state) e recarrega', () => {
    const c = criar();
    c.state.page = 3;
    c.onFilter({ key: 'sala', state: { values: ['1', '2'] } });
    expect(c.filters['sala']).toEqual({ values: ['1', '2'] });
    expect(c.state.filters).toEqual({ sala: { values: ['1', '2'] } });
    expect(c.state.page).toBe(1);
    expect(getList).toHaveBeenCalledTimes(1);
  });

  it('onFilter com state:null REMOVE o filtro existente', () => {
    const c = criar();
    c.onFilter({ key: 'sala', state: { values: ['1'] } });
    expect(c.filters['sala']).toBeDefined();
    c.onFilter({ key: 'sala', state: null });
    expect(c.filters['sala']).toBeUndefined();
    expect(c.state.filters).toEqual({}); // buildFilters de {} = {}
    expect(getList).toHaveBeenCalledTimes(2);
  });

  it('onPage troca a página (sem resetar) e recarrega', () => {
    const c = criar();
    c.onPage(4);
    expect(c.state.page).toBe(4);
    expect(getList).toHaveBeenCalledTimes(1);
  });

  it('onLimit troca o limite, reseta a página e recarrega', () => {
    const c = criar();
    c.state.page = 7;
    c.onLimit(50);
    expect(c.state.limit).toBe(50);
    expect(c.state.page).toBe(1);
    expect(getList).toHaveBeenCalledTimes(1);
  });

  describe('onSearch — debounce de 400 ms (fake timers)', () => {
    beforeEach(() => vi.useFakeTimers());

    it('duas digitações em <400 ms disparam UM load, com o último texto', () => {
      const c = criar();
      c.searchText = 'an';
      c.onSearch();
      vi.advanceTimersByTime(200);
      c.searchText = 'ana';
      c.onSearch(); // reinicia o debounce
      vi.advanceTimersByTime(399);
      expect(getList).not.toHaveBeenCalled(); // ainda dentro da janela da 2ª digitação
      vi.advanceTimersByTime(1); // completa 400 ms
      expect(getList).toHaveBeenCalledTimes(1);
      expect(c.state.search).toBe('ana');
      expect(c.state.page).toBe(1);
    });

    it('não dispara antes de 400 ms', () => {
      const c = criar();
      c.searchText = 'x';
      c.onSearch();
      vi.advanceTimersByTime(399);
      expect(getList).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);
      expect(getList).toHaveBeenCalledTimes(1);
    });

    it('destruir o injector cancela o debounce pendente (onDestroy → clearTimeout)', () => {
      // O construtor registra inject(DestroyRef).onDestroy(() => clearTimeout(...)) —
      // lógica real ("limpo no destroy", JSDoc). Sem isto, um debounce pendente dispara
      // após o componente ser destruído. Injector filho descartável prova a limpeza.
      const child = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
      const c = runInInjectionContext(
        child,
        () =>
          new TableStateController({ getList } as unknown as ApiService, {
            endpoint: '/api/lista',
            defaultSort: 'nome',
            defaultDir: 'asc',
          }),
      );
      c.searchText = 'x';
      c.onSearch();               // agenda o debounce
      child.destroy();            // dispara onDestroy → clearTimeout(searchDebounce)
      vi.advanceTimersByTime(400);
      expect(getList).not.toHaveBeenCalled();
    });
  });
});

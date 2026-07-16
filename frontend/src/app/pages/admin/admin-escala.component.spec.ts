import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminEscalaComponent } from './admin-escala.component';
import { ApiService } from '../../core/services/api.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';

/**
 * T24 — AdminEscalaComponent (page, 613 LOC — editor de escala semanal; §A5).
 *
 * Estratégia (manual de PAGE do T22/T23): TestBed cria o componente (DI + signals) SEM
 * `detectChanges()`; a lógica é exercitada por chamada direta. Services mockados via
 * `useValue` (padrão T21); `LookupService` com signals writable (`salas`/`operadores`) que
 * os computeds leem direto. Prioridade na LÓGICA PURA (A5): rodízio, inversão de turno,
 * seleção com Map/Set.
 *
 * ⚠️ Mutação in-place vs `.set()` (D2 do estágio): `toggleOperador`/`inverterTurnoSala`/
 * `toggleOperadorFuncao` mutam a coleção interna e RE-EMITEM com `signal.set(new Map(map))` —
 * a referência EXTERNA muda (dispara reatividade); nenhum `computed` lê o Map interno
 * memoizado, então a mutação in-place NÃO quebra reatividade aqui. Caracterizamos o
 * comportamento REAL: asserção sobre o efeito (isSelected/turnoDe) + sobre a nova referência
 * externa do signal (`.not.toBe(antes)`). Caso de borda no-op (`inverterTurnoSala` sem
 * seleção) preserva a MESMA referência (o `return` precede o `.set`). Nenhum achado.
 *
 * Fake timers COMPLETOS (D5): `focarAreaSalas` usa `window.setTimeout` — instalados após
 * `compileComponents` (que precisa de timers reais), `vi.runAllTimers()` onde há agendamento,
 * `vi.useRealTimers()` em afterEach (zero timer real vazando).
 */
describe('AdminEscalaComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiDelete: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let loadSalas: ReturnType<typeof vi.fn>;
  let loadOperadores: ReturnType<typeof vi.fn>;
  let salasSignal: WritableSignal<LookupItem[]>;
  let operadoresSignal: WritableSignal<LookupItem[]>;

  const SALAS: LookupItem[] = [
    { id: 10, nome: 'Plenário 2' },
    { id: 20, nome: 'Plenário 6' },
    { id: 30, nome: 'Sala Verde' }, // não numerada
  ];
  const OPERADORES: LookupItem[] = [
    { id: 1, nome: 'Ana', nome_completo: 'Ana Souza', participa_escala: true, turno: 'M' },
    { id: 2, nome: 'Bia', nome_completo: 'Bia Lima', participa_escala: true, turno: 'V' },
    { id: 3, nome: 'Zé', participa_escala: false, turno: 'M' }, // não participa
  ];

  beforeEach(async () => {
    apiGet = vi.fn().mockReturnValue(of({ data: {} }));
    apiGetList = vi.fn().mockReturnValue(of({ data: [], meta: null }));
    apiPost = vi.fn().mockReturnValue(of({ data: { salas: {} } }));
    apiDelete = vi.fn().mockReturnValue(of({}));
    toastSuccess = vi.fn();
    toastError = vi.fn();
    loadSalas = vi.fn();
    loadOperadores = vi.fn();
    salasSignal = signal<LookupItem[]>([]);
    operadoresSignal = signal<LookupItem[]>([]);

    await TestBed.configureTestingModule({
      imports: [AdminEscalaComponent],
      providers: [
        provideRouter([]), // o componente importa RouterLink (instanciado na criação) → precisa de Router/ActivatedRoute
        { provide: ApiService, useValue: { get: apiGet, getList: apiGetList, post: apiPost, delete: apiDelete } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError } },
        {
          provide: LookupService,
          useValue: { salas: salasSignal, operadores: operadoresSignal, loadSalas, loadOperadores },
        },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    vi.useFakeTimers();
    // jsdom não implementa scrollIntoView; garante no-op p/ o focarAreaSalas não estourar
    // (o SUT usa document.getElementById global — o runner compartilha o document entre specs).
    (HTMLElement.prototype as any).scrollIntoView ??= function () {};
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    // limpa qualquer editor órfão anexado ao document compartilhado do runner
    document.querySelectorAll('#escala-salas-editor').forEach(e => e.remove());
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit). */
  function criar(): AdminEscalaComponent {
    return TestBed.createComponent(AdminEscalaComponent).componentInstance;
  }

  /** Componente com lookup já semeado (salas numeradas + operadores). */
  function criarComLookup(): AdminEscalaComponent {
    salasSignal.set(SALAS);
    operadoresSignal.set(OPERADORES);
    return criar();
  }

  // ═══════════════════════════════════════════════════════════════════
  // Computeds — salasNumeradas / salaAtual / operadores / temAlgumaSelecao
  // ═══════════════════════════════════════════════════════════════════
  describe('computeds', () => {
    it('salasNumeradas mantém só as salas "Plenário N"', () => {
      const comp = criarComLookup();
      expect(comp.salasNumeradas().map(s => s.id)).toEqual([10, 20]);
    });

    it('salaAtual segue o salaAtualIndex e é null fora do range', () => {
      const comp = criarComLookup();
      expect(comp.salaAtual()?.id).toBe(10);
      comp.salaAtualIndex.set(1);
      expect(comp.salaAtual()?.id).toBe(20);
      comp.salaAtualIndex.set(5);
      expect(comp.salaAtual()).toBeNull();
    });

    it('operadores filtra participa_escala === true', () => {
      const comp = criarComLookup();
      expect(comp.operadores().map(o => o.id)).toEqual([1, 2]);
    });

    it('temAlgumaSelecao é false sem seleção', () => {
      const comp = criarComLookup();
      expect(comp.temAlgumaSelecao()).toBe(false);
    });

    it('temAlgumaSelecao vira true com operador de sala selecionado', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1);
      expect(comp.temAlgumaSelecao()).toBe(true);
    });

    it('temAlgumaSelecao vira true com operador de função selecionado', () => {
      const comp = criarComLookup();
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      expect(comp.temAlgumaSelecao()).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Seleção de operadores por sala — Map<sala, Map<op, turno>>
  // ═══════════════════════════════════════════════════════════════════
  describe('seleção de operadores (sala)', () => {
    it('toggleOperador adiciona e remove; isSelected reflete o estado', () => {
      const comp = criarComLookup();
      expect(comp.isSelected(10, 1)).toBe(false);
      comp.toggleOperador(10, 1);
      expect(comp.isSelected(10, 1)).toBe(true);
      comp.toggleOperador(10, 1);
      expect(comp.isSelected(10, 1)).toBe(false);
    });

    it('isSelected/toggleOperador coagem sala (número) e operador (string)', () => {
      const comp = criarComLookup();
      comp.toggleOperador('10', '1'); // strings
      expect(comp.isSelected(10, 1)).toBe(true); // números → mesma chave
    });

    it('toggleOperador re-emite o signal com NOVA referência externa', () => {
      const comp = criarComLookup();
      const antes = comp.selecao();
      comp.toggleOperador(10, 1);
      expect(comp.selecao()).not.toBe(antes);
    });

    it('getOperadoresSala lista os ids (string) selecionados na sala', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1);
      comp.toggleOperador(10, 2);
      expect(comp.getOperadoresSala(10)).toEqual(['1', '2']);
      expect(comp.getOperadoresSala(20)).toEqual([]);
    });

    it('defaultTurno vem do lookup: "V" preserva V, qualquer outro cai em M', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1); // Ana, turno M
      comp.toggleOperador(10, 2); // Bia, turno V
      expect(comp.turnoDe(10, 1)).toBe('M');
      expect(comp.turnoDe(10, 2)).toBe('V');
    });

    it('turnoDe é null quando o operador não está selecionado', () => {
      const comp = criarComLookup();
      expect(comp.turnoDe(10, 1)).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // inverterTurnoSala — M↔V dos selecionados; no-op quando vazio
  // ═══════════════════════════════════════════════════════════════════
  describe('inverterTurnoSala', () => {
    it('inverte M↔V de todos os operadores selecionados na sala', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1); // M
      comp.toggleOperador(10, 2); // V
      comp.inverterTurnoSala(10);
      expect(comp.turnoDe(10, 1)).toBe('V');
      expect(comp.turnoDe(10, 2)).toBe('M');
    });

    it('re-emite o signal com nova referência ao inverter', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1);
      const antes = comp.selecao();
      comp.inverterTurnoSala(10);
      expect(comp.selecao()).not.toBe(antes);
    });

    it('no-op (sem seleção na sala) preserva a MESMA referência do signal', () => {
      const comp = criarComLookup();
      const antes = comp.selecao();
      comp.inverterTurnoSala(99);
      expect(comp.selecao()).toBe(antes);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Seleção de funções — Map<tipo, Set<op>>
  // ═══════════════════════════════════════════════════════════════════
  describe('seleção de funções', () => {
    it('toggleOperadorFuncao adiciona/remove no Set do tipo', () => {
      const comp = criarComLookup();
      expect(comp.isFuncaoSelected('APOIO_COMISSOES', 1)).toBe(false);
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      expect(comp.isFuncaoSelected('APOIO_COMISSOES', 1)).toBe(true);
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      expect(comp.isFuncaoSelected('APOIO_COMISSOES', 1)).toBe(false);
    });

    it('getOperadoresFuncao lista os ids do tipo', () => {
      const comp = criarComLookup();
      comp.toggleOperadorFuncao('FECHAMENTO', 2);
      expect(comp.getOperadoresFuncao('FECHAMENTO')).toEqual(['2']);
      expect(comp.getOperadoresFuncao('APOIO_COMISSOES')).toEqual([]);
    });

    it('toggleOperadorFuncao re-emite o signal com nova referência', () => {
      const comp = criarComLookup();
      const antes = comp.selecaoFuncao();
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      expect(comp.selecaoFuncao()).not.toBe(antes);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Navegação entre plenários e funções (clamp)
  // ═══════════════════════════════════════════════════════════════════
  describe('navegação', () => {
    it('avancarPlenario/voltarPlenario faz clamp em [0, salasNumeradas-1]', () => {
      const comp = criarComLookup(); // 2 salas numeradas
      expect(comp.salaAtualIndex()).toBe(0);
      comp.avancarPlenario();
      expect(comp.salaAtualIndex()).toBe(1);
      comp.avancarPlenario(); // já no fim → clamp
      expect(comp.salaAtualIndex()).toBe(1);
      comp.voltarPlenario();
      comp.voltarPlenario(); // já no início → clamp
      expect(comp.salaAtualIndex()).toBe(0);
    });

    it('avancarFuncao/voltarFuncao faz clamp em [0, funcoesTipos-1]', () => {
      const comp = criarComLookup(); // 2 funções
      expect(comp.funcaoAtualTipo()).toBe('APOIO_COMISSOES');
      comp.avancarFuncao();
      expect(comp.funcaoAtualTipo()).toBe('FECHAMENTO');
      expect(comp.funcaoAtualLabel()).toBe('Fechamento dos Plenários');
      comp.avancarFuncao(); // clamp
      expect(comp.funcaoAtualIndex()).toBe(1);
      comp.voltarFuncao();
      comp.voltarFuncao(); // clamp
      expect(comp.funcaoAtualIndex()).toBe(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // getOutrosPlenarios — nomes abreviados dos outros plenários do operador
  // ═══════════════════════════════════════════════════════════════════
  describe('getOutrosPlenarios', () => {
    it('retorna "P<n>" das outras salas em que o operador está selecionado', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1); // Plenário 2
      comp.toggleOperador(20, 1); // Plenário 6
      expect(comp.getOutrosPlenarios(10, 1)).toEqual(['P6']);
      expect(comp.getOutrosPlenarios(20, 1)).toEqual(['P2']);
    });

    it('vazio quando o operador só está na sala atual', () => {
      const comp = criarComLookup();
      comp.toggleOperador(10, 1);
      expect(comp.getOutrosPlenarios(10, 1)).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // loadEscalas
  // ═══════════════════════════════════════════════════════════════════
  describe('loadEscalas', () => {
    it('povoa escalas/meta e encerra o loading no sucesso', () => {
      const comp = criar();
      apiGetList.mockReturnValue(of({ data: [{ id: 1 }], meta: { page: 1, pages: 3 } }));
      comp.loadEscalas();
      expect(apiGetList.mock.calls[0][0]).toBe('/api/admin/escala/list');
      expect(comp.escalas()).toEqual([{ id: 1 }]);
      expect(comp.escalasMeta()).toEqual({ page: 1, pages: 3 });
      expect(comp.loading()).toBe(false);
    });

    it('zera a lista e a meta no erro', () => {
      const comp = criar();
      comp.escalas.set([{ id: 9 }]);
      apiGetList.mockReturnValue(throwError(() => new Error('rede')));
      comp.loadEscalas();
      expect(comp.escalas()).toEqual([]);
      expect(comp.escalasMeta()).toBeNull();
      expect(comp.loading()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // salvar — validação, payload e ramos criar/atualizar/erro
  // ═══════════════════════════════════════════════════════════════════
  describe('salvar', () => {
    it('sem datas: alerta e NÃO chama a API', () => {
      const comp = criarComLookup();
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      comp.toggleOperador(10, 1);
      comp.salvar();
      expect(alertSpy).toHaveBeenCalled();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('cria: monta salas/turnos/funcoes, avisa sucesso e limpa a edição', () => {
      const comp = criarComLookup();
      comp.dataInicio = '2026-07-13';
      comp.dataFim = '2026-07-17';
      comp.toggleOperador(10, 1); // M
      comp.toggleOperador(10, 2); // V
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      apiPost.mockReturnValue(of({ ok: true }));

      comp.salvar();

      expect(apiPost.mock.calls[0][0]).toBe('/api/admin/escala/save');
      const payload = apiPost.mock.calls[0][1];
      expect(payload).toMatchObject({
        data_inicio: '2026-07-13',
        data_fim: '2026-07-17',
        salas: { '10': ['1', '2'] },
        turnos: { '10': { '1': 'M', '2': 'V' } },
        funcoes: { APOIO_COMISSOES: ['1'] },
      });
      expect(payload.id).toBeUndefined();
      expect(toastSuccess).toHaveBeenCalledWith('Escala criada com sucesso');
      expect(comp.salvando()).toBe(false);
      // cancelarEdicao() no next zerou a seleção
      expect(comp.temAlgumaSelecao()).toBe(false);
    });

    it('atualiza: inclui o id no payload e avisa "atualizada"', () => {
      const comp = criarComLookup();
      comp.dataInicio = '2026-07-13';
      comp.dataFim = '2026-07-17';
      comp.editandoId.set(7);
      comp.toggleOperador(10, 1);
      apiPost.mockReturnValue(of({ ok: true }));

      comp.salvar();

      expect(apiPost.mock.calls[0][1].id).toBe(7);
      expect(toastSuccess).toHaveBeenCalledWith('Escala atualizada com sucesso');
    });

    it('só entram no payload as salas/funções com seleção (size > 0)', () => {
      const comp = criarComLookup();
      comp.dataInicio = 'a';
      comp.dataFim = 'b';
      comp.toggleOperador(10, 1);
      comp.toggleOperador(10, 1); // remove → sala 10 fica vazia
      comp.toggleOperadorFuncao('FECHAMENTO', 2);
      apiPost.mockReturnValue(of({ ok: true }));

      comp.salvar();

      const payload = apiPost.mock.calls[0][1];
      expect(payload.salas).toEqual({}); // sala vazia omitida
      expect(payload.funcoes).toEqual({ FECHAMENTO: ['2'] });
    });

    it('erro: exibe toast de erro e reseta salvando', () => {
      const comp = criarComLookup();
      comp.dataInicio = 'a';
      comp.dataFim = 'b';
      comp.toggleOperador(10, 1);
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'Período inválido' } })));

      comp.salvar();

      expect(toastError).toHaveBeenCalledWith('Período inválido');
      expect(comp.salvando()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // gerarRodizio — prévia da escala automática
  // ═══════════════════════════════════════════════════════════════════
  describe('gerarRodizio', () => {
    it('sem datas: não chama a API', () => {
      const comp = criarComLookup();
      comp.gerarRodizio();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('constrói a seleção da prévia com o turno padrão e foca a área', () => {
      const comp = criarComLookup();
      comp.dataInicio = '2026-07-13';
      comp.dataFim = '2026-07-17';
      comp.salaAtualIndex.set(1);
      apiPost.mockReturnValue(of({ data: { salas: { '10': ['1', '2'] } } }));

      comp.gerarRodizio();
      vi.runAllTimers(); // drena o setTimeout de focarAreaSalas

      expect(apiPost.mock.calls[0][0]).toBe('/api/admin/escala/rodizio/preview');
      expect(apiPost.mock.calls[0][1]).toEqual({ data_inicio: '2026-07-13', data_fim: '2026-07-17' });
      expect(comp.isSelected(10, 1)).toBe(true);
      expect(comp.turnoDe(10, 1)).toBe('M'); // Ana, turno M
      expect(comp.turnoDe(10, 2)).toBe('V'); // Bia, turno V
      expect(comp.salaAtualIndex()).toBe(0); // resetado
      expect(toastSuccess).toHaveBeenCalledWith(expect.stringContaining('Prévia gerada'));
      expect(comp.salvando()).toBe(false);
    });

    it('erro: toast de erro e reseta salvando', () => {
      const comp = criarComLookup();
      comp.dataInicio = 'a';
      comp.dataFim = 'b';
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'Sem escala anterior' } })));
      comp.gerarRodizio();
      expect(toastError).toHaveBeenCalledWith('Sem escala anterior');
      expect(comp.salvando()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // editar — hidrata o formulário a partir de uma escala existente
  // ═══════════════════════════════════════════════════════════════════
  describe('editar', () => {
    it('carrega datas, seleção (pulando funções sem sala_id) e funções', () => {
      const comp = criarComLookup();
      apiGet.mockReturnValue(of({
        data: {
          resumo: [
            { sala_id: 10, operadores_detalhe: [{ id: 1, turno: 'M' }, { id: 2, turno: 'V' }] },
            { sala_id: null, operadores_detalhe: [{ id: 9, turno: 'M' }] }, // função → ignorada aqui
          ],
          funcoes: { APOIO_COMISSOES: ['1'], FECHAMENTO: ['2'] },
        },
      }));

      comp.editar({ id: 42, data_inicio: '2026-07-13', data_fim: '2026-07-17' });
      vi.runAllTimers(); // drena focarAreaSalas

      expect(comp.editandoId()).toBe(42);
      expect(comp.dataInicio).toBe('2026-07-13');
      expect(comp.dataFim).toBe('2026-07-17');
      expect(comp.getOperadoresSala(10)).toEqual(['1', '2']);
      expect(comp.turnoDe(10, 2)).toBe('V');
      expect(comp.getOperadoresFuncao('APOIO_COMISSOES')).toEqual(['1']);
      expect(comp.getOperadoresFuncao('FECHAMENTO')).toEqual(['2']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // excluir — confirmação + DELETE
  // ═══════════════════════════════════════════════════════════════════
  describe('excluir', () => {
    it('cancelado no confirm: não chama DELETE', () => {
      const comp = criar();
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      comp.excluir({ id: 5, data_inicio: 'a', data_fim: 'b' });
      expect(apiDelete).not.toHaveBeenCalled();
    });

    it('confirmado: DELETA no endpoint certo e recarrega a lista', () => {
      const comp = criar();
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      comp.excluir({ id: 5, data_inicio: 'a', data_fim: 'b' });
      expect(apiDelete.mock.calls[0][0]).toBe('/api/admin/escala/5');
      expect(apiGetList).toHaveBeenCalled(); // loadEscalas
    });

    it('confirmado sobre a escala em edição: cancela a edição', () => {
      const comp = criar();
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      comp.editandoId.set(5);
      comp.dataInicio = 'a';
      comp.excluir({ id: 5, data_inicio: 'a', data_fim: 'b' });
      expect(comp.editandoId()).toBeNull();
      expect(comp.dataInicio).toBe('');
    });

    it('erro no DELETE: alerta', () => {
      const comp = criar();
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      apiDelete.mockReturnValue(throwError(() => new Error('falhou')));
      comp.excluir({ id: 5, data_inicio: 'a', data_fim: 'b' });
      expect(alertSpy).toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // cancelarEdicao — reseta todo o estado do formulário
  // ═══════════════════════════════════════════════════════════════════
  describe('cancelarEdicao', () => {
    it('zera edição, datas, seleções e índices', () => {
      const comp = criarComLookup();
      comp.editandoId.set(3);
      comp.dataInicio = 'a';
      comp.dataFim = 'b';
      comp.toggleOperador(10, 1);
      comp.toggleOperadorFuncao('APOIO_COMISSOES', 1);
      comp.salaAtualIndex.set(1);
      comp.funcaoAtualIndex.set(1);

      comp.cancelarEdicao();

      expect(comp.editandoId()).toBeNull();
      expect(comp.dataInicio).toBe('');
      expect(comp.dataFim).toBe('');
      expect(comp.temAlgumaSelecao()).toBe(false);
      expect(comp.salaAtualIndex()).toBe(0);
      expect(comp.funcaoAtualIndex()).toBe(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // toggleDetalhe — accordion de resumo por escala (carga preguiçosa)
  // ═══════════════════════════════════════════════════════════════════
  describe('toggleDetalhe', () => {
    it('expande e carrega o resumo uma vez; recolhe sem recarregar', () => {
      const comp = criar();
      apiGet.mockReturnValue(of({ data: { resumo: [{ sala_nome: 'Plenário 2' }] } }));
      const esc: Record<string, any> = { id: 5 };

      comp.toggleDetalhe(esc); // expande → carrega
      expect(esc['_expanded']).toBe(true);
      expect(esc['_resumo']).toEqual([{ sala_nome: 'Plenário 2' }]);
      expect(apiGet).toHaveBeenCalledTimes(1);
      expect(apiGet.mock.calls[0][0]).toBe('/api/admin/escala/5');

      comp.toggleDetalhe(esc); // recolhe
      expect(esc['_expanded']).toBe(false);
      expect(apiGet).toHaveBeenCalledTimes(1); // não recarrega (já tem _resumo)
    });

    it('erro na carga do resumo: define lista vazia', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      const esc: Record<string, any> = { id: 5 };
      comp.toggleDetalhe(esc);
      expect(esc['_resumo']).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // focarAreaSalas — window.setTimeout + scrollIntoView/focus (D5)
  // ═══════════════════════════════════════════════════════════════════
  describe('focarAreaSalas', () => {
    it('rola e foca o editor quando o elemento existe', () => {
      const comp = criar();
      document.querySelectorAll('#escala-salas-editor').forEach(e => e.remove());
      const editor = document.createElement('div');
      editor.id = 'escala-salas-editor';
      document.body.appendChild(editor); // único #escala-salas-editor no document → getElementById pega este
      const scrollSpy = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
      const focusSpy = vi.spyOn(editor, 'focus');

      (comp as any).focarAreaSalas();
      vi.runAllTimers();

      expect(scrollSpy).toHaveBeenCalled();
      expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true });

      editor.remove();
    });

    it('no-op seguro quando o editor não está no DOM', () => {
      const comp = criar();
      (comp as any).focarAreaSalas();
      expect(() => vi.runAllTimers()).not.toThrow();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // ngOnInit — dispara as cargas iniciais
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('carrega salas, operadores e a lista de escalas', () => {
      const comp = criar();
      comp.ngOnInit();
      expect(loadSalas).toHaveBeenCalledTimes(1);
      expect(loadOperadores).toHaveBeenCalledTimes(1);
      expect(apiGetList).toHaveBeenCalledWith('/api/admin/escala/list', comp.escalasState);
    });
  });
});

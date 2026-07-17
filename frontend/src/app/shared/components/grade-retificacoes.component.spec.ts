import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { GradeRetificacoesComponent } from './grade-retificacoes.component';
import { MesAnoSelectorComponent } from './mes-ano-selector.component';
import { ToastService } from './toast.component';

/**
 * T28 — GradeRetificacoesComponent (shared, 503 LOC — card admin "Retificações", E10).
 *
 * ⚠️ [C20] O spec do próprio `MesAnoSelectorComponent` (componente de arquivo próprio,
 * `mes-ano-selector.component.ts`) MUDOU-SE para o seu spec DEDICADO
 * `mes-ano-selector.component.spec.ts` (nasceu ali no C20). Aqui fica só a cobertura do
 * CONSUMIDOR: o que a GRADE mostra através de `app-mes-ano-selector` (bloco de render F37, abaixo).
 *
 * ⚠️ Divergência 2 — "interação de célula": **não existe**. A célula da grade não tem handler de
 * clique (só `[title]` com a observação e classes de cor); o próprio SUT declara
 * `TODO(E10.3/Q1 — fora da v1): clicar numa célula para abrir/editar a retificação`. O que há para
 * travar é a MONTAGEM da célula a partir do payload (`celula()`/`horariosLinhas()`) — feito abaixo.
 *
 * Estratégia (manual de PAGE do T22/T23/T24): TestBed cria o componente (DI + signals) SEM
 * `detectChanges()` — logo `ngOnInit` é chamado à mão e os filhos (`app-mes-ano-selector`,
 * `app-mini-calendario`) nunca são instanciados (isolamento). `ApiService` mockado via `useValue`.
 * O spec trava LÓGICA e ESTADO (signals/computeds/payloads), nunca DOM/CSS — o layout do módulo
 * ainda pode mudar (ressalva do GATE).
 *
 * Relógio congelado (`{toFake:['Date']}`) ANTES de `createComponent`: `hoje`/`anoMes` são lidos no
 * field initializer, nos DOIS componentes.
 *
 * A precedência por célula é do BACKEND (E10 passo 1) — o spec trava a renderização fiel do
 * payload, não recalcula regra.
 *
 * **F38 CORRIGIDO (C13b)**: o erro do download do XLSX vai ao toast e não apaga mais a grade.
 * **F37 CORRIGIDO (C14)**: o seletor deixou de prender o módulo ao ano do relógio — a grade passa o
 * range da política em `[anos]` e alcança a virada do ano. Os blocos de render abaixo travam o que a
 * TELA da grade mostra (option/seta/GET); a política do range e a mecânica interna do seletor (o
 * salto do `<select>` do F69 e a janela de 13 meses do F70, C20) vivem no spec dedicado do componente.
 */

/** Payload representativo de `GET /api/admin/ponto/retificacoes/grade` (julho/2026). */
function payloadGrade(qtdFuncionarios = 3) {
  return {
    categoria: 'operadores',
    ano: 2026,
    mes: 7,
    funcionarios: Array.from({ length: qtdFuncionarios }, (_, i) => ({
      id: `op-${i + 1}`,
      nome: `Operador ${i + 1}`,
      folgas: i,
    })),
    dias: [
      { dia: 1, data: '2026-07-01', dow: 3, fim_semana: false, marcacao_global: null },
      { dia: 4, data: '2026-07-04', dow: 6, fim_semana: true, marcacao_global: null },
      { dia: 6, data: '2026-07-06', dow: 1, fim_semana: false, marcacao_global: 'FERIADO' },
    ],
    celulas: {
      'op-1': {
        1: { tipo: 'horarios', texto: '08:00 12:00 13:00 17:00', tem_obs: true, obs: 'esqueci de bater' },
        6: { tipo: 'marcacao_global', texto: 'Feriado', tem_obs: false },
      },
      'op-2': {
        1: { tipo: 'banco', texto: 'Folga', tem_obs: false },
      },
    },
  };
}

describe('GradeRetificacoesComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPut: ReturnType<typeof vi.fn>;
  let apiGetBlob: ReturnType<typeof vi.fn>;
  let baixarBlob: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;

  const XLSX = new Blob(['PK'], { type: 'application/vnd.ms-excel' });

  /** Marcações de `GET /api/admin/ponto/marcacoes` (julho/2026). */
  const MARCACOES = {
    globais: [{ data: '2026-07-09', tipo: 'FERIADO' }],
    pessoais: [
      { pessoa_id: 'op-1', data: '2026-07-15', tipo: 'ATESTADO' },
      { pessoa_id: 'op-2', data: '2026-07-20', tipo: 'FERIAS' },
    ],
  };

  beforeEach(async () => {
    apiGet = vi.fn().mockImplementation((url: string) =>
      url.endsWith('/marcacoes') ? of({ data: structuredClone(MARCACOES) }) : of({ data: payloadGrade() }),
    );
    apiPut = vi.fn().mockReturnValue(of({ ok: true }));
    apiGetBlob = vi.fn().mockReturnValue(of(XLSX));
    baixarBlob = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [GradeRetificacoesComponent],
      providers: [
        // baixarBlob/getBlob mockados: o ApiService real usa URL.createObjectURL, que o jsdom não implementa
        { provide: ApiService, useValue: { get: apiGet, put: apiPut, getBlob: apiGetBlob, baixarBlob } },
        // C13b/F38: o erro do DOWNLOAD passou a sair pelo toast (canal separado do erro da grade)
        { provide: ToastService, useValue: { error: toastError, success: vi.fn(), warning: vi.fn(), show: vi.fn() } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Congela o relógio e cria o componente (o `hoje`/`anoMes` são lidos na construção). */
  function criar(hoje = '2026-07-12T10:00:00-03:00'): GradeRetificacoesComponent {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    return TestBed.createComponent(GradeRetificacoesComponent).componentInstance;
  }

  /** Componente com a grade já carregada (ngOnInit à mão — sem detectChanges não há ciclo de vida). */
  function criarCarregado(hoje?: string): GradeRetificacoesComponent {
    const comp = criar(hoje);
    comp.ngOnInit();
    return comp;
  }

  /** Componente + modal Configurar aberto (marcações do E6 já carregadas). */
  function criarComConfigurar(): GradeRetificacoesComponent {
    const comp = criarCarregado();
    comp.abrirConfigurar();
    return comp;
  }

  /** `Event` de <select> com `target.value` MUTÁVEL (o `onEscopo` escreve de volta ao cancelar). */
  function eventoSelect(value: string): Event {
    const sel = document.createElement('select');
    for (const v of ['todos', 'op-1', 'op-2', 'operadores', 'tecnicos', 'administradores', value]) {
      const opt = document.createElement('option');
      opt.value = v;
      sel.appendChild(opt);
    }
    sel.value = value;
    return { target: sel } as unknown as Event;
  }

  // ═══════════════════════════════════════════════════════════════════
  // carregar — a grade vem pronta do backend (precedência já resolvida)
  // ═══════════════════════════════════════════════════════════════════
  describe('carregar', () => {
    it('ngOnInit busca a grade do mês corrente com ano/mês NUMÉRICOS', () => {
      const comp = criarCarregado();
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 7,
      });
      expect(comp.funcionarios().map(f => f.id)).toEqual(['op-1', 'op-2', 'op-3']);
      expect(comp.dias().map(d => d.dia)).toEqual([1, 4, 6]);
      expect(comp.carregando()).toBe(false);
      expect(comp.erro()).toBe('');
    });

    it('carregando fica true enquanto a resposta não chega', () => {
      const resposta = new Subject<any>();
      apiGet.mockReturnValue(resposta);
      const comp = criarCarregado();

      expect(comp.carregando()).toBe(true);
      expect(comp.funcionarios()).toEqual([]);

      resposta.next({ data: payloadGrade() });
      expect(comp.carregando()).toBe(false);
      expect(comp.funcionarios()).toHaveLength(3);
    });

    it('erro: zera a grade e mostra a mensagem do backend', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => ({ error: { message: 'Categoria inválida' } })));
      comp.ngOnInit();
      expect(comp.grade()).toBeNull();
      expect(comp.erro()).toBe('Categoria inválida');
      expect(comp.carregando()).toBe(false);
    });

    it('erro sem corpo: fallback', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      comp.ngOnInit();
      expect(comp.erro()).toBe('Erro ao carregar a grade.');
    });

    it('resposta sem data: grade nula, sem funcionários (empty-state)', () => {
      const comp = criar();
      apiGet.mockReturnValue(of({}));
      comp.ngOnInit();
      expect(comp.grade()).toBeNull();
      expect(comp.funcionarios()).toEqual([]);
      expect(comp.dias()).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Montagem das células — renderização FIEL do payload (sem recalcular regra)
  // ═══════════════════════════════════════════════════════════════════
  describe('células e derivados', () => {
    it('celula(funcionário, dia) devolve a célula do payload; ausente = vazia (undefined)', () => {
      const comp = criarCarregado();
      expect(comp.celula('op-1', 1)).toEqual({
        tipo: 'horarios', texto: '08:00 12:00 13:00 17:00', tem_obs: true, obs: 'esqueci de bater',
      });
      expect(comp.celula('op-1', 6)?.tipo).toBe('marcacao_global');
      expect(comp.celula('op-2', 1)?.texto).toBe('Folga');
      expect(comp.celula('op-1', 4)).toBeUndefined(); // dia sem célula
      expect(comp.celula('op-3', 1)).toBeUndefined(); // funcionário sem nenhuma célula
    });

    it('celula() é segura com a grade vazia', () => {
      expect(criar().celula('op-1', 1)).toBeUndefined();
    });

    it('horariosLinhas agrupa os horários em pares Ent./Saí. (uma linha por par)', () => {
      const comp = criarCarregado();
      expect(comp.horariosLinhas('08:00 12:00 13:00 17:00')).toEqual(['08:00 12:00', '13:00 17:00']);
      expect(comp.horariosLinhas('08:00 12:00')).toEqual(['08:00 12:00']);
      expect(comp.horariosLinhas('08:00')).toEqual(['08:00']); // par 1 sem saída
    });

    it('horariosLinhas com texto vazio devolve [""] (nunca lista vazia)', () => {
      expect(criarCarregado().horariosLinhas('')).toEqual(['']);
    });

    it('mesAbrev vem do mês do PAYLOAD (o rótulo "1-jul" da 1ª coluna)', () => {
      const comp = criarCarregado();
      expect(comp.mesAbrev()).toBe('jul');
    });

    it('mesAbrev cai no mês do seletor enquanto não há grade', () => {
      expect(criar('2026-09-10T12:00:00-03:00').mesAbrev()).toBe('set');
    });

    it('tituloMesAno / primeiroDiaMes / ultimoDiaMes seguem o mês selecionado', () => {
      const comp = criar('2026-02-10T12:00:00-03:00'); // fevereiro (bissexto? 2026 não é)
      expect(comp.tituloMesAno()).toBe('Fevereiro de 2026');
      expect(comp.primeiroDiaMes().getDate()).toBe(1);
      expect(comp.ultimoDiaMes().getDate()).toBe(28);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Paginação client-side — 8 colunas de funcionários por página (B-3.9)
  // ═══════════════════════════════════════════════════════════════════
  describe('paginação de 8 colunas', () => {
    /** Grade com N funcionários (as células não importam para o recorte). */
    function comFuncionarios(n: number): GradeRetificacoesComponent {
      apiGet.mockReturnValue(of({ data: { ...payloadGrade(n), celulas: {} } }));
      return criarCarregado();
    }

    it('8 funcionários cabem em 1 página (o limite exato)', () => {
      const comp = comFuncionarios(8);
      expect(comp.totalPaginas()).toBe(1);
      expect(comp.funcsPagina()).toHaveLength(8);
    });

    it('9 funcionários viram 2 páginas: a 2ª é parcial', () => {
      const comp = comFuncionarios(9);
      expect(comp.totalPaginas()).toBe(2);
      expect(comp.funcsPagina().map(f => f.id)).toEqual(
        ['op-1', 'op-2', 'op-3', 'op-4', 'op-5', 'op-6', 'op-7', 'op-8'],
      );

      comp.paginaSeguinte();
      expect(comp.pagina()).toBe(1);
      expect(comp.funcsPagina().map(f => f.id)).toEqual(['op-9']); // última página parcial
    });

    it('navegação faz clamp nos dois extremos', () => {
      const comp = comFuncionarios(9);
      comp.paginaAnterior(); // já na 1ª
      expect(comp.pagina()).toBe(0);

      comp.paginaSeguinte();
      comp.paginaSeguinte(); // já na última
      expect(comp.pagina()).toBe(1);

      comp.paginaAnterior();
      expect(comp.pagina()).toBe(0);
    });

    it('sem funcionários: 1 página (vazia), sem divisão por zero', () => {
      const comp = comFuncionarios(0);
      expect(comp.totalPaginas()).toBe(1);
      expect(comp.funcsPagina()).toEqual([]);
    });

    it('recarregar volta para a 1ª página (a lista de funcionários muda com a categoria/mês)', () => {
      const comp = comFuncionarios(9);
      comp.paginaSeguinte();
      expect(comp.pagina()).toBe(1);

      comp.onMesAno({ ano: 2026, mes: 6 });
      expect(comp.pagina()).toBe(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Barra: categoria e mês/ano — cada mudança recarrega a grade
  // ═══════════════════════════════════════════════════════════════════
  describe('categoria e mês/ano', () => {
    it('trocar a categoria recarrega a grade com a nova categoria', () => {
      const comp = criarCarregado();
      comp.onCategoria(eventoSelect('tecnicos'));

      expect(comp.categoria()).toBe('tecnicos');
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'tecnicos', ano: 2026, mes: 7,
      });
    });

    it('trocar o mês recarrega a grade com o novo mês/ano', () => {
      const comp = criarCarregado();
      comp.onMesAno({ ano: 2025, mes: 12 });

      expect(comp.anoMes()).toEqual({ ano: 2025, mes: 12 });
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2025, mes: 12,
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // baixarTabela — XLSX do mês/categoria (B-5.1, nome ponto_{cat}_{AAMM}.xlsx — Q31)
  // ═══════════════════════════════════════════════════════════════════
  describe('baixarTabela', () => {
    it('baixa o XLSX com ano/mês em STRING e nome ponto_{categoria}_{AAMM}.xlsx', () => {
      const comp = criarCarregado();
      comp.baixarTabela();

      expect(apiGetBlob).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade/xlsx', {
        categoria: 'operadores', ano: '2026', mes: '7',
      });
      expect(baixarBlob).toHaveBeenCalledWith(XLSX, 'ponto_operadores_2607.xlsx');
    });

    it('o mês entra com zero à esquerda no nome (março → 2603)', () => {
      const comp = criarCarregado('2026-03-05T12:00:00-03:00');
      comp.onCategoria(eventoSelect('administradores'));
      comp.baixarTabela();
      expect(baixarBlob).toHaveBeenCalledWith(XLSX, 'ponto_administradores_2603.xlsx');
    });

    it('corrige F38 — o erro do DOWNLOAD vai para o TOAST: a grade não é apagada', () => {
      // Antes (C13b/F38): `baixarTabela` fazia `erro.set(...)` — o mesmo signal do primeiro @if do
      // template (`@if (erro()) { error-box } @else if … { tabela }`). Um 500 no XLSX trocava a grade
      // inteira por "Erro ao baixar a tabela.", embora os dados continuassem em memória; só trocar de
      // mês/categoria a trazia de volta. O que falhou foi o ARQUIVO, não a tela.
      const comp = criarCarregado();
      apiGetBlob.mockReturnValue(throwError(() => new Error('500')));

      comp.baixarTabela();

      expect(toastError).toHaveBeenCalledWith('Erro ao baixar a tabela.');
      expect(comp.erro()).toBe('');                 // o canal da GRADE fica intocado
      expect(baixarBlob).not.toHaveBeenCalled();
      expect(comp.funcionarios()).toHaveLength(3);  // e os dados seguem exibidos
    });

    it('erro de CARGA da grade continua no signal `erro` (o canal não foi esvaziado pela correção)', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/grade') ? throwError(() => ({ error: { message: 'Falha na consulta' } })) : of({ data: {} }),
      );
      const comp = criarCarregado();

      expect(comp.erro()).toBe('Falha na consulta');
      expect(toastError).not.toHaveBeenCalled();   // carga não é download: canais separados
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Configurar (B-4) — marcações globais e pessoa-dia
  // ═══════════════════════════════════════════════════════════════════
  describe('Configurar — abertura e escopo', () => {
    it('abrir carrega as marcações do mês e começa no escopo "todos" (globais)', () => {
      const comp = criarComConfigurar();

      expect(comp.configurarAberto()).toBe(true);
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/marcacoes', { ano: 2026, mes: 7 });
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.escopo()).toBe('todos');
      expect(comp.modo()).toBe('FERIADO');
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'FERIADO']]); // só as globais
    });

    it('carregando fica true até as marcações chegarem', () => {
      const resposta = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? resposta : of({ data: payloadGrade() }),
      );
      const comp = criarCarregado();
      comp.abrirConfigurar();

      expect(comp.carregandoConfig()).toBe(true);
      resposta.next({ data: MARCACOES });
      expect(comp.carregandoConfig()).toBe(false);
    });

    it('erro ao carregar as marcações: mensagem no modal, sem travar em "carregando" — e SEM destravar o Aplicar', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes')
          ? throwError(() => ({ error: { message: 'Sem permissão' } }))
          : of({ data: payloadGrade() }),
      );
      const comp = criarCarregado();
      comp.abrirConfigurar();

      expect(comp.erroConfig()).toBe('Sem permissão');
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.configurarAberto()).toBe(true);
      expect(comp.configCarregado()).toBe(false);   // C13b/F41: sem carga, o Aplicar não age
    });

    it('escopo por funcionário traz só as marcações dele e troca o modo padrão', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-1'));

      expect(comp.escopo()).toBe('op-1');
      expect(comp.modo()).toBe('A_DISPOSICAO');
      expect([...comp.marcacoesEscopo()]).toEqual([[15, 'ATESTADO']]); // nada da op-2
    });

    it('voltar para "todos" recupera as marcações globais', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-2'));
      expect([...comp.marcacoesEscopo()]).toEqual([[20, 'FERIAS']]);

      comp.onEscopo(eventoSelect('todos'));
      expect(comp.modo()).toBe('FERIADO');
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'FERIADO']]);
    });

    it('selecionar o MESMO escopo é no-op (não pergunta nem recarrega o mapa)', () => {
      const comp = criarComConfigurar();
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
      const antes = comp.marcacoesEscopo();

      comp.onEscopo(eventoSelect('todos'));

      expect(confirmSpy).not.toHaveBeenCalled();
      expect(comp.marcacoesEscopo()).toBe(antes); // mesma referência
    });

    it('trocar de escopo com edições pendentes: confirma antes de descartar', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13)); // marca o dia 13 (pendente)
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

      comp.onEscopo(eventoSelect('op-1'));

      expect(confirmSpy).toHaveBeenCalled();
      expect(comp.escopo()).toBe('op-1'); // aceitou → trocou (edições descartadas)
    });

    it('confirmação negada: mantém o escopo, as edições e devolve o <select> ao valor antigo', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      const ev = eventoSelect('op-1');

      comp.onEscopo(ev);

      expect(comp.escopo()).toBe('todos');
      expect(comp.marcacoesEscopo().get(13)).toBe('FERIADO'); // edição preservada
      expect((ev.target as HTMLSelectElement).value).toBe('todos'); // <select> revertido
    });

    it('modosDisponiveis muda com o escopo (globais × pessoais)', () => {
      const comp = criarComConfigurar();
      expect(comp.modosDisponiveis().map(m => m.valor)).toEqual(['FERIADO', 'PONTO_FACULTATIVO', '__limpar']);

      comp.onEscopo(eventoSelect('op-1'));
      expect(comp.modosDisponiveis().map(m => m.valor)).toEqual(
        ['A_DISPOSICAO', 'ATESTADO', 'FERIAS', 'RECESSO', 'LICENCA_MEDICA', '__limpar'],
      );
    });

    it('fechar não pergunta nada (as edições pendentes são descartadas em silêncio)', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

      comp.fecharConfigurar();

      expect(comp.configurarAberto()).toBe(false);
      expect(confirmSpy).not.toHaveBeenCalled(); // assimetria conhecida com a troca de escopo
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Configurar — clique no dia (toggle) e estado do calendário
  // ═══════════════════════════════════════════════════════════════════
  describe('Configurar — marcação do dia', () => {
    it('clicar num dia livre aplica o modo ativo', () => {
      const comp = criarComConfigurar();
      const antes = comp.marcacoesEscopo();

      comp.onDiaConfig(new Date(2026, 6, 13));

      expect(comp.marcacoesEscopo().get(13)).toBe('FERIADO');
      expect(comp.marcacoesEscopo()).not.toBe(antes); // Map novo (reatividade)
    });

    it('clicar de novo no MESMO tipo remove a marcação (toggle)', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      comp.onDiaConfig(new Date(2026, 6, 13));
      expect(comp.marcacoesEscopo().has(13)).toBe(false);
    });

    it('com outro modo ativo, o clique SUBSTITUI o tipo do dia', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13)); // FERIADO
      comp.modo.set('PONTO_FACULTATIVO');
      comp.onDiaConfig(new Date(2026, 6, 13));
      expect(comp.marcacoesEscopo().get(13)).toBe('PONTO_FACULTATIVO');
    });

    it('modo "Limpar" remove a marcação existente (e não faz nada num dia livre)', () => {
      const comp = criarComConfigurar();
      comp.modo.set('__limpar');

      comp.onDiaConfig(new Date(2026, 6, 9)); // dia que veio marcado do backend
      expect(comp.marcacoesEscopo().has(9)).toBe(false);

      comp.onDiaConfig(new Date(2026, 6, 13)); // dia livre → segue livre
      expect(comp.marcacoesEscopo().has(13)).toBe(false);
    });

    it('estadoDiaConfig: dia útil marcado vem selecionado, com badge e rótulo', () => {
      const comp = criarComConfigurar();
      expect(comp.estadoDiaConfig(new Date(2026, 6, 9))).toEqual({
        selecionado: true, badge: 'Fer', rotulo: 'Feriado',
      });
    });

    it('estadoDiaConfig: dia útil livre não tem override (null)', () => {
      const comp = criarComConfigurar();
      expect(comp.estadoDiaConfig(new Date(2026, 6, 13))).toBeNull();
    });

    it('estadoDiaConfig: fim de semana e dia de outro mês ficam desabilitados', () => {
      const comp = criarComConfigurar();
      expect(comp.estadoDiaConfig(new Date(2026, 6, 11))).toEqual({ desabilitado: true }); // sábado
      expect(comp.estadoDiaConfig(new Date(2026, 6, 12))).toEqual({ desabilitado: true }); // domingo
      expect(comp.estadoDiaConfig(new Date(2026, 7, 3))).toEqual({ desabilitado: true });  // agosto
      expect(comp.estadoDiaConfig(new Date(2025, 6, 8))).toEqual({ desabilitado: true });  // outro ano
    });

    it('estadoDiaConfig segue o escopo pessoal (badge do tipo daquela pessoa)', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-1'));
      expect(comp.estadoDiaConfig(new Date(2026, 6, 15))).toEqual({
        selecionado: true, badge: 'Atest', rotulo: 'Atestado',
      });
      expect(comp.estadoDiaConfig(new Date(2026, 6, 9))).toBeNull(); // a global não aparece no escopo pessoal
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Configurar — Aplicar: envia o DIFF (aplicar + remover) do escopo
  // ═══════════════════════════════════════════════════════════════════
  describe('Configurar — aplicar', () => {
    it('sem mudanças: fecha o modal sem chamar a API', () => {
      const comp = criarComConfigurar();
      comp.aplicarConfig();

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.configurarAberto()).toBe(false);
    });

    it('globais: envia os dias novos em "aplicar" e os retirados em "remover"', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13)); // novo FERIADO
      comp.modo.set('__limpar');
      comp.onDiaConfig(new Date(2026, 6, 9));  // remove o feriado que veio do backend

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: {
          aplicar: [{ data: '2026-07-13', tipo: 'FERIADO' }],
          remover: ['2026-07-09'],
        },
      });
    });

    it('mudar só o TIPO do dia entra em "aplicar" (não vira remover+aplicar)', () => {
      const comp = criarComConfigurar();
      comp.modo.set('PONTO_FACULTATIVO');
      comp.onDiaConfig(new Date(2026, 6, 9)); // era FERIADO

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: { aplicar: [{ data: '2026-07-09', tipo: 'PONTO_FACULTATIVO' }], remover: [] },
      });
    });

    it('pessoais: envia pessoa_id + pessoa_tipo derivado da categoria da grade', () => {
      const comp = criarComConfigurar();
      comp.onCategoria(eventoSelect('tecnicos'));
      comp.onEscopo(eventoSelect('op-1'));
      comp.onDiaConfig(new Date(2026, 6, 22)); // A_DISPOSICAO (modo padrão do escopo pessoal)

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        pessoais: {
          pessoa_id: 'op-1',
          pessoa_tipo: 'TECNICO',
          aplicar: [{ data: '2026-07-22', tipo: 'A_DISPOSICAO' }],
          remover: [],
        },
      });
    });

    it('as datas do diff usam o mês/ano selecionado, com zero à esquerda', () => {
      const comp = criarCarregado('2026-03-10T12:00:00-03:00');
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? of({ data: { globais: [], pessoais: [] } }) : of({ data: payloadGrade() }),
      );
      comp.abrirConfigurar();
      comp.onDiaConfig(new Date(2026, 2, 5));

      comp.aplicarConfig();

      expect(apiPut.mock.calls[0][1].globais.aplicar).toEqual([{ data: '2026-03-05', tipo: 'FERIADO' }]);
    });

    it('sucesso: fecha o modal e recarrega a grade', () => {
      const comp = criarComConfigurar();
      const chamadasAntes = apiGet.mock.calls.length;
      comp.onDiaConfig(new Date(2026, 6, 13));

      comp.aplicarConfig();

      expect(comp.aplicandoConfig()).toBe(false);
      expect(comp.configurarAberto()).toBe(false);
      expect(apiGet.mock.calls.length).toBe(chamadasAntes + 1);
      expect(apiGet.mock.calls.at(-1)![0]).toBe('/api/admin/ponto/retificacoes/grade');
    });

    it('erro: modal segue aberto com a mensagem do backend, sem recarregar a grade', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      apiPut.mockReturnValue(throwError(() => ({ error: { message: 'Dia inválido' } })));
      const chamadasAntes = apiGet.mock.calls.length;

      comp.aplicarConfig();

      expect(comp.erroConfig()).toBe('Dia inválido');
      expect(comp.aplicandoConfig()).toBe(false);
      expect(comp.configurarAberto()).toBe(true);
      expect(apiGet.mock.calls.length).toBe(chamadasAntes); // não recarregou
    });

    it('erro sem corpo: fallback', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      apiPut.mockReturnValue(throwError(() => new Error('rede')));
      comp.aplicarConfig();
      expect(comp.erroConfig()).toBe('Erro ao aplicar as marcações.');
    });

    it('aplicando fica true enquanto o PUT não responde', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      const resposta = new Subject<any>();
      apiPut.mockReturnValue(resposta);

      comp.aplicarConfig();
      expect(comp.aplicandoConfig()).toBe(true);
      expect(comp.erroConfig()).toBe('');

      resposta.next({ ok: true });
      expect(comp.aplicandoConfig()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // C13b/F41 — o Configurar não reabre com o estado do mês/escopo anterior
  // ═══════════════════════════════════════════════════════════════════
  describe('corrige F41 — reabertura do Configurar sem estado velho', () => {
    /** Faz o GET das marcações falhar (a grade continua respondendo normalmente). */
    function marcacoesFalham(msg = 'Erro no servidor') {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes')
          ? throwError(() => ({ error: { message: msg } }))
          : of({ data: payloadGrade() }),
      );
    }

    it('abrir em junho, fechar, ir para julho e reabrir com o GET FALHANDO: calendário vazio, erro visível, Aplicar travado', () => {
      // O defeito: só o ramo de SUCESSO repovoava o modal (via aplicarEscopo). No erro, nada era
      // limpo — o calendário de JULHO exibia as marcações de JUNHO, e o Aplicar monta o ISO com o mês
      // CORRENTE: um clique mandava `remover: ['2026-07-05']` por causa de um feriado que era 05/06.
      const comp = criarCarregado('2026-06-10T10:00:00-03:00');
      comp.abrirConfigurar();
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'FERIADO']]);   // marcação de junho na tela
      comp.fecharConfigurar();

      comp.onMesAno({ ano: 2026, mes: 7 });   // o usuário vai para julho
      marcacoesFalham();
      comp.abrirConfigurar();

      expect(comp.marcacoesEscopo().size).toBe(0);      // nada de junho sobrevive
      expect(comp.erroConfig()).toBe('Erro no servidor');
      expect(comp.configCarregado()).toBe(false);       // Aplicar travado
      expect(comp.escopo()).toBe('todos');              // escopo e modo também voltam ao inicial
      expect(comp.modo()).toBe('FERIADO');
    });

    it('o escopo PESSOAL escolhido antes não sobrevive à reabertura', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-1'));
      expect(comp.escopo()).toBe('op-1');
      expect(comp.modo()).toBe('A_DISPOSICAO');
      comp.fecharConfigurar();

      marcacoesFalham();
      comp.abrirConfigurar();

      expect(comp.escopo()).toBe('todos');
      expect(comp.modo()).toBe('FERIADO');
      expect(comp.marcacoesEscopo().size).toBe(0);
    });

    it('com a carga falhada, o Aplicar NÃO age nem que seja chamado à força (nenhum PUT fantasma)', () => {
      marcacoesFalham();
      const comp = criarCarregado();
      comp.abrirConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));   // o usuário até consegue clicar no calendário

      comp.aplicarConfig();

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.configurarAberto()).toBe(true);   // o modal não some fingindo que aplicou
    });

    it('a resposta OBSOLETA de uma abertura anterior não repovoa o modal (token de recência)', () => {
      // abrir (GET lento) → fechar → trocar de mês → reabrir (GET rápido, OK) → a resposta VELHA chega.
      const lenta = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? lenta : of({ data: payloadGrade() }),
      );
      const comp = criarCarregado('2026-06-10T10:00:00-03:00');
      comp.abrirConfigurar();          // 1ª carga (junho) — fica no ar
      comp.fecharConfigurar();

      comp.onMesAno({ ano: 2026, mes: 7 });
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? of({ data: { globais: [], pessoais: [] } }) : of({ data: payloadGrade() }),
      );
      comp.abrirConfigurar();          // 2ª carga (julho) — responde na hora: julho está VAZIO
      expect(comp.marcacoesEscopo().size).toBe(0);

      lenta.next({ data: structuredClone(MARCACOES) });   // a de junho chega atrasada

      expect(comp.marcacoesEscopo().size).toBe(0);        // e é ignorada
      expect(comp.configCarregado()).toBe(true);
    });

    it('reabrir com um PUT em voo: o modal NÃO volta preso em "Aplicando..." — e a resposta velha não o fecha', () => {
      // Achado da revisão adversarial do próprio C13b: o reset esquecia `aplicandoConfig`, e o PUT não
      // tinha token. Aplicar (PUT lento) → Cancelar → Configurar de novo devolvia um modal INÚTIL (botão
      // travado em "Aplicando...") que a resposta velha depois FECHAVA sozinha — ou enchia de erro
      // fantasma de uma ação que o usuário não repetiu nesta sessão.
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      const putEmVoo = new Subject<any>();
      apiPut.mockReturnValue(putEmVoo);

      comp.aplicarConfig();
      expect(comp.aplicandoConfig()).toBe(true);
      comp.fecharConfigurar();          // o Cancelar não é bloqueado durante o PUT

      apiPut.mockReturnValue(of({ ok: true }));
      comp.abrirConfigurar();           // sessão NOVA

      expect(comp.aplicandoConfig()).toBe(false);   // o Aplicar reabre utilizável
      expect(comp.configCarregado()).toBe(true);

      putEmVoo.next({ ok: true });                  // o PUT velho responde agora

      expect(comp.configurarAberto()).toBe(true);   // e NÃO fecha o modal que o usuário acabou de abrir
      expect(comp.erroConfig()).toBe('');
    });

    it('o ERRO de um PUT velho não pinta mensagem fantasma na sessão nova', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      const putEmVoo = new Subject<any>();
      apiPut.mockReturnValue(putEmVoo);
      comp.aplicarConfig();
      comp.fecharConfigurar();

      comp.abrirConfigurar();
      putEmVoo.error({ error: { message: 'Dia inválido' } });

      expect(comp.erroConfig()).toBe('');
      expect(comp.aplicandoConfig()).toBe(false);
    });

    it('o modal com a carga falhada oferece RETRY (não é beco sem saída) — e o retry o destrava', () => {
      marcacoesFalham();
      const comp = criarCarregado();
      comp.abrirConfigurar();
      expect(comp.configCarregado()).toBe(false);

      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? of({ data: structuredClone(MARCACOES) }) : of({ data: payloadGrade() }),
      );
      comp.carregarMarcacoes();                      // é o que a caixa app-erro-carga dispara

      expect(comp.erroConfig()).toBe('');
      expect(comp.configCarregado()).toBe(true);
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'FERIADO']]);
    });

    it('a carga bem-sucedida destrava o Aplicar; o erro do PUT NÃO o trava de novo (retry legítimo)', () => {
      const comp = criarComConfigurar();
      expect(comp.configCarregado()).toBe(true);

      comp.onDiaConfig(new Date(2026, 6, 13));
      apiPut.mockReturnValue(throwError(() => ({ error: { message: 'Dia inválido' } })));
      comp.aplicarConfig();

      expect(comp.erroConfig()).toBe('Dia inválido');
      expect(comp.configCarregado()).toBe(true);   // ← a distinção do §3.3.2: repetir o Aplicar é o retry

      apiPut.mockReturnValue(of({ ok: true }));
      comp.aplicarConfig();
      expect(apiPut).toHaveBeenCalledTimes(2);
      expect(comp.configurarAberto()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER (C13b) — o que o usuário VÊ: a grade que não some (F38) e o
  // Aplicar que não age sobre estado desconhecido (F41)
  // ═══════════════════════════════════════════════════════════════════
  describe('render — grade preservada no erro de download (F38) e gate do Aplicar (F41)', () => {
    // Exceção deliberada ao GATE "só lógica": as duas correções SÓ existem no template — a grade que
    // sobrevive (o primeiro @if) e o [disabled] do Aplicar. Sem render, apagar os bindings deixaria a
    // suíte verde e devolveria os defeitos na pior forma.

    function renderizar(): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();   // ngOnInit + render (as respostas do mock são síncronas)
      return fixture;
    }

    const btnAplicar = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.queryAll(By.css('.modal-actions button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.trim().startsWith('Aplic'))
        ?.nativeElement as HTMLButtonElement | undefined;

    it('F38: com o download falhando, a TABELA continua na tela e não há caixa de erro', () => {
      const fixture = renderizar();
      expect(fixture.debugElement.query(By.css('table.grade'))).not.toBeNull();

      apiGetBlob.mockReturnValue(throwError(() => new Error('500')));
      fixture.componentInstance.baixarTabela();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('table.grade'))).not.toBeNull();   // a grade FICA
      expect(fixture.debugElement.query(By.css('.error-box'))).toBeNull();        // sem caixa no lugar dela
      expect(toastError).toHaveBeenCalledWith('Erro ao baixar a tabela.');
    });

    it('F41: reabertura com o GET falhando → calendário sem marcações, erro no modal e Aplicar DESABILITADO', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;

      comp.abrirConfigurar();                 // 1ª abertura: OK (dia 9 marcado)
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(false);
      comp.fecharConfigurar();

      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes')
          ? throwError(() => ({ error: { message: 'Erro interno do servidor' } }))
          : of({ data: payloadGrade() }),
      );
      comp.abrirConfigurar();                 // 2ª abertura: o GET falha
      fixture.detectChanges();

      expect(comp.marcacoesEscopo().size).toBe(0);
      expect(fixture.debugElement.query(By.css('.modal-card .error-box'))!.nativeElement.textContent)
        .toContain('Erro interno do servidor');
      expect(btnAplicar(fixture)!.disabled).toBe(true);   // não age sobre estado desconhecido
    });

    it('F41: o modal com a carga falhada mostra a caixa COM RETRY, e o clique nela recarrega e libera o Aplicar', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes')
          ? throwError(() => ({ error: { message: 'Erro interno do servidor' } }))
          : of({ data: payloadGrade() }),
      );

      comp.abrirConfigurar();
      fixture.detectChanges();

      const caixa = fixture.debugElement.query(By.css('.modal-card .erro-carga'));
      expect(caixa).not.toBeNull();                       // sem isto o modal seria um beco sem saída
      expect(btnAplicar(fixture)!.disabled).toBe(true);

      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? of({ data: structuredClone(MARCACOES) }) : of({ data: payloadGrade() }),
      );
      (caixa.query(By.css('button')).nativeElement as HTMLButtonElement).click();   // "Tentar novamente"
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.modal-card .erro-carga'))).toBeNull();
      expect(btnAplicar(fixture)!.disabled).toBe(false);
    });

    it('F41: enquanto a carga do modal está EM VOO, o Aplicar não está disponível (o modal ainda diz "Carregando marcações...")', () => {
      const fixture = renderizar();
      const emVoo = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/marcacoes') ? emVoo : of({ data: payloadGrade() }),
      );

      fixture.componentInstance.abrirConfigurar();
      fixture.detectChanges();

      expect(fixture.componentInstance.configCarregado()).toBe(false);
      expect(btnAplicar(fixture)).toBeUndefined();   // o botão nem existe: o modal está no ramo "carregando"

      emVoo.next({ data: structuredClone(MARCACOES) });
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(false);   // a carga bem-sucedida o libera
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER (C14/F37) — a grade alcança o mês vivo na virada do ano
  //
  // A correção está no TEMPLATE (`[anos]="anosSeletor"`): sem render, apagar esse binding deixaria
  // a suíte verde com o admin de novo sem acesso a dezembro em janeiro — bem no prazo de retificação.
  // ═══════════════════════════════════════════════════════════════════
  describe('render — o seletor da barra cruza a virada do ano (F37)', () => {
    function renderizar(hoje: string): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(hoje));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();   // ngOnInit + render (o seletor filho é instanciado aqui)
      return fixture;
    }

    const seletor = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.query(By.directive(MesAnoSelectorComponent)).componentInstance as MesAnoSelectorComponent;
    const setaVoltar = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.query(By.css('app-mes-ano-selector button[aria-label="Mês anterior"]'))
        .nativeElement as HTMLButtonElement;

    it('corrige F37 — em 05/01/2027 o ‹ está habilitado e o clique pede a grade de DEZEMBRO/2026', () => {
      const fixture = renderizar('2027-01-05T09:00:00-03:00');
      const comp = fixture.componentInstance;

      expect(seletor(fixture).anos()).toEqual([2026, 2027]);   // o range chega ao filho pelo [anos]
      expect(setaVoltar(fixture).disabled).toBe(false);

      apiGet.mockClear();
      setaVoltar(fixture).click();                             // clique real na seta ‹
      fixture.detectChanges();

      expect(comp.anoMes()).toEqual({ ano: 2026, mes: 12 });
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 12,
      });
      expect(comp.tituloMesAno()).toBe('Dezembro de 2026');    // o modal Configurar segue o mês exibido
    });

    it('regressão: em julho o seletor da barra segue preso ao ano corrente (uma <option>, ‹ dentro do ano)', () => {
      const fixture = renderizar('2026-07-12T10:00:00-03:00');
      expect(seletor(fixture).anos()).toEqual([2026]);
      expect(fixture.debugElement.queryAll(By.css('app-mes-ano-selector select.sel-ano option'))).toHaveLength(1);
    });
  });
});

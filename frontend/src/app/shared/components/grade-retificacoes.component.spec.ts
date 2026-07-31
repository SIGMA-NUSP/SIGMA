import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfigurarTiposMarcacaoComponent, TipoMarcacao } from './configurar-tipos-marcacao.component';
import { GradeRetificacoesComponent } from './grade-retificacoes.component';
import { MesAnoSelectorComponent } from './mes-ano-selector.component';
import { ToastService } from './toast.component';

/**
 * GradeRetificacoesComponent (card admin "Retificações"): carga da grade, montagem das células
 * fiel ao payload (a precedência por célula é do BACKEND — nada é recalculado aqui), paginação
 * client-side de 8 colunas, troca de categoria/mês, download do XLSX (o erro vai ao TOAST,
 * canal separado do erro da grade) e o modal Ocorrências (catálogo de tipos + marcações, diff
 * aplicar/remover, gate do Aplicar, retry).
 *
 * Os tipos de ocorrência NÃO são constantes do front: vêm do catálogo do backend, e o modal
 * faz DUAS cargas em sequência — `/tipos-marcacao` e, só depois, `/marcacoes` do mês. Por isso
 * o `get` mockado é roteado por endpoint em três respostas independentes (tipos, marcações,
 * grade): derrubar uma não mexe nas outras.
 *
 * TestBed sem `detectChanges()` por padrão — `ngOnInit` à mão, filhos não instanciados;
 * `ApiService` e `ToastService` mockados via `useValue`; `AuthService` é um stub com o signal
 * `isMaster` (o botão "Configurar", do catálogo, é só do master). Relógio congelado
 * (`{toFake:['Date']}`) ANTES de `createComponent`: `hoje`/`anoMes` são lidos no field
 * initializer. A mecânica interna do seletor de mês/ano vive no spec dedicado
 * `mes-ano-selector.component.spec.ts` — aqui só o que a grade exibe através dele.
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
      { dia: 6, data: '2026-07-06', dow: 1, fim_semana: false, marcacao_global: 'Feriado' },
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

/** Endpoints do componente, em três canais independentes de resposta. */
type Rota = 'tipos' | 'marcacoes' | 'grade';
function rotaDe(url: string): Rota {
  if (url.includes('/tipos-marcacao')) return 'tipos';
  if (url.endsWith('/marcacoes')) return 'marcacoes';
  return 'grade';
}

describe('GradeRetificacoesComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPut: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiDelete: ReturnType<typeof vi.fn>;
  let apiGetBlob: ReturnType<typeof vi.fn>;
  let baixarBlob: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  /** Resposta corrente de cada endpoint — trocar UMA não toca nas outras. */
  let respostas: Record<Rota, () => Observable<any>>;

  const XLSX = new Blob(['PK'], { type: 'application/vnd.ms-excel' });

  /**
   * Catálogo de `GET /api/admin/ponto/tipos-marcacao`. A ORDEM importa: o modal ativa o
   * primeiro tipo do escopo exibido (Feriado nas globais, À Disposição nas individuais).
   */
  const TIPOS: TipoMarcacao[] = [
    { id: 'tp-feriado', nome: 'Feriado', badge: 'Fer', escopo: 'GLOBAL' },
    { id: 'tp-facultativo', nome: 'Ponto Facultativo', badge: 'PF', escopo: 'GLOBAL' },
    { id: 'tp-disposicao', nome: 'À Disposição', badge: 'Disp', escopo: 'INDIVIDUAL' },
    { id: 'tp-atestado', nome: 'Atestado', badge: 'Atest', escopo: 'INDIVIDUAL' },
    { id: 'tp-ferias', nome: 'Férias', badge: 'Fér', escopo: 'INDIVIDUAL' },
  ];

  /** Marcações de `GET /api/admin/ponto/marcacoes` (julho/2026) — já descritas pelo backend. */
  const MARCACOES = {
    globais: [{ data: '2026-07-09', tipo_id: 'tp-feriado', nome: 'Feriado', badge: 'Fer' }],
    pessoais: [
      { pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', data: '2026-07-15', tipo_id: 'tp-atestado', nome: 'Atestado', badge: 'Atest' },
      { pessoa_id: 'op-2', pessoa_tipo: 'OPERADOR', data: '2026-07-20', tipo_id: 'tp-ferias', nome: 'Férias', badge: 'Fér' },
    ],
  };

  /** Resposta de erro com corpo do backend. */
  const falha = (msg: string) => () => throwError(() => ({ error: { message: msg } }));

  /** Quantas vezes cada endpoint foi pedido (o modal pede dois; a grade, um). */
  const chamadasDe = (rota: Rota) => apiGet.mock.calls.filter(c => rotaDe(c[0]) === rota).length;

  /** Monta o TestBed com o papel desejado — só o master enxerga o botão "Configurar". */
  async function configurar(master = true): Promise<void> {
    TestBed.resetTestingModule();
    respostas = {
      tipos: () => of({ data: { tipos: structuredClone(TIPOS) } }),
      marcacoes: () => of({ data: structuredClone(MARCACOES) }),
      grade: () => of({ data: payloadGrade() }),
    };
    apiGet = vi.fn((url: string) => respostas[rotaDe(url)]());
    apiPut = vi.fn().mockReturnValue(of({ ok: true }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    apiDelete = vi.fn().mockReturnValue(of({ ok: true }));
    apiGetBlob = vi.fn().mockReturnValue(of(XLSX));
    baixarBlob = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [GradeRetificacoesComponent],
      providers: [
        // baixarBlob/getBlob mockados: o ApiService real usa URL.createObjectURL, que o jsdom não implementa
        {
          provide: ApiService,
          useValue: { get: apiGet, put: apiPut, post: apiPost, delete: apiDelete, getBlob: apiGetBlob, baixarBlob },
        },
        // O erro do DOWNLOAD sai pelo toast (canal separado do erro da grade)
        { provide: ToastService, useValue: { error: toastError, success: vi.fn(), warning: vi.fn(), show: vi.fn() } },
        { provide: AuthService, useValue: { isMaster: signal(master) } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos
  }

  beforeEach(async () => {
    await configurar();   // o default dos testes é o MASTER
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

  /** Componente + modal Ocorrências aberto (catálogo e marcações já carregados). */
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

    it('a grade não puxa o catálogo de tipos: ele é carregado só quando o modal abre', () => {
      criarCarregado();
      expect(chamadasDe('tipos')).toBe(0);
      expect(chamadasDe('marcacoes')).toBe(0);
    });

    it('carregando fica true enquanto a resposta não chega', () => {
      const resposta = new Subject<any>();
      respostas.grade = () => resposta;
      const comp = criarCarregado();

      expect(comp.carregando()).toBe(true);
      expect(comp.funcionarios()).toEqual([]);

      resposta.next({ data: payloadGrade() });
      expect(comp.carregando()).toBe(false);
      expect(comp.funcionarios()).toHaveLength(3);
    });

    it('erro: zera a grade e mostra a mensagem do backend', () => {
      const comp = criar();
      respostas.grade = falha('Categoria inválida');
      comp.ngOnInit();
      expect(comp.grade()).toBeNull();
      expect(comp.erro()).toBe('Categoria inválida');
      expect(comp.carregando()).toBe(false);
    });

    it('erro sem corpo: fallback', () => {
      const comp = criar();
      respostas.grade = () => throwError(() => new Error('rede'));
      comp.ngOnInit();
      expect(comp.erro()).toBe('Erro ao carregar a grade.');
    });

    it('resposta sem data: grade nula, sem funcionários (empty-state)', () => {
      const comp = criar();
      respostas.grade = () => of({});
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
  // Paginação client-side — 8 colunas de funcionários por página
  // ═══════════════════════════════════════════════════════════════════
  describe('paginação de 8 colunas', () => {
    /** Grade com N funcionários (as células não importam para o recorte). */
    function comFuncionarios(n: number): GradeRetificacoesComponent {
      respostas.grade = () => of({ data: { ...payloadGrade(n), celulas: {} } });
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
  // baixarTabela — XLSX do mês/categoria (nome ponto_{categoria}_{AAMM}.xlsx)
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

    it('o erro do DOWNLOAD vai para o TOAST: a grade não é apagada', () => {
      // Antes, `baixarTabela` fazia `erro.set(...)` — o mesmo signal do primeiro @if do
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
      respostas.grade = falha('Falha na consulta');
      const comp = criarCarregado();

      expect(comp.erro()).toBe('Falha na consulta');
      expect(toastError).not.toHaveBeenCalled();   // carga não é download: canais separados
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Ocorrências — abertura (catálogo + marcações) e escopo
  // ═══════════════════════════════════════════════════════════════════
  describe('Ocorrências — abertura e escopo', () => {
    it('abrir carrega o CATÁLOGO e depois as marcações do mês, começando no escopo "todos"', () => {
      const comp = criarComConfigurar();

      expect(comp.configurarAberto()).toBe(true);
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao');
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/marcacoes', { ano: 2026, mes: 7 });
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.tipos()).toHaveLength(5);
      expect(comp.escopo()).toBe('todos');
      expect(comp.modo()).toBe('tp-feriado');                          // 1º tipo GLOBAL do catálogo
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'tp-feriado']]); // só as globais
    });

    it('as marcações do mês só são pedidas DEPOIS que o catálogo responde', () => {
      const tiposEmVoo = new Subject<any>();
      respostas.tipos = () => tiposEmVoo;
      const comp = criarCarregado();
      comp.abrirConfigurar();

      expect(comp.carregandoConfig()).toBe(true);
      expect(chamadasDe('marcacoes')).toBe(0);   // são os chips que dizem o que cada dia significa

      tiposEmVoo.next({ data: { tipos: structuredClone(TIPOS) } });

      expect(chamadasDe('marcacoes')).toBe(1);
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.modo()).toBe('tp-feriado');
    });

    it('carregando fica true até as marcações chegarem', () => {
      const resposta = new Subject<any>();
      respostas.marcacoes = () => resposta;
      const comp = criarCarregado();
      comp.abrirConfigurar();

      expect(comp.carregandoConfig()).toBe(true);
      resposta.next({ data: structuredClone(MARCACOES) });
      expect(comp.carregandoConfig()).toBe(false);
    });

    it('erro no CATÁLOGO: mensagem própria, marcações nem pedidas e Aplicar travado', () => {
      respostas.tipos = falha('Sem permissão');
      const comp = criarCarregado();
      comp.abrirConfigurar();

      // canal de CARGA: o guia da tela vem na frente e o detalhe do servidor entre parênteses
      expect(comp.erroConfig()).toBe('Erro ao carregar os tipos de ocorrência. (Sem permissão)');
      expect(chamadasDe('marcacoes')).toBe(0);   // sem tipos não há o que aplicar num dia
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.configurarAberto()).toBe(true);
      expect(comp.configCarregado()).toBe(false);
      expect(comp.tipos()).toEqual([]);
    });

    it('erro no catálogo sem corpo: fallback próprio dos tipos', () => {
      respostas.tipos = () => throwError(() => new Error('rede'));
      const comp = criarCarregado();
      comp.abrirConfigurar();
      expect(comp.erroConfig()).toBe('Erro ao carregar os tipos de ocorrência.');
    });

    it('o retry depois do erro do catálogo refaz as DUAS cargas', () => {
      respostas.tipos = falha('Sem permissão');
      const comp = criarCarregado();
      comp.abrirConfigurar();
      expect(comp.configCarregado()).toBe(false);

      respostas.tipos = () => of({ data: { tipos: structuredClone(TIPOS) } });
      comp.carregarMarcacoes();                  // é o que a caixa app-erro-carga dispara

      expect(chamadasDe('tipos')).toBe(2);
      expect(chamadasDe('marcacoes')).toBe(1);
      expect(comp.erroConfig()).toBe('');
      expect(comp.configCarregado()).toBe(true);
      expect(comp.modo()).toBe('tp-feriado');
    });

    it('erro ao carregar as marcações: mensagem no modal, sem travar em "carregando" — e SEM destravar o Aplicar', () => {
      respostas.marcacoes = falha('Sem permissão');
      const comp = criarCarregado();
      comp.abrirConfigurar();

      expect(comp.erroConfig()).toBe('Sem permissão');
      expect(comp.carregandoConfig()).toBe(false);
      expect(comp.configurarAberto()).toBe(true);
      expect(comp.configCarregado()).toBe(false);   // sem carga, o Aplicar não age
      expect(comp.tipos()).toHaveLength(5);         // o catálogo, esse, chegou
    });

    it('escopo por funcionário traz só as marcações dele e ativa o primeiro tipo INDIVIDUAL', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-1'));

      expect(comp.escopo()).toBe('op-1');
      expect(comp.modo()).toBe('tp-disposicao');
      expect([...comp.marcacoesEscopo()]).toEqual([[15, 'tp-atestado']]); // nada da op-2
    });

    it('voltar para "todos" recupera as marcações globais', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-2'));
      expect([...comp.marcacoesEscopo()]).toEqual([[20, 'tp-ferias']]);

      comp.onEscopo(eventoSelect('todos'));
      expect(comp.modo()).toBe('tp-feriado');
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'tp-feriado']]);
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
      expect(comp.marcacoesEscopo().get(13)).toBe('tp-feriado'); // edição preservada
      expect((ev.target as HTMLSelectElement).value).toBe('todos'); // <select> revertido
    });

    it('modosDisponiveis é o catálogo do escopo (globais × individuais) mais o "Limpar"', () => {
      const comp = criarComConfigurar();
      expect(comp.modosDisponiveis()).toEqual([
        { valor: 'tp-feriado', rotulo: 'Feriado' },
        { valor: 'tp-facultativo', rotulo: 'Ponto Facultativo' },
        { valor: '__limpar', rotulo: 'Limpar' },
      ]);

      comp.onEscopo(eventoSelect('op-1'));
      expect(comp.modosDisponiveis().map(m => m.valor)).toEqual(
        ['tp-disposicao', 'tp-atestado', 'tp-ferias', '__limpar'],
      );
    });

    it('catálogo vazio: nenhum chip (nem o "Limpar") e nenhum modo ativo', () => {
      respostas.tipos = () => of({ data: { tipos: [] } });
      const comp = criarComConfigurar();

      expect(comp.modosDisponiveis()).toEqual([]);
      expect(comp.modo()).toBe('');
      expect(comp.configCarregado()).toBe(true);   // as marcações vieram: o modal está íntegro
    });

    it('escopo sem tipo no catálogo: trocar para um funcionário zera chips e modo', () => {
      respostas.tipos = () => of({ data: { tipos: TIPOS.filter(t => t.escopo === 'GLOBAL') } });
      const comp = criarComConfigurar();
      expect(comp.modo()).toBe('tp-feriado');

      comp.onEscopo(eventoSelect('op-1'));

      expect(comp.modosDisponiveis()).toEqual([]);
      expect(comp.modo()).toBe('');
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
  // Ocorrências — clique no dia (toggle) e estado do calendário
  // ═══════════════════════════════════════════════════════════════════
  describe('Ocorrências — marcação do dia', () => {
    it('clicar num dia livre aplica o tipo ativo', () => {
      const comp = criarComConfigurar();
      const antes = comp.marcacoesEscopo();

      comp.onDiaConfig(new Date(2026, 6, 13));

      expect(comp.marcacoesEscopo().get(13)).toBe('tp-feriado');
      expect(comp.marcacoesEscopo()).not.toBe(antes); // Map novo (reatividade)
    });

    it('clicar de novo no MESMO tipo remove a marcação (toggle)', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13));
      comp.onDiaConfig(new Date(2026, 6, 13));
      expect(comp.marcacoesEscopo().has(13)).toBe(false);
    });

    it('com outro tipo ativo, o clique SUBSTITUI o tipo do dia', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13)); // Feriado
      comp.modo.set('tp-facultativo');
      comp.onDiaConfig(new Date(2026, 6, 13));
      expect(comp.marcacoesEscopo().get(13)).toBe('tp-facultativo');
    });

    it('modo "Limpar" remove a marcação existente (e não faz nada num dia livre)', () => {
      const comp = criarComConfigurar();
      comp.modo.set('__limpar');

      comp.onDiaConfig(new Date(2026, 6, 9)); // dia que veio marcado do backend
      expect(comp.marcacoesEscopo().has(9)).toBe(false);

      comp.onDiaConfig(new Date(2026, 6, 13)); // dia livre → segue livre
      expect(comp.marcacoesEscopo().has(13)).toBe(false);
    });

    it('sem nenhum tipo cadastrado, o clique no dia não marca nada', () => {
      respostas.tipos = () => of({ data: { tipos: [] } });
      const comp = criarComConfigurar();
      const antes = comp.marcacoesEscopo();

      comp.onDiaConfig(new Date(2026, 6, 13));

      expect(comp.marcacoesEscopo()).toBe(antes);   // nem o Map é recriado
      expect(comp.marcacoesEscopo().has(13)).toBe(false);
      expect(comp.estadoDiaConfig(new Date(2026, 6, 13))).toBeNull();
    });

    it('estadoDiaConfig: dia útil marcado vem selecionado, com o badge e o nome do catálogo', () => {
      const comp = criarComConfigurar();
      expect(comp.estadoDiaConfig(new Date(2026, 6, 9))).toEqual({
        selecionado: true, badge: 'Fer', rotulo: 'Feriado',
      });
    });

    it('estadoDiaConfig: tipo fora do catálogo usa a descrição que veio na própria marcação', () => {
      // Um dia marcado nunca aparece sem rótulo: a marcação já chega descrita (nome + badge),
      // então nem um catálogo desatualizado deixa o calendário mudo.
      respostas.tipos = () => of({ data: { tipos: TIPOS.filter(t => t.id !== 'tp-feriado') } });
      const comp = criarComConfigurar();

      expect(comp.estadoDiaConfig(new Date(2026, 6, 9))).toEqual({
        selecionado: true, badge: 'Fer', rotulo: 'Feriado',
      });
    });

    it('estadoDiaConfig: o catálogo tem precedência sobre a descrição embutida na marcação', () => {
      respostas.marcacoes = () => of({
        data: {
          globais: [{ data: '2026-07-09', tipo_id: 'tp-feriado', nome: 'Nome antigo', badge: 'Ant' }],
          pessoais: [],
        },
      });
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
  // Ocorrências — Aplicar: envia o DIFF (aplicar + remover) do escopo
  // ═══════════════════════════════════════════════════════════════════
  describe('Ocorrências — aplicar', () => {
    it('sem mudanças: fecha o modal sem chamar a API', () => {
      const comp = criarComConfigurar();
      comp.aplicarConfig();

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.configurarAberto()).toBe(false);
    });

    it('globais: envia os dias novos em "aplicar" (com tipo_id) e os retirados em "remover"', () => {
      const comp = criarComConfigurar();
      comp.onDiaConfig(new Date(2026, 6, 13)); // novo Feriado
      comp.modo.set('__limpar');
      comp.onDiaConfig(new Date(2026, 6, 9));  // remove o feriado que veio do backend

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: {
          aplicar: [{ data: '2026-07-13', tipo_id: 'tp-feriado' }],
          remover: ['2026-07-09'],
        },
      });
    });

    it('mudar só o TIPO do dia entra em "aplicar" (não vira remover+aplicar)', () => {
      const comp = criarComConfigurar();
      comp.modo.set('tp-facultativo');
      comp.onDiaConfig(new Date(2026, 6, 9)); // era Feriado

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: { aplicar: [{ data: '2026-07-09', tipo_id: 'tp-facultativo' }], remover: [] },
      });
    });

    it('pessoais: envia pessoa_id + pessoa_tipo derivado da categoria da grade', () => {
      const comp = criarComConfigurar();
      comp.onCategoria(eventoSelect('tecnicos'));
      comp.onEscopo(eventoSelect('op-1'));
      comp.onDiaConfig(new Date(2026, 6, 22)); // À Disposição (1º tipo individual do catálogo)

      comp.aplicarConfig();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        pessoais: {
          pessoa_id: 'op-1',
          pessoa_tipo: 'TECNICO',
          aplicar: [{ data: '2026-07-22', tipo_id: 'tp-disposicao' }],
          remover: [],
        },
      });
    });

    it('as datas do diff usam o mês/ano selecionado, com zero à esquerda', () => {
      const comp = criarCarregado('2026-03-10T12:00:00-03:00');
      respostas.marcacoes = () => of({ data: { globais: [], pessoais: [] } });
      comp.abrirConfigurar();
      comp.onDiaConfig(new Date(2026, 2, 5));

      comp.aplicarConfig();

      expect(apiPut.mock.calls[0][1].globais.aplicar).toEqual([{ data: '2026-03-05', tipo_id: 'tp-feriado' }]);
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
  // Configurar — o catálogo de tipos alterado repercute na grade
  // ═══════════════════════════════════════════════════════════════════
  describe('Configurar — catálogo de tipos', () => {
    it('abrir o Configurar não mexe no modal de Ocorrências nem carrega marcações', () => {
      const comp = criarCarregado();
      comp.abrirConfigurarTipos();

      expect(comp.configurarTiposAberto()).toBe(true);
      expect(comp.configurarAberto()).toBe(false);
      expect(chamadasDe('marcacoes')).toBe(0);
    });

    it('o catálogo alterado recarrega a GRADE (excluir um tipo apaga as marcações feitas com ele)', () => {
      const comp = criarCarregado();
      const chamadasAntes = chamadasDe('grade');

      comp.onTiposAlterados();

      expect(chamadasDe('grade')).toBe(chamadasAntes + 1);
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 7,
      });
    });

    it('a recarga do catálogo alterado respeita a categoria e o mês exibidos', () => {
      const comp = criarCarregado();
      comp.onCategoria(eventoSelect('administradores'));
      comp.onMesAno({ ano: 2025, mes: 11 });

      comp.onTiposAlterados();

      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'administradores', ano: 2025, mes: 11,
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O modal de Ocorrências não reabre com o estado do mês/escopo anterior
  // ═══════════════════════════════════════════════════════════════════
  describe('reabertura do modal de Ocorrências sem estado velho', () => {
    /** Faz o GET das marcações falhar (catálogo e grade continuam respondendo normalmente). */
    function marcacoesFalham(msg = 'Erro no servidor') {
      respostas.marcacoes = falha(msg);
    }

    it('abrir em junho, fechar, ir para julho e reabrir com o GET FALHANDO: calendário vazio, erro visível, Aplicar travado', () => {
      // O defeito: só o ramo de SUCESSO repovoava o modal (via aplicarEscopo). No erro, nada era
      // limpo — o calendário de JULHO exibia as marcações de JUNHO, e o Aplicar monta o ISO com o mês
      // CORRENTE: um clique mandava `remover: ['2026-07-05']` por causa de um feriado que era 05/06.
      const comp = criarCarregado('2026-06-10T10:00:00-03:00');
      comp.abrirConfigurar();
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'tp-feriado']]);   // marcação de junho na tela
      comp.fecharConfigurar();

      comp.onMesAno({ ano: 2026, mes: 7 });   // o usuário vai para julho
      marcacoesFalham();
      comp.abrirConfigurar();

      expect(comp.marcacoesEscopo().size).toBe(0);      // nada de junho sobrevive
      expect(comp.erroConfig()).toBe('Erro no servidor');
      expect(comp.configCarregado()).toBe(false);       // Aplicar travado
      expect(comp.escopo()).toBe('todos');              // escopo e modo também voltam ao inicial
      expect(comp.modo()).toBe('');                     // sem marcações carregadas, nenhum tipo fica ativo
    });

    it('o escopo PESSOAL escolhido antes não sobrevive à reabertura', () => {
      const comp = criarComConfigurar();
      comp.onEscopo(eventoSelect('op-1'));
      expect(comp.escopo()).toBe('op-1');
      expect(comp.modo()).toBe('tp-disposicao');
      comp.fecharConfigurar();

      marcacoesFalham();
      comp.abrirConfigurar();

      expect(comp.escopo()).toBe('todos');
      expect(comp.modo()).toBe('');
      expect(comp.marcacoesEscopo().size).toBe(0);
    });

    it('com a carga falhada, o Aplicar NÃO age nem que seja chamado à força (nenhum PUT fantasma)', () => {
      marcacoesFalham();
      const comp = criarCarregado();
      comp.abrirConfigurar();
      comp.modo.set('tp-feriado');               // os chips do catálogo estão na tela: o usuário
      comp.onDiaConfig(new Date(2026, 6, 13));   // escolhe um tipo e até consegue clicar no dia

      comp.aplicarConfig();

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.configurarAberto()).toBe(true);   // o modal não some fingindo que aplicou
    });

    it('a resposta OBSOLETA de uma abertura anterior não repovoa o modal (token de recência)', () => {
      // abrir (GET lento) → fechar → trocar de mês → reabrir (GET rápido, OK) → a resposta VELHA chega.
      const lenta = new Subject<any>();
      respostas.marcacoes = () => lenta;
      const comp = criarCarregado('2026-06-10T10:00:00-03:00');
      comp.abrirConfigurar();          // 1ª carga (junho) — fica no ar
      comp.fecharConfigurar();

      comp.onMesAno({ ano: 2026, mes: 7 });
      respostas.marcacoes = () => of({ data: { globais: [], pessoais: [] } });
      comp.abrirConfigurar();          // 2ª carga (julho) — responde na hora: julho está VAZIO
      expect(comp.marcacoesEscopo().size).toBe(0);

      lenta.next({ data: structuredClone(MARCACOES) });   // a de junho chega atrasada

      expect(comp.marcacoesEscopo().size).toBe(0);        // e é ignorada
      expect(comp.configCarregado()).toBe(true);
    });

    it('o CATÁLOGO obsoleto de uma sessão morta não dispara o GET das marcações dela', () => {
      const tiposLentos = new Subject<any>();
      respostas.tipos = () => tiposLentos;
      const comp = criarCarregado();
      comp.abrirConfigurar();          // 1ª sessão: o catálogo fica no ar
      comp.fecharConfigurar();

      respostas.tipos = () => of({ data: { tipos: structuredClone(TIPOS) } });
      comp.abrirConfigurar();          // 2ª sessão: carrega inteira
      const marcacoesAte = chamadasDe('marcacoes');

      tiposLentos.next({ data: { tipos: [] } });   // o catálogo velho responde agora

      expect(chamadasDe('marcacoes')).toBe(marcacoesAte);   // sem 2º GET encadeado
      expect(comp.tipos()).toHaveLength(5);                 // e sem esvaziar os chips da sessão viva
    });

    it('reabrir com um PUT em voo: o modal NÃO volta preso em "Aplicando..." — e a resposta velha não o fecha', () => {
      // O reset esquecia `aplicandoConfig`, e o PUT não
      // tinha token. Aplicar (PUT lento) → Cancelar → Ocorrências de novo devolvia um modal INÚTIL (botão
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

      respostas.marcacoes = () => of({ data: structuredClone(MARCACOES) });
      comp.carregarMarcacoes();                      // é o que a caixa app-erro-carga dispara

      expect(comp.erroConfig()).toBe('');
      expect(comp.configCarregado()).toBe(true);
      expect([...comp.marcacoesEscopo()]).toEqual([[9, 'tp-feriado']]);
    });

    it('a carga bem-sucedida destrava o Aplicar; o erro do PUT NÃO o trava de novo (retry legítimo)', () => {
      const comp = criarComConfigurar();
      expect(comp.configCarregado()).toBe(true);

      comp.onDiaConfig(new Date(2026, 6, 13));
      apiPut.mockReturnValue(throwError(() => ({ error: { message: 'Dia inválido' } })));
      comp.aplicarConfig();

      expect(comp.erroConfig()).toBe('Dia inválido');
      expect(comp.configCarregado()).toBe(true);   // ← repetir o Aplicar é o retry

      apiPut.mockReturnValue(of({ ok: true }));
      comp.aplicarConfig();
      expect(apiPut).toHaveBeenCalledTimes(2);
      expect(comp.configurarAberto()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — o que o usuário VÊ: a grade que não some e o
  // Aplicar que não age sobre estado desconhecido
  // ═══════════════════════════════════════════════════════════════════
  describe('render — grade preservada no erro de download e gate do Aplicar', () => {
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

    it('com o download falhando, a TABELA continua na tela e não há caixa de erro', () => {
      const fixture = renderizar();
      expect(fixture.debugElement.query(By.css('table.grade'))).not.toBeNull();

      apiGetBlob.mockReturnValue(throwError(() => new Error('500')));
      fixture.componentInstance.baixarTabela();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('table.grade'))).not.toBeNull();   // a grade FICA
      expect(fixture.debugElement.query(By.css('.error-box'))).toBeNull();        // sem caixa no lugar dela
      expect(toastError).toHaveBeenCalledWith('Erro ao baixar a tabela.');
    });

    it('reabertura com o GET falhando → calendário sem marcações, erro no modal e Aplicar DESABILITADO', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;

      comp.abrirConfigurar();                 // 1ª abertura: OK (dia 9 marcado)
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(false);
      comp.fecharConfigurar();

      respostas.marcacoes = falha('Erro interno do servidor');
      comp.abrirConfigurar();                 // 2ª abertura: o GET falha
      fixture.detectChanges();

      expect(comp.marcacoesEscopo().size).toBe(0);
      expect(fixture.debugElement.query(By.css('.modal-card .error-box'))!.nativeElement.textContent)
        .toContain('Erro interno do servidor');
      expect(btnAplicar(fixture)!.disabled).toBe(true);   // não age sobre estado desconhecido
    });

    it('o modal com a carga falhada mostra a caixa COM RETRY, e o clique nela recarrega e libera o Aplicar', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      respostas.marcacoes = falha('Erro interno do servidor');

      comp.abrirConfigurar();
      fixture.detectChanges();

      const caixa = fixture.debugElement.query(By.css('.modal-card .erro-carga'));
      expect(caixa).not.toBeNull();                       // sem isto o modal seria um beco sem saída
      expect(btnAplicar(fixture)!.disabled).toBe(true);

      respostas.marcacoes = () => of({ data: structuredClone(MARCACOES) });
      (caixa.query(By.css('button')).nativeElement as HTMLButtonElement).click();   // "Tentar novamente"
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.modal-card .erro-carga'))).toBeNull();
      expect(btnAplicar(fixture)!.disabled).toBe(false);
    });

    it('o erro do CATÁLOGO também abre a caixa com retry (o modal nunca fica sem saída)', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      respostas.tipos = falha('Erro interno do servidor');

      comp.abrirConfigurar();
      fixture.detectChanges();

      const caixa = fixture.debugElement.query(By.css('.modal-card .erro-carga'));
      expect(caixa).not.toBeNull();
      expect(btnAplicar(fixture)!.disabled).toBe(true);

      respostas.tipos = () => of({ data: { tipos: structuredClone(TIPOS) } });
      (caixa.query(By.css('button')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.queryAll(By.css('.modal-card .chip')).length).toBeGreaterThan(0);
      expect(btnAplicar(fixture)!.disabled).toBe(false);
    });

    it('enquanto a carga do modal está EM VOO, o Aplicar não está disponível (o modal ainda diz "Carregando marcações...")', () => {
      const fixture = renderizar();
      const emVoo = new Subject<any>();
      respostas.marcacoes = () => emVoo;

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
  // RENDER — os chips do modal saem do catálogo, e sem catálogo não há calendário
  // ═══════════════════════════════════════════════════════════════════
  describe('render — chips do catálogo e modal sem tipos', () => {
    function renderizarAberto(): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();
      fixture.componentInstance.abrirConfigurar();
      fixture.detectChanges();
      return fixture;
    }

    const chips = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.queryAll(By.css('.modal-card .modos .chip'))
        .map(b => (b.nativeElement as HTMLElement).textContent?.trim());

    it('os chips são os tipos do catálogo (nomes do backend) mais o "Limpar"', () => {
      const fixture = renderizarAberto();
      expect(chips(fixture)).toEqual(['Feriado', 'Ponto Facultativo', 'Limpar']);
    });

    it('trocar para um funcionário troca os chips pelos tipos individuais', () => {
      const fixture = renderizarAberto();
      const sel = fixture.debugElement.query(By.css('.modal-card select[aria-label="Funcionário"]'))
        .nativeElement as HTMLSelectElement;
      sel.value = 'op-1';
      sel.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(chips(fixture)).toEqual(['À Disposição', 'Atestado', 'Férias', 'Limpar']);
    });

    it('clicar num chip ativa aquele tipo (e o dia clicado no calendário recebe o badge dele)', () => {
      const fixture = renderizarAberto();
      const comp = fixture.componentInstance;
      const chipFacultativo = fixture.debugElement.queryAll(By.css('.modal-card .modos .chip'))
        .find(b => (b.nativeElement as HTMLElement).textContent?.trim() === 'Ponto Facultativo')!;

      (chipFacultativo.nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(comp.modo()).toBe('tp-facultativo');
      expect((chipFacultativo.nativeElement as HTMLElement).classList.contains('active')).toBe(true);

      comp.onDiaConfig(new Date(2026, 6, 13));
      expect(comp.estadoDiaConfig(new Date(2026, 6, 13))).toEqual({
        selecionado: true, badge: 'PF', rotulo: 'Ponto Facultativo',
      });
    });

    it('catálogo vazio: o modal diz "Nenhum tipo cadastrado" e não renderiza chips nem calendário', () => {
      respostas.tipos = () => of({ data: { tipos: [] } });
      const fixture = renderizarAberto();

      expect((fixture.debugElement.query(By.css('.modal-card .empty-state'))!.nativeElement as HTMLElement)
        .textContent?.trim()).toBe('Nenhum tipo cadastrado');
      expect(fixture.debugElement.query(By.css('.modal-card .modos'))).toBeNull();
      expect(fixture.debugElement.query(By.css('.modal-card app-mini-calendario'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — os textos da barra e do modal de Ocorrências
  //
  // O rebatismo Configurar→Ocorrências vive SÓ no template (os identificadores
  // abrirConfigurar/configurarAberto não mudaram): sem render, os rótulos antigos —
  // inclusive a label solta "Funcionário" e o parágrafo de instrução, removidos de
  // propósito — poderiam voltar com a suíte verde. E o "Configurar" de hoje é OUTRO
  // botão: o do catálogo de tipos, exclusivo do master.
  // ═══════════════════════════════════════════════════════════════════
  describe('render — textos do modal de Ocorrências', () => {
    function renderizar(): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();   // ngOnInit + render (as respostas do mock são síncronas)
      return fixture;
    }

    /** Renderiza com o modal de Ocorrências já aberto (catálogo e marcações carregados na hora). */
    function renderizarAberto(): ComponentFixture<GradeRetificacoesComponent> {
      const fixture = renderizar();
      fixture.componentInstance.abrirConfigurar();
      fixture.detectChanges();
      return fixture;
    }

    const botoesDaBarra = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.queryAll(By.css('.barra button'))
        .map(b => (b.nativeElement as HTMLButtonElement).textContent?.trim());

    const botaoDaBarra = (f: ComponentFixture<GradeRetificacoesComponent>, texto: string) =>
      f.debugElement.queryAll(By.css('.barra button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.trim() === texto)
        ?.nativeElement as HTMLButtonElement | undefined;

    it('a barra separa "Ocorrências" (marcações do mês) de "Configurar" (catálogo de tipos)', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      expect(botoesDaBarra(fixture)).toContain('Ocorrências');

      botaoDaBarra(fixture, 'Ocorrências')!.click();
      expect(comp.configurarAberto()).toBe(true);
      expect(comp.configurarTiposAberto()).toBe(false);
      comp.fecharConfigurar();

      botaoDaBarra(fixture, 'Configurar')!.click();
      expect(comp.configurarTiposAberto()).toBe(true);
      expect(comp.configurarAberto()).toBe(false);
    });

    it('o título do modal é "Ocorrências — <mês selecionado>"', () => {
      const fixture = renderizarAberto();
      expect((fixture.debugElement.query(By.css('.modal-title')).nativeElement as HTMLElement)
        .textContent?.trim()).toBe('Ocorrências — Julho de 2026');
    });

    it('o escopo global se chama "Todos os Funcionários" e o select carrega aria-label "Funcionário"', () => {
      const fixture = renderizarAberto();
      const sel = fixture.debugElement.query(By.css('.modal-card select[aria-label="Funcionário"]'));
      expect(sel).not.toBeNull();

      const opcaoTodos = (sel.nativeElement as HTMLSelectElement).options[0];
      expect(opcaoTodos.value).toBe('todos');
      expect(opcaoTodos.textContent?.trim()).toBe('Todos os Funcionários');
    });

    it('a label visível "Funcionário" e o parágrafo de instrução do calendário saíram do modal', () => {
      const fixture = renderizarAberto();
      // a orientação do select vive só no aria-label — nenhuma <label> sobrou no modal
      expect(fixture.debugElement.queryAll(By.css('.modal-card label'))).toHaveLength(0);
      expect(fixture.debugElement.query(By.css('.modal-card .legenda'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — o "Configurar" (catálogo) é do master, e o que ele altera volta à grade
  // ═══════════════════════════════════════════════════════════════════
  describe('render — botão Configurar e o catálogo alterado', () => {
    function renderizar(): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();
      return fixture;
    }

    const botoesDaBarra = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.queryAll(By.css('.barra button'))
        .map(b => (b.nativeElement as HTMLButtonElement).textContent?.trim());

    it('o master vê o botão "Configurar" na barra', () => {
      expect(botoesDaBarra(renderizar())).toContain('Configurar');
    });

    it('o admin comum NÃO vê o botão "Configurar" (esconder não é a segurança — o backend recusa)', async () => {
      await configurar(false);
      const fixture = renderizar();

      expect(botoesDaBarra(fixture)).not.toContain('Configurar');
      expect(botoesDaBarra(fixture)).toContain('Ocorrências');   // as marcações seguem acessíveis
      expect(fixture.debugElement.query(By.directive(ConfigurarTiposMarcacaoComponent))).toBeNull();
    });

    it('o modal do catálogo abre sem amarrar-se ao mês exibido e some ao emitir "fechar"', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;

      comp.abrirConfigurarTipos();
      fixture.detectChanges();

      const filho = fixture.debugElement.query(By.directive(ConfigurarTiposMarcacaoComponent));
      expect(filho).not.toBeNull();
      // O catálogo vale para todos os meses: o título do modal não cita o mês da grade.
      const titulo = filho.query(By.css('.modal-title'));
      expect((titulo.nativeElement as HTMLElement).textContent?.trim()).toBe('Configurar ocorrências');

      (filho.componentInstance as ConfigurarTiposMarcacaoComponent).fechar.emit();
      fixture.detectChanges();

      expect(comp.configurarTiposAberto()).toBe(false);
      expect(fixture.debugElement.query(By.directive(ConfigurarTiposMarcacaoComponent))).toBeNull();
    });

    it('o catálogo alterado recarrega a grade exibida (a exclusão de um tipo apaga marcações)', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      comp.abrirConfigurarTipos();
      fixture.detectChanges();

      const filho = fixture.debugElement.query(By.directive(ConfigurarTiposMarcacaoComponent))
        .componentInstance as ConfigurarTiposMarcacaoComponent;
      const chamadasAntes = chamadasDe('grade');

      filho.alterado.emit();
      fixture.detectChanges();

      expect(chamadasDe('grade')).toBe(chamadasAntes + 1);
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 7,
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — a grade alcança o mês vivo na virada do ano
  //
  // A correção está no TEMPLATE (`[anos]="anosSeletor"`): sem render, apagar esse binding deixaria
  // a suíte verde com o admin de novo sem acesso a dezembro em janeiro — bem no prazo de retificação.
  // ═══════════════════════════════════════════════════════════════════
  describe('render — o seletor da barra cruza a virada do ano', () => {
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

    it('em 05/01/2027 o ‹ está habilitado e o clique pede a grade de DEZEMBRO/2026', () => {
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
      expect(comp.tituloMesAno()).toBe('Dezembro de 2026');    // o modal Ocorrências segue o mês exibido
    });

    it('regressão: em julho o seletor da barra segue preso ao ano corrente (uma <option>, ‹ dentro do ano)', () => {
      const fixture = renderizar('2026-07-12T10:00:00-03:00');
      expect(seletor(fixture).anos()).toEqual([2026]);
      expect(fixture.debugElement.queryAll(By.css('app-mes-ano-selector select.sel-ano option'))).toHaveLength(1);
    });
  });
});

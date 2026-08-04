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
 * canal separado do erro da grade) e a marcação de ocorrências na PRÓPRIA grade.
 *
 * As ocorrências são marcadas clicando: o rótulo do dia abre os tipos GERAIS (valem para todos
 * os funcionários do mês, independentemente da paginação) e a célula de um funcionário abre os
 * INDIVIDUAIS. A escolha grava na hora, num lote de um item só; a ação do dia — aplicar ou
 * remover — passa antes por uma confirmação dentro do próprio popover, nunca por `confirm()`.
 * O estado de cada célula vem do payload da grade (`tipo_id`/`marcacao_global_id`): não há GET
 * de marcações, e o catálogo de tipos é buscado uma vez, junto da grade.
 *
 * TestBed sem `detectChanges()` por padrão — `ngOnInit` à mão, filhos não instanciados;
 * `ApiService` e `ToastService` mockados via `useValue`; `AuthService` é um stub com o signal
 * `isMaster` (o botão "Configurar", do catálogo, é só do master). Relógio congelado
 * (`{toFake:['Date']}`) ANTES de `createComponent`: `hoje`/`anoMes` são lidos no field
 * initializer. A mecânica interna do seletor de mês/ano vive no spec dedicado
 * `mes-ano-selector.component.spec.ts` — aqui só o que a grade exibe através dele.
 */

/**
 * Payload representativo de `GET /api/admin/ponto/retificacoes/grade` (julho/2026), com os
 * quatro casos que a tela precisa distinguir: dia útil livre, fim de semana, dia inteiro sob
 * uma ocorrência geral e célula com ocorrência individual.
 */
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
      { dia: 1, data: '2026-07-01', dow: 3, fim_semana: false, marcacao_global: null, marcacao_global_id: null },
      { dia: 4, data: '2026-07-04', dow: 6, fim_semana: true, marcacao_global: null, marcacao_global_id: null },
      { dia: 6, data: '2026-07-06', dow: 1, fim_semana: false, marcacao_global: 'Feriado', marcacao_global_id: 'tp-feriado' },
      { dia: 7, data: '2026-07-07', dow: 2, fim_semana: false, marcacao_global: null, marcacao_global_id: null },
    ],
    celulas: {
      'op-1': {
        1: { tipo: 'horarios', texto: '08:00 12:00 13:00 17:00', tem_obs: true, obs: 'esqueci de bater' },
        6: { tipo: 'marcacao_global', texto: 'Feriado', tem_obs: false, tipo_id: 'tp-feriado' },
        7: { tipo: 'marcacao_pessoa', texto: 'Atestado', tem_obs: false, tipo_id: 'tp-atestado' },
      },
      'op-2': {
        1: { tipo: 'banco', texto: 'Folga', tem_obs: false },
        6: { tipo: 'marcacao_global', texto: 'Feriado', tem_obs: false, tipo_id: 'tp-feriado' },
      },
    },
  };
}

/** Endpoints do componente, em canais independentes de resposta. */
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
   * Catálogo de `GET /api/admin/ponto/tipos-marcacao`. A ORDEM importa: é a ordem em que os
   * tipos aparecem na lista do popover, logo abaixo do "Nenhuma".
   */
  const TIPOS: TipoMarcacao[] = [
    { id: 'tp-feriado', nome: 'Feriado', badge: 'Fer', escopo: 'GLOBAL' },
    { id: 'tp-facultativo', nome: 'Ponto Facultativo', badge: 'PF', escopo: 'GLOBAL' },
    { id: 'tp-disposicao', nome: 'À Disposição', badge: 'Disp', escopo: 'INDIVIDUAL' },
    { id: 'tp-atestado', nome: 'Atestado', badge: 'Atest', escopo: 'INDIVIDUAL' },
    { id: 'tp-ferias', nome: 'Férias', badge: 'Fér', escopo: 'INDIVIDUAL' },
  ];

  /** Resposta de erro com corpo do backend. */
  const falha = (msg: string) => () => throwError(() => ({ error: { message: msg } }));

  /** Quantas vezes cada endpoint foi pedido. */
  const chamadasDe = (rota: Rota) => apiGet.mock.calls.filter(c => rotaDe(c[0]) === rota).length;

  /** Monta o TestBed com o papel desejado — só o master enxerga o botão "Configurar". */
  async function configurar(master = true): Promise<void> {
    TestBed.resetTestingModule();
    respostas = {
      tipos: () => of({ data: { tipos: structuredClone(TIPOS) } }),
      marcacoes: () => of({ data: { globais: [], pessoais: [] } }),
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
        // Erro de AÇÃO (download, gravação de ocorrência) sai pelo toast — canal separado do erro da grade
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

  /** `Event` de <select> com `target.value` (a barra lê o valor escolhido). */
  function eventoSelect(value: string): Event {
    const sel = document.createElement('select');
    for (const v of ['operadores', 'tecnicos', 'administradores', value]) {
      const opt = document.createElement('option');
      opt.value = v;
      sel.appendChild(opt);
    }
    sel.value = value;
    return { target: sel } as unknown as Event;
  }

  /** Clique com a âncora do popover (o componente lê o retângulo do elemento clicado). */
  function clique(): MouseEvent {
    return {
      stopPropagation: vi.fn(),
      currentTarget: { getBoundingClientRect: () => ({ left: 120, bottom: 260 }) },
    } as unknown as MouseEvent;
  }

  /** O dia do payload, pelo número. */
  const dia = (comp: GradeRetificacoesComponent, n: number) => comp.dias().find(d => d.dia === n)!;
  const funcionario = (comp: GradeRetificacoesComponent, id: string) =>
    comp.funcionarios().find(f => f.id === id)!;
  /** Rótulos das opções do popover aberto. */
  const rotulos = (comp: GradeRetificacoesComponent) => comp.opcoes().map(o => o.rotulo);
  /** Abre o popover na célula (funcionário, dia) e devolve o componente. */
  function abrirCelula(comp: GradeRetificacoesComponent, pessoaId: string, n: number): void {
    comp.abrirNaCelula(funcionario(comp, pessoaId), dia(comp, n), clique());
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
      expect(comp.dias().map(d => d.dia)).toEqual([1, 4, 6, 7]);
      expect(comp.carregando()).toBe(false);
      expect(comp.erro()).toBe('');
    });

    it('o catálogo de tipos vem JUNTO com a grade, uma vez — e não há GET de marcações', () => {
      // O estado de cada célula já vem no payload da grade (tipo_id/marcacao_global_id): abrir o
      // popover não pode custar rede, senão cada clique numa célula viraria uma ida ao servidor.
      const comp = criarCarregado();

      expect(chamadasDe('tipos')).toBe(1);
      expect(chamadasDe('marcacoes')).toBe(0);
      expect(comp.tipos()).toHaveLength(5);

      comp.abrirNoDia(dia(comp, 1), clique());
      expect(chamadasDe('tipos')).toBe(1);       // o popover abre com o que já está em memória
      expect(chamadasDe('marcacoes')).toBe(0);
    });

    it('trocar de mês recarrega a grade sem repuxar o catálogo (ele não muda com o mês)', () => {
      const comp = criarCarregado();
      comp.onMesAno({ ano: 2026, mes: 6 });

      expect(chamadasDe('grade')).toBe(2);
      expect(chamadasDe('tipos')).toBe(1);
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

    it('o catálogo indisponível não derruba a grade: ela carrega e o erro fica no popover', () => {
      respostas.tipos = falha('500');
      const comp = criarCarregado();

      expect(comp.funcionarios()).toHaveLength(3);   // a grade não depende do catálogo
      expect(comp.erro()).toBe('');
      expect(comp.catalogoErro()).toBe(true);
      expect(comp.tipos()).toEqual([]);
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
  // Ocorrências — onde o popover abre e onde o clique é recusado
  // ═══════════════════════════════════════════════════════════════════
  describe('ocorrências — abertura e bloqueios de clique', () => {
    it('o rótulo do dia abre a lista dos tipos GERAIS, com "Nenhuma" na frente', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 1), clique());

      expect(comp.alvo()).toMatchObject({ escopo: 'dia', dia: 1, data: '2026-07-01', atual: null });
      expect(rotulos(comp)).toEqual(['Nenhuma', 'Feriado', 'Ponto Facultativo']);
    });

    it('o dia que já tem geral abre com o tipo dele selecionado', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 6), clique());

      expect(comp.alvo()?.atual).toBe('tp-feriado');
    });

    it('a célula do funcionário abre a lista dos tipos INDIVIDUAIS', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-3', 1);   // célula vazia

      expect(comp.alvo()).toMatchObject({ escopo: 'pessoa', pessoaId: 'op-3', dia: 1, atual: null });
      expect(rotulos(comp)).toEqual(['Nenhuma', 'À Disposição', 'Atestado', 'Férias']);
    });

    it('a célula com ocorrência individual abre com o tipo dela selecionado', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-1', 7);

      expect(comp.alvo()?.atual).toBe('tp-atestado');
    });

    it('fim de semana não abre nada — nem no rótulo do dia, nem na célula', () => {
      const comp = criarCarregado();

      comp.abrirNoDia(dia(comp, 4), clique());
      expect(comp.alvo()).toBeNull();

      abrirCelula(comp, 'op-1', 4);
      expect(comp.alvo()).toBeNull();
    });

    it('célula com horários de retificação ou com folga aprovada não abre: a marcação ficaria invisível', () => {
      const comp = criarCarregado();

      abrirCelula(comp, 'op-1', 1);   // horários
      expect(comp.alvo()).toBeNull();

      abrirCelula(comp, 'op-2', 1);   // "Banco de horas"
      expect(comp.alvo()).toBeNull();
    });

    it('no dia sob uma ocorrência geral, as células individuais ficam bloqueadas — mas o rótulo do dia não', () => {
      const comp = criarCarregado();

      abrirCelula(comp, 'op-1', 6);   // tem célula (a geral)
      expect(comp.alvo()).toBeNull();
      abrirCelula(comp, 'op-3', 6);   // sem célula na página, mesmo assim sob a geral
      expect(comp.alvo()).toBeNull();

      comp.abrirNoDia(dia(comp, 6), clique());
      expect(comp.alvo()?.escopo).toBe('dia');   // a geral se troca/remove pelo próprio rótulo
    });

    it('os bloqueios são consultáveis pelo template (cursor e aria-disabled saem daqui)', () => {
      const comp = criarCarregado();

      expect(comp.diaClicavel(dia(comp, 1))).toBe(true);
      expect(comp.diaClicavel(dia(comp, 4))).toBe(false);
      expect(comp.celulaClicavel('op-3', dia(comp, 1))).toBe(true);
      expect(comp.celulaClicavel('op-1', dia(comp, 1))).toBe(false);
      expect(comp.celulaClicavel('op-2', dia(comp, 1))).toBe(false);
      expect(comp.celulaClicavel('op-1', dia(comp, 6))).toBe(false);
      expect(comp.celulaClicavel('op-1', dia(comp, 4))).toBe(false);
    });

    it('o clique que ABRE não borbulha (senão o handler de clique-fora fecharia na mesma hora)', () => {
      const comp = criarCarregado();
      const ev = clique();
      comp.abrirNoDia(dia(comp, 1), ev);

      expect(ev.stopPropagation).toHaveBeenCalled();
    });

    it('clique fora e Esc fecham o popover', () => {
      const comp = criarCarregado();

      comp.abrirNoDia(dia(comp, 1), clique());
      comp.onCliqueFora();
      expect(comp.alvo()).toBeNull();

      comp.abrirNoDia(dia(comp, 1), clique());
      comp.onEscape();
      expect(comp.alvo()).toBeNull();
    });

    it('sem tipo cadastrado no escopo, não há opção nenhuma (nem o "Nenhuma" sozinho)', () => {
      respostas.tipos = () => of({ data: { tipos: [{ id: 'tp-feriado', nome: 'Feriado', badge: 'F', escopo: 'GLOBAL' }] } });
      const comp = criarCarregado();

      abrirCelula(comp, 'op-3', 1);            // escopo INDIVIDUAL, catálogo só com GLOBAL
      expect(comp.opcoes()).toEqual([]);

      comp.fecharPopover();
      comp.abrirNoDia(dia(comp, 1), clique()); // escopo GLOBAL: tem tipo
      expect(rotulos(comp)).toEqual(['Nenhuma', 'Feriado']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Ocorrências — a escolha grava na hora (lote de um item só)
  // ═══════════════════════════════════════════════════════════════════
  describe('ocorrências — gravação individual', () => {
    it('escolher um tipo na célula aplica só para aquele (funcionário, dia), sem confirmação', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[2]);   // "Atestado"

      expect(comp.confirmacao()).toBeNull();
      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        pessoais: {
          pessoa_id: 'op-3', pessoa_tipo: 'OPERADOR',
          aplicar: [{ data: '2026-07-01', tipo_id: 'tp-atestado' }],
        },
      });
    });

    it('"Nenhuma" numa célula preenchida REMOVE a ocorrência daquele dia', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-1', 7);      // tem "Atestado"

      comp.escolher(comp.opcoes()[0]);   // "Nenhuma"

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        pessoais: { pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', remover: ['2026-07-07'] },
      });
    });

    it('trocar de tipo é um upsert só (não vira remover + aplicar)', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-1', 7);      // "Atestado"

      comp.escolher(comp.opcoes()[3]);   // "Férias"

      expect(apiPut).toHaveBeenCalledTimes(1);
      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        pessoais: {
          pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR',
          aplicar: [{ data: '2026-07-07', tipo_id: 'tp-ferias' }],
        },
      });
    });

    it('escolher a opção que já está marcada não chama a API: só fecha', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-1', 7);

      comp.escolher(comp.opcoes()[2]);   // "Atestado", o mesmo que já está lá

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.alvo()).toBeNull();
    });

    it('"Nenhuma" numa célula VAZIA também é no-op (nada a remover)', () => {
      const comp = criarCarregado();
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[0]);

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.alvo()).toBeNull();
    });

    it('o pessoa_tipo acompanha a categoria exibida na barra', () => {
      const comp = criarCarregado();
      comp.onCategoria(eventoSelect('tecnicos'));
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[1]);

      expect(apiPut.mock.calls[0][1].pessoais.pessoa_tipo).toBe('TECNICO');
    });

    it('sucesso: fecha o popover e recarrega a grade (a precedência é recalculada no servidor)', () => {
      const comp = criarCarregado();
      const antes = chamadasDe('grade');
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[1]);

      expect(comp.alvo()).toBeNull();
      expect(comp.salvandoEm()).toBeNull();
      expect(chamadasDe('grade')).toBe(antes + 1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Ocorrências — a ação do dia é coletiva e passa por confirmação
  // ═══════════════════════════════════════════════════════════════════
  describe('ocorrências — ação geral com mini-confirmação', () => {
    it('escolher um tipo no dia PERGUNTA antes, sem chamar a API', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 1), clique());

      comp.escolher(comp.opcoes()[1]);   // "Feriado"

      expect(comp.confirmacao()?.pergunta).toBe('Aplicar "Feriado" para todos os funcionários?');
      expect(apiPut).not.toHaveBeenCalled();
    });

    it('confirmar aplica o tipo na DATA — vale para todos, inclusive quem está em outra página', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);

      comp.confirmar();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: { aplicar: [{ data: '2026-07-01', tipo_id: 'tp-feriado' }] },
      });
      expect(comp.alvo()).toBeNull();
    });

    it('remover a geral também é coletivo: pergunta nomeando a ocorrência e manda "remover"', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 6), clique());   // dia com "Feriado"

      comp.escolher(comp.opcoes()[0]);           // "Nenhuma"
      expect(comp.confirmacao()?.pergunta).toBe('Remover "Feriado" de todos os funcionários?');
      expect(apiPut).not.toHaveBeenCalled();

      comp.confirmar();
      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: { remover: ['2026-07-06'] },
      });
    });

    it('cancelar a confirmação não grava nada e devolve a lista de opções', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);

      comp.cancelarConfirmacao();

      expect(comp.confirmacao()).toBeNull();
      expect(comp.alvo()).not.toBeNull();   // o popover continua aberto, na lista
      expect(apiPut).not.toHaveBeenCalled();
    });

    it('a confirmação é do próprio popover: `confirm()` do navegador nunca é usado', () => {
      // O confirm() nativo já mordeu este projeto (ele bloqueia a thread e some em alguns fluxos).
      const nativo = vi.spyOn(window, 'confirm').mockReturnValue(true);
      const comp = criarCarregado();

      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);
      comp.confirmar();

      expect(nativo).not.toHaveBeenCalled();
    });

    it('reabrir o popover começa sem confirmação pendente da vez anterior', () => {
      const comp = criarCarregado();
      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);
      comp.fecharPopover();

      comp.abrirNoDia(dia(comp, 7), clique());

      expect(comp.confirmacao()).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Ocorrências — erro, gravação em voo e resposta obsoleta
  // ═══════════════════════════════════════════════════════════════════
  describe('ocorrências — robustez da gravação', () => {
    it('erro vai para o TOAST com a mensagem do servidor: a grade fica na tela', () => {
      const comp = criarCarregado();
      const antes = chamadasDe('grade');
      apiPut.mockReturnValue(throwError(() => ({
        error: { message: 'O dia 01/07/2026 tem uma ocorrência geral, que vale para todos os funcionários. Recarregue a tela e tente novamente.' },
      })));
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[1]);

      expect(toastError).toHaveBeenCalledWith(
        'O dia 01/07/2026 tem uma ocorrência geral, que vale para todos os funcionários. Recarregue a tela e tente novamente.');
      expect(comp.erro()).toBe('');                  // o canal da GRADE não é o da gravação
      expect(comp.funcionarios()).toHaveLength(3);
      expect(chamadasDe('grade')).toBe(antes);       // nada mudou no servidor: não recarrega
      expect(comp.salvandoEm()).toBeNull();          // e a célula destrava
    });

    it('erro sem corpo: fallback', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(throwError(() => new Error('rede')));
      abrirCelula(comp, 'op-3', 1);

      comp.escolher(comp.opcoes()[1]);

      expect(toastError).toHaveBeenCalledWith('Erro ao salvar a ocorrência.');
    });

    it('com uma gravação em voo, a célula fica marcada e a próxima escolha não dispara outro PUT', () => {
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      abrirCelula(comp, 'op-3', 1);
      const opcoes = comp.opcoes();

      comp.escolher(opcoes[1]);
      expect(comp.salvandoEm()).toBe('op-3|1');

      // Duplo clique impaciente: a segunda escolha cai no vazio enquanto a primeira não volta
      comp.escolher(opcoes[2]);
      expect(apiPut).toHaveBeenCalledTimes(1);

      // E o popover nem reabre em outra célula enquanto a gravação está em voo
      comp.fecharPopover();
      abrirCelula(comp, 'op-3', 7);
      expect(comp.alvo()).toBeNull();

      emVoo.next({ ok: true });
      expect(comp.salvandoEm()).toBeNull();
    });

    it('a confirmação coletiva também não grava duas vezes com o PUT em voo', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);

      comp.confirmar();
      comp.confirmar();

      expect(apiPut).toHaveBeenCalledTimes(1);
    });

    it('marcar uma ocorrência não tira o admin da página em que ele está', () => {
      // A recarga da gravação traz a MESMA lista de funcionários; só a troca de categoria/mês
      // muda a lista e justifica voltar para a primeira página.
      respostas.grade = () => of({ data: { ...payloadGrade(9), celulas: {} } });
      const comp = criarCarregado();
      comp.paginaSeguinte();
      expect(comp.pagina()).toBe(1);

      comp.abrirNaCelula(funcionario(comp, 'op-9'), dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);

      expect(apiPut).toHaveBeenCalledTimes(1);
      expect(comp.pagina()).toBe(1);
    });

    it('a resposta ATRASADA de uma carga anterior não sobrescreve a grade mais nova', () => {
      // Duas cargas em voo deixaram de ser exceção: toda gravação bem-sucedida dispara uma.
      const lenta = new Subject<any>();
      respostas.grade = () => lenta;
      const comp = criarCarregado();

      respostas.grade = () => of({ data: payloadGrade(5) });
      comp.onMesAno({ ano: 2026, mes: 8 });          // a 2ª carga responde na hora
      expect(comp.funcionarios()).toHaveLength(5);

      lenta.next({ data: payloadGrade(3) });          // a 1ª chega atrasada
      expect(comp.funcionarios()).toHaveLength(5);    // e é descartada
    });

    it('o catálogo que falhou é tentado de novo na próxima carga da grade (não é beco sem saída)', () => {
      respostas.tipos = falha('500');
      const comp = criarCarregado();
      expect(comp.catalogoErro()).toBe(true);
      expect(chamadasDe('tipos')).toBe(1);

      respostas.tipos = () => of({ data: { tipos: structuredClone(TIPOS) } });
      comp.onMesAno({ ano: 2026, mes: 8 });

      expect(chamadasDe('tipos')).toBe(2);
      expect(comp.catalogoErro()).toBe(false);
      comp.abrirNoDia(dia(comp, 1), clique());
      expect(rotulos(comp)).toEqual(['Nenhuma', 'Feriado', 'Ponto Facultativo']);
    });

    it('com o catálogo saudável, trocar de mês não repete o GET de tipos', () => {
      const comp = criarCarregado();
      comp.onMesAno({ ano: 2026, mes: 8 });
      comp.onCategoria(eventoSelect('tecnicos'));

      expect(chamadasDe('tipos')).toBe(1);
    });

    it('a ação do dia marca o rótulo do dia enquanto grava', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      comp.abrirNoDia(dia(comp, 1), clique());
      comp.escolher(comp.opcoes()[1]);

      comp.confirmar();

      expect(comp.salvandoEm()).toBe('dia|1');
    });

    it('a resposta que chega DEPOIS de trocar de mês não recarrega nem avisa nada', () => {
      // O usuário clicou, mudou de mês e a resposta do PUT chegou atrasada: recarregar ali traria a
      // grade de outro contexto, e um toast falaria de uma tela que não está mais na frente dele.
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      abrirCelula(comp, 'op-3', 1);
      comp.escolher(comp.opcoes()[1]);

      comp.onMesAno({ ano: 2026, mes: 8 });
      const aposTroca = chamadasDe('grade');
      emVoo.next({ ok: true });

      expect(chamadasDe('grade')).toBe(aposTroca);   // a recarga da troca de mês já aconteceu
      expect(toastError).not.toHaveBeenCalled();
    });

    it('o ERRO de uma gravação obsoleta não pinta toast na grade nova', () => {
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      abrirCelula(comp, 'op-3', 1);
      comp.escolher(comp.opcoes()[1]);

      comp.onCategoria(eventoSelect('tecnicos'));
      emVoo.error({ error: { message: 'falhou' } });

      expect(toastError).not.toHaveBeenCalled();
    });

    it('trocar de contexto destrava a célula e fecha o popover (nada preso em "salvando")', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      abrirCelula(comp, 'op-3', 1);
      comp.escolher(comp.opcoes()[1]);

      comp.onMesAno({ ano: 2026, mes: 8 });

      expect(comp.salvandoEm()).toBeNull();
      expect(comp.alvo()).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Configurar — catálogo de tipos (modal do master)
  // ═══════════════════════════════════════════════════════════════════
  describe('Configurar — catálogo de tipos', () => {
    it('abrir o Configurar não dispara carga nenhuma', () => {
      const comp = criarCarregado();
      const antes = { grade: chamadasDe('grade'), tipos: chamadasDe('tipos') };

      comp.abrirConfigurarTipos();

      expect(comp.configurarTiposAberto()).toBe(true);
      expect(chamadasDe('grade')).toBe(antes.grade);
      expect(chamadasDe('tipos')).toBe(antes.tipos);
    });

    it('o catálogo alterado recarrega a GRADE e a lista de tipos', () => {
      // Excluir um tipo apaga as marcações feitas com ele (some da grade) e tira a opção do popover.
      const comp = criarCarregado();
      const antes = { grade: chamadasDe('grade'), tipos: chamadasDe('tipos') };

      comp.onTiposAlterados();

      expect(chamadasDe('grade')).toBe(antes.grade + 1);
      expect(chamadasDe('tipos')).toBe(antes.tipos + 1);
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 7,
      });
    });

    it('a recarga do catálogo alterado respeita a categoria e o mês exibidos', () => {
      const comp = criarCarregado();
      comp.onCategoria(eventoSelect('administradores'));
      comp.onMesAno({ ano: 2025, mes: 11 });

      comp.onTiposAlterados();

      const ultimaGrade = apiGet.mock.calls.filter(c => rotaDe(c[0]) === 'grade').at(-1);
      expect(ultimaGrade).toEqual(['/api/admin/ponto/retificacoes/grade', {
        categoria: 'administradores', ano: 2025, mes: 11,
      }]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // render — o que só existe no template (bindings de clique e popover)
  // ═══════════════════════════════════════════════════════════════════
  describe('render — grade, popover e barra', () => {
    function renderizar(): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();   // ngOnInit + render (as respostas do mock são síncronas)
      return fixture;
    }

    /** Célula (linha do dia, coluna do funcionário) como o usuário a vê. */
    function celulaDe(f: ComponentFixture<GradeRetificacoesComponent>, diaIdx: number, colIdx: number): HTMLElement {
      const linha = f.debugElement.queryAll(By.css('tbody tr'))[diaIdx + 1];   // +1: a linha "Folgas"
      return linha.queryAll(By.css('td.cel'))[colIdx].nativeElement as HTMLElement;
    }
    const rotuloDia = (f: ComponentFixture<GradeRetificacoesComponent>, diaIdx: number) =>
      f.debugElement.queryAll(By.css('tbody tr'))[diaIdx + 1].query(By.css('td.rot-dia')).nativeElement as HTMLElement;
    const popover = (f: ComponentFixture<GradeRetificacoesComponent>) => f.debugElement.query(By.css('.popover'));
    const itens = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.queryAll(By.css('.popover .pop-item'))
        .map(b => (b.nativeElement as HTMLElement).textContent?.trim());

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

    it('o botão "Ocorrências" saiu da barra: a marcação é na própria grade', () => {
      const fixture = renderizar();
      const botoes = fixture.debugElement.queryAll(By.css('.barra button'))
        .map(b => (b.nativeElement as HTMLButtonElement).textContent?.trim());

      expect(botoes).not.toContain('Ocorrências');
      expect(botoes).toEqual(expect.arrayContaining(['Configurar', 'Baixar tabela']));
    });

    it('clicar numa célula livre abre o popover ancorado, com a lista dos tipos individuais', () => {
      const fixture = renderizar();

      celulaDe(fixture, 0, 2).click();   // dia 1, Operador 3 (vazia)
      fixture.detectChanges();

      expect(popover(fixture)).not.toBeNull();
      expect(itens(fixture)).toEqual(['Nenhuma', 'À Disposição', 'Atestado', 'Férias']);
    });

    it('a opção já marcada aparece destacada na lista', () => {
      const fixture = renderizar();

      celulaDe(fixture, 3, 0).click();   // dia 7, Operador 1 — "Atestado"
      fixture.detectChanges();

      const marcado = fixture.debugElement.queryAll(By.css('.popover .pop-item.atual'))
        .map(b => (b.nativeElement as HTMLElement).textContent?.trim());
      expect(marcado).toEqual(['Atestado']);
    });

    it('clicar no rótulo do dia e escolher um tipo mostra a confirmação, não a gravação', () => {
      const fixture = renderizar();

      rotuloDia(fixture, 0).click();
      fixture.detectChanges();
      (fixture.debugElement.queryAll(By.css('.popover .pop-item'))[1].nativeElement as HTMLElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.popover .pop-pergunta')).nativeElement.textContent.trim())
        .toBe('Aplicar "Feriado" para todos os funcionários?');
      expect(apiPut).not.toHaveBeenCalled();

      const confirmar = fixture.debugElement.queryAll(By.css('.popover .pop-acoes button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.trim() === 'Confirmar')!;
      (confirmar.nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(apiPut).toHaveBeenCalledWith('/api/admin/ponto/marcacoes', {
        globais: { aplicar: [{ data: '2026-07-01', tipo_id: 'tp-feriado' }] },
      });
    });

    it('células bloqueadas e fim de semana ficam com aria-disabled e sem a marca de clicável', () => {
      const fixture = renderizar();

      const horarios = celulaDe(fixture, 0, 0);       // dia 1, Operador 1
      const livre = celulaDe(fixture, 0, 2);          // dia 1, Operador 3
      const sobGeral = celulaDe(fixture, 2, 0);       // dia 6 (feriado), Operador 1
      const fds = rotuloDia(fixture, 1);              // dia 4, sábado

      expect(horarios.getAttribute('aria-disabled')).toBe('true');
      expect(horarios.classList.contains('clicavel')).toBe(false);
      expect(sobGeral.getAttribute('aria-disabled')).toBe('true');
      expect(fds.getAttribute('aria-disabled')).toBe('true');
      expect(livre.getAttribute('aria-disabled')).toBeNull();
      expect(livre.classList.contains('clicavel')).toBe(true);
    });

    it('clicar numa célula bloqueada não abre popover nenhum', () => {
      const fixture = renderizar();

      celulaDe(fixture, 0, 0).click();   // horários
      fixture.detectChanges();

      expect(popover(fixture)).toBeNull();
    });

    it('um clique REAL no documento fecha o popover; o clique que o abriu, não', () => {
      // O handler está no document: sem o stopPropagation da abertura, o popover se fecharia sozinho.
      const fixture = renderizar();

      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();
      expect(popover(fixture)).not.toBeNull();

      document.body.click();
      fixture.detectChanges();
      expect(popover(fixture)).toBeNull();
    });

    it('clicar DENTRO do popover não o fecha (a lista sobrevive à própria interação)', () => {
      const fixture = renderizar();
      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();

      (popover(fixture).nativeElement as HTMLElement).click();
      fixture.detectChanges();

      expect(popover(fixture)).not.toBeNull();
    });

    it('Esc de verdade (evento no documento) fecha o popover', () => {
      const fixture = renderizar();
      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();

      expect(popover(fixture)).toBeNull();
    });

    it('a lista abre ancorada na célula clicada (posição fixa vinda do retângulo dela)', () => {
      const fixture = renderizar();
      const celula = celulaDe(fixture, 0, 2);
      celula.getBoundingClientRect = () => ({ left: 340, bottom: 210, top: 190 }) as DOMRect;

      celula.click();
      fixture.detectChanges();

      const caixa = popover(fixture).nativeElement as HTMLElement;
      expect(caixa.style.left).toBe('340px');
      expect(caixa.style.top).toBe('210px');
      expect(caixa.classList.contains('acima')).toBe(false);
    });

    it('sem espaço abaixo, a lista sobe: ancora no topo da célula e cresce para cima', () => {
      const fixture = renderizar();
      const celula = celulaDe(fixture, 0, 2);
      // Célula colada no rodapé da janela do jsdom (768px de altura)
      celula.getBoundingClientRect = () => ({ left: 340, bottom: 760, top: 740 }) as DOMRect;

      celula.click();
      fixture.detectChanges();

      const caixa = popover(fixture).nativeElement as HTMLElement;
      expect(caixa.style.top).toBe('740px');
      expect(caixa.classList.contains('acima')).toBe(true);
    });

    it('célula colada na borda direita: a lista recua para caber na janela', () => {
      const fixture = renderizar();
      const celula = celulaDe(fixture, 0, 2);
      celula.getBoundingClientRect = () => ({ left: 1000, bottom: 210, top: 190 }) as DOMRect;

      celula.click();
      fixture.detectChanges();

      // jsdom: innerWidth 1024 → 1024 - 260 (largura máxima) - 8 (margem)
      expect((popover(fixture).nativeElement as HTMLElement).style.left).toBe('756px');
    });

    it('a célula que está gravando mostra o indicador e a lista fica travada', () => {
      const fixture = renderizar();
      apiPut.mockReturnValue(new Subject<any>());

      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();
      (fixture.debugElement.queryAll(By.css('.popover .pop-item'))[1].nativeElement as HTMLElement).click();
      fixture.detectChanges();

      expect(celulaDe(fixture, 0, 2).querySelector('.salvando')).not.toBeNull();
      expect(fixture.componentInstance.salvandoEm()).toBe('op-3|1');
    });

    it('a ação do dia em voo marca o rótulo do dia e desabilita o Confirmar', () => {
      const fixture = renderizar();
      apiPut.mockReturnValue(new Subject<any>());

      rotuloDia(fixture, 0).click();
      fixture.detectChanges();
      (fixture.debugElement.queryAll(By.css('.popover .pop-item'))[1].nativeElement as HTMLElement).click();
      fixture.detectChanges();

      const confirmar = fixture.debugElement.queryAll(By.css('.popover .pop-acoes button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.trim() === 'Confirmar')!
        .nativeElement as HTMLButtonElement;
      confirmar.click();
      fixture.detectChanges();

      expect(rotuloDia(fixture, 0).querySelector('.salvando')).not.toBeNull();
      expect(confirmar.disabled).toBe(true);
    });

    it('sem tipo no escopo, o popover explica em vez de mostrar uma lista só com "Nenhuma"', () => {
      respostas.tipos = () => of({ data: { tipos: [] } });
      const fixture = renderizar();

      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();

      expect(itens(fixture)).toEqual([]);
      expect(fixture.debugElement.query(By.css('.popover .pop-vazio')).nativeElement.textContent.trim())
        .toBe('Nenhum tipo cadastrado');
    });

    it('catálogo que falhou não se disfarça de catálogo vazio', () => {
      respostas.tipos = falha('500');
      const fixture = renderizar();

      celulaDe(fixture, 0, 2).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.popover .pop-vazio')).nativeElement.textContent.trim())
        .toBe('Não foi possível carregar os tipos de ocorrência.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // render — botão Configurar (só master) e o catálogo alterado
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
      expect(botoesDaBarra(fixture)).toContain('Baixar tabela');
      expect(fixture.debugElement.query(By.directive(ConfigurarTiposMarcacaoComponent))).toBeNull();
    });

    it('o admin comum marca ocorrências normalmente (o catálogo é dele também)', () => {
      // Cadastrar TIPOS é do master; usar os tipos cadastrados é de qualquer admin.
      return configurar(false).then(() => {
        const comp = criarCarregado();
        abrirCelula(comp, 'op-3', 1);
        expect(comp.opcoes()).not.toEqual([]);
      });
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
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 7,
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // render — o seletor da barra propaga o período mensal
  // ═══════════════════════════════════════════════════════════════════
  describe('render — o seletor da barra propaga o período mensal', () => {
    function renderizar(hoje: string): ComponentFixture<GradeRetificacoesComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(hoje));
      const fixture = TestBed.createComponent(GradeRetificacoesComponent);
      fixture.detectChanges();   // ngOnInit + render (o seletor filho é instanciado aqui)
      return fixture;
    }

    const seletor = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.query(By.directive(MesAnoSelectorComponent)).componentInstance as MesAnoSelectorComponent;
    const selectMes = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.query(By.css('app-mes-ano-selector select.sel-mes'))
        .nativeElement as HTMLSelectElement;
    const setaAvancar = (f: ComponentFixture<GradeRetificacoesComponent>) =>
      f.debugElement.query(By.css('app-mes-ano-selector button[aria-label="Próximo mês"]'))
        .nativeElement as HTMLButtonElement;

    it('selecionar um mês passado pede a grade correspondente', () => {
      const fixture = renderizar('2026-07-12T10:00:00-03:00');
      const comp = fixture.componentInstance;

      apiGet.mockClear();
      selectMes(fixture).value = '1';
      selectMes(fixture).dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(comp.anoMes()).toEqual({ ano: 2026, mes: 1 });
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 1,
      });
    });

    it('navegar até o teto futuro pede a grade e desabilita a seta seguinte', () => {
      const fixture = renderizar('2026-07-12T10:00:00-03:00');
      const comp = fixture.componentInstance;

      expect(seletor(fixture).anos()).toEqual([2026]);
      apiGet.mockClear();

      setaAvancar(fixture).click();
      fixture.detectChanges();
      setaAvancar(fixture).click();
      fixture.detectChanges();

      expect(comp.anoMes()).toEqual({ ano: 2026, mes: 9 });
      expect(apiGet).toHaveBeenLastCalledWith('/api/admin/ponto/retificacoes/grade', {
        categoria: 'operadores', ano: 2026, mes: 9,
      });
      expect(setaAvancar(fixture).disabled).toBe(true);
    });
  });
});

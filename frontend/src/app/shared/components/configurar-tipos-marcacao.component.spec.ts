import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ConfigurarTiposMarcacaoComponent, TipoMarcacao } from './configurar-tipos-marcacao.component';
import { ToastService } from './toast.component';

/**
 * ConfigurarTiposMarcacaoComponent (modal "Configurar" do card Retificações): os tipos de
 * ocorrência são um CATÁLOGO do backend — nada é constante no front. O modal carrega o catálogo,
 * filtra por escopo (Geral/Individual), monta um rascunho de inclusão (vários tipos de uma vez,
 * com recusa local de nome/badge repetidos) ou marca UM tipo para exclusão, que só acontece depois
 * do preview e da confirmação.
 *
 * TestBed sem `detectChanges()` por padrão — `ngOnInit` à mão, filhos não instanciados;
 * `ApiService` mockado por `useValue` com `get` roteado por URL (catálogo × preview) e
 * `ToastService` idem. Os outputs `(fechar)`/`(alterado)` são observados por `subscribe`. O SUT não lê `Date` — o relógio congelado é profilático.
 * Os blocos `render — ...` são a exceção deliberada: radios, chips, botão "+", gate do Aplicar e
 * o texto da confirmação vivem SÓ no template, e sem render apagá-los deixaria a suíte verde.
 */

/** Catálogo representativo de `GET /api/admin/ponto/tipos-marcacao` (dois escopos). */
const CATALOGO: TipoMarcacao[] = [
  { id: 't-1', nome: 'Feriado', badge: 'FER', escopo: 'GLOBAL', visivel_funcionario: false, conta_folga: false },
  { id: 't-2', nome: 'Ponto Facultativo', badge: 'PF', escopo: 'GLOBAL', visivel_funcionario: false, conta_folga: false },
  { id: 't-3', nome: 'Férias', badge: 'FÉ', escopo: 'INDIVIDUAL', visivel_funcionario: false, conta_folga: false },
  { id: 't-4', nome: 'Atestado', badge: 'AT', escopo: 'INDIVIDUAL', visivel_funcionario: false, conta_folga: false },
  // O funcionário também o declara na própria folha; para o admin é um tipo como qualquer outro.
  { id: 't-5', nome: 'Banco de horas', badge: 'Ban', escopo: 'INDIVIDUAL', visivel_funcionario: true, conta_folga: true },
];

/** Preview de exclusão de um tipo INDIVIDUAL: conta marcações E funcionários atingidos. */
const PREVIEW_INDIVIDUAL = {
  tipo: { id: 't-3', nome: 'Férias', badge: 'FÉ', escopo: 'INDIVIDUAL' as const,
    visivel_funcionario: false, conta_folga: false },
  marcacoes: 12,
  retificacoes: 0,
  pessoas_afetadas: 2,
  pessoas: ['Ana', 'Bruno'],
};

/** Preview de um tipo GERAL: não há funcionário específico atingido. */
const PREVIEW_GLOBAL = {
  tipo: { id: 't-1', nome: 'Feriado', badge: 'FER', escopo: 'GLOBAL' as const,
    visivel_funcionario: false, conta_folga: false },
  marcacoes: 3,
  retificacoes: 0,
  pessoas_afetadas: 0,
  pessoas: [] as string[],
};

describe('ConfigurarTiposMarcacaoComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiDelete: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let confirmSpy: ReturnType<typeof vi.spyOn>;

  /** Roteia o `get` por URL: o preview do tipo marcado × o catálogo completo. */
  function rotearGet(lista: TipoMarcacao[] = CATALOGO): void {
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/exclusao/preview')) {
        return of({ ok: true, data: structuredClone(url.includes('/t-3/') ? PREVIEW_INDIVIDUAL : PREVIEW_GLOBAL) });
      }
      return of({ ok: true, data: { tipos: structuredClone(lista) } });
    });
  }

  beforeEach(async () => {
    apiGet = vi.fn();
    rotearGet();
    apiPost = vi.fn().mockReturnValue(of({ ok: true, data: { criados: ['t-9'] } }));
    apiDelete = vi.fn().mockReturnValue(of({ ok: true, data: structuredClone(PREVIEW_INDIVIDUAL) }));
    toastSuccess = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [ConfigurarTiposMarcacaoComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost, delete: apiDelete } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError, warning: vi.fn(), show: vi.fn() } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    // O aviso de rascunho ao fechar é um confirm() nativo: por padrão o usuário aceita descartar.
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Cria o componente com os dois outputs observados. */
  function criar() {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    const fixture = TestBed.createComponent(ConfigurarTiposMarcacaoComponent);
    const comp = fixture.componentInstance;
    const fechou = vi.fn();
    const alterou = vi.fn();
    comp.fechar.subscribe(fechou);
    comp.alterado.subscribe(alterou);
    return { fixture, comp, fechou, alterou };
  }

  /** Componente com o catálogo já carregado (ngOnInit à mão — sem detectChanges não há ciclo de vida). */
  function criarCarregado() {
    const ctx = criar();
    ctx.comp.ngOnInit();
    return ctx;
  }

  /** Rascunho de inclusão pronto: um tipo novo válido na lista de pendentes. */
  function digitarPendente(comp: ConfigurarTiposMarcacaoComponent, nome: string, badge: string): void {
    comp.nomeNovo.set(nome);
    comp.badgeNovo.set(badge);
    comp.adicionarPendente();
  }

  /** Modo Excluir com um tipo do catálogo marcado. */
  function marcar(comp: ConfigurarTiposMarcacaoComponent, id: string): void {
    comp.trocarOperacao('excluir');
    comp.marcarParaExcluir(comp.tipos().find(t => t.id === id)!);
  }

  // ═══════════════════════════════════════════════════════════════════
  // Carga do catálogo — os tipos vêm do backend, nunca de constantes do front
  // ═══════════════════════════════════════════════════════════════════
  describe('carregar', () => {
    it('ngOnInit busca o catálogo e guarda os tipos dos dois escopos', () => {
      const { comp } = criarCarregado();
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao');
      expect(comp.tipos().map(t => t.id)).toEqual(['t-1', 't-2', 't-3', 't-4', 't-5']);
      expect(comp.carregando()).toBe(false);
      expect(comp.erroCarga()).toBe('');
    });

    it('carregando fica true enquanto a resposta não chega', () => {
      const emVoo = new Subject<any>();
      apiGet.mockReturnValue(emVoo);
      const { comp } = criarCarregado();

      expect(comp.carregando()).toBe(true);
      expect(comp.tipos()).toEqual([]);

      emVoo.next({ data: { tipos: structuredClone(CATALOGO) } });
      expect(comp.carregando()).toBe(false);
      expect(comp.tipos()).toHaveLength(5);
    });

    it('erro na carga: a orientação da tela vem na frente, com o detalhe do backend anexado', () => {
      const { comp } = criar();
      apiGet.mockReturnValue(throwError(() => ({ error: { message: 'Sem permissão' } })));
      comp.ngOnInit();

      expect(comp.erroCarga()).toBe('Erro ao carregar os tipos de ocorrência. (Sem permissão)');
      expect(comp.tipos()).toEqual([]);
      expect(comp.carregando()).toBe(false);
    });

    it('erro sem corpo: só a mensagem-guia', () => {
      const { comp } = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      comp.ngOnInit();
      expect(comp.erroCarga()).toBe('Erro ao carregar os tipos de ocorrência.');
    });

    it('resposta sem data: catálogo vazio, sem erro', () => {
      const { comp } = criar();
      apiGet.mockReturnValue(of({ ok: true }));
      comp.ngOnInit();
      expect(comp.tipos()).toEqual([]);
      expect(comp.erroCarga()).toBe('');
    });

    it('o retry recarrega o catálogo e limpa o erro', () => {
      const { comp } = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      comp.ngOnInit();
      expect(comp.erroCarga()).not.toBe('');

      rotearGet();
      comp.carregar(); // é o que a caixa app-erro-carga dispara

      expect(comp.erroCarga()).toBe('');
      expect(comp.tipos()).toHaveLength(5);
      expect(apiGet).toHaveBeenCalledTimes(2);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Escopo — o radio Geral/Individual decide QUAIS tipos aparecem
  // ═══════════════════════════════════════════════════════════════════
  describe('escopo e tiposDoEscopo', () => {
    it('abre em Geral e lista só os tipos GLOBAL', () => {
      const { comp } = criarCarregado();
      expect(comp.escopo()).toBe('GLOBAL');
      expect(comp.tiposDoEscopo().map(t => t.nome)).toEqual(['Feriado', 'Ponto Facultativo']);
    });

    it('Individual lista só os tipos INDIVIDUAL', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      expect(comp.tiposDoEscopo().map(t => t.nome)).toEqual(['Férias', 'Atestado', 'Banco de horas']);
    });

    it('escopo sem nenhum tipo cadastrado devolve lista vazia', () => {
      rotearGet(CATALOGO.filter(t => t.escopo === 'GLOBAL'));
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      expect(comp.tiposDoEscopo()).toEqual([]);
    });

    it('o tipo que o funcionário declara aparece na lista como qualquer outro do escopo', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      expect(comp.tiposDoEscopo().map(t => t.nome)).toContain('Banco de horas');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Trocar de radio descarta o rascunho — o que valia para um escopo/modo
  // não vale para o outro
  // ═══════════════════════════════════════════════════════════════════
  describe('troca de radio descarta o rascunho', () => {
    it('trocar de escopo esvazia pendentes, campos digitados e a mensagem de erro', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'Feriado', 'XY'); // recusado: deixa erro na tela
      comp.nomeNovo.set('meio digitado');
      expect(comp.pendentes()).toHaveLength(1);
      expect(comp.erro()).not.toBe('');

      comp.trocarEscopo('INDIVIDUAL');

      expect(comp.pendentes()).toEqual([]);
      expect(comp.nomeNovo()).toBe('');
      expect(comp.badgeNovo()).toBe('');
      expect(comp.erro()).toBe('');
    });

    it('trocar de escopo desmarca o tipo escolhido para exclusão', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');
      expect(comp.marcadoId()).toBe('t-1');

      comp.trocarEscopo('INDIVIDUAL');
      expect(comp.marcadoId()).toBe('');
      expect(comp.podeAplicar()).toBe(false);
    });

    it('trocar de operação (Incluir → Excluir) esvazia o rascunho de inclusão', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');

      comp.trocarOperacao('excluir');

      expect(comp.pendentes()).toEqual([]);
      expect(comp.erro()).toBe('');
      expect(comp.podeAplicar()).toBe(false);
    });

    it('trocar de operação (Excluir → Incluir) desmarca o tipo', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-2');

      comp.trocarOperacao('incluir');
      expect(comp.marcadoId()).toBe('');
    });

    it('reclicar no MESMO radio de escopo não descarta nada', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');

      comp.trocarEscopo('GLOBAL'); // já era o escopo corrente

      expect(comp.pendentes()).toHaveLength(1);
    });

    it('reclicar na MESMA operação não descarta nada', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');

      comp.trocarOperacao('incluir');

      expect(comp.pendentes()).toHaveLength(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Incluir — o rascunho é montado no cliente e só vai ao servidor no Aplicar
  // ═══════════════════════════════════════════════════════════════════
  describe('incluir: montagem do rascunho', () => {
    /** Componente carregado e já no modo Incluir. */
    function criarIncluindo(escopo: 'GLOBAL' | 'INDIVIDUAL' = 'GLOBAL') {
      const ctx = criarCarregado();
      ctx.comp.trocarEscopo(escopo);
      ctx.comp.trocarOperacao('incluir');
      return ctx;
    }

    it('o + guarda o tipo digitado como pendente e limpa os dois campos', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');

      expect(comp.pendentes()).toEqual([
        { nome: 'Recesso', badge: 'REC', visivel_funcionario: false, conta_folga: false }]);
      expect(comp.nomeNovo()).toBe('');
      expect(comp.badgeNovo()).toBe('');
      expect(comp.erro()).toBe('');
      expect(apiPost).not.toHaveBeenCalled(); // nada vai ao servidor antes do Aplicar
    });

    it('vários pendentes de uma vez, na ordem em que foram digitados', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'Convocação', 'CV');
      digitarPendente(comp, 'Treinamento', 'TR');

      expect(comp.pendentes().map(p => p.nome)).toEqual(['Recesso', 'Convocação', 'Treinamento']);
      expect(comp.podeAplicar()).toBe(true);
    });

    it('nome ou badge em branco: o + não faz nada (nem erro, nem pendente)', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, '   ', 'REC');
      digitarPendente(comp, 'Recesso', '  ');

      expect(comp.pendentes()).toEqual([]);
      expect(comp.erro()).toBe('');
    });

    it('espaços em excesso no nome são colapsados antes de guardar', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, '  Recesso   Branco  ', ' RB ');
      expect(comp.pendentes()).toEqual([
        { nome: 'Recesso Branco', badge: 'RB', visivel_funcionario: false, conta_folga: false }]);
    });

    it('nome com mais de 20 caracteres é recusado com mensagem e nada entra na lista', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'A'.repeat(21), 'RC');

      expect(comp.erro()).toBe('O nome do tipo deve ter no máximo 20 caracteres.');
      expect(comp.pendentes()).toEqual([]);
    });

    it('nome com exatamente 20 caracteres é aceito (o teto é inclusivo)', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'A'.repeat(20), 'RC');
      expect(comp.pendentes()).toHaveLength(1);
      expect(comp.erro()).toBe('');
    });

    it('badge com mais de 3 caracteres é recusada', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'RECE');

      expect(comp.erro()).toBe('A badge deve ter no máximo 3 caracteres.');
      expect(comp.pendentes()).toEqual([]);
    });

    it('badge com exatamente 3 caracteres é aceita', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      expect(comp.pendentes()).toHaveLength(1);
    });

    it('nome já existente no catálogo é recusado sem gastar requisição', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Feriado', 'FRD');

      expect(comp.erro()).toBe('Já existe um tipo com o nome "Feriado".');
      expect(comp.pendentes()).toEqual([]);
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('a comparação de nomes ignora acento e caixa — "ferias" colide com "Férias"', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'ferias', 'FRS');

      expect(comp.erro()).toBe('Já existe um tipo com o nome "Férias" (em Individual).');
      expect(comp.pendentes()).toEqual([]);
    });

    it('a colisão vale mesmo com o tipo existente sendo de OUTRO escopo (o catálogo é único)', () => {
      const { comp } = criarIncluindo('GLOBAL'); // "Férias" é INDIVIDUAL
      digitarPendente(comp, 'Férias', 'FRS');
      // o tipo conflitante não está na lista à vista: a recusa diz em que escopo ele vive
      expect(comp.erro()).toBe('Já existe um tipo com o nome "Férias" (em Individual).');
    });

    it('badge já existente é recusada, também ignorando acento e caixa ("fe" × "FÉ")', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'fe');

      expect(comp.erro()).toBe('Já existe um tipo com a badge "FÉ" (em Individual).');
      expect(comp.pendentes()).toEqual([]);
    });

    it('nome repetido de um PENDENTE (ainda não salvo) também é recusado', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'RECESSO', 'RS');

      expect(comp.erro()).toBe('Já existe um tipo com o nome "Recesso".');
      expect(comp.pendentes()).toHaveLength(1);
    });

    it('badge repetida de um pendente também é recusada', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'Convocação', 'rec');

      expect(comp.erro()).toBe('Já existe um tipo com a badge "REC".');
      expect(comp.pendentes()).toHaveLength(1);
    });

    it('um tipo válido depois da recusa entra na lista e apaga a mensagem de erro', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Feriado', 'FRD');
      expect(comp.erro()).not.toBe('');

      digitarPendente(comp, 'Recesso', 'REC');

      expect(comp.erro()).toBe('');
      expect(comp.pendentes()).toEqual([
        { nome: 'Recesso', badge: 'REC', visivel_funcionario: false, conta_folga: false }]);
    });

    it('remover um pendente tira só ele da lista', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'Convocação', 'CV');
      digitarPendente(comp, 'Treinamento', 'TR');

      comp.removerPendente(1);

      expect(comp.pendentes().map(p => p.nome)).toEqual(['Recesso', 'Treinamento']);
    });

    it('remover o último pendente zera o gate do Aplicar', () => {
      const { comp } = criarIncluindo();
      digitarPendente(comp, 'Recesso', 'REC');
      comp.removerPendente(0);

      expect(comp.pendentes()).toEqual([]);
      expect(comp.podeAplicar()).toBe(false);
    });

    it('as marcas escolhidas nos checkboxes acompanham o pendente do escopo Individual', () => {
      const { comp } = criarIncluindo('INDIVIDUAL');
      comp.visivelNovo.set(true);
      comp.contaFolgaNovo.set(true);
      digitarPendente(comp, 'Compensação', 'CMP');

      expect(comp.pendentes()).toEqual([
        { nome: 'Compensação', badge: 'CMP', visivel_funcionario: true, conta_folga: true }]);
    });

    it('no escopo Geral as marcas saem sempre desligadas — os checkboxes nem aparecem lá', () => {
      const { comp } = criarIncluindo('GLOBAL');
      comp.visivelNovo.set(true);
      comp.contaFolgaNovo.set(true);
      digitarPendente(comp, 'Luto', 'LUT');

      expect(comp.pendentes()).toEqual([
        { nome: 'Luto', badge: 'LUT', visivel_funcionario: false, conta_folga: false }]);
    });

    it('trocar de escopo zera também os checkboxes das marcas', () => {
      const { comp } = criarIncluindo('INDIVIDUAL');
      comp.visivelNovo.set(true);
      comp.contaFolgaNovo.set(true);

      comp.trocarEscopo('GLOBAL');

      expect(comp.visivelNovo()).toBe(false);
      expect(comp.contaFolgaNovo()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Incluir — Aplicar: um POST com todos os pendentes (tudo ou nada)
  // ═══════════════════════════════════════════════════════════════════
  describe('incluir: aplicar (POST)', () => {
    it('envia os pendentes com o escopo escolhido e emite (alterado) e (fechar)', () => {
      const { comp, fechou, alterou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');

      comp.aplicar();

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao', {
        tipos: [{ nome: 'Recesso', badge: 'REC', escopo: 'GLOBAL',
          visivel_funcionario: false, conta_folga: false }],
      });
      expect(toastSuccess).toHaveBeenCalledWith('Tipo cadastrado.');
      expect(alterou).toHaveBeenCalledTimes(1);
      expect(fechou).toHaveBeenCalledTimes(1);
      expect(comp.salvando()).toBe(false);
    });

    it('o escopo Individual acompanha cada tipo do corpo', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Licença', 'LIC');
      digitarPendente(comp, 'Curso', 'CUR');

      comp.aplicar();

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao', {
        tipos: [
          { nome: 'Licença', badge: 'LIC', escopo: 'INDIVIDUAL',
            visivel_funcionario: false, conta_folga: false },
          { nome: 'Curso', badge: 'CUR', escopo: 'INDIVIDUAL',
            visivel_funcionario: false, conta_folga: false },
        ],
      });
      expect(toastSuccess).toHaveBeenCalledWith('Tipos cadastrados.'); // plural com 2+
    });

    it('as marcas escolhidas para o tipo individual vão no corpo do POST', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      comp.trocarOperacao('incluir');
      comp.visivelNovo.set(true);
      digitarPendente(comp, 'Compensação', 'CMP');

      comp.aplicar();

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao', {
        tipos: [{ nome: 'Compensação', badge: 'CMP', escopo: 'INDIVIDUAL',
          visivel_funcionario: true, conta_folga: false }],
      });
    });

    it('erro do servidor: a mensagem fica na tela e o rascunho NÃO é perdido', () => {
      const { comp, fechou, alterou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      digitarPendente(comp, 'Convocação', 'CV');
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'Badge já utilizada.' } })));

      comp.aplicar();

      expect(comp.erro()).toBe('Badge já utilizada.');
      expect(comp.pendentes().map(p => p.nome)).toEqual(['Recesso', 'Convocação']); // rascunho intacto
      expect(comp.operacao()).toBe('incluir');
      expect(comp.salvando()).toBe(false);
      expect(alterou).not.toHaveBeenCalled();
      expect(fechou).not.toHaveBeenCalled();
      expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('erro sem corpo: mensagem de fallback', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      apiPost.mockReturnValue(throwError(() => new Error('rede')));

      comp.aplicar();

      expect(comp.erro()).toBe('Erro ao cadastrar os tipos.');
      expect(comp.pendentes()).toHaveLength(1);
    });

    it('o rascunho corrigido depois do erro é reenviado (repetir o Aplicar é o retry)', () => {
      const { comp, fechou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'Badge já utilizada.' } })));
      comp.aplicar();

      apiPost.mockReturnValue(of({ ok: true }));
      comp.aplicar();

      expect(apiPost).toHaveBeenCalledTimes(2);
      expect(comp.erro()).toBe('');
      expect(fechou).toHaveBeenCalledTimes(1);
    });

    it('com o POST em voo o duplo clique não repete o cadastro', () => {
      const { comp, fechou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);

      comp.aplicar();
      expect(comp.salvando()).toBe(true);
      comp.aplicar(); // segundo clique

      expect(apiPost).toHaveBeenCalledTimes(1);

      emVoo.next({ ok: true });
      expect(comp.salvando()).toBe(false);
      expect(fechou).toHaveBeenCalledTimes(1);
    });

    it('sem pendentes o Aplicar não chama nada', () => {
      const { comp, fechou } = criarCarregado();
      comp.trocarOperacao('incluir');

      comp.aplicar();

      expect(apiPost).not.toHaveBeenCalled();
      expect(fechou).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Excluir — um tipo por vez, e nunca sem antes ver o que morre junto
  // ═══════════════════════════════════════════════════════════════════
  describe('excluir: marcação do tipo', () => {
    it('clicar num tipo o marca; clicar em outro troca a marcação (nunca dois)', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('excluir');

      comp.marcarParaExcluir(comp.tipos()[0]);
      expect(comp.marcadoId()).toBe('t-1');

      comp.marcarParaExcluir(comp.tipos()[1]);
      expect(comp.marcadoId()).toBe('t-2');
      expect(comp.podeAplicar()).toBe(true);
    });

    it('clicar no tipo já marcado desfaz a marcação', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');

      comp.marcarParaExcluir(comp.tipos()[0]);

      expect(comp.marcadoId()).toBe('');
      expect(comp.podeAplicar()).toBe(false);
    });

    it('fora do modo Excluir o clique no tipo é inerte', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');

      comp.marcarParaExcluir(comp.tipos()[0]);

      expect(comp.marcadoId()).toBe('');
    });

    it('o tipo que o funcionário declara também se marca para excluir', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      marcar(comp, 't-5');

      expect(comp.marcadoId()).toBe('t-5');
      expect(comp.podeAplicar()).toBe(true);
    });
  });

  describe('excluir: preview antes de perguntar', () => {
    it('o Aplicar busca o preview do tipo marcado e só então abre a confirmação', () => {
      const { comp } = criarCarregado();
      comp.trocarEscopo('INDIVIDUAL');
      marcar(comp, 't-3');

      comp.aplicar();

      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao/t-3/exclusao/preview');
      expect(apiDelete).not.toHaveBeenCalled(); // a exclusão não acontece direto
      expect(comp.preview()).toMatchObject({ marcacoes: 12, pessoas_afetadas: 2, pessoas: ['Ana', 'Bruno'] });
      expect(comp.erro()).toBe('');
      expect(comp.salvando()).toBe(false);
    });

    it('falha no preview: a mensagem fica na tela e NENHUMA confirmação é aberta', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');
      apiGet.mockReturnValue(throwError(() => ({ error: { message: 'Erro interno do servidor' } })));

      comp.aplicar();

      expect(comp.preview()).toBeNull();
      expect(comp.erro()).toBe(
        'Não foi possível concluir a operação. (Erro interno do servidor)',
      );
      expect(apiDelete).not.toHaveBeenCalled();
      expect(comp.marcadoId()).toBe('t-1'); // a marcação continua para o retry
    });

    it('falha no preview sem corpo: só a mensagem-guia', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');
      apiGet.mockReturnValue(throwError(() => new Error('rede')));

      comp.aplicar();

      expect(comp.erro()).toBe('Não foi possível concluir a operação.');
    });

    it('preview sem data não abre confirmação vazia', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');
      apiGet.mockReturnValue(of({ ok: true }));

      comp.aplicar();

      expect(comp.preview()).toBeNull();
    });

    it('sem tipo marcado o Aplicar não busca preview nenhum', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('excluir');
      apiGet.mockClear();

      comp.aplicar();

      expect(apiGet).not.toHaveBeenCalled();
      expect(comp.preview()).toBeNull();
    });

    it('com o preview em voo o duplo clique não repete a consulta', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');
      const emVoo = new Subject<any>();
      apiGet.mockReturnValue(emVoo);
      apiGet.mockClear();

      comp.aplicar();
      comp.aplicar();

      expect(apiGet).toHaveBeenCalledTimes(1);
      expect(comp.salvando()).toBe(true);
    });
  });

  describe('excluir: confirmação (DELETE)', () => {
    /** Componente com o segundo modal (confirmação) já aberto para o tipo individual. */
    function criarConfirmando(id = 't-3') {
      const ctx = criarCarregado();
      ctx.comp.trocarEscopo(id === 't-3' ? 'INDIVIDUAL' : 'GLOBAL');
      marcar(ctx.comp, id);
      ctx.comp.aplicar();
      return ctx;
    }

    it('"Excluir definitivamente" chama o DELETE do tipo, toca o toast e emite (alterado) e (fechar)', () => {
      const { comp, fechou, alterou } = criarConfirmando();

      comp.confirmarExclusao();

      expect(apiDelete).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao/t-3');
      expect(toastSuccess).toHaveBeenCalledWith('Tipo excluído.');
      expect(alterou).toHaveBeenCalledTimes(1);
      expect(fechou).toHaveBeenCalledTimes(1);
      expect(comp.preview()).toBeNull();
      expect(comp.excluindo()).toBe(false);
    });

    it('recusa 403: a mensagem fica NO MODAL de confirmação, que continua aberto', () => {
      const { comp, fechou, alterou } = criarConfirmando();
      apiDelete.mockReturnValue(throwError(() => ({
        status: 403,
        error: { message: 'Apenas o administrador master pode excluir tipos.' },
      })));

      comp.confirmarExclusao();

      expect(comp.erroExclusao()).toBe('Apenas o administrador master pode excluir tipos.');
      expect(comp.preview()).not.toBeNull(); // não some fingindo que excluiu
      expect(comp.excluindo()).toBe(false);
      expect(alterou).not.toHaveBeenCalled();
      expect(fechou).not.toHaveBeenCalled();
      expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('erro sem corpo: mensagem de fallback na confirmação', () => {
      const { comp } = criarConfirmando();
      apiDelete.mockReturnValue(throwError(() => new Error('rede')));

      comp.confirmarExclusao();

      expect(comp.erroExclusao()).toBe('Não foi possível excluir o tipo.');
    });

    it('com o DELETE em voo o duplo clique não repete a exclusão', () => {
      const { comp, fechou } = criarConfirmando();
      const emVoo = new Subject<any>();
      apiDelete.mockReturnValue(emVoo);

      comp.confirmarExclusao();
      expect(comp.excluindo()).toBe(true);
      comp.confirmarExclusao();

      expect(apiDelete).toHaveBeenCalledTimes(1);

      emVoo.next({ ok: true });
      expect(fechou).toHaveBeenCalledTimes(1);
    });

    it('cancelar a confirmação volta à lista sem excluir, com o tipo ainda marcado', () => {
      const { comp, fechou } = criarConfirmando();

      comp.fecharConfirmacao();

      expect(comp.preview()).toBeNull();
      expect(comp.erroExclusao()).toBe('');
      expect(apiDelete).not.toHaveBeenCalled();
      expect(comp.marcadoId()).toBe('t-3');
      expect(fechou).not.toHaveBeenCalled(); // o modal de trás continua aberto
    });

    it('durante a exclusão o Cancelar da confirmação é inerte', () => {
      const { comp } = criarConfirmando();
      apiDelete.mockReturnValue(new Subject<any>());
      comp.confirmarExclusao();

      comp.fecharConfirmacao();

      expect(comp.preview()).not.toBeNull();
    });

    it('confirmar sem preview aberto não chama o DELETE', () => {
      const { comp } = criarCarregado();
      marcar(comp, 't-1');

      comp.confirmarExclusao();

      expect(apiDelete).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Gate do Aplicar e fechamento com rascunho pendente
  // ═══════════════════════════════════════════════════════════════════
  describe('gate do Aplicar', () => {
    it('sem operação escolhida o Aplicar está bloqueado', () => {
      const { comp } = criarCarregado();
      expect(comp.operacao()).toBe('');
      expect(comp.podeAplicar()).toBe(false);

      comp.aplicar();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('cada modo tem o seu rascunho mínimo: pendentes para incluir, um marcado para excluir', () => {
      const { comp } = criarCarregado();
      comp.trocarOperacao('incluir');
      expect(comp.podeAplicar()).toBe(false);
      digitarPendente(comp, 'Recesso', 'REC');
      expect(comp.podeAplicar()).toBe(true);

      comp.trocarOperacao('excluir');
      expect(comp.podeAplicar()).toBe(false);
      comp.marcarParaExcluir(comp.tipos()[0]);
      expect(comp.podeAplicar()).toBe(true);
    });
  });

  describe('fechar o modal', () => {
    it('sem rascunho: fecha direto, sem perguntar nada', () => {
      const { comp, fechou } = criarCarregado();

      comp.tentarFechar();

      expect(confirmSpy).not.toHaveBeenCalled();
      expect(fechou).toHaveBeenCalledTimes(1);
    });

    it('com rascunho: pergunta antes; recusar mantém o modal aberto', () => {
      const { comp, fechou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      confirmSpy.mockReturnValue(false);

      comp.tentarFechar();

      expect(confirmSpy).toHaveBeenCalledTimes(1);
      expect(fechou).not.toHaveBeenCalled();
      expect(comp.pendentes()).toHaveLength(1);
    });

    it('com rascunho: aceitar descarta e fecha', () => {
      const { comp, fechou } = criarCarregado();
      marcar(comp, 't-1');

      comp.tentarFechar();

      expect(fechou).toHaveBeenCalledTimes(1);
    });

    it('durante o POST o Cancelar é inerte', () => {
      const { comp, fechou } = criarCarregado();
      comp.trocarOperacao('incluir');
      digitarPendente(comp, 'Recesso', 'REC');
      apiPost.mockReturnValue(new Subject<any>());
      comp.aplicar();

      comp.tentarFechar();

      expect(fechou).not.toHaveBeenCalled();
    });

    it('com a confirmação de exclusão aberta, o clique fora não fecha o modal de trás', () => {
      const { comp, fechou } = criarCarregado();
      marcar(comp, 't-1');
      comp.aplicar();
      expect(comp.preview()).not.toBeNull();

      comp.tentarFechar();

      expect(fechou).not.toHaveBeenCalled();
      expect(comp.preview()).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — o catálogo, os radios, o rascunho e o gate do Aplicar
  // vivem no TEMPLATE: sem render, apagar um binding sairia impune
  // ═══════════════════════════════════════════════════════════════════
  describe('render — catálogo, escopo e rascunho', () => {
    function renderizar(): ComponentFixture<ConfigurarTiposMarcacaoComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(ConfigurarTiposMarcacaoComponent);
      fixture.detectChanges(); // ngOnInit + render (as respostas do mock são síncronas)
      return fixture;
    }

    /** Os rótulos dos chips exibidos (tipos do catálogo + pendentes), com espaços normalizados. */
    const chips = (f: ComponentFixture<ConfigurarTiposMarcacaoComponent>) =>
      f.debugElement.queryAll(By.css('.lista .chip'))
        .map(c => (c.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ').trim());

    const radio = (f: ComponentFixture<ConfigurarTiposMarcacaoComponent>, grupo: string, valor: string) =>
      f.debugElement.query(By.css(`input[name="${grupo}"][value="${valor}"]`)).nativeElement as HTMLInputElement;

    const btnAplicar = (f: ComponentFixture<ConfigurarTiposMarcacaoComponent>) =>
      f.debugElement.queryAll(By.css('.modal-actions button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.trim().startsWith('Aplic'))
        ?.nativeElement as HTMLButtonElement | undefined;

    const btnAdd = (f: ComponentFixture<ConfigurarTiposMarcacaoComponent>) =>
      f.debugElement.query(By.css('.btn-add'))?.nativeElement as HTMLButtonElement | undefined;

    /** Escreve num dos campos do formulário de inclusão como o usuário faria. */
    function digitar(f: ComponentFixture<ConfigurarTiposMarcacaoComponent>, aria: string, valor: string): void {
      const input = f.debugElement.query(By.css(`.novo input[aria-label="${aria}"]`)).nativeElement as HTMLInputElement;
      input.value = valor;
      input.dispatchEvent(new Event('input'));
      f.detectChanges();
    }

    it('o título não cita mês nenhum: o catálogo vale para todos', () => {
      const fixture = renderizar();
      expect((fixture.debugElement.query(By.css('.modal-title')).nativeElement as HTMLElement).textContent?.trim())
        .toBe('Configurar ocorrências');
    });

    it('abre listando os tipos GERAIS; o clique em "Individual" troca os chips exibidos', () => {
      const fixture = renderizar();
      expect(chips(fixture)).toEqual(['Feriado FER', 'Ponto Facultativo PF']);

      radio(fixture, 'escopo', 'INDIVIDUAL').click();
      fixture.detectChanges();

      // as tags do chip dizem as marcas do tipo: visível aos funcionários e/ou conta folga
      expect(chips(fixture)).toEqual(['Férias FÉ', 'Atestado AT', 'Banco de horas BanFuncionáriosFolga']);
    });

    it('os checkboxes das marcas só aparecem no modo Incluir do escopo Individual', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.css('.flags'))).toBeNull(); // escopo Geral

      radio(fixture, 'escopo', 'INDIVIDUAL').click();
      fixture.detectChanges();
      expect(fixture.debugElement.queryAll(By.css('.flags input[type="checkbox"]'))).toHaveLength(2);
    });

    it('escopo sem tipos mostra "Nenhum tipo cadastrado"', () => {
      rotearGet(CATALOGO.filter(t => t.escopo === 'GLOBAL'));
      const fixture = renderizar();

      radio(fixture, 'escopo', 'INDIVIDUAL').click();
      fixture.detectChanges();

      expect((fixture.debugElement.query(By.css('.empty-state')).nativeElement as HTMLElement).textContent?.trim())
        .toBe('Nenhum tipo cadastrado');
      expect(chips(fixture)).toEqual([]);
    });

    it('o estado vazio some quando há um pendente, mesmo sem tipo cadastrado', () => {
      rotearGet(CATALOGO.filter(t => t.escopo === 'GLOBAL'));
      const fixture = renderizar();
      radio(fixture, 'escopo', 'INDIVIDUAL').click();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();

      digitar(fixture, 'Nome do tipo', 'Licença');
      digitar(fixture, 'Badge do tipo', 'LIC');
      btnAdd(fixture)!.click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.empty-state'))).toBeNull();
      expect(chips(fixture)).toEqual(['Licença LIC✕']);
    });

    it('o formulário de inclusão só aparece no modo Incluir', () => {
      const fixture = renderizar();
      expect(fixture.debugElement.query(By.css('.novo'))).toBeNull();

      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.css('.novo'))).not.toBeNull();

      radio(fixture, 'operacao', 'excluir').click();
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.css('.novo'))).toBeNull();
    });

    it('o + fica desabilitado enquanto faltar nome ou badge', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      expect(btnAdd(fixture)!.disabled).toBe(true);

      digitar(fixture, 'Nome do tipo', 'Recesso');
      expect(btnAdd(fixture)!.disabled).toBe(true); // sem badge ainda

      digitar(fixture, 'Badge do tipo', 'REC');
      expect(btnAdd(fixture)!.disabled).toBe(false);
    });

    it('o + vira chip pendente, limpa os campos e o ✕ retira o pendente da lista', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      digitar(fixture, 'Nome do tipo', 'Recesso');
      digitar(fixture, 'Badge do tipo', 'REC');

      btnAdd(fixture)!.click();
      fixture.detectChanges();

      expect(chips(fixture)).toEqual(['Feriado FER', 'Ponto Facultativo PF', 'Recesso REC✕']);
      const campos = fixture.debugElement.queryAll(By.css('.novo input'))
        .map(i => (i.nativeElement as HTMLInputElement).value);
      expect(campos).toEqual(['', '']);

      (fixture.debugElement.query(By.css('.chip.pendente .btn-remover')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.chip.pendente'))).toBeNull();
    });

    it('a recusa por duplicidade aparece na caixa de erro e o chip não é criado', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      digitar(fixture, 'Nome do tipo', 'feriado');
      digitar(fixture, 'Badge do tipo', 'FRD');

      btnAdd(fixture)!.click();
      fixture.detectChanges();

      expect((fixture.debugElement.query(By.css('.error-box')).nativeElement as HTMLElement).textContent)
        .toContain('Já existe um tipo com o nome "Feriado".');
      expect(fixture.debugElement.query(By.css('.chip.pendente'))).toBeNull();
    });

    it('no modo Excluir o clique num chip o marca; o segundo clique desmarca', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'excluir').click();
      fixture.detectChanges();

      const chipFeriado = fixture.debugElement.queryAll(By.css('.lista .chip'))[0].nativeElement as HTMLButtonElement;
      chipFeriado.click();
      fixture.detectChanges();
      expect(chipFeriado.classList.contains('excluir')).toBe(true);
      expect(fixture.debugElement.queryAll(By.css('.chip.excluir'))).toHaveLength(1); // um por vez

      chipFeriado.click();
      fixture.detectChanges();
      expect(fixture.debugElement.queryAll(By.css('.chip.excluir'))).toHaveLength(0);
    });

    it('o Aplicar nasce desabilitado, habilita com o rascunho e volta a travar na troca de radio', () => {
      const fixture = renderizar();
      expect(btnAplicar(fixture)!.disabled).toBe(true); // nenhuma operação escolhida

      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(true); // rascunho vazio

      digitar(fixture, 'Nome do tipo', 'Recesso');
      digitar(fixture, 'Badge do tipo', 'REC');
      btnAdd(fixture)!.click();
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(false);

      radio(fixture, 'escopo', 'INDIVIDUAL').click();
      fixture.detectChanges();
      expect(btnAplicar(fixture)!.disabled).toBe(true); // o rascunho foi descartado
      expect(fixture.debugElement.query(By.css('.chip.pendente'))).toBeNull();
    });

    it('o clique em Aplicar no modo Incluir envia o rascunho', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      digitar(fixture, 'Nome do tipo', 'Recesso');
      digitar(fixture, 'Badge do tipo', 'REC');
      btnAdd(fixture)!.click();
      fixture.detectChanges();

      btnAplicar(fixture)!.click();

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao', {
        tipos: [{ nome: 'Recesso', badge: 'REC', escopo: 'GLOBAL',
          visivel_funcionario: false, conta_folga: false }],
      });
    });

    it('o erro do POST aparece na caixa e os pendentes continuam na tela', () => {
      const fixture = renderizar();
      radio(fixture, 'operacao', 'incluir').click();
      fixture.detectChanges();
      digitar(fixture, 'Nome do tipo', 'Recesso');
      digitar(fixture, 'Badge do tipo', 'REC');
      btnAdd(fixture)!.click();
      fixture.detectChanges();
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'Badge já utilizada.' } })));

      btnAplicar(fixture)!.click();
      fixture.detectChanges();

      expect((fixture.debugElement.query(By.css('.error-box')).nativeElement as HTMLElement).textContent)
        .toContain('Badge já utilizada.');
      expect(chips(fixture)).toContain('Recesso REC✕');
      expect(btnAplicar(fixture)!.disabled).toBe(false); // dá para corrigir e tentar de novo
    });

    it('a falha de carga mostra a caixa com retry, e o clique nela traz o catálogo', () => {
      apiGet.mockReturnValue(throwError(() => ({ error: { message: 'Erro interno do servidor' } })));
      const fixture = renderizar();

      const caixa = fixture.debugElement.query(By.css('app-erro-carga'));
      expect(caixa).not.toBeNull(); // sem isto o modal seria um beco sem saída
      expect((caixa.nativeElement as HTMLElement).textContent)
        .toContain('Erro ao carregar os tipos de ocorrência. (Erro interno do servidor)');
      expect(btnAplicar(fixture)).toBeUndefined(); // o corpo do modal nem existe

      rotearGet();
      (caixa.query(By.css('button')).nativeElement as HTMLButtonElement).click(); // "Tentar novamente"
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('app-erro-carga'))).toBeNull();
      expect(chips(fixture)).toEqual(['Feriado FER', 'Ponto Facultativo PF']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — a confirmação da exclusão: o preview do backend na tela,
  // com o aviso de irreversibilidade
  // ═══════════════════════════════════════════════════════════════════
  describe('render — confirmação da exclusão', () => {
    function renderizar(): ComponentFixture<ConfigurarTiposMarcacaoComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(ConfigurarTiposMarcacaoComponent);
      fixture.detectChanges();
      return fixture;
    }

    /** Marca um tipo e aciona o Aplicar: o preview volta e a confirmação abre. */
    function renderizarConfirmacao(individual: boolean): ComponentFixture<ConfigurarTiposMarcacaoComponent> {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      if (individual) comp.trocarEscopo('INDIVIDUAL');
      comp.trocarOperacao('excluir');
      comp.marcarParaExcluir(comp.tipos().find(t => t.id === (individual ? 't-3' : 't-1'))!);
      comp.aplicar();
      fixture.detectChanges();
      return fixture;
    }

    const btnExcluir = (f: ComponentFixture<ConfigurarTiposMarcacaoComponent>) =>
      f.debugElement.queryAll(By.css('.btn-danger'))[0]?.nativeElement as HTMLButtonElement | undefined;

    it('a confirmação nomeia o tipo, mostra as contagens do preview e avisa que é irreversível', () => {
      const fixture = renderizarConfirmacao(true);

      const titulos = fixture.debugElement.queryAll(By.css('.modal-title'))
        .map(t => (t.nativeElement as HTMLElement).textContent?.trim());
      expect(titulos).toEqual(['Configurar ocorrências', 'Excluir este tipo?']);

      const alvo = (fixture.debugElement.query(By.css('.alvo')).nativeElement as HTMLElement)
        .textContent?.replace(/\s+/g, ' ').trim();
      expect(alvo).toBe('Férias (individual — badge FÉ)');

      const itens = fixture.debugElement.queryAll(By.css('.consequencias li'))
        .map(li => (li.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ').trim());
      expect(itens).toEqual([
        'o tipo, que deixa de aparecer nas ocorrências',
        '12 marcação(ões) feita(s) com ele',
        '2 funcionário(s) afetado(s) (Ana, Bruno)',
      ]);

      expect((fixture.debugElement.query(By.css('.irreversivel')).nativeElement as HTMLElement).textContent?.trim())
        .toBe('Esta ação é irreversível.');
    });

    it('tipo geral: sem linha de funcionários afetados, só as marcações', () => {
      const fixture = renderizarConfirmacao(false);

      const itens = fixture.debugElement.queryAll(By.css('.consequencias li'))
        .map(li => (li.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ').trim());
      expect(itens).toEqual([
        'o tipo, que deixa de aparecer nas ocorrências',
        '3 marcação(ões) feita(s) com ele',
      ]);
      expect((fixture.debugElement.query(By.css('.alvo')).nativeElement as HTMLElement)
        .textContent?.replace(/\s+/g, ' ').trim()).toBe('Feriado (geral — badge FER)');
    });

    it('a falha do preview não abre confirmação nenhuma — o usuário fica no modal com o erro', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;
      comp.trocarOperacao('excluir');
      comp.marcarParaExcluir(comp.tipos()[0]);
      apiGet.mockReturnValue(throwError(() => ({ error: { message: 'Erro interno do servidor' } })));

      comp.aplicar();
      fixture.detectChanges();

      expect(fixture.debugElement.queryAll(By.css('.modal-title'))).toHaveLength(1);
      expect(btnExcluir(fixture)).toBeUndefined();
      expect((fixture.debugElement.query(By.css('.error-box')).nativeElement as HTMLElement).textContent)
        .toContain('Não foi possível concluir a operação.');
    });

    it('"Excluir definitivamente" dispara o DELETE do tipo confirmado', () => {
      const fixture = renderizarConfirmacao(true);

      btnExcluir(fixture)!.click();

      expect(apiDelete).toHaveBeenCalledWith('/api/admin/ponto/tipos-marcacao/t-3');
    });

    it('a recusa 403 fica na caixa de erro DA CONFIRMAÇÃO, que continua na tela', () => {
      const fixture = renderizarConfirmacao(true);
      apiDelete.mockReturnValue(throwError(() => ({
        status: 403,
        error: { message: 'Apenas o administrador master pode excluir tipos.' },
      })));

      btnExcluir(fixture)!.click();
      fixture.detectChanges();

      const caixas = fixture.debugElement.queryAll(By.css('.error-box'))
        .map(c => (c.nativeElement as HTMLElement).textContent?.trim());
      expect(caixas).toEqual(['Apenas o administrador master pode excluir tipos.']);
      expect(fixture.debugElement.queryAll(By.css('.modal-title'))).toHaveLength(2); // a confirmação fica
      expect(btnExcluir(fixture)!.disabled).toBe(false); // e o botão volta a aceitar o retry
    });
  });
});

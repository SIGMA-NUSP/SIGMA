import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Router, RouterLink, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { ToastService } from '../../shared/components/toast.component';
import { AdminPontoComponent } from './admin-ponto.component';

/**
 * T29 — AdminPontoComponent (page — `/admin/ponto`: upload/split/match/publicação das folhas de
 * ponto, mais os cards Retificações e Banco de Horas).
 *
 * ⚠️ **ESCOPO RESTRITO PELO GATE (§ do estágio): só LÓGICA NÃO-VISUAL.** A reforma de layout desta
 * página está decidida e pendente, então o spec não assere disposição/seções/CSS. O que ele trava —
 * e que sobrevive a qualquer reforma — é: o upload (validações, `FormData`, estados, erros), o ciclo
 * dos lotes (carga, acordeão, vínculo de página, publicação), a paginação client-side e os
 * formatadores.
 *
 * Estratégia (manual de PAGE do T22/T23/T24 + padrões do módulo Ponto do T28): TestBed cria o
 * componente SEM `detectChanges()` — `ngOnInit` à mão, filhos (`app-solicitacoes-admin`,
 * `app-grade-retificacoes`) nunca instanciados; `ApiService` mockado despachando por URL (decisão 2
 * do T28 — a página faz 4 GETs distintos pelo mesmo `api.get`); `window.confirm` espionado (o gate
 * destrutivo da publicação continua sendo uma PERGUNTA — toast não substitui pergunta).
 *
 * Sem render, o `@ViewChild('fileInput')` fica `undefined` — o próprio SUT já se protege com
 * `if (this.fileInput)`. Relógio congelado por disciplina do estágio (o SUT não lê `Date`).
 *
 * **Exceções de render, deliberadas** (`renderizarAberto`): o feature-gate do card "Meu Ponto e
 * Banco" (presença de acesso, não disposição) e os controles DESTRUTIVOS da revisão do lote — o
 * `disabled` do `<select>` de vínculo (C7/F50), o VALOR que o `<select>` exibe (C9/F48) e o
 * `disabled` do botão "Publicar lote" (C9/F49). Todos vivem só no template: sem estes testes,
 * apagar um binding deixaria a suíte verde e devolveria o defeito na sua forma pior.
 *
 * **C9 — F48/F49/F51/F57 CORRIGIDOS** (a tela fecha aqui):
 * - `corrige F48` — o `<select>` reverte ao vínculo real quando o PATCH falha, e reescolher a MESMA
 *   pessoa volta a disparar request (não é mais um beco sem saída).
 * - `corrige F49` (frontend) — trava de publicação POR LOTE: a resposta de um lote não destrava o
 *   botão de outro ainda em voo. Com o lock pessimista do C6 (backend), o achado fecha por inteiro.
 * - `corrige F51` — a resposta do PATCH aplica só a página respondida, com recência por página:
 *   nenhuma resposta atrasada desfaz estado mais novo.
 * - `corrige F57` — a frase da recusa do backend (`{ok:false, error:"…"}`) chega ao admin.
 * - Carona autorizada: os `alert()` informativos viraram `ToastService` (o `confirm()` do gate
 *   destrutivo permanece).
 */

interface PaginaFix {
  id: string;
  numero_pagina: number;
  status_match: string;
  pessoa_id?: string;
  pessoa_tipo?: string;
  pessoa_nome?: string;
  nome_extraido?: string;
}

const PESSOAS = [
  { id: 'op-1', nome: 'Maria Souza', tipo: 'OPERADOR' },
  { id: 'op-2', nome: 'João Lima', tipo: 'OPERADOR' },
  { id: 'tec-1', nome: 'Carlos Téc', tipo: 'TECNICO' },
  { id: 'adm-1', nome: 'Ana Admin', tipo: 'ADMINISTRADOR' },
];

const OP1 = { id: 'op-1', tipo: 'OPERADOR', nome: 'Maria Souza' };
const OP2 = { id: 'op-2', tipo: 'OPERADOR', nome: 'João Lima' };
const TEC1 = { id: 'tec-1', tipo: 'TECNICO', nome: 'Carlos Téc' };

/** Lote em revisão (o detalhe traz `paginas`; a listagem, não) — coerente com PAGINAS: 3 páginas, 2 pendentes. */
function lote(over: Record<string, unknown> = {}) {
  return {
    id: 'lote-1',
    tipo: 'MENSAL',
    data_inicio: '2026-06-01',
    data_fim: '2026-06-30',
    status: 'REVISAO' as const,
    total_paginas: 3,
    pendentes: 2,
    criado_em: '2026-07-01',
    ...over,
  };
}

const PAGINAS: PaginaFix[] = [
  { id: 'pag-1', numero_pagina: 1, status_match: 'AUTO', pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR', pessoa_nome: 'Maria Souza', nome_extraido: 'MARIA SOUZA' },
  { id: 'pag-2', numero_pagina: 2, status_match: 'PENDENTE', nome_extraido: 'M4RI4 S0UZ4' },
  { id: 'pag-3', numero_pagina: 3, status_match: 'PENDENTE', nome_extraido: 'C4RL0S T3C' },
];

/** Aplica (ou desfaz) um vínculo num snapshot de páginas — o que o backend gravaria naquele PATCH. */
function comVinculo(base: PaginaFix[], paginaId: string, pessoa: { id: string; tipo: string; nome: string } | null): PaginaFix[] {
  return structuredClone(base).map(p => {
    if (p.id !== paginaId) return p;
    return pessoa
      ? { ...p, pessoa_id: pessoa.id, pessoa_tipo: pessoa.tipo, pessoa_nome: pessoa.nome, status_match: 'MANUAL' }
      : { id: p.id, numero_pagina: p.numero_pagina, nome_extraido: p.nome_extraido, status_match: 'PENDENTE' };
  });
}

/**
 * Resposta de `/lote/{id}` (GET) e do PATCH de vínculo: o backend devolve o **LOTE INTEIRO**
 * (`PontoService.detalheLote`) — todas as páginas + `pendentes` contado por `status_match=PENDENTE`.
 * É esse shape que torna honesto o teste do F51: o SUT recebe o lote todo e deve aplicar só a página
 * que ele mesmo alterou.
 */
function respostaLote(paginas: PaginaFix[], over: Record<string, unknown> = {}) {
  return {
    ok: true,
    data: {
      ...lote(over),
      paginas: structuredClone(paginas),
      pendentes: paginas.filter(p => p.status_match === 'PENDENTE').length,
    },
  };
}

/**
 * Preview da exclusão (F59) — o shape REAL do backend (`PontoExclusaoService.preview`). É ele que o
 * modal renderiza: nada de texto genérico, porque o que morre muda de item para item.
 */
function preview(over: Record<string, unknown> = {}) {
  return {
    ok: true,
    data: {
      escopo: 'LOTE',
      lote: { id: 'lote-1', tipo: 'MENSAL', data_inicio: '2026-06-01', data_fim: '2026-06-30', status: 'PUBLICADO', publicado: true },
      pagina: null,
      pessoas: [
        { pessoa_id: 'op-1', nome: 'Maria Souza', tipo: 'OPERADOR', retificacoes_excluidas: 2, reancora: 'volta para a folha 01/05/2026 a 31/05/2026' },
        { pessoa_id: 'op-2', nome: 'João Lima', tipo: 'OPERADOR', retificacoes_excluidas: 0, reancora: 'fica sem folha oficial — abertura 0' },
      ],
      paginas_excluidas: 3,
      retificacoes_excluidas: 2,
      avisos_destinatarios: ['João Lima', 'Maria Souza'],
      avisos_removidos: 2,
      reabre_competencia: '06/2026',
      arquivos: 4,
      ...over,
    },
  };
}

describe('AdminPontoComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiPatch: ReturnType<typeof vi.fn>;
  let apiPostForm: ReturnType<typeof vi.fn>;
  let apiGetBlob: ReturnType<typeof vi.fn>;
  let apiDelete: ReturnType<typeof vi.fn>;
  let abrirBlobInline: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let confirmSpy: ReturnType<typeof vi.spyOn>;

  const PDF = new Blob(['%PDF-1.4'], { type: 'application/pdf' });

  /** Lista de lotes devolvida pelo GET /lotes (recriada a cada chamada — o SUT muta os objetos). */
  let lotesResposta: () => any[];
  /** A flag `pode_excluir` do envelope da listagem (F59) — quem manda no X é o backend. */
  let podeExcluirResposta: boolean;
  /** Resposta do GET de preview da exclusão. */
  let previewResposta: () => any;

  /** O ApiService mockado — um só objeto, usado também pelos blocos que recriam o TestBed. */
  function apiMock() {
    return {
      get: apiGet, post: apiPost, patch: apiPatch, postForm: apiPostForm,
      getBlob: apiGetBlob, delete: apiDelete, abrirBlobInline,
    };
  }

  beforeEach(async () => {
    lotesResposta = () => [lote()];
    podeExcluirResposta = false;
    previewResposta = () => preview();

    apiGet = vi.fn().mockImplementation((url: string) => {
      if (url === '/api/admin/ponto/pessoas') return of({ ok: true, data: structuredClone(PESSOAS) });
      if (url === '/api/admin/ponto/lotes') {
        return of({ ok: true, data: lotesResposta(), pode_excluir: podeExcluirResposta });
      }
      // Antes do startsWith do detalhe: as duas rotas partilham o prefixo /lote/{id}.
      if (url.includes('/exclusao/preview')) return of(previewResposta());
      if (url.startsWith('/api/admin/ponto/lote/')) return of(respostaLote(PAGINAS));
      return of({ ok: true, data: null });
    });
    // o POST de publicar também devolve `detalheLote` (lote inteiro) — PontoService:288
    apiPost = vi.fn().mockReturnValue(of(respostaLote(PAGINAS, { status: 'PUBLICADO', publicado_em: '2026-07-12' })));
    apiPatch = vi.fn().mockReturnValue(of(respostaLote(comVinculo(PAGINAS, 'pag-2', TEC1))));
    apiPostForm = vi.fn().mockReturnValue(of(respostaLote(PAGINAS)));
    apiGetBlob = vi.fn().mockReturnValue(of(PDF));
    apiDelete = vi.fn().mockReturnValue(of({ ok: true, data: { escopo: 'LOTE', paginas_excluidas: 3 } }));
    abrirBlobInline = vi.fn();
    toastError = vi.fn();
    toastSuccess = vi.fn();

    await TestBed.configureTestingModule({
      imports: [AdminPontoComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink (não instanciado sem render, mas o DI fica coerente)
        { provide: ApiService, useValue: apiMock() },
        { provide: AuthService, useValue: { temFolhaPonto: signal(false) } },
        { provide: ToastService, useValue: { error: toastError, success: toastSuccess } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function criar(): AdminPontoComponent {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    return TestBed.createComponent(AdminPontoComponent).componentInstance;
  }

  /** Componente com pessoas e lotes já carregados (ngOnInit à mão — sem render). */
  function criarCarregado(): AdminPontoComponent {
    const comp = criar();
    comp.ngOnInit();
    return comp;
  }

  /** Componente carregado com o 1º lote já expandido (as páginas na mão — é onde o vínculo vive). */
  function criarComLoteAberto(): { comp: AdminPontoComponent; l: any } {
    const comp = criarCarregado();
    const l: any = comp.lotes()[0];
    comp.toggleLote(l);
    return { comp, l };
  }

  /** `File` de PDF para o input de upload. */
  function pdf(nome = 'folhas-junho.pdf'): File {
    return new File(['%PDF-1.4'], nome, { type: 'application/pdf' });
  }

  /** Evento de `<input type="file">` com os arquivos informados. */
  function fileEvent(...files: File[]): Event {
    return { target: { files } } as unknown as Event;
  }

  /** Preenche o formulário de upload com um cenário válido. */
  function preencherUpload(comp: AdminPontoComponent, arquivo: File | null = pdf()): void {
    comp.onFileSelect(fileEvent(...(arquivo ? [arquivo] : [])));
    comp.dataInicio = '2026-06-01';
    comp.dataFim = '2026-06-30';
  }

  // ═══════════════════════════════════════════════════════════════════
  // Carga inicial: pessoas (selects de vínculo) + lotes
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('carrega pessoas e lotes', () => {
      const comp = criarCarregado();
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/pessoas');
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lotes');
      expect(comp.pessoas()).toHaveLength(4);
      expect(comp.lotes()).toHaveLength(1);
      expect(comp.loadingLotes()).toBe(false);
    });

    it('as pessoas são separadas por tipo para os optgroups do select', () => {
      const comp = criarCarregado();
      expect(comp.operadores().map(p => p.id)).toEqual(['op-1', 'op-2']);
      expect(comp.tecnicos().map(p => p.id)).toEqual(['tec-1']);
      expect(comp.administradores().map(p => p.id)).toEqual(['adm-1']);
    });

    it('corrige F50 — erro ao carregar pessoas é SINALIZADO e bloqueia o vínculo (não convida à desvinculação)', () => {
      // C7 (F50): antes, o handler descartava a lista e não avisava ninguém — os selects de vínculo
      // ficavam sem nenhuma opção, e uma página já vinculada exibia valor sem `<option>`
      // correspondente (select em branco), convidando o admin a "corrigir" escolhendo a única opção
      // restante ("— pendente —") → PATCH que DESVINCULA uma página que estava certa. Agora a falha
      // tem canal próprio (caixa com retry) e o `<select>` fica desabilitado enquanto durar.
      // Carrega com sucesso primeiro: assim o `toEqual([])` prova o RESET (o signal já nasce []).
      const comp = criarCarregado();
      expect(comp.pessoas()).toHaveLength(4);
      expect(comp.vinculoBloqueado()).toBe(false);

      apiGet.mockImplementation(() => throwError(() => ({ status: 500, error: { message: 'Falha ao listar pessoas.' } })));
      comp.loadPessoas();

      expect(comp.pessoas()).toEqual([]);
      expect(comp.operadores()).toEqual([]);
      expect(comp.erroPessoas()).toContain('Falha ao listar pessoas.');   // guia da tela + detalhe
      expect(comp.vinculoBloqueado()).toBe(true);                    // <select> desabilitado
    });

    it('corrige F50 — erro de pessoas sem mensagem do backend cai no fallback', () => {
      apiGet.mockImplementation(() => throwError(() => ({ status: 503 })));
      const comp = criarCarregado();
      expect(comp.erroPessoas()).toBe(
        'Não foi possível carregar a lista de pessoas. O vínculo das páginas fica indisponível até recarregar.');
    });

    it('corrige F50 — o retry das pessoas re-dispara a carga, limpa o erro e destrava o vínculo', () => {
      apiGet.mockImplementation(() => throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      expect(comp.vinculoBloqueado()).toBe(true);

      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/pessoas' ? of({ ok: true, data: structuredClone(PESSOAS) }) : of({ ok: true, data: [] }));
      comp.loadPessoas();                        // botão "Tentar novamente" da caixa

      expect(comp.erroPessoas()).toBe('');
      expect(comp.pessoas()).toHaveLength(4);
      expect(comp.vinculoBloqueado()).toBe(false);
    });

    it('corrige F50 — erro ao carregar lotes é SINALIZADO (a tela não afirma "nenhum lote enviado")', () => {
      // Sem sinal, "Nenhum lote enviado ainda." induzia ao reenvio de um PDF já processado (lote duplicado).
      const comp = criarCarregado();
      expect(comp.lotes()).toHaveLength(1);

      apiGet.mockImplementation(() => throwError(() => ({ status: 503, error: { message: 'Serviço indisponível.' } })));
      comp.loadLotes();

      expect(comp.lotes()).toEqual([]);          // prova o reset (não o valor inicial)
      expect(comp.loadingLotes()).toBe(false);
      expect(comp.erroLotes()).toContain('Serviço indisponível.');
    });

    it('corrige F50 — erro de lotes sem mensagem do backend adverte contra o reenvio', () => {
      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/lotes' ? throwError(() => ({ status: 500 })) : of({ ok: true, data: [] }));
      const comp = criarCarregado();
      expect(comp.erroLotes()).toBe(
        'Não foi possível carregar os lotes enviados. '
        + 'Não reenvie o PDF antes de recarregar — o lote pode já ter sido processado.');
    });

    it('corrige F50 — o retry dos lotes limpa o erro e repovoa a lista', () => {
      apiGet.mockImplementation(() => throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      expect(comp.erroLotes()).not.toBe('');

      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/lotes' ? of({ ok: true, data: lotesResposta() }) : of({ ok: true, data: [] }));
      comp.loadLotes();                          // botão "Tentar novamente" da caixa

      expect(comp.erroLotes()).toBe('');
      expect(comp.lotes()).toHaveLength(1);
      expect(comp.loadingLotes()).toBe(false);
    });

    it('lista vazia de verdade NÃO liga nenhum canal de erro (vazio ≠ falha)', () => {
      apiGet.mockReturnValue(of({ ok: true, data: [] }));
      const comp = criarCarregado();
      expect(comp.erroPessoas()).toBe('');
      expect(comp.erroLotes()).toBe('');
      expect(comp.vinculoBloqueado()).toBe(false);   // sem pessoas, mas sem erro → select segue utilizável
    });

    it('payload sem `data` vira lista vazia', () => {
      apiGet.mockReturnValue(of({ ok: true }));
      const comp = criarCarregado();
      expect(comp.pessoas()).toEqual([]);
      expect(comp.lotes()).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Paginação client-side dos lotes (ClientPager sobre o signal `lotes`)
  // ═══════════════════════════════════════════════════════════════════
  describe('paginação dos lotes', () => {
    it('recorta 10 por página e conta o total', () => {
      lotesResposta = () => Array.from({ length: 12 }, (_, i) => lote({ id: `lote-${i + 1}` }));
      const comp = criarCarregado();
      const pager = (comp as any).lotesPager;
      expect(pager.rows()).toHaveLength(10);
      expect(pager.meta()).toEqual({ page: 1, limit: 10, total: 12, pages: 2 });
      pager.onPage(2);
      expect(pager.rows().map((l: any) => l.id)).toEqual(['lote-11', 'lote-12']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Upload (POST multipart /api/admin/ponto/upload)
  // ═══════════════════════════════════════════════════════════════════
  describe('onFileSelect', () => {
    it('guarda o arquivo escolhido', () => {
      const comp = criarCarregado();
      const f = pdf();
      comp.onFileSelect(fileEvent(f));
      expect(comp.arquivo).toBe(f);
    });

    it('input esvaziado volta a arquivo nulo', () => {
      const comp = criarCarregado();
      comp.onFileSelect(fileEvent(pdf()));
      comp.onFileSelect(fileEvent());
      expect(comp.arquivo).toBeNull();
    });
  });

  describe('onUpload — validações client-side', () => {
    it('sem arquivo: erro e nenhum POST', () => {
      const comp = criarCarregado();
      comp.dataInicio = '2026-06-01';
      comp.dataFim = '2026-06-30';
      comp.onUpload();
      expect(comp.errorMsg()).toBe('Selecione o arquivo PDF.');
      expect(apiPostForm).not.toHaveBeenCalled();
      expect(comp.uploading()).toBe(false);
    });

    it('sem período: erro e nenhum POST', () => {
      const comp = criarCarregado();
      comp.onFileSelect(fileEvent(pdf()));
      comp.dataInicio = '';
      comp.dataFim = '2026-06-30';
      comp.onUpload();
      expect(comp.errorMsg()).toBe('Informe o início e o fim do período.');
      expect(apiPostForm).not.toHaveBeenCalled();
    });

    it('fim anterior ao início: erro e nenhum POST', () => {
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.dataInicio = '2026-06-30';
      comp.dataFim = '2026-06-01';
      comp.onUpload();
      expect(comp.errorMsg()).toBe('A data final não pode ser anterior à inicial.');
      expect(apiPostForm).not.toHaveBeenCalled();
    });

    it('início igual ao fim é válido (folha de um único dia)', () => {
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.dataInicio = comp.dataFim = '2026-06-15';
      comp.onUpload();
      expect(apiPostForm).toHaveBeenCalled();
      expect(comp.errorMsg()).toBe('');
    });
  });

  describe('onUpload — envio', () => {
    it('monta o multipart com arquivo, tipo e período; mantém "uploading" durante o voo', () => {
      const emVoo = new Subject<any>();
      apiPostForm.mockReturnValue(emVoo);
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.tipo = 'SEMANAL';

      comp.onUpload();

      expect(comp.uploading()).toBe(true);
      const [url, fd] = apiPostForm.mock.calls[0];
      expect(url).toBe('/api/admin/ponto/upload');
      expect(fd).toBeInstanceOf(FormData);
      expect((fd as FormData).get('arquivo')).toBeInstanceOf(File);
      expect((fd as FormData).get('tipo')).toBe('SEMANAL');
      expect((fd as FormData).get('data_inicio')).toBe('2026-06-01');
      expect((fd as FormData).get('data_fim')).toBe('2026-06-30');

      emVoo.next({ ok: true, data: lote() });
      emVoo.complete();
      expect(comp.uploading()).toBe(false);
    });

    it('sucesso: limpa o arquivo e recarrega a lista já abrindo o lote enviado', () => {
      const comp = criarCarregado();
      preencherUpload(comp);
      apiGet.mockClear();

      comp.onUpload();

      expect(comp.arquivo).toBeNull();
      expect(comp.uploading()).toBe(false);
      expect(comp.errorMsg()).toBe('');
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lotes');   // recarga
      const l: any = comp.lotes()[0];
      expect(l._exp).toBe(true);                                        // o lote novo já vem expandido
      expect(l.paginas).toHaveLength(3);                                // com o detalhe do upload mesclado
    });

    it('o envio traz a paginação de volta para a 1ª página (onde o lote novo aparece — ordem desc)', () => {
      // Com 12 lotes o pager tem 2 páginas: só assim a volta para a página 1 é observável (com um
      // único lote o `meta().page` já é 1 e a asserção não distinguiria a ausência do `onPage(1)`).
      lotesResposta = () => Array.from({ length: 12 }, (_, i) => lote({ id: `lote-${i + 1}` }));
      const comp = criarCarregado();
      const pager = (comp as any).lotesPager;
      pager.onPage(2);
      expect(pager.meta().page).toBe(2);

      apiPostForm.mockReturnValue(of(respostaLote(PAGINAS, { id: 'lote-1' })));
      preencherUpload(comp);
      comp.onUpload();

      expect(pager.meta().page).toBe(1);                                        // voltou para a 1ª
      expect(pager.rows().some((l: any) => l.id === 'lote-1' && l._exp)).toBe(true);   // e o lote novo está visível e aberto
    });

    it('o lote recém-enviado que não volta na listagem não expande nada (caracterização)', () => {
      apiPostForm.mockReturnValue(of({ ok: true, data: { ...lote({ id: 'lote-outro' }), paginas: [] } }));
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.onUpload();
      expect(comp.lotes().every((l: any) => !l._exp)).toBe(true);
      expect(comp.errorMsg()).toBe('');
    });

    it('resposta ok:false: mensagem do backend na caixa de erro, sem recarga', () => {
      apiPostForm.mockReturnValue(of({ ok: false, error: 'PDF ilegível.' }));
      const comp = criarCarregado();
      preencherUpload(comp);
      apiGet.mockClear();

      comp.onUpload();

      expect(comp.errorMsg()).toBe('PDF ilegível.');
      expect(comp.arquivo).not.toBeNull();     // o arquivo continua no formulário
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('resposta ok:false sem texto cai no fallback', () => {
      apiPostForm.mockReturnValue(of({ ok: false }));
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.onUpload();
      expect(comp.errorMsg()).toBe('Erro ao processar o PDF.');
    });

    it('erro HTTP: mensagem do backend e trava liberada', () => {
      apiPostForm.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'Arquivo não é um PDF.' } })));
      const comp = criarCarregado();
      preencherUpload(comp);

      comp.onUpload();

      expect(comp.errorMsg()).toBe('Arquivo não é um PDF.');
      expect(comp.uploading()).toBe(false);
      expect(comp.arquivo).not.toBeNull();
    });

    it('erro HTTP sem corpo cai no fallback', () => {
      apiPostForm.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      preencherUpload(comp);
      comp.onUpload();
      expect(comp.errorMsg()).toBe('Erro ao processar o PDF.');
    });

    it('um novo envio limpa o erro anterior', () => {
      const comp = criarCarregado();
      comp.errorMsg.set('erro velho');
      preencherUpload(comp);
      comp.onUpload();
      expect(comp.errorMsg()).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Acordeão do lote (GET do detalhe no 1º clique)
  // ═══════════════════════════════════════════════════════════════════
  describe('toggleLote', () => {
    it('abre e busca as páginas no primeiro clique', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      apiGet.mockClear();

      comp.toggleLote(l);

      expect(l._exp).toBe(true);
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1');
      expect(l.paginas).toHaveLength(3);
    });

    it('fechar não refaz o GET', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      comp.toggleLote(l);
      apiGet.mockClear();

      comp.toggleLote(l);

      expect(l._exp).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('reabrir usa as páginas já carregadas (sem novo GET)', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      comp.toggleLote(l);
      comp.toggleLote(l);
      apiGet.mockClear();

      comp.toggleLote(l);

      expect(l._exp).toBe(true);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('erro no detalhe: fecha de volta e o toast traz a guia da tela + a mensagem do backend', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      apiGet.mockImplementation(() => throwError(() => ({ status: 404, error: { message: 'Lote não encontrado.' } })));

      comp.toggleLote(l);

      expect(l._exp).toBe(false);
      expect(l.paginas).toBeUndefined();
      expect(toastError).toHaveBeenCalledWith('Não foi possível abrir o lote. (Lote não encontrado.)');
    });

    it('erro sem corpo cai na guia da tela', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      apiGet.mockImplementation(() => throwError(() => ({ status: 500 })));
      comp.toggleLote(l);
      expect(toastError).toHaveBeenCalledWith('Não foi possível abrir o lote.');
    });

    it('a recusa no shape real do backend ({ok:false, error}) também chega ao toast', () => {
      // Mesmo defeito do F57 (lista fixa `['message']`), aqui no GET do detalhe: sem os dois campos,
      // toda recusa do backend virava o fallback genérico.
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      apiGet.mockImplementation(() => throwError(() => ({ status: 404, error: { ok: false, error: 'Lote não encontrado.' } })));

      comp.toggleLote(l);

      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Lote não encontrado.'));
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Vínculo página → pessoa (PATCH .../lote/{id}/pagina/{id})
  // A máquina de estado do C9: valor exibido (F48) + recência por página (F51)
  // ═══════════════════════════════════════════════════════════════════
  describe('valorPessoa', () => {
    it('página vinculada vira "TIPO:id" (valor do <option>)', () => {
      const comp = criar();
      expect(comp.valorPessoa({ pessoa_id: 'op-1', pessoa_tipo: 'OPERADOR' } as any)).toBe('OPERADOR:op-1');
    });

    it('página pendente vira string vazia (opção "— pendente —")', () => {
      const comp = criar();
      expect(comp.valorPessoa({ id: 'pag-2' } as any)).toBe('');
    });

    it('corrige F48 — durante o PATCH exibe a ESCOLHA; quando ele falha, VOLTA ao vínculo salvo', () => {
      // O select tinha como fonte de verdade o objeto da página (que só muda com a resposta do
      // servidor): no erro, o valor bindado não mudava, o Angular não via mudança de input e o DOM
      // seguia exibindo a pessoa escolhida — um vínculo inexistente. Agora o valor sobe para a
      // escolha no disparo e cai de volta na resposta (o render deste mesmo achado prova no DOM).
      const emVoo = new Subject<any>();
      apiPatch.mockReturnValue(emVoo);
      const { comp, l } = criarComLoteAberto();
      const pag1 = l.paginas[0];                                  // já vinculada ao op-1 (match AUTO)

      comp.onAssign(l, pag1, 'OPERADOR:op-2');                    // o admin troca a pessoa
      expect(comp.valorPessoa(pag1)).toBe('OPERADOR:op-2');       // otimista: o select mostra a escolha

      emVoo.error({ status: 401 });                               // sessão expirada (o interceptor não desloga)

      expect(comp.valorPessoa(pag1)).toBe('OPERADOR:op-1');       // ← reverte ao que o servidor tem
      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Não foi possível vincular a página'));
    });

    it('corrige F48 — no sucesso o valor volta a derivar do SERVIDOR, não da escolha', () => {
      // Discrimina de propósito: o backend responde com uma pessoa DIFERENTE da escolhida (é o que
      // acontece quando outra aba mexeu no lote no meio do caminho). Se o valor escolhido ficasse
      // "pinado" no `valorEmVoo`, o select mentiria para sempre — e reescolher a mesma pessoa não
      // emitiria `change`: o beco sem saída do F48 de volta, agora pelo caminho do SUCESSO.
      apiPatch.mockReturnValue(of(respostaLote(comVinculo(PAGINAS, 'pag-2', OP1))));
      const { comp, l } = criarComLoteAberto();

      comp.onAssign(l, l.paginas[1], 'TECNICO:tec-1');            // o admin escolhe o técnico...

      expect(l.paginas[1].pessoa_id).toBe('op-1');                // ...e o servidor diz que é a Maria
      expect(comp.valorPessoa(l.paginas[1])).toBe('OPERADOR:op-1');   // o select segue o servidor
    });
  });

  describe('onAssign', () => {
    it('vincula: quebra "TIPO:id" no primeiro ":" e envia tipo + id', () => {
      const { comp, l } = criarComLoteAberto();

      comp.onAssign(l, l.paginas[1], 'TECNICO:tec-1');

      expect(apiPatch).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/pagina/pag-2',
        { pessoa_tipo: 'TECNICO', pessoa_id: 'tec-1' });
      expect(l.paginas[1].status_match).toBe('MANUAL');
      expect(l.pendentes).toBe(1);            // pag-3 ainda pendente — contador derivado das páginas em tela
      expect(toastError).not.toHaveBeenCalled();
    });

    it('desvincular ("— pendente —") LIMPA o vínculo na tela — o payload do backend OMITE as chaves nulas', () => {
      // ⚠️ O backend serializa com `spring.jackson.default-property-inclusion: non_null`: a página
      // desvinculada volta SEM `pessoa_id`/`pessoa_tipo`/`pessoa_nome`. Aplicar a resposta com um
      // `Object.assign` (mesclar) deixaria o vínculo antigo intacto no objeto local — e o `<select>`
      // voltaria a exibir a pessoa que o admin acabou de remover, sobre um servidor que já desvinculou.
      // Por isso a página é SUBSTITUÍDA. (Regressão pega na revisão adversarial do C9.)
      apiPatch.mockReturnValue(of(respostaLote(comVinculo(PAGINAS, 'pag-1', null))));
      const { comp, l } = criarComLoteAberto();

      comp.onAssign(l, l.paginas[0], '');

      expect(apiPatch).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/pagina/pag-1',
        { pessoa_id: null, pessoa_tipo: null });
      expect(l.paginas[0].status_match).toBe('PENDENTE');
      expect(l.paginas[0].pessoa_id).toBeUndefined();             // ← o resíduo do vínculo NÃO sobrevive
      expect(l.paginas[0].pessoa_nome).toBeUndefined();
      expect(comp.valorPessoa(l.paginas[0])).toBe('');            // e o select mostra "— pendente —"
      expect(l.pendentes).toBe(3);
    });

    it('erro: toast com a guia da tela + a mensagem do backend, e recarga do detalhe do lote', () => {
      apiPatch.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Página já vinculada.' } })));
      const { comp, l } = criarComLoteAberto();
      apiGet.mockClear();

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-2');

      expect(toastError).toHaveBeenCalledWith(
        'Não foi possível vincular a página — a escolha foi desfeita. (Página já vinculada.)');
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1');   // recarregarLote
      expect(l.paginas).toHaveLength(3);
    });

    it('erro 500: a GUIA da tela vem na frente (o corpo genérico do backend não substitui o contexto)', () => {
      // O `GlobalExceptionHandler` responde `{ok:false, error:"Erro interno do servidor"}` em TODO 500.
      // Com o helper cru (que prioriza o corpo), o admin leria só "Erro interno do servidor" — sem saber
      // que a escolha dele foi desfeita. É a mesma lição do C7 (`erroCargaMsg`).
      apiPatch.mockReturnValue(throwError(() => ({ status: 500, error: { ok: false, error: 'Erro interno do servidor' } })));
      const { comp, l } = criarComLoteAberto();

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-2');

      expect(toastError).toHaveBeenCalledWith(
        'Não foi possível vincular a página — a escolha foi desfeita. (Erro interno do servidor)');
    });

    it('corrige F48 (faceta recarregarLote) — a falha do GET de recuperação é SINALIZADA, sem unhandled', () => {
      // C7 (F48, faceta silenciosa): `recarregarLote` — o GET disparado quando o PATCH falha — não
      // tinha handler de erro. Se ele também falhasse (a rede caiu: as duas chamadas falham juntas),
      // nada era restaurado, nada avisava o admin e o erro subia como unhandled rejection.
      apiPatch.mockReturnValue(throwError(() => ({ status: 502 })));
      const { comp, l } = criarComLoteAberto();
      apiGet.mockImplementation(() => throwError(() => ({ status: 502, error: { message: 'Bad gateway' } })));

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-2');

      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Não foi possível vincular a página'));   // o PATCH
      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Bad gateway'));                          // a recuperação
      // O "sem unhandled" não é asserido aqui (o RxJS reporta fora do stack): quem o prova é o
      // PRÓPRIO Vitest — removendo o handler de `recarregarLote`, o run acusa "caught 2 unhandled
      // errors" e falha. Verificado por mutação no C7.
    });

    it('corrige F48 (faceta recarregarLote) — sem mensagem do backend, o toast adverte que a tela ficou desatualizada', () => {
      apiPatch.mockReturnValue(throwError(() => ({ status: 500 })));
      const { comp, l } = criarComLoteAberto();
      apiGet.mockImplementation(() => throwError(() => ({ status: 500 })));

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-2');

      expect(toastError).toHaveBeenCalledWith(
        'Não foi possível recarregar o lote. '
        + 'Os vínculos exibidos podem estar desatualizados — recarregue a página.');   // sem corpo → só a guia
    });

    it('corrige F51 — a resposta atrasada de uma página NÃO desfaz o vínculo já salvo de outra', () => {
      // Cada PATCH devolve o LOTE INTEIRO, e o `Object.assign(l, data)` fazia vencer a última
      // resposta a CHEGAR (não a mais nova): o snapshot atrasado da 1ª página revertia a 2ª — a
      // página voltava a "— pendente —" e o contador subia. Como `toggleLote` só busca quando
      // `!l.paginas`, recolher/reexpandir reexibia o cache errado: a tela mentia até sair da rota.
      const patchPag2 = new Subject<any>();
      const patchPag3 = new Subject<any>();
      apiPatch.mockReturnValueOnce(patchPag2).mockReturnValueOnce(patchPag3);
      const { comp, l } = criarComLoteAberto();

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-1');   // vincula a pag-2
      comp.onAssign(l, l.paginas[2], 'TECNICO:tec-1');   // vincula a pag-3, antes de a 1ª responder

      // a 2ª responde primeiro, com o servidor já contendo as DUAS vinculações
      const comAsDuas = comVinculo(comVinculo(PAGINAS, 'pag-2', OP1), 'pag-3', TEC1);
      patchPag3.next(respostaLote(comAsDuas));
      expect(l.paginas[2].pessoa_id).toBe('tec-1');

      // e a 1ª chega atrasada, com o snapshot de ANTES (pag-3 ainda pendente)
      patchPag2.next(respostaLote(comVinculo(PAGINAS, 'pag-2', OP1)));

      expect(l.paginas[1].pessoa_id).toBe('op-1');      // aplica a SUA página...
      expect(l.paginas[2].pessoa_id).toBe('tec-1');     // ...e NÃO desfaz a outra (era o defeito)
      expect(l.paginas[2].status_match).toBe('MANUAL');
      expect(l.pendentes).toBe(0);                      // o contador também não volta atrás
    });

    it('corrige F51 — dois PATCHes da MESMA página fora de ordem: vence o mais novo, não o último a chegar', () => {
      const primeiro = new Subject<any>();
      const segundo = new Subject<any>();
      apiPatch.mockReturnValueOnce(primeiro).mockReturnValueOnce(segundo);
      const { comp, l } = criarComLoteAberto();
      const pag2 = () => l.paginas[1];   // relido do array: a resposta SUBSTITUI o objeto da página

      comp.onAssign(l, pag2(), 'OPERADOR:op-1');         // escolhe a Maria...
      comp.onAssign(l, pag2(), 'OPERADOR:op-2');         // ...corrige para o João, antes de a 1ª responder

      segundo.next(respostaLote(comVinculo(PAGINAS, 'pag-2', OP2)));
      expect(pag2().pessoa_id).toBe('op-2');

      primeiro.next(respostaLote(comVinculo(PAGINAS, 'pag-2', OP1)));   // a resposta velha chega por último

      expect(pag2().pessoa_id).toBe('op-2');                     // descartada por recência
      expect(comp.valorPessoa(pag2())).toBe('OPERADOR:op-2');    // e o select segue exibindo o vínculo real
    });

    it('corrige F51 — a resposta obsoleta também não devolve o select ao estado velho no ERRO', () => {
      // A 1ª tentativa falha DEPOIS de a 2ª ter sido disparada: sem recência, o handler de erro da
      // velha reverteria o select (e recarregaria o lote) por cima de um PATCH que ainda está em voo.
      const primeiro = new Subject<any>();
      const segundo = new Subject<any>();
      apiPatch.mockReturnValueOnce(primeiro).mockReturnValueOnce(segundo);
      const { comp, l } = criarComLoteAberto();
      const pag2 = () => l.paginas[1];

      comp.onAssign(l, pag2(), 'OPERADOR:op-1');
      comp.onAssign(l, pag2(), 'OPERADOR:op-2');
      apiGet.mockClear();

      primeiro.error({ status: 502 });                          // a velha falha (a nova ainda voa)

      expect(comp.valorPessoa(pag2())).toBe('OPERADOR:op-2');   // o select continua na escolha em voo
      expect(toastError).not.toHaveBeenCalled();
      expect(apiGet).not.toHaveBeenCalled();                    // nem recarrega o lote por conta dela
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O OUTRO lado do F51: o GET do detalhe (recarregarLote/toggleLote) também devolve o lote
  // inteiro — e pode ter lido o banco ANTES do commit de uma escrita que já chegou à tela.
  // ═══════════════════════════════════════════════════════════════════
  describe('snapshot obsoleto do GET de detalhe', () => {
    it('corrige F51 — o GET atrasado NÃO desfaz o vínculo que um PATCH mais novo já salvou', () => {
      // (1) o PATCH da pag-3 falha → dispara o GET de recuperação, que sai com o servidor ainda sem
      // nada da pag-2; (2) o admin vincula a pag-2 e ESSE PATCH responde OK; (3) o GET chega por
      // último, com o snapshot velho. Sem descarte, `Object.assign` devolvia a pag-2 a "— pendente —".
      const patchPag3 = new Subject<any>();
      const getRecuperacao = new Subject<any>();
      apiPatch.mockReturnValueOnce(patchPag3);
      const { comp, l } = criarComLoteAberto();

      apiGet.mockImplementation((url: string) =>
        url.startsWith('/api/admin/ponto/lote/') ? getRecuperacao : of({ ok: true, data: [] }));
      comp.onAssign(l, l.paginas[2], 'TECNICO:tec-1');
      patchPag3.error({ status: 502 });                                  // → recarregarLote (GET em voo)

      apiPatch.mockReturnValue(of(respostaLote(comVinculo(PAGINAS, 'pag-2', OP1))));
      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-1');                   // este PATCH responde na hora
      expect(l.paginas[1].pessoa_id).toBe('op-1');
      expect(l.pendentes).toBe(1);

      getRecuperacao.next(respostaLote(PAGINAS));                        // o snapshot de ANTES chega por último

      expect(l.paginas[1].pessoa_id).toBe('op-1');                       // ← o vínculo salvo continua na tela
      expect(l.pendentes).toBe(1);                                       // e o contador não volta atrás
    });

    it('corrige F51 — o GET atrasado NÃO ressuscita o botão "Publicar" de um lote já publicado', () => {
      const getRecuperacao = new Subject<any>();
      apiPatch.mockReturnValue(throwError(() => ({ status: 400 })));
      const { comp, l } = criarComLoteAberto();

      apiGet.mockImplementation((url: string) =>
        url.startsWith('/api/admin/ponto/lote/') ? getRecuperacao : of({ ok: true, data: [] }));
      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-1');                   // PATCH recusado → GET em voo
      comp.publicar(l);                                                  // e o admin publica assim mesmo
      expect(l.status).toBe('PUBLICADO');

      getRecuperacao.next(respostaLote(PAGINAS));                        // o snapshot velho (REVISAO) chega depois

      expect(l.status).toBe('PUBLICADO');   // ← não volta a "Em revisão" (com o botão destrutivo de volta)
    });

    it('sem escrita no meio, o GET de recuperação é aplicado normalmente (o descarte não é cego)', () => {
      apiPatch.mockReturnValue(throwError(() => ({ status: 502 })));
      const { comp, l } = criarComLoteAberto();

      apiGet.mockImplementation((url: string) =>
        url.startsWith('/api/admin/ponto/lote/')
          ? of(respostaLote(comVinculo(PAGINAS, 'pag-2', OP2)))          // o servidor tinha OUTRO estado
          : of({ ok: true, data: [] }));

      comp.onAssign(l, l.paginas[1], 'OPERADOR:op-1');                   // falha → recarregarLote

      expect(l.paginas[1].pessoa_id).toBe('op-2');                       // a tela se corrige com o servidor
      expect(l.pendentes).toBe(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Cargas da tela: retry clicado duas vezes (o botão não trava)
  // ═══════════════════════════════════════════════════════════════════
  describe('recência das cargas (pessoas e lotes)', () => {
    it('a falha ATRASADA da 1ª carga não apaga a lista que o retry já trouxe', () => {
      const primeira = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/lotes' ? primeira : of({ ok: true, data: [] }));
      const comp = criarCarregado();

      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/lotes' ? of({ ok: true, data: lotesResposta() }) : of({ ok: true, data: [] }));
      comp.loadLotes();                                   // 2º clique no "Tentar novamente"
      expect(comp.lotes()).toHaveLength(1);

      primeira.error({ status: 500 });                    // a falha da 1ª chega por último

      expect(comp.lotes()).toHaveLength(1);               // ← a lista boa continua na tela
      expect(comp.erroLotes()).toBe('');                  // e nenhum erro falso é ligado
      expect(comp.loadingLotes()).toBe(false);
    });

    it('a falha ATRASADA da carga de pessoas não bloqueia o vínculo que o retry já destravou', () => {
      const primeira = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/pessoas' ? primeira : of({ ok: true, data: [] }));
      const comp = criarCarregado();

      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/pessoas' ? of({ ok: true, data: structuredClone(PESSOAS) }) : of({ ok: true, data: [] }));
      comp.loadPessoas();
      expect(comp.pessoas()).toHaveLength(4);

      primeira.error({ status: 500 });

      expect(comp.pessoas()).toHaveLength(4);
      expect(comp.erroPessoas()).toBe('');
      expect(comp.vinculoBloqueado()).toBe(false);        // o select continua utilizável
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Preview do PDF da página
  // ═══════════════════════════════════════════════════════════════════
  describe('preview', () => {
    it('baixa o blob da página e abre inline', () => {
      const comp = criarCarregado();
      comp.preview({ id: 'pag-1' } as any);
      expect(apiGetBlob).toHaveBeenCalledWith('/api/admin/ponto/pagina/pag-1/preview');
      expect(abrirBlobInline).toHaveBeenCalledWith(PDF);
      expect(toastError).not.toHaveBeenCalled();
    });

    it('erro: toast e não abre nada', () => {
      apiGetBlob.mockReturnValue(throwError(() => ({ status: 404 })));
      const comp = criarCarregado();
      comp.preview({ id: 'pag-9' } as any);
      expect(toastError).toHaveBeenCalledWith('Não foi possível abrir o PDF da página.');
      expect(abrirBlobInline).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Publicação (POST .../lote/{id}/publicar) — confirm + "Emitir aviso"
  // ═══════════════════════════════════════════════════════════════════
  describe('publicar', () => {
    it('confirma e publica com aviso ligado por padrão (checkbox ausente = marcado)', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      l.pendentes = 0;

      comp.publicar(l);

      expect(confirmSpy).toHaveBeenCalledWith(
        'Publicar este lote? As folhas vinculadas ficarão disponíveis para os operadores/técnicos.');
      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/publicar', { emitir_aviso: true });
      expect(l.status).toBe('PUBLICADO');       // o payload da resposta é mesclado no lote
      expect(comp.publicando('lote-1')).toBe(false);
    });

    it('o confirm avisa quantas páginas pendentes ficarão invisíveis', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];           // pendentes = 2

      comp.publicar(l);

      expect(confirmSpy).toHaveBeenCalledWith(
        'Publicar este lote? As folhas vinculadas ficarão disponíveis para os operadores/técnicos.'
        + '\n\nAtenção: 2 página(s) pendente(s) não ficarão visíveis a ninguém.');
    });

    it('confirmação negada: nenhum POST', () => {
      confirmSpy.mockReturnValue(false);
      const comp = criarCarregado();

      comp.publicar(comp.lotes()[0] as any);

      expect(apiPost).not.toHaveBeenCalled();
      expect(comp.publicando('lote-1')).toBe(false);
    });

    it('checkbox "Emitir aviso" desmarcado publica sem aviso', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      l.emitirAviso = false;

      comp.publicar(l);

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/publicar', { emitir_aviso: false });
    });

    it('checkbox remarcado volta a emitir aviso', () => {
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];
      l.emitirAviso = true;
      comp.publicar(l);
      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/publicar', { emitir_aviso: true });
    });

    it('marca o lote como "publicando" durante o voo (só ele)', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];

      comp.publicar(l);
      expect(comp.publicando('lote-1')).toBe(true);

      emVoo.next(respostaLote(PAGINAS, { status: 'PUBLICADO' }));
      emVoo.complete();
      expect(comp.publicando('lote-1')).toBe(false);
    });

    it('erro: toast com a mensagem do backend, trava liberada e lote intacto', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Lote já publicado.' } })));
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];

      comp.publicar(l);

      expect(toastError).toHaveBeenCalledWith('Não foi possível publicar o lote. (Lote já publicado.)');
      expect(comp.publicando('lote-1')).toBe(false);
      expect(l.status).toBe('REVISAO');
    });

    it('erro sem corpo cai na guia da tela', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      comp.publicar(comp.lotes()[0] as any);
      expect(toastError).toHaveBeenCalledWith('Não foi possível publicar o lote.');
    });

    it('corrige F57 — a razão da recusa chega ao admin (shape REAL do backend: {ok:false, error})', () => {
      // O front pedia só `['message']`, mas o `GlobalExceptionHandler` serializa a
      // `ServiceValidationException` como `{ok:false, error:"<frase>"}` — sem `message`. Resultado:
      // TODA recusa virava "Erro ao publicar." e o admin não sabia o que remover do lote. As frases
      // da folha mensal (C6) existem justamente para NOMEAR a pessoa em conflito.
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'Maria Souza já tem uma folha MENSAL publicada para 06/2026.' },
      })));
      const comp = criarCarregado();

      comp.publicar(comp.lotes()[0] as any);

      expect(toastError).toHaveBeenCalledWith(
        expect.stringContaining('Maria Souza já tem uma folha MENSAL publicada para 06/2026.'));
    });

    it('corrige F57 — a recusa FICA na tela (é uma tarefa: quais páginas remover), não só num toast de 12 s', () => {
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'Já existe folha MENSAL de Maria Souza (06/2026).' },
      })));
      const comp = criarCarregado();
      const l: any = comp.lotes()[0];

      comp.publicar(l);

      expect(comp.erroPublicacao()['lote-1']).toContain('Maria Souza');

      apiPost.mockReturnValue(of(respostaLote(PAGINAS, { status: 'PUBLICADO' })));
      comp.publicar(l);                                    // nova tentativa (o admin removeu a página)

      expect(comp.erroPublicacao()['lote-1']).toBeUndefined();   // a recusa velha sai da tela
    });

    it('corrige F57 — o contorno do C6 (frase nos DOIS campos) segue funcionando', () => {
      // Defesa em profundidade: o backend repete a frase em `message` e em `error`; a ordem do
      // helper (`message` primeiro) mantém o mesmo texto na tela.
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'Lote já está publicado.', message: 'Lote já está publicado.' },
      })));
      const comp = criarCarregado();

      comp.publicar(comp.lotes()[0] as any);

      expect(toastError).toHaveBeenCalledWith('Não foi possível publicar o lote. (Lote já está publicado.)');
    });

    it('corrige F49 — a resposta de um lote NÃO destrava a publicação de OUTRO ainda em voo', () => {
      // `publicandoId` era um slot único e QUALQUER resposta o zerava: com dois lotes em revisão
      // (situação normal — eles se acumulam), a resposta do lote A reabilitava o botão do lote B
      // cujo POST ainda estava em voo → o admin reclicava → segunda publicação concorrente do MESMO
      // lote (avisos pessoais duplicados; hoje o C6 recusa a 2ª com 400, mas a trava é aqui).
      lotesResposta = () => [lote({ id: 'lote-A' }), lote({ id: 'lote-B' })];
      const postA = new Subject<any>();
      const postB = new Subject<any>();
      apiPost.mockReturnValueOnce(postA).mockReturnValueOnce(postB);
      const comp = criarCarregado();
      const [a, b]: any[] = comp.lotes();

      comp.publicar(a);
      comp.publicar(b);                        // publica B enquanto A ainda voa
      expect(comp.publicando('lote-A')).toBe(true);
      expect(comp.publicando('lote-B')).toBe(true);

      postA.next(respostaLote(PAGINAS, { id: 'lote-A', status: 'PUBLICADO' }));   // A responde primeiro

      expect(comp.publicando('lote-A')).toBe(false);   // A liberado...
      expect(comp.publicando('lote-B')).toBe(true);    // ...e B continua travado (era aqui que abria)

      comp.publicar(b);                        // o reclique não passa
      expect(apiPost).toHaveBeenCalledTimes(2);
      expect(apiPost.mock.calls.filter(c => c[0] === '/api/admin/ponto/lote/lote-B/publicar')).toHaveLength(1);
      expect(confirmSpy).toHaveBeenCalledTimes(2);   // nem chega a perguntar de novo
    });

    it('corrige F49 — publicado o lote, ele volta a ser publicável só depois da resposta (a trava é por lote, não global)', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValueOnce(emVoo);
      lotesResposta = () => [lote({ id: 'lote-A' }), lote({ id: 'lote-B' })];
      const comp = criarCarregado();
      const [a, b]: any[] = comp.lotes();

      comp.publicar(a);
      expect(comp.publicando('lote-B')).toBe(false);   // a publicação de A não trava B

      apiPost.mockReturnValue(of(respostaLote(PAGINAS, { id: 'lote-B', status: 'PUBLICADO' })));
      comp.publicar(b);
      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-B/publicar', { emitir_aviso: true });
      expect(comp.publicando('lote-A')).toBe(true);    // e A segue em voo
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER (exceções deliberadas ao GATE) — controles destrutivos no DOM
  // ═══════════════════════════════════════════════════════════════════
  describe('render da revisão do lote (select de vínculo e botão Publicar)', () => {
    // ⚠️ Exceção ao GATE ("só lógica não-visual"), na mesma família da autorizada no C7: o que se
    // assere é o estado de controles DESTRUTIVOS — se estão habilitados, e QUE VALOR exibem —,
    // comportamento que vive só no template. Sem estes testes, apagar um binding deixaria a suíte
    // verde e devolveria o defeito na forma pior (o admin desvinculando uma página correta, ou
    // publicando o mesmo lote duas vezes).
    // ⚠️ `NgModel` aplica o valor/`disabled` no CVA numa MICROTASK (resolvedPromise.then) — um
    // `detectChanges()` síncrono não o reflete no DOM. Daí o `await fixture.whenStable()` (as
    // promises não são falsificadas: os fake timers deste spec só cobrem `Date`).

    /** Renderiza a página com o card "Folhas" aberto e TODOS os lotes expandidos (páginas na tela). */
    async function renderizarAberto(opts: { pessoasOk?: boolean; lotes?: any[] } = {}) {
      const pessoasOk = opts.pessoasOk !== false;
      const lista = opts.lotes ?? [lote()];
      TestBed.resetTestingModule();
      apiGet = vi.fn().mockImplementation((url: string) => {
        if (url === '/api/admin/ponto/pessoas') {
          return pessoasOk ? of({ ok: true, data: structuredClone(PESSOAS) }) : throwError(() => ({ status: 500 }));
        }
        if (url === '/api/admin/ponto/lotes') return of({ ok: true, data: structuredClone(lista) });
        // ids de página ÚNICOS por lote (como no backend): `valorEmVoo`/`seqPagina` são chaveados só
        // pelo id da página — uma fixture com ids repetidos entre lotes inventaria cross-talk.
        const id = url.substring(url.lastIndexOf('/') + 1);
        return of(respostaLote(PAGINAS.map(p => ({ ...p, id: `${id}-${p.id}` })), { id }));
      });
      TestBed.configureTestingModule({
        imports: [AdminPontoComponent],
        providers: [
          provideRouter([]),
          { provide: ApiService, useValue: apiMock() },
          { provide: AuthService, useValue: { temFolhaPonto: signal(false) } },
          { provide: ToastService, useValue: { error: toastError, success: toastSuccess } },
        ],
      });
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(AdminPontoComponent);
      fixture.detectChanges();                                     // ngOnInit + render
      const comp = fixture.componentInstance;
      comp.selectCard('folhas');                                   // abre o card das folhas
      comp.lotes().forEach(l => comp.toggleLote(l));               // expande os lotes (traz as páginas)
      await estabilizar(fixture);
      return fixture;
    }

    async function estabilizar(fixture: ComponentFixture<AdminPontoComponent>): Promise<void> {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    const selects = (fixture: ComponentFixture<AdminPontoComponent>) =>
      fixture.debugElement.queryAll(By.css('select.pessoa-select'))
        .map(de => de.nativeElement as HTMLSelectElement);

    const botoesPublicar = (fixture: ComponentFixture<AdminPontoComponent>) =>
      fixture.debugElement.queryAll(By.css('button.btn-publicar'))
        .map(de => de.nativeElement as HTMLButtonElement);

    /**
     * Escolha REAL no `<select>`: o navegador só emite `change` quando o valor MUDA — reescolher a
     * opção já selecionada não dispara nada. É essa regra que fazia do F48 um beco sem saída, e é
     * por isso que o helper a respeita em vez de despachar o evento à força.
     */
    function escolher(select: HTMLSelectElement, valor: string): void {
      if (select.value === valor) return;
      select.value = valor;
      select.dispatchEvent(new Event('change'));
    }

    it('pessoas carregadas: os selects de vínculo estão HABILITADOS (controle de sensibilidade)', async () => {
      const fixture = await renderizarAberto();
      const ss = selects(fixture);
      expect(ss).toHaveLength(3);                                // o lote em revisão tem 3 páginas
      expect(ss.some(s => s.disabled)).toBe(false);   // `every(...)===false` só provaria "nem todos"
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    });

    it('corrige F50 — erro ao carregar pessoas: os selects ficam DESABILITADOS e a caixa de erro aparece', async () => {
      const fixture = await renderizarAberto({ pessoasOk: false });
      const ss = selects(fixture);
      expect(ss.length).toBeGreaterThan(0);
      expect(ss.every(s => s.disabled)).toBe(true);              // nenhum clique destrutivo é possível
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).not.toBeNull();
    });

    it('corrige F50 — durante o retry EM VOO o select segue desabilitado (a lista ainda está vazia)', async () => {
      // O retry limpa `erroPessoas` no disparo; sem `loadingPessoas` no guard, o select voltaria a
      // ficar interativo durante todo o request — com "— pendente —" como única opção.
      const fixture = await renderizarAberto({ pessoasOk: false });
      const comp = fixture.componentInstance;

      const emVoo = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url === '/api/admin/ponto/pessoas' ? emVoo : of({ ok: true, data: [] }));
      comp.loadPessoas();                                        // "Tentar novamente"
      await estabilizar(fixture);

      expect(comp.erroPessoas()).toBe('');                       // o erro sumiu da tela...
      expect(selects(fixture).every(s => s.disabled)).toBe(true); // ...mas o vínculo continua travado

      emVoo.next({ ok: true, data: structuredClone(PESSOAS) });  // a carga volta
      emVoo.complete();
      await estabilizar(fixture);
      expect(selects(fixture).some(s => s.disabled)).toBe(false);
    });

    it('corrige F48 — o PATCH que falha REVERTE o <select> ao vínculo real do servidor', async () => {
      const emVoo = new Subject<any>();
      apiPatch.mockReturnValue(emVoo);
      const fixture = await renderizarAberto();
      const select = selects(fixture)[1];                        // pag-2 — pendente
      expect(select.value).toBe('');

      escolher(select, 'OPERADOR:op-2');                         // o admin vincula
      await estabilizar(fixture);
      expect(apiPatch).toHaveBeenCalledTimes(1);
      expect(select.value).toBe('OPERADOR:op-2');                // otimista: o DOM mostra a escolha

      emVoo.error({ status: 401 });                              // sessão expirada — o PATCH nunca chegou
      await estabilizar(fixture);

      expect(select.value).toBe('');                             // ← o DOM volta ao estado REAL do servidor
      expect(fixture.componentInstance.lotes()[0].paginas![1].pessoa_id).toBeUndefined();
    });

    it('corrige F48 — depois do erro, reescolher a MESMA pessoa dispara um novo PATCH (fim do beco sem saída)', async () => {
      const primeiro = new Subject<any>();
      const doLote = PAGINAS.map(p => ({ ...p, id: `lote-1-${p.id}` }));   // ids como a fixture de render os cria
      apiPatch.mockReturnValueOnce(primeiro).mockReturnValue(of(respostaLote(comVinculo(doLote, 'lote-1-pag-2', OP2))));
      const fixture = await renderizarAberto();
      const select = selects(fixture)[1];

      escolher(select, 'OPERADOR:op-2');
      await estabilizar(fixture);
      primeiro.error({ status: 502 });                           // o 1º PATCH falha
      await estabilizar(fixture);
      expect(select.value).toBe('');                             // o select reverteu

      escolher(select, 'OPERADOR:op-2');                         // o admin insiste na MESMA pessoa
      await estabilizar(fixture);

      expect(apiPatch).toHaveBeenCalledTimes(2);                 // ← sai request (antes, nada saía)
      expect(select.value).toBe('OPERADOR:op-2');
      expect(fixture.componentInstance.lotes()[0].paginas![1].pessoa_id).toBe('op-2');
    });

    it('corrige F49 — com A e B em voo, a resposta de A NÃO reabilita o botão "Publicar" de B', async () => {
      const postA = new Subject<any>();
      const postB = new Subject<any>();
      apiPost.mockReturnValueOnce(postA).mockReturnValueOnce(postB);
      const fixture = await renderizarAberto({ lotes: [lote({ id: 'lote-A' }), lote({ id: 'lote-B' })] });
      const [botaoA, botaoB] = botoesPublicar(fixture);

      botaoA.click();                                            // cliques REAIS
      botaoB.click();
      await estabilizar(fixture);
      expect(botaoA.disabled).toBe(true);
      expect(botaoB.disabled).toBe(true);
      expect(botaoB.textContent?.trim()).toBe('Publicando...');

      postA.next(respostaLote(PAGINAS, { id: 'lote-A', status: 'PUBLICADO' }));   // A responde primeiro
      await estabilizar(fixture);

      expect(botaoB.disabled).toBe(true);                        // ← B continua travado (era aqui que reabria)
      expect(apiPost.mock.calls.filter(c => c[0] === '/api/admin/ponto/lote/lote-B/publicar')).toHaveLength(1);

      postB.next(respostaLote(PAGINAS, { id: 'lote-B', status: 'PUBLICADO' }));   // e só a resposta DELE o libera
      await estabilizar(fixture);
      expect(botoesPublicar(fixture)).toHaveLength(0);           // os dois lotes agora estão publicados
    });

    it('corrige F57 — a recusa da publicação aparece NA TELA, junto do botão (não só no toast)', async () => {
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'Já existe folha MENSAL de Maria Souza (06/2026). Remova a página dela do lote.' },
      })));
      const fixture = await renderizarAberto();

      botoesPublicar(fixture)[0].click();                        // clique real → confirm (spy: true) → POST recusado
      await estabilizar(fixture);

      const caixa = fixture.debugElement.query(By.css('.accordion-row .error-box'));
      expect(caixa).not.toBeNull();
      expect(caixa.nativeElement.textContent).toContain('Maria Souza');   // a lista de trabalho fica legível
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Feature-gate do card pessoal do admin (Q35/E9) — presença, não layout
  // ═══════════════════════════════════════════════════════════════════
  describe('card "Meu Ponto e Banco" (admin com folha)', () => {
    /** Renderiza a página com `activeCard` nulo: os três `@if` de conteúdo ficam fechados
     *  (nenhum filho pesado é instanciado) — só os cards de navegação existem. */
    function renderizar(temFolha: boolean) {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [AdminPontoComponent],
        providers: [
          provideRouter([]),
          { provide: ApiService, useValue: apiMock() },
          { provide: AuthService, useValue: { temFolhaPonto: signal(temFolha) } },
          { provide: ToastService, useValue: { error: toastError, success: toastSuccess } },
        ],
      });
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(AdminPontoComponent);
      fixture.detectChanges();
      return fixture;
    }

    /** Quantos RouterLink apontam para `/ponto` (o card é um <button routerLink>, sem href). */
    function linksParaPonto(fixture: ComponentFixture<AdminPontoComponent>): number {
      const router = TestBed.inject(Router);
      return fixture.debugElement.queryAll(By.directive(RouterLink))
        .map(de => de.injector.get(RouterLink))
        .filter(rl => rl.urlTree && router.serializeUrl(rl.urlTree) === '/ponto')
        .length;
    }

    it('admin SEM folha (servidor público): nenhum acesso pessoal na página', () => {
      expect(linksParaPonto(renderizar(false))).toBe(0);
    });

    it('admin COM folha (SERVIDOR_PUBLICO=0): o acesso pessoal para /ponto aparece', () => {
      expect(linksParaPonto(renderizar(true))).toBe(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Estado dos cards e rótulos (lógica, sem asserção visual — GATE)
  // ═══════════════════════════════════════════════════════════════════
  describe('selectCard e tipoLabel', () => {
    it('nenhum card ativo no início; selectCard troca o ativo (sem alternância — sempre set)', () => {
      const comp = criarCarregado();
      expect(comp.activeCard()).toBeNull();
      comp.selectCard('folhas');
      expect(comp.activeCard()).toBe('folhas');
      comp.selectCard('folhas');
      expect(comp.activeCard()).toBe('folhas');   // ≠ do acordeão do /ponto: reclicar NÃO fecha
      comp.selectCard('banco');
      expect(comp.activeCard()).toBe('banco');
    });

    it('trocar de card não refaz as cargas da página', () => {
      const comp = criarCarregado();
      apiGet.mockClear();
      comp.selectCard('retificacoes');
      comp.selectCard('banco');
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('tipoLabel traduz os três tipos e devolve vazio para ausente', () => {
      const comp = criar();
      expect(comp.tipoLabel('OPERADOR')).toBe('Operador');
      expect(comp.tipoLabel('TECNICO')).toBe('Técnico');
      expect(comp.tipoLabel('ADMINISTRADOR')).toBe('Administrador');
      expect(comp.tipoLabel(undefined)).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // F59 — exclusão de publicações (X → preview → modal dinâmico → DELETE)
  // ═══════════════════════════════════════════════════════════════════
  describe('exclusão de publicações (F59)', () => {

    /** Componente carregado COM a flag do master (o backend a manda no envelope da listagem). */
    function criarComoMaster(): AdminPontoComponent {
      podeExcluirResposta = true;
      return criarCarregado();
    }

    it('corrige F59 — a permissão de excluir vem do BACKEND (flag da listagem), nunca de um username no front', () => {
      const comum = criarCarregado();
      expect(comum.podeExcluir()).toBe(false);   // flag ausente/false → sem X

      const master = criarComoMaster();
      expect(master.podeExcluir()).toBe(true);
    });

    it('X do lote: busca o preview daquele lote e abre o modal com as consequências REAIS', () => {
      const comp = criarComoMaster();

      comp.abrirExclusaoLote(comp.lotes()[0] as any);

      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/exclusao/preview');
      expect(comp.previewExclusao()?.escopo).toBe('LOTE');
      expect(comp.previewExclusao()?.retificacoes_excluidas).toBe(2);
      expect(comp.previewExclusao()?.reabre_competencia).toBe('06/2026');
    });

    it('X da folha: o preview é o DA PÁGINA (o que morre numa folha não é o que morre no lote)', () => {
      previewResposta = () => preview({
        escopo: 'PAGINA',
        pagina: { id: 'pag-1', numero_pagina: 1, pessoa_nome: 'Maria Souza' },
        paginas_excluidas: 1,
        retificacoes_excluidas: 1,
        avisos_removidos: 1,
        avisos_destinatarios: ['Maria Souza'],
        arquivos: 1,
      });
      const comp = criarComoMaster();
      const l: any = comp.lotes()[0];
      comp.toggleLote(l);   // traz as páginas

      comp.abrirExclusaoPagina(l, l.paginas[0]);

      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/pagina/pag-1/exclusao/preview');
      expect(comp.previewExclusao()?.escopo).toBe('PAGINA');
      expect(comp.previewExclusao()?.pagina?.pessoa_nome).toBe('Maria Souza');
    });

    it('preview que falha: o modal NÃO abre e o erro vai ao toast — nada é excluído às cegas', () => {
      apiGet.mockImplementation((url: string) => {
        if (url.includes('/exclusao/preview')) return throwError(() => ({ status: 500, error: { ok: false, error: 'Erro interno do servidor' } }));
        if (url === '/api/admin/ponto/pessoas') return of({ ok: true, data: PESSOAS });
        return of({ ok: true, data: lotesResposta(), pode_excluir: true });
      });
      const comp = criarCarregado();

      comp.abrirExclusaoLote(comp.lotes()[0] as any);

      expect(comp.previewExclusao()).toBeNull();
      expect(toastError).toHaveBeenCalledWith(
        'Não foi possível verificar o que a exclusão apagaria — nada foi excluído. (Erro interno do servidor)');
      expect(apiDelete).not.toHaveBeenCalled();
    });

    it('confirmar: DELETE do lote, modal fechado e listagem RECARREGADA (a exclusão muda mais que a linha clicada)', () => {
      const comp = criarComoMaster();
      comp.abrirExclusaoLote(comp.lotes()[0] as any);
      apiGet.mockClear();

      comp.confirmarExclusao();

      expect(apiDelete).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1');
      expect(comp.previewExclusao()).toBeNull();               // fechou
      expect(toastSuccess).toHaveBeenCalledWith('Lote excluído.');
      expect(apiGet).toHaveBeenCalledWith('/api/admin/ponto/lotes');   // recarregou
    });

    it('confirmar a exclusão de uma FOLHA: DELETE da página (o lote sobrevive)', () => {
      const comp = criarComoMaster();
      const l: any = comp.lotes()[0];
      comp.toggleLote(l);
      comp.abrirExclusaoPagina(l, l.paginas[0]);

      comp.confirmarExclusao();

      expect(apiDelete).toHaveBeenCalledWith('/api/admin/ponto/lote/lote-1/pagina/pag-1');
      expect(toastSuccess).toHaveBeenCalledWith('Folha excluída.');
    });

    it('cancelar: fecha sem DELETE nenhum e sem recarregar', () => {
      const comp = criarComoMaster();
      comp.abrirExclusaoLote(comp.lotes()[0] as any);
      apiGet.mockClear();

      comp.fecharExclusao();

      expect(comp.previewExclusao()).toBeNull();
      expect(apiDelete).not.toHaveBeenCalled();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('corrige F59 — o 403 do backend (não-master que chamou o endpoint) chega NO MODAL, que continua aberto', () => {
      apiDelete.mockReturnValue(throwError(() => ({ status: 403, error: { ok: false, error: 'forbidden' } })));
      const comp = criarComoMaster();
      comp.abrirExclusaoLote(comp.lotes()[0] as any);

      comp.confirmarExclusao();

      expect(comp.erroExclusao()).toBe('Não foi possível excluir. (forbidden)');
      expect(comp.previewExclusao()).not.toBeNull();   // o modal fica: é ali que o admin está olhando
      expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('erro sem corpo cai na guia da tela', () => {
      apiDelete.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarComoMaster();
      comp.abrirExclusaoLote(comp.lotes()[0] as any);

      comp.confirmarExclusao();

      expect(comp.erroExclusao()).toBe('Não foi possível excluir.');
    });

    it('corrige F59 — trava POR ITEM: o X de um lote em voo não trava o do outro, e o reclique não redispara', () => {
      lotesResposta = () => [lote({ id: 'lote-A' }), lote({ id: 'lote-B' })];
      const previewEmVoo = new Subject<any>();
      podeExcluirResposta = true;
      const comp = criarCarregado();
      const [a, b]: any[] = comp.lotes();

      apiGet.mockImplementation((url: string) =>
        url.includes('/exclusao/preview') ? previewEmVoo : of({ ok: true, data: [] }));

      comp.abrirExclusaoLote(a);
      expect(comp.exclusaoEmVoo('lote:lote-A')).toBe(true);
      expect(comp.exclusaoEmVoo('lote:lote-B')).toBe(false);   // o item do vizinho segue clicável

      comp.abrirExclusaoLote(a);                                // reclique: não sai um 2º preview
      expect(apiGet.mock.calls.filter(c => c[0].includes('/exclusao/preview'))).toHaveLength(1);

      previewEmVoo.next(preview());
      expect(comp.exclusaoEmVoo('lote:lote-A')).toBe(false);
      expect(comp.previewExclusao()).not.toBeNull();
    });

    it('DELETE em voo: "Excluir definitivamente" trava (excluindoAlvo) e o reclique não dispara um segundo DELETE', () => {
      const deleteEmVoo = new Subject<any>();
      apiDelete.mockReturnValue(deleteEmVoo);
      const comp = criarComoMaster();
      comp.abrirExclusaoLote(comp.lotes()[0] as any);

      comp.confirmarExclusao();
      expect(comp.excluindoAlvo()).toBe(true);

      comp.confirmarExclusao();                                 // o reclique não passa
      expect(apiDelete).toHaveBeenCalledTimes(1);

      deleteEmVoo.next({ ok: true, data: {} });
      expect(comp.excluindoAlvo()).toBe(false);
      expect(comp.previewExclusao()).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER da exclusão (exceção ao GATE, mesma família dos controles destrutivos)
  // ═══════════════════════════════════════════════════════════════════
  describe('render da exclusão (X e modal dinâmico)', () => {
    // O X é o gesto mais destrutivo da tela e o modal é a ÚNICA barreira antes dele. Os dois vivem só
    // no template: sem estes testes, apagar o `@if (podeExcluir())` daria o X a qualquer admin (com o
    // 403 aparecendo como erro misterioso), e um modal que não consumisse o preview viraria de novo o
    // "tem certeza?" genérico que este estágio existe para eliminar.

    async function renderizarComExclusao(opts: { master?: boolean } = {}) {
      podeExcluirResposta = opts.master !== false;
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [AdminPontoComponent],
        providers: [
          provideRouter([]),
          { provide: ApiService, useValue: apiMock() },
          { provide: AuthService, useValue: { temFolhaPonto: signal(false) } },
          { provide: ToastService, useValue: { error: toastError, success: toastSuccess } },
        ],
      });
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(AdminPontoComponent);
      fixture.detectChanges();
      const comp = fixture.componentInstance;
      comp.selectCard('folhas');
      comp.lotes().forEach(l => comp.toggleLote(l));   // expande (traz as páginas)
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      return fixture;
    }

    const xDeLote = (f: ComponentFixture<AdminPontoComponent>) =>
      f.debugElement.queryAll(By.css('button.btn-x-lote')).map(de => de.nativeElement as HTMLButtonElement);
    const xDePagina = (f: ComponentFixture<AdminPontoComponent>) =>
      f.debugElement.queryAll(By.css('button.btn-x-pagina')).map(de => de.nativeElement as HTMLButtonElement);
    const modal = (f: ComponentFixture<AdminPontoComponent>) =>
      f.debugElement.query(By.css('.modal-card'));

    it('corrige F59 — sem a flag do backend, NENHUM X é renderizado (nem no lote, nem nas folhas)', async () => {
      const fixture = await renderizarComExclusao({ master: false });

      expect(xDeLote(fixture)).toHaveLength(0);
      expect(xDePagina(fixture)).toHaveLength(0);
    });

    it('corrige F59 — com a flag, o X aparece nas DUAS tabelas (o lote e cada folha dele)', async () => {
      const fixture = await renderizarComExclusao();

      expect(xDeLote(fixture)).toHaveLength(1);       // 1 lote
      expect(xDePagina(fixture)).toHaveLength(3);     // 3 páginas
    });

    it('corrige F59 — o X do lote NÃO expande/contrai a linha (stopPropagation): ele abre o modal', async () => {
      const fixture = await renderizarComExclusao();
      const comp = fixture.componentInstance;
      const expandidoAntes = (comp.lotes()[0] as any)._exp;

      xDeLote(fixture)[0].click();
      fixture.detectChanges();

      expect((comp.lotes()[0] as any)._exp).toBe(expandidoAntes);   // o acordeão não se mexeu
      expect(modal(fixture)).not.toBeNull();
    });

    it('corrige F59 — o modal RENDERIZA o preview: pessoas, re-âncora, contagens, destinatários e o mês reaberto', async () => {
      const fixture = await renderizarComExclusao();

      xDeLote(fixture)[0].click();
      fixture.detectChanges();

      const texto = modal(fixture).nativeElement.textContent as string;
      expect(texto).toContain('Excluir o lote inteiro?');
      expect(texto).toContain('3 folha(s) e 4 arquivo(s) PDF');
      expect(texto).toContain('2 retificação(ões)');
      expect(texto).toContain('2 aviso(s) pessoal(is)');
      expect(texto).toContain('João Lima, Maria Souza');                      // destinatários do aviso
      expect(texto).toContain('volta para a folha 01/05/2026 a 31/05/2026');  // re-âncora de Maria
      expect(texto).toContain('fica sem folha oficial — abertura 0');         // re-âncora de João
      expect(texto).toContain('06/2026');                                     // competência reaberta
      expect(texto).toContain('Esta ação é irreversível.');
    });

    it('corrige F59 — o modal de uma FOLHA nomeia a folha e mostra os números DELA (o texto não é fixo)', async () => {
      previewResposta = () => preview({
        escopo: 'PAGINA',
        pagina: { id: 'pag-1', numero_pagina: 1, pessoa_nome: 'Maria Souza' },
        pessoas: [{ pessoa_id: 'op-1', nome: 'Maria Souza', tipo: 'OPERADOR', retificacoes_excluidas: 1, reancora: 'não muda' }],
        paginas_excluidas: 1,
        retificacoes_excluidas: 1,
        avisos_removidos: 1,
        avisos_destinatarios: ['Maria Souza'],
        reabre_competencia: null,
        arquivos: 1,
      });
      const fixture = await renderizarComExclusao();

      xDePagina(fixture)[0].click();
      fixture.detectChanges();

      const texto = modal(fixture).nativeElement.textContent as string;
      expect(texto).toContain('Excluir esta folha?');
      expect(texto).toContain('Página 1 — Maria Souza');
      expect(texto).toContain('1 folha(s) e 1 arquivo(s) PDF');
      expect(texto).toContain('não muda');
      expect(texto).not.toContain('volta a aceitar publicação');   // nenhuma competência reabre aqui
    });

    it('corrige F59 — durante o DELETE em voo, "Excluir definitivamente" e "Cancelar" ficam DESABILITADOS', async () => {
      const deleteEmVoo = new Subject<any>();
      apiDelete.mockReturnValue(deleteEmVoo);
      const fixture = await renderizarComExclusao();

      xDeLote(fixture)[0].click();
      fixture.detectChanges();
      const confirmar = modal(fixture).query(By.css('button.btn-danger')).nativeElement as HTMLButtonElement;
      const cancelar = modal(fixture).query(By.css('button.btn-outline')).nativeElement as HTMLButtonElement;
      expect(confirmar.disabled).toBe(false);

      confirmar.click();
      fixture.detectChanges();

      expect(confirmar.disabled).toBe(true);
      expect(cancelar.disabled).toBe(true);
      expect(confirmar.textContent).toContain('Excluindo...');

      deleteEmVoo.next({ ok: true, data: {} });
      fixture.detectChanges();
      expect(modal(fixture)).toBeNull();   // fechou
    });

    it('corrige F59 — o erro do backend aparece DENTRO do modal (não só num toast que some)', async () => {
      apiDelete.mockReturnValue(throwError(() => ({ status: 403, error: { ok: false, error: 'forbidden' } })));
      const fixture = await renderizarComExclusao();

      xDeLote(fixture)[0].click();
      fixture.detectChanges();
      (modal(fixture).query(By.css('button.btn-danger')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      const caixa = modal(fixture).query(By.css('.error-box'));
      expect(caixa.nativeElement.textContent).toContain('forbidden');
    });
  });
});

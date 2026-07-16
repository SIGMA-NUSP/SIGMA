import { DebugElement, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { HomeComponent } from './home.component';

/**
 * C13b — HomeComponent (page `/` do operador): spec NOVO, criado junto com a instalação do canal
 * de erro (C7) nas duas listagens da tela — "Verificação de Salas" (`chkCtrl`) e "Registros de
 * Operação de Áudio" (`opCtrl`), ambas sobre o `TableStateController`.
 *
 * O que se trava aqui é EXATAMENTE o que só existe no template: sem estes testes, apagar o ramo
 * `@else if (X.erro())` deixaria toda a suíte verde e a página principal voltaria a afirmar
 * "Nenhuma verificação encontrada." / "Nenhuma operação encontrada." quando a leitura FALHA — a
 * pior forma do defeito, porque o operador conclui que não registrou nada (e re-registra, ou deixa
 * de conferir o que já registrou) em vez de descobrir que o backend caiu. O motor (rows/meta/
 * loading/erro + recência) já está coberto em `table-state.controller.spec.ts`; este spec prova o
 * CONSUMO do canal pela tela: caixa com retry, distinção do vazio legítimo, isolamento entre as
 * duas tabelas e o rodapé que não mente.
 *
 * Estratégia: TestBed com render de verdade (`detectChanges()` → `ngOnInit` dispara as cargas, que
 * respondem de forma síncrona pelos mocks). O `app-pagination` usa `NgModel` → o DOM só assenta
 * depois do `await fixture.whenStable()`. `ApiService` despacha por ENDPOINT (a tela faz 3 GETs
 * pelo mesmo `api.getList`), o que também permite contar as chamadas de cada tabela em separado.
 *
 * A exclusão "FORA DE ESCOPO (F65)" que vivia aqui DEIXOU DE VALER no C18: a seção "Escala"
 * (a última listagem que engolia erro — e sem recência) ganhou canal + token no PRÓPRIO loader
 * (decisão do estágio: NÃO migrou para o `TableStateController` — o contrato do endpoint com
 * sort/search/filtros não foi provado), e o acordeão dela ganhou erro POR LINHA sem cache sticky.
 * O describe "seção Escala (C18/F65)" cobre tudo isso.
 */

// ── Endpoints (o mock do getList despacha por eles; contamos as chamadas por endpoint) ──
const CHK = '/api/operador/meus-checklists';
const OP = '/api/operador/minhas-operacoes';
const ESCALA = '/api/escala/list';

/** Linha de `GET /api/operador/meus-checklists`. */
const LINHA_CHK = { id: 'chk-1', sala_nome: 'Plenário 3', data: '2026-07-10', qtde_ok: 12, qtde_falha: 1 };
/** Linha de `GET /api/operador/minhas-operacoes`. */
const LINHA_OP = {
  id: 'ent-1', sala: 'Sala de Áudio 7', data: '2026-07-11', nome_evento: 'Sessão Deliberativa',
  hora_entrada: '09:00:00', hora_saida: '12:30:00', anormalidade: false,
};

const META = { page: 1, limit: 10, total: 3, pages: 1 };

/** Linha de `GET /api/escala/list` (F65). */
const LINHA_ESCALA = { id: 'esc-1', data_inicio: '2026-07-13', data_fim: '2026-07-17', criado_em: '2026-07-10' };
/** Resposta do `GET /api/escala/esc-1` — um plenário com um operador da manhã. */
const RESUMO_OK = {
  data: {
    resumo: [{
      sala_nome: 'Plenário 5', operadores: 'Maria Souza', operadores_ids: ['op-1'],
      operadores_detalhe: [{ id: 'op-1', nome: 'Maria Souza', turno: 'M' }],
    }],
  },
};

/** Erro no shape REAL do backend (`GlobalExceptionHandler`: `{ok:false, error:"…"}`). */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };

/**
 * Guia da tela + detalhe do backend (`erroCargaMsg`). A HomeComponent NÃO passa `erroMsg` ao
 * controlador → vale o fallback do motor, igual nas duas tabelas.
 */
const MSG_ERRO = 'Não foi possível carregar a lista. (Erro interno do servidor)';

describe('HomeComponent (canal de erro das listagens — C13b)', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiGet: ReturnType<typeof vi.fn>;
  let openPdfInline: ReturnType<typeof vi.fn>;
  let whoAmI: ReturnType<typeof vi.fn>;
  /** Resposta corrente de cada endpoint — trocada por teste (o mock chama a fábrica a cada GET). */
  let resposta: Record<string, () => any>;
  /** Resposta corrente do GET /api/escala/{id} (o acordeão da Escala) — trocada por teste (F65). */
  let respostaDetalheEscala: () => any;

  beforeEach(async () => {
    resposta = {
      [CHK]: () => of({ data: [LINHA_CHK], meta: { ...META } }),
      [OP]: () => of({ data: [LINHA_OP], meta: { ...META } }),
      [ESCALA]: () => of({ data: [], meta: null }),   // vazio legítimo por padrão; os testes do F65 trocam
    };
    apiGetList = vi.fn((endpoint: string) => (resposta[endpoint] ?? (() => of({ data: [], meta: null })))());
    respostaDetalheEscala = () => of({ data: { resumo: [] } });
    apiGet = vi.fn(() => respostaDetalheEscala());   // detalhe da escala (acordeão) é o único api.get da tela
    openPdfInline = vi.fn();
    whoAmI = vi.fn().mockReturnValue(of(null));

    await TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink nos cards de atalho
        { provide: ApiService, useValue: { getList: apiGetList, get: apiGet, openPdfInline } },
        {
          // Usuário JÁ carregado → o `ngOnInit` segue o caminho SÍNCRONO (sem esperar o whoami).
          provide: AuthService,
          useValue: { user: signal({ id: 'op-1', nome: 'Maria Souza' }), isAdmin: signal(false), whoAmI },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  /** Cria e renderiza a página: `ngOnInit` dispara as 3 cargas; o `whenStable` assenta o NgModel. */
  async function renderizar(): Promise<ComponentFixture<HomeComponent>> {
    const fixture = TestBed.createComponent(HomeComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  /** Aplica no DOM o que as mutações de signal produziram (retry, recarga). */
  async function assentar(fixture: ComponentFixture<HomeComponent>): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** A `<section>` cujo `<h2>` casa com o título — as duas tabelas são irmãs no mesmo template. */
  function secao(fixture: ComponentFixture<HomeComponent>, titulo: string): DebugElement {
    const alvo = fixture.debugElement.queryAll(By.css('section'))
      .find(s => ((s.nativeElement as HTMLElement).querySelector('h2')?.textContent ?? '').includes(titulo));
    if (!alvo) throw new Error(`seção "${titulo}" não encontrada`);
    return alvo;
  }

  const caixaErro = (sec: DebugElement) => sec.query(By.directive(ErroCargaComponent));
  const texto = (sec: DebugElement) => (sec.nativeElement as HTMLElement).textContent ?? '';
  /** Quantas cargas cada tabela pediu (o `getList` é o mesmo para as três). */
  const chamadas = (endpoint: string) => apiGetList.mock.calls.filter(c => c[0] === endpoint).length;

  // ═══════════════════════════════════════════════════════════════════
  // Carga inicial — caminho síncrono do ngOnInit
  // ═══════════════════════════════════════════════════════════════════
  describe('carga inicial', () => {
    it('com o usuário já em memória, não chama o whoami e dispara as três cargas (checklists, operações e escala)', async () => {
      await renderizar();
      expect(whoAmI).not.toHaveBeenCalled();
      expect(chamadas(CHK)).toBe(1);
      expect(chamadas(OP)).toBe(1);
      expect(chamadas(ESCALA)).toBe(1);
    });

    it('cada tabela pede a sua listagem com a ordenação padrão (data, mais recentes primeiro)', async () => {
      await renderizar();
      expect(apiGetList).toHaveBeenCalledWith(CHK,
        expect.objectContaining({ page: 1, limit: 10, sort: 'data', direction: 'desc' }));
      expect(apiGetList).toHaveBeenCalledWith(OP,
        expect.objectContaining({ page: 1, limit: 10, sort: 'data', direction: 'desc' }));
    });

    it('sucesso nas duas: as linhas aparecem e nenhuma caixa de erro é renderizada', async () => {
      const fixture = await renderizar();
      expect(texto(secao(fixture, 'Verificação de Salas'))).toContain('Plenário 3');
      expect(texto(secao(fixture, 'Registros de Operação de Áudio'))).toContain('Sala de Áudio 7');
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O canal de erro de CADA tabela (C7/C13b) — o piso do estágio
  //
  // As duas listagens têm o mesmo contrato (mesmo motor, mesmo ramo de template): o que muda é o
  // endpoint, o colspan e a frase do vazio. O erro de uma NÃO pode virar "lista vazia" nem contaminar
  // a outra.
  // ═══════════════════════════════════════════════════════════════════
  const TABELAS = [
    {
      nome: 'Verificação de Salas (checklists)', titulo: 'Verificação de Salas', endpoint: CHK, outro: OP,
      vazio: 'Nenhuma verificação encontrada.', marca: 'Plenário 3', colspan: '5',
      ctrl: (c: HomeComponent) => c.chkCtrl,
    },
    {
      nome: 'Registros de Operação de Áudio', titulo: 'Registros de Operação de Áudio', endpoint: OP, outro: CHK,
      vazio: 'Nenhuma operação encontrada.', marca: 'Sala de Áudio 7', colspan: '7',
      ctrl: (c: HomeComponent) => c.opCtrl,
    },
  ];

  for (const t of TABELAS) {
    describe(`tabela "${t.nome}"`, () => {
      it('falha na carga: caixa de erro (role="alert") com a mensagem do canal — e SEM a frase do vazio', async () => {
        // O operador precisa distinguir "não há registros" de "não deu para ler os registros": a
        // segunda é reversível (retry), a primeira o levaria a refazer trabalho já feito.
        resposta[t.endpoint] = () => throwError(() => ERRO_500);
        const fixture = await renderizar();
        const sec = secao(fixture, t.titulo);

        const caixa = caixaErro(sec);
        expect(caixa).not.toBeNull();
        expect(caixa.componentInstance.mensagem()).toBe(MSG_ERRO);
        expect(sec.query(By.css('[role="alert"]'))).not.toBeNull();     // o leitor de tela é avisado
        expect(texto(sec)).toContain(MSG_ERRO);
        expect(texto(sec)).not.toContain(t.vazio);                      // a tela não mente sobre o vazio
        expect(sec.query(By.css('app-erro-carga'))!.nativeElement.closest('td').getAttribute('colspan'))
          .toBe(t.colspan);                                             // a caixa ocupa a linha inteira
      });

      it('o botão "Tentar novamente" re-dispara a carga DESTA tabela (e só dela)', async () => {
        resposta[t.endpoint] = () => throwError(() => ERRO_500);
        const fixture = await renderizar();
        expect(chamadas(t.endpoint)).toBe(1);

        const botao = caixaErro(secao(fixture, t.titulo)).query(By.css('button')).nativeElement as HTMLButtonElement;
        botao.click();                                                  // clique REAL no DOM
        await assentar(fixture);

        expect(chamadas(t.endpoint)).toBe(2);                           // o retry chegou ao load()
        expect(chamadas(t.outro)).toBe(1);                              // a tabela vizinha não recarrega
        expect(caixaErro(secao(fixture, t.titulo))).not.toBeNull();     // ainda falhando → caixa continua
      });

      it('retry com sucesso: a caixa some e as linhas aparecem', async () => {
        resposta[t.endpoint] = () => throwError(() => ERRO_500);
        const fixture = await renderizar();
        resposta[t.endpoint] = () => of({ data: [t.endpoint === CHK ? LINHA_CHK : LINHA_OP], meta: { ...META } });

        (caixaErro(secao(fixture, t.titulo)).query(By.css('button')).nativeElement as HTMLButtonElement).click();
        await assentar(fixture);

        const sec = secao(fixture, t.titulo);
        expect(caixaErro(sec)).toBeNull();
        expect(texto(sec)).toContain(t.marca);
        expect(t.ctrl(fixture.componentInstance).erro()).toBe('');
      });

      it('vazio LEGÍTIMO (data: [] com sucesso): a frase do vazio, sem caixa de erro', async () => {
        resposta[t.endpoint] = () => of({ data: [], meta: { ...META, total: 0 } });
        const fixture = await renderizar();
        const sec = secao(fixture, t.titulo);

        expect(texto(sec)).toContain(t.vazio);
        expect(caixaErro(sec)).toBeNull();
      });

      it('o rodapé não mente: no erro o meta é limpo e a paginação deixa de exibir o total da carga anterior', async () => {
        // Sem isso, uma recarga que falha (filtro/ordenação/paginação) apagaria as linhas e manteria
        // "(3 registros)" no rodapé — a tela afirmando que há dados que ela não tem.
        const fixture = await renderizar();
        const ctrl = t.ctrl(fixture.componentInstance);
        expect(texto(secao(fixture, t.titulo))).toContain('3 registros');

        resposta[t.endpoint] = () => throwError(() => ERRO_500);
        ctrl.load();                                                    // a recarga de um filtro/paginação
        await assentar(fixture);

        const sec = secao(fixture, t.titulo);
        expect(ctrl.meta()).toBeNull();
        expect(sec.query(By.css('.pagination-controls'))).toBeNull();   // nada de rodapé sobre dados inexistentes
        expect(texto(sec)).not.toContain('registros');
        expect(caixaErro(sec)).not.toBeNull();
        expect(texto(sec)).toContain('Gerar Relatório');                // o resto da seção continua na tela
      });
    });
  }

  // ═══════════════════════════════════════════════════════════════════
  // Isolamento entre as duas tabelas — cada uma tem o SEU canal
  // ═══════════════════════════════════════════════════════════════════
  describe('isolamento entre as tabelas', () => {
    it('a falha dos checklists não põe caixa de erro nas operações (que seguem com as suas linhas)', async () => {
      resposta[CHK] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      const chk = secao(fixture, 'Verificação de Salas');
      const op = secao(fixture, 'Registros de Operação de Áudio');
      expect(caixaErro(chk)).not.toBeNull();
      expect(caixaErro(op)).toBeNull();
      expect(texto(op)).toContain('Sala de Áudio 7');
      expect(fixture.componentInstance.opCtrl.erro()).toBe('');
      expect(fixture.componentInstance.opCtrl.meta()).toEqual(META);    // o rodapé das operações segue válido
      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
    });

    it('a falha das operações não põe caixa de erro nos checklists (que seguem com as suas linhas)', async () => {
      resposta[OP] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      const chk = secao(fixture, 'Verificação de Salas');
      const op = secao(fixture, 'Registros de Operação de Áudio');
      expect(caixaErro(op)).not.toBeNull();
      expect(caixaErro(chk)).toBeNull();
      expect(texto(chk)).toContain('Plenário 3');
      expect(fixture.componentInstance.chkCtrl.erro()).toBe('');
      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
    });

    it('as duas falhando: cada seção ganha a SUA caixa, e nenhuma frase de vazio aparece', async () => {
      resposta[CHK] = () => throwError(() => ERRO_500);
      resposta[OP] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(2);
      expect(texto(secao(fixture, 'Verificação de Salas'))).not.toContain('Nenhuma verificação encontrada.');
      expect(texto(secao(fixture, 'Registros de Operação de Áudio'))).not.toContain('Nenhuma operação encontrada.');
    });

    it('o retry de uma tabela não ressuscita o erro da outra: cada caixa some sozinha', async () => {
      resposta[CHK] = () => throwError(() => ERRO_500);
      resposta[OP] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      resposta[CHK] = () => of({ data: [LINHA_CHK], meta: { ...META } });
      const botaoChk = caixaErro(secao(fixture, 'Verificação de Salas')).query(By.css('button'));
      (botaoChk.nativeElement as HTMLButtonElement).click();
      await assentar(fixture);

      expect(caixaErro(secao(fixture, 'Verificação de Salas'))).toBeNull();
      expect(texto(secao(fixture, 'Verificação de Salas'))).toContain('Plenário 3');
      expect(caixaErro(secao(fixture, 'Registros de Operação de Áudio'))).not.toBeNull();   // segue em erro
      expect(chamadas(OP)).toBe(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Seção Escala (C18/F65) — a última listagem que engolia erro, FORA do motor
  //
  // A Escala tem loader próprio (decisão do estágio: sem migrar para o TableStateController) e um
  // acordeão com drill-down por linha. O que se trava: o canal de erro da listagem (com recência —
  // a faceta F61 que o C13b curou só no motor), o erro POR LINHA do acordeão sem cache sticky
  // (reabrir refaz o GET) e o isolamento em relação às tabelas do motor.
  // ═══════════════════════════════════════════════════════════════════
  describe('seção Escala (C18/F65)', () => {
    const VAZIO_ESCALA = 'Nenhuma escala cadastrada.';
    const VAZIO_RESUMO = 'Nenhum operador escalado.';
    const MSG_ERRO_ESCALA =
      'Não foi possível carregar a Escala. Você pode estar escalado mesmo sem ela aparecer aqui — tente novamente. (Erro interno do servidor)';
    const MSG_ERRO_RESUMO = 'Não foi possível carregar os operadores desta escala. (Erro interno do servidor)';

    const secEscala = (f: ComponentFixture<HomeComponent>) => f.debugElement.query(By.css('section.escala-section'));
    /** Quantas vezes o detalhe daquela escala foi pedido (prova do sticky morto e do retry). */
    const chamadasDetalhe = (id: string) => apiGet.mock.calls.filter(c => c[0] === `/api/escala/${id}`).length;

    /** Clique REAL na linha da escala (a linha inteira é o gatilho do acordeão). */
    function clicarLinha(fixture: ComponentFixture<HomeComponent>): void {
      (secEscala(fixture).query(By.css('tr.escala-row')).nativeElement as HTMLTableRowElement).click();
    }

    it('corrige F65 — falha na carga: caixa (role="alert") com a mensagem do canal, SEM "Nenhuma escala cadastrada." e com o meta limpo', async () => {
      // "Nenhuma escala cadastrada." num 500 fazia o operador concluir que NÃO está escalado —
      // o oposto de "não sabemos", numa tela que é a fonte de onde ele deve se apresentar.
      resposta[ESCALA] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();
      const sec = secEscala(fixture);

      const caixa = caixaErro(sec);
      expect(caixa).not.toBeNull();
      expect(caixa.componentInstance.mensagem()).toBe(MSG_ERRO_ESCALA);
      expect(sec.query(By.css('[role="alert"]'))).not.toBeNull();
      expect(texto(sec)).not.toContain(VAZIO_ESCALA);
      expect(fixture.componentInstance.escalaMeta()).toBeNull();          // o rodapé não mente
      expect(sec.query(By.css('app-pagination'))).toBeNull();
    });

    it('corrige F65 — "Tentar novamente" re-dispara loadEscalas() (e não recarrega as tabelas vizinhas)', async () => {
      resposta[ESCALA] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();
      expect(chamadas(ESCALA)).toBe(1);

      (caixaErro(secEscala(fixture)).query(By.css('button')).nativeElement as HTMLButtonElement).click();
      await assentar(fixture);

      expect(chamadas(ESCALA)).toBe(2);
      expect(chamadas(CHK)).toBe(1);
      expect(chamadas(OP)).toBe(1);
      expect(caixaErro(secEscala(fixture))).not.toBeNull();               // ainda falhando → caixa continua
    });

    it('corrige F65 — retry com sucesso: a caixa some e as escalas aparecem', async () => {
      resposta[ESCALA] = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      resposta[ESCALA] = () => of({ data: [{ ...LINHA_ESCALA }], meta: { ...META, total: 1 } });
      (caixaErro(secEscala(fixture)).query(By.css('button')).nativeElement as HTMLButtonElement).click();
      await assentar(fixture);

      const sec = secEscala(fixture);
      expect(caixaErro(sec)).toBeNull();
      expect(sec.query(By.css('table.data-table'))).not.toBeNull();
      expect(fixture.componentInstance.erroEscala()).toBe('');
      expect(fixture.componentInstance.escalas()).toHaveLength(1);
    });

    it('vazio LEGÍTIMO (200 com data:[]): a frase do vazio, SEM caixa de erro', async () => {
      resposta[ESCALA] = () => of({ data: [], meta: { ...META, total: 0 } });
      const fixture = await renderizar();

      expect(texto(secEscala(fixture))).toContain(VAZIO_ESCALA);
      expect(caixaErro(secEscala(fixture))).toBeNull();
    });

    it('corrige F65 — recência: a resposta VELHA que chega depois da nova é descartada (sucesso × sucesso)', async () => {
      // A Escala é paginada server-side e sem token: dois cliques rápidos de página deixavam a
      // resposta VELHA vencer se chegasse por último — a mesma faceta F61 curada no motor (C13b).
      const primeira = new Subject<any>();
      const segunda = new Subject<any>();
      let n = 0;
      resposta[ESCALA] = () => (++n === 1 ? primeira : segunda);
      const fixture = await renderizar();                 // 1ª carga em voo

      fixture.componentInstance.escalaState.page = 2;
      fixture.componentInstance.loadEscalas();            // o clique de página dispara a 2ª

      segunda.next({ data: [{ ...LINHA_ESCALA }], meta: { ...META, page: 2, total: 1 } });
      segunda.complete();
      primeira.next({ data: [], meta: null });            // a VELHA chega por último
      primeira.complete();
      await assentar(fixture);

      expect(fixture.componentInstance.escalas()).toHaveLength(1);        // vale a nova
      expect(texto(secEscala(fixture))).not.toContain(VAZIO_ESCALA);
    });

    it('corrige F65 — recência: o ERRO velho não sobrescreve o sucesso mais novo', async () => {
      const primeira = new Subject<any>();
      const segunda = new Subject<any>();
      let n = 0;
      resposta[ESCALA] = () => (++n === 1 ? primeira : segunda);
      const fixture = await renderizar();

      fixture.componentInstance.loadEscalas();            // retry reclicado: duas cargas em voo

      segunda.next({ data: [{ ...LINHA_ESCALA }], meta: { ...META, total: 1 } });
      segunda.complete();
      primeira.error(ERRO_500);                           // a falha VELHA chega por último
      await assentar(fixture);

      expect(caixaErro(secEscala(fixture))).toBeNull();   // nenhum alarme falso
      expect(fixture.componentInstance.erroEscala()).toBe('');
      expect(fixture.componentInstance.escalas()).toHaveLength(1);        // a lista boa continua
    });

    describe('acordeão (drill-down por escala)', () => {
      beforeEach(() => {
        resposta[ESCALA] = () => of({ data: [{ ...LINHA_ESCALA }], meta: { ...META, total: 1 } });
      });

      it('corrige F65 — falha do detalhe: caixa NA LINHA, sem "Nenhum operador escalado."', async () => {
        // A mentira aqui era dupla: além de "nenhum operador" num 500, o `[]` gravado no erro era
        // truthy para o guard de refetch — o vazio ficava STICKY até o F5.
        respostaDetalheEscala = () => throwError(() => ERRO_500);
        const fixture = await renderizar();

        clicarLinha(fixture);
        await assentar(fixture);

        const caixa = caixaErro(secEscala(fixture));
        expect(caixa).not.toBeNull();
        expect(caixa.componentInstance.mensagem()).toBe(MSG_ERRO_RESUMO);
        expect(texto(secEscala(fixture))).not.toContain(VAZIO_RESUMO);
      });

      it('corrige F65 — o sticky morreu: fechar e reabrir REFAZ o GET (e o sucesso renderiza o resumo)', async () => {
        respostaDetalheEscala = () => throwError(() => ERRO_500);
        const fixture = await renderizar();

        clicarLinha(fixture);                             // abre → GET falha
        await assentar(fixture);
        expect(chamadasDetalhe('esc-1')).toBe(1);

        clicarLinha(fixture);                             // fecha
        await assentar(fixture);
        respostaDetalheEscala = () => of(RESUMO_OK);      // o backend voltou
        clicarLinha(fixture);                             // reabre → REFAZ o GET (antes: cache do [])
        await assentar(fixture);

        expect(chamadasDetalhe('esc-1')).toBe(2);
        const sec = secEscala(fixture);
        expect(caixaErro(sec)).toBeNull();
        expect(texto(sec)).toContain('Plenário 5');
        expect(texto(sec)).toContain('Maria Souza');
      });

      it('corrige F65 — o retry da caixa refaz o GET DA LINHA e o sucesso renderiza o resumo', async () => {
        respostaDetalheEscala = () => throwError(() => ERRO_500);
        const fixture = await renderizar();
        clicarLinha(fixture);
        await assentar(fixture);

        respostaDetalheEscala = () => of(RESUMO_OK);
        (caixaErro(secEscala(fixture)).query(By.css('button')).nativeElement as HTMLButtonElement).click();
        await assentar(fixture);

        expect(chamadasDetalhe('esc-1')).toBe(2);
        expect(caixaErro(secEscala(fixture))).toBeNull();
        expect(texto(secEscala(fixture))).toContain('Plenário 5');
      });

      it('vazio LEGÍTIMO do detalhe (resumo:[]): "Nenhum operador escalado.", sem caixa', async () => {
        respostaDetalheEscala = () => of({ data: { resumo: [] } });
        const fixture = await renderizar();

        clicarLinha(fixture);
        await assentar(fixture);

        expect(texto(secEscala(fixture))).toContain(VAZIO_RESUMO);
        expect(caixaErro(secEscala(fixture))).toBeNull();
      });
    });

    describe('isolamento com as tabelas do motor', () => {
      it('a falha da Escala não põe caixa nas outras seções (que seguem com as suas linhas)', async () => {
        resposta[ESCALA] = () => throwError(() => ERRO_500);
        const fixture = await renderizar();

        expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
        expect(caixaErro(secEscala(fixture))).not.toBeNull();
        expect(texto(secao(fixture, 'Verificação de Salas'))).toContain('Plenário 3');
        expect(texto(secao(fixture, 'Registros de Operação de Áudio'))).toContain('Sala de Áudio 7');
        expect(fixture.componentInstance.chkCtrl.erro()).toBe('');
        expect(fixture.componentInstance.opCtrl.erro()).toBe('');
      });

      it('a falha dos checklists não põe caixa na Escala (que segue com as suas linhas)', async () => {
        resposta[ESCALA] = () => of({ data: [{ ...LINHA_ESCALA }], meta: { ...META, total: 1 } });
        resposta[CHK] = () => throwError(() => ERRO_500);
        const fixture = await renderizar();

        expect(caixaErro(secEscala(fixture))).toBeNull();
        expect(secEscala(fixture).query(By.css('table.data-table'))).not.toBeNull();
        expect(fixture.componentInstance.erroEscala()).toBe('');
        expect(caixaErro(secao(fixture, 'Verificação de Salas'))).not.toBeNull();
      });
    });
  });
});

import { DebugElement, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
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
 * ⚠️ FORA DE ESCOPO (F65): a seção "Escala" NÃO usa o `TableStateController` — `loadEscalas()`
 * engole o erro (`escalas.set([])`) e a tela exibe "Nenhuma escala cadastrada.", que é o mesmo
 * defeito ainda não corrigido. O endpoint dela é mockado só para o `ngOnInit` não quebrar; nada
 * aqui a testa nem a caracteriza.
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

  beforeEach(async () => {
    resposta = {
      [CHK]: () => of({ data: [LINHA_CHK], meta: { ...META } }),
      [OP]: () => of({ data: [LINHA_OP], meta: { ...META } }),
      [ESCALA]: () => of({ data: [], meta: null }),   // F65: fora de escopo, só não pode quebrar o ngOnInit
    };
    apiGetList = vi.fn((endpoint: string) => (resposta[endpoint] ?? (() => of({ data: [], meta: null })))());
    apiGet = vi.fn().mockReturnValue(of({ data: { resumo: [] } }));   // detalhe da escala (acordeão)
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
});

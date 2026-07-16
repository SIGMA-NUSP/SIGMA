import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { DebugElement } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { AdminOperacaoAudioComponent } from './admin-operacao-audio.component';

/**
 * C13b — AdminOperacaoAudioComponent (/admin/operacao-audio): a tela mais densa do painel, com
 * TRÊS listagens server-side independentes, cada uma no seu `TableStateController`:
 *   • opCtrl   → endpoint DINÂMICO: `/api/admin/dashboard/operacoes` (agrupado por local, default)
 *                ou `/api/admin/dashboard/operacoes/entradas` (lista plana, checkbox desmarcado);
 *   • anomCtrl → `/api/admin/dashboard/anormalidades/lista`;
 *   • chkCtrl  → `/api/admin/dashboard/checklists`.
 *
 * O que este spec trava é o CANAL DE ERRO (C7) instalado nas três tabelas. Antes dele, a tela
 * preenchia o `erro` do controlador e não o exibia: uma leitura que falhava caía no ramo de lista
 * vazia e a tela AFIRMAVA "Nenhuma sessão." / "Nenhuma anormalidade." / "Nenhum checklist
 * encontrado." — o admin concluiria que não houve operação nem anormalidade no período (e um RDS
 * ou um relatório sairia dessa leitura falsa), sem nada na tela indicando falha nem como tentar
 * de novo.
 *
 * Por isso os testes são de RENDER (exceção deliberada à regra "só lógica"): o canal só cumpre a
 * decisão do estágio se o TEMPLATE o consumir. Travar apenas o signal deixaria a suíte verde com
 * o ramo `@if (X.erro())` apagado e o defeito de volta na tela. O motor em si (rows/meta/loading/
 * erro/recência) já está coberto no T21 (`table-state.controller.spec.ts`); aqui prova-se a
 * INSTALAÇÃO por tabela: caixa presente, mensagem certa, retry que re-pede o endpoint CERTO,
 * vazio legítimo intacto, isolamento entre as três tabelas e rodapé que não mente.
 *
 * ⚠️ O `opCtrl` aparece em DOIS pontos do template (modo agrupado × modo lista plana) — o canal foi
 * posto nos dois; ambos são exercidos abaixo.
 *
 * Estratégia: TestBed com `ApiService` mockado por `useValue` e `getList` roteado POR ENDPOINT (é
 * o que distingue as três tabelas — e o que permite derrubar uma sem tocar nas outras). Os
 * `TableStateController` são REAIS. Sem fake timers: nada no caminho testado depende de `Date` e o
 * debounce da busca (400 ms) não é exercido. Como o template tem `ngModel` (busca, checkbox de
 * agrupar, selects de RDS/relatório), todo render passa por `await fixture.whenStable()`.
 */

// ── Endpoints das 4 listagens (o `opCtrl` tem dois, um por modo) ──
const EP_OP_AGRUPADO = '/api/admin/dashboard/operacoes';
const EP_OP_PLANO = '/api/admin/dashboard/operacoes/entradas';
const EP_ANOM = '/api/admin/dashboard/anormalidades/lista';
const EP_CHK = '/api/admin/dashboard/checklists';

const META = { page: 1, limit: 10, total: 3, pages: 1 };

/** Linha de `/operacoes` (sessão agrupada por local). */
const SESSAO = {
  id: 1, sala_nome: 'Plenário 2', data: '2026-07-10', ultimo_evento: 'Sessão Deliberativa',
  comissao_nome: 'CAE - Comissão de Assuntos Econômicos', ultimo_pauta: '09:00:00',
  ultimo_inicio: '09:12:00', ultimo_termino: '11:40:00', checklist_do_dia_ok: true,
};
/** Linha de `/operacoes/entradas` (lista plana de entradas). */
const ENTRADA = {
  id: 11, sala_nome: 'Plenário 3', data: '2026-07-10', operador_nome: 'Maria Souza',
  tipo_evento: 'Reunião', nome_evento: 'Audiência Pública', horario_pauta: '14:00:00',
  horario_inicio: '14:05:00', horario_termino: '16:30:00', houve_anormalidade: false,
};
/** Linha de `/anormalidades/lista`. */
const ANORMALIDADE = {
  id: 21, data: '2026-07-09', sala_nome: 'Sala 5', registrado_por: 'João Lima',
  descricao_anormalidade: 'Microfone da bancada sem áudio', resolvida_pelo_operador: true,
  houve_prejuizo: false, houve_reclamacao: false,
};
/** Linha de `/checklists` (Verificação de Plenários). */
const CHECKLIST = {
  id: 31, sala_nome: 'Plenário 7', data: '2026-07-11', operador_nome: 'Ana Prado',
  hora_inicio_testes: '08:00:00', hora_termino_testes: '08:25:00', status: 'Ok',
};

/** 500 REAL do backend (`GlobalExceptionHandler`): corpo genérico → a guia da tela vem na frente. */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };
/** Texto que o admin efetivamente lê na caixa: guia da tela + detalhe do backend entre parênteses. */
const MSG_500 = 'Não foi possível carregar a lista. (Erro interno do servidor)';

describe('AdminOperacaoAudioComponent — canal de erro das 3 listagens (C7/C13b)', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiGet: ReturnType<typeof vi.fn>;
  let getBlob: ReturnType<typeof vi.fn>;
  let baixarBlob: ReturnType<typeof vi.fn>;
  let downloadReport: ReturnType<typeof vi.fn>;

  /** Resposta corrente de cada endpoint de listagem — `falhar`/`vazio` trocam UMA sem tocar nas outras. */
  let respostas: Record<string, () => Observable<any>>;

  const ok = (linha: unknown) => () => of({ data: [linha], meta: { ...META } });
  const vazio = () => () => of({ data: [], meta: { ...META, total: 0, pages: 0 } });
  const falha = (err: unknown = ERRO_500) => () => throwError(() => err);

  beforeEach(async () => {
    respostas = {
      [EP_OP_AGRUPADO]: ok(SESSAO),
      [EP_OP_PLANO]: ok(ENTRADA),
      [EP_ANOM]: ok(ANORMALIDADE),
      [EP_CHK]: ok(CHECKLIST),
    };
    // Roteia por endpoint: é assim que o spec distingue as três tabelas (e o modo do `opCtrl`).
    apiGetList = vi.fn((endpoint: string) => respostas[endpoint]());
    // `ngOnInit` também chama `loadRdsAnos()` (GET /rds/anos); `/rds/meses`, entradas-sessão e
    // detalhe do checklist saem do mesmo `get` — mockar todos evita erro obscuro de método ausente.
    apiGet = vi.fn().mockReturnValue(of({ data: [] }));
    getBlob = vi.fn().mockReturnValue(of(new Blob()));
    baixarBlob = vi.fn();
    downloadReport = vi.fn();

    await TestBed.configureTestingModule({
      imports: [AdminOperacaoAudioComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink (cards de navegação / voltar)
        {
          provide: ApiService,
          useValue: { getList: apiGetList, get: apiGet, getBlob, baixarBlob, downloadReport },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  /** Render completo: `detectChanges` roda o `ngOnInit` (as 3 cargas + os anos do RDS, síncronas). */
  async function renderizar(): Promise<ComponentFixture<AdminOperacaoAudioComponent>> {
    const fixture = TestBed.createComponent(AdminOperacaoAudioComponent);
    await estabilizar(fixture);
    return fixture;
  }

  /** O template tem `ngModel` → o DOM só é confiável depois do `whenStable`. */
  async function estabilizar(fixture: ComponentFixture<AdminOperacaoAudioComponent>): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  // As 3 tabelas principais, na ordem do template (as sub-tabelas dos acordeões são `.sub-table`,
  // e nenhum acordeão é expandido aqui — os índices são estáveis).
  const tabelas = (f: ComponentFixture<AdminOperacaoAudioComponent>) =>
    f.debugElement.queryAll(By.css('table.data-table'));
  const tabOp = (f: ComponentFixture<AdminOperacaoAudioComponent>) => tabelas(f)[0];
  const tabAnom = (f: ComponentFixture<AdminOperacaoAudioComponent>) => tabelas(f)[1];
  const tabChk = (f: ComponentFixture<AdminOperacaoAudioComponent>) => tabelas(f)[2];

  /** A caixa de erro DAQUELA tabela (o canal é por tabela — o erro de uma não vaza para as outras). */
  const caixa = (tabela: DebugElement) => tabela.query(By.directive(ErroCargaComponent));
  const textoDaTabela = (tabela: DebugElement) =>
    (tabela.query(By.css('tbody')).nativeElement as HTMLElement).textContent ?? '';

  /** Clique REAL no "Tentar novamente" da caixa daquela tabela. */
  function clicarRetry(tabela: DebugElement): void {
    (tabela.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
  }

  /** Quantas vezes a listagem daquele endpoint foi PEDIDA (prova do retry). */
  const chamadas = (endpoint: string) =>
    apiGetList.mock.calls.filter(c => c[0] === endpoint).length;

  // ═══════════════════════════════════════════════════════════════════
  // Carga inicial — o ponto de partida das demais provas
  // ═══════════════════════════════════════════════════════════════════
  describe('carga inicial', () => {
    it('pede as 3 listagens (operações no modo AGRUPADO, o default) e os anos do RDS', async () => {
      await renderizar();
      expect(chamadas(EP_OP_AGRUPADO)).toBe(1);
      expect(chamadas(EP_OP_PLANO)).toBe(0);   // o checkbox "Agrupar por local" nasce marcado
      expect(chamadas(EP_ANOM)).toBe(1);
      expect(chamadas(EP_CHK)).toBe(1);
      expect(apiGet).toHaveBeenCalledWith('/api/admin/operacoes/rds/anos');
    });

    it('tudo OK: as 3 tabelas exibem suas linhas e nenhuma caixa de erro existe na tela', async () => {
      const fixture = await renderizar();
      expect(textoDaTabela(tabOp(fixture))).toContain('Plenário 2');
      expect(textoDaTabela(tabAnom(fixture))).toContain('Microfone da bancada sem áudio');
      expect(textoDaTabela(tabChk(fixture))).toContain('Ana Prado');
      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Registros de Operação — MODO AGRUPADO (default)
  // ═══════════════════════════════════════════════════════════════════
  describe('Registros de Operação (modo agrupado)', () => {
    it('falha na carga: caixa de erro com role="alert" e a mensagem do canal, SEM "Nenhuma sessão."', async () => {
      // Sem o canal, esta falha se passava por "não houve sessão nenhuma" — leitura falsa que o
      // admin levaria adiante (inclusive para o RDS/relatório do período).
      respostas[EP_OP_AGRUPADO] = falha();
      const fixture = await renderizar();

      const box = caixa(tabOp(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();   // anunciado ao leitor de tela
      expect(textoDaTabela(tabOp(fixture))).toContain(MSG_500);
      expect(textoDaTabela(tabOp(fixture))).not.toContain('Nenhuma sessão.');
    });

    it('"Tentar novamente" re-dispara a carga DA TABELA (2º GET no endpoint das operações)', async () => {
      respostas[EP_OP_AGRUPADO] = falha();
      const fixture = await renderizar();
      expect(chamadas(EP_OP_AGRUPADO)).toBe(1);

      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_OP_AGRUPADO)).toBe(2);   // o clique no DOM chegou ao opCtrl.load()
      expect(chamadas(EP_ANOM)).toBe(1);          // e não recarregou as vizinhas
      expect(chamadas(EP_CHK)).toBe(1);
    });

    it('retry com sucesso: a caixa some e as sessões aparecem', async () => {
      respostas[EP_OP_AGRUPADO] = falha();
      const fixture = await renderizar();

      respostas[EP_OP_AGRUPADO] = ok(SESSAO);   // o backend voltou
      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(caixa(tabOp(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Plenário 2');
      expect(fixture.componentInstance.opCtrl.erro()).toBe('');
    });

    it('vazio LEGÍTIMO (200 com data:[]): "Nenhuma sessão." e nenhuma caixa de erro', async () => {
      // O outro lado da moeda: o canal não pode transformar ausência de dados em alarme falso.
      respostas[EP_OP_AGRUPADO] = vazio();
      const fixture = await renderizar();

      expect(textoDaTabela(tabOp(fixture))).toContain('Nenhuma sessão.');
      expect(caixa(tabOp(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação some (não exibe o total anterior)', async () => {
      const fixture = await renderizar();
      const secaoOp = fixture.debugElement.queryAll(By.css('section'))[0];
      expect(secaoOp.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_OP_AGRUPADO] = falha();
      fixture.componentInstance.opCtrl.load();   // recarga (paginação, sort, filtro, busca)
      await estabilizar(fixture);

      expect(fixture.componentInstance.opCtrl.meta()).toBeNull();
      expect(secaoOp.query(By.css('.pag-info'))).toBeNull();   // "3 registros" não sobrevive à falha
      expect(caixa(tabOp(fixture))).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Registros de Operação — MODO LISTA PLANA (o MESMO controlador, outro ramo do template)
  // ═══════════════════════════════════════════════════════════════════
  describe('Registros de Operação (modo lista plana)', () => {
    /** Desmarca "Agrupar por local": o `opCtrl` passa a pedir `/operacoes/entradas`. */
    async function desagrupar(fixture: ComponentFixture<AdminOperacaoAudioComponent>): Promise<void> {
      fixture.componentInstance.groupBySala = false;
      fixture.componentInstance.onGroupChange();   // reseta página/filtros e recarrega
      await estabilizar(fixture);
    }

    it('o canal está nos DOIS ramos: erro em /operacoes/entradas mostra a caixa e não "Nenhuma entrada."', async () => {
      // O modo plano é outro bloco de template com outro <tbody>; um canal só no modo agrupado
      // deixaria METADE da tela mentindo — e é o modo plano que alimenta o relatório de entradas.
      const fixture = await renderizar();
      respostas[EP_OP_PLANO] = falha();

      await desagrupar(fixture);

      const box = caixa(tabOp(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(textoDaTabela(tabOp(fixture))).not.toContain('Nenhuma entrada.');
    });

    it('retry no modo plano pede o endpoint das ENTRADAS (não o do agrupado) e o sucesso repovoa a tabela', async () => {
      const fixture = await renderizar();
      respostas[EP_OP_PLANO] = falha();
      await desagrupar(fixture);
      expect(chamadas(EP_OP_PLANO)).toBe(1);

      respostas[EP_OP_PLANO] = ok(ENTRADA);
      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_OP_PLANO)).toBe(2);
      expect(chamadas(EP_OP_AGRUPADO)).toBe(1);   // o endpoint dinâmico seguiu o modo corrente
      expect(caixa(tabOp(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
    });

    it('vazio LEGÍTIMO no modo plano: "Nenhuma entrada." sem caixa de erro', async () => {
      const fixture = await renderizar();
      respostas[EP_OP_PLANO] = vazio();
      await desagrupar(fixture);

      expect(textoDaTabela(tabOp(fixture))).toContain('Nenhuma entrada.');
      expect(caixa(tabOp(fixture))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Relatórios de Anormalidades
  // ═══════════════════════════════════════════════════════════════════
  describe('Relatórios de Anormalidades', () => {
    it('falha na carga: caixa com role="alert" e a mensagem, SEM "Nenhuma anormalidade."', async () => {
      // A pior das três leituras falsas: "nenhuma anormalidade" é conclusão operacional (nada a
      // apurar no período), e sairia de um 500 silencioso.
      respostas[EP_ANOM] = falha();
      const fixture = await renderizar();

      const box = caixa(tabAnom(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();
      expect(textoDaTabela(tabAnom(fixture))).not.toContain('Nenhuma anormalidade.');
    });

    it('"Tentar novamente" re-pede só as anormalidades; o sucesso limpa a caixa e traz as linhas', async () => {
      respostas[EP_ANOM] = falha();
      const fixture = await renderizar();

      respostas[EP_ANOM] = ok(ANORMALIDADE);
      clicarRetry(tabAnom(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_ANOM)).toBe(2);
      expect(chamadas(EP_OP_AGRUPADO)).toBe(1);
      expect(chamadas(EP_CHK)).toBe(1);
      expect(caixa(tabAnom(fixture))).toBeNull();
      expect(textoDaTabela(tabAnom(fixture))).toContain('Microfone da bancada sem áudio');
    });

    it('vazio LEGÍTIMO: "Nenhuma anormalidade." sem caixa de erro', async () => {
      respostas[EP_ANOM] = vazio();
      const fixture = await renderizar();
      expect(textoDaTabela(tabAnom(fixture))).toContain('Nenhuma anormalidade.');
      expect(caixa(tabAnom(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação da seção some', async () => {
      const fixture = await renderizar();
      const secaoAnom = fixture.debugElement.queryAll(By.css('section'))[1];
      expect(secaoAnom.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_ANOM] = falha();
      fixture.componentInstance.anomCtrl.load();
      await estabilizar(fixture);

      expect(fixture.componentInstance.anomCtrl.meta()).toBeNull();
      expect(secaoAnom.query(By.css('.pag-info'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Verificação de Plenários (checklists)
  // ═══════════════════════════════════════════════════════════════════
  describe('Verificação de Plenários', () => {
    it('falha na carga: caixa com role="alert" e a mensagem, SEM "Nenhum checklist encontrado."', async () => {
      respostas[EP_CHK] = falha();
      const fixture = await renderizar();

      const box = caixa(tabChk(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();
      expect(textoDaTabela(tabChk(fixture))).not.toContain('Nenhum checklist encontrado.');
    });

    it('"Tentar novamente" re-pede só os checklists; o sucesso limpa a caixa e traz as linhas', async () => {
      respostas[EP_CHK] = falha();
      const fixture = await renderizar();

      respostas[EP_CHK] = ok(CHECKLIST);
      clicarRetry(tabChk(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_CHK)).toBe(2);
      expect(chamadas(EP_OP_AGRUPADO)).toBe(1);
      expect(chamadas(EP_ANOM)).toBe(1);
      expect(caixa(tabChk(fixture))).toBeNull();
      expect(textoDaTabela(tabChk(fixture))).toContain('Plenário 7');
    });

    it('vazio LEGÍTIMO: "Nenhum checklist encontrado." sem caixa de erro', async () => {
      respostas[EP_CHK] = vazio();
      const fixture = await renderizar();
      expect(textoDaTabela(tabChk(fixture))).toContain('Nenhum checklist encontrado.');
      expect(caixa(tabChk(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação da seção some', async () => {
      const fixture = await renderizar();
      const secaoChk = fixture.debugElement.queryAll(By.css('section'))[2];
      expect(secaoChk.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_CHK] = falha();
      fixture.componentInstance.chkCtrl.load();
      await estabilizar(fixture);

      expect(fixture.componentInstance.chkCtrl.meta()).toBeNull();
      expect(secaoChk.query(By.css('.pag-info'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Isolamento — 3 controladores, 3 canais independentes
  // ═══════════════════════════════════════════════════════════════════
  describe('isolamento entre as 3 tabelas', () => {
    it('só as operações falham: a caixa aparece nelas e as outras duas seguem com suas linhas', async () => {
      respostas[EP_OP_AGRUPADO] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
      expect(caixa(tabOp(fixture))).not.toBeNull();
      expect(caixa(tabAnom(fixture))).toBeNull();
      expect(caixa(tabChk(fixture))).toBeNull();
      expect(textoDaTabela(tabAnom(fixture))).toContain('Microfone da bancada sem áudio');
      expect(textoDaTabela(tabChk(fixture))).toContain('Ana Prado');
    });

    it('só as anormalidades falham: operações e checklists intactos (inclusive o rodapé deles)', async () => {
      respostas[EP_ANOM] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
      expect(caixa(tabAnom(fixture))).not.toBeNull();
      expect(caixa(tabOp(fixture))).toBeNull();
      expect(caixa(tabChk(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Plenário 2');
      expect(fixture.componentInstance.opCtrl.meta()).toEqual(META);
      expect(fixture.componentInstance.chkCtrl.meta()).toEqual(META);
      expect(fixture.componentInstance.anomCtrl.meta()).toBeNull();
    });

    it('as 3 falham juntas (backend fora do ar): 3 caixas, uma por tabela, e nenhuma frase de vazio', async () => {
      respostas[EP_OP_AGRUPADO] = falha();
      respostas[EP_ANOM] = falha();
      respostas[EP_CHK] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(3);
      const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(texto).not.toContain('Nenhuma sessão.');
      expect(texto).not.toContain('Nenhuma anormalidade.');
      expect(texto).not.toContain('Nenhum checklist encontrado.');
    });

    it('o retry de UMA tabela não ressuscita as outras: recuperar as operações deixa a caixa das anormalidades de pé', async () => {
      respostas[EP_OP_AGRUPADO] = falha();
      respostas[EP_ANOM] = falha();
      const fixture = await renderizar();

      respostas[EP_OP_AGRUPADO] = ok(SESSAO);
      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(caixa(tabOp(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Plenário 2');
      expect(caixa(tabAnom(fixture))).not.toBeNull();   // continua falhando — e continua avisando
      expect(chamadas(EP_ANOM)).toBe(1);                // o retry não recarregou a vizinha
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Controles da tela durante o erro (não repetir o F44: perder a saída junto com os dados)
  // ═══════════════════════════════════════════════════════════════════
  it('com as 3 listagens em erro, busca, "Agrupar por local", RDS e relatórios continuam na tela', async () => {
    respostas[EP_OP_AGRUPADO] = falha();
    respostas[EP_ANOM] = falha();
    respostas[EP_CHK] = falha();
    const fixture = await renderizar();

    expect(fixture.debugElement.queryAll(By.css('.search-input')).length).toBe(3);   // uma por seção
    expect(fixture.debugElement.query(By.css('.check-label input'))).not.toBeNull(); // agrupar por local
    expect(fixture.debugElement.query(By.css('.btn-rds'))).not.toBeNull();           // gerar RDS
    expect(fixture.debugElement.queryAll(By.css('.btn-report')).length).toBeGreaterThan(0);
  });
});

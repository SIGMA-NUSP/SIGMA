import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { BancoHorasPessoalComponent } from './banco-horas-pessoal.component';
import { ErroCargaComponent } from './erro-carga.component';
import { MesAnoSelectorComponent } from './mes-ano-selector.component';
import { DiaEstado } from './mini-calendario.component';
import { ToastService } from './toast.component';

/**
 * T29 — BancoHorasPessoalComponent (shared, 329 LOC — card "Banco de horas" do /ponto,
 * E7): saldo, seleção de dias no calendário, solicitar/cancelar folga.
 *
 * Estratégia (manual de PAGE do T22/T23/T24 + padrões do módulo Ponto fixados no T28):
 * TestBed cria o componente (DI + signals) SEM `detectChanges()` — `ngOnInit` é chamado à
 * mão e os filhos (`app-mini-calendario`, `app-mes-ano-selector`, tabela) nunca são
 * instanciados. `ApiService`/`ToastService` mockados via `useValue`. O spec trava LÓGICA e
 * ESTADO (signals/computeds/payloads), nunca DOM/CSS (ressalva do GATE: o layout do módulo
 * ainda pode mudar).
 *
 * Relógio congelado (`{toFake:['Date']}`) ANTES de `createComponent`: `anoMes` é lido no
 * field initializer. Sem `setTimeout` no SUT — falsificar só `Date` preserva os timers do
 * scheduler zoneless (decisão 1 do T28: drenar timer acorda a change detection e re-executa
 * o `ngOnInit`).
 *
 * **Contrato com o `mini-calendario` (o que o estágio manda travar):** o calendário em si já
 * está coberto (T20); aqui trava-se o que `estadoDia(d)` devolve por dia a partir do payload
 * (`dias_bloqueados` + saldo + seleção) — selecionável/selecionado/bloqueado/"Carregando...".
 *
 * ⚠️ Divergência do prompt do estágio (o código vence): "solicitar/cancelar … erros 400/409 →
 * toast" só vale para **cancelar**. O erro de `solicitar()` NÃO vai para o toast: é escrito no
 * signal `erroAcao` (caixa de erro dentro do card) — travado como está.
 *
 * **C8 — F43/F44/F45 CORRIGIDOS**: os três eram facetas do mesmo caminho de carga e foram
 * fechados como um redesenho único de `carregarBanco()` (token de recência + invalidação do mês
 * carregado + canais separados de erro fatal/transitório). Os `caracteriza Fn` viraram
 * `corrige Fn`, e há um bloco de **render** ao final: como no C7, travar só os signals deixaria
 * a suíte verde com o defeito de volta na tela (bastaria apagar o `@else if` do template).
 */

/** Payload representativo de `GET /api/ponto/banco` (julho/2026, carga 30h → débito 360 min/dia). */
function payloadBanco(over: Record<string, unknown> = {}) {
  return {
    saldo_min: 900,             // +15:00
    saldo_fmt: '+15:00',
    carga_horaria: 30,
    folgas_mes: 2,
    dias_bloqueados: [
      { data: '2026-07-06', motivo: 'Você está escalado neste dia' },
      { data: '2026-07-20', motivo: 'Já existe solicitação para este dia' },
    ],
    ...over,
  };
}

/** Linha da tabela "Minhas Solicitações" (`GET /api/ponto/banco/solicitacoes`). */
const SOLIC_PENDENTE = { id: 'sol-1', data_folga: '2026-07-15', status: 'PENDENTE' as const };
const META = { page: 1, limit: 10, total: 1, pages: 1 };

// ── Erros, no shape REAL do backend: `{ok:false, error:"…"}` (GlobalExceptionHandler) ──
/** Q17 — único erro FATAL do GET /api/ponto/banco: gate de carga horária, 409 (C8/F44). */
const MSG_Q17 = 'Sua carga horária não está cadastrada corretamente. Procure a Gestão de Pessoas.';
const Q17_409 = { status: 409, error: { ok: false, error: MSG_Q17 } };
/** 500 do backend: a mensagem do corpo é sempre genérica — por isso a guia da TELA vem na frente. */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };

/** Guias da tela (C8) — o texto que o usuário efetivamente lê nas caixas de erro. */
const GUIA_CARGA =
  'Não foi possível carregar o seu banco de horas deste mês. Sem ele não dá para marcar dias — ' +
  'tente novamente ou escolha outro mês.';
const GUIA_SOLICITACOES =
  'Não foi possível carregar as suas solicitações de folga. Pode haver pedidos pendentes ou já ' +
  'aprovados que não aparecem aqui.';

describe('BancoHorasPessoalComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiPatch: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let confirmSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    apiGet = vi.fn().mockReturnValue(of({ data: payloadBanco() }));
    apiGetList = vi.fn().mockReturnValue(of({ data: [SOLIC_PENDENTE], meta: { page: 1, limit: 10, total: 1, pages: 1 } }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    apiPatch = vi.fn().mockReturnValue(of({ ok: true }));
    toastSuccess = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [BancoHorasPessoalComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, getList: apiGetList, post: apiPost, patch: apiPatch } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Congela o relógio e cria o componente (`anoMes` é lido na construção). */
  function criar(hoje = '2026-07-12T10:00:00-03:00'): BancoHorasPessoalComponent {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    return TestBed.createComponent(BancoHorasPessoalComponent).componentInstance;
  }

  /** Componente com o banco já carregado (sem `detectChanges` não há ciclo de vida). */
  function criarCarregado(hoje?: string): BancoHorasPessoalComponent {
    const comp = criar(hoje);
    comp.ngOnInit();
    return comp;
  }

  /** `estadoDia` é `protected` (contrato com o mini-calendário). */
  function estadoDe(comp: BancoHorasPessoalComponent, iso: string): DiaEstado | null {
    const [a, m, d] = iso.split('-').map(Number);
    return (comp as any).estadoDia(new Date(a, m - 1, d));
  }

  /** Marca dias pelo mesmo caminho do calendário (emit do filho → toggleDia). */
  function marcar(comp: BancoHorasPessoalComponent, ...isos: string[]): void {
    for (const iso of isos) {
      const [a, m, d] = iso.split('-').map(Number);
      comp.toggleDia(new Date(a, m - 1, d));
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // Carga do saldo (GET /api/ponto/banco) + tabela de solicitações
  // ═══════════════════════════════════════════════════════════════════
  describe('carga inicial', () => {
    it('pede o banco do mês corrente do relógio e carrega a tabela de solicitações', () => {
      criarCarregado();
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 7 });
      expect(apiGetList).toHaveBeenCalledWith('/api/ponto/banco/solicitacoes',
        expect.objectContaining({ page: 1, limit: 10, sort: 'data_folga', direction: 'desc' }));
    });

    it('aplica o payload: dados, mês carregado e fim do carregando', () => {
      const comp = criarCarregado();
      expect(comp.carregando()).toBe(false);
      expect(comp.dados()).toMatchObject({ saldo_min: 900, carga_horaria: 30, folgas_mes: 2 });
      expect((comp as any).mesCarregado()).toEqual({ ano: 2026, mes: 7 });
      expect(comp.erroBloqueio()).toBe('');
      expect(comp.ctrl.rows()).toEqual([SOLIC_PENDENTE]);
    });

    it('Q17 (409 do gate de carga horária) bloqueia o card com a mensagem do backend', () => {
      // Shape REAL do backend (`GlobalExceptionHandler`: {ok:false, error:"…"}) — o `{message:…}`
      // que os specs antigos usavam este backend não emite (lição do C7).
      apiGet.mockReturnValue(throwError(() => Q17_409));
      const comp = criarCarregado();
      expect(comp.erroBloqueio()).toBe(MSG_Q17);
      expect(comp.erroCarga()).toBe('');     // fatal não vaza para o canal transitório
      expect(comp.carregando()).toBe(false);
      expect(comp.dados()).toBeNull();
    });

    it('409 sem corpo cai na mensagem padrão do bloqueio', () => {
      apiGet.mockReturnValue(throwError(() => ({ status: 409 })));
      const comp = criarCarregado();
      expect(comp.erroBloqueio()).toBe('Erro ao carregar o banco de horas.');
    });

    it('resposta de um mês já abandonado é descartada (guard de corrida)', () => {
      const julho = new Subject<any>();
      apiGet.mockReturnValueOnce(julho).mockReturnValueOnce(of({ data: payloadBanco({ saldo_min: 60, folgas_mes: 9 }) }));

      const comp = criarCarregado();          // GET de julho em voo
      comp.onMesAno({ ano: 2026, mes: 8 });   // usuário troca para agosto → 2º GET resolve na hora
      expect(comp.dados()?.folgas_mes).toBe(9);

      julho.next({ data: payloadBanco({ saldo_min: 900, folgas_mes: 2 }) });   // julho chega atrasado
      julho.complete();

      expect(comp.dados()?.folgas_mes).toBe(9);                      // agosto permanece
      expect((comp as any).mesCarregado()).toEqual({ ano: 2026, mes: 8 });
    });

    it('erro de um mês já abandonado não bloqueia o card', () => {
      const julho = new Subject<any>();
      apiGet.mockReturnValueOnce(julho).mockReturnValueOnce(of({ data: payloadBanco() }));

      const comp = criarCarregado();
      comp.onMesAno({ ano: 2026, mes: 8 });
      julho.error({ error: { message: 'timeout de julho' } });

      expect(comp.erroBloqueio()).toBe('');
      expect(comp.carregando()).toBe(false);
    });

    it('corrige F43 — entre duas respostas do MESMO mês vence a mais NOVA, não a que chegar por último', () => {
      // C8/F43: o guard antigo comparava apenas ano/mês do pedido com o exibido — descartava a
      // resposta de um mês abandonado, mas aceitava AS DUAS de dois pedidos do mesmo mês. Agora um
      // token de recência descarta tudo que não seja a carga mais recente: a resposta atrasada da
      // 1ª não sobrescreve mais a 2ª (que é a que reflete a mutação que o usuário acabou de fazer).
      const primeira = new Subject<any>();
      const segunda = new Subject<any>();
      apiGet.mockReturnValueOnce(primeira).mockReturnValueOnce(segunda);

      const comp = criarCarregado();          // GET#1 (estado antigo) em voo
      comp.carregarBanco();                   // GET#2 (estado novo) em voo — mesmo mês

      segunda.next({ data: payloadBanco({ saldo_min: 900, dias_bloqueados: [] }) });   // verdade atual
      expect(comp.saldoVisualMin()).toBe(900);

      primeira.next({ data: payloadBanco({ saldo_min: 540, dias_bloqueados: [{ data: '2026-07-22', motivo: 'Solicitação pendente' }] }) });

      expect(comp.saldoVisualMin()).toBe(900);                       // a resposta velha é DESCARTADA
      expect(estadoDe(comp, '2026-07-22')).toBeNull();               // o dia liberado continua livre
      expect((comp as any).mesCarregado()).toEqual({ ano: 2026, mes: 7 });
    });

    it('corrige F43 — duas mutações em sequência (o gatilho real): a recarga da 2ª manda', () => {
      // O caminho pelo qual o usuário topava com o F43 sem fazer nada de estranho: `cancelando` é
      // zerado no `next` do PATCH ANTES de `carregarBanco()`, então cancelar duas solicitações em
      // sequência dispara dois GETs do mesmo mês — e o 1º costuma chegar depois do 2º.
      const recargaA = new Subject<any>();
      const recargaB = new Subject<any>();
      const comp = criarCarregado();
      apiGet.mockReturnValueOnce(recargaA).mockReturnValueOnce(recargaB);

      comp.cancelarSolicitacao(SOLIC_PENDENTE);                        // recarga A (1 folga cancelada)
      comp.cancelarSolicitacao({ ...SOLIC_PENDENTE, id: 'sol-2' });    // recarga B (2 folgas canceladas)

      recargaB.next({ data: payloadBanco({ saldo_min: 1620 }) });      // estado após os DOIS cancelamentos
      recargaA.next({ data: payloadBanco({ saldo_min: 1260 }) });      // resposta atrasada da 1ª

      expect(comp.saldoVisualMin()).toBe(1620);   // o saldo exibido é o de agora, não o intermediário
    });

    it('corrige F44 — falha transitória NÃO bloqueia o card: erro no canal transitório, com o fatal intacto', () => {
      // C8/F44: `erroBloqueio` era o canal ÚNICO — todo 500/timeout matava o card inteiro (some o
      // `app-mes-ano-selector`, único gatilho de nova carga) e, como o pai mantém o componente em
      // `[hidden]`, nem fechar/reabrir recuperava. Agora o transporte vai para `erroCarga`, que o
      // template exibe DENTRO do card, com retry (render provado no bloco de render, abaixo).
      const comp = criarCarregado();                     // julho OK
      apiGet.mockReturnValue(throwError(() => ERRO_500));
      comp.onMesAno({ ano: 2026, mes: 8 });              // blip de rede na troca de mês

      expect(comp.erroBloqueio()).toBe('');                                   // o canal fatal fica LIMPO
      expect(comp.erroCarga()).toBe(`${GUIA_CARGA} (Erro interno do servidor)`);
      expect(comp.dados()).not.toBeNull();               // o payload anterior segue exibido
      expect(comp.carregando()).toBe(false);
    });

    it('corrige F44 — o retry re-dispara a carga do mês exibido e o sucesso limpa o erro', () => {
      const comp = criarCarregado();
      apiGet.mockReturnValue(throwError(() => ERRO_500));
      comp.onMesAno({ ano: 2026, mes: 8 });
      expect(comp.erroCarga()).not.toBe('');
      apiGet.mockClear().mockReturnValue(of({ data: payloadBanco({ saldo_min: 120, folgas_mes: 0 }) }));

      comp.carregarBanco();                              // o botão "Tentar novamente" da caixa

      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 8 });   // o mês EXIBIDO
      expect(comp.erroCarga()).toBe('');
      expect(comp.dados()?.saldo_min).toBe(120);
      expect((comp as any).mesCarregado()).toEqual({ ano: 2026, mes: 8 });
    });

    it('corrige F44 — erro transitório sem corpo cai na guia da tela (o backend não manda mensagem no 500)', () => {
      apiGet.mockReturnValue(throwError(() => ({ status: 503 })));
      const comp = criarCarregado();
      expect(comp.erroCarga()).toBe(GUIA_CARGA);
      expect(comp.erroBloqueio()).toBe('');
    });

    it('corrige F44 — durante o erro do mês, o calendário não mente "Carregando..."', () => {
      const comp = criarCarregado();
      apiGet.mockReturnValue(throwError(() => ERRO_500));
      comp.onMesAno({ ano: 2026, mes: 8 });
      expect(estadoDe(comp, '2026-08-10')).toEqual({
        desabilitado: true, rotulo: 'Não foi possível carregar este mês',
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Saldo visual = saldo do backend − dias marcados × débito (C-3.2)
  // ═══════════════════════════════════════════════════════════════════
  describe('saldo e débito visual', () => {
    it('sem seleção, exibe o saldo do backend formatado', () => {
      const comp = criarCarregado();
      expect(comp.saldoVisualMin()).toBe(900);
      expect(comp.saldoVisualFmt()).toBe('+15:00');
    });

    it('carga 30h → débito de 360 min por dia marcado', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13', '2026-07-14');
      expect(comp.saldoVisualMin()).toBe(900 - 720);
      expect(comp.saldoVisualFmt()).toBe('+03:00');
    });

    it('carga 40h → débito de 480 min por dia marcado', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ carga_horaria: 40, saldo_min: 1000 }) }));
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      expect(comp.saldoVisualMin()).toBe(520);
    });

    it('qualquer carga diferente de 30 debita 480 min (caracterização do ternário)', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ carga_horaria: 20, saldo_min: 1000 }) }));
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      expect(comp.saldoVisualMin()).toBe(520);
    });

    it('saldo negativo é formatado com sinal', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ saldo_min: -125 }) }));
      const comp = criarCarregado();
      expect(comp.saldoVisualFmt()).toBe('-02:05');
    });

    it('sem dados ainda, o saldo exibido é zero', () => {
      const comp = criar();   // sem ngOnInit
      expect(comp.saldoVisualMin()).toBe(0);
      expect(comp.saldoVisualFmt()).toBe('+00:00');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // estadoDia — contrato com o <app-mini-calendario> (Q24/C-3.2/C-3.3)
  // ═══════════════════════════════════════════════════════════════════
  describe('estadoDia (contrato com o mini-calendário)', () => {
    it('antes da resposta do mês, todo dia vem desabilitado com "Carregando..."', () => {
      const comp = criar();   // nada carregado → mesCarregado null
      expect(estadoDe(comp, '2026-07-13')).toEqual({ desabilitado: true, rotulo: 'Carregando...' });
    });

    it('mês exibido diferente do mês carregado (troca em voo) volta a "Carregando..."', () => {
      const emVoo = new Subject<any>();
      const comp = criarCarregado();                 // julho aplicado
      apiGet.mockReturnValue(emVoo);
      comp.onMesAno({ ano: 2026, mes: 8 });          // agosto exibido, resposta pendente
      expect(estadoDe(comp, '2026-08-10')).toEqual({ desabilitado: true, rotulo: 'Carregando...' });
      emVoo.next({ data: payloadBanco() });
      expect(estadoDe(comp, '2026-08-10')).toBeNull();
    });

    it('dia bloqueado pelo backend vem desabilitado com o motivo', () => {
      const comp = criarCarregado();
      expect(estadoDe(comp, '2026-07-06')).toEqual({ desabilitado: true, rotulo: 'Você está escalado neste dia' });
      expect(estadoDe(comp, '2026-07-20')).toEqual({
        desabilitado: true, rotulo: 'Já existe solicitação para este dia',
      });
    });

    it('dia livre com saldo suficiente não tem override (null → comportamento base)', () => {
      const comp = criarCarregado();
      expect(estadoDe(comp, '2026-07-13')).toBeNull();
    });

    it('dia marcado vem selecionado + com o retângulo "×"', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      expect(estadoDe(comp, '2026-07-13')).toEqual({
        selecionado: true, marcado: true, rotulo: 'Selecionado — clique para desmarcar',
      });
    });

    it('sem saldo para mais um dia, os não-marcados desabilitam (C-3.3) e os marcados continuam marcados', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ saldo_min: 400 }) }));   // 400 < 2×360
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');                                                // saldo visual = 40
      expect(estadoDe(comp, '2026-07-14')).toEqual({
        desabilitado: true, rotulo: 'Saldo insuficiente para mais um dia',
      });
      expect(estadoDe(comp, '2026-07-13')).toMatchObject({ selecionado: true, marcado: true });
    });

    it('saldo exatamente igual ao débito de um dia ainda permite marcar', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ saldo_min: 360 }) }));
      const comp = criarCarregado();
      expect(estadoDe(comp, '2026-07-13')).toBeNull();
      marcar(comp, '2026-07-13');
      expect(estadoDe(comp, '2026-07-14')).toMatchObject({ desabilitado: true });
    });

    it('a seleção tem precedência sobre o bloqueio (dia marcado que passou a bloqueado segue "Selecionado")', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      // o backend passa a bloquear o dia já marcado (ex.: escala publicada depois)
      comp.dados.set({ ...comp.dados()!, dias_bloqueados: [{ data: '2026-07-13', motivo: 'Você está escalado' }] } as any);
      expect(estadoDe(comp, '2026-07-13')).toMatchObject({ selecionado: true, marcado: true });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // toggleDia / limparSelecao
  // ═══════════════════════════════════════════════════════════════════
  describe('toggleDia', () => {
    it('marca um dia livre (ISO local, sem deslocamento de fuso)', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      expect([...comp.selecionados()]).toEqual(['2026-07-13']);
    });

    it('clicar de novo no mesmo dia desmarca', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13', '2026-07-13');
      expect(comp.selecionados().size).toBe(0);
    });

    it('dia bloqueado não entra na seleção', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-06');
      expect(comp.selecionados().size).toBe(0);
    });

    it('sem saldo para mais um dia, não marca outro — mas desmarcar continua possível', () => {
      apiGet.mockReturnValue(of({ data: payloadBanco({ saldo_min: 400 }) }));
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');   // saldo visual = 40 → trava
      marcar(comp, '2026-07-14');
      expect([...comp.selecionados()]).toEqual(['2026-07-13']);
      marcar(comp, '2026-07-13');   // desmarcar sempre pode
      expect(comp.selecionados().size).toBe(0);
    });

    it('marcar limpa o erro da ação anterior', () => {
      const comp = criarCarregado();
      comp.erroAcao.set('Saldo insuficiente.');
      marcar(comp, '2026-07-13');
      expect(comp.erroAcao()).toBe('');
    });

    it('limparSelecao desmarca tudo, restaura o saldo e limpa o erro', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13', '2026-07-14');
      comp.erroAcao.set('erro anterior');
      comp.limparSelecao();
      expect(comp.selecionados().size).toBe(0);
      expect(comp.saldoVisualMin()).toBe(900);
      expect(comp.erroAcao()).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // solicitar() — POST /api/ponto/banco/solicitar
  // ═══════════════════════════════════════════════════════════════════
  describe('solicitar', () => {
    it('sem nenhum dia marcado, não chama a API', () => {
      const comp = criarCarregado();
      comp.solicitar();
      expect(apiPost).not.toHaveBeenCalled();
      expect(comp.enviando()).toBe(false);
    });

    it('envia os dias marcados em ordem cronológica e mantém "enviando" durante o voo', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      apiGet.mockReturnValue(of({ data: payloadBanco({ saldo_min: 1200 }) }));   // saldo p/ 3 dias (3×360)
      const comp = criarCarregado();
      // marcados fora de ordem: o Set preserva a ordem de inserção, então só a ordenação
      // explícita do SUT produz a sequência cronológica (3 dias — com 2, inverter a ordem de
      // inserção daria o mesmo resultado e o teste não distinguiria `sort()` de `reverse()`).
      marcar(comp, '2026-07-14', '2026-07-10', '2026-07-13');

      comp.solicitar();
      expect(comp.enviando()).toBe(true);
      expect(apiPost).toHaveBeenCalledWith('/api/ponto/banco/solicitar',
        { dias: ['2026-07-10', '2026-07-13', '2026-07-14'] });

      emVoo.next({ ok: true });
      emVoo.complete();
      expect(comp.enviando()).toBe(false);
    });

    it('sucesso: toast no plural, seleção limpa e recarga do banco + da tabela', () => {
      const comp = criarCarregado();
      apiGet.mockClear(); apiGetList.mockClear();
      marcar(comp, '2026-07-13', '2026-07-14');

      comp.solicitar();

      expect(toastSuccess).toHaveBeenCalledWith('Solicitação enviada (2 dias).');
      expect(comp.selecionados().size).toBe(0);
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 7 });
      expect(apiGetList).toHaveBeenCalledTimes(1);
      expect(comp.erroAcao()).toBe('');
    });

    it('um único dia usa o singular na mensagem', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      comp.solicitar();
      expect(toastSuccess).toHaveBeenCalledWith('Solicitação enviada (1 dia).');
    });

    it('erro 400 do backend vai para a caixa de erro do card (NÃO para o toast) e preserva a seleção', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'Saldo insuficiente.' } })));
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      apiGet.mockClear(); apiGetList.mockClear();

      comp.solicitar();

      expect(comp.erroAcao()).toBe('Saldo insuficiente.');
      expect(toastError).not.toHaveBeenCalled();
      expect(toastSuccess).not.toHaveBeenCalled();
      expect(comp.enviando()).toBe(false);
      expect([...comp.selecionados()]).toEqual(['2026-07-13']);   // o usuário pode corrigir e reenviar
      expect(apiGet).not.toHaveBeenCalled();                       // sem recarga no erro
      expect(apiGetList).not.toHaveBeenCalled();
    });

    it('erro 409 sem mensagem cai no fallback', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 409 })));
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      comp.solicitar();
      expect(comp.erroAcao()).toBe('Erro ao enviar a solicitação.');
    });

    it('corrige F45 — durante a recarga pós-sucesso o calendário trava: nada é clicável sobre o payload velho', () => {
      // C8/F45: o `next` chamava `carregarBanco()` sem religar a carga nem invalidar `mesCarregado`
      // — o guard "Carregando..." do `estadoDia`, que existe justamente para impedir interação com
      // dados velhos, só disparava na troca de mês. Na janela do round-trip o dia recém-pedido
      // aparecia livre, e remarcá-lo produzia um 400 logo depois do toast de sucesso. Agora TODA
      // recarga passa pelo mesmo caminho, e o caminho invalida o mês carregado.
      const recarga = new Subject<any>();
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      expect(comp.saldoVisualMin()).toBe(540);      // 900 − 360 (débito visual do dia marcado)
      apiGet.mockReturnValue(recarga);              // a recarga do `next` fica em voo

      comp.solicitar();

      expect((comp as any).mesCarregado()).toBeNull();                            // payload velho invalidado
      expect(estadoDe(comp, '2026-07-13')).toEqual({ desabilitado: true, rotulo: 'Carregando...' });
      expect(estadoDe(comp, '2026-07-14')).toEqual({ desabilitado: true, rotulo: 'Carregando...' });
      expect(comp.recarregando()).toBe(true);       // o card sinaliza a carga em voo ("atualizando...")
      expect(comp.carregando()).toBe(false);        // sem esconder o card (o payload anterior segue na tela)

      recarga.next({ data: payloadBanco({ saldo_min: 540, dias_bloqueados: [{ data: '2026-07-13', motivo: 'Solicitação pendente' }] }) });

      expect(comp.recarregando()).toBe(false);
      expect(comp.saldoVisualMin()).toBe(540);                                    // a verdade chega
      expect(estadoDe(comp, '2026-07-13')).toMatchObject({ desabilitado: true, rotulo: 'Solicitação pendente' });
      expect(estadoDe(comp, '2026-07-14')).toBeNull();                            // e os livres voltam a ser clicáveis
    });

    it('corrige F45 — o guard também vale para a recarga pós-CANCELAMENTO', () => {
      const recarga = new Subject<any>();
      const comp = criarCarregado();
      apiGet.mockReturnValue(recarga);

      comp.cancelarSolicitacao(SOLIC_PENDENTE);

      expect((comp as any).mesCarregado()).toBeNull();
      expect(estadoDe(comp, '2026-07-13')).toEqual({ desabilitado: true, rotulo: 'Carregando...' });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // cancelarSolicitacao() — PATCH .../solicitacao/{id}/cancelar (Q19)
  // ═══════════════════════════════════════════════════════════════════
  describe('cancelarSolicitacao', () => {
    it('pede confirmação com a data em dd/mm/aaaa e só então chama a API', () => {
      const comp = criarCarregado();
      comp.cancelarSolicitacao(SOLIC_PENDENTE);
      expect(confirmSpy).toHaveBeenCalledWith('Cancelar a solicitação do dia 15/07/2026?');
      expect(apiPatch).toHaveBeenCalledWith('/api/ponto/banco/solicitacao/sol-1/cancelar', {});
    });

    it('confirmação negada: nenhuma chamada e nenhum estado alterado', () => {
      confirmSpy.mockReturnValue(false);
      const comp = criarCarregado();
      comp.cancelarSolicitacao(SOLIC_PENDENTE);
      expect(apiPatch).not.toHaveBeenCalled();
      expect(comp.cancelando()).toBe(false);
    });

    it('sucesso: toast, saldo e tabela recarregados (o estorno vem do backend)', () => {
      const comp = criarCarregado();
      apiGet.mockClear(); apiGetList.mockClear();

      comp.cancelarSolicitacao(SOLIC_PENDENTE);

      expect(toastSuccess).toHaveBeenCalledWith('Solicitação cancelada.');
      expect(comp.cancelando()).toBe(false);
      expect(apiGet).toHaveBeenCalledTimes(1);
      expect(apiGetList).toHaveBeenCalledTimes(1);
    });

    it('mantém "cancelando" durante o voo e barra reentrância (sem 2º confirm)', () => {
      const emVoo = new Subject<any>();
      apiPatch.mockReturnValue(emVoo);
      const comp = criarCarregado();

      comp.cancelarSolicitacao(SOLIC_PENDENTE);
      expect(comp.cancelando()).toBe(true);

      comp.cancelarSolicitacao(SOLIC_PENDENTE);          // 2º clique enquanto voa
      expect(confirmSpy).toHaveBeenCalledTimes(1);
      expect(apiPatch).toHaveBeenCalledTimes(1);

      emVoo.next({ ok: true });
      emVoo.complete();
      expect(comp.cancelando()).toBe(false);
    });

    it('erro: toast de erro com a mensagem do backend e nenhuma recarga', () => {
      apiPatch.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Já deliberada.' } })));
      const comp = criarCarregado();
      apiGet.mockClear(); apiGetList.mockClear();

      comp.cancelarSolicitacao(SOLIC_PENDENTE);

      expect(toastError).toHaveBeenCalledWith('Já deliberada.');
      expect(comp.cancelando()).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
      expect(apiGetList).not.toHaveBeenCalled();
    });

    it('erro sem corpo cai no fallback', () => {
      apiPatch.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      comp.cancelarSolicitacao(SOLIC_PENDENTE);
      expect(toastError).toHaveBeenCalledWith('Erro ao cancelar.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onMesAno — o seletor mês/ano manda; a seleção pertence ao mês exibido
  // ═══════════════════════════════════════════════════════════════════
  describe('onMesAno', () => {
    it('troca o mês, desmarca os dias do mês anterior e pede o banco do mês novo', () => {
      const comp = criarCarregado();
      marcar(comp, '2026-07-13');
      apiGet.mockClear(); apiGetList.mockClear();

      comp.onMesAno({ ano: 2026, mes: 6 });

      expect(comp.anoMes()).toEqual({ ano: 2026, mes: 6 });
      expect(comp.selecionados().size).toBe(0);
      expect(comp.saldoVisualMin()).toBe(900);   // saldo restaurado
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 6 });
      expect(apiGetList).not.toHaveBeenCalled(); // a tabela "Minhas Solicitações" não é filtrada por mês
    });

    it('durante o voo do mês novo, o card segue exibindo o saldo do mês anterior (marcado como "atualizando")', () => {
      // O card não pisca a cada troca de mês: `carregando` (que esconde o miolo) só vale quando não
      // há NADA para exibir. Com payload anterior na tela é `recarregando` que sobe — e o que impede
      // interação com os dados velhos é o `estadoDia` ("Carregando...").
      const agosto = new Subject<any>();
      const comp = criarCarregado();
      apiGet.mockReturnValue(agosto);

      comp.onMesAno({ ano: 2026, mes: 8 });

      expect(comp.carregando()).toBe(false);                  // o card não some da tela
      expect(comp.recarregando()).toBe(true);
      expect(comp.dados()?.folgas_mes).toBe(2);               // valores de JULHO ainda exibidos
      expect(comp.saldoVisualMin()).toBe(900);
      expect(estadoDe(comp, '2026-08-10')).toMatchObject({ desabilitado: true, rotulo: 'Carregando...' });

      agosto.next({ data: payloadBanco({ saldo_min: 120, folgas_mes: 0, dias_bloqueados: [] }) });
      expect(comp.dados()?.folgas_mes).toBe(0);
      expect(comp.saldoVisualMin()).toBe(120);
    });

    it('os limites do calendário acompanham o mês exibido', () => {
      const comp = criarCarregado();
      comp.onMesAno({ ano: 2026, mes: 2 });      // fevereiro (28 dias em 2026)
      expect(comp.primeiroDiaMes()).toEqual(new Date(2026, 1, 1));
      expect(comp.ultimoDiaMes()).toEqual(new Date(2026, 1, 28));
    });

    it('dezembro: o último dia do mês é 31 (o cálculo com mês+1/dia 0 não estoura o ano)', () => {
      const comp = criarCarregado();
      comp.onMesAno({ ano: 2026, mes: 12 });
      expect(comp.ultimoDiaMes()).toEqual(new Date(2026, 11, 31));
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O que a TELA mostra (C8) — exceção de render deliberada
  //
  // Sem estes testes, o C8 estaria "bem projetado e mal protegido" (lição do C7): apagar o ramo
  // `@else if (erroCarga())`, o `@if (dados())` do saldo ou o `[disabled]` que o `estadoDia`
  // alimenta deixaria a suíte de signals INTEIRA verde — com os F43/F44/F45 de volta na forma em
  // que o usuário os vê. Travam-se presença/ausência de estado e o clique real, nunca CSS/layout.
  // ═══════════════════════════════════════════════════════════════════
  describe('render dos estados de erro e de recarga (F44/F45)', () => {
    function renderizar(): ComponentFixture<BancoHorasPessoalComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(BancoHorasPessoalComponent);
      fixture.detectChanges();   // ngOnInit + render
      return fixture;
    }

    const caixaErro = (f: ComponentFixture<BancoHorasPessoalComponent>) =>
      f.debugElement.query(By.directive(ErroCargaComponent));
    const seletorMes = (f: ComponentFixture<BancoHorasPessoalComponent>) =>
      f.debugElement.query(By.directive(MesAnoSelectorComponent));
    const textoDoCard = (f: ComponentFixture<BancoHorasPessoalComponent>) =>
      (f.nativeElement as HTMLElement).textContent ?? '';

    /** Botão do dia `n` DENTRO do mês exibido (o grid 7×6 também traz dias de fora). */
    function botaoDia(f: ComponentFixture<BancoHorasPessoalComponent>, n: number): HTMLButtonElement {
      const botao = f.debugElement.queryAll(By.css('button.cal-dia'))
        .map(d => d.nativeElement as HTMLButtonElement)
        .filter(b => !b.classList.contains('fora-mes'))
        .find(b => b.querySelector('.cal-dia-num')?.textContent?.trim() === String(n));
      if (!botao) throw new Error(`dia ${n} não encontrado no calendário`);
      return botao;
    }

    it('corrige F44 — erro transitório: caixa com retry DENTRO do card, e o seletor de mês continua no DOM', () => {
      apiGet.mockReturnValue(throwError(() => ERRO_500));
      const fixture = renderizar();

      const caixa = caixaErro(fixture);
      expect(caixa).not.toBeNull();
      expect(caixa.componentInstance.mensagem()).toBe(`${GUIA_CARGA} (Erro interno do servidor)`);
      expect(seletorMes(fixture)).not.toBeNull();          // o gatilho de nova carga NÃO some (F44)
      expect(textoDoCard(fixture)).toContain('Minhas Solicitações');   // nem a tabela
      expect(textoDoCard(fixture)).not.toContain('+00:00');            // e a falha não vira "saldo zero"
    });

    it('corrige F44 — o clique real no "Tentar novamente" recarrega e devolve o card', () => {
      apiGet.mockReturnValue(throwError(() => ERRO_500));
      const fixture = renderizar();
      apiGet.mockClear().mockReturnValue(of({ data: payloadBanco() }));

      (fixture.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 7 });
      expect(caixaErro(fixture)).toBeNull();                // erro limpo
      expect(textoDoCard(fixture)).toContain('+15:00');     // saldo de volta
    });

    it('corrige F44 — o FATAL (Q17) preserva o comportamento antigo: o card inteiro sai', () => {
      apiGet.mockReturnValue(throwError(() => Q17_409));
      const fixture = renderizar();

      expect(textoDoCard(fixture)).toContain(MSG_Q17);
      expect(seletorMes(fixture)).toBeNull();     // sem carga horária válida não há o que recarregar
      expect(caixaErro(fixture)).toBeNull();      // e nada de oferecer um retry que nunca resolveria
      expect(textoDoCard(fixture)).not.toContain('Minhas Solicitações');
    });

    it('corrige F45 — durante a recarga pós-solicitação os dias ficam DESABILITADOS no DOM (clique não marca)', () => {
      const fixture = renderizar();
      const comp = fixture.componentInstance;

      botaoDia(fixture, 13).click();              // o dia livre é clicável antes da mutação
      fixture.detectChanges();
      expect([...comp.selecionados()]).toEqual(['2026-07-13']);

      apiGet.mockReturnValue(new Subject<any>());   // a recarga do `next` do POST fica EM VOO
      (fixture.debugElement.query(By.css('.btn-primary-custom')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      const dia14 = botaoDia(fixture, 14);
      expect(dia14.disabled).toBe(true);                       // guard do estadoDia no DOM real
      expect(dia14.getAttribute('title')).toBe('Carregando...');
      expect(textoDoCard(fixture)).toContain('(atualizando...)');   // o saldo na tela ainda é o velho

      dia14.click();                                           // clique sobre o payload velho
      fixture.detectChanges();
      expect(comp.selecionados().size).toBe(0);                // ...não marca nada
    });

    it('a tabela de solicitações: erro na carga vira caixa com retry, não "Nenhuma solicitação registrada."', () => {
      apiGetList.mockReturnValue(throwError(() => ERRO_500));
      const fixture = renderizar();

      const tbody = fixture.debugElement.query(By.css('tbody'));
      const caixa = tbody.query(By.directive(ErroCargaComponent));
      expect(caixa).not.toBeNull();
      expect(caixa.componentInstance.mensagem()).toBe(`${GUIA_SOLICITACOES} (Erro interno do servidor)`);
      expect((tbody.nativeElement as HTMLElement).textContent).not.toContain('Nenhuma solicitação registrada.');
      expect(fixture.componentInstance.ctrl.meta()).toBeNull();   // o rodapé não exibe o total anterior
    });

    it('a tabela de solicitações: vazio de verdade continua sendo a frase do vazio, sem caixa de erro', () => {
      apiGetList.mockReturnValue(of({ data: [], meta: { ...META, total: 0 } }));
      const fixture = renderizar();

      const tbody = fixture.debugElement.query(By.css('tbody'));
      expect((tbody.nativeElement as HTMLElement).textContent).toContain('Nenhuma solicitação registrada.');
      expect(tbody.query(By.directive(ErroCargaComponent))).toBeNull();
    });

    it('a tabela de solicitações: o clique no retry refaz o load() e repovoa a lista', () => {
      apiGetList.mockReturnValue(throwError(() => ERRO_500));
      const fixture = renderizar();
      apiGetList.mockClear().mockReturnValue(of({ data: [SOLIC_PENDENTE], meta: META }));

      const botao = fixture.debugElement.query(By.css('tbody app-erro-carga button'));
      (botao.nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(apiGetList).toHaveBeenCalledTimes(1);
      const tbody = fixture.debugElement.query(By.css('tbody')).nativeElement as HTMLElement;
      expect(tbody.textContent).toContain('15/07/2026');
      expect(fixture.debugElement.query(By.css('tbody app-erro-carga'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER (C14/F37) — o funcionário alcança o mês da folha recém-publicada
  //
  // A folha de dezembro é publicada no começo de janeiro e é ela que abre o prazo de 5 dias: em
  // janeiro, o mês que o funcionário precisa consultar é DEZEMBRO. O `[anos]` do template é a
  // correção — daí o render (apagar o binding deixaria a suíte de signals inteira verde).
  // ═══════════════════════════════════════════════════════════════════
  describe('render — o seletor cruza a virada do ano (F37)', () => {
    function renderizar(hoje: string): ComponentFixture<BancoHorasPessoalComponent> {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(hoje));
      const fixture = TestBed.createComponent(BancoHorasPessoalComponent);
      fixture.detectChanges();   // ngOnInit + render
      return fixture;
    }

    const seletor = (f: ComponentFixture<BancoHorasPessoalComponent>) =>
      f.debugElement.query(By.directive(MesAnoSelectorComponent)).componentInstance as MesAnoSelectorComponent;
    const seta = (f: ComponentFixture<BancoHorasPessoalComponent>, aria: string) =>
      f.debugElement.query(By.css(`app-mes-ano-selector button[aria-label="${aria}"]`))
        .nativeElement as HTMLButtonElement;

    it('corrige F37 — em 05/01/2027 o ‹ está habilitado e o clique carrega o banco de DEZEMBRO/2026', () => {
      const fixture = renderizar('2027-01-05T09:00:00-03:00');
      const comp = fixture.componentInstance;

      expect(seletor(fixture).anos()).toEqual([2026, 2027]);
      expect(seta(fixture, 'Mês anterior').disabled).toBe(false);

      comp.toggleDia(new Date(2027, 0, 8));        // um dia marcado ANTES de navegar (senão a
      expect(comp.selecionados().size).toBe(1);    // asserção de limpeza abaixo seria vazia)
      apiGet.mockClear();

      seta(fixture, 'Mês anterior').click();       // clique real na seta ‹
      fixture.detectChanges();

      expect(comp.anoMes()).toEqual({ ano: 2026, mes: 12 });
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2026, mes: 12 });
      expect(comp.selecionados().size).toBe(0);    // idioma da tela: navegar limpa a seleção
    });

    it('corrige F37 — em 20/12/2026 o › está habilitado e o clique carrega o banco de JANEIRO/2027 (flanco simétrico na TELA)', () => {
      // Sem este caso, trocar o helper por um `[ano-1, ano]` fixo em qualquer consumidor passaria
      // verde: os demais testes de consumidor só exercitam o flanco de janeiro.
      const fixture = renderizar('2026-12-20T09:00:00-03:00');
      const comp = fixture.componentInstance;

      expect(seletor(fixture).anos()).toEqual([2026, 2027]);
      expect(seta(fixture, 'Próximo mês').disabled).toBe(false);

      apiGet.mockClear();
      seta(fixture, 'Próximo mês').click();        // clique real na seta ›
      fixture.detectChanges();

      expect(comp.anoMes()).toEqual({ ano: 2027, mes: 1 });
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/banco', { ano: 2027, mes: 1 });
    });

    it('regressão: em julho o seletor segue preso ao ano corrente (uma <option> de ano)', () => {
      const fixture = renderizar('2026-07-12T10:00:00-03:00');
      expect(seletor(fixture).anos()).toEqual([2026]);
      expect(fixture.debugElement.queryAll(By.css('app-mes-ano-selector select.sel-ano option'))).toHaveLength(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // statusLabel
  // ═══════════════════════════════════════════════════════════════════
  describe('statusLabel', () => {
    it('traduz os quatro status e devolve o valor cru para o desconhecido', () => {
      const comp = criar();
      const label = (s: string) => (comp as any).statusLabel(s);
      expect(label('PENDENTE')).toBe('Pendente');
      expect(label('APROVADO')).toBe('Aprovado');
      expect(label('REJEITADO')).toBe('Rejeitado');
      expect(label('CANCELADO')).toBe('Cancelado');
      expect(label('QUALQUER_OUTRO')).toBe('QUALQUER_OUTRO');
    });
  });
});

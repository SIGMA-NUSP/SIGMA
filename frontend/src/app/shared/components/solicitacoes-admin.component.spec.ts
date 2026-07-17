import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ErroCargaComponent } from './erro-carga.component';
import { SolicitacoesAdminComponent } from './solicitacoes-admin.component';
import { ToastService } from './toast.component';

/**
 * SolicitacoesAdminComponent (card "Banco de Horas" do /admin/ponto): fila de deliberação —
 * aprovar (confirm nativo), rejeitar (modal com motivação obrigatória, teto de 300
 * caracteres), canal de erro da fila com retry (distinto de "fila vazia", `meta` limpo),
 * recarga pós-deliberação e relatório PDF/DOCX honrando sort/busca/filtros. TestBed sem
 * `detectChanges()` por padrão — `ngOnInit` à mão; `ApiService`/`ToastService` via `useValue`;
 * `window.confirm` espionado (o jsdom não o implementa). O `TableStateController` é REAL,
 * instanciado no field initializer com o `ApiService` mockado — as recargas são observadas por
 * `api.getList`. Falsifica-se só `Date` (nada de `setTimeout` no caminho testado; preserva os
 * timers do scheduler zoneless); `formatarDataExtensoBr` monta um `Date` local a partir do ISO.
 * Contrato: `pode_deliberar` só desabilita botões no template — quem barra é o backend.
 */

/** Linha de `GET /api/admin/ponto/banco/solicitacoes`. */
function linha(over: Record<string, unknown> = {}) {
  return {
    id: 'sol-1',
    pessoa_id: 'op-1',
    pessoa_tipo: 'OPERADOR' as const,
    nome: 'Maria Souza',
    saldo_min: 930,
    data_folga: '2026-07-16',
    status: 'PENDENTE' as const,
    pode_deliberar: true,
    atrasada: false,
    ...over,
  };
}

describe('SolicitacoesAdminComponent', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let downloadReport: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let confirmSpy: ReturnType<typeof vi.spyOn>;

  const META = { page: 1, limit: 10, total: 1, pages: 1 };

  beforeEach(async () => {
    apiGetList = vi.fn().mockReturnValue(of({ data: [linha()], meta: META }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    downloadReport = vi.fn();
    toastSuccess = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [SolicitacoesAdminComponent],
      providers: [
        { provide: ApiService, useValue: { getList: apiGetList, post: apiPost, downloadReport } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function criar(): SolicitacoesAdminComponent {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    return TestBed.createComponent(SolicitacoesAdminComponent).componentInstance;
  }

  /** Componente com a fila já carregada (ngOnInit à mão — sem detectChanges não há ciclo de vida). */
  function criarCarregado(): SolicitacoesAdminComponent {
    const comp = criar();
    comp.ngOnInit();
    return comp;
  }

  // ═══════════════════════════════════════════════════════════════════
  // Fila (GET /api/admin/ponto/banco/solicitacoes)
  // ═══════════════════════════════════════════════════════════════════
  describe('carga da fila', () => {
    it('pede a fila com a ordenação composta do backend (pendentes primeiro — D-4.1)', () => {
      criarCarregado();
      expect(apiGetList).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes',
        expect.objectContaining({ page: 1, limit: 10, sort: 'padrao', direction: 'asc' }));
    });

    it('aplica o payload na tabela (linhas + meta) e encerra o loading', () => {
      const comp = criarCarregado();
      expect(comp.ctrl.rows()).toEqual([linha()]);
      expect(comp.ctrl.meta()).toEqual(META);
      expect(comp.ctrl.loading()).toBe(false);
    });

    it('falha na leitura da fila vira estado de ERRO (distinto de "fila vazia"), com o meta limpo', () => {
      // Canal de erro do `TableStateController`: a fila que falhou não pode se passar
      // por fila vazia — o admin concluiria que não há nada a deliberar e pedidos de folga ficariam
      // sem resposta. O erro é preenchido (o template troca "Nenhuma solicitação registrada." pela
      // caixa com retry) e o `meta` é LIMPO (o rodapé não pode seguir exibindo o total anterior).
      const comp = criarCarregado();                         // 1ª carga OK (1 linha, meta total=1)
      apiGetList.mockReturnValue(throwError(() => ({ status: 502, error: { message: 'Bad gateway' } })));

      comp.ctrl.load();                                      // recarga (paginação/filtro/deliberação)

      expect(comp.ctrl.erro()).toContain('Bad gateway');     // canal preenchido → tela NÃO diz "nenhuma solicitação"
      expect(comp.ctrl.rows()).toEqual([]);
      expect(comp.ctrl.loading()).toBe(false);
      expect(comp.ctrl.meta()).toBeNull();                   // meta obsoleto não sobrevive ao erro
    });

    it('sem mensagem do backend, o canal traz a mensagem da própria fila', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(throwError(() => ({ status: 500 })));

      comp.ctrl.load();

      expect(comp.ctrl.erro()).toBe(
        'Não foi possível carregar as solicitações. A fila pode ter pedidos aguardando deliberação.');
    });

    it('o retry re-dispara a carga e o sucesso limpa o erro e repovoa a fila', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(throwError(() => ({ status: 502 })));
      comp.ctrl.load();
      expect(comp.ctrl.erro()).not.toBe('');

      apiGetList.mockReturnValue(of({ data: [linha()], meta: META }));
      comp.ctrl.load();                                      // o botão "Tentar novamente" da caixa

      expect(comp.ctrl.erro()).toBe('');
      expect(comp.ctrl.rows()).toEqual([linha()]);
      expect(comp.ctrl.meta()).toEqual(META);
    });

    it('nenhum modal aberto e nenhuma deliberação em curso no início', () => {
      const comp = criarCarregado();
      expect(comp.alvoRejeicao()).toBeNull();
      expect(comp.deliberando()).toBe(false);
      expect(comp.motivoRejeicao).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O que a TELA mostra no erro — exceção de render deliberada
  // ═══════════════════════════════════════════════════════════════════
  describe('render do estado de erro', () => {
    // O canal de erro só cumpre seu papel se o TEMPLATE o consumir: sem estes testes,
    // apagar o ramo `@else if (ctrl.erro())` deixaria a suíte verde e a tela voltaria a afirmar
    // "Nenhuma solicitação registrada." numa falha de leitura. Mesma família das exceções de render
    // já autorizadas no módulo (presença/ausência de um estado, não disposição/CSS).
    function renderizar() {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(SolicitacoesAdminComponent);
      fixture.detectChanges();   // ngOnInit + render
      return fixture;
    }

    const textoDaTabela = (f: ComponentFixture<SolicitacoesAdminComponent>) =>
      (f.debugElement.query(By.css('tbody')).nativeElement as HTMLElement).textContent ?? '';

    it('fila vazia de verdade: a frase do vazio, sem caixa de erro', () => {
      apiGetList.mockReturnValue(of({ data: [], meta: { ...META, total: 0 } }));
      const fixture = renderizar();
      expect(textoDaTabela(fixture)).toContain('Nenhuma solicitação registrada.');
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    });

    it('erro na carga: caixa de erro com a mensagem e SEM a frase do vazio', () => {
      apiGetList.mockReturnValue(throwError(() => ({ status: 502, error: { ok: false, error: 'Erro interno do servidor' } })));
      const fixture = renderizar();

      const caixa = fixture.debugElement.query(By.directive(ErroCargaComponent));
      expect(caixa).not.toBeNull();
      expect(caixa.componentInstance.mensagem()).toBe(
        'Não foi possível carregar as solicitações. A fila pode ter pedidos aguardando deliberação. (Erro interno do servidor)');
      expect(textoDaTabela(fixture)).not.toContain('Nenhuma solicitação registrada.');
    });

    it('o botão da caixa re-dispara a carga (o retry chega no controller)', () => {
      apiGetList.mockReturnValue(throwError(() => ({ status: 502 })));
      const fixture = renderizar();
      apiGetList.mockClear().mockReturnValue(of({ data: [linha()], meta: META }));

      const botao = fixture.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement;
      botao.click();
      fixture.detectChanges();

      expect(apiGetList).toHaveBeenCalledTimes(1);                                    // ctrl.load()
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull(); // erro limpo
      expect(textoDaTabela(fixture)).toContain('Maria Souza');                        // fila de volta
    });

    it('busca e relatórios continuam na tela durante o erro', () => {
      apiGetList.mockReturnValue(throwError(() => ({ status: 500 })));
      const fixture = renderizar();
      expect(fixture.debugElement.query(By.css('.search-input'))).not.toBeNull();
      expect(fixture.debugElement.queryAll(By.css('.btn-report'))).toHaveLength(2);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Formatação das células (saldo, dia, status)
  // ═══════════════════════════════════════════════════════════════════
  describe('formatação das células', () => {
    it('saldo em ±HH:MM; saldo nulo (sem folha oficial) vira "--"', () => {
      const comp = criar();
      const fmt = (r: any) => (comp as any).saldoFmt(r);
      expect(fmt(linha({ saldo_min: 930 }))).toBe('+15:30');
      expect(fmt(linha({ saldo_min: -75 }))).toBe('-01:15');
      expect(fmt(linha({ saldo_min: 0 }))).toBe('+00:00');
      expect(fmt(linha({ saldo_min: null }))).toBe('--');
    });

    it('dia solicitado vem como "Dia-da-semana, dd/mm/aaaa" (D-1.2), sem deslocar o dia', () => {
      const comp = criar();
      expect((comp as any).diaSolicitado('2026-07-16')).toBe('Quinta-feira, 16/07/2026');
    });

    it('status traduzidos; desconhecido volta cru', () => {
      const comp = criar();
      const label = (s: string) => (comp as any).statusLabel(s);
      expect(label('PENDENTE')).toBe('Pendente');
      expect(label('APROVADO')).toBe('Aprovado');
      expect(label('REJEITADO')).toBe('Rejeitado');
      expect(label('CANCELADO')).toBe('Cancelado');
      expect(label('OUTRO')).toBe('OUTRO');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Aprovar — confirm() nativo (Q14) → POST .../aprovar → recarga
  // ═══════════════════════════════════════════════════════════════════
  describe('aprovar', () => {
    it('confirma com nome + dia e chama a via de lote com 1 id (Q18)', () => {
      const comp = criarCarregado();
      apiGetList.mockClear();

      comp.aprovar(linha());

      expect(confirmSpy).toHaveBeenCalledWith('Aprovar a folga de Maria Souza em 16/07/2026?');
      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacao/sol-1/aprovar', {});
      expect(toastSuccess).toHaveBeenCalledWith('Solicitação aprovada.');
      expect(apiGetList).toHaveBeenCalledTimes(1);   // recarga pós-deliberação (traz o saldo novo)
      expect(comp.deliberando()).toBe(false);
    });

    it('confirmação negada: nenhum POST e nenhuma recarga', () => {
      confirmSpy.mockReturnValue(false);
      const comp = criarCarregado();
      apiGetList.mockClear();

      comp.aprovar(linha());

      expect(apiPost).not.toHaveBeenCalled();
      expect(apiGetList).not.toHaveBeenCalled();
      expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('mantém "deliberando" durante o voo e barra o duplo clique (sem 2º confirm)', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      const comp = criarCarregado();

      comp.aprovar(linha());
      expect(comp.deliberando()).toBe(true);

      comp.aprovar(linha());                       // 2º clique enquanto voa
      expect(confirmSpy).toHaveBeenCalledTimes(1);
      expect(apiPost).toHaveBeenCalledTimes(1);

      emVoo.next({ ok: true });
      emVoo.complete();
      expect(comp.deliberando()).toBe(false);
    });

    it('erro: toast com a mensagem do backend, trava liberada e lista recarregada mesmo assim', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Solicitação já deliberada.' } })));
      const comp = criarCarregado();
      apiGetList.mockClear();

      comp.aprovar(linha());

      expect(toastError).toHaveBeenCalledWith('Solicitação já deliberada.');
      expect(toastSuccess).not.toHaveBeenCalled();
      expect(comp.deliberando()).toBe(false);
      expect(apiGetList).toHaveBeenCalledTimes(1);   // reflete o que porventura tenha sido processado
    });

    it('erro sem corpo cai no fallback', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      comp.aprovar(linha());
      expect(toastError).toHaveBeenCalledWith('Erro ao processar a deliberação.');
    });

    it('caracteriza o contrato Q34: a lógica NÃO checa pode_deliberar (quem barra é o backend — T-1.4)', () => {
      const comp = criarCarregado();
      comp.aprovar(linha({ pode_deliberar: false }));   // no template o botão estaria desabilitado
      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacao/sol-1/aprovar', {});
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Rejeitar — modal com motivação obrigatória (D-3)
  // ═══════════════════════════════════════════════════════════════════
  describe('rejeitar', () => {
    it('abrir o modal guarda o alvo e zera o motivo herdado da vez anterior', () => {
      const comp = criarCarregado();
      comp.motivoRejeicao = 'texto antigo';

      comp.abrirRejeicao(linha());

      expect(comp.alvoRejeicao()).toEqual(linha());
      expect(comp.motivoRejeicao).toBe('');
      expect(apiPost).not.toHaveBeenCalled();   // abrir não delibera
    });

    it('não abre o modal com uma deliberação em curso', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      const comp = criarCarregado();
      comp.aprovar(linha());                    // trava ligada

      comp.abrirRejeicao(linha({ id: 'sol-2' }));

      expect(comp.alvoRejeicao()).toBeNull();
    });

    it('fechar o modal descarta o alvo (sem chamar a API)', () => {
      const comp = criarCarregado();
      comp.abrirRejeicao(linha());
      comp.fecharRejeicao();
      expect(comp.alvoRejeicao()).toBeNull();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('confirmar sem alvo não faz nada', () => {
      const comp = criarCarregado();
      comp.motivoRejeicao = 'qualquer';
      comp.confirmarRejeicao();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('motivo só com espaços é recusado no front (não chama a API)', () => {
      const comp = criarCarregado();
      comp.abrirRejeicao(linha());
      comp.motivoRejeicao = '   ';
      comp.confirmarRejeicao();
      expect(apiPost).not.toHaveBeenCalled();
      expect(comp.alvoRejeicao()).not.toBeNull();   // modal continua aberto
    });

    it('sucesso: envia o motivo (trimado), fecha o modal, avisa e recarrega', () => {
      const comp = criarCarregado();
      apiGetList.mockClear();
      comp.abrirRejeicao(linha());
      comp.motivoRejeicao = '  escala do dia  ';

      comp.confirmarRejeicao();

      expect(apiPost).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacao/sol-1/rejeitar',
        { motivo: 'escala do dia' });
      expect(toastSuccess).toHaveBeenCalledWith('Solicitação rejeitada.');
      expect(comp.alvoRejeicao()).toBeNull();
      expect(apiGetList).toHaveBeenCalledTimes(1);   // reload traz o estorno do saldo
      expect(confirmSpy).not.toHaveBeenCalled();     // rejeição não usa confirm nativo
      expect(comp.deliberando()).toBe(false);
    });

    it('a rejeição não passa pelo confirm nativo, mas usa a mesma trava do aprovar', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      const comp = criarCarregado();
      comp.abrirRejeicao(linha());
      comp.motivoRejeicao = 'motivo';

      comp.confirmarRejeicao();
      expect(comp.deliberando()).toBe(true);

      comp.confirmarRejeicao();                      // 2º clique enquanto voa
      expect(apiPost).toHaveBeenCalledTimes(1);
    });

    it('o textarea do motivo tem maxlength="300" (render)', () => {
      // `MOTIVO_REJEICAO` é VARCHAR2(1000) em BYTES (changelog 036) e NADA limitava o texto — nem o
      // textarea, nem o componente, nem o service. Um motivo colado de um e-mail/norma estourava a
      // coluna → ORA-12899 → 500 com um toast genérico, e a rejeição ficava impossível sem que o
      // admin descobrisse que a causa era o tamanho. O teto vive no template: sem este render, apagar
      // o atributo deixaria a suíte verde e devolveria o defeito.
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
      const fixture = TestBed.createComponent(SolicitacoesAdminComponent);
      fixture.detectChanges();                          // ngOnInit + render
      fixture.componentInstance.abrirRejeicao(linha()); // o modal só existe com alvo
      fixture.detectChanges();

      const textarea = fixture.debugElement.query(By.css('#motivo-rejeicao')).nativeElement as HTMLTextAreaElement;
      expect(textarea.getAttribute('maxlength')).toBe('300');
    });

    it('o 400 do backend (motivo longo) chega ao admin com a causa, e o modal fica aberto', () => {
      // O backend agora recusa antes de tocar o banco; o admin precisa LER a causa para encurtar o
      // texto — o toast genérico de 500 não dizia nada.
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'O motivo da rejeição excede o máximo de 300 caracteres (foram 1500).' },
      })));
      const comp = criarCarregado();
      comp.abrirRejeicao(linha());
      comp.motivoRejeicao = 'x'.repeat(1500);

      comp.confirmarRejeicao();

      expect(toastError).toHaveBeenCalledWith('O motivo da rejeição excede o máximo de 300 caracteres (foram 1500).');
      expect(comp.alvoRejeicao()).not.toBeNull();   // o texto continua lá para ser encurtado
      expect(comp.deliberando()).toBe(false);
    });

    it('erro: toast, trava liberada e o modal PERMANECE aberto (o admin pode reenviar)', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'Motivo obrigatório.' } })));
      const comp = criarCarregado();
      apiGetList.mockClear();
      comp.abrirRejeicao(linha());
      comp.motivoRejeicao = 'motivo';

      comp.confirmarRejeicao();

      expect(toastError).toHaveBeenCalledWith('Motivo obrigatório.');
      expect(comp.alvoRejeicao()).not.toBeNull();   // onSucesso só roda no next
      expect(comp.deliberando()).toBe(false);
      expect(apiGetList).toHaveBeenCalledTimes(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Relatório PDF/DOCX (D-1.3/Q27) — honra sort, busca e filtros de coluna
  // ═══════════════════════════════════════════════════════════════════
  describe('gerarRelatorio', () => {
    it('leva o formato e a ordenação corrente', () => {
      const comp = criarCarregado();
      comp.gerarRelatorio('pdf');
      expect(downloadReport).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes/relatorio',
        { format: 'pdf', sort: 'padrao', direction: 'asc' });
    });

    it('inclui busca e filtros de coluna aplicados', () => {
      const comp = criarCarregado();
      comp.ctrl.state.search = 'maria';
      comp.ctrl.state.sort = 'nome';
      comp.ctrl.state.direction = 'desc';
      comp.ctrl.filters = { status: { values: ['PENDENTE'] } as any };

      comp.gerarRelatorio('docx');

      expect(downloadReport).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes/relatorio', {
        format: 'docx',
        sort: 'nome',
        direction: 'desc',
        search: 'maria',
        filters: JSON.stringify({ status: { values: ['PENDENTE'] } }),
      });
    });
  });
});

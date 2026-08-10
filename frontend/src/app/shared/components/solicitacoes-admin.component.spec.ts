import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ErroCargaComponent } from './erro-carga.component';
import { SolicitacoesAdminComponent } from './solicitacoes-admin.component';
import { ToastService } from './toast.component';

/**
 * SolicitacoesAdminComponent (card "Banco de Horas" do /admin/ponto): fila de deliberação com uma
 * linha por SOLICITAÇÃO — os dias do envio abrem na expansão, e a resposta é sempre do envio
 * inteiro (✅/❌ na linha-mãe, ou marca dia a dia concluída no "Deliberar"). Cobre o canal de erro
 * da fila com retry (distinto de "fila vazia", `meta` limpo), a janela "Confirmar ação" com
 * motivação obrigatória só havendo rejeição (teto de 300 caracteres), a recarga pós-deliberação e
 * o relatório PDF/DOCX honrando sort/busca/filtros. TestBed sem `detectChanges()` por padrão —
 * `ngOnInit` à mão; `ApiService`/`ToastService` via `useValue`. O `TableStateController` é REAL,
 * instanciado no field initializer com o `ApiService` mockado — as recargas são observadas por
 * `api.getList`. Falsifica-se só `Date`; `formatarDataExtensoBr` monta um `Date` local a partir do
 * ISO. Contrato: `pode_deliberar` só desabilita botões no template — quem barra é o backend.
 */

/** Dia dentro do envio. */
function dia(over: Record<string, unknown> = {}) {
  return { id: 'dia-1', data_folga: '2026-07-16', status: 'PENDENTE' as const, motivo: null, ...over };
}

/** Linha de `GET /api/admin/ponto/banco/solicitacoes` — uma solicitação. */
function linha(over: Record<string, unknown> = {}) {
  return {
    id: 'env-1',
    pessoa_id: 'op-1',
    pessoa_tipo: 'OPERADOR' as const,
    nome: 'Maria Souza',
    saldo_min: 930,
    data_solicitacao: '2026-07-10',
    status: 'PENDENTE' as const,
    dias: [dia()],
    pode_deliberar: true,
    atrasada: false,
    ...over,
  };
}

/** Envio de dois dias — o alvo dos fluxos misto e total. */
function envioDeDoisDias(over: Record<string, unknown> = {}) {
  return linha({ dias: [dia(), dia({ id: 'dia-2', data_folga: '2026-07-17' })], ...over });
}

describe('SolicitacoesAdminComponent', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let downloadReport: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;

  const META = { page: 1, limit: 10, total: 1, pages: 1 };
  const ROTA_DELIBERAR = '/api/admin/ponto/banco/solicitacao/env-1/deliberar';

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

  /** Fixture renderizada com a fila informada — para os testes de DOM. */
  function renderizar(linhas: unknown[] = [linha()]): ComponentFixture<SolicitacoesAdminComponent> {
    apiGetList.mockReturnValue(of({ data: linhas, meta: { ...META, total: linhas.length } }));
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    const fixture = TestBed.createComponent(SolicitacoesAdminComponent);
    fixture.detectChanges();
    return fixture;
  }

  const primeira = (comp: SolicitacoesAdminComponent) => comp.ctrl.rows()[0] as any;

  // ═══════════════════════════════════════════════════════════════════
  // Fila (GET /api/admin/ponto/banco/solicitacoes)
  // ═══════════════════════════════════════════════════════════════════
  describe('carga da fila', () => {
    it('pede a fila pela data da solicitação, da mais recente para a mais antiga', () => {
      criarCarregado();
      expect(apiGetList).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes',
        expect.objectContaining({ page: 1, limit: 10, sort: 'data_solicitacao', direction: 'desc' }));
    });

    it('aplica o payload na tabela (linhas + meta) e encerra o loading', () => {
      const comp = criarCarregado();
      expect(comp.ctrl.rows()).toHaveLength(1);
      expect(primeira(comp).nome).toBe('Maria Souza');
      expect(comp.ctrl.meta()).toEqual(META);
      expect(comp.ctrl.loading()).toBe(false);
      expect(comp.ctrl.erro()).toBe('');
    });

    it('falha na leitura da fila vira estado de ERRO (distinto de "fila vazia"), com o meta limpo', () => {
      apiGetList.mockReturnValue(throwError(() => ({ status: 500, error: { error: 'Erro interno do servidor' } })));
      const comp = criarCarregado();

      expect(comp.ctrl.rows()).toEqual([]);
      expect(comp.ctrl.meta()).toBeNull();
      expect(comp.ctrl.erro()).toContain('Não foi possível carregar as solicitações.');
      expect(comp.ctrl.erro()).toContain('Erro interno do servidor');
    });

    it('o retry re-dispara a carga e o sucesso limpa o erro e repovoa a fila', () => {
      apiGetList.mockReturnValue(throwError(() => ({ status: 0 })));
      const comp = criarCarregado();
      expect(comp.ctrl.erro()).not.toBe('');

      apiGetList.mockReturnValue(of({ data: [linha()], meta: META }));
      comp.ctrl.load();

      expect(comp.ctrl.erro()).toBe('');
      expect(comp.ctrl.rows()).toHaveLength(1);
    });

    it('nenhuma janela aberta e nenhuma deliberação em curso no início', () => {
      const comp = criarCarregado();
      expect(comp.alvo()).toBeNull();
      expect(comp.deliberando()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Render
  // ═══════════════════════════════════════════════════════════════════
  describe('render da fila', () => {
    const textoDaTabela = (f: ComponentFixture<SolicitacoesAdminComponent>) =>
      (f.debugElement.query(By.css('tbody')).nativeElement as HTMLElement).textContent ?? '';

    it('a linha é o ENVIO: nome, saldo, data da solicitação e status', () => {
      const fixture = renderizar([envioDeDoisDias()]);
      const texto = textoDaTabela(fixture);

      expect(texto).toContain('Maria Souza');
      expect(texto).toContain('+15:30');
      expect(texto).toContain('10/07/2026');
      expect(texto).toContain('Pendente');
      expect(fixture.debugElement.queryAll(By.css('tbody > tr'))).toHaveLength(1);
    });

    it('expandir mostra os dias do envio, um por linha', () => {
      const fixture = renderizar([envioDeDoisDias()]);

      (fixture.debugElement.query(By.css('tbody > tr')).nativeElement as HTMLElement).click();
      fixture.detectChanges();

      const sub = fixture.debugElement.queryAll(By.css('.sub tbody tr'));
      expect(sub).toHaveLength(2);
      expect((sub[0].nativeElement as HTMLElement).textContent).toContain('16/07/2026');
      expect((sub[1].nativeElement as HTMLElement).textContent).toContain('17/07/2026');
    });

    it('fila vazia de verdade: a frase do vazio, sem caixa de erro', () => {
      const fixture = renderizar([]);
      expect(textoDaTabela(fixture)).toContain('Nenhuma solicitação registrada.');
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    });

    it('erro na carga: caixa de erro com a mensagem e SEM a frase do vazio', () => {
      const fixture = renderizar();
      apiGetList.mockReturnValue(throwError(() => ({ status: 500, error: { error: 'Erro interno do servidor' } })));
      fixture.componentInstance.ctrl.load();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).not.toBeNull();
      expect(textoDaTabela(fixture)).not.toContain('Nenhuma solicitação registrada.');
      // A busca e os relatórios continuam na tela durante o erro.
      expect(fixture.debugElement.query(By.css('.search-input'))).not.toBeNull();
      expect(fixture.debugElement.queryAll(By.css('.btn-report'))).toHaveLength(2);
    });

    it('status traduzidos, inclusive o parcial; desconhecido volta cru', () => {
      const comp = criarCarregado();
      expect((comp as any).statusLabel('PENDENTE')).toBe('Pendente');
      expect((comp as any).statusLabel('APROVADO')).toBe('Aprovado');
      expect((comp as any).statusLabel('REJEITADO')).toBe('Rejeitado');
      expect((comp as any).statusLabel('CANCELADO')).toBe('Cancelado');
      expect((comp as any).statusLabel('PARCIAL')).toBe('Parcialmente aprovado');
      expect((comp as any).statusLabel('ZZZ')).toBe('ZZZ');
    });

    it('saldo em ±HH:MM; saldo nulo (sem folha oficial) vira "--"', () => {
      const comp = criarCarregado();
      expect((comp as any).saldoFmt({ saldo_min: 930 })).toBe('+15:30');
      expect((comp as any).saldoFmt({ saldo_min: -75 })).toBe('-01:15');
      expect((comp as any).saldoFmt({ saldo_min: null })).toBe('--');
    });

    it('dia solicitado vem como "Dia-da-semana, dd/mm/aaaa" (D-1.2), sem deslocar o dia', () => {
      const comp = criarCarregado();
      expect((comp as any).diaSolicitado('2026-07-16')).toBe('Quinta-feira, 16/07/2026');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Fluxo total: ✅/❌ na linha-mãe respondem o envio inteiro
  // ═══════════════════════════════════════════════════════════════════
  describe('deliberação total', () => {
    it('aprovar tudo: a janela lista os dias e o POST leva todos como aprovados', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(of({ data: [envioDeDoisDias()], meta: META }));
      comp.ctrl.load();
      const r = primeira(comp);

      comp.aprovarTudo(r);
      expect(comp.alvo()).toBe(r);
      expect((comp as any).diasAprovados(r).map((d: any) => d.id)).toEqual(['dia-1', 'dia-2']);
      expect((comp as any).diasRejeitados(r)).toEqual([]);
      expect((comp as any).podeEnviar()).toBe(true);

      comp.confirmar();

      expect(apiPost).toHaveBeenCalledWith(ROTA_DELIBERAR,
        { aprovados: ['dia-1', 'dia-2'], rejeitados: [] });
      expect(toastSuccess).toHaveBeenCalledWith('Solicitação deliberada.');
      expect(comp.alvo()).toBeNull();
      expect(apiGetList).toHaveBeenCalledTimes(3);   // carga, recarga do teste e a de pós-deliberação
    });

    it('rejeitar tudo: sem motivo o envio fica travado; com motivo, ele acompanha o POST', () => {
      const comp = criarCarregado();
      const r = primeira(comp);

      comp.rejeitarTudo(r);
      expect((comp as any).podeEnviar()).toBe(false);
      comp.confirmar();
      expect(apiPost).not.toHaveBeenCalled();

      comp.motivoRejeicao = '   Escala fechada   ';
      expect((comp as any).podeEnviar()).toBe(true);
      comp.confirmar();

      expect(apiPost).toHaveBeenCalledWith(ROTA_DELIBERAR,
        { aprovados: [], rejeitados: ['dia-1'], motivo: 'Escala fechada' });
    });

    it('sem permissão de deliberar, os botões da linha-mãe não abrem nada', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(of({ data: [linha({ pode_deliberar: false })], meta: META }));
      comp.ctrl.load();

      comp.aprovarTudo(primeira(comp));

      expect(comp.alvo()).toBeNull();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('a trava barra o duplo clique: o 2º confirmar não dispara outro POST', () => {
      const emVoo = new Subject<any>();
      apiPost.mockReturnValue(emVoo);
      const comp = criarCarregado();
      const r = primeira(comp);

      comp.aprovarTudo(r);
      comp.confirmar();
      expect(comp.deliberando()).toBe(true);
      comp.confirmar();
      expect(apiPost).toHaveBeenCalledTimes(1);

      emVoo.next({ ok: true });
      emVoo.complete();
      expect(comp.deliberando()).toBe(false);
    });

    it('erro: toast com a mensagem do backend, trava liberada e lista recarregada mesmo assim', () => {
      apiPost.mockReturnValue(throwError(() => ({ status: 400, error: { error: 'Delibere todos os dias pendentes da solicitação.' } })));
      const comp = criarCarregado();

      comp.aprovarTudo(primeira(comp));
      comp.confirmar();

      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Delibere todos os dias pendentes'));
      expect(comp.deliberando()).toBe(false);
      expect(comp.alvo()).toBeNull();
      expect(apiGetList).toHaveBeenCalledTimes(2);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Fluxo misto: marca dia a dia e conclui no "Deliberar"
  // ═══════════════════════════════════════════════════════════════════
  describe('deliberação mista', () => {
    it('só destrava com TODOS os dias marcados — meia deliberação não existe', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(of({ data: [envioDeDoisDias()], meta: META }));
      comp.ctrl.load();
      const r = primeira(comp);

      comp.votar(r, r.dias[0], 'aprovar');
      expect((comp as any).temVoto(r)).toBe(true);
      expect((comp as any).podeConcluir(r)).toBe(false);
      comp.abrirConfirmacao(r);
      expect(comp.alvo()).toBeNull();   // a janela não abre pela metade

      comp.votar(r, r.dias[1], 'rejeitar');
      expect((comp as any).podeConcluir(r)).toBe(true);
    });

    it('reclicar desmarca o dia', () => {
      const comp = criarCarregado();
      const r = primeira(comp);

      comp.votar(r, r.dias[0], 'aprovar');
      expect(r.dias[0]._voto).toBe('aprovar');
      comp.votar(r, r.dias[0], 'aprovar');
      expect(r.dias[0]._voto).toBeUndefined();
      expect((comp as any).temVoto(r)).toBe(false);
    });

    it('envia cada dia para o seu lado, com o motivo das rejeições', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(of({ data: [envioDeDoisDias()], meta: META }));
      comp.ctrl.load();
      const r = primeira(comp);

      comp.votar(r, r.dias[0], 'aprovar');
      comp.votar(r, r.dias[1], 'rejeitar');
      comp.abrirConfirmacao(r);
      comp.motivoRejeicao = 'Sem cobertura';
      comp.confirmar();

      expect(apiPost).toHaveBeenCalledWith(ROTA_DELIBERAR,
        { aprovados: ['dia-1'], rejeitados: ['dia-2'], motivo: 'Sem cobertura' });
    });

    it('fechar a janela desfaz as marcas — a linha volta aos ✅/❌', () => {
      const comp = criarCarregado();
      const r = primeira(comp);

      comp.votar(r, r.dias[0], 'rejeitar');
      comp.abrirConfirmacao(r);
      comp.fecharConfirmacao();

      expect(comp.alvo()).toBeNull();
      expect(r.dias[0]._voto).toBeUndefined();
      expect((comp as any).temVoto(r)).toBe(false);
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('dias já deliberados não entram na conta do que falta marcar', () => {
      const comp = criarCarregado();
      apiGetList.mockReturnValue(of({
        data: [linha({ dias: [dia(), dia({ id: 'dia-2', status: 'APROVADO' })] })], meta: META,
      }));
      comp.ctrl.load();
      const r = primeira(comp);

      comp.votar(r, r.dias[0], 'aprovar');

      expect((comp as any).podeConcluir(r)).toBe(true);
      comp.abrirConfirmacao(r);
      comp.confirmar();
      expect(apiPost).toHaveBeenCalledWith(ROTA_DELIBERAR, { aprovados: ['dia-1'], rejeitados: [] });
    });

    it('o textarea da motivação só existe havendo rejeição, com maxlength 300 (render)', () => {
      const fixture = renderizar([envioDeDoisDias()]);
      const comp = fixture.componentInstance;
      const r = primeira(comp);

      comp.aprovarTudo(r);
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.css('#motivo-rejeicao'))).toBeNull();

      comp.fecharConfirmacao();
      comp.rejeitarTudo(r);
      fixture.detectChanges();
      const textarea = fixture.debugElement.query(By.css('#motivo-rejeicao'));
      expect(textarea).not.toBeNull();
      expect((textarea.nativeElement as HTMLTextAreaElement).maxLength).toBe(300);
      expect((fixture.debugElement.query(By.css('.modal-title')).nativeElement as HTMLElement).textContent)
        .toBe('Confirmar ação');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  describe('gerarRelatorio', () => {
    it('leva o formato e a ordenação corrente', () => {
      const comp = criarCarregado();
      comp.gerarRelatorio('pdf');
      expect(downloadReport).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes/relatorio',
        { format: 'pdf', sort: 'data_solicitacao', direction: 'desc' });
    });

    it('inclui busca e filtros de coluna aplicados', () => {
      const comp = criarCarregado();
      comp.ctrl.state.search = 'maria';
      comp.ctrl.filters['status'] = { values: ['PENDENTE'] };

      comp.gerarRelatorio('docx');

      expect(downloadReport).toHaveBeenCalledWith('/api/admin/ponto/banco/solicitacoes/relatorio',
        expect.objectContaining({
          format: 'docx', search: 'maria', filters: JSON.stringify({ status: { values: ['PENDENTE'] } }),
        }));
    });
  });
});

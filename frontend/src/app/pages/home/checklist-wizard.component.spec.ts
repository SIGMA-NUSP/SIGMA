import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ChecklistWizardComponent } from './checklist-wizard.component';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';

/**
 * ChecklistWizardComponent (page): rascunho em `localStorage` com DUPLA expiração
 * independente — por IDADE (`DRAFT_MAX_AGE_MS` = 2h) e por VIRADA DE DIA, comparada em
 * componentes LOCAIS (`getFullYear/getMonth/getDate`, não UTC; depende da TZ do runner
 * America/Sao_Paulo, fixada pelo `npm test`) —, navegação do wizard, submit/submitEdit,
 * aviso de verificação e mudanças de sala (o `onSalaChange` do modo novo NÃO reseta
 * `selectedCabine`/`selectedPlenario`; quem reconcilia estado é `onEditSalaChange`).
 * TestBed sem `detectChanges()` — o template importa RouterLink/ngModel/@if e dispararia
 * ngOnInit; `ngOnInit()` é chamado direto com o ActivatedRoute mockado; services via
 * `useValue`, `LookupService` com signals writable semeados por teste. Relógio congelado
 * SEMPRE (o field initializer `dataOperacao` e `startTime`/`submit` leem `Date`): fake
 * timers `{toFake:['Date']}` ligados APÓS `compileComponents` (preserva setTimeout/rAF
 * reais), re-setados por teste ANTES de `criar()` quando o instante importa;
 * `localStorage.clear()` em beforeEach e afterEach.
 */
describe('ChecklistWizardComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiPut: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let toastWarning: ReturnType<typeof vi.fn>;
  let routerNavigate: ReturnType<typeof vi.fn>;
  let loadSalasOperador: ReturnType<typeof vi.fn>;
  let loadOperadoresPlenario: ReturnType<typeof vi.fn>;
  let userSignal: WritableSignal<any>;
  let salasSignal: WritableSignal<LookupItem[]>;
  let operadoresPlenarioSignal: WritableSignal<LookupItem[]>;
  let routeMock: any;

  beforeEach(async () => {
    apiGet = vi.fn().mockReturnValue(of({ data: {} }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    apiPut = vi.fn().mockReturnValue(of({ ok: true }));
    toastSuccess = vi.fn();
    toastError = vi.fn();
    toastWarning = vi.fn();
    routerNavigate = vi.fn();
    loadSalasOperador = vi.fn();
    loadOperadoresPlenario = vi.fn();
    userSignal = signal<any>({ id: '9' });
    salasSignal = signal<LookupItem[]>([]);
    operadoresPlenarioSignal = signal<LookupItem[]>([]);
    routeMock = { queryParams: of({}) };

    await TestBed.configureTestingModule({
      imports: [ChecklistWizardComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost, put: apiPut } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError, warning: toastWarning } },
        { provide: Router, useValue: { navigate: routerNavigate } },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: AuthService, useValue: { user: userSignal } },
        {
          provide: LookupService,
          useValue: {
            salas: salasSignal,
            operadoresPlenario: operadoresPlenarioSignal,
            loadSalasOperador,
            loadOperadoresPlenario,
          },
        },
      ],
    }).compileComponents(); // timers reais — só depois falsificamos o Date

    localStorage.clear();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 6, 10, 12, 0, 0)); // 10/jul 12:00 BRT (default seguro)
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit). */
  function criar(): ChecklistWizardComponent {
    return TestBed.createComponent(ChecklistWizardComponent).componentInstance;
  }

  /** Semeia um rascunho válido no localStorage salvando pelo próprio SUT (savedAt = relógio atual). */
  function semearRascunho(comp: ChecklistWizardComponent, step = 'wizard'): void {
    comp.salaId = '3';
    comp.salaNome = 'Plenário 01';
    comp.itens.set([{ id: 1, nome: 'Item A', tipo_widget: 'radio' }]);
    comp.currentIndex.set(0);
    comp.respostas = {};
    (comp as any).saveDraft(step);
  }

  const key = (comp: ChecklistWizardComponent) => (comp as any).DRAFT_KEY as string;

  // ═══════════════════════════════════════════════════════════════════
  // dataOperacao — dia LOCAL no flanco 21h BRT
  // ═══════════════════════════════════════════════════════════════════
  describe('dataOperacao (relógio congelado — flanco 21h BRT)', () => {
    it('antes das 21h BRT coincide com o dia local', () => {
      vi.setSystemTime(new Date('2026-07-15T12:00:00-03:00')); // 15h UTC, mesmo dia
      expect(criar().dataOperacao).toBe('2026-07-15');
    });

    it('depois das 21h BRT mantém o dia LOCAL (usa toISODate, não o dia UTC)', () => {
      vi.setSystemTime(new Date('2026-07-15T22:30:00-03:00')); // 01:30Z do dia 16
      // O relógio está no flanco: o dia UTC (o que o código antigo gravava) é o 16.
      expect(new Date().toISOString().split('T')[0]).toBe('2026-07-16');
      expect(criar().dataOperacao).toBe('2026-07-15');
    });

    it('o payload do registro herda o dia LOCAL pós-21h BRT', () => {
      vi.setSystemTime(new Date('2026-07-15T22:30:00-03:00'));
      const comp = criar();
      comp.salaId = '3';
      comp.itens.set([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
      comp.respostas = { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } };
      apiPost.mockReturnValue(of({ ok: true }));

      comp.submit();

      expect(apiPost.mock.calls[0][1]).toMatchObject({ data_operacao: '2026-07-15' });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — rascunho: dupla expiração (localStorage)
  // ═══════════════════════════════════════════════════════════════════
  describe('rascunho — dupla expiração (localStorage)', () => {
    it('B1 — expira por IDADE (>2h, mesmo dia local)', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 10, 0, 0));
      const comp = criar();
      semearRascunho(comp);
      expect(localStorage.getItem(key(comp))).not.toBeNull(); // salvo

      vi.setSystemTime(new Date(2026, 6, 10, 13, 1, 0)); // +3h01, dia 10 → só a idade estoura
      expect((comp as any).loadDraft()).toBeNull();
      expect(localStorage.getItem(key(comp))).toBeNull(); // clearDraft removeu
    });

    it('B1 — no limite exato de 2h NÃO expira (comparação é `>`, não `>=`)', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 10, 0, 0));
      const comp = criar();
      semearRascunho(comp);

      vi.setSystemTime(new Date(2026, 6, 10, 12, 0, 0)); // +2h exatas, mesmo dia
      const loaded = (comp as any).loadDraft();
      expect(loaded).not.toBeNull();
      expect(loaded.salaId).toBe('3');
    });

    it('B2 — expira por VIRADA DE DIA mesmo com idade dentro do limite (+5min cruzando 00h)', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 23, 58, 0)); // 23:58 do dia 10
      const comp = criar();
      semearRascunho(comp);

      vi.setSystemTime(new Date(2026, 6, 11, 0, 3, 0)); // 00:03 do dia 11 — +5min, dia mudou
      expect((comp as any).loadDraft()).toBeNull();
      expect(localStorage.getItem(key(comp))).toBeNull();
    });

    it('B3 (guarda) — comparação de dia é LOCAL, não UTC: 20:00→21:30 do mesmo dia local RESTAURA', () => {
      // 20:00 BRT = 23:00Z (dia 10); 21:30 BRT = 00:30Z (dia 11). Dia LOCAL igual (10) → não expira.
      // Se a comparação usasse getUTC*, o dia UTC mudaria e o rascunho expiraria.
      vi.setSystemTime(new Date(2026, 6, 10, 20, 0, 0));
      const comp = criar();
      semearRascunho(comp);

      vi.setSystemTime(new Date(2026, 6, 10, 21, 30, 0)); // +1h30, mesmo dia local
      const loaded = (comp as any).loadDraft();
      expect(loaded).not.toBeNull();
      expect(loaded.salaId).toBe('3');
      expect(localStorage.getItem(key(comp))).not.toBeNull(); // preservado
    });

    it('rascunho válido (mesmo dia, +30min) é retornado e preservado', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 10, 0, 0));
      const comp = criar();
      semearRascunho(comp);

      vi.setSystemTime(new Date(2026, 6, 10, 10, 30, 0));
      const loaded = (comp as any).loadDraft();
      expect(loaded.salaId).toBe('3');
      expect(loaded.step).toBe('wizard');
      expect(localStorage.getItem(key(comp))).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // rascunho — guardas de loadDraft / saveDraft / clearDraft
  // ═══════════════════════════════════════════════════════════════════
  describe('rascunho — guardas e persistência', () => {
    it('loadDraft sem nada no storage → null', () => {
      const comp = criar();
      expect((comp as any).loadDraft()).toBeNull();
    });

    it('loadDraft descarta rascunho sem salaId', () => {
      const comp = criar();
      localStorage.setItem(key(comp), JSON.stringify({ itens: [{ id: 1 }], savedAt: Date.now() }));
      expect((comp as any).loadDraft()).toBeNull();
    });

    it('loadDraft descarta rascunho com itens vazios', () => {
      const comp = criar();
      localStorage.setItem(key(comp), JSON.stringify({ salaId: '3', itens: [], savedAt: Date.now() }));
      expect((comp as any).loadDraft()).toBeNull();
    });

    it('loadDraft com JSON malformado → null e limpa a chave (catch)', () => {
      const comp = criar();
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      localStorage.setItem(key(comp), 'não é json {');
      expect((comp as any).loadDraft()).toBeNull();
      expect(localStorage.getItem(key(comp))).toBeNull();
      expect(warn).toHaveBeenCalled();
    });

    it('loadDraft sem savedAt não aplica expiração (rascunho legado) e é retornado', () => {
      const comp = criar();
      localStorage.setItem(key(comp), JSON.stringify({ salaId: '3', itens: [{ id: 1 }] }));
      const loaded = (comp as any).loadDraft();
      expect(loaded).not.toBeNull();
      expect(loaded.salaId).toBe('3');
    });

    it('saveDraft grava o snapshot esperado (savedAt, step, itens, índice, multi-operador)', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 10, 0, 0));
      const comp = criar();
      comp.salaId = '3';
      comp.salaNome = 'Plenário 01';
      comp.dataOperacao = '2026-07-10';
      comp.itens.set([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
      comp.currentIndex.set(0);
      comp.respostas = { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } };
      comp.isMultiOperador = true;
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = ['5'];
      (comp as any).saveDraft('wizard');

      const saved = JSON.parse(localStorage.getItem(key(comp))!);
      expect(saved).toMatchObject({
        salaId: '3',
        salaNome: 'Plenário 01',
        dataOperacao: '2026-07-10',
        currentIndex: 0,
        step: 'wizard',
        isMultiOperador: true,
        selectedCabine: ['9'],
        selectedPlenario: ['5'],
      });
      expect(saved.savedAt).toBe(new Date(2026, 6, 10, 10, 0, 0).getTime());
      expect(saved.itens).toEqual([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
    });

    it('a chave do rascunho é escopada pelo id do usuário', () => {
      const comp = criar();
      expect(key(comp)).toBe('checklist_draft_9');
    });

    it('DRAFT_KEY cai em "anonymous" quando não há usuário logado', () => {
      userSignal.set(null);
      const comp = criar();
      expect(key(comp)).toBe('checklist_draft_anonymous');
    });

    it('saveDraft engole erro de localStorage (quota) sem propagar', () => {
      const comp = criar();
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      // jsdom trata `localStorage.setItem = x` como gravar a chave "setItem" → spiar no protótipo
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
      comp.salaId = '3';
      comp.itens.set([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
      expect(() => (comp as any).saveDraft('wizard')).not.toThrow();
      expect(warn).toHaveBeenCalled();
    });

    it('clearDraft remove a chave', () => {
      const comp = criar();
      localStorage.setItem(key(comp), '{}');
      (comp as any).clearDraft();
      expect(localStorage.getItem(key(comp))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // restoreDraft — reidratação do estado
  // ═══════════════════════════════════════════════════════════════════
  describe('restoreDraft', () => {
    it('reidrata o estado e vai para o passo wizard carregando o item corrente', () => {
      const comp = criar();
      (comp as any).restoreDraft({
        salaId: 3,
        salaNome: 'Plenário 01',
        dataOperacao: '2026-07-09',
        itens: [{ id: 1, nome: 'Item A', tipo_widget: 'radio' }],
        currentIndex: 0,
        respostas: { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } },
        startTime: '2026-07-10T09:00:00.000Z',
        step: 'wizard',
      });
      expect(comp.salaId).toBe('3'); // coerção para String
      expect(comp.salaNome).toBe('Plenário 01');
      expect(comp.dataOperacao).toBe('2026-07-09');
      expect(comp.currentIndex()).toBe(0);
      expect(comp.startTime).toBeInstanceOf(Date);
      expect(comp.step()).toBe('wizard');
      expect(comp.currentItem()?.nome).toBe('Item A');
      expect(comp.radioValue).toBe('Ok'); // loadCurrentItem populou dos respostas
    });

    it('step "finish" vai direto ao passo finish sem carregar item corrente', () => {
      const comp = criar();
      (comp as any).restoreDraft({
        salaId: '3',
        itens: [{ id: 1, nome: 'A', tipo_widget: 'radio' }],
        currentIndex: 0,
        step: 'finish',
      });
      expect(comp.step()).toBe('finish');
      expect(comp.currentItem()).toBeNull();
    });

    it('rascunho multi-operador reidrata operadores e carrega o plenário', () => {
      const comp = criar();
      (comp as any).restoreDraft({
        salaId: '5',
        itens: [{ id: 1, nome: 'A', tipo_widget: 'radio' }],
        currentIndex: 0,
        step: 'wizard',
        isMultiOperador: true,
        selectedCabine: ['9'],
        selectedPlenario: [],
      });
      expect(comp.isMultiOperador).toBe(true);
      expect(comp.selectedCabine).toEqual(['9']);
      expect(loadOperadoresPlenario).toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // ngOnInit — wiring de rota + restauração de rascunho
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('rota com checklist_id: entra em modo edição e carrega o detalhe', () => {
      routeMock.queryParams = of({ checklist_id: '42' });
      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(true);
      expect(loadSalasOperador).toHaveBeenCalled();
      expect(apiGet).toHaveBeenCalledWith('/api/operador/checklist/detalhe', { checklist_id: 42 });
    });

    it('rota nova com rascunho válido no storage: restaura o wizard', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 10, 0, 0));
      routeMock.queryParams = of({});
      // semeia via um componente descartável, depois cria o SUT (mesma chave/usuário)
      const seed = criar();
      semearRascunho(seed);

      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(false);
      expect(comp.salaId).toBe('3');
      expect(comp.step()).toBe('wizard');
      expect(comp.currentItem()?.nome).toBe('Item A');
    });

    it('rota nova sem rascunho: permanece no setup em modo novo', () => {
      routeMock.queryParams = of({});
      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(false);
      expect(comp.step()).toBe('setup');
      expect(comp.salaId).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — navegação do wizard
  // ═══════════════════════════════════════════════════════════════════
  describe('navegação do wizard', () => {
    function comItens(comp: ChecklistWizardComponent, ...itens: any[]) {
      comp.salaId = '3';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.itens.set(itens);
      comp.currentIndex.set(0);
      (comp as any).loadCurrentItem();
    }

    it('startWizard sem sala não chama a API', () => {
      const comp = criar();
      comp.salaId = '';
      comp.startWizard();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('startWizard carrega itens, zera o índice, marca startTime e salva rascunho', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 9, 0, 0));
      const comp = criar();
      comp.salaId = '3';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      apiGet.mockReturnValue(of({ data: [{ id: 1, nome: 'Item A', tipo_widget: 'radio' }] }));
      comp.startWizard();
      expect(apiGet.mock.calls[0][0]).toBe('/api/forms/checklist/itens-tipo');
      expect(comp.salaNome).toBe('Plenário 01');
      expect(comp.itens().length).toBe(1);
      expect(comp.currentIndex()).toBe(0);
      expect(comp.startTime).toBeInstanceOf(Date);
      expect(comp.step()).toBe('wizard');
      expect(localStorage.getItem(key(comp))).not.toBeNull(); // saveDraft('wizard')
    });

    it('startWizard preserva o startTime já existente (guarda `if (!this.startTime)`)', () => {
      vi.setSystemTime(new Date(2026, 6, 10, 9, 0, 0));
      const comp = criar();
      comp.salaId = '3';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      const inicioReal = new Date(2026, 6, 10, 8, 30, 0); // hora real de início já registrada
      comp.startTime = inicioReal;
      apiGet.mockReturnValue(of({ data: [{ id: 1, nome: 'A', tipo_widget: 'radio' }] }));
      comp.startWizard();
      expect(comp.startTime).toBe(inicioReal); // NÃO recarimba ao re-entrar no wizard
    });

    it('startWizard sem itens configurados avisa e não avança', () => {
      const comp = criar();
      comp.salaId = '3';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      apiGet.mockReturnValue(of({ data: [] }));
      comp.startWizard();
      expect(toastWarning).toHaveBeenCalledWith('Este local não possui itens de verificação configurados.');
      expect(comp.step()).toBe('setup'); // inalterado
    });

    describe('canAdvance', () => {
      it('sem item corrente → false', () => {
        const comp = criar();
        comp.currentItem.set(null);
        expect(comp.canAdvance()).toBe(false);
      });
      it('item de texto sempre pode avançar', () => {
        const comp = criar();
        comp.currentItem.set({ id: 1, nome: 'T', tipo_widget: 'text' });
        expect(comp.canAdvance()).toBe(true);
      });
      it('radio sem marcação → false', () => {
        const comp = criar();
        comp.currentItem.set({ id: 1, nome: 'R', tipo_widget: 'radio' });
        comp.radioValue = '';
        expect(comp.canAdvance()).toBe(false);
      });
      it('multi-operador pode avançar sem marcar (validação final no submit)', () => {
        const comp = criar();
        comp.isMultiOperador = true;
        comp.currentItem.set({ id: 1, nome: 'R', tipo_widget: 'radio' });
        comp.radioValue = '';
        expect(comp.canAdvance()).toBe(true);
      });
      it('Falha com descrição curta (<10) → false; ≥10 → true', () => {
        const comp = criar();
        comp.currentItem.set({ id: 1, nome: 'R', tipo_widget: 'radio' });
        comp.radioValue = 'Falha';
        comp.falhaDesc = 'curta';
        expect(comp.canAdvance()).toBe(false);
        comp.falhaDesc = 'descricao suficientemente longa';
        expect(comp.canAdvance()).toBe(true);
      });
      it('Ok → true', () => {
        const comp = criar();
        comp.currentItem.set({ id: 1, nome: 'R', tipo_widget: 'radio' });
        comp.radioValue = 'Ok';
        expect(comp.canAdvance()).toBe(true);
      });
    });

    it('isLastItem reflete a posição no último índice', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' }, { id: 2, nome: 'B', tipo_widget: 'radio' });
      expect(comp.isLastItem()).toBe(false);
      comp.currentIndex.set(1);
      expect(comp.isLastItem()).toBe(true);
    });

    it('nextStep (não-último): grava a resposta, avança e salva rascunho "wizard"', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' }, { id: 2, nome: 'B', tipo_widget: 'radio' });
      comp.radioValue = 'Ok';
      comp.nextStep();
      expect(comp.respostas[1]).toMatchObject({ item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null });
      expect(comp.currentIndex()).toBe(1);
      expect(comp.currentItem()?.nome).toBe('B');
      expect(JSON.parse(localStorage.getItem(key(comp))!).step).toBe('wizard');
    });

    it('nextStep no último item vai para finish e salva rascunho "finish"', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' });
      comp.radioValue = 'Ok';
      comp.nextStep();
      expect(comp.step()).toBe('finish');
      expect(JSON.parse(localStorage.getItem(key(comp))!).step).toBe('finish');
    });

    it('nextStep grava Falha com a descrição trimada', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' }, { id: 2, nome: 'B', tipo_widget: 'radio' });
      comp.radioValue = 'Falha';
      comp.falhaDesc = '  cabo rompido no painel  ';
      comp.nextStep();
      expect(comp.respostas[1]).toMatchObject({ status: 'Falha', descricao_falha: 'cabo rompido no painel', valor_texto: null });
    });

    it('nextStep de item de texto grava status Ok e valor_texto trimado', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'Serial', tipo_widget: 'text' });
      comp.textValue = '  ABC123  ';
      comp.nextStep();
      expect(comp.respostas[1]).toMatchObject({ status: 'Ok', valor_texto: 'ABC123', descricao_falha: null });
    });

    it('prevStep no meio grava a resposta e recua o índice', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' }, { id: 2, nome: 'B', tipo_widget: 'radio' });
      comp.currentIndex.set(1);
      (comp as any).loadCurrentItem();
      comp.radioValue = 'Ok';
      comp.prevStep();
      expect(comp.respostas[2]).toMatchObject({ item_tipo_id: 2, status: 'Ok' });
      expect(comp.currentIndex()).toBe(0);
      expect(comp.currentItem()?.nome).toBe('A');
    });

    it('prevStep no primeiro item volta ao setup (não-multi) — grava status null se em branco', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' });
      comp.step.set('wizard');
      comp.radioValue = '';
      comp.prevStep();
      expect(comp.respostas[1].status).toBeNull();
      expect(comp.step()).toBe('setup');
    });

    it('prevStep no primeiro item volta ao passo operadores quando multi-operador', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' });
      comp.isMultiOperador = true;
      comp.step.set('wizard');
      comp.prevStep();
      expect(comp.step()).toBe('operadores');
    });

    it('backFromFinish volta ao wizard recarregando o item corrente', () => {
      const comp = criar();
      comItens(comp, { id: 1, nome: 'A', tipo_widget: 'radio' });
      comp.step.set('finish');
      comp.backFromFinish();
      expect(comp.step()).toBe('wizard');
      expect(comp.currentItem()?.nome).toBe('A');
    });

    it('loadCurrentItem popula radio/falha/texto a partir da resposta salva', () => {
      const comp = criar();
      comp.itens.set([{ id: 7, nome: 'A', tipo_widget: 'radio' }]);
      comp.currentIndex.set(0);
      comp.respostas = { 7: { item_tipo_id: 7, status: 'Falha', descricao_falha: 'x', valor_texto: null } };
      (comp as any).loadCurrentItem();
      expect(comp.radioValue).toBe('Falha');
      expect(comp.falhaDesc).toBe('x');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — submit (registro novo)
  // ═══════════════════════════════════════════════════════════════════
  describe('submit (registro novo)', () => {
    function pronto(comp: ChecklistWizardComponent) {
      comp.salaId = '3';
      comp.dataOperacao = '2026-07-10';
      comp.itens.set([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
      comp.respostas = { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } };
    }

    it('duplo clique (saving já ligado) não chama a API', () => {
      const comp = criar();
      pronto(comp);
      comp.saving.set(true);
      comp.submit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('sucesso: POST no endpoint certo (sala_id numérico, itens do respostas), limpa rascunho e navega', () => {
      const comp = criar();
      pronto(comp);
      semearRascunho(comp); // rascunho presente p/ provar clearDraft
      comp.respostas = { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } };
      comp.startTime = new Date(2026, 6, 10, 9, 0, 0);
      apiPost.mockReturnValue(of({ ok: true }));
      comp.submit();
      expect(apiPost.mock.calls[0][0]).toBe('/api/forms/checklist/registro');
      expect(apiPost.mock.calls[0][1]).toMatchObject({
        data_operacao: '2026-07-10',
        sala_id: 3,
        hora_inicio_testes: '09:00:00',
        observacoes: null,
      });
      expect(apiPost.mock.calls[0][1].itens).toEqual([{ item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null }]);
      expect(toastSuccess).toHaveBeenCalledWith('Verificação salva com sucesso!');
      expect(routerNavigate).toHaveBeenCalledWith(['/home']);
      expect(localStorage.getItem(key(comp))).toBeNull(); // clearDraft
      expect(comp.saving()).toBe(true); // mantido durante o redirecionamento
    });

    it('hora_inicio_testes é null quando não houve startTime', () => {
      const comp = criar();
      pronto(comp);
      comp.startTime = null;
      apiPost.mockReturnValue(of({ ok: true }));
      comp.submit();
      expect(apiPost.mock.calls[0][1].hora_inicio_testes).toBeNull();
    });

    it('multi-operador com item sem marcação avisa e não submete', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.salaId = '3';
      comp.itens.set([{ id: 1, nome: 'Item A', tipo_widget: 'radio' }]);
      comp.respostas = {};
      comp.submit();
      expect(toastWarning).toHaveBeenCalledWith('Itens sem marcação: Item A');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('multi-operador válido: payload leva operadores_cabine/plenario', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.salaId = '5';
      comp.itens.set([{ id: 1, nome: 'A', tipo_widget: 'radio' }]);
      comp.respostas = { 1: { item_tipo_id: 1, status: 'Ok', descricao_falha: null, valor_texto: null } };
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = ['5'];
      apiPost.mockReturnValue(of({ ok: true }));
      comp.submit();
      expect(apiPost.mock.calls[0][1]).toMatchObject({ operadores_cabine: ['9'], operadores_plenario: ['5'] });
    });

    it('resposta ok:false → desliga saving e mostra erro do backend', () => {
      const comp = criar();
      pronto(comp);
      apiPost.mockReturnValue(of({ ok: false, error: 'Sala fechada' }));
      comp.submit();
      expect(toastError).toHaveBeenCalledWith('Sala fechada');
      expect(comp.saving()).toBe(false);
    });

    it('erro HTTP com corpo → usa a mensagem do backend e desliga saving', () => {
      const comp = criar();
      pronto(comp);
      apiPost.mockReturnValue(throwError(() => ({ error: { error: 'boom' } })));
      comp.submit();
      expect(toastError).toHaveBeenCalledWith('boom');
      expect(comp.saving()).toBe(false);
    });

    it('erro HTTP sem corpo → mensagem de conexão', () => {
      const comp = criar();
      pronto(comp);
      apiPost.mockReturnValue(throwError(() => ({})));
      comp.submit();
      expect(toastError).toHaveBeenCalledWith('Erro de conexão ao salvar.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — submitEdit (edição)
  // ═══════════════════════════════════════════════════════════════════
  describe('submitEdit (edição)', () => {
    function edicao(comp: ChecklistWizardComponent) {
      (comp as any).checklistId = 42;
      comp.dataOperacao = '2026-07-10';
      comp.salaId = '3';
      comp.observacoes = 'obs';
      comp.editItems.set([
        { item_tipo_id: 1, item_nome: 'A', tipo_widget: 'radio', status: 'Falha', descricao_falha: '  ruído no ar  ', valor_texto: '', editado: false },
        { item_tipo_id: 2, item_nome: 'Serial', tipo_widget: 'text', status: '', descricao_falha: '', valor_texto: '  XPTO  ', editado: false },
      ]);
    }

    it('sucesso: PUT no endpoint certo, mapeia itens, toast e recarrega o detalhe', () => {
      const comp = criar();
      edicao(comp);
      apiPut.mockReturnValue(of({ ok: true }));
      comp.submitEdit();
      expect(apiPut.mock.calls[0][0]).toBe('/api/forms/checklist/editar');
      const payload = apiPut.mock.calls[0][1];
      expect(payload).toMatchObject({ checklist_id: 42, data_operacao: '2026-07-10', sala_id: 3, observacoes: 'obs' });
      expect(payload.itens).toEqual([
        { item_tipo_id: 1, status: 'Falha', descricao_falha: 'ruído no ar', valor_texto: null },
        { item_tipo_id: 2, status: 'Ok', descricao_falha: null, valor_texto: 'XPTO' },
      ]);
      expect(toastSuccess).toHaveBeenCalledWith('Checklist atualizado com sucesso!');
      expect(apiGet).toHaveBeenCalledWith('/api/operador/checklist/detalhe', { checklist_id: 42 });
      expect(comp.saving()).toBe(false);
    });

    it('multi-operador: payload leva operadores_cabine/plenario', () => {
      const comp = criar();
      edicao(comp);
      comp.editIsMultiOperador = true;
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = [];
      apiPut.mockReturnValue(of({ ok: true }));
      comp.submitEdit();
      expect(apiPut.mock.calls[0][1]).toMatchObject({ operadores_cabine: ['9'], operadores_plenario: [] });
    });

    it('resposta ok:false → erro do backend', () => {
      const comp = criar();
      edicao(comp);
      apiPut.mockReturnValue(of({ ok: false, error: 'Conflito' }));
      comp.submitEdit();
      expect(toastError).toHaveBeenCalledWith('Conflito');
      expect(comp.saving()).toBe(false);
    });

    // submitEdit monta a mensagem inline ('Erro ao salvar: ' + (err.error?.error || 'Erro de conexão')),
    // NÃO via httpErrorMsg — por isso asserta-se o valor EXATO (discrimina a extração), não stringContaining.
    it('erro HTTP com corpo → "Erro ao salvar: <msg do backend>" e desliga saving', () => {
      const comp = criar();
      edicao(comp);
      apiPut.mockReturnValue(throwError(() => ({ error: { error: 'x' } })));
      comp.submitEdit();
      expect(toastError).toHaveBeenCalledWith('Erro ao salvar: x');
      expect(comp.saving()).toBe(false);
    });

    it('erro HTTP sem corpo → fallback "Erro ao salvar: Erro de conexão"', () => {
      const comp = criar();
      edicao(comp);
      apiPut.mockReturnValue(throwError(() => ({})));
      comp.submitEdit();
      expect(toastError).toHaveBeenCalledWith('Erro ao salvar: Erro de conexão');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Fluxo do aviso de verificação (ordem de corte — sai primeiro)
  // ═══════════════════════════════════════════════════════════════════
  describe('aviso de verificação', () => {
    it('proceedFromSetup sem sala não chama a API', () => {
      const comp = criar();
      comp.salaId = '';
      comp.proceedFromSetup();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('com aviso pendente: guarda o aviso, zera "ciente" e vai ao passo aviso', () => {
      const comp = criar();
      comp.salaId = '3';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.avisoCiente = true;
      apiGet.mockReturnValue(of({ data: { cadastro_id: 'abc', manter_apos_ciencia: false, mensagens: [{ ordem: 1, texto: 'oi' }] } }));
      comp.proceedFromSetup();
      expect(apiGet.mock.calls[0][0]).toBe('/api/forms/checklist/aviso-pendente');
      expect(comp.salaNome).toBe('Plenário 01');
      expect(comp.avisoPendente()?.cadastro_id).toBe('abc');
      expect(comp.avisoCiente).toBe(false);
      expect(comp.step()).toBe('aviso');
    });

    it('sem aviso (data null) segue direto — multi vai ao passo operadores', () => {
      const comp = criar();
      comp.salaId = '5';
      comp.isMultiOperador = true;
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      apiGet.mockReturnValue(of({ data: null }));
      comp.proceedFromSetup();
      expect(comp.step()).toBe('operadores');
    });

    it('erro na consulta de aviso segue o fluxo (proceedAfterAviso)', () => {
      const comp = criar();
      comp.salaId = '5';
      comp.isMultiOperador = true;
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      apiGet.mockReturnValue(throwError(() => new Error('boom')));
      comp.proceedFromSetup();
      expect(comp.step()).toBe('operadores');
    });

    it('proceedAfterAviso: não-multi inicia o wizard (busca itens-tipo)', () => {
      const comp = criar();
      comp.salaId = '3';
      comp.isMultiOperador = false;
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      apiGet.mockReturnValue(of({ data: [{ id: 1, nome: 'A', tipo_widget: 'radio' }] }));
      (comp as any).proceedAfterAviso();
      expect(apiGet.mock.calls[0][0]).toBe('/api/forms/checklist/itens-tipo');
      expect(comp.step()).toBe('wizard');
    });

    it('confirmarCiencia sem aviso ou sem "ciente" não chama a API', () => {
      const comp = criar();
      comp.avisoPendente.set(null);
      comp.avisoCiente = true;
      comp.confirmarCiencia();
      expect(apiPost).not.toHaveBeenCalled();

      comp.avisoPendente.set({ cadastro_id: 'abc', manter_apos_ciencia: false, mensagens: [] });
      comp.avisoCiente = false;
      comp.confirmarCiencia();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('confirmarCiencia sucesso: registra ciência, limpa o aviso e segue', () => {
      const comp = criar();
      comp.salaId = '5';
      comp.isMultiOperador = true;
      comp.avisoPendente.set({ cadastro_id: 'abc', manter_apos_ciencia: false, mensagens: [] });
      comp.avisoCiente = true;
      apiPost.mockReturnValue(of({ ok: true }));
      comp.confirmarCiencia();
      expect(apiPost.mock.calls[0][0]).toBe('/api/forms/checklist/aviso/abc/ciencia');
      expect(apiPost.mock.calls[0][1]).toEqual({ sala_id: '5' });
      expect(comp.avisoPendente()).toBeNull();
      expect(comp.step()).toBe('operadores');
    });

    it('confirmarCiencia com erro mostra toast', () => {
      const comp = criar();
      comp.avisoPendente.set({ cadastro_id: 'abc', manter_apos_ciencia: false, mensagens: [] });
      comp.avisoCiente = true;
      apiPost.mockReturnValue(throwError(() => new Error('boom')));
      comp.confirmarCiencia();
      expect(toastError).toHaveBeenCalledWith('Erro ao registrar ciência.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onSalaChange (modo novo) — detecção de multi-operador
  // ═══════════════════════════════════════════════════════════════════
  describe('onSalaChange (modo novo)', () => {
    it('sala multi-operador liga a flag e carrega o plenário', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.salaId = '5';
      comp.onSalaChange();
      expect(comp.isMultiOperador).toBe(true);
      expect(loadOperadoresPlenario).toHaveBeenCalled();
    });

    it('sala comum desliga a flag e não carrega o plenário', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      comp.onSalaChange();
      expect(comp.isMultiOperador).toBe(false);
      expect(loadOperadoresPlenario).not.toHaveBeenCalled();
    });

    it('sala inexistente no lookup mantém a flag em false', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '99';
      comp.onSalaChange();
      expect(comp.isMultiOperador).toBe(false);
    });

    it('onSalaChange NÃO reinicia selectedCabine/Plenario', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = ['5'];
      comp.onSalaChange();
      expect(comp.selectedCabine).toEqual(['9']); // preservado
      expect(comp.selectedPlenario).toEqual(['5']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onEditSalaChange (modo edição) — reconciliação de itens/operadores
  // ═══════════════════════════════════════════════════════════════════
  describe('onEditSalaChange (modo edição)', () => {
    it('sem sala selecionada retorna cedo (nada de API)', () => {
      const comp = criar();
      comp.salaId = '';
      comp.onEditSalaChange();
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('reconcilia: preserva a resposta de item mantido e zera item novo', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      comp.editItems.set([
        { item_tipo_id: 1, item_nome: 'Antigo', tipo_widget: 'radio', status: 'Ok', descricao_falha: '', valor_texto: '', editado: true },
      ]);
      apiGet.mockReturnValue(of({ data: [
        { id: 1, nome: 'Item 1 (novo nome)', tipo_widget: 'radio' },
        { id: 2, nome: 'Item 2', tipo_widget: 'text' },
      ] }));
      comp.onEditSalaChange();
      const itens = comp.editItems();
      expect(itens[0]).toMatchObject({ item_tipo_id: 1, item_nome: 'Item 1 (novo nome)', status: 'Ok', editado: true });
      expect(itens[1]).toMatchObject({ item_tipo_id: 2, item_nome: 'Item 2', status: '', editado: false });
    });

    it('sala comum limpa a seleção de operadores', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = ['5'];
      apiGet.mockReturnValue(of({ data: [] })); // onEditSalaChange sempre recarrega itens-tipo
      comp.onEditSalaChange();
      expect(comp.editIsMultiOperador).toBe(false);
      expect(comp.selectedCabine).toEqual([]);
      expect(comp.selectedPlenario).toEqual([]);
    });

    it('sala multi-operador liga a flag e carrega o plenário', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.salaId = '5';
      apiGet.mockReturnValue(of({ data: [] })); // onEditSalaChange sempre recarrega itens-tipo
      comp.onEditSalaChange();
      expect(comp.editIsMultiOperador).toBe(true);
      expect(loadOperadoresPlenario).toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // usuarioNosOperadores · operadorOptions
  // ═══════════════════════════════════════════════════════════════════
  describe('usuarioNosOperadores / operadorOptions', () => {
    it('usuarioNosOperadores: false sem usuário logado', () => {
      const comp = criar();
      userSignal.set(null);
      comp.selectedCabine = ['9'];
      expect(comp.usuarioNosOperadores()).toBe(false);
    });
    it('usuarioNosOperadores: true quando o usuário está na cabine', () => {
      const comp = criar();
      comp.selectedCabine = ['9'];
      comp.selectedPlenario = [];
      expect(comp.usuarioNosOperadores()).toBe(true);
    });
    it('usuarioNosOperadores: true quando o usuário está no plenário', () => {
      const comp = criar();
      comp.selectedCabine = [];
      comp.selectedPlenario = ['9'];
      expect(comp.usuarioNosOperadores()).toBe(true);
    });
    it('usuarioNosOperadores: false quando não está em nenhum grupo', () => {
      const comp = criar();
      comp.selectedCabine = ['1'];
      comp.selectedPlenario = ['2'];
      expect(comp.usuarioNosOperadores()).toBe(false);
    });
    it('operadorOptions mapeia id→string preferindo nome_completo', () => {
      const comp = criar();
      operadoresPlenarioSignal.set([
        { id: 1, nome: 'A', nome_completo: 'Ana Maria' },
        { id: 2, nome: 'Bruno' },
      ]);
      expect(comp.operadorOptions()).toEqual([
        { id: '1', label: 'Ana Maria' },
        { id: '2', label: 'Bruno' },
      ]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // canEditChecklist · canSaveEdit · getSalaNome
  // ═══════════════════════════════════════════════════════════════════
  describe('canEditChecklist / canSaveEdit / getSalaNome', () => {
    it('canEditChecklist: só true quando somente_leitura === false', () => {
      const comp = criar();
      expect(comp.canEditChecklist()).toBe(false); // editData null
      comp.editData.set({ somente_leitura: true });
      expect(comp.canEditChecklist()).toBe(false);
      comp.editData.set({ somente_leitura: false });
      expect(comp.canEditChecklist()).toBe(true);
    });

    function itensEdit(comp: ChecklistWizardComponent) {
      comp.dataOperacao = '2026-07-10';
      comp.salaId = '3';
      comp.editItems.set([
        { item_tipo_id: 1, item_nome: 'A', tipo_widget: 'radio', status: 'Ok', descricao_falha: '', valor_texto: '', editado: false },
        { item_tipo_id: 2, item_nome: 'Serial', tipo_widget: 'text', status: '', descricao_falha: '', valor_texto: '', editado: false },
      ]);
    }

    it('canSaveEdit: true quando todos os radios têm status e há data+sala (texto ignorado)', () => {
      const comp = criar();
      itensEdit(comp);
      expect(comp.canSaveEdit()).toBe(true);
    });
    it('canSaveEdit: false se um radio está sem status', () => {
      const comp = criar();
      itensEdit(comp);
      comp.editItems.update(list => { list[0].status = ''; return [...list]; });
      expect(comp.canSaveEdit()).toBe(false);
    });
    it('canSaveEdit: false para Falha com descrição curta (<10)', () => {
      const comp = criar();
      itensEdit(comp);
      comp.editItems.update(list => { list[0].status = 'Falha'; list[0].descricao_falha = 'curto'; return [...list]; });
      expect(comp.canSaveEdit()).toBe(false);
    });
    it('canSaveEdit: false em multi-operador quando o usuário não está nos grupos', () => {
      const comp = criar();
      itensEdit(comp);
      comp.editIsMultiOperador = true;
      comp.selectedCabine = ['1'];
      comp.selectedPlenario = ['2'];
      expect(comp.canSaveEdit()).toBe(false);
    });
    it('canSaveEdit: true em multi-operador quando o usuário está num grupo', () => {
      const comp = criar();
      itensEdit(comp);
      comp.editIsMultiOperador = true;
      comp.selectedCabine = ['9']; // usuário logado (id 9) presente → guarda multi não bloqueia
      comp.selectedPlenario = [];
      expect(comp.canSaveEdit()).toBe(true);
    });
    it('canSaveEdit: false sem sala', () => {
      const comp = criar();
      itensEdit(comp);
      comp.salaId = '';
      expect(comp.canSaveEdit()).toBe(false);
    });

    it('getSalaNome resolve pelo lookup (e devolve "" quando não acha)', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      expect(comp.getSalaNome()).toBe('Plenário 01');
      comp.salaId = '99';
      expect(comp.getSalaNome()).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // loadEditData — mapeamento do detalhe
  // ═══════════════════════════════════════════════════════════════════
  describe('loadEditData', () => {
    it('sucesso não-multi: mapeia campos, itens e entra em readonly', () => {
      const comp = criar();
      (comp as any).checklistId = 42;
      apiGet.mockReturnValue(of({ data: {
        data_operacao: '2026-07-10T00:00:00',
        sala_id: 3,
        observacoes: 'obs',
        multi_operador: false,
        itens: [{ item_tipo_id: 1, item_nome: 'A', tipo_widget: 'radio', status: 'Ok', editado: true }],
      } }));
      (comp as any).loadEditData();
      expect(comp.dataOperacao).toBe('2026-07-10');
      expect(comp.salaId).toBe('3');
      expect(comp.observacoes).toBe('obs');
      expect(comp.editItems()[0]).toMatchObject({ item_tipo_id: 1, item_nome: 'A', status: 'Ok', editado: true });
      expect(comp.editIsMultiOperador).toBe(false);
      expect(comp.editSalaTravada).toBe(false);
      expect(comp.readOnly()).toBe(true);
      expect(comp.editLoading()).toBe(false);
    });

    it('multi-operador: trava a sala, mapeia nomes/ids e carrega o plenário', () => {
      const comp = criar();
      (comp as any).checklistId = 42;
      apiGet.mockReturnValue(of({ data: {
        multi_operador: true,
        operadores_cabine: ['Ana'],
        operadores_plenario: ['Bruno'],
        operadores_cabine_ids: [9],
        operadores_plenario_ids: [5],
        itens: [],
      } }));
      (comp as any).loadEditData();
      expect(comp.editIsMultiOperador).toBe(true);
      expect(comp.editSalaTravada).toBe(true);
      expect(comp.editCabineNomes).toEqual(['Ana']);
      expect(comp.selectedCabine).toEqual(['9']); // coeridos p/ string
      expect(comp.selectedPlenario).toEqual(['5']);
      expect(loadOperadoresPlenario).toHaveBeenCalled();
    });

    it('erro: encerra o loading e mostra toast', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('boom')));
      (comp as any).loadEditData();
      expect(comp.editLoading()).toBe(false);
      expect(toastError).toHaveBeenCalledWith('Erro ao carregar checklist.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // fechar · enterEditMode
  // ═══════════════════════════════════════════════════════════════════
  describe('fechar / enterEditMode', () => {
    it('fechar chama window.close', () => {
      const spy = vi.spyOn(window, 'close').mockImplementation(() => {});
      const comp = criar();
      comp.fechar();
      expect(spy).toHaveBeenCalledTimes(1);
    });

    it('enterEditMode desliga o readonly', () => {
      const comp = criar();
      comp.readOnly.set(true);
      comp.enterEditMode();
      expect(comp.readOnly()).toBe(false);
    });
  });
});

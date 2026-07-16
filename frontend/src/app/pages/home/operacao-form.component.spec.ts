import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OperacaoFormComponent } from './operacao-form.component';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';
import { toISODate } from '../../core/helpers/date.helpers';

/**
 * T22 — OperacaoFormComponent (page, 966 LOC — o maior formulário do app; §A5).
 *
 * Estratégia (plano T22): privilegiar CHAMADA DIRETA dos métodos de instância
 * (validadores/computeds/buildPayload) sobre interação de template — o TestBed cria
 * o componente (DI + signals) mas NUNCA chamamos `detectChanges()` (o template importa
 * RouterLink/ngModel e dispararia ngOnInit); o wiring de rota é exercitado chamando
 * `ngOnInit()` diretamente com o ActivatedRoute mockado. Services mockados via useValue
 * (padrão T21 — mais barato que HttpTestingController, o SUT injeta ApiService).
 *
 * Relógio congelado SEMPRE (§C1): `dataOperacao = toISODate(new Date())` (l.382) lê o relógio
 * — fake timers `{toFake:['Date']}` congelados ANTES de `createComponent` (preserva
 * setTimeout/rAF reais do compileComponents), `vi.useRealTimers()` em afterEach.
 * A cadeia de horários NÃO depende do relógio (só de HH:MM); o único ponto sensível é a
 * inicialização de `dataOperacao` — verificada nas duas datas do flanco 21h BRT (§C2).
 *
 * F19 (corrigido no C4): o componente inicializava `dataOperacao` com
 * `new Date().toISOString().split('T')[0]` (dia UTC) em vez do helper `toISODate` (dia local,
 * criado justamente p/ evitar o shift — auditoria §5.6), e entre 21h e 00h BRT o "hoje" do
 * formulário adiantava um dia. Era o análogo frontend do F7 (containers UTC). Os testes abaixo
 * travam o dia LOCAL nos dois lados do flanco, no campo e no payload.
 */
describe('OperacaoFormComponent', () => {
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
  let comissoesSignal: WritableSignal<LookupItem[]>;
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
    comissoesSignal = signal<LookupItem[]>([]);
    operadoresPlenarioSignal = signal<LookupItem[]>([]);
    routeMock = { queryParams: of({}), snapshot: { routeConfig: { path: 'operacao' } } };

    await TestBed.configureTestingModule({
      imports: [OperacaoFormComponent],
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
            comissoes: comissoesSignal,
            operadoresPlenario: operadoresPlenarioSignal,
            loadSalasOperador,
            loadOperadores: vi.fn(),
            loadComissoes: vi.fn(),
            loadOperadoresPlenario,
          },
        },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos o Date

    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-15T12:00:00-03:00')); // default: antes das 21h BRT
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit). */
  function criar(): OperacaoFormComponent {
    return TestBed.createComponent(OperacaoFormComponent).componentInstance;
  }

  // ═══════════════════════════════════════════════════════════════════
  // dataOperacao — relógio congelado, dia LOCAL (corrige F19) — §C1/§C2
  // ═══════════════════════════════════════════════════════════════════
  describe('dataOperacao (relógio congelado — flanco 21h BRT)', () => {
    it('antes das 21h BRT coincide com o dia local', () => {
      vi.setSystemTime(new Date('2026-07-15T12:00:00-03:00')); // 15h UTC, mesmo dia
      const comp = criar();
      expect(comp.dataOperacao).toBe('2026-07-15');
    });

    it('depois das 21h BRT mantém o dia LOCAL (corrige F19 — usa toISODate, não o dia UTC)', () => {
      vi.setSystemTime(new Date('2026-07-15T22:30:00-03:00')); // 01:30Z do dia 16
      const comp = criar();
      // O contraste é com o dia UTC, não com o helper: repetir `toISODate(new Date())` aqui só
      // re-executaria a expressão do próprio SUT. O relógio está no flanco — o código antigo daria 16.
      expect(new Date().toISOString().split('T')[0]).toBe('2026-07-16');
      expect(comp.dataOperacao).toBe('2026-07-15');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — cadeia de validação de horários
  // ═══════════════════════════════════════════════════════════════════
  describe('validarHoraEntrada', () => {
    function comEntradaAnterior(comp: OperacaoFormComponent, horaSaidaAnt: string, nome = 'Ana') {
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 1, hora_saida: horaSaidaAnt, operador_nome: nome }] };
    }

    it('ACEITE: entrada igual ao término anterior não gera erro', () => {
      const comp = criar();
      comEntradaAnterior(comp, '10:00:00');
      comp.horaEntrada = '10:00';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toBe('');
    });

    it('REJEIÇÃO: entrada anterior ao término do operador precedente', () => {
      const comp = criar();
      comEntradaAnterior(comp, '10:00:00', 'Ana');
      comp.horaEntrada = '09:30';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toContain('10:00');
      expect(comp.erroHoraEntrada).toContain('Ana');
    });

    it('multi-operador: sempre limpa o erro (early return)', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comEntradaAnterior(comp, '10:00:00');
      comp.horaEntrada = '09:30';
      comp.erroHoraEntrada = 'sujo';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toBe('');
    });

    it('sem entradas na sessão: não valida', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [] };
      comp.horaEntrada = '08:00';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toBe('');
    });

    it('operador anterior não encontrado (ordem com furo): não valida', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 5, hora_saida: '10:00:00' }] };
      comp.horaEntrada = '09:00';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toBe('');
    });

    it('horaEntrada vazia: não valida', () => {
      const comp = criar();
      comEntradaAnterior(comp, '10:00:00');
      comp.horaEntrada = '';
      comp.validarHoraEntrada();
      expect(comp.erroHoraEntrada).toBe('');
    });
  });

  describe('validarHoraFim', () => {
    it('ACEITE: término posterior ao início, sem operador seguinte', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaEntrada = '08:00';
      comp.horaFim = '10:00';
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toBe('');
    });

    it('REJEIÇÃO: término anterior/igual ao início da operação', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaEntrada = '10:00';
      comp.horaFim = '09:00';
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toContain('10:00');
    });

    it('usa horaInicio como referência quando horaEntrada está vazia', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaEntrada = '';
      comp.horaInicio = '10:00';
      comp.horaFim = '09:30';
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toContain('10:00');
    });

    it('REJEIÇÃO: término posterior ao início do operador seguinte', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaEntrada = '08:00';
      comp.horaFim = '12:00';
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 3, hora_entrada: '11:00:00', operador_nome: 'Bruno' }] };
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toContain('Bruno');
      expect(comp.erroHoraFim).toContain('11:00');
    });

    it('não valida quando o evento não está encerrado', () => {
      const comp = criar();
      comp.eventoEncerrado = false;
      comp.horaEntrada = '10:00';
      comp.horaFim = '09:00';
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toBe('');
    });

    it('multi-operador: não valida horaFim', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.eventoEncerrado = true;
      comp.horaEntrada = '10:00';
      comp.horaFim = '09:00';
      comp.validarHoraFim();
      expect(comp.erroHoraFim).toBe('');
    });
  });

  describe('validarHoraSaida', () => {
    it('ACEITE: término da operação posterior ao início, sem seguinte', () => {
      const comp = criar();
      comp.eventoEncerrado = false;
      comp.horaEntrada = '08:00';
      comp.horaSaida = '10:00';
      comp.validarHoraSaida();
      expect(comp.erroHoraSaida).toBe('');
    });

    it('REJEIÇÃO: término da operação anterior/igual ao início', () => {
      const comp = criar();
      comp.eventoEncerrado = false;
      comp.horaEntrada = '10:00';
      comp.horaSaida = '09:00';
      comp.validarHoraSaida();
      expect(comp.erroHoraSaida).toContain('10:00');
    });

    it('REJEIÇÃO: término da operação posterior ao início do seguinte', () => {
      const comp = criar();
      comp.eventoEncerrado = false;
      comp.horaEntrada = '08:00';
      comp.horaSaida = '12:00';
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 3, hora_entrada: '11:00:00', operador_nome: 'Bruno' }] };
      comp.validarHoraSaida();
      expect(comp.erroHoraSaida).toContain('Bruno');
      expect(comp.erroHoraSaida).toContain('11:00');
    });

    it('não valida quando o evento está encerrado (usa horaFim, não horaSaida)', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaEntrada = '10:00';
      comp.horaSaida = '09:00';
      comp.validarHoraSaida();
      expect(comp.erroHoraSaida).toBe('');
    });

    it('multi-operador: não valida horaSaida', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.eventoEncerrado = false;
      comp.horaEntrada = '10:00';
      comp.horaSaida = '09:00';
      comp.validarHoraSaida();
      expect(comp.erroHoraSaida).toBe('');
    });
  });

  describe('dadosSeguinte (operador adjacente do estado de sessão)', () => {
    it('modo novo: encontra o seguinte por ordem = atual+1', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 3, hora_entrada: '11:00:00', operador_nome: 'Bruno' }] };
      expect((comp as any).dadosSeguinte()).toEqual({ hora: '11:00', nome: 'Bruno' });
    });

    it('modo novo: entradas sequenciais sem posterior → null', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 1 }, { ordem: 2 }] };
      expect((comp as any).dadosSeguinte()).toBeNull();
    });

    it('modo novo: sem entradas → null', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [] };
      expect((comp as any).dadosSeguinte()).toBeNull();
    });

    it('modo novo: seguinte sem hora_entrada → null', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 3, operador_nome: 'Bruno' }] };
      expect((comp as any).dadosSeguinte()).toBeNull();
    });

    it('modo edição: usa hora_entrada_seguinte/operador_nome_seguinte do editData', () => {
      const comp = criar();
      comp.editMode.set(true);
      comp.editData.set({ hora_entrada_seguinte: '11:00', operador_nome_seguinte: 'Bruno' });
      expect((comp as any).dadosSeguinte()).toEqual({ hora: '11:00', nome: 'Bruno' });
    });

    it('modo edição: sem hora_entrada_seguinte → null', () => {
      const comp = criar();
      comp.editMode.set(true);
      comp.editData.set({ operador_nome_seguinte: 'Bruno' });
      expect((comp as any).dadosSeguinte()).toBeNull();
    });
  });

  describe('revalidarTodosHorarios / handlers de horário', () => {
    it('propaga erros de entrada e fim (saida limpa quando encerrado)', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 1, hora_saida: '10:00:00', operador_nome: 'Ana' }] };
      comp.horaEntrada = '09:30'; // < 10:00 → erro entrada
      comp.horaFim = '09:00'; // <= entrada → erro fim
      comp.revalidarTodosHorarios();
      expect(comp.erroHoraEntrada).not.toBe('');
      expect(comp.erroHoraFim).not.toBe('');
      expect(comp.erroHoraSaida).toBe('');
    });

    it('limpa erros anteriores quando a configuração fica válida', () => {
      const comp = criar();
      comp.erroHoraEntrada = 'x';
      comp.erroHoraFim = 'y';
      comp.erroHoraSaida = 'z';
      comp.horaEntrada = '';
      comp.horaFim = '';
      comp.horaSaida = '';
      comp.revalidarTodosHorarios();
      expect(comp.erroHoraEntrada).toBe('');
      expect(comp.erroHoraFim).toBe('');
      expect(comp.erroHoraSaida).toBe('');
    });

    it('onHoraInicioChange: copia horaInicio→horaEntrada quando entrada é readonly (1º operador)', () => {
      const comp = criar();
      (comp as any).estadoSessao = null; // sem sessão aberta → horaEntradaReadonly true
      comp.horaInicio = '09:15';
      comp.onHoraInicioChange();
      expect(comp.horaEntrada).toBe('09:15');
    });

    it('onHoraInicioChange: NÃO copia quando entrada é editável (sessão aberta)', () => {
      const comp = criar();
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      comp.horaInicio = '09:15';
      comp.horaEntrada = '08:00';
      comp.onHoraInicioChange();
      expect(comp.horaEntrada).toBe('08:00');
    });

    it('onHoraFimChange: copia horaFim→horaSaida quando evento encerrado', () => {
      const comp = criar();
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
      comp.onHoraFimChange();
      expect(comp.horaSaida).toBe('11:00');
    });

    it('onHoraFimChange: NÃO copia quando evento não encerrado', () => {
      const comp = criar();
      comp.eventoEncerrado = false;
      comp.horaFim = '11:00';
      comp.horaSaida = '10:00';
      comp.onHoraFimChange();
      expect(comp.horaSaida).toBe('10:00');
    });

    it('onEventoEncerradoChange: zera horaFim e horaSaida', () => {
      const comp = criar();
      comp.horaFim = '11:00';
      comp.horaSaida = '10:00';
      comp.onEventoEncerradoChange();
      expect(comp.horaFim).toBe('');
      expect(comp.horaSaida).toBe('');
    });

    it('onHoraEntradaChange: dispara a validação da entrada', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 1, hora_saida: '10:00:00', operador_nome: 'Ana' }] };
      comp.horaEntrada = '09:00';
      comp.onHoraEntradaChange();
      expect(comp.erroHoraEntrada).not.toBe('');
    });
  });

  describe('aplicarRegraHorarios', () => {
    it('1º operador (readonly): espelha horaInicio em horaEntrada e horaFim em horaSaida', () => {
      const comp = criar();
      (comp as any).estadoSessao = null; // readonly
      comp.horaInicio = '09:00';
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
      (comp as any).aplicarRegraHorarios();
      expect(comp.horaEntrada).toBe('09:00');
      expect(comp.horaSaida).toBe('11:00');
    });

    it('entrada editável (sessão aberta): não espelha horaInicio', () => {
      const comp = criar();
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      comp.horaInicio = '09:00';
      comp.horaEntrada = '08:00';
      (comp as any).aplicarRegraHorarios();
      expect(comp.horaEntrada).toBe('08:00');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Regras de UI derivadas (métodos, não computeds — sem memoização de @Input)
  // ═══════════════════════════════════════════════════════════════════
  describe('regras de UI derivadas', () => {
    it('isRO: só true em modo edição + readonly', () => {
      const comp = criar();
      expect(comp.isRO()).toBe(false); // modo novo
      comp.editMode.set(true);
      comp.readOnly.set(true);
      expect(comp.isRO()).toBe(true);
      comp.readOnly.set(false);
      expect(comp.isRO()).toBe(false);
    });

    it('formDisabled: false em edição; true em situação inicial/duas_entradas; false nas demais', () => {
      const comp = criar();
      comp.editMode.set(true);
      expect(comp.formDisabled()).toBe(false);
      comp.editMode.set(false);
      comp.situacao.set('inicial');
      expect(comp.formDisabled()).toBe(true);
      comp.situacao.set('duas_entradas');
      expect(comp.formDisabled()).toBe(true);
      comp.situacao.set('sem_sessao');
      expect(comp.formDisabled()).toBe(false);
    });

    it('camposSessaoReadonly (modo novo): segue sessaoAberta', () => {
      const comp = criar();
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      expect(comp.camposSessaoReadonly()).toBe(true);
      (comp as any).estadoSessao = { existe_sessao_aberta: false };
      expect(comp.camposSessaoReadonly()).toBe(false);
    });

    it('camposSessaoReadonly (edição): readonly exceto para o 1º operador (ordem 1)', () => {
      const comp = criar();
      comp.editMode.set(true);
      comp.editData.set({ ordem: 1 });
      expect(comp.camposSessaoReadonly()).toBe(false);
      comp.editData.set({ ordem: 2 });
      expect(comp.camposSessaoReadonly()).toBe(true);
      comp.editData.set(null);
      expect(comp.camposSessaoReadonly()).toBe(true); // ordem undefined !== 1
    });

    it('horaEntradaReadonly (modo novo): readonly só sem sessão aberta (1º operador espelha início)', () => {
      const comp = criar();
      (comp as any).estadoSessao = null;
      expect(comp.horaEntradaReadonly()).toBe(true);
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      expect(comp.horaEntradaReadonly()).toBe(false);
    });

    it('horaEntradaReadonly (edição): readonly apenas para o 1º operador (ordem 1)', () => {
      const comp = criar();
      comp.editMode.set(true);
      comp.editData.set({ ordem: 1 });
      expect(comp.horaEntradaReadonly()).toBe(true);
      comp.editData.set({ ordem: 2 });
      expect(comp.horaEntradaReadonly()).toBe(false);
    });

    it('showComissao: esconde em auditório e plenário sem número; mostra em plenário numerado e demais salas', () => {
      const comp = criar();
      salasSignal.set([
        { id: 1, nome: 'Auditório Petrônio Portella' },
        { id: 2, nome: 'Plenário' },
        { id: 3, nome: 'Plenário 01' },
        { id: 4, nome: 'Sala de Reuniões' },
      ]);
      comp.salaId = '1';
      expect(comp.showComissao()).toBe(false); // auditório
      comp.salaId = '2';
      expect(comp.showComissao()).toBe(false); // plenário sem número
      comp.salaId = '3';
      expect(comp.showComissao()).toBe(true); // plenário numerado
      comp.salaId = '4';
      expect(comp.showComissao()).toBe(true); // outra sala
      comp.salaId = '99';
      expect(comp.showComissao()).toBe(false); // sem correspondência
    });

    it('isDemaisSalas: true apenas para o id sentinela (11)', () => {
      const comp = criar();
      comp.salaId = '11';
      expect(comp.isDemaisSalas()).toBe(true);
      comp.salaId = '5';
      expect(comp.isDemaisSalas()).toBe(false);
    });

    it('nomeDemaisSalasReadonly: por sessão (novo) / 1º operador (edição)', () => {
      const comp = criar();
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      expect(comp.nomeDemaisSalasReadonly()).toBe(true);
      comp.editMode.set(true);
      comp.editData.set({ ordem: 1 });
      expect(comp.nomeDemaisSalasReadonly()).toBe(false);
    });

    it('btnSalvarLabel: rótulo especial só na 2ª entrada', () => {
      const comp = criar();
      comp.situacao.set('uma_entrada');
      expect(comp.btnSalvarLabel()).toBe('Novo registro (2ª entrada)');
      comp.situacao.set('sem_sessao');
      expect(comp.btnSalvarLabel()).toBe('Salvar registro');
    });

    it('canEditOperacao: só quando somente_leitura === false', () => {
      const comp = criar();
      expect(comp.canEditOperacao()).toBe(false); // editData null
      comp.editData.set({ somente_leitura: true });
      expect(comp.canEditOperacao()).toBe(false);
      comp.editData.set({ somente_leitura: false });
      expect(comp.canEditOperacao()).toBe(true);
    });

    it('sessaoAberta: reflete existe_sessao_aberta === true', () => {
      const comp = criar();
      expect(comp.sessaoAberta()).toBe(false); // estadoSessao null
      (comp as any).estadoSessao = { existe_sessao_aberta: false };
      expect(comp.sessaoAberta()).toBe(false);
      (comp as any).estadoSessao = { existe_sessao_aberta: true };
      expect(comp.sessaoAberta()).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — buildPayload
  // ═══════════════════════════════════════════════════════════════════
  describe('buildPayload', () => {
    it('não-multi (plenário sem número): monta campos e tipo_evento=operacao', () => {
      const comp = criar();
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      comp.salaId = '2';
      comp.dataOperacao = '2026-07-15';
      comp.nomeEvento = 'Ev';
      comp.responsavelEvento = 'Resp';
      comp.horaInicio = '09:00';
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
      comp.horaEntrada = '09:05';
      comp.usb01 = 'A';
      comp.usb02 = 'B';
      comp.observacoes = 'obs';
      comp.houveAnormalidade = 'sim';
      const p = (comp as any).buildPayload();
      expect(p).toMatchObject({
        comissao_id: null,
        data_operacao: '2026-07-15',
        nome_demais_salas: null,
        nome_evento: 'Ev',
        responsavel_evento: 'Resp',
        horario_pauta: null,
        hora_inicio: '09:00',
        hora_fim: '11:00',
        hora_entrada: '09:05',
        hora_saida: null,
        usb_01: 'A',
        usb_02: 'B',
        observacoes: 'obs',
        houve_anormalidade: 'sim',
        tipo_evento: 'operacao',
      });
      expect(p).not.toHaveProperty('operadores_ids');
      expect(p).not.toHaveProperty('suspensoes');
    });

    it('comissão comum → comissao_id numérico e tipo_evento=operacao', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comissoesSignal.set([{ id: 7, nome: 'CCJ' }]);
      comp.salaId = '3';
      comp.comissaoId = '7';
      const p = (comp as any).buildPayload();
      expect(p.comissao_id).toBe(7);
      expect(p.tipo_evento).toBe('operacao');
    });

    it('comissão "Cessão de Sala" → tipo_evento=cessao', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comissoesSignal.set([{ id: 8, nome: 'Cessão de Sala' }]);
      comp.salaId = '3';
      comp.comissaoId = '8';
      expect((comp as any).buildPayload().tipo_evento).toBe('cessao');
    });

    it('sala mostra comissão mas sem comissão selecionada → tipo_evento=outros', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      comp.comissaoId = '';
      expect((comp as any).buildPayload().tipo_evento).toBe('outros');
    });

    it('auditório sem comissão → tipo_evento=outros', () => {
      const comp = criar();
      salasSignal.set([{ id: 9, nome: 'Auditório Petrônio Portella' }]);
      comp.salaId = '9';
      comp.comissaoId = '';
      expect((comp as any).buildPayload().tipo_evento).toBe('outros');
    });

    it('multi-operador (novo): hora_entrada=início, hora_saida=fim, suspensões filtradas, operadores_ids', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.isMultiOperador = true;
      comp.salaId = '5';
      comp.horaInicio = '09:00';
      comp.horaFim = '11:00';
      comp.suspensoes = [
        { hora_suspensao: '10:00', hora_reabertura: '10:15' },
        { hora_suspensao: '', hora_reabertura: '' },
      ];
      comp.selectedOperadorIds = ['1', '2'];
      const p = (comp as any).buildPayload();
      expect(p).toMatchObject({
        comissao_id: null,
        responsavel_evento: null,
        horario_pauta: null,
        hora_fim: '11:00',
        hora_entrada: '09:00',
        hora_saida: '11:00',
      });
      expect(p.suspensoes).toEqual([{ hora_suspensao: '10:00', hora_reabertura: '10:15' }]);
      expect(p.operadores_ids).toEqual(['1', '2']);
      expect(p).not.toHaveProperty('operadores_sessao_ids');
    });

    it('multi-operador (edição): usa operadores_sessao_ids', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.isMultiOperador = true;
      comp.editMode.set(true);
      comp.salaId = '5';
      comp.selectedOperadorIds = ['1', '2'];
      const p = (comp as any).buildPayload();
      expect(p.operadores_sessao_ids).toEqual(['1', '2']);
      expect(p).not.toHaveProperty('operadores_ids');
    });

    it('nome_demais_salas: trim quando é "Demais Salas", null caso contrário', () => {
      const comp = criar();
      salasSignal.set([{ id: 11, nome: 'Demais Salas' }]);
      comp.salaId = '11';
      comp.nomeDemaisSalas = '  Sala Verde  ';
      expect((comp as any).buildPayload().nome_demais_salas).toBe('Sala Verde');
      comp.salaId = '2';
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      expect((comp as any).buildPayload().nome_demais_salas).toBeNull();
    });

    it('data_operacao herda o dia LOCAL pós-21h BRT (corrige F19 no payload)', () => {
      vi.setSystemTime(new Date('2026-07-15T22:30:00-03:00'));
      const comp = criar();
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      comp.salaId = '2';
      expect((comp as any).buildPayload().data_operacao).toBe('2026-07-15');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // NÚCLEO — onSubmit → submitNew / submitEdit
  // ═══════════════════════════════════════════════════════════════════
  describe('onSubmit — guardas (não chamam a API)', () => {
    it('sem sala selecionada (modo novo): não submete', () => {
      const comp = criar();
      comp.salaId = '';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('Demais Salas sem nome: avisa e não submete', () => {
      const comp = criar();
      comp.salaId = '11';
      comp.nomeDemaisSalas = '';
      comp.onSubmit();
      expect(toastWarning).toHaveBeenCalledWith('Informe o nome da sala.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('sem descrição do evento: não submete', () => {
      const comp = criar();
      comp.salaId = '2';
      comp.nomeEvento = '';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('sem início da sessão: não submete', () => {
      const comp = criar();
      comp.salaId = '2';
      comp.nomeEvento = 'Ev';
      comp.horaInicio = '';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('erro de horário pendente (erroHoraEntrada): não submete', () => {
      const comp = criar();
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      comp.salaId = '2';
      comp.nomeEvento = 'Ev';
      comp.horaInicio = '09:00';
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
      comp.erroHoraEntrada = 'inconsistente';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('multi sem operadores (novo): avisa e não submete', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.salaId = '5';
      comp.nomeEvento = 'Ev';
      comp.horaInicio = '09:00';
      comp.selectedOperadorIds = [];
      comp.onSubmit();
      expect(toastWarning).toHaveBeenCalledWith('Selecione pelo menos um operador.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('multi sem término da sessão: não submete', () => {
      const comp = criar();
      comp.isMultiOperador = true;
      comp.salaId = '5';
      comp.nomeEvento = 'Ev';
      comp.horaInicio = '09:00';
      comp.selectedOperadorIds = ['1'];
      comp.horaFim = '';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });
  });

  describe('onSubmit — submitNew (registro novo)', () => {
    function novoValido(comp: OperacaoFormComponent) {
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      comp.salaId = '2';
      comp.nomeEvento = 'Reunião';
      comp.horaInicio = '09:00';
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
    }

    it('sucesso simples: POST no endpoint certo (sala_id numérico), toast e navegação /home', () => {
      const comp = criar();
      novoValido(comp);
      apiPost.mockReturnValue(of({ ok: true, tipo_evento: 'operacao' }));
      comp.onSubmit();
      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0][0]).toBe('/api/operacao/audio/salvar-entrada');
      expect(apiPost.mock.calls[0][1]).toMatchObject({ sala_id: 2 });
      expect(toastSuccess).toHaveBeenCalledWith('Registro salvo com sucesso.');
      expect(routerNavigate).toHaveBeenCalledWith(['/home']);
      expect(comp.saving()).toBe(false);
    });

    it('anormalidade → redireciona para /anormalidade com os ids da resposta', () => {
      const comp = criar();
      novoValido(comp);
      comp.houveAnormalidade = 'sim';
      apiPost.mockReturnValue(of({ ok: true, houve_anormalidade: true, tipo_evento: 'operacao', registro_id: 5, entrada_id: 9 }));
      comp.onSubmit();
      expect(routerNavigate).toHaveBeenCalledWith(['/anormalidade'], {
        queryParams: { registro_id: 5, entrada_id: 9, modo: 'novo' },
      });
    });

    it('resposta ok:false → toast de erro com a mensagem do backend', () => {
      const comp = criar();
      novoValido(comp);
      apiPost.mockReturnValue(of({ ok: false, error: 'Falhou' }));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith('Falhou');
    });

    it('erro HTTP → toast "Erro ao salvar: …" e saving desligado', () => {
      const comp = criar();
      novoValido(comp);
      apiPost.mockReturnValue(throwError(() => ({ error: { error: 'boom' } })));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Erro ao salvar'));
      expect(comp.saving()).toBe(false);
    });

    it('multi-operador válido: POST com operadores_ids', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.isMultiOperador = true;
      comp.salaId = '5';
      comp.nomeEvento = 'Sessão';
      comp.horaInicio = '09:00';
      comp.horaFim = '11:00';
      comp.selectedOperadorIds = ['1'];
      apiPost.mockReturnValue(of({ ok: true }));
      comp.onSubmit();
      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0][1]).toMatchObject({ operadores_ids: ['1'] });
      expect(routerNavigate).toHaveBeenCalledWith(['/home']);
    });
  });

  describe('onSubmit — submitEdit (edição)', () => {
    function edicaoValida(comp: OperacaoFormComponent) {
      salasSignal.set([{ id: 2, nome: 'Plenário' }]);
      comp.editMode.set(true);
      comp.editData.set({ ordem: 1, somente_leitura: false });
      comp.salaId = '2';
      comp.nomeEvento = 'Ev';
      comp.horaInicio = '09:00';
      comp.eventoEncerrado = true;
      comp.horaFim = '11:00';
      (comp as any).entradaIdEdit = 7;
    }

    it('sucesso: PUT no endpoint certo (entrada_id no payload), toast e recarga do detalhe', () => {
      const comp = criar();
      edicaoValida(comp);
      apiPut.mockReturnValue(of({ ok: true }));
      comp.onSubmit();
      expect(apiPut).toHaveBeenCalledTimes(1);
      expect(apiPut.mock.calls[0][0]).toBe('/api/operacao/audio/editar-entrada');
      expect(apiPut.mock.calls[0][1]).toMatchObject({ entrada_id: 7 });
      expect(toastSuccess).toHaveBeenCalledWith('Registro atualizado com sucesso!');
      expect(apiGet).toHaveBeenCalledWith('/api/operador/operacao/detalhe', { entrada_id: 7 });
    });

    it('anormalidade nova → redireciona para /anormalidade', () => {
      const comp = criar();
      edicaoValida(comp);
      apiPut.mockReturnValue(of({ ok: true, houve_anormalidade_nova: true, registro_id: 5, entrada_id: 9 }));
      comp.onSubmit();
      expect(routerNavigate).toHaveBeenCalledWith(['/anormalidade'], {
        queryParams: { registro_id: 5, entrada_id: 9, modo: 'novo' },
      });
    });

    it('resposta ok:false → toast de erro do backend', () => {
      const comp = criar();
      edicaoValida(comp);
      apiPut.mockReturnValue(of({ ok: false, error: 'x' }));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith('x');
    });

    it('erro HTTP → toast "Erro ao salvar: …"', () => {
      const comp = criar();
      edicaoValida(comp);
      apiPut.mockReturnValue(throwError(() => ({ error: { error: 'boom' } })));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Erro ao salvar'));
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // fechar() · suspensões · preencherCom* · enterEditMode
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

  describe('suspensões', () => {
    it('addSuspensao adiciona um par vazio', () => {
      const comp = criar();
      comp.suspensoes = [];
      comp.addSuspensao();
      expect(comp.suspensoes).toEqual([{ hora_suspensao: '', hora_reabertura: '' }]);
    });

    it('removeSuspensao remove pelo índice', () => {
      const comp = criar();
      comp.suspensoes = [
        { hora_suspensao: '10:00', hora_reabertura: '10:10' },
        { hora_suspensao: '11:00', hora_reabertura: '11:10' },
        { hora_suspensao: '12:00', hora_reabertura: '12:10' },
      ];
      comp.removeSuspensao(1);
      expect(comp.suspensoes.map(s => s.hora_suspensao)).toEqual(['10:00', '12:00']);
    });
  });

  describe('preencherComEntrada / preencherComSessao', () => {
    it('preencherComEntrada mapeia campos, resolve a comissão e reseta os dados do operador', () => {
      const comp = criar();
      comissoesSignal.set([{ id: 7, nome: 'CCJ' }]);
      comp.horaFim = '99:99'; // será resetado
      (comp as any).preencherComEntrada({
        data_operacao: '2026-07-15T00:00:00',
        nome_evento: 'Ev',
        responsavel_evento: 'Resp',
        horario_pauta: '08:30:00',
        horario_inicio: '09:00:00',
        comissao_id: 7,
        hora_entrada: '09:05:00',
      });
      expect(comp.dataOperacao).toBe('2026-07-15');
      expect(comp.nomeEvento).toBe('Ev');
      expect(comp.responsavelEvento).toBe('Resp');
      expect(comp.horarioPauta).toBe('08:30');
      expect(comp.horaInicio).toBe('09:00');
      expect(comp.comissaoId).toBe('7');
      expect(comp.comissaoNome).toBe('CCJ');
      expect(comp.horaEntrada).toBe('09:05');
      expect(comp.horaFim).toBe(''); // resetDadosOperador
      expect(comp.eventoEncerrado).toBe(true);
      expect(comp.houveAnormalidade).toBe('nao');
    });

    it('preencherComSessao com entradas: herda da última entrada e limpa a hora do operador', () => {
      const comp = criar();
      (comp as any).preencherComSessao({
        entradas_sessao: [
          { ordem: 1, nome_evento: 'Ev1', horario_inicio: '08:00:00' },
          { ordem: 2, nome_evento: 'Ev2', horario_inicio: '10:00:00', hora_entrada: '10:05:00' },
        ],
      });
      expect(comp.nomeEvento).toBe('Ev2');
      expect(comp.horaInicio).toBe('10:00');
      expect(comp.horaEntrada).toBe(''); // limpo após herdar (campo pessoal do operador)
    });

    it('preencherComSessao sem entradas: usa os campos de sessão do payload', () => {
      const comp = criar();
      (comp as any).preencherComSessao({
        data: '2026-07-20',
        nome_evento: 'EvS',
        responsavel_evento: 'R',
        horario_pauta: '08:00:00',
        horario_inicio: '09:00:00',
      });
      expect(comp.dataOperacao).toBe('2026-07-20');
      expect(comp.nomeEvento).toBe('EvS');
      expect(comp.horarioPauta).toBe('08:00');
      expect(comp.horaInicio).toBe('09:00');
      expect(comp.horaEntrada).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onSalaChange — orquestrador da situação da sessão (ApiService mockado)
  // ═══════════════════════════════════════════════════════════════════
  describe('onSalaChange', () => {
    it('sala vazia: volta ao estado inicial e zera o estado de sessão', () => {
      const comp = criar();
      comp.salaId = '';
      comp.onSalaChange();
      expect(comp.situacao()).toBe('inicial');
      expect((comp as any).estadoSessao).toBeNull();
      expect(comp.isMultiOperador).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('não-multi, sem sessão e sem entradas: situação sem_sessao', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(of({ data: { existe_sessao_aberta: false, entradas_operador: [] } }));
      comp.onSalaChange();
      expect(apiGet.mock.calls[0][0]).toBe('/api/operacao/audio/estado-sessao');
      expect(comp.situacao()).toBe('sem_sessao');
      expect(comp.isMultiOperador).toBe(false);
    });

    it('não-multi, sessão aberta sem entradas do operador: sem_entrada e herda a sessão', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(of({
        data: {
          existe_sessao_aberta: true,
          entradas_operador: [],
          entradas_sessao: [{ ordem: 1, nome_evento: 'Sessão X', horario_inicio: '09:00:00' }],
        },
      }));
      comp.onSalaChange();
      expect(comp.situacao()).toBe('sem_entrada');
      expect(comp.nomeEvento).toBe('Sessão X');
      expect(comp.horaInicio).toBe('09:00');
    });

    it('multi-operador: sem_sessao, carrega operadores do plenário e trava o próprio usuário', () => {
      const comp = criar();
      salasSignal.set([{ id: 5, nome: 'Plenário Principal', multi_operador: true }]);
      comp.salaId = '5';
      apiGet.mockReturnValue(of({ data: { existe_sessao_aberta: false, entradas_operador: [] } }));
      comp.onSalaChange();
      expect(comp.isMultiOperador).toBe(true);
      expect(comp.situacao()).toBe('sem_sessao');
      expect(loadOperadoresPlenario).toHaveBeenCalled();
      expect(comp.selectedOperadorIds).toEqual(['9']);
      expect(comp.lockedOperadorIds).toEqual(['9']);
    });

    it('uma entrada ainda aberta: situação uma_entrada e pré-preenche a entrada', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(of({
        data: {
          existe_sessao_aberta: true,
          entradas_operador: [{ horario_termino: null, nome_evento: 'EvA', horario_inicio: '09:00:00', hora_entrada: '09:05:00' }],
        },
      }));
      comp.onSalaChange();
      expect(comp.situacao()).toBe('uma_entrada');
      expect(comp.entradaAberta1).toBe(true);
      expect(comp.nomeEvento).toBe('EvA');
      expect(comp.horaEntrada).toBe('09:05');
    });

    it('duas ou mais entradas: situação duas_entradas', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(of({ data: { existe_sessao_aberta: true, entradas_operador: [{}, {}] } }));
      comp.onSalaChange();
      expect(comp.situacao()).toBe('duas_entradas');
    });

    it('trava a comissão quando a sessão aberta já tem comissão e a sala a exibe', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comissoesSignal.set([{ id: 7, nome: 'CCJ' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(of({
        data: { existe_sessao_aberta: true, entradas_operador: [], entradas_sessao: [], comissao_id: 7 },
      }));
      comp.onSalaChange();
      expect(comp.comissaoTravada).toBe(true);
      expect(comp.comissaoId).toBe('7');
    });

    it('erro na consulta de estado: cai em sem_sessao', () => {
      const comp = criar();
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      comp.salaId = '3';
      apiGet.mockReturnValue(throwError(() => new Error('boom')));
      comp.onSalaChange();
      expect(comp.situacao()).toBe('sem_sessao');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // ngOnInit — wiring de rota (chamado diretamente, sem detectChanges)
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit (rota)', () => {
    it('rota de edição: entra em modo edição e carrega o detalhe', () => {
      routeMock.queryParams = of({ entrada_id: '5' });
      routeMock.snapshot.routeConfig.path = 'operacao/edit';
      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(true);
      expect(apiGet).toHaveBeenCalledWith('/api/operador/operacao/detalhe', { entrada_id: 5 });
      expect(loadSalasOperador).toHaveBeenCalled();
    });

    it('rota nova com sala_id: pré-seleciona a sala e dispara onSalaChange', () => {
      routeMock.queryParams = of({ sala_id: '3' });
      routeMock.snapshot.routeConfig.path = 'operacao';
      salasSignal.set([{ id: 3, nome: 'Plenário 01' }]);
      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(false);
      expect(comp.salaId).toBe('3');
      expect(apiGet).toHaveBeenCalledWith('/api/operacao/audio/estado-sessao', { sala_id: '3' });
      expect(comp.loading()).toBe(false);
    });

    it('rota nova sem parâmetros: apenas encerra o loading em modo novo', () => {
      routeMock.queryParams = of({});
      const comp = criar();
      comp.ngOnInit();
      expect(comp.editMode()).toBe(false);
      expect(comp.loading()).toBe(false);
      expect(comp.salaId).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // loadEditData — mapeamento do detalhe (chamado diretamente)
  // ═══════════════════════════════════════════════════════════════════
  describe('loadEditData', () => {
    it('sucesso não-multi: mapeia os campos e entra em readonly', () => {
      const comp = criar();
      apiGet.mockReturnValue(of({
        data: {
          sala_nome: 'Plenário 01', sala_id: 3, comissao_id: 7, comissao_nome: 'CCJ',
          nome_evento: 'Ev', responsavel_evento: 'R', data: '2026-07-15T00:00:00',
          horario_pauta: '08:30:00', horario_inicio: '09:00:00', horario_termino: '11:00:00',
          hora_entrada: '09:05:00', hora_saida: '10:55:00', usb_01: 'A', usb_02: 'B',
          observacoes: 'obs', houve_anormalidade: true, multi_operador: false,
        },
      }));
      (comp as any).loadEditData();
      expect(comp.salaNome).toBe('Plenário 01');
      expect(comp.salaId).toBe('3');
      expect(comp.comissaoId).toBe('7');
      expect(comp.dataOperacao).toBe('2026-07-15');
      expect(comp.horaInicio).toBe('09:00');
      expect(comp.horaFim).toBe('11:00');
      expect(comp.eventoEncerrado).toBe(true);
      expect(comp.houveAnormalidade).toBe('sim');
      expect(comp.originalAnormalidade).toBe(true);
      expect(comp.isMultiOperador).toBe(false);
      expect(comp.readOnly()).toBe(true);
      expect(comp.loading()).toBe(false);
    });

    it('sem horário de término: evento não encerrado e horaFim vazia', () => {
      const comp = criar();
      apiGet.mockReturnValue(of({ data: { nome_evento: 'Ev', horario_inicio: '09:00:00' } }));
      (comp as any).loadEditData();
      expect(comp.eventoEncerrado).toBe(false);
      expect(comp.horaFim).toBe('');
    });

    it('multi-operador: mapeia suspensões, injeta o usuário logado e trava-o', () => {
      const comp = criar();
      apiGet.mockReturnValue(of({
        data: {
          multi_operador: true,
          suspensoes: [{ hora_suspensao: '10:00:00', hora_reabertura: '10:15:00' }],
          operadores_sessao_ids: [1, 2],
        },
      }));
      (comp as any).loadEditData();
      expect(comp.isMultiOperador).toBe(true);
      expect(comp.suspensoes).toEqual([{ hora_suspensao: '10:00', hora_reabertura: '10:15' }]);
      expect(comp.selectedOperadorIds).toEqual(['1', '2', '9']); // usuário logado adicionado
      expect(comp.lockedOperadorIds).toEqual(['9']);
      expect(loadOperadoresPlenario).toHaveBeenCalled();
    });

    it('erro: encerra o loading e mostra toast', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('boom')));
      (comp as any).loadEditData();
      expect(comp.loading()).toBe(false);
      expect(toastError).toHaveBeenCalledWith('Erro ao carregar operação.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // infoOperadoresSessao · operadorOptions
  // ═══════════════════════════════════════════════════════════════════
  describe('infoOperadoresSessao', () => {
    it('um operador: "Registro aberto por …"', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [{ ordem: 1, operador_nome: 'Ana' }] };
      expect(comp.infoOperadoresSessao()).toBe('Registro aberto por Ana.');
    });

    it('dois operadores: usa o ordinal e ordena por ordem', () => {
      const comp = criar();
      (comp as any).estadoSessao = {
        entradas_sessao: [
          { ordem: 2, operador_nome: 'Bruno', entrada_id: 2 },
          { ordem: 1, operador_nome: 'Ana', entrada_id: 1 },
        ],
      };
      expect(comp.infoOperadoresSessao()).toBe('Registro aberto por Ana.<br>Segundo registro feito por Bruno');
    });

    it('três operadores: agrupa as descrições de dois em dois', () => {
      const comp = criar();
      (comp as any).estadoSessao = {
        entradas_sessao: [
          { ordem: 1, operador_nome: 'Ana' },
          { ordem: 2, operador_nome: 'Bruno' },
          { ordem: 3, operador_nome: 'Carla' },
        ],
      };
      expect(comp.infoOperadoresSessao()).toBe(
        'Registro aberto por Ana.<br>Segundo registro feito por Bruno • Terceiro registro feito por Carla',
      );
    });

    it('sem entradas: usa nomes_operadores_sessao', () => {
      const comp = criar();
      (comp as any).estadoSessao = { entradas_sessao: [], nomes_operadores_sessao: ['Ana'] };
      expect(comp.infoOperadoresSessao()).toBe('Registro aberto por Ana.');
    });

    it('nada disponível: string vazia', () => {
      const comp = criar();
      (comp as any).estadoSessao = null;
      expect(comp.infoOperadoresSessao()).toBe('');
    });
  });

  describe('operadorOptions', () => {
    it('mapeia para {id string, label} preferindo nome_completo', () => {
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
});

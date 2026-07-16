import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AnormalidadeFormComponent } from './anormalidade-form.component';
import { ApiService } from '../../core/services/api.service';
import { LookupService, LookupItem } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';

/**
 * T24 — AnormalidadeFormComponent (page, 327 LOC — o form mais simples do escopo; §A5).
 *
 * Estratégia (manual de PAGE do T22/T23): o TestBed cria o componente (DI + signals) mas
 * NUNCA chamamos `detectChanges()` — o template importa RouterLink/ngModel e dispararia
 * ngOnInit; o wiring de rota é exercitado chamando `ngOnInit()` diretamente com o
 * ActivatedRoute mockado (`queryParams` reatribuível). Services mockados via `useValue`
 * (padrão T21). Sem Date/localStorage/timers (o form não os usa) → sem fake timers.
 *
 * `onSubmit`: o ramo `res.ok===false` monta a msg INLINE (`res.error || 'Erro desconhecido'`)
 * → asserção do valor EXATO; o ramo de erro HTTP usa `httpErrorMsg(err, 'Erro de conexão',
 * ['error'])` sob o prefixo `'Erro ao salvar: '` → `expect.stringContaining` (padrão T22).
 * `focusFirst` (guardas) faz `document.querySelector('[name=…]')` → null sem DOM renderizado
 * → no-op seguro; a guarda é observada pela ausência de POST.
 */
describe('AnormalidadeFormComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let routerNavigate: ReturnType<typeof vi.fn>;
  let loadAll: ReturnType<typeof vi.fn>;
  let salasSignal: WritableSignal<LookupItem[]>;
  let routeMock: any;

  beforeEach(async () => {
    apiGet = vi.fn().mockReturnValue(of({ ok: true, data: {} }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    toastSuccess = vi.fn();
    toastError = vi.fn();
    routerNavigate = vi.fn();
    loadAll = vi.fn();
    salasSignal = signal<LookupItem[]>([]);
    routeMock = { queryParams: of({}) };

    await TestBed.configureTestingModule({
      imports: [AnormalidadeFormComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError } },
        { provide: Router, useValue: { navigate: routerNavigate } },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: LookupService, useValue: { salas: salasSignal, loadAll } },
      ],
    }).compileComponents();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit). */
  function criar(): AnormalidadeFormComponent {
    return TestBed.createComponent(AnormalidadeFormComponent).componentInstance;
  }

  /** Preenche os 3 campos mínimos exigidos pelas guardas do submit. */
  function preencherMinimos(comp: AnormalidadeFormComponent): void {
    comp.responsavelEvento = 'Secretário';
    comp.horaInicio = '10:00';
    comp.descricao = 'Falha de áudio';
  }

  // ═══════════════════════════════════════════════════════════════════
  // isDemaisSalas — Number(salaId) === SALA_DEMAIS_SALAS_ID (11)
  // ═══════════════════════════════════════════════════════════════════
  describe('isDemaisSalas', () => {
    it('true quando salaId é 11 (a sala "Demais Salas")', () => {
      const comp = criar();
      comp.salaId = '11';
      expect(comp.isDemaisSalas()).toBe(true);
    });

    it('false para outra sala', () => {
      const comp = criar();
      comp.salaId = '2';
      expect(comp.isDemaisSalas()).toBe(false);
    });

    it('false quando salaId vazio (Number("")===0)', () => {
      const comp = criar();
      comp.salaId = '';
      expect(comp.isDemaisSalas()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // ngOnInit — leitura da rota e ramificação de carga
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('dispara lookup.loadAll()', () => {
      const comp = criar();
      comp.ngOnInit();
      expect(loadAll).toHaveBeenCalledTimes(1);
    });

    it('sem registro_id: apenas encerra o loading e NÃO busca o lookup do registro', () => {
      routeMock.queryParams = of({});
      const comp = criar();
      comp.ngOnInit();
      expect(comp.registroId).toBe('');
      expect(comp.loading()).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('com registro_id em modo novo SEM entrada_id: carrega o lookup e NÃO carrega RAOA existente', () => {
      routeMock.queryParams = of({ registro_id: 'r1', modo: 'novo' });
      apiGet.mockImplementation((endpoint: string) => {
        if (endpoint === '/api/forms/lookup/registro-operacao') {
          return of({ ok: true, data: { sala_id: 5, nome_evento: 'Sessão' } });
        }
        return of({ ok: true, data: {} });
      });
      const comp = criar();
      comp.ngOnInit();
      expect(comp.registroId).toBe('r1');
      expect(apiGet).toHaveBeenCalledTimes(1);
      expect(apiGet.mock.calls[0][0]).toBe('/api/forms/lookup/registro-operacao');
      expect(apiGet.mock.calls[0][1]).toEqual({ id: 'r1' });
      expect(comp.loading()).toBe(false);
    });

    it('modo novo COM entrada_id: passa entrada_id ao lookup e carrega a RAOA existente', () => {
      routeMock.queryParams = of({ registro_id: 'r1', entrada_id: 'e9', modo: 'novo' });
      const comp = criar();
      comp.ngOnInit();
      expect(comp.entradaId).toBe('e9');
      expect(apiGet.mock.calls[0][1]).toEqual({ id: 'r1', entrada_id: 'e9' });
      // 2ª chamada = loadExisting (novo && entradaId)
      expect(apiGet).toHaveBeenCalledTimes(2);
      expect(apiGet.mock.calls[1][0]).toBe('/api/operacao/anormalidade/registro');
      expect(apiGet.mock.calls[1][1]).toEqual({ entrada_id: 'e9' });
    });

    it('modo edicao: carrega a RAOA existente mesmo sem entrada_id no par novo', () => {
      routeMock.queryParams = of({ registro_id: 'r1', entrada_id: 'e9', modo: 'edicao' });
      const comp = criar();
      comp.ngOnInit();
      expect(apiGet).toHaveBeenCalledTimes(2);
      expect(apiGet.mock.calls[1][0]).toBe('/api/operacao/anormalidade/registro');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // loadLookupData — pré-preenchimento a partir do registro de operação
  // ═══════════════════════════════════════════════════════════════════
  describe('loadLookupData', () => {
    it('preenche data (só a parte YYYY-MM-DD), sala, evento, demais-salas e responsável', () => {
      const comp = criar();
      comp.registroId = 'r1';
      apiGet.mockReturnValue(of({
        ok: true,
        data: {
          data: '2026-07-10T00:00:00',
          sala_id: 11,
          nome_evento: 'Audiência Pública',
          nome_demais_salas: 'Sala Azul',
          responsavel_evento: 'Chefe da Mesa',
        },
      }));
      (comp as any).loadLookupData('novo');
      expect(comp.data).toBe('2026-07-10');
      expect(comp.salaId).toBe('11');
      expect(comp.nomeEvento).toBe('Audiência Pública');
      expect(comp.nomeDemaisSalas).toBe('Sala Azul');
      expect(comp.responsavelEvento).toBe('Chefe da Mesa');
      expect(comp.loading()).toBe(false);
    });

    it('res.ok=false NÃO preenche campos, mas ainda encerra o loading (modo novo sem entrada)', () => {
      const comp = criar();
      comp.registroId = 'r1';
      apiGet.mockReturnValue(of({ ok: false }));
      (comp as any).loadLookupData('novo');
      expect(comp.nomeEvento).toBe('');
      expect(comp.loading()).toBe(false);
    });

    it('erro HTTP encerra o loading', () => {
      const comp = criar();
      comp.registroId = 'r1';
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      (comp as any).loadLookupData('novo');
      expect(comp.loading()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // loadExisting — hidratação da RAOA já cadastrada (booleanos → string)
  // ═══════════════════════════════════════════════════════════════════
  describe('loadExisting', () => {
    it('mapeia os booleanos do backend para "true"/"false" e hidrata os campos', () => {
      const comp = criar();
      comp.entradaId = 'e9';
      apiGet.mockReturnValue(of({
        ok: true,
        data: {
          id: 'anom-1',
          responsavel_evento: 'Resp Existente',
          hora_inicio_anormalidade: '11:30',
          descricao_anormalidade: 'Microfone mudo',
          houve_prejuizo: true,
          descricao_prejuizo: 'Sessão adiada',
          houve_reclamacao: false,
          autores_conteudo_reclamacao: '',
          acionou_manutencao: true,
          hora_acionamento_manutencao: '11:45',
          resolvida_pelo_operador: false,
          procedimentos_adotados: '',
        },
      }));
      (comp as any).loadExisting();
      expect(comp.anomId).toBe('anom-1');
      expect(comp.horaInicio).toBe('11:30');
      expect(comp.descricao).toBe('Microfone mudo');
      expect(comp.houvePrejuizo).toBe('true');
      expect(comp.descricaoPrejuizo).toBe('Sessão adiada');
      expect(comp.houveReclamacao).toBe('false');
      expect(comp.acionouManutencao).toBe('true');
      expect(comp.horaManutencao).toBe('11:45');
      expect(comp.resolvidaOperador).toBe('false');
      expect(comp.loading()).toBe(false);
    });

    it('mantém o responsável já carregado do lookup quando a RAOA não traz o campo', () => {
      const comp = criar();
      comp.responsavelEvento = 'Do Lookup';
      apiGet.mockReturnValue(of({ ok: true, data: { id: 'a1' } }));
      (comp as any).loadExisting();
      expect(comp.responsavelEvento).toBe('Do Lookup');
    });

    it('erro HTTP encerra o loading', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      (comp as any).loadExisting();
      expect(comp.loading()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onToggle — limpa o campo condicional quando o toggle volta para "Não"
  // ═══════════════════════════════════════════════════════════════════
  describe('onToggle', () => {
    it('prejuizo="false" limpa descricaoPrejuizo', () => {
      const comp = criar();
      comp.houvePrejuizo = 'false';
      comp.descricaoPrejuizo = 'texto';
      comp.onToggle('prejuizo');
      expect(comp.descricaoPrejuizo).toBe('');
    });

    it('reclamacao="false" limpa autoresReclamacao', () => {
      const comp = criar();
      comp.houveReclamacao = 'false';
      comp.autoresReclamacao = 'texto';
      comp.onToggle('reclamacao');
      expect(comp.autoresReclamacao).toBe('');
    });

    it('manutencao="false" limpa horaManutencao', () => {
      const comp = criar();
      comp.acionouManutencao = 'false';
      comp.horaManutencao = '10:00';
      comp.onToggle('manutencao');
      expect(comp.horaManutencao).toBe('');
    });

    it('resolvida="false" limpa procedimentos', () => {
      const comp = criar();
      comp.resolvidaOperador = 'false';
      comp.procedimentos = 'texto';
      comp.onToggle('resolvida');
      expect(comp.procedimentos).toBe('');
    });

    it('NÃO limpa o campo quando o toggle está em "true"', () => {
      const comp = criar();
      comp.houvePrejuizo = 'true';
      comp.descricaoPrejuizo = 'preservar';
      comp.onToggle('prejuizo');
      expect(comp.descricaoPrejuizo).toBe('preservar');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onSubmit — guardas de validação (nenhuma chama a API)
  // ═══════════════════════════════════════════════════════════════════
  describe('onSubmit — guardas (bloqueiam o POST)', () => {
    it('sem responsável não submete', () => {
      const comp = criar();
      comp.horaInicio = '10:00';
      comp.descricao = 'x';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('sem hora de início não submete', () => {
      const comp = criar();
      comp.responsavelEvento = 'R';
      comp.descricao = 'x';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('sem descrição não submete', () => {
      const comp = criar();
      comp.responsavelEvento = 'R';
      comp.horaInicio = '10:00';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('houvePrejuizo="true" sem descrição do prejuízo não submete', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.houvePrejuizo = 'true';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('houveReclamacao="true" sem autores não submete', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.houveReclamacao = 'true';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('acionouManutencao="true" sem hora da manutenção não submete', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.acionouManutencao = 'true';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('resolvidaOperador="true" sem procedimentos não submete', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.resolvidaOperador = 'true';
      comp.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onSubmit — caminho feliz e montagem do payload
  // ═══════════════════════════════════════════════════════════════════
  describe('onSubmit — sucesso e payload', () => {
    it('POSTa no endpoint certo, mostra sucesso e navega para /home', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.registroId = 'r1';
      comp.onSubmit();
      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0][0]).toBe('/api/operacao/anormalidade/registro');
      expect(apiPost.mock.calls[0][1]).toMatchObject({
        registro_id: 'r1',
        responsavel_evento: 'Secretário',
        hora_inicio_anormalidade: '10:00',
        descricao_anormalidade: 'Falha de áudio',
      });
      expect(toastSuccess).toHaveBeenCalledWith('Registro de anormalidade salvo com sucesso!');
      expect(routerNavigate).toHaveBeenCalledWith(['/home']);
      expect(comp.saving()).toBe(false);
    });

    it('entrada_id vira null quando ausente e o valor real quando presente', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1].entrada_id).toBeNull();

      apiPost.mockClear();
      comp.entradaId = 'e5';
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1].entrada_id).toBe('e5');
    });

    it('inclui id no payload apenas quando há anomId (edição)', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1].id).toBeUndefined();

      apiPost.mockClear();
      comp.anomId = 'anom-9';
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1].id).toBe('anom-9');
    });

    it('campos condicionais entram no payload quando o toggle está "true"', () => {
      const comp = criar();
      preencherMinimos(comp);
      comp.houvePrejuizo = 'true';
      comp.descricaoPrejuizo = 'Adiado';
      comp.houveReclamacao = 'true';
      comp.autoresReclamacao = 'Deputado X';
      comp.acionouManutencao = 'true';
      comp.horaManutencao = '10:30';
      comp.resolvidaOperador = 'true';
      comp.procedimentos = 'Troca de cabo';
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1]).toMatchObject({
        houve_prejuizo: 'true',
        descricao_prejuizo: 'Adiado',
        houve_reclamacao: 'true',
        autores_conteudo_reclamacao: 'Deputado X',
        acionou_manutencao: 'true',
        hora_acionamento_manutencao: '10:30',
        resolvida_pelo_operador: 'true',
        procedimentos_adotados: 'Troca de cabo',
      });
    });

    it('campos condicionais são zerados no payload quando o toggle está "false" (mesmo com valor residual)', () => {
      const comp = criar();
      preencherMinimos(comp);
      // valores residuais que NÃO devem vazar porque os toggles estão em "false"
      comp.houvePrejuizo = 'false';
      comp.descricaoPrejuizo = 'residuo';
      comp.houveReclamacao = 'false';
      comp.autoresReclamacao = 'residuo';
      comp.acionouManutencao = 'false';
      comp.horaManutencao = '09:00';
      comp.resolvidaOperador = 'false';
      comp.procedimentos = 'residuo';
      comp.onSubmit();
      expect(apiPost.mock.calls[0][1]).toMatchObject({
        descricao_prejuizo: '',
        autores_conteudo_reclamacao: '',
        hora_acionamento_manutencao: '',
        procedimentos_adotados: '',
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // onSubmit — erros (res.ok=false inline; erro HTTP via httpErrorMsg)
  // ═══════════════════════════════════════════════════════════════════
  describe('onSubmit — erros', () => {
    it('res.ok=false com error mostra a mensagem exata do backend', () => {
      const comp = criar();
      preencherMinimos(comp);
      apiPost.mockReturnValue(of({ ok: false, error: 'Sala inválida' }));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith('Sala inválida');
      expect(routerNavigate).not.toHaveBeenCalled();
      expect(comp.saving()).toBe(false);
    });

    it('res.ok=false sem error cai no fallback "Erro desconhecido"', () => {
      const comp = criar();
      preencherMinimos(comp);
      apiPost.mockReturnValue(of({ ok: false }));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith('Erro desconhecido');
    });

    it('erro HTTP mostra "Erro ao salvar: …" e reseta saving', () => {
      const comp = criar();
      preencherMinimos(comp);
      apiPost.mockReturnValue(throwError(() => ({ error: { error: 'boom' } })));
      comp.onSubmit();
      expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Erro ao salvar'));
      expect(comp.saving()).toBe(false);
    });
  });
});

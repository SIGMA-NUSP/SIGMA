import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { FeatureFlagService } from '../../core/services/feature-flags.service';
import { ErroCargaComponent } from './erro-carga.component';
import { MinhaFolha } from './folhas-ponto-lista.component';
import { PontoBancoComponent } from './ponto-banco.component';
import { RegistroManualPontoComponent } from './registro-manual-ponto.component';

/**
 * PontoBancoComponent (página `/ponto`, compartilhada por operador, técnico e admin com
 * folha): busca de "minhas folhas" com canal de erro + retry, acordeão com registro manual
 * e Voltar roteado por papel. Os testes do acordeão e de erro renderizam para verificar o
 * contrato visível da página, nunca layout/CSS. TestBed sem `detectChanges()` por padrão —
 * `ngOnInit` à mão;
 * `ApiService`/`AuthService` mockados via `useValue` (o `role` é um signal writable). O `get`
 * mockado despacha por URL: o `BancoHorasPessoalComponent`, instanciado sempre (fica em
 * `[hidden]`, não em `@if`), dispara os seus próprios GETs. Relógio congelado antes de
 * `createComponent`: o SUT não lê `Date`, mas esse filho lê no render.
 */
describe('PontoBancoComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiGetList: ReturnType<typeof vi.fn>;
  let role: WritableSignal<string | null>;
  let flagRegistroManual: boolean;

  const FOLHAS: MinhaFolha[] = [
    { id: 'f-1', tipo: 'MENSAL', data_inicio: '2026-06-01', data_fim: '2026-06-30', publicado_em: '2026-07-02' },
    { id: 'f-2', tipo: 'MENSAL', data_inicio: '2026-05-01', data_fim: '2026-05-31', publicado_em: '2026-06-02' },
  ];

  /** Banco de horas do filho (só usado nos testes que renderizam). */
  const BANCO = { saldo_min: 0, saldo_fmt: '+00:00', carga_horaria: 30, folgas_mes: 0, dias_bloqueados: [] };

  beforeEach(async () => {
    apiGet = vi.fn().mockImplementation((url: string) =>
      url.endsWith('/minhas-folhas') ? of({ data: FOLHAS }) : of({ data: BANCO }),
    );
    apiGetList = vi.fn().mockReturnValue(of({ data: [], meta: { page: 1, limit: 10, total: 0, pages: 1 } }));
    role = signal<string | null>('operador');
    flagRegistroManual = true;   // o card destravado é o cenário da maioria dos testes

    await TestBed.configureTestingModule({
      imports: [PontoBancoComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink no "Voltar" (instanciado só no render)
        { provide: ApiService, useValue: { get: apiGet, getList: apiGetList, post: vi.fn(), patch: vi.fn() } },
        { provide: AuthService, useValue: { role, temFolhaPonto: signal(true) } },
        // Só a flag do card é controlada; as demais seguem desligadas, como no runtime sem config
        { provide: FeatureFlagService, useValue: {
          isEnabled: (flag: string) => flag === 'registroManualPonto' && flagRegistroManual } },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function criar() {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
    const fixture = TestBed.createComponent(PontoBancoComponent);
    return { fixture, comp: fixture.componentInstance };
  }

  /** Componente com as folhas já carregadas (ngOnInit à mão — sem render). */
  function criarCarregado(): PontoBancoComponent {
    const { comp } = criar();
    comp.ngOnInit();
    return comp;
  }

  // ═══════════════════════════════════════════════════════════════════
  // Carga das folhas (GET /api/ponto/minhas-folhas)
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('busca as folhas do usuário autenticado e encerra o carregando', () => {
      const comp = criarCarregado();
      expect(apiGet).toHaveBeenCalledWith('/api/ponto/minhas-folhas');
      expect(comp.folhas()).toEqual(FOLHAS);
      expect(comp.loading()).toBe(false);
    });

    it('mantém o carregando enquanto a resposta não chega', () => {
      const emVoo = new Subject<any>();
      apiGet.mockReturnValue(emVoo);
      const comp = criarCarregado();
      expect(comp.loading()).toBe(true);
      expect(comp.folhas()).toEqual([]);
      emVoo.next({ data: FOLHAS });
      expect(comp.loading()).toBe(false);
    });

    it('payload sem `data` vira lista vazia (sem estourar)', () => {
      apiGet.mockReturnValue(of({ ok: true }));
      const comp = criarCarregado();
      expect(comp.folhas()).toEqual([]);
      expect(comp.loading()).toBe(false);
    });

    it('erro na carga NÃO é indistinguível de "nenhuma folha"', () => {
      // O cerne do problema era a EQUIVALÊNCIA — falha (500/timeout) e ausência real de
      // folhas deixavam o componente no mesmo estado observável, então a tela dizia "Nenhuma folha
      // de ponto disponível ainda" nos dois casos, enquanto o prazo de retificação (5 dias) corria.
      // Agora o erro tem canal próprio: o template exibe a caixa com retry, não a frase do vazio.
      const snapshot = (c: PontoBancoComponent) =>
        ({ folhas: c.folhas(), loading: c.loading(), erro: c.erro() });

      apiGet.mockReturnValue(of({ data: [] }));                                   // não há folhas
      const semFolhas = criarCarregado();
      expect(snapshot(semFolhas)).toEqual({ folhas: [], loading: false, erro: '' });

      apiGet.mockReturnValue(throwError(() => ({ status: 500, error: { message: 'Erro interno' } })));
      const comErro = criarCarregado();                                            // a carga falhou

      expect(snapshot(comErro)).not.toEqual(snapshot(semFolhas));                  // distinguíveis
      expect(comErro.erro()).toContain('Erro interno');   // guia da tela + detalhe do backend
      expect(comErro.folhas()).toEqual([]);
      expect(comErro.loading()).toBe(false);
    });

    it('erro sem mensagem do backend cai no fallback', () => {
      apiGet.mockReturnValue(throwError(() => ({ status: 503 })));
      const comp = criarCarregado();
      expect(comp.erro()).toBe(
        'Não foi possível carregar as folhas de ponto.');
    });

    it('o retry re-dispara a carga; o sucesso limpa o erro e exibe as folhas', () => {
      apiGet.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();
      expect(comp.erro()).not.toBe('');

      apiGet.mockReturnValue(of({ data: FOLHAS }));
      comp.carregarFolhas();                       // o botão "Tentar novamente" da caixa de erro

      expect(apiGet).toHaveBeenCalledTimes(2);
      expect(comp.erro()).toBe('');
      expect(comp.folhas()).toEqual(FOLHAS);
      expect(comp.loading()).toBe(false);
    });

    it('uma nova carga limpa o erro já no disparo (a tela não mostra erro velho carregando)', () => {
      apiGet.mockReturnValue(throwError(() => ({ status: 500 })));
      const comp = criarCarregado();

      const emVoo = new Subject<any>();
      apiGet.mockReturnValue(emVoo);
      comp.carregarFolhas();

      expect(comp.erro()).toBe('');
      expect(comp.loading()).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Acordeão de cards
  // ═══════════════════════════════════════════════════════════════════
  describe('toggleCard', () => {
    it('nenhum card aberto no início', () => {
      const { comp } = criar();
      expect(comp.activeCard()).toBeNull();
    });

    it('abre o card clicado', () => {
      const comp = criarCarregado();
      comp.toggleCard('folhas');
      expect(comp.activeCard()).toBe('folhas');
    });

    it('clicar no card já aberto fecha (acordeão de 1 por vez)', () => {
      const comp = criarCarregado();
      comp.toggleCard('banco');
      comp.toggleCard('banco');
      expect(comp.activeCard()).toBeNull();
    });

    it('abrir outro card troca o ativo (o anterior fecha)', () => {
      const comp = criarCarregado();
      comp.toggleCard('folhas');
      comp.toggleCard('banco');
      expect(comp.activeCard()).toBe('banco');
    });

    it('a troca de card não refaz a busca das folhas', () => {
      const comp = criarCarregado();
      apiGet.mockClear();
      comp.toggleCard('folhas');
      comp.toggleCard('banco');
      expect(apiGet).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // backLink — o Voltar depende do papel (o admin chega aqui de /admin/ponto)
  // ═══════════════════════════════════════════════════════════════════
  describe('backLink', () => {
    it('administrador volta para /admin/ponto', () => {
      role.set('administrador');
      const { comp } = criar();
      expect(comp.backLink()).toBe('/admin/ponto');
    });

    it('técnico volta para /tecnico', () => {
      role.set('tecnico');
      const { comp } = criar();
      expect(comp.backLink()).toBe('/tecnico');
    });

    it('operador volta para /home', () => {
      role.set('operador');
      const { comp } = criar();
      expect(comp.backLink()).toBe('/home');
    });

    it('papel ausente cai em /home (fallback do homeRouteForRole)', () => {
      role.set(null);
      const { comp } = criar();
      expect(comp.backLink()).toBe('/home');
    });

    it('reage à troca de papel (computed sobre o signal do AuthService)', () => {
      const { comp } = criar();
      expect(comp.backLink()).toBe('/home');
      role.set('administrador');
      expect(comp.backLink()).toBe('/admin/ponto');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Registro manual no acordeão
  // ═══════════════════════════════════════════════════════════════════
  describe('registro manual', () => {
    function botaoCard(fixture: any, texto: string): HTMLButtonElement {
      const botao = fixture.debugElement.queryAll(By.css('button.card-pick'))
        .map((d: any) => d.nativeElement as HTMLButtonElement)
        .find((b: HTMLButtonElement) => b.textContent?.trim() === texto);
      if (!botao) throw new Error(`card "${texto}" não encontrado`);
      return botao;
    }

    it('instancia o componente e exibe o botão normalmente', () => {
      const { fixture } = criar();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(RegistroManualPontoComponent))).not.toBeNull();
      expect(botaoCard(fixture, 'Registro manual de ponto')).toBeTruthy();
    });

    it('o botão abre e fecha o painel manual atualizando activeCard', () => {
      const { fixture, comp } = criar();
      fixture.detectChanges();
      const botaoManual = botaoCard(fixture, 'Registro manual de ponto');
      const painelManual = botaoManual.nextElementSibling as HTMLDivElement;

      expect(comp.activeCard()).toBeNull();
      expect(painelManual.hidden).toBe(true);

      botaoManual.click();
      fixture.detectChanges();
      expect(comp.activeCard()).toBe('manual');
      expect(painelManual.hidden).toBe(false);

      botaoManual.click();
      fixture.detectChanges();
      expect(comp.activeCard()).toBeNull();
      expect(painelManual.hidden).toBe(true);
    });

    it('flag desligada: o card fica visível porém inerte, dizendo "Indisponível"', () => {
      flagRegistroManual = false;
      const { fixture, comp } = criar();
      fixture.detectChanges();

      const botao = fixture.debugElement.queryAll(By.css('button.card-pick'))
        .map(d => d.nativeElement as HTMLButtonElement)
        .find(b => b.textContent!.includes('Registro manual de ponto'))!;
      expect(botao.disabled).toBe(true);
      expect(botao.classList.contains('card-disabled')).toBe(true);
      expect(botao.textContent).toContain('Indisponível');

      botao.click();
      fixture.detectChanges();
      expect(comp.activeCard()).toBeNull();   // o clique num botão inerte não chega ao handler
    });

    it('ao abrir outro card mantém somente um painel aberto e preserva a instância manual', () => {
      const { fixture, comp } = criar();
      fixture.detectChanges();
      const manualAntes = fixture.debugElement.query(By.directive(RegistroManualPontoComponent)).componentInstance;

      botaoCard(fixture, 'Registro manual de ponto').click();
      fixture.detectChanges();
      botaoCard(fixture, 'Banco de horas').click();
      fixture.detectChanges();

      const paineis = fixture.debugElement.queryAll(By.css('.painel'))
        .map(d => d.nativeElement as HTMLDivElement);
      expect(comp.activeCard()).toBe('banco');
      expect(paineis.filter(p => !p.hidden)).toHaveLength(1);
      expect(fixture.debugElement.query(By.directive(RegistroManualPontoComponent)).componentInstance)
        .toBe(manualAntes);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O que a TELA mostra no erro — exceção de render deliberada
  // ═══════════════════════════════════════════════════════════════════
  describe('render do estado de erro', () => {
    // O signal `erro` só cumpre o papel se o TEMPLATE o consumir: sem estes testes, apagar o ramo
    // `@else if (erro())` deixaria a suíte verde e o painel voltaria a dizer "Nenhuma folha de ponto
    // disponível ainda." numa falha de carga. Asserção de PRESENÇA/AUSÊNCIA de estado (mesma família
    // da exceção já autorizada acima), nunca de disposição ou CSS.
    const painel = (fixture: any) =>
      (fixture.debugElement.queryAll(By.css('.painel'))[0].nativeElement as HTMLElement).textContent ?? '';

    it('sem folhas de verdade: a frase do vazio, sem caixa de erro', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/minhas-folhas') ? of({ data: [] }) : of({ data: BANCO }));
      const { fixture } = criar();
      fixture.detectChanges();

      expect(painel(fixture)).toContain('Nenhuma folha de ponto disponível ainda.');
      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    });

    it('erro na carga: caixa de erro (com o detalhe do backend) e SEM a frase do vazio', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/minhas-folhas')
          ? throwError(() => ({ status: 500, error: { ok: false, error: 'Erro interno do servidor' } }))
          : of({ data: BANCO }));
      const { fixture } = criar();
      fixture.detectChanges();

      const caixa = fixture.debugElement.query(By.directive(ErroCargaComponent));
      expect(caixa).not.toBeNull();
      expect(caixa.componentInstance.mensagem()).toContain('Não foi possível carregar as folhas de ponto.');
      expect(painel(fixture)).not.toContain('Nenhuma folha de ponto disponível ainda.');
    });

    it('o botão da caixa re-dispara a carga das folhas', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/minhas-folhas') ? throwError(() => ({ status: 500 })) : of({ data: BANCO }));
      const { fixture } = criar();
      fixture.detectChanges();

      apiGet.mockImplementation((url: string) =>
        url.endsWith('/minhas-folhas') ? of({ data: FOLHAS }) : of({ data: BANCO }));
      (fixture.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
      expect(fixture.componentInstance.folhas()).toEqual(FOLHAS);
    });
  });
});

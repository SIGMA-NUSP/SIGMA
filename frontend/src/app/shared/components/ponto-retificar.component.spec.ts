import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { PontoRetificarComponent } from './ponto-retificar.component';

/**
 * PontoRetificarComponent: carga da folha e dos dias já retificados (fail-closed com retry),
 * prazo calculado no BACKEND (o front só lê `limite_fmt`/`prazo_expirado` de
 * `GET /api/ponto/folha/{id}/retificacoes`), validação de pares Ent./Saí., teto de 300
 * caracteres na observação e gravação em UM POST de LOTE (`{dias:[…]}`, transacional), com
 * trava de duplo clique e timer de navegação cancelado no destroy. TestBed sem
 * `detectChanges()`; `ApiService`/`Router`/`ActivatedRoute` mockados via `useValue` (o
 * `RouterLink` do template resolve os mesmos mocks na criação). Fake timers COMPLETOS (não só
 * `Date`) — o `salvar()` feliz agenda `setTimeout(…, 1400)` — instalados APÓS
 * `compileComponents()`, que exige timers reais. ⚠️ Zoneless: `TestBed.createComponent` deixa
 * timers pendentes (`fixture.autoDetectChanges(false)` é PROIBIDO) e drenar timers
 * (`runAllTimers`/`advanceTimersByTime`) ACORDA a change detection, que roda o `ngOnInit` do
 * SUT — drenar SÓ onde o efeito do timer é o objeto do teste, e nunca assertar contagem de
 * chamadas à API depois de drenar.
 */
describe('PontoRetificarComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let navigateByUrl: ReturnType<typeof vi.fn>;
  let paginaId: string | null;

  /** Folha semanal com 3 dias: 2 úteis + 1 de status (Feriado). */
  const FOLHA = {
    id: 'pag-1',
    tipo: 'SEMANAL',
    data_inicio: '2026-07-06',
    data_fim: '2026-07-10',
    linhas: [
      { dia: '06/07/26 - seg', ent1: '08:00', sai1: '12:00', ent2: '13:00', sai2: '17:00', total_dia: '08:00', banco: '00:00' },
      { dia: '07/07/26 - ter', ent1: 'Feriado', sai1: '', ent2: '', sai2: '', total_dia: '', banco: '00:00' },
      { dia: '08/07/26 - qua', ent1: '08:10', sai1: '12:00', ent2: '', sai2: '', total_dia: '03:50', banco: '-02:10' },
    ],
  };

  /** URL única de gravação: um POST de lote com todos os dias. */
  const URL_LOTE = '/api/ponto/folha/pag-1/retificacoes';

  beforeEach(async () => {
    paginaId = 'pag-1';
    apiGet = vi.fn().mockImplementation((url: string) =>
      url.endsWith('/dados')
        ? of({ data: structuredClone(FOLHA) })
        : of({ data: { limite_fmt: null, prazo_expirado: false, retificacoes: [] } }),
    );
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    navigateByUrl = vi.fn();

    await TestBed.configureTestingModule({
      imports: [PontoRetificarComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost } },
        { provide: Router, useValue: { navigateByUrl } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: (k: string) => (k === 'paginaId' ? paginaId : null) } } },
        },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-12T10:00:00-03:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit automático). */
  function criar(): PontoRetificarComponent {
    return TestBed.createComponent(PontoRetificarComponent).componentInstance;
  }

  /** Componente com a folha já carregada (ngOnInit chamado à mão). */
  function criarCarregado(): PontoRetificarComponent {
    const comp = criar();
    comp.ngOnInit();
    return comp;
  }

  /** Resposta do endpoint de retificações (limite/prazo/dias já retificados). */
  function respostaRetificacoes(data: Record<string, unknown>) {
    apiGet.mockImplementation((url: string) =>
      url.endsWith('/dados') ? of({ data: structuredClone(FOLHA) }) : of({ data }),
    );
  }

  /** A folha carrega, mas a LISTAGEM das retificações falha (fail-closed). */
  function retificacoesFalham(erro: unknown = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } }) {
    apiGet.mockImplementation((url: string) =>
      url.endsWith('/dados') ? of({ data: structuredClone(FOLHA) }) : throwError(() => erro),
    );
  }

  /** Abre o dia (por índice) e preenche os horários/observações da retificação. */
  function preencher(
    comp: PontoRetificarComponent,
    idx: number,
    horas: Partial<{ r_ent1: string; r_sai1: string; r_ent2: string; r_sai2: string; observacoes: string }>,
  ) {
    const linha = comp.linhas()[idx];
    comp.toggle(linha);
    Object.assign(comp.linhas()[idx], horas);
    return comp.linhas()[idx];
  }

  /** Os dias do corpo do lote (o único POST). */
  const diasEnviados = (chamada = 0) => apiPost.mock.calls[chamada][1].dias;

  // ═══════════════════════════════════════════════════════════════════
  // ngOnInit — carga da folha pela rota
  // ═══════════════════════════════════════════════════════════════════
  describe('ngOnInit', () => {
    it('sem paginaId na rota: erro e nenhuma chamada à API', () => {
      paginaId = null;
      const comp = criar();
      comp.ngOnInit();
      expect(comp.erro()).toBe('Folha não informada.');
      expect(comp.loading()).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('carrega a folha, fecha todas as linhas e encerra o loading', () => {
      const comp = criarCarregado();
      expect(apiGet.mock.calls[0][0]).toBe('/api/ponto/folha/pag-1/dados');
      expect(comp.dados()?.id).toBe('pag-1');
      expect(comp.linhas()).toHaveLength(3);
      expect(comp.linhas().every(l => l.aberto === false)).toBe(true);
      expect(comp.loading()).toBe(false);
      expect(comp.erro()).toBe('');
    });

    it('encadeia a carga das retificações da mesma folha', () => {
      criarCarregado();
      expect(apiGet.mock.calls[1][0]).toBe('/api/ponto/folha/pag-1/retificacoes');
    });

    it('erro na folha: mensagem do backend (campo error tem precedência) e nenhuma 2ª chamada', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => ({ error: { error: 'Folha de outro usuário' } })));
      comp.ngOnInit();
      expect(comp.erro()).toBe('Folha de outro usuário');
      expect(comp.dados()).toBeNull();
      expect(comp.loading()).toBe(false);
      expect(apiGet).toHaveBeenCalledTimes(1); // não encadeia /retificacoes
    });

    it('erro sem corpo: cai no fallback', () => {
      const comp = criar();
      apiGet.mockReturnValue(throwError(() => new Error('rede')));
      comp.ngOnInit();
      expect(comp.erro()).toBe('Não foi possível carregar a folha.');
    });

    it('folha sem linhas: lista vazia (sem estourar)', () => {
      const comp = criar();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados') ? of({ data: { ...FOLHA, linhas: undefined } }) : of({ data: {} }),
      );
      comp.ngOnInit();
      expect(comp.linhas()).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // carregarRetificacoes — dias já retificados + prazo (vêm do backend)
  // ═══════════════════════════════════════════════════════════════════
  describe('carregarRetificacoes', () => {
    it('marca ja_retificado nos dias que casam com a data ISO da retificação', () => {
      respostaRetificacoes({
        limite_fmt: '17/07/2026',
        prazo_expirado: false,
        retificacoes: [{ data: '2026-07-08' }],
      });
      const comp = criarCarregado();
      expect(comp.linhas().map(l => l.ja_retificado)).toEqual([false, false, true]);
      expect(comp.limiteFmt()).toBe('17/07/2026');
      expect(comp.prazoExpirado()).toBe(false);
    });

    it('sem retificações: nenhum dia marcado', () => {
      const comp = criarCarregado();
      expect(comp.linhas().every(l => l.ja_retificado === false)).toBe(true);
      expect(comp.limiteFmt()).toBeNull();
    });

    it('a falha NÃO é mais silenciosa: caixa de erro própria e envio BLOQUEADO (fail-closed)', () => {
      // Antes: o `error` era um bloco vazio. Num 500/timeout a folha ficava na tela com todos os dias
      // livres e sem prazo (fail-open); o usuário abria um dia que JÁ retificara, enviava, e levava o
      // 400 "O dia … já foi retificado" sem ver retificação nenhuma — e, como o lote é tudo-ou-nada,
      // o dia novo enviado junto TAMBÉM não gravava.
      retificacoesFalham();
      const comp = criarCarregado();

      expect(comp.linhas()).toHaveLength(3);              // a folha continua visível (ela carregou)
      expect(comp.erroRetificacoes()).toContain('Não foi possível verificar quais dias você já retificou');
      expect(comp.retificacoesCarregadas()).toBe(false);  // ← o que bloqueia o Salvar
      expect(comp.erro()).toBe('');                       // canal do formulário intocado (não se misturam)

      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(apiPost).not.toHaveBeenCalled();             // preencher e mandar salvar não passa
      expect(comp.erro()).toContain('recarregue antes de enviar');
    });

    it('o retry recarrega, aplica marcação/prazo e DESTRAVA o envio', () => {
      retificacoesFalham();
      const comp = criarCarregado();
      expect(comp.retificacoesCarregadas()).toBe(false);

      respostaRetificacoes({ limite_fmt: '17/07/2026', prazo_expirado: false, retificacoes: [{ data: '2026-07-08' }] });
      comp.recarregarRetificacoes();

      expect(comp.erroRetificacoes()).toBe('');
      expect(comp.retificacoesCarregadas()).toBe(true);
      expect(comp.limiteFmt()).toBe('17/07/2026');
      expect(comp.linhas().map(l => l.ja_retificado)).toEqual([false, false, true]);

      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();
      expect(apiPost).toHaveBeenCalledTimes(1);           // o envio voltou a funcionar
    });

    it('o re-sync que falha depois de um lote recusado BLOQUEIA o Salvar de novo', () => {
      // O `error` do salvar() re-sincroniza os dias que porventura passaram. Se ESSE GET falhar, a tela
      // volta a não saber quem já foi retificado — e o próximo envio seria o mesmo tiro no escuro.
      const comp = criarCarregado();
      expect(comp.retificacoesCarregadas()).toBe(true);

      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      apiPost.mockReturnValue(throwError(() => ({ error: { error: 'O dia 06/07/2026 já foi retificado.' } })));
      retificacoesFalham();                                // o re-sync vai falhar

      comp.salvar();

      expect(comp.erro()).toContain('O dia 06/07/2026 já foi retificado.');   // o motivo do backend fica
      expect(comp.retificacoesCarregadas()).toBe(false);                       // e o envio trava
      expect(comp.erroRetificacoes()).not.toBe('');

      apiPost.mockClear();
      comp.salvar();
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('um erro ATRASADO não re-bloqueia o envio que um retry bem-sucedido já destravou', () => {
      // Dois cliques no "Tentar novamente": a 1ª carga falha DEPOIS de a 2ª ter sucedido. Sem o token
      // de recência, o erro velho voltaria a esconder o botão Salvar de uma tela já sincronizada.
      const primeira = new Subject<any>();
      const segunda = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados') ? of({ data: structuredClone(FOLHA) }) : primeira,
      );
      const comp = criarCarregado();          // 1ª carga das retificações: em voo

      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados') ? of({ data: structuredClone(FOLHA) }) : segunda,
      );
      comp.recarregarRetificacoes();          // 2ª carga: em voo

      segunda.next({ data: { limite_fmt: '17/07/2026', prazo_expirado: false, retificacoes: [] } });
      expect(comp.retificacoesCarregadas()).toBe(true);

      primeira.error(new Error('500'));       // a falha da carga VELHA chega atrasada

      expect(comp.retificacoesCarregadas()).toBe(true);   // e é ignorada
      expect(comp.erroRetificacoes()).toBe('');
    });

    it('a falha da carga da FOLHA (a outra) continua no canal do formulário — nenhum canal foi trocado', () => {
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados') ? throwError(() => ({ error: { error: 'Folha não encontrada.' } })) : of({ data: {} }),
      );
      const comp = criarCarregado();

      expect(comp.erro()).toBe('Folha não encontrada.');
      expect(comp.erroRetificacoes()).toBe('');   // nem chegou a pedir as retificações
      expect(comp.dados()).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — o usuário VÊ a caixa com retry, e o Salvar não está lá
  // ═══════════════════════════════════════════════════════════════════
  describe('render: caixa de erro com retry e Salvar bloqueado', () => {
    async function renderizarComDiaAberto(): Promise<ComponentFixture<PontoRetificarComponent>> {
      const fixture = TestBed.createComponent(PontoRetificarComponent);
      fixture.detectChanges();                     // ngOnInit + render (respostas síncronas)
      preencher(fixture.componentInstance, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      fixture.detectChanges();
      await fixture.whenStable();                  // bindings do template (NgModel do textarea)
      fixture.detectChanges();
      return fixture;
    }

    it('com a carga das retificações falhando: caixa role=alert com retry, e NENHUM botão Salvar', async () => {
      retificacoesFalham();
      const fixture = await renderizarComDiaAberto();

      const caixa = fixture.debugElement.query(By.css('app-erro-carga [role="alert"], app-erro-carga.erro-carga, .erro-carga'));
      expect(caixa).not.toBeNull();
      expect(caixa!.nativeElement.textContent).toContain('Enviar agora poderia derrubar o lote inteiro');
      expect(fixture.debugElement.query(By.css('button.salvar-top'))).toBeNull();   // sem Salvar
    });

    it('o clique em "Tentar novamente" recarrega e o botão Salvar VOLTA à tela', async () => {
      retificacoesFalham();
      const fixture = await renderizarComDiaAberto();

      respostaRetificacoes({ limite_fmt: '17/07/2026', prazo_expirado: false, retificacoes: [] });
      const btnRetry = fixture.debugElement
        .queryAll(By.css('.erro-carga button'))
        .find(b => (b.nativeElement as HTMLButtonElement).textContent?.includes('Tentar novamente'))!;
      btnRetry.nativeElement.click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.erro-carga'))).toBeNull();
      const salvar = fixture.debugElement.query(By.css('button.salvar-top'));
      expect(salvar).not.toBeNull();
      expect((salvar!.nativeElement as HTMLButtonElement).disabled).toBe(false);
    });

    it('durante a recarga a tela NÃO fica muda: sai a caixa, entra o "Verificando..." (e o Salvar segue fora)', async () => {
      // A caixa (que contém o botão de retry) sumia no clique e NADA a substituía —
      // num GET lento o usuário ficava sem Salvar, sem caixa e sem pista.
      retificacoesFalham();
      const fixture = await renderizarComDiaAberto();

      const emVoo = new Subject<any>();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados') ? of({ data: structuredClone(FOLHA) }) : emVoo,
      );
      (fixture.debugElement.query(By.css('.erro-carga button')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.erro-carga'))).toBeNull();
      expect(fixture.nativeElement.textContent).toContain('Verificando os dias já retificados');
      expect(fixture.debugElement.query(By.css('button.salvar-top'))).toBeNull();   // ainda bloqueado

      emVoo.next({ data: { limite_fmt: '17/07/2026', prazo_expirado: false, retificacoes: [] } });
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).not.toContain('Verificando os dias já retificados');
      expect(fixture.debugElement.query(By.css('button.salvar-top'))).not.toBeNull();
    });

    it('carga bem-sucedida (o caminho normal): sem caixa de erro e com o Salvar disponível', async () => {
      const fixture = await renderizarComDiaAberto();
      expect(fixture.debugElement.query(By.css('.erro-carga'))).toBeNull();
      expect(fixture.debugElement.query(By.css('button.salvar-top'))).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Prazo de retificação — 5 dias após a publicação (calculado no BACKEND)
  // Datas congeladas distintas, derivadas do mesmo instante
  // ═══════════════════════════════════════════════════════════════════
  describe('prazo de retificação (relógio congelado dentro e fora dos 5 dias)', () => {
    const PUBLICADO = new Date('2026-07-10T09:00:00-03:00'); // folha publicada em 10/07
    const LIMITE = '15/07/2026'; // publicação + 5 dias (regra do RetificacaoService)

    it('DENTRO do prazo (D+2): exibe o dia-limite e o submit passa', () => {
      vi.setSystemTime(new Date(PUBLICADO.getTime() + 2 * 24 * 3600_000)); // 12/07 — antes do limite
      respostaRetificacoes({ limite_fmt: LIMITE, prazo_expirado: false, retificacoes: [] });
      const comp = criarCarregado();

      expect(comp.limiteFmt()).toBe(LIMITE);
      expect(comp.prazoExpirado()).toBe(false);

      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(comp.erro()).toBe('');
      expect(comp.enviado()).toBe(true);
    });

    it('FORA do prazo (D+7): submit barrado, nenhum POST', () => {
      vi.setSystemTime(new Date(PUBLICADO.getTime() + 7 * 24 * 3600_000)); // 17/07 — depois do limite
      respostaRetificacoes({ limite_fmt: LIMITE, prazo_expirado: true, retificacoes: [] });
      const comp = criarCarregado();

      expect(comp.prazoExpirado()).toBe(true);

      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(comp.erro()).toBe('Prazo de retificação encerrado.');
      expect(apiPost).not.toHaveBeenCalled();
      expect(comp.enviado()).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // toggle / selecionadas — abertura e limpeza da área do dia
  // ═══════════════════════════════════════════════════════════════════
  describe('toggle', () => {
    it('"+" abre o dia e re-emite o signal com nova referência', () => {
      const comp = criarCarregado();
      const antes = comp.linhas();
      comp.toggle(comp.linhas()[0]);
      expect(comp.linhas()[0].aberto).toBe(true);
      expect(comp.linhas()).not.toBe(antes);
    });

    it('"−" fecha e DESCARTA o que foi digitado (horas e observações)', () => {
      const comp = criarCarregado();
      const l = preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00', observacoes: 'esqueci de bater' });
      comp.toggle(l);
      expect(l.aberto).toBe(false);
      expect([l.r_ent1, l.r_sai1, l.r_ent2, l.r_sai2]).toEqual(['', '', '', '']);
      expect(l.observacoes).toBe('');
    });

    it('selecionadas() traz só os dias abertos, em ordem cronológica', () => {
      const comp = criarCarregado();
      comp.toggle(comp.linhas()[2]);
      comp.toggle(comp.linhas()[0]); // aberto depois, mas é o dia anterior
      expect(comp.selecionadas().map(l => l.dia)).toEqual(['06/07/26 - seg', '08/07/26 - qua']);
    });

    it('sem nenhum dia aberto, selecionadas() é vazia', () => {
      const comp = criarCarregado();
      expect(comp.selecionadas()).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Helpers de exibição — isStatus / tipoLabel / diaParaISO
  // ═══════════════════════════════════════════════════════════════════
  describe('helpers', () => {
    it('isStatus: dia com letras em ENT.1 é status; horário e vazio não são', () => {
      const comp = criarCarregado();
      const [seg, ter] = comp.linhas();
      expect(comp.isStatus(ter)).toBe(true); // "Feriado"
      expect(comp.isStatus(seg)).toBe(false); // "08:00"
      expect(comp.isStatus({ ...seg, ent1: '' })).toBe(false);
      expect(comp.isStatus({ ...seg, ent1: 'FÉRIAS' })).toBe(true); // acentuado (À-ÿ)
    });

    it('tipoLabel: MENSAL → "mensal"; qualquer outro → "semanal"', () => {
      const comp = criarCarregado();
      expect(comp.tipoLabel()).toBe('semanal'); // a folha da fixture é SEMANAL
      comp.dados.set({ ...comp.dados()!, tipo: 'MENSAL' });
      expect(comp.tipoLabel()).toBe('mensal');
      comp.dados.set(null);
      expect(comp.tipoLabel()).toBe('semanal'); // sem folha também cai no "semanal"
    });

    it('diaParaISO: "dd/mm/aa - diasem" vira ISO; formato inesperado vira ""', () => {
      const comp = criarCarregado();
      const iso = (d: string) => (comp as any).diaParaISO(d);
      expect(iso('06/07/26 - seg')).toBe('2026-07-06');
      expect(iso('31/12/26')).toBe('2026-12-31');
      expect(iso('6/7/26 - seg')).toBe(''); // sem zero à esquerda não casa
      expect(iso('')).toBe('');
    });

    it('voltarLink é sempre /ponto (inclusive para o admin com folha)', () => {
      expect(criar().voltarLink).toBe('/ponto');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // salvar — validação (HORA_RE + regra de pares Q32)
  // ═══════════════════════════════════════════════════════════════════
  describe('salvar — validação', () => {
    it('horário fora de HH:MM válido: recusa nomeando o dia', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '25:00', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toBe('Horário inválido em 06/07/26 - seg (use HH:MM).');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('horário sem máscara completa ("8:0") também é recusado', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '8:0', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toContain('Horário inválido');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('minuto inválido (":60") é recusado', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:60', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toContain('Horário inválido');
    });

    it('par 1 incompleto (só entrada): recusa', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00' });
      comp.salvar();
      expect(comp.erro()).toBe('Preencha os pares Ent./Saí. completos em 06/07/26 - seg.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('par 2 incompleto (entrada sem saída): recusa', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00', r_ent2: '13:00' });
      comp.salvar();
      expect(comp.erro()).toContain('Preencha os pares');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('par 2 sem par 1 (Q32): recusa mesmo com o par 2 completo', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent2: '13:00', r_sai2: '17:00' });
      comp.salvar();
      expect(comp.erro()).toContain('Preencha os pares');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('nenhum dia aberto: recusa sem tocar a API', () => {
      const comp = criarCarregado();
      comp.salvar();
      expect(comp.erro()).toBe('Nenhum dia preenchido para retificar.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('só dias JÁ retificados abertos: recusa (eles são pulados no payload)', () => {
      respostaRetificacoes({ limite_fmt: null, prazo_expirado: false, retificacoes: [{ data: '2026-07-06' }] });
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' }); // dia já retificado
      comp.salvar();
      expect(comp.erro()).toBe('Nenhum dia preenchido para retificar.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('dia com rótulo fora do padrão dd/mm/aa: recusa com "Data inválida"', () => {
      const comp = criar();
      apiGet.mockImplementation((url: string) =>
        url.endsWith('/dados')
          ? of({ data: { ...FOLHA, linhas: [{ ...FOLHA.linhas[0], dia: 'Total do período' }] } })
          : of({ data: {} }),
      );
      comp.ngOnInit();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toBe('Data inválida em Total do período.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('a validação limpa o erro anterior a cada tentativa', () => {
      const comp = criarCarregado();
      comp.erro.set('sujeira de antes');
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toBe('');
    });

    it('a validação de um dia barra o LOTE inteiro: nenhum dia é enviado se um deles estiver torto', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' }); // este está bom…
      preencher(comp, 2, { r_ent1: '09:00' });                  // …e este, incompleto
      comp.salvar();
      expect(comp.erro()).toContain('08/07/26 - qua');
      expect(apiPost).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // salvar — submit (UM POST de LOTE, tudo-ou-nada no backend)
  // ═══════════════════════════════════════════════════════════════════
  describe('salvar — submit', () => {
    it('2 horários: um POST no endpoint de LOTE, com o dia dentro de "dias"', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00', observacoes: '  esqueci de bater  ' });
      comp.salvar();

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0][0]).toBe(URL_LOTE);
      expect(apiPost.mock.calls[0][1]).toEqual({
        dias: [{
          data: '2026-07-06',
          ent1: '08:00', sai1: '12:00',
          ent2: null, sai2: null,
          observacoes: 'esqueci de bater', // trim aplicado
        }],
      });
    });

    it('4 horários: envia os dois pares', () => {
      const comp = criarCarregado();
      preencher(comp, 2, { r_ent1: '08:00', r_sai1: '12:00', r_ent2: '13:00', r_sai2: '17:30' });
      comp.salvar();

      expect(diasEnviados()).toEqual([{
        data: '2026-07-08',
        ent1: '08:00', sai1: '12:00',
        ent2: '13:00', sai2: '17:30',
        observacoes: '',
      }]);
    });

    it('espaços em volta do horário são tolerados (trim antes da validação)', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: ' 08:00 ', r_sai1: '12:00 ' });
      comp.salvar();
      expect(diasEnviados()[0]).toMatchObject({ ent1: '08:00', sai1: '12:00' });
    });

    it('dois dias abertos: UM ÚNICO POST com os dois dias (em ordem cronológica)', () => {
      // Antes eram 2 POSTs paralelos (`forkJoin`), cada um numa transação própria: um falhar
      // deixava o outro gravado — e sem edição/exclusão na v1, isso era definitivo.
      const comp = criarCarregado();
      preencher(comp, 2, { r_ent1: '09:00', r_sai1: '15:00' });
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(apiPost.mock.calls[0][0]).toBe(URL_LOTE);
      expect(diasEnviados().map((d: any) => d.data)).toEqual(['2026-07-06', '2026-07-08']);
    });

    it('2º clique enquanto o lote está no ar NÃO dispara um 2º POST (guard do salvando)', () => {
      const comp = criarCarregado();
      apiPost.mockReturnValue(new Subject<any>()); // POST no ar: nunca responde
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });

      comp.salvar();
      comp.salvar(); // 2º clique antes de a resposta chegar

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(comp.salvando()).toBe(true);
    });

    it('a trava é liberada quando o lote é recusado: o usuário conserta e reenvia', () => {
      const comp = criarCarregado();
      apiPost.mockReturnValue(throwError(() => ({ error: { error: 'O dia 08/07/2026 já foi retificado.' } })));
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });

      comp.salvar();
      expect(comp.salvando()).toBe(false);

      apiPost.mockReturnValue(of({ ok: true }));
      comp.salvar();
      expect(apiPost).toHaveBeenCalledTimes(2);
      expect(comp.enviado()).toBe(true);
    });

    it('sucesso: marca "enviada" e volta para /ponto depois de 1,4 s', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(comp.enviado()).toBe(true);
      expect(navigateByUrl).not.toHaveBeenCalled(); // ainda não — o aviso fica 1,4 s na tela
      vi.advanceTimersByTime(1400); // ⚠️ drenar timer acorda a CD (ver cabeçalho): só o efeito do timer é assertado
      expect(navigateByUrl).toHaveBeenCalledWith('/ponto');
    });

    it('sair da tela dentro da janela de 1,4 s CANCELA a navegação (o timer morre no destroy)', () => {
      // Antes, o handle do setTimeout não era guardado e o componente não implementava OnDestroy:
      // o timer disparava mesmo fora da tela e arrancava o usuário da rota nova de volta para /ponto.
      const fixture = TestBed.createComponent(PontoRetificarComponent);
      const comp = fixture.componentInstance;
      comp.ngOnInit();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      fixture.destroy(); // o usuário navegou para outra rota

      vi.advanceTimersByTime(1400);
      expect(navigateByUrl).not.toHaveBeenCalled();
    });

    it('lote recusado: a tela diz que NENHUM dia foi gravado (não há mais falha parcial) e preserva o motivo, que nomeia o dia', () => {
      // Inversão da caracterização: antes, um POST podia gravar e o outro falhar — a tela dizia
      // "não foi possível salvar" enquanto dias já estavam no banco, e a 2ª tentativa batia na UK.
      // Agora é um lote só: se ele é recusado, nada foi gravado — e a tela afirma isso.
      const comp = criarCarregado();
      apiPost.mockReturnValue(throwError(() => ({ error: { message: 'O dia 08/07/2026 já foi retificado.' } })));
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();

      expect(comp.erro()).toBe(
        'Não foi possível salvar a retificação — nenhum dia foi gravado. (O dia 08/07/2026 já foi retificado.)');
      expect(comp.enviado()).toBe(false);
      expect(navigateByUrl).not.toHaveBeenCalled(); // o setTimeout de saída nem chega a ser agendado
      // re-sync: 2º GET /retificacoes (dias que porventura passaram) — sem drenar timers,
      // senão a CD zoneless acorda e roda o ngOnInit de novo (ver cabeçalho: ⚠️ timers).
      const retifs = apiGet.mock.calls.filter(c => String(c[0]).endsWith('/retificacoes'));
      expect(retifs).toHaveLength(2);
    });

    it('erro sem corpo (500 mudo): a guia da tela sozinha — o usuário fica sabendo que nada foi gravado', () => {
      const comp = criarCarregado();
      apiPost.mockReturnValue(throwError(() => new Error('rede')));
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      comp.salvar();
      expect(comp.erro()).toBe('Não foi possível salvar a retificação — nenhum dia foi gravado.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // RENDER — a trava de duplo clique no DOM (o guard sozinho é invisível)
  // ═══════════════════════════════════════════════════════════════════
  describe('render do botão Salvar (trava de duplo clique)', () => {
    // Exceção deliberada ao GATE "só lógica não-visual": o
    // `[disabled]` de um controle DESTRUTIVO vive só no template. Sem este teste, apagar o binding
    // deixaria a suíte verde e devolveria o defeito na pior forma — o usuário clicando duas vezes
    // num botão que parece ativo (o 2º lote morreria na UK, com o error-box junto do ok-box).

    async function renderizarComDiaAberto(): Promise<ComponentFixture<PontoRetificarComponent>> {
      const fixture = TestBed.createComponent(PontoRetificarComponent);
      fixture.detectChanges();                       // ngOnInit + render (as respostas são síncronas)
      const comp = fixture.componentInstance;
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });
      fixture.detectChanges();
      await fixture.whenStable();                    // bindings do template (promises não são falsificadas)
      fixture.detectChanges();
      return fixture;
    }

    const botaoSalvar = (fixture: ComponentFixture<PontoRetificarComponent>) =>
      fixture.debugElement.query(By.css('button.salvar-top')).nativeElement as HTMLButtonElement;

    it('com um dia preenchido, o botão está ativo e diz "Salvar"', async () => {
      const fixture = await renderizarComDiaAberto();
      const botao = botaoSalvar(fixture);
      expect(botao.disabled).toBe(false);
      expect(botao.textContent?.trim()).toBe('Salvar');
    });

    it('durante o voo do lote o botão fica DESABILITADO — dois cliques REAIS disparam um POST só', async () => {
      apiPost.mockReturnValue(new Subject<any>()); // o lote fica no ar
      const fixture = await renderizarComDiaAberto();
      const botao = botaoSalvar(fixture);

      botao.click();                                // 1º clique — dispara o lote
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(botao.disabled).toBe(true);
      expect(botao.textContent?.trim()).toBe('Salvando...');

      botao.click();                                // 2º clique real, com o lote ainda no ar
      expect(apiPost).toHaveBeenCalledTimes(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // par mínimo de horários e teto da observação
  // ═══════════════════════════════════════════════════════════════════
  describe('retificação sem horários não existe mais', () => {
    it('dia aberto e INTOCADO (o "+" por engano) fica FORA do lote — sem erro, sem POST', () => {
      // A retificação vazia vencia a precedência na grade e na planilha da chefia: o dia que dizia
      // "Banco de horas"/"Férias" virava célula vazia e a contagem de folgas caía 1 — sem desfazer.
      const comp = criarCarregado();
      preencher(comp, 1, {});                                   // abriu e não digitou nada
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' }); // o dia de verdade

      comp.salvar();

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(diasEnviados()).toEqual([{
        data: '2026-07-06',
        ent1: '08:00', sai1: '12:00', ent2: null, sai2: null,
        observacoes: '',
      }]);
      expect(comp.erro()).toBe('');
    });

    it('nenhum horário em NENHUM dia aberto: recusa sem tocar a API', () => {
      const comp = criarCarregado();
      preencher(comp, 0, {});
      comp.salvar();
      expect(comp.erro()).toBe('Nenhum dia preenchido para retificar.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('dia com OBSERVAÇÃO e nenhum horário BLOQUEIA o envio, nomeando o dia (nunca descarte silencioso)', () => {
      // O usuário quis retificar — descartar o dia calado apagaria o que ele escreveu; enviá-lo é o
      // bug. A saída honesta é a recusa visível, no idioma da tela.
      const comp = criarCarregado();
      preencher(comp, 0, { observacoes: 'estava em reunião externa' });

      comp.salvar();

      expect(comp.erro()).toBe(
        'Informe ao menos o par Ent. 1 / Saí. 1 em 06/07/26 - seg: não é possível retificar um dia sem horários.');
      expect(apiPost).not.toHaveBeenCalled();
    });

    it('um dia sem horários derruba o LOTE inteiro no front: nem o dia válido é enviado', () => {
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });   // válido
      preencher(comp, 2, { observacoes: 'sem horários' });        // ✗

      comp.salvar();

      expect(comp.erro()).toContain('08/07/26 - qua');
      expect(apiPost).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // o dia retificado por OUTRA folha da mesma pessoa chega travado
  // ═══════════════════════════════════════════════════════════════════
  describe('dia retificado por outra folha vem marcado e travado (render)', () => {
    // Semanais cumulativas (01–05, 01–12…) dão à mesma pessoa DUAS folhas publicadas cobrindo o
    // mesmo dia. A listagem do backend filtrava por PAGINA_ID enquanto a gravação valida pela UK
    // (pessoa+dia): na folha B o dia já retificado pela folha A vinha LIVRE e habilitado, e o envio
    // levava 400 "O dia … já foi retificado" sem NENHUMA retificação visível na tela — sem edição
    // nem exclusão na v1, o dia ficava congelado sem explicação.
    // Corrigida a chave de leitura, o front (que só enxerga `data`, nunca a proveniência) trava o
    // dia sozinho. É o que estes testes provam — no DOM das DUAS vistas, e no corpo do POST.

    /** A resposta da listagem com o dia 08/07 já retificado — para o front, uma data como outra qualquer. */
    function comDiaRetificadoPorOutraFolha() {
      respostaRetificacoes({
        limite_fmt: '17/07/2026',
        prazo_expirado: false,
        retificacoes: [
          { data: '2026-07-08', ent1: '08:00', sai1: '12:00', ent2: null, sai2: null, observacoes: null },
        ],
      });
    }

    function renderizar(): ComponentFixture<PontoRetificarComponent> {
      comDiaRetificadoPorOutraFolha();
      const fixture = TestBed.createComponent(PontoRetificarComponent);
      fixture.detectChanges();   // ngOnInit + render (as respostas do mock são síncronas)
      return fixture;
    }

    it('desktop: o dia traz o selo "✓ Retificado" e NÃO oferece o "+" (os campos ficam inalcançáveis)', () => {
      const fixture = renderizar();
      const linhas = fixture.debugElement.queryAll(By.css('.vista-desktop tbody tr'));
      expect(linhas).toHaveLength(3);   // nenhum dia aberto → uma linha por dia

      const retificado = linhas[2];     // 08/07/26 — o dia que veio da outra folha
      expect(retificado.query(By.css('.badge-retif'))?.nativeElement.textContent).toContain('Retificado');
      expect(retificado.query(By.css('button.btn-pm'))).toBeNull();

      const livre = linhas[0];          // os demais dias seguem retificáveis
      expect(livre.query(By.css('.badge-retif'))).toBeNull();
      expect(livre.query(By.css('button.btn-pm'))).not.toBeNull();
    });

    it('celular: o mesmo dia traz o selo e perde o "+" no card (as duas vistas contam — o usuário do ponto usa o celular)', () => {
      const fixture = renderizar();
      const cards = fixture.debugElement.queryAll(By.css('.vista-mobile .dia-card'));
      expect(cards).toHaveLength(3);

      expect(cards[2].query(By.css('.badge-retif'))).not.toBeNull();
      expect(cards[2].query(By.css('button.btn-pm'))).toBeNull();
      expect(cards[0].query(By.css('button.btn-pm'))).not.toBeNull();
    });

    it('o dia travado fica FORA do lote: o POST leva só o dia livre — o 400 misterioso não acontece mais', () => {
      comDiaRetificadoPorOutraFolha();
      const comp = criarCarregado();

      // forçando a abertura do dia travado (a UI não oferece o "+", mas o filtro do payload é o que
      // impede o dia de chegar ao backend e derrubar o LOTE INTEIRO na UK — tudo-ou-nada)
      preencher(comp, 2, { r_ent1: '09:00', r_sai1: '15:00' });
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00' });

      comp.salvar();

      expect(apiPost).toHaveBeenCalledTimes(1);
      expect(diasEnviados()).toEqual([{
        data: '2026-07-06',
        ent1: '08:00', sai1: '12:00', ent2: null, sai2: null,
        observacoes: '',
      }]);
      expect(comp.erro()).toBe('');
    });
  });

  describe('teto de 300 caracteres nas observações (render)', () => {
    it('os textareas de observação (desktop e celular) têm maxlength="300"', () => {
      // OBSERVACOES é VARCHAR2(2000) em BYTES: sem o teto, ~1.100 caracteres acentuados estouravam a
      // coluna e o ORA-12899 era respondido como "o dia já foi retificado" — o usuário ia embora
      // achando que gravou, com o prazo de 5 dias correndo.
      const fixture = TestBed.createComponent(PontoRetificarComponent);
      fixture.detectChanges();                  // ngOnInit + render (as respostas são síncronas)
      const comp = fixture.componentInstance;
      comp.toggle(comp.linhas()[0]);            // a área de retificação só existe com o dia aberto
      fixture.detectChanges();

      const textareas = fixture.debugElement.queryAll(By.css('.retif-area textarea'));
      expect(textareas.length).toBe(2);   // a tabela do desktop e o card do celular
      for (const ta of textareas) {
        expect((ta.nativeElement as HTMLTextAreaElement).getAttribute('maxlength')).toBe('300');
      }
    });

    it('a recusa de 400 do backend (shape real {ok:false, error}) chega à tela nomeando o campo', () => {
      apiPost.mockReturnValue(throwError(() => ({
        status: 400,
        error: { ok: false, error: 'A observação do dia 06/07/2026 excede o máximo de 300 caracteres (foram 420).' },
      })));
      const comp = criarCarregado();
      preencher(comp, 0, { r_ent1: '08:00', r_sai1: '12:00', observacoes: 'x'.repeat(420) });

      comp.salvar();

      expect(comp.erro()).toContain('nenhum dia foi gravado');     // a guia da tela
      expect(comp.erro()).toContain('máximo de 300 caracteres');   // o motivo do backend
      expect(comp.enviado()).toBe(false);
      expect(comp.salvando()).toBe(false);                         // a trava é liberada: dá para consertar
    });
  });
});

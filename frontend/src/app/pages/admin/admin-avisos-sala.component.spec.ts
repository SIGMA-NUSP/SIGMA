import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { DebugElement, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { ToastService } from '../../shared/components/toast.component';
import { AdminAvisosSalaComponent } from './admin-avisos-sala.component';

/**
 * Testes de RENDER da listagem "Avisos Cadastrados": caixa de erro presente com a
 * mensagem certa, retry que re-pede o endpoint, estado vazio legítimo intacto, rodapé
 * coerente, e falha da LISTA não derruba o FORMULÁRIO de cadastro. Também cobre o lock
 * fail-closed de "1 aviso ativo por sala" (cadastro bloqueado enquanto a carga de salas
 * não tiver sucedido).
 * TestBed com ApiService mockado por useValue (getList roteado POR ENDPOINT); Router
 * real via provideRouter([]) — RouterLink não aceita useValue cru; sem fake timers.
 * O template tem ngModel → todo render passa por `await fixture.whenStable()`.
 */

// ── Endpoints da tela ──
const EP_LISTA = '/api/admin/avisos/list';
const EP_SALAS_OCUPADAS = '/api/admin/avisos/salas-ocupadas';

const META = { page: 1, limit: 10, total: 2, pages: 1 };

/** Linha de `GET /api/admin/avisos/list` (o `tipo` já vem como label do backend). */
const AVISO_ATIVO = {
  id: 'av-1', numero: 42, tipo: 'Verificação', criado_em: '2026-07-10',
  criado_por: 'Ana Prado', expira_em: '2026-07-20', status: 'Ativo' as const, permanente: 0,
};
const AVISO_DESATIVADO = {
  id: 'av-2', numero: 41, tipo: 'Verificação', criado_em: '2026-07-01',
  criado_por: 'João Lima', expira_em: null, status: 'Desativado' as const, permanente: 1,
};

/** 500 REAL do backend (`GlobalExceptionHandler`): corpo genérico → a guia da tela vem na frente. */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };
/** Texto que o admin efetivamente lê na caixa: guia da tela + detalhe do backend entre parênteses. */
const MSG_500 = 'Não foi possível carregar a lista. (Erro interno do servidor)';

const VAZIO = 'Nenhum aviso cadastrado.';

describe('AdminAvisosSalaComponent — canal de erro da listagem', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiPatch: ReturnType<typeof vi.fn>;
  let loadSalas: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;

  /** Resposta corrente da listagem — `falhar`/`vazio` trocam o cenário sem tocar no resto da tela. */
  let respostas: Record<string, () => Observable<any>>;
  /** Resposta corrente do GET .../salas-ocupadas — os testes do fail-closed a trocam. */
  let respostaSalasOcupadas: () => Observable<any>;
  /** Cache de salas do lookup (o multi-select) — signal trocável por teste. */
  let salasLookup: ReturnType<typeof signal<any[]>>;

  const ok = (...linhas: unknown[]) => () => of({ data: linhas, meta: { ...META, total: linhas.length } });
  const vazio = () => () => of({ data: [], meta: { ...META, total: 0, pages: 0 } });
  const falha = (err: unknown = ERRO_500) => () => throwError(() => err);

  beforeEach(async () => {
    respostas = { [EP_LISTA]: ok(AVISO_ATIVO, AVISO_DESATIVADO) };
    apiGetList = vi.fn((endpoint: string) => respostas[endpoint]());
    // `ngOnInit` também chama `loadSalasOcupadas()` (GET .../salas-ocupadas, que trava no
    // multi-select as salas com aviso ativo). O fail-closed exige a resposta trocável.
    respostaSalasOcupadas = () => of({ data: [] });
    apiGet = vi.fn((url: string) =>
      url === EP_SALAS_OCUPADAS ? respostaSalasOcupadas() : of({ data: [] }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));     // cadastro
    apiPatch = vi.fn().mockReturnValue(of({ ok: true }));    // desativar
    loadSalas = vi.fn();
    toastSuccess = vi.fn();
    toastError = vi.fn();
    salasLookup = signal<any[]>([]);

    await TestBed.configureTestingModule({
      imports: [AdminAvisosSalaComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink ("← Voltar") — ver divergência no cabeçalho
        { provide: ApiService, useValue: { getList: apiGetList, get: apiGet, post: apiPost, patch: apiPatch } },
        // Cache de salas VAZIO por padrão: é o estado da carga fria da tela (e o que dispara `loadSalas()`).
        { provide: LookupService, useValue: { salas: salasLookup, loadSalas } },
        { provide: ToastService, useValue: { success: toastSuccess, error: toastError } },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  /** Render completo: `detectChanges` roda o `ngOnInit` (lista + salas ocupadas, síncronas). */
  async function renderizar(): Promise<ComponentFixture<AdminAvisosSalaComponent>> {
    const fixture = TestBed.createComponent(AdminAvisosSalaComponent);
    await estabilizar(fixture);
    return fixture;
  }

  /** O template tem `ngModel` (mensagens, permanente, duração, busca) → o DOM só é confiável depois do `whenStable`. */
  async function estabilizar(fixture: ComponentFixture<AdminAvisosSalaComponent>): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const tabela = (f: ComponentFixture<AdminAvisosSalaComponent>) => f.debugElement.query(By.css('table.data-table'));
  const textoDaTabela = (f: ComponentFixture<AdminAvisosSalaComponent>) =>
    (tabela(f).query(By.css('tbody')).nativeElement as HTMLElement).textContent ?? '';

  /**
   * A caixa de erro de CARGA. ⚠️ Não dá para procurá-la por `.error-box`: o formulário de cadastro
   * usa a MESMA classe na sua caixa de validação (`errorMsg`). A busca é pela diretiva.
   */
  const caixa = (f: ComponentFixture<AdminAvisosSalaComponent>): DebugElement =>
    f.debugElement.query(By.directive(ErroCargaComponent));

  /** Clique REAL no "Tentar novamente" da caixa. */
  function clicarRetry(f: ComponentFixture<AdminAvisosSalaComponent>): void {
    (f.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
  }

  /** Quantas vezes a listagem foi PEDIDA (prova do retry). */
  const chamadas = (endpoint: string) => apiGetList.mock.calls.filter(c => c[0] === endpoint).length;

  // ═══════════════════════════════════════════════════════════════════
  // Carga inicial — o ponto de partida das demais provas
  // ═══════════════════════════════════════════════════════════════════
  describe('carga inicial', () => {
    it('pede a listagem, as salas ocupadas e os locais do lookup', async () => {
      await renderizar();
      expect(chamadas(EP_LISTA)).toBe(1);
      expect(apiGetList).toHaveBeenCalledWith(EP_LISTA,
        expect.objectContaining({ page: 1, limit: 10, sort: 'data', direction: 'desc' }));
      expect(apiGet).toHaveBeenCalledWith(EP_SALAS_OCUPADAS);
      expect(loadSalas).toHaveBeenCalledTimes(1);   // só com o cache do lookup vazio (guard do ngOnInit)
    });

    it('tudo OK: a tabela exibe os avisos cadastrados e nenhuma caixa de erro existe na tela', async () => {
      const fixture = await renderizar();
      expect(textoDaTabela(fixture)).toContain('Ana Prado');
      expect(textoDaTabela(fixture)).toContain('42');
      expect(textoDaTabela(fixture)).not.toContain(VAZIO);
      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Canal de erro — a falha de leitura deixa de se passar por "lista vazia"
  // ═══════════════════════════════════════════════════════════════════
  describe('falha na carga da listagem', () => {
    it('mostra a caixa app-erro-carga com role="alert" e a mensagem do canal, SEM "Nenhum aviso cadastrado."', async () => {
      // Sem o canal, este 500 virava a frase do vazio: o admin lê "nenhum aviso" numa tela em que a
      // ausência de aviso é o gatilho para CADASTRAR — e o cadastro esbarra depois no "1 ativo por
      // sala" do backend, ou pior: um aviso que devia ser desativado fica invisível e ativo.
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();

      const box = caixa(fixture);
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();   // anunciado ao leitor de tela
      expect(textoDaTabela(fixture)).toContain(MSG_500);
      expect(textoDaTabela(fixture)).not.toContain(VAZIO);
      expect(fixture.componentInstance.ctrl.rows()).toEqual([]);
    });

    it('a caixa vive DENTRO do <tbody> da listagem (é ali que a frase falsa aparecia)', async () => {
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();
      expect(tabela(fixture).query(By.css('tbody app-erro-carga'))).not.toBeNull();
    });

    it('"Tentar novamente" re-dispara a carga: 2º GET em /api/admin/avisos/list', async () => {
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();
      expect(chamadas(EP_LISTA)).toBe(1);

      clicarRetry(fixture);                 // clique REAL no botão do DOM
      await estabilizar(fixture);

      expect(chamadas(EP_LISTA)).toBe(2);   // o clique chegou ao ctrl.load()
    });

    it('retry com sucesso: a caixa some e os avisos aparecem', async () => {
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();

      respostas[EP_LISTA] = ok(AVISO_ATIVO);   // o backend voltou
      clicarRetry(fixture);
      await estabilizar(fixture);

      expect(caixa(fixture)).toBeNull();
      expect(fixture.componentInstance.ctrl.erro()).toBe('');
      expect(textoDaTabela(fixture)).toContain('Ana Prado');
      expect(textoDaTabela(fixture)).not.toContain(MSG_500);
    });

    it('erro sem corpo (backend fora do ar): a guia da tela sozinha, sem parênteses vazios', async () => {
      respostas[EP_LISTA] = falha({ status: 0 });
      const fixture = await renderizar();
      expect(caixa(fixture).componentInstance.mensagem()).toBe('Não foi possível carregar a lista.');
    });

    it('vazio LEGÍTIMO (200 com data:[]): a frase do vazio, SEM caixa de erro', async () => {
      // O outro lado da moeda: o canal não pode transformar ausência de dados em alarme falso —
      // "nenhum aviso cadastrado" continua sendo uma resposta válida do backend.
      respostas[EP_LISTA] = vazio();
      const fixture = await renderizar();

      expect(textoDaTabela(fixture)).toContain(VAZIO);
      expect(caixa(fixture)).toBeNull();
      expect(fixture.componentInstance.ctrl.erro()).toBe('');
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação some (não exibe o total anterior)', async () => {
      const fixture = await renderizar();
      expect(fixture.debugElement.query(By.css('.pag-info')).nativeElement.textContent).toContain('2 registros');

      respostas[EP_LISTA] = falha();
      fixture.componentInstance.ctrl.load();   // recarga (paginação, sort, filtro de coluna, busca)
      await estabilizar(fixture);

      expect(fixture.componentInstance.ctrl.meta()).toBeNull();
      expect(fixture.debugElement.query(By.css('.pag-info'))).toBeNull();   // "2 registros" não sobrevive à falha
      expect(caixa(fixture)).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Isolamento (só há 1 tabela): a falha da LISTA não leva junto o FORMULÁRIO
  // ═══════════════════════════════════════════════════════════════════
  describe('o formulário de cadastro sobrevive ao erro da listagem', () => {
    it('multi-select de locais, mensagens e o botão "Cadastrar Aviso" continuam na tela', async () => {
      // O canal de erro mora no <tbody>; a metade de CIMA da tela é a razão de o admin estar aqui.
      // Se a caixa tivesse sido posta em volta da seção (ou o `@if` colocado alto demais), a falha
      // de uma LEITURA impediria uma ESCRITA que o backend aceitaria sem problema.
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.query(By.css('app-multi-select-dropdown'))).not.toBeNull();
      expect(fixture.debugElement.queryAll(By.css('textarea'))).toHaveLength(1);
      const botao = fixture.debugElement.query(By.css('.btn-primary-custom')).nativeElement as HTMLButtonElement;
      expect(botao.textContent).toContain('Cadastrar Aviso');
      expect(botao.disabled).toBe(false);
      expect(fixture.debugElement.query(By.css('.search-input'))).not.toBeNull();   // e a busca da lista também
    });

    it('a caixa de erro da CARGA não se confunde com a caixa de validação do formulário', async () => {
      // As duas usam a classe `.error-box`. Com a lista falhando e o formulário íntegro, existe
      // exatamente UMA caixa na tela — a do canal. Se a do formulário tivesse sido acionada por
      // tabela, o admin veria um "erro de cadastro" que ninguém cometeu.
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.css('.error-box'))).toHaveLength(1);
      expect(fixture.debugElement.query(By.css('.error-box')).nativeElement.classList).toContain('erro-carga');
      expect(fixture.componentInstance.errorMsg()).toBe('');
    });

    it('com a lista em erro, o cadastro ainda é enviado — e o sucesso recarrega a lista (que volta a exibir as linhas)', async () => {
      respostas[EP_LISTA] = falha();
      const fixture = await renderizar();
      const comp = fixture.componentInstance;

      comp.selectedSalaIds = ['7'];              // o que o multi-select emitiria
      comp.mensagens = ['Verificar o microfone da bancada'];
      respostas[EP_LISTA] = ok(AVISO_ATIVO);     // o backend volta junto com o POST
      (fixture.debugElement.query(By.css('.btn-primary-custom')).nativeElement as HTMLButtonElement).click();
      await estabilizar(fixture);

      expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', expect.objectContaining({
        tipo: 'VERIFICACAO', alvo_tipo: 'SALA', sala_ids: [7],
        mensagens: ['Verificar o microfone da bancada'],
      }));
      expect(toastSuccess).toHaveBeenCalledWith('Aviso cadastrado com sucesso.');
      expect(chamadas(EP_LISTA)).toBe(2);        // a recarga do `next` do POST
      expect(caixa(fixture)).toBeNull();         // e o canal se limpa sozinho na carga bem-sucedida
      expect(textoDaTabela(fixture)).toContain('Ana Prado');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Lock "1 aviso ativo por sala" — FAIL-CLOSED
  //
  // `loadSalasOcupadas()` era assinado só com `next`: num 500, o mapa ficava `{}`, os rótulos
  // "— Cadastro nº X" sumiam e o multi-select mostrava TODAS as salas destravadas — o admin
  // selecionava uma ocupada e levava a recusa do backend sem entender. Agora a carga que não
  // SUCEDEU (em voo ou falhou) mostra erro com retry e BLOQUEIA o envio; preencher pode.
  // ═══════════════════════════════════════════════════════════════════
  describe('lock "1 aviso ativo por sala" — fail-closed', () => {
    const MSG_ERRO_LOCK_SALAS = 'Não foi possível verificar quais locais já têm aviso ativo. '
      + 'O cadastro fica bloqueado até recarregar — um local ocupado apareceria como livre. (Erro interno do servidor)';

    const botaoCadastrar = (f: ComponentFixture<AdminAvisosSalaComponent>) =>
      f.debugElement.query(By.css('.btn-primary-custom')).nativeElement as HTMLButtonElement;
    const chamadasOcupadas = () => apiGet.mock.calls.filter(c => c[0] === EP_SALAS_OCUPADAS).length;

    /** Formulário válido — para provar que é o GATE que segura o POST, não a validação. */
    function preencherFormValido(f: ComponentFixture<AdminAvisosSalaComponent>): void {
      f.componentInstance.selectedSalaIds = ['7'];
      f.componentInstance.mensagens = ['Verificar o microfone da bancada'];
    }

    it('falha das salas-ocupadas: caixa com retry, botão DESABILITADO e onSubmit() não emite POST nem forçado', async () => {
      respostaSalasOcupadas = () => throwError(() => ERRO_500);
      const fixture = await renderizar();

      const box = caixa(fixture);
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_ERRO_LOCK_SALAS);
      expect(botaoCadastrar(fixture).disabled).toBe(true);         // camada de UI

      preencherFormValido(fixture);
      fixture.componentInstance.onSubmit();                        // forçado, por fora do botão
      expect(apiPost).not.toHaveBeenCalled();                      // guard: a trava real
      expect(fixture.componentInstance.saving()).toBe(false);
    });

    it('em VOO (antes da 1ª resposta) o envio já está bloqueado', async () => {
      const emVoo = new Subject<any>();
      respostaSalasOcupadas = () => emVoo;
      const fixture = await renderizar();

      expect(botaoCadastrar(fixture).disabled).toBe(true);
      preencherFormValido(fixture);
      fixture.componentInstance.onSubmit();
      expect(apiPost).not.toHaveBeenCalled();

      emVoo.next({ data: [] });                                    // a carga sucede
      emVoo.complete();
      await estabilizar(fixture);
      expect(botaoCadastrar(fixture).disabled).toBe(false);        // destravou
    });

    it('retry com sucesso: destrava o envio e as salas ocupadas voltam rotuladas/travadas', async () => {
      salasLookup.set([{ id: 7, nome: 'Plenário 2' }, { id: 8, nome: 'Sala 5' }]);
      respostaSalasOcupadas = () => throwError(() => ERRO_500);
      const fixture = await renderizar();
      expect(chamadasOcupadas()).toBe(1);

      respostaSalasOcupadas = () => of({ data: [{ sala_id: 7, numero: 42 }] });
      (caixa(fixture).query(By.css('button')).nativeElement as HTMLButtonElement).click();
      await estabilizar(fixture);

      expect(chamadasOcupadas()).toBe(2);
      expect(caixa(fixture)).toBeNull();
      expect(botaoCadastrar(fixture).disabled).toBe(false);
      const comp = fixture.componentInstance;
      expect(comp.lockedSalaIds()).toEqual(['7']);                 // a sala ocupada volta travada
      expect(comp.salaOptions().map(o => o.label)).toContain('Plenário 2 — Cadastro nº 42');
      expect(comp.salaOptions().map(o => o.label)).toContain('Sala 5');   // e a livre, sem rótulo
    });

    it('regressão — fluxo feliz intacto: POST com o shape atual, reset do formulário e recarga do lock e da lista', async () => {
      const fixture = await renderizar();
      const comp = fixture.componentInstance;
      expect(botaoCadastrar(fixture).disabled).toBe(false);        // carga OK → destravado

      preencherFormValido(fixture);
      botaoCadastrar(fixture).click();
      await estabilizar(fixture);

      expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', expect.objectContaining({
        tipo: 'VERIFICACAO', alvo_tipo: 'SALA', sala_ids: [7],
        mensagens: ['Verificar o microfone da bancada'],
      }));
      expect(comp.selectedSalaIds).toEqual([]);                    // resetForm
      expect(comp.mensagens).toEqual(['']);
      expect(chamadasOcupadas()).toBe(2);                          // o next do POST recarrega o lock
      expect(chamadas(EP_LISTA)).toBe(2);                          // e a listagem
      expect(toastSuccess).toHaveBeenCalledWith('Aviso cadastrado com sucesso.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Cards de seleção (§3) — 4 painéis, 1 ativo por vez, reclique oculta.
  // Verificação abre por padrão: os testes de cima (que veem o form sem clicar) dependem disso.
  // ═══════════════════════════════════════════════════════════════════
  describe('cards de seleção — 4 painéis e alternância', () => {
    const cardBtns = (f: ComponentFixture<AdminAvisosSalaComponent>) =>
      f.debugElement.queryAll(By.css('.cards-aviso .card-pick')).map(d => d.nativeElement as HTMLButtonElement);

    it('renderiza os 4 cards; Verificação começa ativo com o form inline visível e nenhum sub-painel', async () => {
      const fixture = await renderizar();
      expect(cardBtns(fixture).map(b => b.textContent?.trim())).toEqual(['Verificação', 'Escala', 'Agenda', 'Pessoal']);
      expect(fixture.componentInstance.activeCard()).toBe('verificacao');
      expect(cardBtns(fixture)[0].classList.contains('active')).toBe(true);
      expect(fixture.debugElement.query(By.css('app-multi-select-dropdown'))).not.toBeNull();  // salas (Verificação)
      expect(fixture.debugElement.query(By.css('app-aviso-escala-form'))).toBeNull();
      expect(fixture.debugElement.query(By.css('app-aviso-agenda-form'))).toBeNull();
      expect(fixture.debugElement.query(By.css('app-aviso-pessoal-form'))).toBeNull();
    });

    it('cada card ativa o seu painel; a tabela "Avisos Cadastrados" continua na tela', async () => {
      const fixture = await renderizar();
      const casos: [number, string][] = [[1, 'app-aviso-escala-form'], [2, 'app-aviso-agenda-form'], [3, 'app-aviso-pessoal-form']];
      for (const [i, sel] of casos) {
        cardBtns(fixture)[i].click();
        await estabilizar(fixture);
        expect(fixture.debugElement.query(By.css(sel))).not.toBeNull();
        expect(fixture.debugElement.query(By.css('table.data-table'))).not.toBeNull();
      }
    });

    it('reclicar no card ativo fecha o painel (nenhum ativo)', async () => {
      const fixture = await renderizar();
      cardBtns(fixture)[2].click();          // Agenda
      await estabilizar(fixture);
      expect(fixture.componentInstance.activeCard()).toBe('agenda');
      cardBtns(fixture)[2].click();          // reclique
      await estabilizar(fixture);
      expect(fixture.componentInstance.activeCard()).toBeNull();
      expect(fixture.debugElement.query(By.css('app-aviso-agenda-form'))).toBeNull();
      expect(fixture.debugElement.query(By.css('table.data-table'))).not.toBeNull();
    });

    it('coluna "Expira em" exibe a data sempre que o backend a mandar — inclusive num permanente (Escala)', async () => {
      // Antes: permanente=1 => sempre "—", escondendo o DATA_FIM que a Escala manda. Agora exibe a data.
      respostas[EP_LISTA] = ok({ ...AVISO_ATIVO, permanente: 1, expira_em: '2026-07-24' });
      const fixture = await renderizar();
      expect(textoDaTabela(fixture)).toContain('24/07/2026');
    });
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { DebugElement, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { ToastService } from '../../shared/components/toast.component';
import { AdminAvisosSalaComponent } from './admin-avisos-sala.component';

/**
 * C13b — AdminAvisosSalaComponent (/admin/avisos-sala): tela de DUAS metades — o formulário de
 * cadastro de aviso (multi-select de locais + mensagens) em cima, e UMA listagem server-side
 * ("Avisos Cadastrados") no `TableStateController` `ctrl` (`/api/admin/avisos/list`) embaixo.
 *
 * O que este spec trava é o CANAL DE ERRO (C7) recém-instalado na listagem. Antes dele, a tela
 * preenchia o `erro` do controlador e NÃO o exibia: uma leitura que falhava caía no ramo de lista
 * vazia e a tela AFIRMAVA "Nenhum aviso cadastrado." — a pior leitura falsa possível justamente
 * AQUI, porque a tela é de escrita: o admin conclui que a sala está limpa e cadastra um aviso por
 * cima (ou deixa de DESATIVAR o aviso ativo que devia sair), e é a listagem — não o formulário —
 * que lhe mostra o que já existe. Pior ainda: o backend só admite 1 aviso ativo por sala, então a
 * verdade escondida vira erro de cadastro depois, sem que o admin entenda por quê.
 *
 * Por isso os testes são de RENDER (exceção deliberada à regra "só lógica" do módulo): o canal só
 * cumpre a decisão do estágio se o TEMPLATE o consumir. Travar apenas o signal deixaria a suíte
 * verde com o ramo `@if (ctrl.erro())` apagado e o defeito de volta na tela. O motor em si
 * (rows/meta/loading/erro/recência) já está coberto no T21 (`table-state.controller.spec.ts`);
 * aqui prova-se a INSTALAÇÃO: caixa presente com a mensagem certa, retry que re-pede o endpoint,
 * vazio legítimo intacto, rodapé que não mente e — no lugar do teste de isolamento entre tabelas
 * (só há uma) — a prova de que a falha da LISTA não leva junto o FORMULÁRIO de cadastro.
 *
 * Estratégia: TestBed com `ApiService` mockado por `useValue` e `getList` roteado POR ENDPOINT;
 * `LookupService` (salas do multi-select) e `ToastService` também por `useValue`. O
 * `TableStateController` é REAL. Sem fake timers: nada no caminho testado depende de `Date` e o
 * debounce da busca (400 ms) não é exercido. O template tem `ngModel` (mensagens, permanente,
 * duração, busca) → todo render passa por `await fixture.whenStable()`.
 *
 * ⚠️ Divergência do roteiro do estágio (o CÓDIGO vence): o `Router` NÃO pode ser um `useValue`
 * cru — o template usa `RouterLink` ("← Voltar"), e a diretiva exige um `Router` de verdade
 * (`events`, `createUrlTree`, `serializeUrl`) + `ActivatedRoute`. Usa-se `provideRouter([])`, como
 * no spec irmão do `/admin/operacao-audio`, e espiona-se `navigate` quando preciso.
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

describe('AdminAvisosSalaComponent — canal de erro da listagem (C7/C13b)', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let apiPatch: ReturnType<typeof vi.fn>;
  let loadSalas: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;

  /** Resposta corrente da listagem — `falhar`/`vazio` trocam o cenário sem tocar no resto da tela. */
  let respostas: Record<string, () => Observable<any>>;

  const ok = (...linhas: unknown[]) => () => of({ data: linhas, meta: { ...META, total: linhas.length } });
  const vazio = () => () => of({ data: [], meta: { ...META, total: 0, pages: 0 } });
  const falha = (err: unknown = ERRO_500) => () => throwError(() => err);

  beforeEach(async () => {
    respostas = { [EP_LISTA]: ok(AVISO_ATIVO, AVISO_DESATIVADO) };
    apiGetList = vi.fn((endpoint: string) => respostas[endpoint]());
    // `ngOnInit` também chama `loadSalasOcupadas()` (GET .../salas-ocupadas, que trava no
    // multi-select as salas com aviso ativo). Sem este mock o `subscribe` estoura com erro obscuro.
    apiGet = vi.fn().mockReturnValue(of({ data: [] }));
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));     // cadastro
    apiPatch = vi.fn().mockReturnValue(of({ ok: true }));    // desativar
    loadSalas = vi.fn();
    toastSuccess = vi.fn();
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [AdminAvisosSalaComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink ("← Voltar") — ver divergência no cabeçalho
        { provide: ApiService, useValue: { getList: apiGetList, get: apiGet, post: apiPost, patch: apiPatch } },
        // Cache de salas VAZIO: é o estado da carga fria da tela (e o que dispara `loadSalas()`).
        { provide: LookupService, useValue: { salas: signal([]), loadSalas } },
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
  // Canal de erro (C7) — a falha de leitura deixa de se passar por "lista vazia"
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
});

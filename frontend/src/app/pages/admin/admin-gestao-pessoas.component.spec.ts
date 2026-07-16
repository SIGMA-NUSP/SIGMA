import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { DebugElement, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { AdminGestaoPessoasComponent } from './admin-gestao-pessoas.component';

/**
 * C13b — AdminGestaoPessoasComponent (/admin/gestao-pessoas): o cadastro vivo de PESSOAS do
 * sistema, com TRÊS listagens server-side independentes, uma por `TableStateController`:
 *   • opCtrl  → `/api/admin/dashboard/operadores`
 *   • tecCtrl → `/api/admin/dashboard/tecnicos`
 *   • admCtrl → `/api/admin/dashboard/administradores` (seção que SÓ existe para o master)
 *
 * O que este spec trava é o CANAL DE ERRO (C7) instalado nas três tabelas. Antes dele, a tela
 * preenchia o `erro` do controlador e não o exibia: uma leitura que falhava caía no ramo de lista
 * vazia e a tela AFIRMAVA "Nenhum operador encontrado." / "Nenhum técnico cadastrado." / "Nenhum
 * administrador encontrado.". Aqui a leitura falsa é especialmente cara: o admin conclui que a
 * pessoa não existe e vai RECADASTRAR quem já está no sistema (os cards "Cadastro de Operador"/
 * "Cadastro de Técnico" estão logo acima) — ou, na tabela do master, que não há mais nenhum
 * administrador.
 *
 * Por isso os testes são de RENDER (exceção deliberada à regra "só lógica"): o canal só cumpre a
 * decisão do estágio se o TEMPLATE o consumir — travar apenas o signal deixaria a suíte verde com
 * o ramo `@if (X.erro())` apagado e o defeito de volta na tela. O motor (rows/meta/loading/erro/
 * recência) já está coberto no T21 (`table-state.controller.spec.ts`); aqui prova-se a INSTALAÇÃO
 * por tabela: caixa presente com `role="alert"`, mensagem certa, retry que re-pede o endpoint
 * CERTO, vazio legítimo intacto, isolamento entre as três e rodapé que não mente.
 *
 * Estratégia: TestBed com `ApiService` mockado por `useValue` e `getList` roteado POR ENDPOINT (é
 * o que distingue as três tabelas — e o que permite derrubar UMA sem tocar nas outras). Os
 * `TableStateController` são REAIS. `AuthService` entra como stub com `isMaster` = signal (é só o
 * que a tela lê dele), permitindo cobrir os dois papéis. Sem fake timers: nada no caminho testado
 * lê `Date` e o debounce da busca (400 ms, do controlador) não é exercido. Como o template tem
 * `ngModel` (as buscas das seções), todo render passa por `await fixture.whenStable()`.
 *
 * ⚠️ Divergência do enunciado do estágio (o CÓDIGO vence): o `Router` NÃO pode ser um `useValue`
 * só com `navigate` — o template usa `routerLink` nos 5 cards de navegação, e a diretiva injeta o
 * `Router` de verdade (`createUrlTree`/`serializeUrl` para montar o href). Usa-se o `provideRouter([])`
 * e espiona-se o `navigate` (mesma solução do spec do /admin/ponto): a navegação programática
 * (`abrirPerfil`/`novoAdmin`) fica provada sem quebrar os cards.
 */

// ── Endpoints das 3 listagens ──
const EP_OP = '/api/admin/dashboard/operadores';
const EP_TEC = '/api/admin/dashboard/tecnicos';
const EP_ADM = '/api/admin/dashboard/administradores';

const META = { page: 1, limit: 10, total: 3, pages: 1 };

/** Linha de `/operadores`. */
const OPERADOR = { id: 'op-1', nome_completo: 'Maria Souza', email: 'maria.souza@senado.leg.br' };
/** Linha de `/tecnicos`. */
const TECNICO = { id: 'tec-1', nome_completo: 'Carlos Dias', email: 'carlos.dias@senado.leg.br' };
/** Linha de `/administradores` (só o master enxerga esta tabela). */
const ADMIN = { id: 'adm-1', nome_completo: 'Evandro Pereira', email: 'evandro.p@senado.leg.br' };

/** 500 REAL do backend (`GlobalExceptionHandler`): corpo genérico → a guia da tela vem na frente. */
const ERRO_500 = { status: 500, error: { ok: false, error: 'Erro interno do servidor' } };
/** Texto que o admin efetivamente lê na caixa: guia da tela + detalhe do backend entre parênteses. */
const MSG_500 = 'Não foi possível carregar a lista. (Erro interno do servidor)';

describe('AdminGestaoPessoasComponent — canal de erro das 3 listagens (C7/C13b)', () => {
  let apiGetList: ReturnType<typeof vi.fn>;
  let downloadReport: ReturnType<typeof vi.fn>;

  /** Resposta corrente de cada endpoint — `falha`/`vazio` trocam UMA sem tocar nas outras. */
  let respostas: Record<string, () => Observable<any>>;

  const ok = (linha: unknown) => () => of({ data: [linha], meta: { ...META } });
  const vazio = () => () => of({ data: [], meta: { ...META, total: 0, pages: 0 } });
  const falha = (err: unknown = ERRO_500) => () => throwError(() => err);

  /** Monta o TestBed com o papel desejado (o master vê a 3ª tabela; o admin comum, não). */
  async function configurar(master: boolean): Promise<void> {
    TestBed.resetTestingModule();
    respostas = {
      [EP_OP]: ok(OPERADOR),
      [EP_TEC]: ok(TECNICO),
      [EP_ADM]: ok(ADMIN),
    };
    // Roteia por endpoint: é assim que o spec distingue as três tabelas.
    apiGetList = vi.fn((endpoint: string) => respostas[endpoint]());
    downloadReport = vi.fn();

    await TestBed.configureTestingModule({
      imports: [AdminGestaoPessoasComponent],
      providers: [
        provideRouter([]),   // o template usa RouterLink (cards de navegação / voltar ao painel)
        { provide: ApiService, useValue: { getList: apiGetList, downloadReport } },
        { provide: AuthService, useValue: { isMaster: signal(master) } },
      ],
    }).compileComponents();
  }

  beforeEach(async () => {
    await configurar(true);   // o default dos testes é o MASTER (3 tabelas na tela)
  });

  afterEach(() => vi.restoreAllMocks());

  /** Render completo: `detectChanges` roda o `ngOnInit` (as cargas mockadas são síncronas). */
  async function renderizar(): Promise<ComponentFixture<AdminGestaoPessoasComponent>> {
    const fixture = TestBed.createComponent(AdminGestaoPessoasComponent);
    await estabilizar(fixture);
    return fixture;
  }

  /** O template tem `ngModel` (buscas) → o DOM só é confiável depois do `whenStable`. */
  async function estabilizar(fixture: ComponentFixture<AdminGestaoPessoasComponent>): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  // Tabelas na ordem do template: Operadores, Técnicos e (só para o master) Administradores.
  const tabelas = (f: ComponentFixture<AdminGestaoPessoasComponent>) =>
    f.debugElement.queryAll(By.css('table.data-table'));
  const tabOp = (f: ComponentFixture<AdminGestaoPessoasComponent>) => tabelas(f)[0];
  const tabTec = (f: ComponentFixture<AdminGestaoPessoasComponent>) => tabelas(f)[1];
  const tabAdm = (f: ComponentFixture<AdminGestaoPessoasComponent>) => tabelas(f)[2];

  /** A caixa de erro DAQUELA tabela (o canal é por tabela — o erro de uma não vaza para as outras). */
  const caixa = (tabela: DebugElement) => tabela.query(By.directive(ErroCargaComponent));
  const textoDaTabela = (tabela: DebugElement) =>
    (tabela.query(By.css('tbody')).nativeElement as HTMLElement).textContent ?? '';

  /** Clique REAL no "Tentar novamente" da caixa daquela tabela. */
  function clicarRetry(tabela: DebugElement): void {
    (tabela.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
  }

  /** Quantas vezes a listagem daquele endpoint foi PEDIDA (prova do retry). */
  const chamadas = (endpoint: string) =>
    apiGetList.mock.calls.filter(c => c[0] === endpoint).length;

  // ═══════════════════════════════════════════════════════════════════
  // Carga inicial — ponto de partida das demais provas
  // ═══════════════════════════════════════════════════════════════════
  describe('carga inicial', () => {
    it('o master pede as 3 listagens, ordenadas por nome (asc)', async () => {
      await renderizar();
      expect(chamadas(EP_OP)).toBe(1);
      expect(chamadas(EP_TEC)).toBe(1);
      expect(chamadas(EP_ADM)).toBe(1);
      expect(apiGetList).toHaveBeenCalledWith(EP_OP,
        expect.objectContaining({ page: 1, limit: 10, sort: 'nome', direction: 'asc' }));
    });

    it('tudo OK: as 3 tabelas exibem suas pessoas e nenhuma caixa de erro existe na tela', async () => {
      const fixture = await renderizar();
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
      expect(textoDaTabela(tabTec(fixture))).toContain('Carlos Dias');
      expect(textoDaTabela(tabAdm(fixture))).toContain('Evandro Pereira');
      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Operadores de Áudio
  // ═══════════════════════════════════════════════════════════════════
  describe('Operadores de Áudio', () => {
    it('falha na carga: caixa de erro com role="alert" e a mensagem do canal, SEM "Nenhum operador encontrado."', async () => {
      // Sem o canal, esta falha se passava por "não há operador nenhum cadastrado" — e o admin,
      // com o card "Cadastro de Operador" à mão, recadastraria gente que já existe.
      respostas[EP_OP] = falha();
      const fixture = await renderizar();

      const box = caixa(tabOp(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();   // anunciado ao leitor de tela
      expect(textoDaTabela(tabOp(fixture))).toContain(MSG_500);
      expect(textoDaTabela(tabOp(fixture))).not.toContain('Nenhum operador encontrado.');
    });

    it('"Tentar novamente" re-dispara a carga DA TABELA (2º GET em /operadores) e não mexe nas vizinhas', async () => {
      respostas[EP_OP] = falha();
      const fixture = await renderizar();
      expect(chamadas(EP_OP)).toBe(1);

      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_OP)).toBe(2);    // o clique no DOM chegou ao opCtrl.load()
      expect(chamadas(EP_TEC)).toBe(1);
      expect(chamadas(EP_ADM)).toBe(1);
    });

    it('retry com sucesso: a caixa some e os operadores aparecem', async () => {
      respostas[EP_OP] = falha();
      const fixture = await renderizar();

      respostas[EP_OP] = ok(OPERADOR);   // o backend voltou
      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(caixa(tabOp(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
      expect(fixture.componentInstance.opCtrl.erro()).toBe('');
    });

    it('vazio LEGÍTIMO (200 com data:[]): "Nenhum operador encontrado." e nenhuma caixa de erro', async () => {
      // O outro lado da moeda: o canal não pode transformar ausência de dados em alarme falso.
      respostas[EP_OP] = vazio();
      const fixture = await renderizar();

      expect(textoDaTabela(tabOp(fixture))).toContain('Nenhum operador encontrado.');
      expect(caixa(tabOp(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação some (não exibe o total anterior)', async () => {
      const fixture = await renderizar();
      const secaoOp = fixture.debugElement.queryAll(By.css('section'))[0];
      expect(secaoOp.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_OP] = falha();
      fixture.componentInstance.opCtrl.load();   // recarga (paginação, sort, filtro, busca)
      await estabilizar(fixture);

      expect(fixture.componentInstance.opCtrl.meta()).toBeNull();
      expect(secaoOp.query(By.css('.pag-info'))).toBeNull();   // "3 registros" não sobrevive à falha
      expect(caixa(tabOp(fixture))).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Técnicos
  // ═══════════════════════════════════════════════════════════════════
  describe('Técnicos', () => {
    it('falha na carga: caixa com role="alert" e a mensagem, SEM "Nenhum técnico cadastrado."', async () => {
      respostas[EP_TEC] = falha();
      const fixture = await renderizar();

      const box = caixa(tabTec(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();
      expect(textoDaTabela(tabTec(fixture))).not.toContain('Nenhum técnico cadastrado.');
    });

    it('"Tentar novamente" re-pede só os técnicos; o sucesso limpa a caixa e traz as linhas', async () => {
      respostas[EP_TEC] = falha();
      const fixture = await renderizar();

      respostas[EP_TEC] = ok(TECNICO);
      clicarRetry(tabTec(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_TEC)).toBe(2);
      expect(chamadas(EP_OP)).toBe(1);
      expect(chamadas(EP_ADM)).toBe(1);
      expect(caixa(tabTec(fixture))).toBeNull();
      expect(textoDaTabela(tabTec(fixture))).toContain('Carlos Dias');
    });

    it('vazio LEGÍTIMO: "Nenhum técnico cadastrado." sem caixa de erro', async () => {
      respostas[EP_TEC] = vazio();
      const fixture = await renderizar();
      expect(textoDaTabela(tabTec(fixture))).toContain('Nenhum técnico cadastrado.');
      expect(caixa(tabTec(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação da seção some', async () => {
      const fixture = await renderizar();
      const secaoTec = fixture.debugElement.queryAll(By.css('section'))[1];
      expect(secaoTec.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_TEC] = falha();
      fixture.componentInstance.tecCtrl.load();
      await estabilizar(fixture);

      expect(fixture.componentInstance.tecCtrl.meta()).toBeNull();
      expect(secaoTec.query(By.css('.pag-info'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Administradores do Sistema — tabela EXCLUSIVA do master
  // ═══════════════════════════════════════════════════════════════════
  describe('Administradores do Sistema (master)', () => {
    it('falha na carga: caixa com role="alert" e a mensagem, SEM "Nenhum administrador encontrado."', async () => {
      // "Nenhum administrador encontrado." num 500 é a leitura falsa mais alarmante da tela: sugere
      // ao master que o sistema ficou sem administradores — e ele criaria admins duplicados.
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();

      const box = caixa(tabAdm(fixture));
      expect(box).not.toBeNull();
      expect(box.componentInstance.mensagem()).toBe(MSG_500);
      expect(box.query(By.css('[role="alert"]'))).not.toBeNull();
      expect(textoDaTabela(tabAdm(fixture))).not.toContain('Nenhum administrador encontrado.');
    });

    it('"Tentar novamente" re-pede só os administradores; o sucesso limpa a caixa e traz as linhas', async () => {
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();

      respostas[EP_ADM] = ok(ADMIN);
      clicarRetry(tabAdm(fixture));
      await estabilizar(fixture);

      expect(chamadas(EP_ADM)).toBe(2);
      expect(chamadas(EP_OP)).toBe(1);
      expect(chamadas(EP_TEC)).toBe(1);
      expect(caixa(tabAdm(fixture))).toBeNull();
      expect(textoDaTabela(tabAdm(fixture))).toContain('Evandro Pereira');
    });

    it('vazio LEGÍTIMO: "Nenhum administrador encontrado." sem caixa de erro', async () => {
      respostas[EP_ADM] = vazio();
      const fixture = await renderizar();
      expect(textoDaTabela(tabAdm(fixture))).toContain('Nenhum administrador encontrado.');
      expect(caixa(tabAdm(fixture))).toBeNull();
    });

    it('o rodapé não mente: no erro o meta é limpo e a paginação da seção some', async () => {
      const fixture = await renderizar();
      const secaoAdm = fixture.debugElement.queryAll(By.css('section'))[2];
      expect(secaoAdm.query(By.css('.pag-info')).nativeElement.textContent).toContain('3 registros');

      respostas[EP_ADM] = falha();
      fixture.componentInstance.admCtrl.load();
      await estabilizar(fixture);

      expect(fixture.componentInstance.admCtrl.meta()).toBeNull();
      expect(secaoAdm.query(By.css('.pag-info'))).toBeNull();
    });

    it('o botão "Novo Admin" continua na tela durante o erro (a saída não some junto com os dados — F44)', async () => {
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();
      const secaoAdm = fixture.debugElement.queryAll(By.css('section'))[2];
      expect(secaoAdm.query(By.css('.header-actions button'))).not.toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Admin NÃO-master — a 3ª seção não existe (nem a tabela, nem a carga, nem o canal)
  // ═══════════════════════════════════════════════════════════════════
  describe('admin comum (isMaster = false)', () => {
    beforeEach(async () => { await configurar(false); });

    it('a seção de administradores não é renderizada e o endpoint nem é pedido', async () => {
      const fixture = await renderizar();

      expect(tabelas(fixture)).toHaveLength(2);                      // só operadores e técnicos
      expect(chamadas(EP_ADM)).toBe(0);                              // ngOnInit não chama admCtrl.load()
      const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(texto).not.toContain('Administradores do Sistema');
      expect(texto).not.toContain('Nenhum administrador encontrado.');
    });

    it('um /administradores fora do ar não pinta caixa nenhuma para o admin comum (a tabela não existe)', async () => {
      // O canal de erro é do TEMPLATE: sem a seção, não há onde a caixa aparecer — e as duas
      // listagens que ele PODE ver seguem inteiras.
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(0);
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
      expect(textoDaTabela(tabTec(fixture))).toContain('Carlos Dias');
    });

    it('o canal segue valendo nas 2 tabelas que ele vê: erro nos operadores mostra a caixa', async () => {
      respostas[EP_OP] = falha();
      const fixture = await renderizar();

      expect(caixa(tabOp(fixture))).not.toBeNull();
      expect(caixa(tabTec(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).not.toContain('Nenhum operador encontrado.');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Isolamento — 3 controladores, 3 canais independentes
  // ═══════════════════════════════════════════════════════════════════
  describe('isolamento entre as 3 tabelas', () => {
    it('só os operadores falham: a caixa aparece neles e as outras duas seguem com suas linhas', async () => {
      respostas[EP_OP] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
      expect(caixa(tabOp(fixture))).not.toBeNull();
      expect(caixa(tabTec(fixture))).toBeNull();
      expect(caixa(tabAdm(fixture))).toBeNull();
      expect(textoDaTabela(tabTec(fixture))).toContain('Carlos Dias');
      expect(textoDaTabela(tabAdm(fixture))).toContain('Evandro Pereira');
    });

    it('só os técnicos falham: operadores e administradores intactos (inclusive o meta deles)', async () => {
      respostas[EP_TEC] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(1);
      expect(caixa(tabTec(fixture))).not.toBeNull();
      expect(caixa(tabOp(fixture))).toBeNull();
      expect(caixa(tabAdm(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
      expect(fixture.componentInstance.opCtrl.meta()).toEqual(META);
      expect(fixture.componentInstance.admCtrl.meta()).toEqual(META);
      expect(fixture.componentInstance.tecCtrl.meta()).toBeNull();
    });

    it('as 3 falham juntas (backend fora do ar): 3 caixas, uma por tabela, e nenhuma frase de vazio', async () => {
      respostas[EP_OP] = falha();
      respostas[EP_TEC] = falha();
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.directive(ErroCargaComponent))).toHaveLength(3);
      const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(texto).not.toContain('Nenhum operador encontrado.');
      expect(texto).not.toContain('Nenhum técnico cadastrado.');
      expect(texto).not.toContain('Nenhum administrador encontrado.');
    });

    it('o retry de UMA tabela não ressuscita as outras: recuperar os operadores deixa a caixa dos técnicos de pé', async () => {
      respostas[EP_OP] = falha();
      respostas[EP_TEC] = falha();
      const fixture = await renderizar();

      respostas[EP_OP] = ok(OPERADOR);
      clicarRetry(tabOp(fixture));
      await estabilizar(fixture);

      expect(caixa(tabOp(fixture))).toBeNull();
      expect(textoDaTabela(tabOp(fixture))).toContain('Maria Souza');
      expect(caixa(tabTec(fixture))).not.toBeNull();   // continua falhando — e continua avisando
      expect(chamadas(EP_TEC)).toBe(1);                // o retry não recarregou a vizinha
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Controles da tela durante o erro (não repetir o F44: perder a saída junto com os dados)
  // ═══════════════════════════════════════════════════════════════════
  describe('controles da tela', () => {
    it('com as 3 listagens em erro, as buscas, os cards de cadastro e os relatórios continuam na tela', async () => {
      respostas[EP_OP] = falha();
      respostas[EP_TEC] = falha();
      respostas[EP_ADM] = falha();
      const fixture = await renderizar();

      expect(fixture.debugElement.queryAll(By.css('.search-input'))).toHaveLength(2);   // operadores e técnicos
      expect(fixture.debugElement.queryAll(By.css('.card-nav'))).toHaveLength(5);
      expect(fixture.debugElement.queryAll(By.css('.btn-report'))).toHaveLength(2);     // PDF e DOCX
    });

    it('os relatórios de operadores (PDF/DOCX) saem mesmo com a tabela em erro — vão ao backend, não às linhas da tela', async () => {
      respostas[EP_OP] = falha();
      const fixture = await renderizar();

      const botoes = fixture.debugElement.queryAll(By.css('.btn-report'));
      (botoes[0].nativeElement as HTMLButtonElement).click();   // PDF
      (botoes[1].nativeElement as HTMLButtonElement).click();   // DOCX

      expect(downloadReport).toHaveBeenNthCalledWith(1, `${EP_OP}/relatorio`, { format: 'pdf' });
      expect(downloadReport).toHaveBeenNthCalledWith(2, `${EP_OP}/relatorio`, { format: 'docx' });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Navegação — o botão "Perfil" de cada linha e o "Novo Admin"
  // ═══════════════════════════════════════════════════════════════════
  describe('navegação', () => {
    it('"Perfil" leva à rota do tipo certo, com o id da pessoa na query', async () => {
      const fixture = await renderizar();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      (tabOp(fixture).query(By.css('tbody button')).nativeElement as HTMLButtonElement).click();
      expect(navigate).toHaveBeenCalledWith(['/admin/operador/perfil'], { queryParams: { id: 'op-1' } });

      (tabTec(fixture).query(By.css('tbody button')).nativeElement as HTMLButtonElement).click();
      expect(navigate).toHaveBeenCalledWith(['/admin/tecnico/perfil'], { queryParams: { id: 'tec-1' } });

      (tabAdm(fixture).query(By.css('tbody button')).nativeElement as HTMLButtonElement).click();
      expect(navigate).toHaveBeenCalledWith(['/admin/administrador/perfil'], { queryParams: { id: 'adm-1' } });
    });

    it('"Novo Admin" (só do master) leva ao cadastro de administrador', async () => {
      const fixture = await renderizar();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      const secaoAdm = fixture.debugElement.queryAll(By.css('section'))[2];
      (secaoAdm.query(By.css('.header-actions button')).nativeElement as HTMLButtonElement).click();

      expect(navigate).toHaveBeenCalledWith(['/admin/novo-admin']);
    });
  });
});

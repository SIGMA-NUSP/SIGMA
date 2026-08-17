import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { PontoRetificarComponent } from './ponto-retificar.component';
import { ToastService } from './toast.component';

/**
 * PontoRetificarComponent: a folha publicada virando planilha editável, uma CÉLULA por vez.
 *
 * A tela nasce de duas cargas independentes — `GET /dados` (as linhas impressas na folha) e
 * `GET /retificacoes` (o que cada dia ainda aceita, o que já foi corrigido e as ocorrências
 * ofertadas). A segunda é o que DESTRAVA a edição: enquanto ela não chega, ou se falha, nenhuma
 * célula é editável e a caixa de erro com "Tentar novamente" é o único caminho de volta.
 *
 * Cada batida se grava sozinha: o clique abre um menu ancorado na célula, "Editar horário"
 * transforma a célula num campo e o horário completo dispara `PUT .../celula` na hora — sem botão
 * Salvar, sem par obrigatório, sem envio em lote e sem observação em nenhuma das rotas. O mesmo
 * menu declara uma ocorrência para o dia inteiro (`PUT .../tipo`) ou apaga a correção
 * (`DELETE .../{data}`). A tela nunca é otimista: só a resposta muda o que está à vista, o erro
 * vai ao toast e o dia com gravação em voo não aceita outro gesto — os outros dias, sim.
 *
 * TestBed sem `detectChanges()` por padrão — `ngOnInit` à mão, filhos não instanciados;
 * `ApiService`/`ToastService`/`Router`/`ActivatedRoute` mockados via `useValue` (o `RouterLink` do
 * template resolve o mesmo mock de `Router` na criação). Fake timers instalados APÓS
 * `compileComponents()`, que exige timers reais: "Editar horário" agenda o foco do campo num
 * `setTimeout` que nenhum teste precisa drenar — e drenar timers no zoneless acordaria a change
 * detection. Os payloads reproduzem a API como ela responde de verdade, OMITINDO as chaves nulas
 * (dia sem marcação não traz `marcacao_global`; correção só de `ent1` não traz os outros campos).
 */
describe('PontoRetificarComponent', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPut: ReturnType<typeof vi.fn>;
  let apiDelete: ReturnType<typeof vi.fn>;
  let toastError: ReturnType<typeof vi.fn>;
  let paginaId: string | null;
  /** Resposta corrente de cada carga — trocar UMA não toca na outra. */
  let respostas: { dados: () => Observable<any>; janela: () => Observable<any> };

  type Linha = ReturnType<PontoRetificarComponent['linhas']>[number];
  type Celula = Linha['celulas'][number];

  /**
   * Folha semanal de 03/08 a 09/08 como o servidor a imprime, com um dia para cada estado que a
   * tela precisa distinguir.
   */
  const FOLHA = {
    id: 'pag-1',
    tipo: 'SEMANAL',
    data_inicio: '2026-08-03',
    data_fim: '2026-08-09',
    linhas: [
      { dia: '03/08/26 - seg', ent1: '08:00', sai1: '12:00', ent2: '13:00', sai2: '17:00', total_dia: '08:00', banco: '00:00' },
      { dia: '04/08/26 - ter', ent1: '08:12', sai1: '12:00', ent2: '', sai2: '', total_dia: '03:48', banco: '-04:12' },
      { dia: '05/08/26 - qua', ent1: 'Falta', sai1: '', ent2: '', sai2: '', total_dia: '', banco: '-08:00' },
      { dia: '06/08/26 - qui', ent1: '', sai1: '', ent2: '', sai2: '', total_dia: '', banco: '00:00' },
      { dia: '07/08/26 - sex', ent1: '09:10', sai1: '12:00', ent2: '13:00', sai2: '18:00', total_dia: '07:50', banco: '-00:10' },
      { dia: '08/08/26 - sáb', ent1: '', sai1: '', ent2: '', sai2: '', total_dia: '', banco: '00:00' },
    ],
  };

  /** Índice de cada linha da folha, pelo estado que ela representa. */
  const ABERTO = 0;      // 03/08 (seg) — aceita correção e não tem nenhuma
  const CORRIGIDO = 1;   // 04/08 (ter) — aceita correção e já tem uma, num campo só
  const STATUS = 2;      // 05/08 (qua) — aceita correção, mas a folha traz "Falta" no lugar da hora
  const FERIADO = 3;     // 06/08 (qui) — o administrador marcou o dia para todos
  const FECHADO = 4;     // 07/08 (sex) — fora da janela, com correções já gravadas
  const SABADO = 5;      // 08/08 (sáb) — fim de semana, ainda que a janela o diga aberto

  /**
   * `GET /retificacoes`: o que cada dia aceita, o que já foi corrigido e as ocorrências que o
   * funcionário pode declarar. As chaves nulas NÃO vêm — é assim que a API responde.
   */
  const JANELA = {
    dias: [
      { data: '2026-08-03', aberto: true },
      { data: '2026-08-04', aberto: true },
      { data: '2026-08-05', aberto: true },
      { data: '2026-08-06', aberto: true, marcacao_global: 'Feriado' },
      { data: '2026-08-07', aberto: false },
      { data: '2026-08-08', aberto: true },
    ],
    retificacoes: [
      { id: 'ret-4', data: '2026-08-04', ent1: '08:00' },
      { id: 'ret-7', data: '2026-08-07', ent1: '09:00', sai1: '12:30' },
    ],
    tipos: [
      { id: 'tp-banco', nome: 'Banco de horas' },
      { id: 'tp-atestado', nome: 'Atestado' },
    ],
  };

  const URL_DADOS = '/api/ponto/folha/pag-1/dados';
  const URL_JANELA = '/api/ponto/folha/pag-1/retificacoes';
  const URL_CELULA = '/api/ponto/folha/pag-1/retificacoes/celula';
  const URL_TIPO = '/api/ponto/folha/pag-1/retificacoes/tipo';

  /** A correção do dia como o servidor a devolve depois de gravar uma batida. */
  const ecoCelula = (body: any, extra: Record<string, unknown> = {}) =>
    ({ data: { id: `ret-${body.data}`, data: body.data, [body.campo]: body.valor, ...extra } });

  /** A correção do dia como o servidor a devolve depois de declarar uma ocorrência. */
  const ecoTipo = (body: any) => ({
    data: {
      id: `ret-${body.data}`,
      data: body.data,
      tipo_id: body.tipo_id,
      tipo_nome: JANELA.tipos.find(t => t.id === body.tipo_id)?.nome,
      conta_folga: true,
    },
  });

  /** Resposta de erro com corpo do backend. */
  const falha = (corpo: Record<string, string>) => () => throwError(() => ({ error: corpo }));

  beforeEach(async () => {
    paginaId = 'pag-1';
    respostas = {
      dados: () => of({ data: structuredClone(FOLHA) }),
      janela: () => of({ data: structuredClone(JANELA) }),
    };
    apiGet = vi.fn((url: string) => (url.endsWith('/dados') ? respostas.dados() : respostas.janela()));
    apiPut = vi.fn((url: string, body: any) => of(url.endsWith('/tipo') ? ecoTipo(body) : ecoCelula(body)));
    apiDelete = vi.fn().mockReturnValue(of({ ok: true }));
    toastError = vi.fn();

    await TestBed.configureTestingModule({
      imports: [PontoRetificarComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, put: apiPut, delete: apiDelete, post: vi.fn() } },
        { provide: ToastService, useValue: { error: toastError, success: vi.fn(), warning: vi.fn(), show: vi.fn() } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: (k: string) => (k === 'paginaId' ? paginaId : null) } } },
        },
      ],
    }).compileComponents(); // com timers reais — só depois falsificamos

    vi.useFakeTimers();     // o foco do campo é agendado; nenhum teste precisa dele
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Cria o componente cru (sem detectChanges → sem ngOnInit automático). */
  const criar = () => TestBed.createComponent(PontoRetificarComponent).componentInstance;

  /** Componente com a folha e a janela já carregadas. */
  function criarCarregado(): PontoRetificarComponent {
    const comp = criar();
    comp.ngOnInit();
    return comp;
  }

  /** Substitui a resposta da janela, preservando o resto do payload. */
  function janelaCom(patch: Record<string, unknown>): void {
    respostas.janela = () => of({ data: { ...structuredClone(JANELA), ...patch } });
  }

  const linha = (comp: PontoRetificarComponent, idx: number): Linha => comp.linhas()[idx];
  const celula = (comp: PontoRetificarComponent, idx: number, campo: string): Celula =>
    linha(comp, idx).celulas.find(c => c.campo === campo)!;

  /** Clique com a âncora do menu (o componente lê o retângulo do elemento clicado). */
  function clique(): MouseEvent {
    return {
      stopPropagation: vi.fn(),
      currentTarget: { getBoundingClientRect: () => ({ left: 120, bottom: 260, top: 240 }) },
    } as unknown as MouseEvent;
  }

  /** Abre o menu na célula (linha, campo). */
  function abrir(comp: PontoRetificarComponent, idx: number, campo = 'ent1'): void {
    comp.abrirMenu(linha(comp, idx), celula(comp, idx, campo), clique());
  }

  /** Abre o menu e escolhe "Editar horário": a célula vira campo. */
  function editar(comp: PontoRetificarComponent, idx: number, campo = 'ent1'): void {
    abrir(comp, idx, campo);
    comp.editarHorario();
  }

  /** Digita no campo aberto (o que a máscara emite a cada tecla). */
  function digitar(comp: PontoRetificarComponent, idx: number, campo: string, valor: string): void {
    comp.aoDigitar(linha(comp, idx), celula(comp, idx, campo), valor);
  }

  /** Sai do campo (blur). */
  function sair(comp: PontoRetificarComponent, idx: number, campo: string): void {
    comp.aoSair(linha(comp, idx), celula(comp, idx, campo));
  }

  // ═══════════════════════════════════════════════════════════════════
  // Carga — duas requisições, cabeçalho do período e a caixa de erro
  // ═══════════════════════════════════════════════════════════════════
  describe('carga da folha', () => {
    it('sem paginaId na rota: erro e nenhuma chamada à API', () => {
      paginaId = null;
      const comp = criar();
      comp.ngOnInit();

      expect(comp.erro()).toBe('Folha não informada.');
      expect(comp.loading()).toBe(false);
      expect(apiGet).not.toHaveBeenCalled();
    });

    it('as duas requisições saem, na ordem: a folha e depois o que ela ainda aceita', () => {
      const comp = criarCarregado();

      expect(apiGet).toHaveBeenCalledTimes(2);
      expect(apiGet.mock.calls[0][0]).toBe(URL_DADOS);
      expect(apiGet.mock.calls[1][0]).toBe(URL_JANELA);
      expect(comp.folha()?.id).toBe('pag-1');
      expect(comp.linhas()).toHaveLength(6);
      expect(comp.loading()).toBe(false);
      expect(comp.erro()).toBe('');
      expect(comp.erroJanela()).toBe('');
    });

    it('cabeçalho da folha semanal: o período com as duas datas', () => {
      const comp = criarCarregado();

      expect(comp.tipoLabel()).toBe('semanal');
      expect(comp.periodoFolhaLabel()).toBe('03/08/2026 a 09/08/2026');
    });

    it('cabeçalho da folha mensal: a competência', () => {
      respostas.dados = () =>
        of({ data: { ...structuredClone(FOLHA), tipo: 'MENSAL', data_inicio: '2026-08-01', data_fim: '2026-08-31' } });
      const comp = criarCarregado();

      expect(comp.tipoLabel()).toBe('mensal');
      expect(comp.periodoFolhaLabel()).toBe('Agosto/2026');
    });

    it('sem folha carregada o cabeçalho fica vazio (nada de período inventado)', () => {
      expect(criar().periodoFolhaLabel()).toBe('');
    });

    it('erro na folha: mensagem do backend e nenhuma 2ª requisição', () => {
      respostas.dados = falha({ error: 'Folha de outro usuário', message: 'ignorada' });
      const comp = criarCarregado();

      expect(comp.erro()).toBe('Folha de outro usuário');
      expect(comp.folha()).toBeNull();
      expect(comp.loading()).toBe(false);
      expect(apiGet).toHaveBeenCalledTimes(1);
      expect(comp.linhas()).toEqual([]);
    });

    it('erro sem corpo: cai no fallback', () => {
      respostas.dados = () => throwError(() => new Error('rede'));
      const comp = criarCarregado();

      expect(comp.erro()).toBe('Não foi possível carregar a folha.');
    });

    it('resposta sem data: nenhuma linha, e a janela ainda é buscada pelo id da rota', () => {
      respostas.dados = () => of({});
      const comp = criarCarregado();

      expect(comp.linhas()).toEqual([]);
      expect(apiGet.mock.calls[1][0]).toBe(URL_JANELA);
    });

    it('render: o erro da folha ocupa a tela, e a tabela não aparece', () => {
      respostas.dados = falha({ error: 'Folha não encontrada.' });
      const fixture = renderizar();

      const caixa = fixture.debugElement.query(By.css('.error-box'));
      expect(caixa).not.toBeNull();
      expect(caixa!.nativeElement.textContent).toContain('Folha não encontrada.');
      expect(fixture.debugElement.query(By.css('table.ponto-table'))).toBeNull();
      expect(fixture.debugElement.query(By.css('.vista-mobile .dia-card'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Fail-closed — sem a resposta da janela, NADA é editável
  // ═══════════════════════════════════════════════════════════════════
  describe('fail-closed: a edição só existe com a janela na mão', () => {
    it('enquanto a janela não responde, a folha aparece e nenhuma célula é editável', () => {
      const emVoo = new Subject<any>();
      respostas.janela = () => emVoo;
      const comp = criarCarregado();

      expect(comp.carregandoJanela()).toBe(true);
      expect(comp.linhas()).toHaveLength(6);
      expect(comp.linhas().every(l => !l.editavel)).toBe(true);
      expect(comp.tipos()).toEqual([]);

      emVoo.next({ data: structuredClone(JANELA) });
      expect(comp.carregandoJanela()).toBe(false);
      expect(linha(comp, ABERTO).editavel).toBe(true);
    });

    it('com a janela em voo o menu não abre — a tela não aceita o que não sabe se pode gravar', () => {
      respostas.janela = () => new Subject<any>();
      const comp = criarCarregado();

      abrir(comp, ABERTO);
      expect(comp.menu()).toBeNull();
    });

    it('janela com erro: guia da tela + detalhe do backend, e a folha inteira fica só leitura', () => {
      respostas.janela = falha({ error: 'Erro interno do servidor' });
      const comp = criarCarregado();

      expect(comp.erroJanela()).toBe('Não foi possível concluir a operação. (Erro interno do servidor)');
      expect(comp.carregandoJanela()).toBe(false);
      expect(comp.erro()).toBe('');                       // o canal da FOLHA não é o da janela
      expect(comp.linhas().every(l => !l.editavel)).toBe(true);
      expect(comp.tipos()).toEqual([]);
    });

    it('erro sem corpo: só o guia da tela', () => {
      respostas.janela = () => throwError(() => new Error('rede'));
      const comp = criarCarregado();

      expect(comp.erroJanela()).toBe('Não foi possível concluir a operação.');
    });

    it('o retry recarrega a janela e devolve a edição', () => {
      respostas.janela = falha({ error: 'Erro interno do servidor' });
      const comp = criarCarregado();
      expect(comp.linhas().every(l => !l.editavel)).toBe(true);

      respostas.janela = () => of({ data: structuredClone(JANELA) });
      comp.recarregarJanela();

      expect(apiGet.mock.calls.filter(c => c[0] === URL_JANELA)).toHaveLength(2);
      expect(comp.erroJanela()).toBe('');
      expect(linha(comp, ABERTO).editavel).toBe(true);
      expect(comp.tipos()).toHaveLength(2);
    });

    it('render: a caixa com "Tentar novamente" aparece e nenhuma célula é um botão', () => {
      respostas.janela = falha({ error: 'Erro interno do servidor' });
      const fixture = renderizar();

      const caixa = fixture.debugElement.query(By.css('.erro-carga'));
      expect(caixa).not.toBeNull();
      expect(caixa!.nativeElement.textContent).toContain('Não foi possível concluir a operação.');
      expect(fixture.debugElement.query(By.css('table.ponto-table'))).not.toBeNull();   // a folha continua à vista
      expect(fixture.debugElement.queryAll(By.css('.vista-desktop td.cel-hora button'))).toEqual([]);
    });

    it('render: durante a recarga a tela avisa, e o clique no retry destrava as células', () => {
      respostas.janela = falha({ error: 'Erro interno do servidor' });
      const fixture = renderizar();

      const emVoo = new Subject<any>();
      respostas.janela = () => emVoo;
      (fixture.debugElement.query(By.css('.erro-carga button')).nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.css('.erro-carga'))).toBeNull();
      expect(fixture.nativeElement.textContent).toContain('Verificando o que ainda pode ser corrigido');
      expect(fixture.debugElement.queryAll(By.css('.vista-desktop td.cel-hora button'))).toEqual([]);

      emVoo.next({ data: structuredClone(JANELA) });
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).not.toContain('Verificando o que ainda pode ser corrigido');
      expect(fixture.debugElement.queryAll(By.css('.vista-desktop td.cel-hora button')).length).toBeGreaterThan(0);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // A linha — o que cada dia aceita e o que ele mostra
  // ═══════════════════════════════════════════════════════════════════
  describe('estados da linha', () => {
    it('dia aberto: editável, com as batidas que a folha imprimiu', () => {
      const comp = criarCarregado();
      const l = linha(comp, ABERTO);

      expect(l.data).toBe('2026-08-03');
      expect(l.editavel).toBe(true);
      expect(l.faixa).toBeNull();
      expect(l.corrigido).toBe(false);
      expect(l.celulas.map(c => c.valor)).toEqual(['08:00', '12:00', '13:00', '17:00']);
      expect(l.celulas.every(c => !c.corrigido)).toBe(true);
    });

    it('a correção substitui só o campo corrigido; o resto continua sendo a folha', () => {
      const comp = criarCarregado();
      const l = linha(comp, CORRIGIDO);

      expect(l.corrigido).toBe(true);
      expect(celula(comp, CORRIGIDO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: true });
      expect(celula(comp, CORRIGIDO, 'sai1')).toMatchObject({ valor: '12:00', corrigido: false });
      expect(celula(comp, CORRIGIDO, 'ent2')).toMatchObject({ valor: '', corrigido: false });
    });

    it('sábado não é editável nem com a janela dizendo que o dia está aberto', () => {
      const comp = criarCarregado();
      const l = linha(comp, SABADO);

      expect(l.fimDeSemana).toBe(true);
      expect(l.editavel).toBe(false);
    });

    it('dia marcado pelo administrador: faixa única com o nome, e nada a editar', () => {
      const comp = criarCarregado();
      const l = linha(comp, FERIADO);

      expect(l.faixa).toEqual({ texto: 'Feriado', declarada: false });
      expect(l.editavel).toBe(false);
    });

    it('a ocorrência declarada pelo funcionário vira faixa dele, e o dia segue editável', () => {
      janelaCom({
        retificacoes: [{ id: 'ret-3', data: '2026-08-03', tipo_id: 'tp-banco', tipo_nome: 'Banco de horas', conta_folga: true }],
      });
      const comp = criarCarregado();
      const l = linha(comp, ABERTO);

      expect(l.faixa).toEqual({ texto: 'Banco de horas', declarada: true });
      expect(l.editavel).toBe(true);
      expect(l.celulas.every(c => !c.corrigido)).toBe(true);   // ou horários, ou ocorrência
    });

    it('a ocorrência declarada vence a marcação do administrador no mesmo dia', () => {
      janelaCom({
        retificacoes: [{ id: 'ret-6', data: '2026-08-06', tipo_id: 'tp-atestado', tipo_nome: 'Atestado' }],
      });
      const comp = criarCarregado();

      expect(linha(comp, FERIADO).faixa).toEqual({ texto: 'Atestado', declarada: true });
      expect(linha(comp, FERIADO).editavel).toBe(false);       // o dia marcado segue fechado à edição
    });

    it('horário corrigido num dia marcado: a faixa sai e as batidas aparecem', () => {
      // A correção prevalece sobre a marcação na grade da chefia; esconder as batidas aqui faria
      // esta tela contar uma história diferente da que eles enxergam.
      janelaCom({ retificacoes: [{ id: 'ret-6', data: '2026-08-06', ent1: '08:05' }] });
      const comp = criarCarregado();
      const l = linha(comp, FERIADO);

      expect(l.faixa).toBeNull();
      expect(celula(comp, FERIADO, 'ent1')).toMatchObject({ valor: '08:05', corrigido: true });
      expect(l.editavel).toBe(false);
    });

    it('dia fora da janela: não é editável, mas as correções já feitas continuam à vista', () => {
      const comp = criarCarregado();
      const l = linha(comp, FECHADO);

      expect(l.editavel).toBe(false);
      expect(l.corrigido).toBe(true);
      expect(celula(comp, FECHADO, 'ent1')).toMatchObject({ valor: '09:00', corrigido: true });
      expect(celula(comp, FECHADO, 'sai1')).toMatchObject({ valor: '12:30', corrigido: true });
      expect(celula(comp, FECHADO, 'sai2')).toMatchObject({ valor: '18:00', corrigido: false });
    });

    it('dia que a janela nem menciona não é editável', () => {
      janelaCom({ dias: structuredClone(JANELA.dias).filter(d => d.data !== '2026-08-03') });
      const comp = criarCarregado();

      expect(linha(comp, ABERTO).editavel).toBe(false);
    });

    it('render: a linha bloqueada tem cadeado e chips fixos; a editável, botões', () => {
      const fixture = renderizar();
      const linhas = fixture.debugElement.queryAll(By.css('.vista-desktop tbody tr'));

      expect(linhas).toHaveLength(6);
      expect(linhas[ABERTO].queryAll(By.css('td.cel-hora button.chip'))).toHaveLength(4);
      expect(linhas[ABERTO].query(By.css('.cadeado'))).toBeNull();

      expect(linhas[SABADO].queryAll(By.css('td.cel-hora button'))).toEqual([]);
      expect(linhas[SABADO].queryAll(By.css('td.cel-hora span.chip-fixo'))).toHaveLength(4);
      expect(linhas[SABADO].query(By.css('.cadeado'))).not.toBeNull();

      const faixa = linhas[FERIADO].query(By.css('td.cel-faixa'));
      expect(faixa.nativeElement.textContent.trim()).toBe('Feriado');
      expect(faixa.nativeElement.getAttribute('colspan')).toBe('4');
      expect(faixa.query(By.css('button'))).toBeNull();
    });

    it('render: a célula corrigida ganha a marca de editada', () => {
      const fixture = renderizar();
      const chips = fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[CORRIGIDO]
        .queryAll(By.css('td.cel-hora .chip'));

      expect((chips[0].nativeElement as HTMLElement).classList.contains('chip-editado')).toBe(true);
      expect((chips[1].nativeElement as HTMLElement).classList.contains('chip-editado')).toBe(false);
    });

    it('render (cards): o dia bloqueado e sem nada corrigido não mostra as batidas', () => {
      // No celular, a linha morta fica só com a data e o cadeado: repetir quatro traços por dia
      // empurraria os dias que importam para fora da tela.
      const fixture = renderizar();
      const cards = fixture.debugElement.queryAll(By.css('.vista-mobile .dia-card'));

      expect(cards[SABADO].query(By.css('.card-celulas'))).toBeNull();

      expect(cards[FECHADO].queryAll(By.css('.card-celulas .chip'))
        .map(c => (c.nativeElement as HTMLElement).textContent?.trim()))
        .toEqual(['09:00', '12:30', '13:00', '18:00']);
      expect(cards[FECHADO].queryAll(By.css('.card-celulas button'))).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O menu da célula — onde nascem os três caminhos
  // ═══════════════════════════════════════════════════════════════════
  describe('menu da célula', () => {
    it('o clique abre o menu com o dia e o rótulo da batida no título', () => {
      const comp = criarCarregado();
      const ev = clique();
      comp.abrirMenu(linha(comp, ABERTO), celula(comp, ABERTO, 'ent1'), ev);

      expect(comp.menu()).toMatchObject({
        data: '2026-08-03', campo: 'ent1', titulo: '03/08 · Ent. 1', temCorrecao: false,
      });
      expect(ev.stopPropagation).toHaveBeenCalled();   // senão o clique fecharia o que acabou de abrir
    });

    it('o título acompanha a batida clicada', () => {
      const comp = criarCarregado();
      abrir(comp, ABERTO, 'sai2');

      expect(comp.menu()?.titulo).toBe('03/08 · Saí. 2');
    });

    it('"Limpar retificação" só existe onde há correção no dia', () => {
      const comp = criarCarregado();

      abrir(comp, ABERTO);
      expect(comp.menu()?.temCorrecao).toBe(false);

      comp.fecharMenu();
      abrir(comp, CORRIGIDO);
      expect(comp.menu()?.temCorrecao).toBe(true);
    });

    it('Esc e clique no documento fecham o menu', () => {
      const comp = criarCarregado();

      abrir(comp, ABERTO);
      comp.onEscape();
      expect(comp.menu()).toBeNull();

      abrir(comp, ABERTO);
      comp.onCliqueFora();
      expect(comp.menu()).toBeNull();
    });

    it('Esc sem menu aberto cancela a digitação em curso', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO);

      comp.onEscape();

      expect(comp.editandoCelula('2026-08-03', 'ent1')).toBe(false);
      expect(apiPut).not.toHaveBeenCalled();
    });

    it('dia bloqueado não abre menu nenhum: fim de semana, marcado ou fora da janela', () => {
      const comp = criarCarregado();

      abrir(comp, SABADO);
      expect(comp.menu()).toBeNull();

      abrir(comp, FERIADO);
      expect(comp.menu()).toBeNull();

      abrir(comp, FECHADO);
      expect(comp.menu()).toBeNull();
    });

    it('com uma gravação em voo naquele dia o menu não abre', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      editar(comp, ABERTO);
      digitar(comp, ABERTO, 'ent1', '09:30');

      abrir(comp, ABERTO, 'sai1');

      expect(comp.menu()).toBeNull();
      expect(comp.salvandoNoDia('2026-08-03')).toBe(true);
    });

    it('a faixa da ocorrência abre o menu do DIA, e "Editar horário" entra pela 1ª batida', () => {
      janelaCom({
        retificacoes: [{ id: 'ret-3', data: '2026-08-03', tipo_id: 'tp-banco', tipo_nome: 'Banco de horas' }],
      });
      const comp = criarCarregado();

      comp.abrirMenuDoDia(linha(comp, ABERTO), clique());

      expect(comp.menu()).toMatchObject({
        data: '2026-08-03', campo: 'ent1', titulo: '03/08 · Dia', temCorrecao: true,
      });
    });

    it('abrir o menu encerra a digitação que estava aberta em outra célula', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');

      abrir(comp, ABERTO, 'sai1');

      expect(comp.editandoNoDia('2026-08-03')).toBe(false);
    });

    it('render: o clique na célula abre o menu ancorado, com as três saídas', () => {
      const fixture = renderizar();

      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();

      expect(popover(fixture)).not.toBeNull();
      expect(tituloMenu(fixture)).toBe('03/08 · Ent. 1');
      expect(itensMenu(fixture)).toEqual(['Editar horário', 'Banco de horas', 'Atestado']);
    });

    it('render: no dia com correção, o menu oferece limpar a retificação', () => {
      const fixture = renderizar();

      chipDe(fixture, CORRIGIDO, 0).click();
      fixture.detectChanges();

      expect(itensMenu(fixture)).toEqual(['Editar horário', 'Banco de horas', 'Atestado', 'Limpar retificação']);
    });

    it('render: a faixa da ocorrência declarada também abre o menu', () => {
      janelaCom({
        retificacoes: [{ id: 'ret-3', data: '2026-08-03', tipo_id: 'tp-banco', tipo_nome: 'Banco de horas' }],
      });
      const fixture = renderizar();

      const faixa = fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[ABERTO]
        .query(By.css('td.cel-faixa button'));
      expect(faixa).not.toBeNull();
      (faixa.nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(tituloMenu(fixture)).toBe('03/08 · Dia');
      expect(itensMenu(fixture)).toContain('Limpar retificação');
    });

    it('render: um clique REAL no documento fecha o menu; o que o abriu, não', () => {
      const fixture = renderizar();

      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();
      expect(popover(fixture)).not.toBeNull();

      document.body.click();
      fixture.detectChanges();
      expect(popover(fixture)).toBeNull();
    });

    it('render: clicar dentro do menu não o fecha', () => {
      const fixture = renderizar();
      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();

      (popover(fixture).nativeElement as HTMLElement).click();
      fixture.detectChanges();

      expect(popover(fixture)).not.toBeNull();
    });

    it('render: Esc de verdade fecha o menu', () => {
      const fixture = renderizar();
      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();

      expect(popover(fixture)).toBeNull();
    });

    it('render: a lista nasce ancorada na célula clicada', () => {
      const fixture = renderizar();
      const chip = chipDe(fixture, ABERTO, 0);
      chip.getBoundingClientRect = () => ({ left: 340, bottom: 210, top: 190 }) as DOMRect;

      chip.click();
      fixture.detectChanges();

      const caixa = popover(fixture).nativeElement as HTMLElement;
      expect(caixa.style.left).toBe('340px');
      expect(caixa.style.top).toBe('210px');
      expect(caixa.classList.contains('acima')).toBe(false);
    });

    it('render: clicar numa célula bloqueada não abre nada', () => {
      const fixture = renderizar();

      (fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[SABADO]
        .query(By.css('td.cel-hora .chip')).nativeElement as HTMLElement).click();
      fixture.detectChanges();

      expect(popover(fixture)).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Edição da célula — o horário completo grava sozinho
  // ═══════════════════════════════════════════════════════════════════
  describe('edição por célula', () => {
    it('"Editar horário" abre o campo com o valor que está à vista', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'sai1');

      expect(comp.editandoCelula('2026-08-03', 'sai1')).toBe(true);
      expect(comp.editandoNoDia('2026-08-03')).toBe(true);
      expect(comp.rascunho()).toBe('12:00');
      expect(comp.menu()).toBeNull();          // o menu sai de cena quando o campo entra
    });

    it('o campo parte da correção já gravada, não do que a folha imprimiu', () => {
      const comp = criarCarregado();
      editar(comp, CORRIGIDO, 'ent1');

      expect(comp.rascunho()).toBe('08:00');   // a folha trazia 08:12
    });

    it('célula com status no lugar da hora abre o campo VAZIO', () => {
      const comp = criarCarregado();
      editar(comp, STATUS, 'ent1');

      expect(comp.rascunho()).toBe('');        // "Falta" não é horário que se possa continuar digitando
    });

    it('célula vazia abre o campo vazio', () => {
      const comp = criarCarregado();
      editar(comp, CORRIGIDO, 'ent2');

      expect(comp.rascunho()).toBe('');
    });

    it('o horário completo grava sozinho e fecha o campo', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:30');

      expect(apiPut).toHaveBeenCalledTimes(1);
      expect(apiPut).toHaveBeenCalledWith(URL_CELULA, { data: '2026-08-03', campo: 'ent1', valor: '09:30' });
      expect(comp.editandoCelula('2026-08-03', 'ent1')).toBe(false);
      expect(comp.rascunho()).toBe('');
      expect(celula(comp, ABERTO, 'ent1')).toMatchObject({ valor: '09:30', corrigido: true });
    });

    it('o horário pela metade não grava nada', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:');
      digitar(comp, ABERTO, 'ent1', '09:3');

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.editandoCelula('2026-08-03', 'ent1')).toBe(true);
      expect(comp.rascunho()).toBe('09:3');
    });

    it('sair do campo com o horário pela metade descarta em silêncio', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:3');

      sair(comp, ABERTO, 'ent1');

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.editandoCelula('2026-08-03', 'ent1')).toBe(false);
      expect(celula(comp, ABERTO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: false });
    });

    it('sair do campo com o horário mudado grava', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:3');   // incompleto: ainda não grava
      digitar(comp, ABERTO, 'ent1', '09:35');

      expect(apiPut).toHaveBeenCalledWith(URL_CELULA, { data: '2026-08-03', campo: 'ent1', valor: '09:35' });
    });

    it('abrir o campo e sair sem mexer não grava nada', () => {
      // O campo abre com o horário que já está à vista; desistir dele não é uma correção, e gravar
      // criaria uma retificação repetindo a folha.
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');

      sair(comp, ABERTO, 'ent1');

      expect(apiPut).not.toHaveBeenCalled();
    });

    it('Esc cancela a digitação sem requisição nenhuma', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:3');

      comp.cancelarEdicao();

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.editandoCelula('2026-08-03', 'ent1')).toBe(false);
      expect(comp.rascunho()).toBe('');
    });

    it('o que gravou pelo horário completo NÃO grava de novo ao sair do campo', () => {
      // O campo já fechou na gravação; o blur que vem em seguida não pode virar um segundo PUT.
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:30');
      sair(comp, ABERTO, 'ent1');

      expect(apiPut).toHaveBeenCalledTimes(1);
    });

    it('regravar o valor que já está gravado não custa requisição', () => {
      const comp = criarCarregado();
      editar(comp, CORRIGIDO, 'ent1');

      digitar(comp, CORRIGIDO, 'ent1', '08:00');   // idêntico à correção existente

      expect(apiPut).not.toHaveBeenCalled();
      expect(comp.editandoCelula('2026-08-04', 'ent1')).toBe(false);
    });

    it('digitar exatamente o horário que já está à vista não vira requisição', () => {
      // Vale tanto para o que veio da folha quanto para o que ele já corrigiu: nos dois casos a
      // célula continuaria mostrando a mesma coisa.
      const comp = criarCarregado();
      editar(comp, ABERTO, 'sai1');

      digitar(comp, ABERTO, 'sai1', '12:00');   // é o que a folha imprimiu nessa célula

      expect(apiPut).not.toHaveBeenCalled();
    });

    it('voltar ao horário que a folha imprimiu, num campo corrigido, grava a volta', () => {
      // A célula corrigida mostra 08:00 sobre um 07:45 impresso: digitar 07:45 é desfazer a
      // correção daquela batida, e isso o servidor precisa saber.
      const comp = criarCarregado();
      editar(comp, CORRIGIDO, 'ent1');

      digitar(comp, CORRIGIDO, 'ent1', '08:12');   // o valor original da folha naquele dia

      expect(apiPut).toHaveBeenCalledWith(URL_CELULA, { data: '2026-08-04', campo: 'ent1', valor: '08:12' });
    });

    it('render: "Editar horário" troca a célula pelo campo, já preenchido', () => {
      const fixture = renderizar();
      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();

      itemMenu(fixture, 'Editar horário').click();
      fixture.detectChanges();

      const campo = fixture.debugElement.query(By.css('.vista-desktop input.cel-input'));
      expect(campo).not.toBeNull();
      expect((campo.nativeElement as HTMLInputElement).value).toBe('08:00');
      expect(popover(fixture)).toBeNull();
    });

    it('render: digitar os quatro dígitos grava o horário mascarado', () => {
      const fixture = renderizar();
      const campo = abrirCampo(fixture, ABERTO, 0);

      campo.value = '0930';
      campo.dispatchEvent(new InputEvent('input', { inputType: 'insertText', bubbles: true }));
      fixture.detectChanges();

      expect(apiPut).toHaveBeenCalledWith(URL_CELULA, { data: '2026-08-03', campo: 'ent1', valor: '09:30' });
      expect(fixture.debugElement.query(By.css('.vista-desktop input.cel-input'))).toBeNull();
    });

    it('render: sair do campo com dois dígitos não grava', () => {
      const fixture = renderizar();
      const campo = abrirCampo(fixture, ABERTO, 0);

      campo.value = '09';
      campo.dispatchEvent(new InputEvent('input', { inputType: 'insertText', bubbles: true }));
      campo.dispatchEvent(new Event('blur'));
      fixture.detectChanges();

      expect(apiPut).not.toHaveBeenCalled();
      expect(fixture.debugElement.query(By.css('.vista-desktop input.cel-input'))).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O corpo enviado — só o que a rota precisa
  // ═══════════════════════════════════════════════════════════════════
  describe('corpo das requisições', () => {
    it('a gravação de uma batida manda exatamente data, campo e valor', () => {
      const comp = criarCarregado();
      editar(comp, ABERTO, 'ent2');
      digitar(comp, ABERTO, 'ent2', '13:15');

      const [url, body] = apiPut.mock.calls[0];
      expect(url).toBe(URL_CELULA);
      expect(body).toEqual({ data: '2026-08-03', campo: 'ent2', valor: '13:15' });
      expect(Object.keys(body)).toEqual(['data', 'campo', 'valor']);
    });

    it('a declaração de ocorrência manda exatamente data e tipo', () => {
      const comp = criarCarregado();
      abrir(comp, ABERTO);
      comp.declarar(comp.tipos()[0]);

      const [url, body] = apiPut.mock.calls[0];
      expect(url).toBe(URL_TIPO);
      expect(body).toEqual({ data: '2026-08-03', tipo_id: 'tp-banco' });
      expect(Object.keys(body)).toEqual(['data', 'tipo_id']);
    });

    it('a limpeza é só a URL com a data — sem corpo', () => {
      const comp = criarCarregado();
      abrir(comp, CORRIGIDO);
      comp.limpar();

      expect(apiDelete).toHaveBeenCalledWith('/api/ponto/folha/pag-1/retificacoes/2026-08-04');
      expect(apiDelete.mock.calls[0]).toHaveLength(1);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Declarar ocorrência — o dia inteiro sob um nome
  // ═══════════════════════════════════════════════════════════════════
  describe('declarar ocorrência', () => {
    it('escolher o tipo grava e a resposta vira a faixa do dia', () => {
      const comp = criarCarregado();
      abrir(comp, ABERTO);

      comp.declarar(comp.tipos()[1]);

      expect(apiPut).toHaveBeenCalledWith(URL_TIPO, { data: '2026-08-03', tipo_id: 'tp-atestado' });
      expect(comp.menu()).toBeNull();
      expect(linha(comp, ABERTO).faixa).toEqual({ texto: 'Atestado', declarada: true });
      expect(linha(comp, ABERTO).corrigido).toBe(true);
    });

    it('a ocorrência declarada apaga os horários corrigidos daquele dia', () => {
      const comp = criarCarregado();
      abrir(comp, CORRIGIDO);

      comp.declarar(comp.tipos()[0]);

      expect(linha(comp, CORRIGIDO).faixa).toEqual({ texto: 'Banco de horas', declarada: true });
      expect(linha(comp, CORRIGIDO).celulas.every(c => !c.corrigido)).toBe(true);
    });

    it('sem menu aberto não há o que declarar', () => {
      const comp = criarCarregado();
      comp.declarar(comp.tipos()[0]);

      expect(apiPut).not.toHaveBeenCalled();
    });

    it('render: escolher o tipo na lista grava e a linha vira faixa', () => {
      const fixture = renderizar();
      chipDe(fixture, ABERTO, 0).click();
      fixture.detectChanges();

      itemMenu(fixture, 'Banco de horas').click();
      fixture.detectChanges();

      expect(apiPut).toHaveBeenCalledWith(URL_TIPO, { data: '2026-08-03', tipo_id: 'tp-banco' });
      const faixa = fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[ABERTO]
        .query(By.css('td.cel-faixa'));
      expect(faixa.nativeElement.textContent.trim()).toBe('Banco de horas');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Limpar — o dia volta a valer o que a folha trouxe
  // ═══════════════════════════════════════════════════════════════════
  describe('limpar a retificação', () => {
    it('o DELETE leva a data e a correção some da tela', () => {
      const comp = criarCarregado();
      abrir(comp, CORRIGIDO);

      comp.limpar();

      expect(apiDelete).toHaveBeenCalledWith('/api/ponto/folha/pag-1/retificacoes/2026-08-04');
      expect(comp.menu()).toBeNull();
      expect(linha(comp, CORRIGIDO).corrigido).toBe(false);
      expect(celula(comp, CORRIGIDO, 'ent1')).toMatchObject({ valor: '08:12', corrigido: false });
    });

    it('limpar um dia não mexe na correção dos outros', () => {
      const comp = criarCarregado();
      abrir(comp, CORRIGIDO);

      comp.limpar();

      expect(linha(comp, FECHADO).corrigido).toBe(true);
    });

    it('limpar a ocorrência declarada devolve as batidas da folha', () => {
      janelaCom({
        retificacoes: [{ id: 'ret-3', data: '2026-08-03', tipo_id: 'tp-banco', tipo_nome: 'Banco de horas' }],
      });
      const comp = criarCarregado();
      abrir(comp, ABERTO);

      comp.limpar();

      expect(linha(comp, ABERTO).faixa).toBeNull();
      expect(linha(comp, ABERTO).celulas.map(c => c.valor)).toEqual(['08:00', '12:00', '13:00', '17:00']);
    });

    it('sem menu aberto não há o que limpar', () => {
      const comp = criarCarregado();
      comp.limpar();

      expect(apiDelete).not.toHaveBeenCalled();
    });

    it('render: "Limpar retificação" apaga a marca de editada da célula', () => {
      const fixture = renderizar();
      chipDe(fixture, CORRIGIDO, 0).click();
      fixture.detectChanges();

      itemMenu(fixture, 'Limpar retificação').click();
      fixture.detectChanges();

      const chip = chipDe(fixture, CORRIGIDO, 0);
      expect(chip.classList.contains('chip-editado')).toBe(false);
      expect(chip.textContent).toContain('08:12');
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Erro de gravação — o toast avisa e a tela não inventa valor
  // ═══════════════════════════════════════════════════════════════════
  describe('erro de gravação', () => {
    it('a mensagem do backend vai ao toast e a célula continua com o valor de antes', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(throwError(() => ({ error: { message: 'A folha não aceita mais correções.' } })));
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:30');

      expect(toastError).toHaveBeenCalledWith('A folha não aceita mais correções.');
      expect(celula(comp, ABERTO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: false });
      expect(comp.erro()).toBe('');            // o canal da carga não é o da gravação
      expect(comp.salvandoNoDia('2026-08-03')).toBe(false);
    });

    it('erro sem corpo: fallback', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(throwError(() => new Error('rede')));
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:30');

      expect(toastError).toHaveBeenCalledWith('Não foi possível salvar a correção.');
    });

    it('a declaração que falha não pinta faixa nenhuma', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(throwError(() => ({ error: { message: 'Tipo indisponível.' } })));
      abrir(comp, ABERTO);

      comp.declarar(comp.tipos()[0]);

      expect(toastError).toHaveBeenCalledWith('Tipo indisponível.');
      expect(linha(comp, ABERTO).faixa).toBeNull();
    });

    it('a limpeza que falha mantém a correção na tela', () => {
      const comp = criarCarregado();
      apiDelete.mockReturnValue(throwError(() => ({ error: { message: 'Fora do prazo.' } })));
      abrir(comp, CORRIGIDO);

      comp.limpar();

      expect(toastError).toHaveBeenCalledWith('Fora do prazo.');
      expect(celula(comp, CORRIGIDO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: true });
    });

    it('a resposta sem correção no corpo não apaga o que estava lá', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(of({ ok: true }));
      editar(comp, CORRIGIDO, 'sai1');

      digitar(comp, CORRIGIDO, 'sai1', '12:30');

      expect(celula(comp, CORRIGIDO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: true });
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Fila — uma gravação por dia
  // ═══════════════════════════════════════════════════════════════════
  describe('fila de gravação', () => {
    it('com a gravação em voo, o segundo gesto do mesmo dia não vira requisição', () => {
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      editar(comp, ABERTO, 'ent1');

      digitar(comp, ABERTO, 'ent1', '09:30');
      expect(comp.salvandoNoDia('2026-08-03')).toBe(true);

      digitar(comp, ABERTO, 'ent1', '09:31');   // clique impaciente enquanto a 1ª não volta
      expect(apiPut).toHaveBeenCalledTimes(1);

      emVoo.next(ecoCelula({ data: '2026-08-03', campo: 'ent1', valor: '09:30' }));
      expect(comp.salvandoNoDia('2026-08-03')).toBe(false);
    });

    it('a espera de um dia não trava os outros: o gesto em outro dia grava na hora', () => {
      // A fila é por DIA. Travar a folha inteira faria o funcionário esperar por uma resposta que
      // não tem nada a ver com a batida que ele está corrigindo agora.
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:30');

      editar(comp, STATUS, 'ent1');
      digitar(comp, STATUS, 'ent1', '08:00');

      expect(apiPut).toHaveBeenCalledTimes(2);
      expect(comp.salvandoNoDia('2026-08-03')).toBe(true);
      expect(comp.salvandoNoDia('2026-08-05')).toBe(true);
      expect(toastError).not.toHaveBeenCalled();
    });

    it('render: os chips do dia em gravação ficam desabilitados', () => {
      const fixture = renderizar();
      apiPut.mockReturnValue(new Subject<any>());
      const campo = abrirCampo(fixture, ABERTO, 0);

      campo.value = '0930';
      campo.dispatchEvent(new InputEvent('input', { inputType: 'insertText', bubbles: true }));
      fixture.detectChanges();

      const chips = fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[ABERTO]
        .queryAll(By.css('td.cel-hora button.chip'));
      expect(chips.every(c => (c.nativeElement as HTMLButtonElement).disabled)).toBe(true);

      const outroDia = fixture.debugElement
        .queryAll(By.css('.vista-desktop tbody tr'))[STATUS]
        .queryAll(By.css('td.cel-hora button.chip'));
      expect(outroDia.every(c => (c.nativeElement as HTMLButtonElement).disabled)).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Recência — a resposta atrasada não manda na tela nova
  // ═══════════════════════════════════════════════════════════════════
  describe('recência das respostas', () => {
    it('a gravação que volta depois de a janela recarregar não altera a tela', () => {
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:30');

      comp.recarregarJanela();
      emVoo.next(ecoCelula({ data: '2026-08-03', campo: 'ent1', valor: '09:30' }));

      expect(celula(comp, ABERTO, 'ent1')).toMatchObject({ valor: '08:00', corrigido: false });
    });

    it('o erro de uma gravação obsoleta não pinta toast na tela nova', () => {
      const comp = criarCarregado();
      const emVoo = new Subject<any>();
      apiPut.mockReturnValue(emVoo);
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:30');

      comp.recarregarJanela();
      emVoo.error({ error: { message: 'falhou' } });

      expect(toastError).not.toHaveBeenCalled();
    });

    it('recarregar a janela destrava a tela: nada fica preso em "gravando"', () => {
      const comp = criarCarregado();
      apiPut.mockReturnValue(new Subject<any>());
      editar(comp, ABERTO, 'ent1');
      digitar(comp, ABERTO, 'ent1', '09:30');
      expect(comp.salvandoNoDia('2026-08-03')).toBe(true);

      comp.recarregarJanela();

      expect(comp.salvandoNoDia('2026-08-03')).toBe(false);
      expect(comp.menu()).toBeNull();
      expect(comp.editandoNoDia('2026-08-03')).toBe(false);
    });

    it('a carga de janela atrasada não sobrescreve a que já está na tela', () => {
      const lenta = new Subject<any>();
      respostas.janela = () => lenta;
      const comp = criarCarregado();

      respostas.janela = () => of({ data: { ...structuredClone(JANELA), retificacoes: [] } });
      comp.recarregarJanela();                       // a 2ª carga responde na hora
      expect(linha(comp, CORRIGIDO).corrigido).toBe(false);

      lenta.next({ data: structuredClone(JANELA) }); // a 1ª chega atrasada
      expect(linha(comp, CORRIGIDO).corrigido).toBe(false);
    });

    it('o erro de uma carga de janela atrasada não trava a tela que já carregou', () => {
      const lenta = new Subject<any>();
      respostas.janela = () => lenta;
      const comp = criarCarregado();

      respostas.janela = () => of({ data: structuredClone(JANELA) });
      comp.recarregarJanela();
      lenta.error({ error: { message: 'timeout' } });

      expect(comp.erroJanela()).toBe('');
      expect(linha(comp, ABERTO).editavel).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // Auxiliares de render
  // ═══════════════════════════════════════════════════════════════════

  /** Componente renderizado com as duas cargas resolvidas (as respostas do mock são síncronas). */
  function renderizar(): ComponentFixture<PontoRetificarComponent> {
    const fixture = TestBed.createComponent(PontoRetificarComponent);
    fixture.detectChanges();   // ngOnInit + render
    return fixture;
  }

  /** A célula (linha, batida) da tabela, como o usuário a clica. */
  function chipDe(f: ComponentFixture<PontoRetificarComponent>, idx: number, campoIdx: number): HTMLElement {
    return f.debugElement
      .queryAll(By.css('.vista-desktop tbody tr'))[idx]
      .queryAll(By.css('td.cel-hora .chip'))[campoIdx].nativeElement as HTMLElement;
  }

  const popover = (f: ComponentFixture<PontoRetificarComponent>) => f.debugElement.query(By.css('.popover'));

  const tituloMenu = (f: ComponentFixture<PontoRetificarComponent>) =>
    (f.debugElement.query(By.css('.popover .pop-titulo')).nativeElement as HTMLElement).textContent?.trim();

  const itensMenu = (f: ComponentFixture<PontoRetificarComponent>) =>
    f.debugElement.queryAll(By.css('.popover .pop-item'))
      .map(b => (b.nativeElement as HTMLElement).textContent?.trim());

  /** Opção do menu, pelo rótulo. */
  function itemMenu(f: ComponentFixture<PontoRetificarComponent>, rotulo: string): HTMLButtonElement {
    return f.debugElement.queryAll(By.css('.popover .pop-item'))
      .find(b => (b.nativeElement as HTMLElement).textContent?.trim() === rotulo)!
      .nativeElement as HTMLButtonElement;
  }

  /** Abre a célula em modo de digitação e devolve o campo renderizado. */
  function abrirCampo(f: ComponentFixture<PontoRetificarComponent>, idx: number, campoIdx: number): HTMLInputElement {
    chipDe(f, idx, campoIdx).click();
    f.detectChanges();
    itemMenu(f, 'Editar horário').click();
    f.detectChanges();
    return f.debugElement.query(By.css('.vista-desktop input.cel-input')).nativeElement as HTMLInputElement;
  }
});

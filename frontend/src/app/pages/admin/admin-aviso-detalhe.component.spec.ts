import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { AdminAvisoDetalheComponent } from './admin-aviso-detalhe.component';

/**
 * Render da página de detalhe do aviso (somente leitura), um cenário por TIPO + estados.
 * Cobre: card por seção (Identificação/Vigência/Destino/Mensagens) e tabela que varia por tipo
 * (Verificação com Local e sem pendentes; Escala com Plenário, pendentes e "(fora da escala atual)";
 * Agenda com "Função"/"Exibido em"; Pessoal com "Função", pendentes e "(não é destinatário)"; Grupo
 * com a lista de quem deu ciência ou de quem já viu), os rótulos "Ciência" vs "Exibido em" conforme a
 * exigência do cadastro, a linha de resumo e a ordenação (pendentes → cientes → fora do público), o
 * fallback de subtipo nulo (legado) e a distinção erro ≠ não encontrado.
 *
 * ApiService mockado por useValue (só o `get`); ActivatedRoute com o query param `id`; Router real
 * via provideRouter([]) porque o template usa RouterLink ("← Voltar").
 */
describe('AdminAvisoDetalheComponent — detalhe por tipo', () => {
  let apiGet: ReturnType<typeof vi.fn>;
  let idParam: string | null;
  let resposta: () => Observable<any>;

  const ok = (data: any) => () => of({ data });
  const falha = (err: unknown) => () => throwError(() => err);

  beforeEach(async () => {
    idParam = 'av-1';
    resposta = ok(verificacao());
    apiGet = vi.fn(() => resposta());

    await TestBed.configureTestingModule({
      imports: [AdminAvisoDetalheComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { get: apiGet } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: (k: string) => (k === 'id' ? idParam : null) } } } },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  async function render(): Promise<ComponentFixture<AdminAvisoDetalheComponent>> {
    const f = TestBed.createComponent(AdminAvisoDetalheComponent);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  const texto = (f: ComponentFixture<AdminAvisoDetalheComponent>) =>
    (f.debugElement.query(By.css('.detalhe-card')).nativeElement as HTMLElement).textContent ?? '';
  const tabela = (f: ComponentFixture<AdminAvisoDetalheComponent>) => f.debugElement.query(By.css('table.data-table'));
  const cabecalhos = (f: ComponentFixture<AdminAvisoDetalheComponent>) =>
    tabela(f).queryAll(By.css('thead th')).map(th => (th.nativeElement as HTMLElement).textContent?.trim());
  const linhasTexto = (f: ComponentFixture<AdminAvisoDetalheComponent>) =>
    tabela(f).queryAll(By.css('tbody tr')).map(tr => (tr.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ').trim() ?? '');
  const resumo = (f: ComponentFixture<AdminAvisoDetalheComponent>) => f.debugElement.query(By.css('.resumo'));

  // ── Fábricas de payload (o backend devolve {ok, data}) ──
  function base(over: Record<string, any>): Record<string, any> {
    return {
      id: 'av-1', numero: 42, tipo_label: 'X', subtipo: null,
      permanente: true, duracao_dias: null, exige_ciencia: false, manter_apos_ciencia: false, status: 'Ativo',
      criado_em: '2026-07-10T09:15:00', expira_em: null, criado_por: 'Ana Prado',
      mensagens: [{ ordem: 1, texto: 'Primeira mensagem' }], alvos: [], cientes: [],
      ...over,
    };
  }
  const verificacao = () => base({
    tipo: 'VERIFICACAO', categoria: 'AVISO', tipo_tabela: 'Verificação', subtipo: null, exige_ciencia: true,
    alvos: [{ alvo_tipo: 'SALA', descricao: 'Plenário 2' }, { alvo_tipo: 'SALA', descricao: 'Plenário 3' }],
    cientes: [
      { nome: 'Bruno', papel: 'Operador', sala_id: 2, sala_nome: 'Plenário 2', ciente_em: '2026-07-10T10:05:00' },
      { nome: 'Bruno', papel: 'Operador', sala_id: 3, sala_nome: 'Plenário 3', ciente_em: '2026-07-10T11:20:00' },
    ],
  });
  const escala = () => base({
    tipo: 'ESCALA', categoria: 'AVISO', tipo_tabela: 'Escala', subtipo: 'ESCALA',
    exige_ciencia: true, manter_apos_ciencia: true, status: 'Ativo',
    escala: { id: 7, data_inicio: '2026-07-14', data_fim: '2026-07-18', plenarios: [{ sala_id: 2, sala_nome: 'Plenário 2' }] },
    alvos: [{ alvo_tipo: 'SALA', descricao: 'Plenário 2' }],
    destinatarios: [
      { nome: 'Ana', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: '2026-07-15T08:00:00' },
      { nome: 'Carlos', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: null },
      { nome: 'Zé Antigo', papel: 'Operador', plenarios: [], ciente_em: '2026-07-14T09:00:00', fora_do_publico: true },
    ],
  });
  const agenda = () => base({
    tipo: 'AGENDA', categoria: 'COMUNICADO', tipo_tabela: 'Agenda', subtipo: 'AGENDA', status: '—',
    alvos: [{ alvo_tipo: 'TODOS', descricao: 'Todos' }],
    exibido_para: [
      { nome: 'Ana', papel: 'Operador', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T10:00:00' },
      { nome: 'Beto', papel: 'Técnico', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T11:00:00' },
    ],
  });
  const pessoal = () => base({
    tipo: 'PESSOAL', categoria: 'MENSAGEM', tipo_tabela: '', subtipo: 'PESSOAL',
    exige_ciencia: true, manter_apos_ciencia: false,
    alvos: [
      { alvo_tipo: 'OPERADOR', descricao: 'Ana' },
      { alvo_tipo: 'TECNICO', descricao: 'Beto' },
      { alvo_tipo: 'ADMIN', descricao: 'Carla' },
    ],
    destinatarios: [
      { nome: 'Ana', papel: 'Operador', ciente_em: '2026-07-10T10:00:00' },
      { nome: 'Beto', papel: 'Técnico', ciente_em: null },
      { nome: 'Estranho', papel: 'Operador', ciente_em: '2026-07-09T10:00:00', fora_do_publico: true },
    ],
  });
  const grupo = (over: Record<string, any> = {}) => base({
    tipo: 'GERAL', categoria: 'COMUNICADO', tipo_tabela: 'Operadores', subtipo: 'GRUPO_OPERADORES',
    alvos: [{ alvo_tipo: 'TODOS_OPERADORES', descricao: 'Todos os operadores' }],
    ...over,
  });

  // ═══ 1) Verificação ═══
  it('Verificação: rótulo, status com bolinha, coluna Local e ciências por sala (sem resumo)', async () => {
    resposta = ok(verificacao());
    const f = await render();

    expect(apiGet).toHaveBeenCalledWith('/api/admin/avisos/av-1/detalhe');
    expect(texto(f)).toContain('Verificação');
    expect(f.debugElement.query(By.css('.status-dot')).nativeElement.getAttribute('data-status')).toBe('Ativo');
    expect(texto(f)).toContain('1º Texto');
    expect(texto(f)).not.toContain('1º Aviso');

    expect(cabecalhos(f)).toEqual(['Destinatário', 'Local', 'Ciência (data)', 'Ciência (hora)']);
    const linhas = linhasTexto(f);
    expect(linhas).toHaveLength(2);
    expect(linhas[0]).toContain('Bruno');
    expect(linhas[0]).toContain('Plenário 2');
    expect(linhas[0]).toContain('10:05');
    expect(resumo(f)).toBeNull();   // público aberto → sem "X de Y"
  });

  // ═══ 2) Escala ═══
  it('Escala: vigência do período, coluna Plenário, resumo "1 de 2", ordenação pendente→ciente→fora com marcador', async () => {
    resposta = ok(escala());
    const f = await render();

    expect(texto(f)).toContain('Período da escala — 14/07/2026 a 18/07/2026');
    expect(texto(f)).toContain('Manter após ciência');
    expect(cabecalhos(f)).toEqual(['Destinatário', 'Plenário', 'Ciência (data)', 'Ciência (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('1 de 2 deram ciência');

    const linhas = linhasTexto(f);
    expect(linhas[0]).toContain('Carlos');       // pendente primeiro (alfabético)
    expect(linhas[0]).toContain('—');            // sem data
    expect(linhas[1]).toContain('Ana');          // depois o ciente
    expect(linhas[2]).toContain('Zé Antigo');    // fora do público por último
    expect(linhas[2]).toContain('(fora da escala atual)');
  });

  // ═══ 3) Agenda ═══
  it('Agenda: vigência de exibição única, "Função"/"Exibido em", resumo de exibições, status "—" sem bolinha e sem "Manter após ciência"', async () => {
    resposta = ok(agenda());
    const f = await render();

    expect(texto(f)).toContain('Exibição única por usuário');
    expect(texto(f)).not.toContain('Manter após ciência');
    expect(f.debugElement.query(By.css('.status-dot'))).toBeNull();   // "—" não tem bolinha
    expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Exibido em (data)', 'Exibido em (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('Exibido para 2 pessoas');
    const linhas = linhasTexto(f);
    expect(linhas[0]).toContain('Ana');
    expect(linhas[0]).toContain('Operador');
  });

  // ═══ 4) Pessoal ═══
  it('Pessoal: coluna "Função", resumo "1 de 2", pendente primeiro e ciência de não-destinatário marcada', async () => {
    resposta = ok(pessoal());
    const f = await render();

    expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Ciência (data)', 'Ciência (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('1 de 2 deram ciência');

    const linhas = linhasTexto(f);
    expect(linhas[0]).toContain('Beto');           // pendente primeiro
    expect(linhas[1]).toContain('Ana');            // ciente
    expect(linhas[2]).toContain('Estranho');       // fora do público por último
    expect(linhas[2]).toContain('(não é destinatário)');
  });

  // ═══ 5) Grupo (GERAL) sem ciência — tabela de exibição ═══
  it('Grupo (GERAL) sem ciência: lista quem já viu em "Exibido em" e não tem "Manter após ciência"', async () => {
    resposta = ok(grupo({
      exibido_para: [
        { nome: 'Ana', papel: 'Operador', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T10:00:00' },
        { nome: 'Beto', papel: 'Técnico', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T11:00:00' },
      ],
    }));
    const f = await render();

    expect(texto(f)).toContain('Exige ciência');
    expect(texto(f)).not.toContain('Manter após ciência');
    expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Exibido em (data)', 'Exibido em (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('Exibido para 2 pessoas');
    expect(linhasTexto(f)[0]).toContain('Ana');
    expect(linhasTexto(f)[0]).toContain('10:00');
  });

  it('Grupo (GERAL) sem ciência e ainda não exibido: tabela vazia diz "Ainda não exibido para ninguém."', async () => {
    resposta = ok(grupo({ exibido_para: [] }));
    const f = await render();
    expect(linhasTexto(f)[0]).toContain('Ainda não exibido para ninguém.');
  });

  // ═══ 6) Ciência escolhida no cadastro ═══
  describe('exigência de ciência do cadastro', () => {

    /** Valor do campo cujo <label> tem o texto pedido. */
    const campo = (f: ComponentFixture<AdminAvisoDetalheComponent>, label: string) =>
      f.debugElement.queryAll(By.css('.field'))
        .find(el => (el.nativeElement as HTMLElement).querySelector('label')?.textContent?.trim() === label)
        ?.query(By.css('.field-value'))?.nativeElement.textContent?.trim();

    it('a linha "Exige ciência" mostra Sim/Não conforme o cadastro', async () => {
      resposta = ok(escala());
      expect(campo(await render(), 'Exige ciência')).toBe('Sim');

      resposta = ok(grupo());
      expect(campo(await render(), 'Exige ciência')).toBe('Não');
    });

    it('Grupo COM ciência: ganha tabela com quem deu ciência (sem denominador) e o "Manter após ciência"', async () => {
      resposta = ok(grupo({
        exige_ciencia: true, manter_apos_ciencia: true,
        cientes: [
          { nome: 'Ana', papel: 'Operador', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T10:00:00' },
          { nome: 'Beto', papel: 'Técnico', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T11:00:00' },
        ],
      }));
      const f = await render();

      expect(texto(f)).toContain('Manter após ciência');
      expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Ciência (data)', 'Ciência (hora)']);
      expect(resumo(f).nativeElement.textContent).toContain('2 pessoas deram ciência');
      const linhas = linhasTexto(f);
      expect(linhas[0]).toContain('Ana');
      expect(linhas[0]).toContain('Operador');
      expect(linhas[0]).toContain('10:00');
      expect(linhas[1]).toContain('Beto');
    });

    it('Grupo COM ciência e ninguém ciente: tabela vazia diz "Nenhuma ciência registrada."', async () => {
      resposta = ok(grupo({ exige_ciencia: true, cientes: [] }));
      const f = await render();
      expect(linhasTexto(f)[0]).toContain('Nenhuma ciência registrada.');
    });

    it('Escala SEM ciência: destinatários listados com "Exibido em", sem "Manter" e com resumo de exibição', async () => {
      const semCiencia = escala();
      semCiencia['exige_ciencia'] = false;
      semCiencia['manter_apos_ciencia'] = false;
      semCiencia['destinatarios'] = [
        { nome: 'Ana', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: '2026-07-15T08:00:00' },
        { nome: 'Carlos', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: null },
      ];
      resposta = ok(semCiencia);
      const f = await render();

      expect(texto(f)).not.toContain('Manter após ciência');
      expect(cabecalhos(f)).toEqual(['Destinatário', 'Plenário', 'Exibido em (data)', 'Exibido em (hora)']);
      expect(resumo(f).nativeElement.textContent).toContain('Exibido para 1 de 2 destinatários');
      expect(linhasTexto(f)).toHaveLength(2);
      expect(linhasTexto(f)[0]).toContain('Carlos');   // ainda não viu → topo
      expect(linhasTexto(f)[1]).toContain('08:00');    // quem viu traz a hora da exibição
    });

    it('Pessoal SEM ciência: mesma regra — destinatários com a coluna "Exibido em"', async () => {
      const semCiencia = pessoal();
      semCiencia['exige_ciencia'] = false;
      semCiencia['destinatarios'] = [{ nome: 'Ana', papel: 'Operador', ciente_em: null }];
      resposta = ok(semCiencia);
      const f = await render();

      expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Exibido em (data)', 'Exibido em (hora)']);
      expect(resumo(f).nativeElement.textContent).toContain('Exibido para 0 de 1 destinatário');
    });
  });

  // ═══ Campo "Tipo": selo da categoria + contexto ═══
  describe('campo "Tipo" e títulos da tela', () => {
    /** Bloco do campo "Tipo" (selo + contexto). */
    const campoTipo = (f: ComponentFixture<AdminAvisoDetalheComponent>) =>
      f.debugElement.query(By.css('.col-tipo')).nativeElement as HTMLElement;

    it('a página se chama "Detalhe da Comunicação" e o campo é "Tipo"', async () => {
      resposta = ok(verificacao());
      const f = await render();
      expect(f.debugElement.query(By.css('h1')).nativeElement.textContent.trim()).toBe('Detalhe da Comunicação');
      expect(texto(f)).not.toContain('Tipo de Aviso');
    });

    it('selo da categoria com o contexto ao lado, e a cor no elemento do selo', async () => {
      resposta = ok(agenda());
      const f = await render();
      expect(campoTipo(f).querySelector('.cat-selo')?.textContent?.trim()).toBe('Comunicado');
      expect(campoTipo(f).textContent).toContain('Agenda');
      expect((f.debugElement.query(By.css('.col-tipo app-categoria-selo')).nativeElement as HTMLElement).className)
        .toBe('cat-comunicado');
    });

    it('Mensagem: só o selo, sem contexto ao lado', async () => {
      resposta = ok(pessoal());
      const f = await render();
      expect(campoTipo(f).textContent?.trim()).toBe('Mensagem');
    });

    // ═══ Fallback de subtipo nulo (legado) ═══
    it('legado PESSOAL sem subtipo: o backend já resolve como Mensagem sem contexto', async () => {
      resposta = ok(base({ tipo: 'PESSOAL', categoria: 'MENSAGEM', tipo_tabela: '', subtipo: null, destinatarios: [] }));
      const f = await render();
      expect(campoTipo(f).textContent?.trim()).toBe('Mensagem');
    });
  });

  // ═══ Estados: erro ≠ não encontrado ═══
  it('erro de rede/500: caixa app-erro-carga com retry (NÃO "não encontrado"); o retry re-pede o endpoint', async () => {
    resposta = falha({ status: 500 });
    const f = await render();

    const box = f.debugElement.query(By.directive(ErroCargaComponent));
    expect(box).not.toBeNull();
    expect(f.debugElement.query(By.css('.detalhe-card'))).toBeNull();
    expect((box.nativeElement as HTMLElement).textContent).not.toContain('não encontrad');
    expect(apiGet).toHaveBeenCalledTimes(1);

    resposta = ok(verificacao());
    (f.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
    await f.whenStable();
    f.detectChanges();
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(f.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    expect(f.debugElement.query(By.css('.detalhe-card'))).not.toBeNull();
  });

  it('404: "Comunicação não encontrada." SEM caixa de erro (canal de erro distinto)', async () => {
    resposta = falha({ status: 404 });
    const f = await render();
    expect(f.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Comunicação não encontrada.');
  });

  it('sem query param id: não chama a API e mostra "Comunicação não encontrada."', async () => {
    idParam = null;
    const f = await render();
    expect(apiGet).not.toHaveBeenCalled();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Comunicação não encontrada.');
  });
});

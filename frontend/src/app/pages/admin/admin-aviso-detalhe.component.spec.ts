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
 * SEM tabela), a linha de resumo e a ordenação (pendentes → cientes → fora do público), o fallback de
 * subtipo nulo (legado) e a distinção erro ≠ não encontrado.
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
      permanente: true, duracao_dias: null, manter_apos_ciencia: false, status: 'Ativo',
      criado_em: '2026-07-10T09:15:00', expira_em: null, criado_por: 'Ana Prado',
      mensagens: [{ ordem: 1, texto: 'Primeira mensagem' }], alvos: [], cientes: [],
      ...over,
    };
  }
  const verificacao = () => base({
    tipo: 'VERIFICACAO', tipo_tabela: 'Verificação', subtipo: null,
    alvos: [{ alvo_tipo: 'SALA', descricao: 'Plenário 2' }, { alvo_tipo: 'SALA', descricao: 'Plenário 3' }],
    cientes: [
      { nome: 'Bruno', papel: 'Operador', sala_id: 2, sala_nome: 'Plenário 2', ciente_em: '2026-07-10T10:05:00' },
      { nome: 'Bruno', papel: 'Operador', sala_id: 3, sala_nome: 'Plenário 3', ciente_em: '2026-07-10T11:20:00' },
    ],
  });
  const escala = () => base({
    tipo: 'ESCALA', tipo_tabela: 'Escala', subtipo: 'ESCALA', manter_apos_ciencia: true, status: 'Ativo',
    escala: { id: 7, data_inicio: '2026-07-14', data_fim: '2026-07-18', plenarios: [{ sala_id: 2, sala_nome: 'Plenário 2' }] },
    alvos: [{ alvo_tipo: 'SALA', descricao: 'Plenário 2' }],
    destinatarios: [
      { nome: 'Ana', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: '2026-07-15T08:00:00' },
      { nome: 'Carlos', papel: 'Operador', plenarios: ['Plenário 2'], ciente_em: null },
      { nome: 'Zé Antigo', papel: 'Operador', plenarios: [], ciente_em: '2026-07-14T09:00:00', fora_do_publico: true },
    ],
  });
  const agenda = () => base({
    tipo: 'AGENDA', tipo_tabela: 'Agenda', subtipo: 'AGENDA', status: '—',
    alvos: [{ alvo_tipo: 'TODOS', descricao: 'Todos' }],
    exibido_para: [
      { nome: 'Ana', papel: 'Operador', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T10:00:00' },
      { nome: 'Beto', papel: 'Técnico', sala_id: null, sala_nome: null, ciente_em: '2026-07-10T11:00:00' },
    ],
  });
  const pessoal = () => base({
    tipo: 'PESSOAL', tipo_tabela: 'Pessoal', subtipo: 'PESSOAL', manter_apos_ciencia: false,
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
  const grupo = () => base({
    tipo: 'GERAL', tipo_tabela: 'Operadores', subtipo: 'GRUPO_OPERADORES',
    alvos: [{ alvo_tipo: 'TODOS_OPERADORES', descricao: 'Todos os operadores' }],
  });

  // ═══ 1) Verificação ═══
  it('Verificação: rótulo, status com bolinha, chips de locais, coluna Local e ciências por sala (sem resumo)', async () => {
    resposta = ok(verificacao());
    const f = await render();

    expect(apiGet).toHaveBeenCalledWith('/api/admin/avisos/av-1/detalhe');
    expect(texto(f)).toContain('Verificação');
    expect(f.debugElement.query(By.css('.status-dot')).nativeElement.getAttribute('data-status')).toBe('Ativo');
    expect(f.debugElement.queryAll(By.css('.chip')).map(c => (c.nativeElement as HTMLElement).textContent?.trim()))
      .toEqual(['Plenário 2', 'Plenário 3']);
    expect(texto(f)).toContain('1º Aviso');

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
    expect(texto(f)).toContain('trocas de operador mudam quem vê');   // nota do Destino
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
    expect(texto(f)).toContain('Todos os operadores e técnicos');
    expect(texto(f)).not.toContain('Manter após ciência');
    expect(f.debugElement.query(By.css('.status-dot'))).toBeNull();   // "—" não tem bolinha
    expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Exibido em (data)', 'Exibido em (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('Exibido para 2 pessoas');
    const linhas = linhasTexto(f);
    expect(linhas[0]).toContain('Ana');
    expect(linhas[0]).toContain('Operador');
  });

  // ═══ 4) Pessoal ═══
  it('Pessoal: destino por papel, coluna "Função", resumo "1 de 2", pendente primeiro e ciência de não-destinatário marcada', async () => {
    resposta = ok(pessoal());
    const f = await render();

    expect(texto(f)).toContain('Operadores');
    expect(texto(f)).toContain('Técnicos');
    expect(texto(f)).toContain('Administradores');
    expect(cabecalhos(f)).toEqual(['Destinatário', 'Função', 'Ciência (data)', 'Ciência (hora)']);
    expect(resumo(f).nativeElement.textContent).toContain('1 de 2 deram ciência');

    const linhas = linhasTexto(f);
    expect(linhas[0]).toContain('Beto');           // pendente primeiro
    expect(linhas[1]).toContain('Ana');            // ciente
    expect(linhas[2]).toContain('Estranho');       // fora do público por último
    expect(linhas[2]).toContain('(não é destinatário)');
  });

  // ═══ 5) Grupo (GERAL) — sem tabela ═══
  it('Grupo (GERAL): mostra o coletivo, termina no card — sem tabela e sem "Manter após ciência"', async () => {
    resposta = ok(grupo());
    const f = await render();

    expect(texto(f)).toContain('Todos os operadores');
    expect(texto(f)).not.toContain('Manter após ciência');
    expect(tabela(f)).toBeNull();
    expect(f.debugElement.query(By.css('.tabela-ciencia'))).toBeNull();
  });

  // ═══ Fallback de subtipo nulo (legado) ═══
  it('legado PESSOAL sem subtipo: exibe o tipo_tabela que o backend resolveu ("Pessoal")', async () => {
    resposta = ok(base({ tipo: 'PESSOAL', tipo_tabela: 'Pessoal', subtipo: null, destinatarios: [] }));
    const f = await render();
    expect(texto(f)).toContain('Pessoal');
  });

  // ═══ Estados: erro ≠ não encontrado ═══
  it('erro de rede/500: caixa app-erro-carga com retry (NÃO "não encontrado"); o retry re-pede o endpoint', async () => {
    resposta = falha({ status: 500 });
    const f = await render();

    const box = f.debugElement.query(By.directive(ErroCargaComponent));
    expect(box).not.toBeNull();
    expect(f.debugElement.query(By.css('.detalhe-card'))).toBeNull();
    expect((box.nativeElement as HTMLElement).textContent).not.toContain('não encontrado');
    expect(apiGet).toHaveBeenCalledTimes(1);

    resposta = ok(verificacao());
    (f.debugElement.query(By.css('app-erro-carga button')).nativeElement as HTMLButtonElement).click();
    await f.whenStable();
    f.detectChanges();
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(f.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    expect(f.debugElement.query(By.css('.detalhe-card'))).not.toBeNull();
  });

  it('404: "Aviso não encontrado." SEM caixa de erro (canal de erro distinto)', async () => {
    resposta = falha({ status: 404 });
    const f = await render();
    expect(f.debugElement.query(By.directive(ErroCargaComponent))).toBeNull();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Aviso não encontrado.');
  });

  it('sem query param id: não chama a API e mostra "Aviso não encontrado."', async () => {
    idParam = null;
    const f = await render();
    expect(apiGet).not.toHaveBeenCalled();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Aviso não encontrado.');
  });
});

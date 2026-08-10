import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoPessoalFormComponent } from './aviso-pessoal-form.component';

/** Ordenadas por nome pt-BR (como vêm do backend); tipos misturados de propósito. */
const PESSOAS = [
  { id: 'tec1', nome: 'Ana', tipo: 'TECNICO' },
  { id: 'op1', nome: 'Bruno', tipo: 'OPERADOR' },
  { id: 'adm1', nome: 'Carlos', tipo: 'ADMINISTRADOR' },
  { id: 'op2', nome: 'Diana', tipo: 'OPERADOR' },
];

describe('AvisoPessoalFormComponent', () => {
  let fixture: ComponentFixture<AvisoPessoalFormComponent>;
  let comp: AvisoPessoalFormComponent;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let respostaPessoas: () => Observable<any>;

  async function montar() {
    apiGet = vi.fn(() => respostaPessoas());
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    await TestBed.configureTestingModule({
      imports: [AvisoPessoalFormComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AvisoPessoalFormComponent);
    comp = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => { respostaPessoas = () => of({ data: PESSOAS }); });
  afterEach(() => vi.restoreAllMocks());

  it('carrega pessoas e monta as opções agrupadas na ordem Operadores/Técnicos/Administradores', async () => {
    await montar();
    expect(apiGet).toHaveBeenCalledWith('/api/admin/avisos/pessoas');
    const opts = comp.pessoaOptions();
    // reordenado por tipo (op, op, tec, adm), preservando a ordem alfabética dentro de cada seção
    expect(opts.map(o => o.group)).toEqual(['Operadores', 'Operadores', 'Técnicos', 'Administradores']);
    expect(opts.filter(o => o.group === 'Operadores').map(o => o.label)).toEqual(['Bruno', 'Diana']);
  });

  it('modo pessoas: POST PESSOAL/alvo PESSOAS com as listas separadas por tipo', async () => {
    await montar();
    let emitido = false;
    comp.cadastrado.subscribe(() => (emitido = true));
    comp.modo = 'pessoas';
    comp.selectedPessoas = ['op1', 'tec1', 'adm1'];
    comp.mensagens = ['Aviso pessoal'];
    comp.manterAposCiencia = true;

    comp.onSubmit();

    expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', {
      tipo: 'PESSOAL', alvo_tipo: 'PESSOAS',
      operador_ids: ['op1'], tecnico_ids: ['tec1'], admin_ids: ['adm1'],
      permanente: true, duracao_dias: null,
      exige_ciencia: true, manter_apos_ciencia: true, mensagens: ['Aviso pessoal'],
    });
    expect(emitido).toBe(true);
    // Reset: o cadastro seguinte começa limpo, com o default de ciência do modo e sem o "manter".
    expect(comp.selectedPessoas).toEqual([]);
    expect(comp.exigeCiencia).toBe(true);
    expect(comp.manterAposCiencia).toBe(false);
  });

  it('modo pessoas sem destinatário é rejeitado', async () => {
    await montar();
    comp.modo = 'pessoas';
    comp.selectedPessoas = [];
    comp.mensagens = ['x'];
    comp.onSubmit();
    expect(comp.errorMsg()).toBe('Selecione ao menos um destinatário.');
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('modo grupo: POST GERAL com o coletivo escolhido, sem ciência por default', async () => {
    await montar();
    comp.onModoChange('grupo');
    comp.grupo = 'TODOS_ADMIN';
    comp.mensagens = ['Comunicado'];

    comp.onSubmit();

    expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', {
      tipo: 'GERAL', alvo_tipo: 'TODOS_ADMIN', permanente: true, duracao_dias: null,
      exige_ciencia: false, manter_apos_ciencia: false, mensagens: ['Comunicado'],
    });
  });

  it('modo grupo COM ciência: o comunicado pode exigir ciência e ganhar o "manter"', async () => {
    await montar();
    comp.onModoChange('grupo');
    comp.grupo = 'TODOS';
    comp.mensagens = ['Leia e confirme'];
    comp.onCienciaChange(true);
    comp.manterAposCiencia = true;

    comp.onSubmit();

    expect(apiPost.mock.calls[0][1]).toMatchObject({
      tipo: 'GERAL', exige_ciencia: true, manter_apos_ciencia: true,
    });
  });

  it('não-permanente exige duração entre 1 e 30', async () => {
    await montar();
    comp.modo = 'grupo';
    comp.grupo = 'TODOS';
    comp.mensagens = ['x'];
    comp.permanente = false;
    comp.duracaoDias = 40;
    comp.onSubmit();
    expect(comp.errorMsg()).toBe('A duração deve estar entre 1 e 30 dias.');
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('o multi-select some no modo grupo, dando lugar ao select do coletivo', async () => {
    await montar();
    expect(fixture.nativeElement.querySelector('app-multi-select-dropdown')).not.toBeNull();

    comp.onModoChange('grupo');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-multi-select-dropdown')).toBeNull();
    expect(fixture.nativeElement.querySelector('select[name="grupo"]')).not.toBeNull();
  });

  it('default de ciência por modo: marcado em pessoas, desmarcado em grupo (e zera o "manter")', async () => {
    await montar();
    expect(comp.exigeCiencia).toBe(true);

    comp.manterAposCiencia = true;
    comp.onModoChange('grupo');
    expect(comp.exigeCiencia).toBe(false);
    expect(comp.manterAposCiencia).toBe(false);   // sem ciência, o manter é zerado

    comp.onModoChange('pessoas');
    expect(comp.exigeCiencia).toBe(true);
  });

  it('o "manter" só existe com a ciência ligada', async () => {
    await montar();
    expect(fixture.nativeElement.querySelector('input[name="manter"]')).not.toBeNull();

    comp.onCienciaChange(false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[name="manter"]')).toBeNull();
  });

  it('pelos cliques do admin: trocar para grupo desmarca a ciência e some o "manter"; remarcar a ciência o traz de volta', async () => {
    await montar();
    const radio = (v: string) => fixture.nativeElement.querySelector(`input[name="modo"][value="${v}"]`) as HTMLInputElement;
    const check = (n: string) => fixture.nativeElement.querySelector(`input[name="${n}"]`) as HTMLInputElement;

    check('ciencia').click();          // desmarca a ciência no modo pessoas
    fixture.detectChanges();
    expect(comp.exigeCiencia).toBe(false);
    expect(check('manter')).toBeNull();

    radio('grupo').click();            // grupo: default desmarcado, segue sem "manter"
    fixture.detectChanges();
    expect(comp.modo).toBe('grupo');
    expect(comp.exigeCiencia).toBe(false);
    expect(check('manter')).toBeNull();

    check('ciencia').click();          // comunicado ao grupo passa a exigir ciência
    fixture.detectChanges();
    expect(comp.exigeCiencia).toBe(true);
    expect(check('manter')).not.toBeNull();

    radio('pessoas').click();          // voltar para pessoas restaura o default marcado
    fixture.detectChanges();
    expect(comp.exigeCiencia).toBe(true);
    expect(check('manter')).not.toBeNull();
  });

  it('o botão e o "manter" nomeiam a categoria do destinatário: Mensagem para pessoas, Comunicado para grupo', async () => {
    await montar();
    const botao = () => fixture.nativeElement.querySelector('.painel-actions button') as HTMLButtonElement;
    const labelManter = () => fixture.nativeElement.querySelector('.check-sub')?.textContent?.trim();

    expect(botao().textContent).toContain('Cadastrar Mensagem');
    expect(labelManter()).toContain('Manter mensagem após ciência');

    (fixture.nativeElement.querySelector('input[name="modo"][value="grupo"]') as HTMLInputElement).click();
    fixture.detectChanges();
    await fixture.whenStable();   // o ngModel só desmarca a ciência do modo grupo na microtask
    (fixture.nativeElement.querySelector('input[name="ciencia"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(botao().textContent).toContain('Cadastrar Comunicado');
    expect(labelManter()).toContain('Manter comunicado após ciência');
  });

  it('fail-closed no modo pessoas: erro ao carregar pessoas bloqueia o envio', async () => {
    respostaPessoas = () => throwError(() => ({ status: 500, error: { error: 'x' } }));
    await montar();
    expect(comp.pessoasIndisponiveis()).toBe(true);

    comp.modo = 'pessoas';
    comp.selectedPessoas = ['op1'];
    comp.mensagens = ['x'];
    comp.onSubmit();
    expect(apiPost).not.toHaveBeenCalled();
  });
});

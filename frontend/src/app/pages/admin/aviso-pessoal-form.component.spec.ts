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
      permanente: true, duracao_dias: null, manter_apos_ciencia: true, mensagens: ['Aviso pessoal'],
    });
    expect(emitido).toBe(true);
    expect(comp.selectedPessoas).toEqual([]);   // reset
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

  it('modo grupo: POST GERAL com o coletivo escolhido e SEM manter_apos_ciencia', async () => {
    await montar();
    comp.modo = 'grupo';
    comp.grupo = 'TODOS_ADMIN';
    comp.mensagens = ['Comunicado'];

    comp.onSubmit();

    expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', {
      tipo: 'GERAL', alvo_tipo: 'TODOS_ADMIN', permanente: true, duracao_dias: null, mensagens: ['Comunicado'],
    });
    // o payload de grupo não carrega manter_apos_ciencia
    expect(apiPost.mock.calls[0][1]).not.toHaveProperty('manter_apos_ciencia');
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

  it('checkbox "Manter" só aparece no modo pessoas; o multi-select some no modo grupo', async () => {
    await montar();
    expect(fixture.nativeElement.querySelector('.check-opt')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-multi-select-dropdown')).not.toBeNull();

    comp.modo = 'grupo';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.check-opt')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-multi-select-dropdown')).toBeNull();
    expect(fixture.nativeElement.querySelector('select[name="grupo"]')).not.toBeNull();
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

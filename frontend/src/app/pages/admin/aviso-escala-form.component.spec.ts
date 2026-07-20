import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoEscalaFormComponent } from './aviso-escala-form.component';

const ESCALAS = [
  { id: 1, data_inicio: '2026-07-20', data_fim: '2026-07-24', cadastro_numero: null,
    plenarios: [{ sala_id: 2, sala_nome: 'Plenário 2' }, { sala_id: 3, sala_nome: 'Plenário 3' }] },
  { id: 2, data_inicio: '2026-07-27', data_fim: '2026-07-31', cadastro_numero: 9,
    plenarios: [{ sala_id: 2, sala_nome: 'Plenário 2' }] },
];

describe('AvisoEscalaFormComponent', () => {
  let fixture: ComponentFixture<AvisoEscalaFormComponent>;
  let comp: AvisoEscalaFormComponent;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  /** Resposta corrente do GET das escalas — trocável para o teste de fail-closed. */
  let respostaEscalas: () => Observable<any>;

  async function montar() {
    apiGet = vi.fn(() => respostaEscalas());
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    await TestBed.configureTestingModule({
      imports: [AvisoEscalaFormComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AvisoEscalaFormComponent);
    comp = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => { respostaEscalas = () => of({ data: ESCALAS }); });
  afterEach(() => vi.restoreAllMocks());

  it('carrega as escalas no init e destrava o envio (fail-closed liberado)', async () => {
    await montar();
    expect(apiGet).toHaveBeenCalledWith('/api/admin/avisos/escalas-disponiveis');
    expect(comp.escalas().length).toBe(2);
    expect(comp.escalasIndisponiveis()).toBe(false);
  });

  it('plenarioOptions deriva da escala selecionada; trocar de escala zera os plenários', async () => {
    await montar();
    comp.onEscalaChange(1);
    expect(comp.plenarioOptions().map(o => o.label)).toEqual(['Plenário 2', 'Plenário 3']);
    comp.selectedPlenarios = ['2', '3'];

    comp.onEscalaChange(2);
    expect(comp.selectedPlenarios).toEqual([]);
    expect(comp.plenarioOptions().map(o => o.id)).toEqual(['2']);
  });

  it('onSubmit válido: POST tipo ESCALA (escala_id + sala_ids numéricos + manter), reset e emite cadastrado', async () => {
    await montar();
    let emitido = false;
    comp.cadastrado.subscribe(() => (emitido = true));
    comp.escalaId.set(1);
    comp.selectedPlenarios = ['2', '3'];
    comp.mensagens = ['Confira sua escala'];
    comp.manterAposCiencia = true;

    comp.onSubmit();

    expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', {
      tipo: 'ESCALA', escala_id: 1, sala_ids: [2, 3], manter_apos_ciencia: true, mensagens: ['Confira sua escala'],
    });
    expect(emitido).toBe(true);
    expect(comp.escalaId()).toBeNull();          // reset
    expect(apiGet).toHaveBeenCalledTimes(2);   // recarrega escalas (a usada passa a aparecer travada)
  });

  it('validações: sem escala, sem plenário e mensagem em branco não emitem POST', async () => {
    await montar();
    comp.onSubmit();
    expect(comp.errorMsg()).toBe('Selecione a escala do aviso.');

    comp.escalaId.set(1);
    comp.onSubmit();
    expect(comp.errorMsg()).toBe('Selecione ao menos um local.');

    comp.selectedPlenarios = ['2'];
    comp.mensagens = ['  '];
    comp.onSubmit();
    expect(comp.errorMsg()).toBe('Preencha todas as mensagens.');

    expect(apiPost).not.toHaveBeenCalled();
  });

  it('fail-closed: erro ao carregar escalas bloqueia o envio (guard do onSubmit)', async () => {
    respostaEscalas = () => throwError(() => ({ status: 500, error: { error: 'x' } }));
    await montar();

    expect(comp.escalasIndisponiveis()).toBe(true);
    expect(comp.erroEscalas()).not.toBe('');

    comp.escalaId.set(1);
    comp.selectedPlenarios = ['2'];
    comp.mensagens = ['x'];
    comp.onSubmit();
    expect(apiPost).not.toHaveBeenCalled();     // o guard segura mesmo com o form "válido"
  });
});

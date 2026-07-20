import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoAgendaFormComponent } from './aviso-agenda-form.component';

describe('AvisoAgendaFormComponent', () => {
  let fixture: ComponentFixture<AvisoAgendaFormComponent>;
  let comp: AvisoAgendaFormComponent;
  let apiPost: ReturnType<typeof vi.fn>;
  let toastSuccess: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    apiPost = vi.fn().mockReturnValue(of({ ok: true }));
    toastSuccess = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AvisoAgendaFormComponent],
      providers: [
        { provide: ApiService, useValue: { post: apiPost, get: vi.fn() } },
        { provide: ToastService, useValue: { success: toastSuccess, error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AvisoAgendaFormComponent);
    comp = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => vi.restoreAllMocks());

  it('onSubmit válido: POST tipo AGENDA (mensagens trimadas), toast, reset e emite cadastrado', () => {
    let emitido = false;
    comp.cadastrado.subscribe(() => (emitido = true));
    comp.mensagens = ['  Confira a agenda  '];

    comp.onSubmit();

    expect(apiPost).toHaveBeenCalledWith('/api/admin/avisos', { tipo: 'AGENDA', mensagens: ['Confira a agenda'] });
    expect(toastSuccess).toHaveBeenCalledWith('Aviso cadastrado com sucesso.');
    expect(comp.mensagens).toEqual(['']);   // reset
    expect(emitido).toBe(true);
  });

  it('mensagem em branco é rejeitada sem POST', () => {
    comp.mensagens = ['   '];
    comp.onSubmit();
    expect(apiPost).not.toHaveBeenCalled();
    expect(comp.errorMsg()).toBe('Preencha todas as mensagens.');
  });

  it('erro do backend cai em errorMsg, sem emitir cadastrado', () => {
    apiPost.mockReturnValue(throwError(() => ({ status: 500, error: { error: 'boom' } })));
    let emitido = false;
    comp.cadastrado.subscribe(() => (emitido = true));
    comp.mensagens = ['x'];

    comp.onSubmit();

    expect(comp.errorMsg()).not.toBe('');
    expect(emitido).toBe(false);
    expect(comp.saving()).toBe(false);
  });
});

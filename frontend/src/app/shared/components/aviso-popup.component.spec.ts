import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService, AVISOS_CIENTES_SESSAO_KEY } from '../../core/services/auth.service';
import { ToastService } from './toast.component';
import { AvisoPopupComponent } from './aviso-popup.component';

const msg = (texto: string) => [{ ordem: 1, texto }];

/** Payloads como o backend os devolve: `titulo` já derivado do subtipo (fallback = label do tipo). */
const ESCALA = { cadastro_id: 'es1', tipo: 'ESCALA', titulo: 'Escala', exige_ciencia: true, manter_apos_ciencia: false, mensagens: msg('Atenção ao rodízio.') };
const PESSOAL_LEGADO = { cadastro_id: 'pe1', tipo: 'PESSOAL', titulo: 'Pessoal', exige_ciencia: true, manter_apos_ciencia: false, mensagens: msg('Aviso legado sem subtipo.') };
const AGENDA = { cadastro_id: 'ag1', tipo: 'AGENDA', titulo: 'Agenda Legislativa', exige_ciencia: false, manter_apos_ciencia: false, mensagens: msg('Planilha atualizada.') };
const GERAL = { cadastro_id: 'ge1', tipo: 'GERAL', titulo: 'Operadores', exige_ciencia: false, manter_apos_ciencia: false, mensagens: msg('Comunicado ao grupo.') };

describe('AvisoPopupComponent', () => {
  let fixture: ComponentFixture<AvisoPopupComponent>;
  let comp: AvisoPopupComponent;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;
  let pendentes: any[];
  let logado: boolean;
  let routerUrl: string;
  let routerEvents: Subject<unknown>;

  async function montar() {
    await TestBed.configureTestingModule({
      imports: [AvisoPopupComponent],
      providers: [
        { provide: ApiService, useValue: { get: apiGet, post: apiPost } },
        { provide: AuthService, useValue: { isLoggedIn: () => logado } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: Router, useValue: { events: routerEvents.asObservable(), get url() { return routerUrl; } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AvisoPopupComponent);
    comp = fixture.componentInstance;
    fixture.detectChanges();   // ngOnInit → recarregar()
  }

  function navegar(url: string): void {
    routerUrl = url;
    routerEvents.next(new NavigationEnd(1, url, url));
    fixture.detectChanges();
  }

  const titulo = () => fixture.nativeElement.querySelector('.modal-title')?.textContent?.trim();
  const chamadasVisto = () => apiPost.mock.calls.filter((c: any[]) => String(c[0]).endsWith('/visto'));

  beforeEach(() => {
    pendentes = [];
    logado = true;
    routerUrl = '/home';
    routerEvents = new Subject<unknown>();
    apiGet = vi.fn(() => of({ ok: true, data: pendentes }));
    apiPost = vi.fn(() => of({ ok: true }));
    sessionStorage.removeItem(AVISOS_CIENTES_SESSAO_KEY);   // isolamento: o storage persiste entre testes
  });
  afterEach(() => { fixture?.destroy(); vi.restoreAllMocks(); });

  // ── Título "Aviso - {titulo}" (§2) ─────────────────────────────

  it('título é "Aviso - {titulo}" do aviso do topo; o "Você tem x avisos" não existe mais', async () => {
    pendentes = [ESCALA, PESSOAL_LEGADO];
    await montar();
    expect(titulo()).toBe('Aviso - Escala');
    expect(fixture.nativeElement.textContent).not.toContain('Você tem');
  });

  it('aviso legado usa o título de fallback que o backend manda (label do tipo)', async () => {
    pendentes = [PESSOAL_LEGADO];
    await montar();
    expect(titulo()).toBe('Aviso - Pessoal');
  });

  it('defensivo: titulo ausente no payload não quebra o título', async () => {
    pendentes = [{ ...GERAL, titulo: undefined }];
    await montar();
    expect(titulo()).toBe('Aviso - Aviso');
    expect(titulo()).not.toContain('undefined');
  });

  // ── Visto de AGENDA na exibição (§6.2) ─────────────────────────

  it('AGENDA no topo dispara POST /visto na exibição — e só uma vez, mesmo recarregando', async () => {
    pendentes = [AGENDA];
    routerUrl = '/agenda';
    await montar();
    expect(apiGet).toHaveBeenCalledWith('/api/avisos/pendentes', { contexto: 'agenda' });
    expect(apiPost).toHaveBeenCalledWith('/api/avisos/ag1/visto', {});
    expect(chamadasVisto().length).toBe(1);

    navegar('/agenda');   // nova consulta (o mock ainda devolve o mesmo aviso)
    navegar('/agenda');
    expect(chamadasVisto().length).toBe(1);   // Set vistoDisparado segura o POST repetido
  });

  it('AGENDA atrás de um aviso com ciência só dispara o visto quando vira o topo', async () => {
    pendentes = [PESSOAL_LEGADO, AGENDA];
    routerUrl = '/agenda';
    await montar();
    expect(chamadasVisto().length).toBe(0);   // topo é PESSOAL: nada de visto ainda

    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);  // resolve o PESSOAL → AGENDA passa a ser exibido
    fixture.detectChanges();

    expect(apiPost).toHaveBeenCalledWith('/api/avisos/pe1/ciencia', {});
    expect(apiPost).toHaveBeenCalledWith('/api/avisos/ag1/visto', {});
    expect(titulo()).toBe('Aviso - Agenda Legislativa');
  });

  // ── GERAL: dispensa por sessão, sem visto (decisão 19) ─────────

  it('GERAL não dispara visto; "Fechar" dispensa só na sessão e o aviso volta num novo componente', async () => {
    pendentes = [GERAL];
    await montar();
    expect(chamadasVisto().length).toBe(0);
    // sem ciência: botão Fechar, sem checkbox
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();

    comp.fechar(comp.avisos()[0]);
    fixture.detectChanges();
    expect(comp.avisos().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeNull();

    navegar('/home');                          // mesma sessão: continua dispensado
    expect(comp.avisos().length).toBe(0);

    // "nova sessão" = novo componente (novo Set dispensados): o GERAL reaparece
    const fixture2 = TestBed.createComponent(AvisoPopupComponent);
    fixture2.detectChanges();
    expect(fixture2.componentInstance.avisos()[0]?.cadastro_id).toBe('ge1');
    expect(chamadasVisto().length).toBe(0);    // GERAL nunca registra visto
    fixture2.destroy();
  });

  // ── Ciência de ESCALA/PESSOAL inalterada ───────────────────────

  it('tipos com ciência mantêm checkbox + Confirmar; a ciência remove o aviso e não gera visto', async () => {
    pendentes = [ESCALA];
    await montar();
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).not.toBeNull();
    const btn = fixture.nativeElement.querySelector('.btn-primary-custom');
    expect(btn.textContent).toContain('Confirmar');
    expect(btn.disabled).toBe(true);           // sem "Estou ciente" o botão fica travado

    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);
    fixture.detectChanges();

    expect(apiPost).toHaveBeenCalledWith('/api/avisos/es1/ciencia', {});
    expect(chamadasVisto().length).toBe(0);    // ESCALA no topo não dispara visto
    expect(comp.avisos().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeNull();
  });

  // ── "Manter após ciência": 1× por sessão de login (sessionStorage) ──

  it('aviso "manter" confirmado não reaparece na navegação/polling (o servidor continua devolvendo-o)', async () => {
    const manter = { ...PESSOAL_LEGADO, manter_apos_ciencia: true };
    pendentes = [manter];
    await montar();
    expect(titulo()).toBe('Aviso - Pessoal');

    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);
    fixture.detectChanges();
    expect(apiPost).toHaveBeenCalledWith('/api/avisos/pe1/ciencia', {});
    expect(comp.avisos().length).toBe(0);

    navegar('/home');   // reconsulta: o backend real segue devolvendo avisos "manter" já cientes
    navegar('/ponto');
    expect(comp.avisos().length).toBe(0);   // segurado pela memória de sessão
  });

  it('aviso ESCALA "manter" confirmado também fica segurado — e sobrevive a F5 (novo componente)', async () => {
    const manter = { ...ESCALA, manter_apos_ciencia: true };
    pendentes = [manter];
    await montar();
    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);
    fixture.detectChanges();
    expect(comp.avisos().length).toBe(0);

    // "F5" = novo componente na MESMA aba: o sessionStorage persiste → não reexibe
    const fixture2 = TestBed.createComponent(AvisoPopupComponent);
    fixture2.detectChanges();
    expect(fixture2.componentInstance.avisos().length).toBe(0);
    fixture2.destroy();

    // novo LOGIN: o AuthService remove a chave → o aviso volta a ser exibido 1×
    sessionStorage.removeItem(AVISOS_CIENTES_SESSAO_KEY);
    const fixture3 = TestBed.createComponent(AvisoPopupComponent);
    fixture3.detectChanges();
    expect(fixture3.componentInstance.avisos()[0]?.cadastro_id).toBe('es1');
    fixture3.destroy();
  });

  it('ciência de aviso SEM "manter" não grava nada na memória de sessão (o servidor é quem o filtra)', async () => {
    pendentes = [ESCALA];
    await montar();
    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);
    expect(sessionStorage.getItem(AVISOS_CIENTES_SESSAO_KEY)).toBeNull();
  });

  it('deslogado não consulta pendentes', async () => {
    logado = false;
    pendentes = [GERAL];
    await montar();
    expect(apiGet).not.toHaveBeenCalled();
    expect(comp.avisos().length).toBe(0);
  });
});

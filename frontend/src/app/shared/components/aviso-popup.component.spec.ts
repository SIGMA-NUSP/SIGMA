import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { Subject, of } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { AuthService, AVISOS_CIENTES_SESSAO_KEY } from '../../core/services/auth.service';
import { ToastService } from './toast.component';
import { AvisoPopupComponent } from './aviso-popup.component';

const msg = (texto: string) => [{ ordem: 1, texto }];

/** Payloads como o backend os devolve: `categoria` + `titulo` (o contexto) já derivados do subtipo. */
const ESCALA = { cadastro_id: 'es1', tipo: 'ESCALA', categoria: 'AVISO', titulo: 'Escala', exige_ciencia: true, manter_apos_ciencia: false, mensagens: msg('Atenção ao rodízio.') };
const PESSOAL_LEGADO = { cadastro_id: 'pe1', tipo: 'PESSOAL', categoria: 'MENSAGEM', titulo: '', exige_ciencia: true, manter_apos_ciencia: false, mensagens: msg('Aviso legado sem subtipo.') };
const AGENDA = { cadastro_id: 'ag1', tipo: 'AGENDA', categoria: 'COMUNICADO', titulo: 'Agenda Legislativa', exige_ciencia: false, manter_apos_ciencia: false, mensagens: msg('Planilha atualizada.') };
const GERAL = { cadastro_id: 'ge1', tipo: 'GERAL', categoria: 'COMUNICADO', titulo: 'Operadores', exige_ciencia: false, manter_apos_ciencia: false, mensagens: msg('Comunicado ao grupo.') };
const FOLHA = { cadastro_id: 'fo1', tipo: 'PESSOAL', categoria: 'NOTIFICACAO', titulo: 'Folha semanal disponível', exige_ciencia: true, manter_apos_ciencia: false, mensagens: msg('Sua folha foi publicada.') };

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
  const selo = () => fixture.nativeElement.querySelector('.cat-selo')?.textContent?.trim();
  const classesDoCard = () => Array.from<string>(fixture.nativeElement.querySelector('.modal-card')?.classList ?? []);
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

  // ── Título, selo e cores por categoria ─────────────────────────

  it('Aviso: o título é o contexto do aviso do topo, com selo e classe de cor; o "Você tem x avisos" não existe mais', async () => {
    pendentes = [ESCALA, PESSOAL_LEGADO];
    await montar();
    expect(titulo()).toBe('Escala');
    expect(titulo()).not.toContain('Aviso');   // quem diz a categoria é o selo, e uma vez só
    expect(selo()).toBe('Aviso');                      // a caixa alta é do CSS
    expect(classesDoCard()).toContain('cat-aviso');
    expect(classesDoCard()).toContain('modal-cat');    // faixa da categoria no topo
    expect(classesDoCard()).toContain('card-custom');  // as classes estáticas continuam
    expect(fixture.nativeElement.textContent).not.toContain('Você tem');
  });

  it('Comunicado de Agenda: título com o contexto e cor de comunicado', async () => {
    pendentes = [AGENDA];
    routerUrl = '/agenda';
    await montar();
    expect(titulo()).toBe('Agenda Legislativa');
    expect(selo()).toBe('Comunicado');
    expect(classesDoCard()).toContain('cat-comunicado');
  });

  it('Mensagem (pessoal e legado): sem contexto, o título é só a categoria', async () => {
    pendentes = [PESSOAL_LEGADO];
    await montar();
    expect(titulo()).toBe('Mensagem');
    expect(titulo()).not.toContain('—');
    expect(selo()).toBe('Mensagem');
    expect(classesDoCard()).toContain('cat-mensagem');
  });

  it('Notificação: aviso disparado pelo sistema tem selo e cor próprios', async () => {
    pendentes = [FOLHA];
    await montar();
    expect(titulo()).toBe('Folha semanal disponível');
    expect(selo()).toBe('Notificação');
    expect(classesDoCard()).toContain('cat-notificacao');
  });

  it('o selo traz um ícone desenhado, um por categoria', async () => {
    pendentes = [GERAL];
    await montar();
    const paths = fixture.nativeElement.querySelectorAll('.cat-selo svg path');
    expect(paths.length).toBeGreaterThan(0);
    expect(paths[0].getAttribute('d')).toBeTruthy();
  });

  it('defensivo: payload sem categoria nem contexto cai em Aviso — nunca some da tela', async () => {
    pendentes = [{ ...GERAL, categoria: undefined, titulo: undefined }];
    await montar();
    expect(titulo()).toBe('Aviso');
    expect(titulo()).not.toContain('undefined');
    expect(classesDoCard()).toContain('cat-aviso');
  });

  it('cada texto sai na sua caixa, sem numeração', async () => {
    pendentes = [{ ...GERAL, mensagens: [{ ordem: 1, texto: 'Primeiro' }, { ordem: 2, texto: 'Segundo' }] }];
    await montar();
    const caixas = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.aviso-box .aviso-msg'))
      .map(el => el.textContent?.trim());
    expect(caixas).toEqual(['Primeiro', 'Segundo']);
    expect(fixture.nativeElement.textContent).not.toContain('Texto nº');
  });

  // ── Visto na exibição das comunicações sem ciência ─────────────

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
    expect(titulo()).toBe('Agenda Legislativa');
  });

  // ── GERAL sem ciência: visto na exibição, como a Agenda ────────

  it('GERAL sem ciência também dispara o visto na exibição; "Fechar" só tira a caixa da tela', async () => {
    pendentes = [GERAL];
    await montar();
    expect(apiPost).toHaveBeenCalledWith('/api/avisos/ge1/visto', {});
    expect(chamadasVisto().length).toBe(1);
    // sem ciência: botão Fechar, sem checkbox
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();

    comp.fechar(comp.avisos()[0]);
    fixture.detectChanges();
    expect(comp.avisos().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeNull();

    navegar('/home');                          // mesma sessão: continua fora da tela
    expect(comp.avisos().length).toBe(0);
    expect(chamadasVisto().length).toBe(1);    // o visto não se repete
  });

  it('quem segura o aviso sem ciência é o servidor: registrada a exibição, nem um novo login o traz de volta', async () => {
    pendentes = [GERAL];
    await montar();
    expect(chamadasVisto().length).toBe(1);

    pendentes = [];   // o backend passa a filtrar o cadastro já exibido para esta pessoa
    const fixture2 = TestBed.createComponent(AvisoPopupComponent);
    fixture2.detectChanges();
    expect(fixture2.componentInstance.avisos().length).toBe(0);
    fixture2.destroy();
  });

  // ── Quem manda no botão é o exige_ciencia do payload, não o tipo ──

  it('cadastro com ciência: checkbox + Confirmar; a ciência remove o aviso e não gera visto', async () => {
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

  it('o tipo não decide o botão: GERAL com ciência no payload pede confirmação', async () => {
    pendentes = [{ ...GERAL, exige_ciencia: true }];
    await montar();

    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).not.toBeNull();
    comp.ciente = true;
    comp.confirmarCiencia(comp.avisos()[0]);

    expect(apiPost).toHaveBeenCalledWith('/api/avisos/ge1/ciencia', {});
    expect(chamadasVisto().length).toBe(0);   // com ciência exigida não há visto a registrar
  });

  it('o tipo não decide o botão nem o visto: PESSOAL sem ciência oferece "Fechar" e registra a exibição', async () => {
    pendentes = [{ ...PESSOAL_LEGADO, exige_ciencia: false }];
    await montar();

    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.btn-primary-custom').textContent).toContain('Fechar');
    expect(apiPost).toHaveBeenCalledWith('/api/avisos/pe1/visto', {});
    expect(chamadasVisto().length).toBe(1);
  });

  // ── "Manter após ciência": 1× por sessão de login (sessionStorage) ──

  it('aviso "manter" confirmado não reaparece na navegação/polling (o servidor continua devolvendo-o)', async () => {
    const manter = { ...PESSOAL_LEGADO, manter_apos_ciencia: true };
    pendentes = [manter];
    await montar();
    expect(titulo()).toBe('Mensagem');

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

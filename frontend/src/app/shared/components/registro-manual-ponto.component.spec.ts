import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MesAnoSelectorComponent } from './mes-ano-selector.component';
import { RegistroManualPontoComponent } from './registro-manual-ponto.component';

/**
 * RegistroManualPontoComponent: esqueleto da página "Registro manual de ponto" (card de
 * /ponto). Sem backend: os campos de hora e as câmeras são placeholders e `dias()` é um
 * computed local do relógio — não há HTTP para mockar. Os testes exercitam o seletor filho
 * renderizado e confirmam que sua emissão reconstrói a lista de dias do período escolhido.
 */
describe('RegistroManualPontoComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RegistroManualPontoComponent] }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function renderizar(hoje: string): ComponentFixture<RegistroManualPontoComponent> {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    const fixture = TestBed.createComponent(RegistroManualPontoComponent);
    fixture.detectChanges();
    return fixture;
  }

  const seletor = (f: ComponentFixture<RegistroManualPontoComponent>) =>
    f.debugElement.query(By.directive(MesAnoSelectorComponent)).componentInstance as MesAnoSelectorComponent;
  const setaVoltar = (f: ComponentFixture<RegistroManualPontoComponent>) =>
    f.debugElement.query(By.css('app-mes-ano-selector button[aria-label="Mês anterior"]'))
      .nativeElement as HTMLButtonElement;
  const setaAvancar = (f: ComponentFixture<RegistroManualPontoComponent>) =>
    f.debugElement.query(By.css('app-mes-ano-selector button[aria-label="Próximo mês"]'))
      .nativeElement as HTMLButtonElement;
  const selectMes = (f: ComponentFixture<RegistroManualPontoComponent>) =>
    f.debugElement.query(By.css('app-mes-ano-selector select.sel-mes'))
      .nativeElement as HTMLSelectElement;
  /** Rótulos "dd/mm - xxx" renderizados (um por dia do mês exibido). */
  const rotulosNoDom = (f: ComponentFixture<RegistroManualPontoComponent>) =>
    f.debugElement.queryAll(By.css('.dia-card .col-dia'))
      .map(d => (d.nativeElement as HTMLElement).textContent?.trim() ?? '');

  /** Rótulo de um dia do mês `mm`: "dd/mm - dia da semana" (o 'sáb' tem acento — nada de `\w`). */
  const rotuloDoMes = (mm: string) => new RegExp(`^\\d{2}/${mm} - (dom|seg|ter|qua|qui|sex|sáb)$`);

  it('abre no mês/ano do relógio local, com uma linha por dia do mês', () => {
    const fixture = renderizar('2026-07-12T10:00:00-03:00');
    const comp = fixture.componentInstance;

    expect(comp.ano()).toBe(2026);
    expect(comp.mes()).toBe(7);
    expect(comp.dias()).toHaveLength(31);
    expect(rotulosNoDom(fixture)[0]).toMatch(rotuloDoMes('07'));
    expect(rotulosNoDom(fixture)[0]).toMatch(/^01\/07 - /);
  });

  it('navega até o teto futuro e regenera os 30 dias de setembro', () => {
    const fixture = renderizar('2026-07-12T10:00:00-03:00');
    const comp = fixture.componentInstance;

    setaAvancar(fixture).click();
    fixture.detectChanges();
    setaAvancar(fixture).click();
    fixture.detectChanges();

    expect(comp.ano()).toBe(2026);
    expect(comp.mes()).toBe(9);
    expect(setaAvancar(fixture).disabled).toBe(true);
    const rotulos = rotulosNoDom(fixture);
    expect(rotulos).toHaveLength(30);
    expect(rotulos.every(r => rotuloDoMes('09').test(r))).toBe(true);
    expect(rotulos[0]).toMatch(/^01\/09 - /);
    expect(rotulos[29]).toMatch(/^30\/09 - /);
  });

  it('navega para janeiro de 2026 e regenera os dias no piso do range', () => {
    const fixture = renderizar('2026-07-12T10:00:00-03:00');
    const comp = fixture.componentInstance;

    expect(seletor(fixture).anos()).toEqual([2026]);
    selectMes(fixture).value = '1';
    selectMes(fixture).dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(comp.ano()).toBe(2026);
    expect(comp.mes()).toBe(1);
    expect(setaVoltar(fixture).disabled).toBe(true);
    const rotulos = rotulosNoDom(fixture);
    expect(rotulos).toHaveLength(31);
    expect(rotulos.every(r => rotuloDoMes('01').test(r))).toBe(true);
    expect(rotulos[0]).toMatch(/^01\/01 - /);
    expect(rotulos[30]).toMatch(/^31\/01 - /);
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MesAnoSelectorComponent } from './mes-ano-selector.component';
import { RegistroManualPontoComponent } from './registro-manual-ponto.component';

/**
 * RegistroManualPontoComponent: esqueleto da página "Registro manual de ponto" (card de
 * /ponto, oculto por flag). Sem backend: os campos de hora e as câmeras são placeholders e
 * `dias()` é um computed local do relógio — não há HTTP para mockar. Trava-se que o range de
 * anos chega ao `app-mes-ano-selector` pelo `[anos]` e que a navegação real (clique na seta ‹)
 * reconstrói a lista de dias do mês vizinho. Relógio congelado (`{toFake:['Date']}`) ANTES de
 * `createComponent`: `hoje`/`ano`/`mes` e o `anosSeletor` são lidos no field initializer.
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

  it('em 05/01/2027 o seletor recebe [2026, 2027] e o ‹ leva a lista para DEZEMBRO/2026', () => {
    const fixture = renderizar('2027-01-05T09:00:00-03:00');
    const comp = fixture.componentInstance;

    expect(seletor(fixture).anos()).toEqual([2026, 2027]);   // o range chega ao filho pelo [anos]
    expect(setaVoltar(fixture).disabled).toBe(false);

    setaVoltar(fixture).click();                             // clique real na seta ‹
    fixture.detectChanges();

    expect(comp.ano()).toBe(2026);
    expect(comp.mes()).toBe(12);
    const rotulos = rotulosNoDom(fixture);
    expect(rotulos).toHaveLength(31);                        // dezembro tem 31 dias
    expect(rotulos.every(r => rotuloDoMes('12').test(r))).toBe(true);
    expect(rotulos[0]).toMatch(/^01\/12 - /);
    expect(rotulos[30]).toMatch(/^31\/12 - /);
  });

  it('regressão: em julho o seletor segue preso ao ano corrente (uma <option> de ano)', () => {
    const fixture = renderizar('2026-07-12T10:00:00-03:00');
    expect(seletor(fixture).anos()).toEqual([2026]);
    expect(fixture.debugElement.queryAll(By.css('app-mes-ano-selector select.sel-ano option'))).toHaveLength(1);
  });
});

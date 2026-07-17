import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DiaEstado, MiniCalendarioComponent } from './mini-calendario.component';

/**
 * MiniCalendarioComponent. A API real (prevMes/nextMes/selecionar/dias/tituloMes) é
 * `protected`/`private` → testa-se por COMPORTAMENTO via DOM (renderização + `emit`),
 * nunca por chamada direta. O "hoje" e o mês default vêm de `new Date()` → relógio
 * congelado com `vi.setSystemTime`. Só o relógio é falsificado (`toFake: ['Date']`) —
 * setTimeout/rAF ficam reais para não interferir com o `compileComponents()` async
 * nem com o scheduler zoneless do Angular; `vi.useRealTimers()` em afterEach.
 */
describe('MiniCalendarioComponent', () => {
  let fixture: ComponentFixture<MiniCalendarioComponent>;
  let comp: MiniCalendarioComponent;

  async function montar(
    hoje: Date,
    inputs?: {
      min?: Date;
      max?: Date;
      multiSelecao?: boolean;
      estadoDia?: (d: Date) => DiaEstado | null;
    },
  ) {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(hoje);
    await TestBed.configureTestingModule({
      imports: [MiniCalendarioComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(MiniCalendarioComponent);
    comp = fixture.componentInstance;
    if (inputs?.min) fixture.componentRef.setInput('min', inputs.min);
    if (inputs?.max) fixture.componentRef.setInput('max', inputs.max);
    if (inputs?.multiSelecao) fixture.componentRef.setInput('multiSelecao', true);
    if (inputs?.estadoDia) fixture.componentRef.setInput('estadoDia', inputs.estadoDia);
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
  });

  function celulas(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.cal-dia'));
  }

  function titulo(): string {
    return (fixture.nativeElement.querySelector('.cal-titulo').textContent || '').trim().toLowerCase();
  }

  /** Célula do dia `dia`; `noMes=true` exige que pertença ao mês exibido (não `.fora-mes`). */
  function celulaPorDia(dia: number, noMes = true): HTMLButtonElement | undefined {
    return celulas().find(c => {
      const num = c.querySelector('.cal-dia-num')?.textContent?.trim();
      const fora = c.classList.contains('fora-mes');
      return num === String(dia) && (noMes ? !fora : fora);
    });
  }

  it('renderiza a grade de 42 células e o título do mês corrente', async () => {
    await montar(new Date(2026, 6, 15));
    expect(celulas().length).toBe(42);
    expect(titulo()).toContain('julho');
    expect(titulo()).toContain('2026');
  });

  it('marca "hoje" com a classe verde (selecionado) — 15/07 (meio do mês)', async () => {
    await montar(new Date(2026, 6, 15));
    const hoje = fixture.nativeElement.querySelector('.cal-dia.hoje') as HTMLElement;
    expect(hoje).toBeTruthy();
    expect(hoje.querySelector('.cal-dia-num')!.textContent!.trim()).toBe('15');
    expect(hoje.classList.contains('selecionado')).toBe(true);
  });

  it('marca "hoje" com a classe verde (selecionado) — 28/02 (fim do mês, data distinta)', async () => {
    await montar(new Date(2026, 1, 28));
    const hoje = fixture.nativeElement.querySelector('.cal-dia.hoje') as HTMLElement;
    expect(hoje).toBeTruthy();
    expect(hoje.querySelector('.cal-dia-num')!.textContent!.trim()).toBe('28');
    expect(hoje.classList.contains('selecionado')).toBe(true);
    expect(titulo()).toContain('fevereiro');
  });

  it('navega para o mês seguinte e o anterior pelos botões', async () => {
    await montar(new Date(2026, 6, 15));
    const navs = fixture.nativeElement.querySelectorAll('.cal-nav');
    const prev = navs[0] as HTMLButtonElement;
    const next = navs[1] as HTMLButtonElement;
    next.click();
    fixture.detectChanges();
    expect(titulo()).toContain('agosto');
    prev.click();
    prev.click();
    fixture.detectChanges();
    expect(titulo()).toContain('junho');
  });

  it('min/max desabilitam a navegação para fora e os dias fora do intervalo', async () => {
    await montar(new Date(2026, 6, 15), { min: new Date(2026, 6, 10), max: new Date(2026, 6, 20) });
    const navs = fixture.nativeElement.querySelectorAll('.cal-nav');
    expect((navs[0] as HTMLButtonElement).disabled).toBe(true); // mês do min → sem prev p/ junho
    expect((navs[1] as HTMLButtonElement).disabled).toBe(true); // mês do max → sem next p/ agosto
    expect(celulaPorDia(5)!.disabled).toBe(true); // < min(10)
    expect(celulaPorDia(15)!.disabled).toBe(false); // dentro
    expect(celulaPorDia(25)!.disabled).toBe(true); // > max(20)
  });

  it('modo single: clicar num dia emite a data e move a seleção verde', async () => {
    await montar(new Date(2026, 6, 15));
    let emitido: Date | undefined;
    comp.dataSelecionada.subscribe(d => (emitido = d));
    celulaPorDia(20)!.click();
    fixture.detectChanges();
    expect(emitido).toBeInstanceOf(Date);
    expect(emitido!.getDate()).toBe(20);
    const selecionada = fixture.nativeElement.querySelector('.cal-dia.selecionado') as HTMLElement;
    expect(selecionada.querySelector('.cal-dia-num')!.textContent!.trim()).toBe('20');
  });

  it('modo multiSelecao: estadoDia do host controla selecionado/marca/badge/rótulo/desabilitado', async () => {
    const estado = (d: Date): DiaEstado | null => {
      if (d.getMonth() !== 6) return null; // só julho
      switch (d.getDate()) {
        case 10: return { selecionado: true };
        case 11: return { marcado: true, badge: '2h', rotulo: 'Extra 11' };
        case 12: return { desabilitado: true };
        default: return null;
      }
    };
    await montar(new Date(2026, 6, 15), { multiSelecao: true, estadoDia: estado });
    const d10 = celulaPorDia(10)!;
    const d11 = celulaPorDia(11)!;
    const d12 = celulaPorDia(12)!;
    expect(d10.classList.contains('selecionado')).toBe(true);
    expect(d11.querySelector('.cal-marca.marcado')).toBeTruthy(); // retângulo com "×"
    expect(d11.querySelector('.cal-marca')!.textContent!.trim()).toBe('×');
    expect(d11.querySelector('.cal-badge')!.textContent!.trim()).toBe('2h');
    expect(d11.getAttribute('title')).toBe('Extra 11');
    expect(d11.getAttribute('aria-label')).toBe('Extra 11');
    expect(d12.disabled).toBe(true);
  });

  it('modo multiSelecao: clicar num dia emite a data SEM mover a seleção interna (host controla)', async () => {
    await montar(new Date(2026, 6, 15), { multiSelecao: true, estadoDia: () => null });
    let emitido: Date | undefined;
    comp.dataSelecionada.subscribe(d => (emitido = d));
    celulaPorDia(20)!.click();
    fixture.detectChanges();
    expect(emitido!.getDate()).toBe(20);
    // estadoDia sempre null → nenhuma célula fica verde (a seleção é responsabilidade do host)
    expect(fixture.nativeElement.querySelector('.cal-dia.selecionado')).toBeNull();
  });

  it('valorSelecionado posiciona o mês e a seleção; null é ignorado', async () => {
    await montar(new Date(2026, 6, 15));
    fixture.componentRef.setInput('valorSelecionado', new Date(2026, 7, 5)); // agosto/2026, dia 5
    fixture.detectChanges();
    expect(titulo()).toContain('agosto');
    const sel = fixture.nativeElement.querySelector('.cal-dia.selecionado') as HTMLElement;
    expect(sel.querySelector('.cal-dia-num')!.textContent!.trim()).toBe('5');
    // null é ignorado pelo setter → nada muda
    fixture.componentRef.setInput('valorSelecionado', null);
    fixture.detectChanges();
    expect(titulo()).toContain('agosto');
  });
});

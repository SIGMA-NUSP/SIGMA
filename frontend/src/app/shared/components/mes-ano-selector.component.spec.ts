import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MesAno, MesAnoSelectorComponent, anosNavegaveis } from './mes-ano-selector.component';

describe('MesAnoSelectorComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [MesAnoSelectorComponent] }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function criar(hoje: string) {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    const fixture = TestBed.createComponent(MesAnoSelectorComponent);
    const comp = fixture.componentInstance;
    const emitidos: MesAno[] = [];
    comp.mudou.subscribe(periodo => emitidos.push(periodo));
    return { comp, emitidos, fixture };
  }

  function renderizar(hoje: string) {
    const criado = criar(hoje);
    criado.fixture.detectChanges();
    return criado;
  }

  const opcoesAno = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.queryAll(By.css('select.sel-ano option'))
      .map(opcao => Number((opcao.nativeElement as HTMLOptionElement).value));

  const opcoesMes = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.queryAll(By.css('select.sel-mes option'))
      .map(opcao => {
        const elemento = opcao.nativeElement as HTMLOptionElement;
        return { mes: Number(elemento.value), disabled: elemento.disabled };
      });

  const selectMes = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.query(By.css('select.sel-mes')).nativeElement as HTMLSelectElement;

  const selectAno = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.query(By.css('select.sel-ano')).nativeElement as HTMLSelectElement;

  const setaVoltar = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.query(By.css('button[aria-label="Mês anterior"]'))
      .nativeElement as HTMLButtonElement;

  const setaAvancar = (fixture: ComponentFixture<MesAnoSelectorComponent>) =>
    fixture.debugElement.query(By.css('button[aria-label="Próximo mês"]'))
      .nativeElement as HTMLButtonElement;

  function selecionar(select: HTMLSelectElement, valor: number): void {
    select.value = String(valor);
    select.dispatchEvent(new Event('change'));
  }

  describe('estado inicial e anos disponíveis', () => {
    it('abre no relógio local sem emitir durante a inicialização', () => {
      const { comp, emitidos, fixture } = renderizar('2026-07-12T10:00:00-03:00');

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(7);
      expect(comp.meses).toHaveLength(12);
      expect(selectMes(fixture).value).toBe('7');
      expect(selectAno(fixture).value).toBe('2026');
      expect(emitidos).toEqual([]);
    });

    it('o input de anos é coerente mesmo sem binding de um consumidor', () => {
      const { comp, fixture } = renderizar('2026-11-10T09:00:00-03:00');

      expect(comp.anos()).toEqual([2026, 2027]);
      expect(opcoesAno(fixture)).toEqual([2026, 2027]);
    });

    it('de janeiro a outubro de 2026 oferta apenas 2026', () => {
      expect(anosNavegaveis(new Date(2026, 0, 15))).toEqual([2026]);
      expect(anosNavegaveis(new Date(2026, 9, 15))).toEqual([2026]);
    });

    it('em novembro e dezembro de 2026 inclui o ano alcançado pelo teto', () => {
      expect(anosNavegaveis(new Date(2026, 10, 15))).toEqual([2026, 2027]);
      expect(anosNavegaveis(new Date(2026, 11, 15))).toEqual([2026, 2027]);
    });

    it('em anos posteriores mantém todos os anos desde 2026', () => {
      expect(anosNavegaveis(new Date(2030, 2, 15)))
        .toEqual([2026, 2027, 2028, 2029, 2030]);
      expect(anosNavegaveis(new Date(2030, 10, 15)))
        .toEqual([2026, 2027, 2028, 2029, 2030, 2031]);
    });

    it('com piso explícito a lista começa nele — é a janela do sumário, que alcança o acervo de 2025', () => {
      expect(anosNavegaveis(new Date(2026, 0, 15), 2025)).toEqual([2025, 2026]);
      expect(anosNavegaveis(new Date(2026, 10, 15), 2025)).toEqual([2025, 2026, 2027]);
    });
  });

  describe('opções e limites do range', () => {
    it('mantém os doze meses visíveis e desabilita somente os posteriores ao teto', () => {
      const { fixture } = renderizar('2026-07-12T10:00:00-03:00');

      expect(opcoesMes(fixture)).toHaveLength(12);
      expect(opcoesMes(fixture).filter(opcao => !opcao.disabled).map(opcao => opcao.mes))
        .toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
      expect(opcoesMes(fixture).filter(opcao => opcao.disabled).map(opcao => opcao.mes))
        .toEqual([10, 11, 12]);
    });

    it('no ano do teto habilita janeiro e desabilita os meses seguintes em novembro', () => {
      const { fixture } = renderizar('2026-11-10T09:00:00-03:00');

      selecionar(selectAno(fixture), 2027);
      fixture.detectChanges();

      expect(opcoesMes(fixture).filter(opcao => !opcao.disabled).map(opcao => opcao.mes)).toEqual([1]);
      expect(opcoesMes(fixture).filter(opcao => opcao.disabled).map(opcao => opcao.mes))
        .toEqual([2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
    });

    it('as setas param em janeiro de 2026 e no teto corrente mais dois', () => {
      const { comp, emitidos, fixture } = renderizar('2026-07-12T10:00:00-03:00');

      selecionar(selectMes(fixture), 1);
      fixture.detectChanges();
      expect(setaVoltar(fixture).disabled).toBe(true);

      const quantidadeAntesDoPiso = emitidos.length;
      comp.voltarMes();
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toHaveLength(quantidadeAntesDoPiso);

      selecionar(selectMes(fixture), 9);
      fixture.detectChanges();
      expect(setaAvancar(fixture).disabled).toBe(true);

      const quantidadeAntesDoTeto = emitidos.length;
      comp.avancarMes();
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(9);
      expect(emitidos).toHaveLength(quantidadeAntesDoTeto);
    });

    it('ignora defensivamente um mês fora do range e não emite', () => {
      const { comp, emitidos } = criar('2026-07-12T10:00:00-03:00');

      comp.onSelectMes({ target: { value: '10' } } as unknown as Event);

      expect(comp.mes()).toBe(7);
      expect(emitidos).toEqual([]);
    });
  });

  describe('navegação pelas setas', () => {
    it('navega e emite uma vez por clique', () => {
      const { comp, emitidos, fixture } = renderizar('2026-07-12T10:00:00-03:00');

      setaVoltar(fixture).click();
      fixture.detectChanges();
      setaAvancar(fixture).click();
      fixture.detectChanges();

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(7);
      expect(emitidos).toEqual([
        { ano: 2026, mes: 6 },
        { ano: 2026, mes: 7 },
      ]);
    });

    it('atravessa dezembro e janeiro até fevereiro quando o relógio está em dezembro', () => {
      const { comp, emitidos, fixture } = renderizar('2026-12-10T09:00:00-03:00');

      setaAvancar(fixture).click();
      fixture.detectChanges();
      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);

      setaAvancar(fixture).click();
      fixture.detectChanges();
      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(2);
      expect(setaAvancar(fixture).disabled).toBe(true);
      expect(emitidos).toEqual([
        { ano: 2027, mes: 1 },
        { ano: 2027, mes: 2 },
      ]);

      comp.avancarMes();
      expect(emitidos).toHaveLength(2);

      setaVoltar(fixture).click();
      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(emitidos.at(-1)).toEqual({ ano: 2027, mes: 1 });
    });
  });

  describe('troca de ano com preservação e clamp', () => {
    it('em novembro clampa novembro para janeiro do ano do teto e emite uma vez', () => {
      const { comp, emitidos, fixture } = renderizar('2026-11-10T09:00:00-03:00');

      selecionar(selectAno(fixture), 2027);

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([{ ano: 2027, mes: 1 }]);
    });

    it('em dezembro clampa dezembro para fevereiro do ano do teto e emite uma vez', () => {
      const { comp, emitidos, fixture } = renderizar('2026-12-10T09:00:00-03:00');

      selecionar(selectAno(fixture), 2027);

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(2);
      expect(emitidos).toEqual([{ ano: 2027, mes: 2 }]);
    });

    it('preserva janeiro ao voltar do ano do teto para 2026', () => {
      const { comp, emitidos, fixture } = renderizar('2026-12-10T09:00:00-03:00');

      setaAvancar(fixture).click();
      fixture.detectChanges();
      emitidos.length = 0;
      selecionar(selectAno(fixture), 2026);

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([{ ano: 2026, mes: 1 }]);
    });

    it('preserva o número do mês quando ele é válido nos dois anos', () => {
      const { comp, emitidos, fixture } = renderizar('2026-12-10T09:00:00-03:00');

      selecionar(selectMes(fixture), 2);
      fixture.detectChanges();
      emitidos.length = 0;
      selecionar(selectAno(fixture), 2027);

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(2);
      expect(emitidos).toEqual([{ ano: 2027, mes: 2 }]);
    });

    it('selecionar o mesmo ano preserva o mês e emite uma vez', () => {
      const { comp, emitidos, fixture } = renderizar('2026-07-12T10:00:00-03:00');

      selecionar(selectAno(fixture), 2026);

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(7);
      expect(emitidos).toEqual([{ ano: 2026, mes: 7 }]);
    });
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MesAno, MesAnoSelectorComponent, anosNavegaveis } from './mes-ano-selector.component';

/**
 * MesAnoSelectorComponent: estado inicial no mês/ano do relógio, navegação por setas e selects,
 * a política de anos na virada (`anosNavegaveis`: dezembro → [ano, ano+1]; janeiro →
 * [ano-1, ano]), o salto do `<select>` de ano para o mês da virada (ano anterior → dez; ano
 * seguinte → jan), a janela navegável de 13 meses (ano corrente inteiro + só o mês vizinho da
 * virada) e o clamp do `ngOnInit` quando o `[anos]` não contém o ano do relógio.
 * Relógio PINADO por cenário (fake timers, `{toFake:['Date']}`) ANTES de `createComponent`:
 * `hoje`, `ano`, `mes` e `anoCorrente` são lidos no field initializer. Render real
 * (`detectChanges` → `ngOnInit`/clamp) para as asserções de DOM (`disabled` das setas, `<option>`).
 */
describe('MesAnoSelectorComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [MesAnoSelectorComponent] }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** Congela o relógio (lido no field initializer) e cria o seletor, opcionalmente com [anos]. */
  function criar(hoje: string, anos?: number[]) {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(hoje));
    const fixture = TestBed.createComponent(MesAnoSelectorComponent);
    if (anos) fixture.componentRef.setInput('anos', anos);
    const comp = fixture.componentInstance;
    const emitidos: MesAno[] = [];
    comp.mudou.subscribe(m => emitidos.push(m));
    return { comp, emitidos, fixture };
  }

  /** Idem, mas renderizado (roda `ngOnInit` → o clamp) — para as asserções de DOM. */
  function renderizar(hoje: string, anos?: number[]) {
    const criado = criar(hoje, anos);
    criado.fixture.detectChanges();
    return criado;
  }

  /** Valores das <option>s do <select> de ano, na ordem em que a UI as oferta. */
  const opcoesAno = (f: ComponentFixture<MesAnoSelectorComponent>) =>
    f.debugElement.queryAll(By.css('select.sel-ano option'))
      .map(o => (o.nativeElement as HTMLOptionElement).value);
  const selectAno = (f: ComponentFixture<MesAnoSelectorComponent>) =>
    f.debugElement.query(By.css('select.sel-ano')).nativeElement as HTMLSelectElement;
  const setaVoltar = (f: ComponentFixture<MesAnoSelectorComponent>) =>
    f.debugElement.query(By.css('button[aria-label="Mês anterior"]')).nativeElement as HTMLButtonElement;
  const setaAvancar = (f: ComponentFixture<MesAnoSelectorComponent>) =>
    f.debugElement.query(By.css('button[aria-label="Próximo mês"]')).nativeElement as HTMLButtonElement;

  describe('estado inicial', () => {
    it('abre no mês/ano do relógio local (nada hardcoded)', () => {
      const { comp } = criar('2026-07-12T10:00:00-03:00');
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(7);
      expect(comp.meses[0]).toBe('Janeiro');
      expect(comp.meses).toHaveLength(12); // MESES é 1-based; o template itera a fatia 0-based
    });

    it('não emite nada ao ser CONSTRUÍDO, sem ciclo de vida (o pai já parte do mês corrente)', () => {
      // `criar` não roda `detectChanges` → o `ngOnInit` (onde vive o clamp) NÃO é chamado
      // aqui. O contrato "não emite ao ser criado" no caminho do INIT é travado pelos testes do
      // clamp, mais abaixo, que renderizam de verdade.
      const { emitidos } = criar('2026-07-12T10:00:00-03:00');
      expect(emitidos).toEqual([]);
    });
  });

  describe('navegação com setas', () => {
    it('voltar um mês emite o mês anterior', () => {
      const { comp, emitidos } = criar('2026-07-12T10:00:00-03:00');
      comp.voltarMes();
      expect(comp.mes()).toBe(6);
      expect(emitidos).toEqual([{ ano: 2026, mes: 6 }]);
    });

    it('avançar um mês emite o mês seguinte', () => {
      const { comp, emitidos } = criar('2026-07-12T10:00:00-03:00');
      comp.avancarMes();
      expect(comp.mes()).toBe(8);
      expect(emitidos).toEqual([{ ano: 2026, mes: 8 }]);
    });

    it('com range de anos, janeiro volta para dezembro do ano anterior', () => {
      const { comp, emitidos } = criar('2026-01-15T12:00:00-03:00', [2025, 2026]);
      expect(comp.podeVoltar()).toBe(true);

      comp.voltarMes();

      expect(comp.ano()).toBe(2025);
      expect(comp.mes()).toBe(12);
      expect(emitidos).toEqual([{ ano: 2025, mes: 12 }]);
    });

    it('com range de anos, dezembro avança para janeiro do ano seguinte', () => {
      const { comp, emitidos } = criar('2026-12-15T12:00:00-03:00', [2026, 2027]);
      expect(comp.podeAvancar()).toBe(true);

      comp.avancarMes();

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([{ ano: 2027, mes: 1 }]);
    });

    it('as setas são inertes nos limites do range (guarda interna, além do [disabled])', () => {
      const { comp, emitidos } = criar('2026-01-15T12:00:00-03:00', [2026]);
      comp.voltarMes(); // janeiro do único ano → nada acontece
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([]);
    });
  });

  describe('podeVoltar / podeAvancar', () => {
    it('no meio do ano, ambos habilitados', () => {
      const { comp } = criar('2026-07-12T10:00:00-03:00');
      expect(comp.podeVoltar()).toBe(true);
      expect(comp.podeAvancar()).toBe(true);
    });

    it('limites do range: 1º ano/janeiro não volta; último ano/dezembro não avança', () => {
      const jan = criar('2025-01-10T12:00:00-03:00', [2025, 2026]);
      expect(jan.comp.podeVoltar()).toBe(false);
      expect(jan.comp.podeAvancar()).toBe(true);
      vi.useRealTimers();

      const dez = criar('2026-12-10T12:00:00-03:00', [2025, 2026]);
      expect(dez.comp.podeAvancar()).toBe(false);
      expect(dez.comp.podeVoltar()).toBe(true);
    });
  });

  describe('selects de mês e ano', () => {
    it('escolher o mês no <select> atualiza o estado e emite', () => {
      const { comp, emitidos } = criar('2026-07-12T10:00:00-03:00');
      comp.onSelectMes({ target: { value: '3' } } as unknown as Event);
      expect(comp.mes()).toBe(3);
      expect(emitidos).toEqual([{ ano: 2026, mes: 3 }]);
    });

    it('escolher o MESMO ano no <select> preserva o mês e emite (o salto só cruza o ano)', () => {
      const { comp, emitidos } = criar('2026-07-12T10:00:00-03:00', [2026]);
      comp.onSelectAno({ target: { value: '2026' } } as unknown as Event);
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(7);            // mês intacto — não houve troca de ano
      expect(emitidos).toEqual([{ ano: 2026, mes: 7 }]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // A virada do ano, exatamente onde ela importa
  //
  // A folha de um mês é publicada no início do mês SEGUINTE e é a publicação que abre a janela de
  // 5 dias para retificar: em janeiro, o mês vivo é DEZEMBRO. Ofertar só o ano do relógio o tornava
  // inalcançável (seta ‹ desabilitada, uma <option> de ano só) — este bloco trava a política do
  // range, a navegação nos dois flancos e o clamp do estado inicial.
  // ═══════════════════════════════════════════════════════════════════
  describe('anosNavegaveis — política do range', () => {
    /** [rótulo, mês (1–12), anos esperados] com o relógio em 2026. */
    const CASOS: [string, number, number[]][] = [
      ['janeiro (a folha de dezembro está publicada, com o prazo correndo)', 1, [2025, 2026]],
      ['fevereiro (borda interna: a virada já passou)', 2, [2026]],
      ['março', 3, [2026]],
      ['abril', 4, [2026]],
      ['maio', 5, [2026]],
      ['junho', 6, [2026]],
      ['julho', 7, [2026]],
      ['agosto', 8, [2026]],
      ['setembro', 9, [2026]],
      ['outubro', 10, [2026]],
      ['novembro (borda interna: a virada ainda não chegou)', 11, [2026]],
      ['dezembro (janeiro do ano seguinte fica ao alcance)', 12, [2026, 2027]],
    ];

    // Ordem CRESCENTE é contrato (podeVoltar/podeAvancar leem anos[0] e anos[length-1]) e está
    // provada pelos literais ascendentes abaixo: `toEqual` de array é sensível à ordem, de modo que
    // um `[ano, ano-1]` em janeiro derruba o caso de janeiro.
    for (const [rotulo, mes, esperado] of CASOS) {
      it(`em ${rotulo}, o range é [${esperado.join(', ')}]`, () => {
        expect(anosNavegaveis(new Date(2026, mes - 1, 15, 9, 0, 0))).toEqual(esperado);
      });
    }

    it('o range é função do relógio, não de um ano hardcoded', () => {
      expect(anosNavegaveis(new Date(2031, 0, 5))).toEqual([2030, 2031]);
      expect(anosNavegaveis(new Date(2031, 11, 20))).toEqual([2031, 2032]);
    });
  });

  describe('navegação nos flancos do ano', () => {
    it('em janeiro, com o range da política, dezembro do ano anterior é alcançável', () => {
      const { comp, emitidos } = renderizar('2027-01-05T09:00:00-03:00',
        anosNavegaveis(new Date('2027-01-05T09:00:00-03:00')));

      expect(comp.anos()).toEqual([2026, 2027]);
      expect(comp.podeVoltar()).toBe(true);          // antes: false — o mês vivo era inalcançável

      comp.voltarMes();

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);
      expect(emitidos).toEqual([{ ano: 2026, mes: 12 }]);
    });

    it('em dezembro, janeiro do ano seguinte é alcançável (flanco simétrico)', () => {
      const { comp, emitidos } = renderizar('2026-12-20T09:00:00-03:00',
        anosNavegaveis(new Date('2026-12-20T09:00:00-03:00')));

      expect(comp.anos()).toEqual([2026, 2027]);
      expect(comp.podeAvancar()).toBe(true);

      comp.avancarMes();

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([{ ano: 2027, mes: 1 }]);
    });

    it('no meio do ano a política não abre nada: só o ano corrente, navegação presa dentro dele', () => {
      const hoje = '2026-07-12T10:00:00-03:00';
      const { comp, emitidos } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(comp.anos()).toEqual([2026]);
      comp.onSelectMes({ target: { value: '1' } } as unknown as Event);   // janeiro/2026
      expect(comp.podeVoltar()).toBe(false);
      comp.voltarMes();
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([{ ano: 2026, mes: 1 }]);                  // só a do <select>
    });
  });

  describe('render do range e do clamp (DOM real)', () => {
    it('em janeiro o <select> oferta 2 anos (crescente) e a seta ‹ NÃO está desabilitada', () => {
      const hoje = '2027-01-05T09:00:00-03:00';
      const { fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(opcoesAno(fixture)).toEqual(['2026', '2027']);
      expect(selectAno(fixture).value).toBe('2027');        // o ano do relógio é o exibido
      expect(setaVoltar(fixture).disabled).toBe(false);     // o caminho para dezembro está aberto
    });

    it('em dezembro o <select> oferta [ano, ano+1] e a seta › NÃO está desabilitada', () => {
      const hoje = '2026-12-20T09:00:00-03:00';
      const { fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(opcoesAno(fixture)).toEqual(['2026', '2027']);
      expect(setaAvancar(fixture).disabled).toBe(false);
    });

    it('no meio do ano o <select> tem 1 <option> só, e a seta ‹ trava em janeiro do ano corrente', () => {
      const hoje = '2026-07-12T10:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(opcoesAno(fixture)).toEqual(['2026']);
      comp.onSelectMes({ target: { value: '1' } } as unknown as Event);
      fixture.detectChanges();
      expect(setaVoltar(fixture).disabled).toBe(true);
    });

    // ⚠️ Estes dois cenários de clamp usam um [anos] que NÃO contém o ano do relógio —
    // inalcançável sob `anosNavegaveis`, existe só para provar o clamp (Math.min/Math.max).
    // A janela navegável é medida contra o RELÓGIO (não contra o range), ortogonal ao clamp; a
    // semântica dos guards é provada nos cenários canônicos de virada, mais abaixo.
    it('um [anos] TODO ABAIXO do relógio clampa ao teto do range, sem emitir', () => {
      // Sem o clamp do ngOnInit, o estado (2026) divergia do <select> (nenhuma <option> casava →
      // o DOM caía na primeira, 2024): a tela dizia um ano e a navegação obedecia a outro.
      const { comp, emitidos, fixture } = renderizar('2026-12-20T09:00:00-03:00', [2024, 2025]);

      expect(comp.ano()).toBe(2025);                       // clampado ao teto do range (Math.min)
      expect(emitidos).toEqual([]);                        // e o clamp NÃO emite (contrato do init)
      expect(selectAno(fixture).value).toBe('2025');       // select e estado voltam a falar o mesmo ano
    });

    it('um [anos] TODO ACIMA do relógio clampa ao PISO do range, sem emitir', () => {
      // O outro lado do clamp (Math.max). Sem ele, `ano()` ficaria em 2026 com o <select> exibindo
      // 2030 (a 1ª <option>, por não haver nenhuma casando).
      const { comp, emitidos, fixture } = renderizar('2026-12-20T09:00:00-03:00', [2030, 2031]);

      expect(comp.ano()).toBe(2030);
      expect(emitidos).toEqual([]);
      expect(selectAno(fixture).value).toBe('2030');
    });

    it('um [anos] vazio não produz NaN (a guarda do clamp) — o estado fica no relógio', () => {
      // Sem `if (anos.length === 0) return;`, o clamp faria Math.min(Math.max(a, undefined), undefined)
      // = NaN: o <select> ficaria sem seleção e todo GET dos pais sairia com `ano=NaN`.
      const { comp, emitidos } = renderizar('2026-07-12T10:00:00-03:00', []);

      expect(Number.isNaN(comp.ano())).toBe(false);
      expect(comp.ano()).toBe(2026);
      expect(emitidos).toEqual([]);
    });

    it('criado DENTRO do range, o clamp é no-op e nada é emitido (regressão do contrato)', () => {
      const hoje = '2027-01-05T09:00:00-03:00';
      const { comp, emitidos } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(emitidos).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // A janela navegável de 13 meses
  //
  // A política do range é "a virada", mas `podeVoltar`/`podeAvancar` barravam só no 1º/último ANO do
  // range: nos meses de virada a navegação alcançava o ano vizinho INTEIRO (24 meses) — o banco de
  // horas abria meses a um ano de distância e o Configurar da grade escrevia em qualquer mês. Agora
  // a janela é o ano corrente inteiro + só o mês vizinho da virada (13 meses). Os testes que
  // DISTINGUEM a janela do comportamento antigo estão em cada flanco cortado; os limites naturais
  // (iguais ao antigo) ficam como regressão.
  // ═══════════════════════════════════════════════════════════════════
  describe('janela navegável de 13 meses na virada', () => {
    // Cenário JANEIRO (05/01/2027): janela = dez/2026 … dez/2027 — o corte é no flanco de TRÁS.
    it('em janeiro, dez/2026 é o PISO da janela: ‹ desabilitada (antes ia até jan/2026)', () => {
      const hoje = '2027-01-05T09:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));   // [2026, 2027]

      setaVoltar(fixture).click();                     // jan/2027 → dez/2026 (pela seta, não pelo salto do <select>)
      fixture.detectChanges();

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);
      expect(comp.podeVoltar()).toBe(false);           // não há para onde recuar
      expect(setaVoltar(fixture).disabled).toBe(true); // corte da janela (antes: habilitada, ia a jan/2026)
    });

    it('regressão do flanco da virada — em janeiro, jan/2027 (o relógio) ainda volta para dez/2026', () => {
      const hoje = '2027-01-05T09:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));

      expect(setaVoltar(fixture).disabled).toBe(false);
      setaVoltar(fixture).click();
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);
    });

    it('em janeiro, dez/2027 é o TETO da janela: › desabilitada (a política não abre 2028)', () => {
      const hoje = '2027-01-05T09:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));
      comp.onSelectMes({ target: { value: '12' } } as unknown as Event);   // dez/2027
      fixture.detectChanges();

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(12);
      expect(setaAvancar(fixture).disabled).toBe(true);
    });

    // Cenário DEZEMBRO (10/12/2026): janela = jan/2026 … jan/2027 — o corte é no flanco da FRENTE.
    it('em dezembro, jan/2027 é o TETO da janela: › desabilitada (antes ia até dez/2027)', () => {
      const hoje = '2026-12-10T09:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));   // [2026, 2027]

      setaAvancar(fixture).click();                    // dez/2026 → jan/2027 (pela seta, não pelo salto do <select>)
      fixture.detectChanges();

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);
      expect(comp.podeAvancar()).toBe(false);          // não há para onde avançar
      expect(setaAvancar(fixture).disabled).toBe(true);// corte da janela (antes: habilitada, ia a dez/2027)

      setaVoltar(fixture).click();                     // e ‹ leva de volta a dez/2026
      fixture.detectChanges();
      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);
    });

    it('regressão — em dezembro, jan/2026 é o PISO natural: ‹ desabilitada', () => {
      const hoje = '2026-12-10T09:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));
      comp.onSelectMes({ target: { value: '1' } } as unknown as Event);   // jan/2026
      fixture.detectChanges();

      expect(setaVoltar(fixture).disabled).toBe(true);
    });

    // Fora da virada (15/06/2026): janela = jan/2026 … dez/2026 (12 meses, igual ao ano corrente).
    it('fora da virada a janela é o ano corrente inteiro (12 meses): ambos os flancos travam', () => {
      const hoje = '2026-06-15T10:00:00-03:00';
      const { comp, fixture } = renderizar(hoje, anosNavegaveis(new Date(hoje)));   // [2026]

      expect(comp.anos()).toEqual([2026]);
      expect(opcoesAno(fixture)).toEqual(['2026']);

      comp.onSelectMes({ target: { value: '1' } } as unknown as Event);
      fixture.detectChanges();
      expect(setaVoltar(fixture).disabled).toBe(true);

      comp.onSelectMes({ target: { value: '12' } } as unknown as Event);
      fixture.detectChanges();
      expect(setaAvancar(fixture).disabled).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════
  // O <select> de ano salta para o mês da virada
  //
  // Com dois anos ofertados na virada, o `<select>` é o caminho natural de quem quer "o outro ano".
  // Antes ele só trocava o ano e mantinha o mês: em 05/01/2027 escolher 2026 caía em jan/2026 — 12
  // meses antes de dezembro (a folha no prazo). Agora salta para o único mês navegável do ano
  // vizinho sob a janela de 13 meses: ano anterior → dezembro; ano seguinte → janeiro. Um só evento.
  // ═══════════════════════════════════════════════════════════════════
  describe('o <select> de ano salta para o mês da virada', () => {
    it('em janeiro, escolher o ano ANTERIOR (2026) salta para dezembro (não jan/2026)', () => {
      const { comp, emitidos } = criar('2027-01-05T09:00:00-03:00', [2026, 2027]);  // parte em jan/2027

      comp.onSelectAno({ target: { value: '2026' } } as unknown as Event);

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);                          // saltou 1 → 12 (antes mantinha janeiro)
      expect(emitidos).toEqual([{ ano: 2026, mes: 12 }]);   // UM evento por gesto
    });

    it('em qualquer mês de 2027, escolher 2026 aterrissa em dezembro/2026', () => {
      const { comp, emitidos } = criar('2027-01-05T09:00:00-03:00', [2026, 2027]);
      comp.onSelectMes({ target: { value: '8' } } as unknown as Event);   // agosto/2027
      comp.onSelectAno({ target: { value: '2026' } } as unknown as Event);

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);                          // saltou 8 → 12
      expect(emitidos.at(-1)).toEqual({ ano: 2026, mes: 12 });
    });

    it('em dezembro, escolher o ano SEGUINTE (2027) salta para janeiro (não dez/2027)', () => {
      const { comp, emitidos } = criar('2026-12-10T09:00:00-03:00', [2026, 2027]);  // parte em dez/2026

      comp.onSelectAno({ target: { value: '2027' } } as unknown as Event);

      expect(comp.ano()).toBe(2027);
      expect(comp.mes()).toBe(1);                           // saltou 12 → 1
      expect(emitidos).toEqual([{ ano: 2027, mes: 1 }]);
    });

    it('em dezembro, de jan/2027 escolher 2026 volta para dezembro/2026', () => {
      const { comp, emitidos } = criar('2026-12-10T09:00:00-03:00', [2026, 2027]);
      comp.avancarMes();                                    // dez/2026 → jan/2027
      comp.onSelectAno({ target: { value: '2026' } } as unknown as Event);

      expect(comp.ano()).toBe(2026);
      expect(comp.mes()).toBe(12);
      expect(emitidos.at(-1)).toEqual({ ano: 2026, mes: 12 });
    });

    // O salto compõe com a janela de 13 meses: aterrissa SEMPRE no mês-borda da janela, nunca fora.
    it('em janeiro, o salto para 2026 aterrissa no PISO da janela (dez/2026)', () => {
      const { comp } = criar('2027-01-05T09:00:00-03:00', [2026, 2027]);
      comp.onSelectAno({ target: { value: '2026' } } as unknown as Event);

      expect(comp.podeVoltar()).toBe(false);   // dez/2026 é o piso: não há mês antes dele na janela
      expect(comp.podeAvancar()).toBe(true);
    });

    it('em dezembro, o salto para 2027 aterrissa no TETO da janela (jan/2027)', () => {
      const { comp } = criar('2026-12-10T09:00:00-03:00', [2026, 2027]);
      comp.onSelectAno({ target: { value: '2027' } } as unknown as Event);

      expect(comp.podeAvancar()).toBe(false);  // jan/2027 é o teto: não há mês depois dele na janela
      expect(comp.podeVoltar()).toBe(true);
    });
  });
});

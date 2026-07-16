import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ColumnFilterComponent,
  ColumnFilterDef,
  ColumnFilterState,
} from './column-filter.component';

/**
 * T20 — ColumnFilterComponent (§5.4; §C5). Alimenta todas as tabelas do admin. O
 * coração testado é a lógica de seleção com a semântica `values: null = TODOS
 * selecionados` (não confundir com "nenhum") e os computeds de busca. O
 * posicionamento do painel (duplo `requestAnimationFrame` + `getBoundingClientRect`)
 * é coberto à parte com `requestAnimationFrame` stubado e `getBoundingClientRect`
 * dublado no jsdom.
 */
describe('ColumnFilterComponent', () => {
  const COL: ColumnFilterDef = { key: 'nome', label: 'Nome', type: 'text' };
  const DISTINCT = [
    { value: 'ana', label: 'Ana' },
    { value: 'bruno', label: 'Bruno' },
    { value: 'carla', label: 'Carla' },
  ];

  let fixture: ComponentFixture<ColumnFilterComponent>;
  let comp: ColumnFilterComponent;

  // `distinctValues`/`col` são @Input clássicos (não signals); os computeds
  // `distinctItems`/`filteredItems` memoizam na 1ª leitura, então setamos ANTES do
  // primeiro detectChanges.
  async function montar(col: ColumnFilterDef = COL, distinct = DISTINCT) {
    await TestBed.configureTestingModule({
      imports: [ColumnFilterComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(ColumnFilterComponent);
    comp = fixture.componentInstance;
    comp.col = col;
    comp.distinctValues = distinct;
    fixture.detectChanges();
  }

  function ouvirFiltro() {
    const emitidos: { key: string; state: ColumnFilterState | null }[] = [];
    comp.filterChange.subscribe(v => emitidos.push(v));
    return emitidos;
  }

  afterEach(() => vi.restoreAllMocks());

  describe('semântica de seleção (values null = todos)', () => {
    it('estado inicial: null = TODOS selecionados, filtro inativo', async () => {
      await montar();
      expect(comp.allSelected()).toBe(true);
      expect(comp.isSelected('ana')).toBe(true);
      expect(comp.isSelected('qualquer-outro')).toBe(true);
      expect(comp.isActive()).toBe(false);
    });

    it('toggleAll de "todos" desmarca tudo ([]), depois volta a todos (null)', async () => {
      await montar();
      const emit = ouvirFiltro();
      comp.toggleAll(); // null → []
      expect(comp.allSelected()).toBe(false);
      expect(comp.isSelected('ana')).toBe(false);
      expect(comp.isActive()).toBe(true);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: { values: [] } });
      comp.toggleAll(); // [] → null
      expect(comp.allSelected()).toBe(true);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: null });
    });

    it('toggleValue a partir de "todos" seleciona APENAS o clicado', async () => {
      await montar();
      const emit = ouvirFiltro();
      comp.toggleValue('ana');
      expect(comp.isSelected('ana')).toBe(true);
      expect(comp.isSelected('bruno')).toBe(false);
      expect(comp.allSelected()).toBe(false);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: { values: ['ana'] } });
    });

    it('toggleValue removendo o último volta a "todos" (null) — evita tabela vazia', async () => {
      await montar();
      const emit = ouvirFiltro();
      comp.toggleValue('ana'); // → ['ana']
      comp.toggleValue('ana'); // remove → [] → null
      expect(comp.allSelected()).toBe(true);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: null });
    });

    it('selecionar manualmente TODOS os valores reseta para "todos" (null)', async () => {
      await montar();
      const emit = ouvirFiltro();
      comp.toggleValue('ana');   // ['ana']
      comp.toggleValue('bruno'); // ['ana','bruno']
      comp.toggleValue('carla'); // 3 >= 3 distinct → null
      expect(comp.allSelected()).toBe(true);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: null });
    });

    it('adicionar um segundo valor mantém o subconjunto (sem reset prematuro)', async () => {
      await montar();
      const emit = ouvirFiltro();
      comp.toggleValue('ana');   // ['ana']
      comp.toggleValue('bruno'); // ['ana','bruno'] (2 < 3)
      expect(comp.allSelected()).toBe(false);
      expect(comp.isSelected('ana')).toBe(true);
      expect(comp.isSelected('bruno')).toBe(true);
      expect(comp.isSelected('carla')).toBe(false);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: { values: ['ana', 'bruno'] } });
    });
  });

  describe('computeds de busca', () => {
    it('filteredItems filtra por searchText case-insensitive; distinctItems reflete o input', async () => {
      await montar();
      expect(comp.distinctItems().length).toBe(3);
      comp.searchText.set('CARL');
      expect(comp.filteredItems().map(i => i.value)).toEqual(['carla']);
      comp.searchText.set('a');
      expect(comp.filteredItems().map(i => i.value)).toEqual(['ana', 'carla']);
      comp.searchText.set('');
      expect(comp.filteredItems().length).toBe(3);
    });
  });

  describe('datas', () => {
    it('isActive true quando há intervalo de datas mesmo com values null', async () => {
      await montar({ key: 'data', label: 'Data', type: 'date' });
      expect(comp.isActive()).toBe(false);
      comp.dateFrom = '2026-07-01';
      expect(comp.isActive()).toBe(true);
    });

    it('onDateChange emite o range preenchido (to indefinido quando vazio)', async () => {
      await montar({ key: 'data', label: 'Data', type: 'date' });
      const emit = ouvirFiltro();
      comp.dateFrom = '2026-07-01';
      comp.onDateChange();
      expect(emit.at(-1)).toEqual({
        key: 'data',
        state: { range: { from: '2026-07-01', to: undefined } },
      });
    });
  });

  describe('sort / limpar / fechar', () => {
    it('onSort emite {sort, direction} da coluna e fecha o painel', async () => {
      await montar();
      comp.open.set(true);
      const sortEmit = vi.spyOn(comp.sortChange, 'emit');
      comp.onSort('desc');
      expect(sortEmit).toHaveBeenCalledWith({ sort: 'nome', direction: 'desc' });
      expect(comp.open()).toBe(false);
    });

    it('clearFilter reseta seleção/datas/busca, emite state null e fecha', async () => {
      await montar();
      comp.toggleValue('ana'); // cria filtro ativo
      comp.dateFrom = '2026-07-01';
      comp.searchText.set('x');
      comp.open.set(true);
      const emit = ouvirFiltro();
      comp.clearFilter();
      expect(comp.allSelected()).toBe(true);
      expect(comp.dateFrom).toBe('');
      expect(comp.searchText()).toBe('');
      expect(comp.isActive()).toBe(false);
      expect(comp.open()).toBe(false);
      expect(emit.at(-1)).toEqual({ key: 'nome', state: null });
    });

    it('onDocClick fecha ao clicar fora; mantém ao clicar dentro', async () => {
      await montar();
      comp.open.set(true);
      comp.onDocClick({ target: fixture.nativeElement } as unknown as Event); // dentro
      expect(comp.open()).toBe(true);
      comp.onDocClick({ target: document.body } as unknown as Event); // fora
      expect(comp.open()).toBe(false);
    });
  });

  describe('posicionamento do painel', () => {
    it('toggle abre, reseta a posição e agenda o reposicionamento (rAF stubado, sem vazar)', async () => {
      await montar();
      const raf = vi
        .spyOn(window, 'requestAnimationFrame')
        .mockImplementation(() => 0 as unknown as number); // captura sem executar
      const evt = { stopPropagation: vi.fn() } as unknown as Event;
      expect(comp.open()).toBe(false);
      comp.toggle(evt);
      expect(comp.open()).toBe(true);
      expect(comp.panelTop).toBe(-9999);
      expect(comp.panelLeft).toBe(-9999);
      // agendou o reposicionamento (a contagem exata é poluída pelo rAF do scheduler
      // zoneless do Angular, que também reage à mudança do signal `open`)
      expect(raf).toHaveBeenCalled();
      comp.toggle(evt); // fecha
      expect(comp.open()).toBe(false);
    });

    it('positionPanel abre abaixo/à direita do trigger dentro da viewport', async () => {
      await montar();
      comp.open.set(true);
      fixture.detectChanges();
      const trigger = fixture.nativeElement.querySelector('.filter-trigger') as HTMLElement;
      const panel = fixture.nativeElement.querySelector('.filter-panel') as HTMLElement;
      trigger.getBoundingClientRect = () => ({ bottom: 100, right: 500, top: 80 } as DOMRect);
      panel.getBoundingClientRect = () => ({ width: 300, height: 200 } as DOMRect);
      (comp as unknown as { positionPanel(): void }).positionPanel();
      expect(comp.panelTop).toBe(106); // rect.bottom + 6
      expect(comp.panelLeft).toBe(200); // rect.right - panelWidth
    });

    it('positionPanel clampa à esquerda (margin) quando o painel excede a borda', async () => {
      await montar();
      comp.open.set(true);
      fixture.detectChanges();
      const trigger = fixture.nativeElement.querySelector('.filter-trigger') as HTMLElement;
      const panel = fixture.nativeElement.querySelector('.filter-panel') as HTMLElement;
      trigger.getBoundingClientRect = () => ({ bottom: 50, right: 100, top: 30 } as DOMRect);
      panel.getBoundingClientRect = () => ({ width: 300, height: 200 } as DOMRect);
      (comp as unknown as { positionPanel(): void }).positionPanel();
      expect(comp.panelLeft).toBe(8); // left = 100 - 300 = -200 < margin → 8
    });
  });
});

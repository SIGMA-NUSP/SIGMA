import { Component, EventEmitter, Input, Output, signal, computed, HostListener, ElementRef, ChangeDetectorRef, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface ColumnFilterDef {
  key: string;
  label: string;
  type: 'text' | 'date';
  sortable?: boolean;
}

export interface ColumnFilterState {
  text?: string;
  values?: string[] | null;  // null = all selected
  range?: { from?: string; to?: string };
}

@Component({
  selector: 'app-column-filter',
  standalone: true,
  imports: [FormsModule],
  template: `
    <span class="filter-trigger" [class.filter-active]="isActive()" (click)="toggle($event)">
      {{ col.label }}
      <span class="filter-icon">{{ isActive() ? '\u25BC' : '\u25BD' }}</span>
    </span>

    @if (open()) {
      <div class="filter-panel" [style.top.px]="panelTop" [style.left.px]="panelLeft"
           (click)="$event.stopPropagation()">
        <div class="filter-header">
          <strong>{{ col.label }}</strong>
          <button class="btn-close-filter" (click)="close()">&times;</button>
        </div>

        <!-- Sort -->
        @if (col.sortable !== false) {
          <div class="filter-section">
            <span class="section-title">Classificar</span>
            <div class="sort-buttons">
              <button class="btn-sort" [class.active]="currentSort === col.key && currentDir === 'asc'" (click)="onSort('asc')">&#9650; Crescente</button>
              <button class="btn-sort" [class.active]="currentSort === col.key && currentDir === 'desc'" (click)="onSort('desc')">&#9660; Decrescente</button>
            </div>
          </div>
        }

        <!-- Date range -->
        @if (col.type === 'date') {
          <div class="filter-section">
            <span class="section-title">Período</span>
            <div class="date-range">
              <input type="date" [(ngModel)]="dateFrom" (change)="onDateChange()" placeholder="De">
              <input type="date" [(ngModel)]="dateTo" (change)="onDateChange()" placeholder="Até">
            </div>
          </div>
        }

        <!-- Text search within values -->
        @if (distinctItems().length > 0) {
          <div class="filter-section">
            <span class="section-title">Filtrar valores</span>
            <input type="text" class="filter-search" [value]="searchText()"
                   (input)="searchText.set($any($event.target).value)" placeholder="Buscar...">
          </div>

          <!-- Checkboxes -->
          <div class="filter-values">
            <div class="filter-check" (click)="toggleAll()">
              <input type="checkbox" [checked]="allSelected()" tabindex="-1">
              <span>(Selecionar tudo)</span>
            </div>
            @for (item of filteredItems(); track item.value) {
              <div class="filter-check" (click)="toggleValue(item.value)">
                <input type="checkbox" [checked]="isSelected(item.value)" tabindex="-1">
                <span>{{ item.label || item.value || '(vazio)' }}</span>
              </div>
            }
          </div>
        }

        <!-- Actions -->
        <div class="filter-actions">
          <button class="btn-clear" (click)="clearFilter()">Limpar filtro</button>
        </div>
      </div>
    }
  `,
  styles: [`
    :host { position: relative; display: inline-flex; align-items: center; }
    .filter-trigger {
      cursor: pointer; user-select: none; display: inline-flex; align-items: center; gap: 4px;
      white-space: nowrap;
    }
    .filter-icon { font-size: .7rem; }
    .filter-active { color: var(--primary); font-weight: 700; }
    .filter-panel {
      position: fixed; z-index: 9999;
      background: #fff; border: 1px solid var(--border); border-radius: 10px;
      box-shadow: 0 10px 30px rgba(0,0,0,.15); padding: 12px;
      min-width: 280px; max-width: 340px; max-height: 420px; overflow: auto;
    }
    .filter-header {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--border);
    }
    .filter-header strong { font-size: .9rem; }
    .btn-close-filter {
      background: none; border: none; font-size: 1.2rem; cursor: pointer;
      color: var(--muted); padding: 0 4px; &:hover { color: var(--text); }
    }
    .filter-section { margin-bottom: 10px; }
    .section-title { font-size: .75rem; color: var(--muted); text-transform: uppercase; display: block; margin-bottom: 4px; }
    .sort-buttons { display: flex; gap: 4px; }
    .btn-sort {
      flex: 1; padding: 4px 8px; font-size: .8rem; border: 1px solid var(--border);
      border-radius: 4px; background: #fff; cursor: pointer;
      &:hover { background: var(--row-hover); }
      &.active { background: var(--primary); color: #fff; border-color: var(--primary); }
    }
    .date-range { display: flex; gap: 6px; }
    .date-range input { flex: 1; padding: 4px 6px; font-size: .8rem; border: 1px solid var(--border); border-radius: 4px; }
    .filter-search {
      width: 100%; padding: 5px 8px; font-size: .8rem;
      border: 1px solid var(--border); border-radius: 4px;
    }
    .filter-values {
      max-height: 200px; overflow-y: auto; margin-top: 6px;
      border: 1px solid var(--border); border-radius: 4px; padding: 4px;
    }
    .filter-check {
      display: flex; align-items: center; gap: 6px;
      padding: 3px 4px; font-size: .8rem; cursor: pointer;
      border-radius: 3px;
      &:hover { background: var(--row-hover); }
      input { margin: 0; pointer-events: none; }
      span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    }
    .filter-actions {
      margin-top: 8px; padding-top: 6px; border-top: 1px solid var(--border);
    }
    .btn-clear {
      width: 100%; padding: 5px; font-size: .8rem; border: 1px solid var(--border);
      border-radius: 4px; background: #fff; cursor: pointer;
      &:hover { background: #fef2f2; color: #b91c1c; }
    }
  `],
})
export class ColumnFilterComponent {
  private el = inject(ElementRef);
  private cdr = inject(ChangeDetectorRef);

  @Input() col!: ColumnFilterDef;
  @Input() distinctValues: { value: string; label: string }[] = [];
  @Input() currentSort = '';
  @Input() currentDir = '';
  @Output() sortChange = new EventEmitter<{ sort: string; direction: string }>();
  @Output() filterChange = new EventEmitter<{ key: string; state: ColumnFilterState | null }>();

  open = signal(false);
  searchText = signal('');
  dateFrom = '';
  dateTo = '';
  panelTop = -9999;
  panelLeft = -9999;
  private selectedValues = signal<string[] | null>(null); // null = all
  private needsPosition = false;

  distinctItems = computed(() => this.distinctValues || []);

  filteredItems = computed(() => {
    const items = this.distinctItems();
    const q = this.searchText();
    if (!q) return items;
    const lower = q.toLowerCase();
    return items.filter(i => (i.label || i.value || '').toLowerCase().includes(lower));
  });

  allSelected = computed(() => this.selectedValues() === null);

  isActive(): boolean {
    return this.selectedValues() !== null || !!this.dateFrom || !!this.dateTo;
  }

  isSelected(value: string): boolean {
    const sel = this.selectedValues();
    return sel === null || sel.includes(value);
  }

  toggle(e: Event): void {
    e.stopPropagation();
    const willOpen = !this.open();
    if (willOpen) {
      this.panelTop = -9999;
      this.panelLeft = -9999;
    }
    this.open.set(willOpen);
    if (willOpen) {
      // Aguardar dois frames para o painel ter dimensões reais no DOM
      requestAnimationFrame(() => requestAnimationFrame(() => this.positionPanel()));
    }
  }

  close(): void { this.open.set(false); }

  private positionPanel(): void {
    const trigger = this.el.nativeElement.querySelector('.filter-trigger') as HTMLElement;
    const panel = this.el.nativeElement.querySelector('.filter-panel') as HTMLElement;
    if (!trigger || !panel) return;

    const rect = trigger.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const margin = 8;

    // Tentar abrir abaixo, alinhado à direita do trigger
    let top = rect.bottom + 6;
    let left = rect.right - panelRect.width;

    // Ajuste horizontal: manter dentro da viewport
    if (left < margin) left = margin;
    const maxLeft = window.innerWidth - panelRect.width - margin;
    if (left > maxLeft) left = Math.max(margin, maxLeft);

    // Ajuste vertical: se não cabe abaixo, abrir acima
    const maxTop = window.innerHeight - panelRect.height - margin;
    if (top > maxTop) {
      top = rect.top - panelRect.height - 6;
    }
    if (top < margin) top = margin;

    this.panelTop = top;
    this.panelLeft = left;
    this.cdr.detectChanges();
  }

  @HostListener('document:click', ['$event'])
  onDocClick(e: Event): void {
    if (this.open() && !this.el.nativeElement.contains(e.target)) {
      this.close();
    }
  }

  onSort(dir: string): void {
    this.sortChange.emit({ sort: this.col.key, direction: dir });
    this.close();
  }

  toggleAll(): void {
    if (this.selectedValues() === null) {
      this.selectedValues.set([]);
    } else {
      this.selectedValues.set(null);
    }
    this.emitFilter();
  }

  toggleValue(value: string): void {
    let sel = this.selectedValues();
    if (sel === null) {
      // Estado "todos selecionados" — clicar em um seleciona APENAS esse
      sel = [value];
    } else if (sel.includes(value)) {
      sel = sel.filter(v => v !== value);
      // Se ficou vazio, voltar para "todos" para evitar tabela vazia
      if (sel.length === 0) { sel = null as any; }
    } else {
      sel = [...sel, value];
    }
    // Se todos selecionados, resetar para null
    if (sel && sel.length >= this.distinctItems().length) {
      this.selectedValues.set(null);
    } else {
      this.selectedValues.set(sel);
    }
    this.emitFilter();
  }

  onDateChange(): void {
    this.emitFilter();
  }

  clearFilter(): void {
    this.selectedValues.set(null);
    this.dateFrom = '';
    this.dateTo = '';
    this.searchText.set('');
    this.emitFilter();
    this.close();
  }

  private emitFilter(): void {
    const sel = this.selectedValues();
    const hasRange = this.dateFrom || this.dateTo;
    if (sel === null && !hasRange) {
      this.filterChange.emit({ key: this.col.key, state: null });
      return;
    }
    const state: ColumnFilterState = {};
    if (sel !== null) state.values = sel;
    if (hasRange) state.range = { from: this.dateFrom || undefined, to: this.dateTo || undefined };
    this.filterChange.emit({ key: this.col.key, state });
  }
}

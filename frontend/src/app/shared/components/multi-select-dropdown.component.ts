import { Component, input, output, signal, computed, ElementRef, inject, HostListener } from '@angular/core';

export interface MultiSelectOption {
  id: string;
  label: string;
}

@Component({
  selector: 'app-multi-select-dropdown',
  standalone: true,
  template: `
    <div class="ms-wrapper">
      <button type="button" class="ms-trigger" (click)="toggle()" [class.ms-open]="open()">
        <span class="ms-label">{{ displayLabel() }}</span>
        <span class="ms-arrow">{{ open() ? '\u25B2' : '\u25BC' }}</span>
      </button>
      @if (open()) {
        <div class="ms-dropdown">
          @for (opt of options(); track opt.id) {
            <label class="ms-option" [class.ms-locked]="lockedIds().includes(opt.id)" (click)="$event.stopPropagation()">
              <input type="checkbox" [checked]="selected().includes(opt.id)"
                [disabled]="lockedIds().includes(opt.id)"
                (change)="onToggle(opt.id, $event)">
              {{ opt.label }}
            </label>
          }
          @if (options().length === 0) {
            <div class="ms-empty">Nenhuma opção disponível</div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .ms-wrapper { position: relative; width: 100%; }
    .ms-trigger {
      display: flex; justify-content: space-between; align-items: center;
      width: 100%; padding: 8px 12px; border: 1px solid var(--border, #d1d5db);
      border-radius: 8px; background: #fff; cursor: pointer; font-size: .9rem;
      color: var(--text, #1f2937); text-align: left;
      &:hover { border-color: var(--primary, #003b63); }
    }
    .ms-open { border-color: var(--primary, #003b63); }
    .ms-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ms-arrow { font-size: .65rem; color: var(--muted, #6b7280); margin-left: 8px; }
    .ms-dropdown {
      position: absolute; top: 100%; left: 0; right: 0; z-index: 50;
      background: #fff; border: 1px solid var(--border, #d1d5db);
      border-radius: 8px; margin-top: 4px; padding: 4px 0;
      box-shadow: 0 4px 16px rgba(0,0,0,.12);
      max-height: 220px; overflow-y: auto;
    }
    .ms-option {
      display: flex; align-items: center; gap: 8px; padding: 8px 12px;
      cursor: pointer; font-size: .9rem; color: var(--text, #1f2937);
      /* blindagem: o dropdown é usado dentro de .form-row — sem isto a global ".form-row label" vazaria aqui */
      font-weight: 400; margin-bottom: 0;
      &:hover { background: #f3f4f6; }
      input[type="checkbox"] { width: 16px; height: 16px; cursor: pointer; flex-shrink: 0; }
    }
    .ms-locked { color: #9ca3af; cursor: default; }
    .ms-locked input[type="checkbox"] { cursor: default; opacity: .5; }
    .ms-empty { padding: 12px; text-align: center; color: var(--muted, #6b7280); font-size: .85rem; }
  `],
})
export class MultiSelectDropdownComponent {
  private elRef = inject(ElementRef);

  options = input.required<MultiSelectOption[]>();
  selected = input.required<string[]>();
  placeholder = input<string>('Selecione...');
  lockedIds = input<string[]>([]);

  selectionChange = output<string[]>();

  open = signal(false);

  toggle(): void {
    this.open.update(v => !v);
  }

  @HostListener('document:click', ['$event'])
  onDocClick(event: MouseEvent): void {
    if (!this.elRef.nativeElement.contains(event.target)) {
      this.open.set(false);
    }
  }

  onToggle(id: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const current = [...this.selected()];
    if (checked) {
      if (!current.includes(id)) current.push(id);
    } else {
      const idx = current.indexOf(id);
      if (idx >= 0) current.splice(idx, 1);
    }
    this.selectionChange.emit(current);
  }

  displayLabel = computed<string>(() => {
    const sel = this.selected();
    if (sel.length === 0) return this.placeholder();
    const opts = this.options();
    const names = sel.map(id => opts.find(o => o.id === id)?.label || id);
    return names.join(', ');
  });
}

import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PaginationMeta } from '../../core/models/user.model';

@Component({
  selector: 'app-pagination',
  standalone: true,
  imports: [FormsModule],
  template: `
    @if (meta && meta.total > 0) {
      <div class="pagination-controls">
        <div class="pag-info">
          Página {{ meta.page }} de {{ meta.pages }} ({{ meta.total }} registros)
          <span class="pag-limit-label">| Exibir</span>
          <select class="pag-limit-select" [ngModel]="meta.limit" (ngModelChange)="onLimitChange($event)">
            @for (opt of limitOptions; track opt) {
              <option [value]="opt">{{ opt }}</option>
            }
          </select>
          <span class="pag-limit-label">por página</span>
        </div>
        @if (meta.pages > 1) {
          <div class="pag-buttons">
            <button (click)="go(1)" [disabled]="meta.page <= 1">&#171;</button>
            <button (click)="go(meta.page - 1)" [disabled]="meta.page <= 1">&#8249;</button>
            <input type="number" [(ngModel)]="pageInput" (keyup.enter)="go(pageInput)" min="1" [max]="meta.pages">
            <button (click)="go(pageInput)" class="btn-ir">Ir</button>
            <button (click)="go(meta.page + 1)" [disabled]="meta.page >= meta.pages">&#8250;</button>
            <button (click)="go(meta.pages)" [disabled]="meta.page >= meta.pages">&#187;</button>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .pagination-controls {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 0;
      font-size: 0.85rem;
      flex-wrap: wrap;
      gap: 8px;
    }
    .pag-info { color: var(--muted); display:flex; align-items:center; gap:4px; flex-wrap:wrap; }
    .pag-limit-label { font-size:.85rem; }
    .pag-limit-select { padding:3px 6px; font-size:.85rem; border:1px solid var(--border); border-radius:4px; cursor:pointer; }
    .pag-buttons {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .pag-buttons button {
      padding: 4px 10px;
      border: 1px solid var(--border);
      border-radius: 4px;
      background: #fff;
      cursor: pointer;
      font-size: 0.85rem;
      &:disabled { opacity: .4; cursor: not-allowed; }
      &:hover:not(:disabled) { background: var(--row-hover); }
    }
    .pag-buttons input {
      width: 50px;
      text-align: center;
      padding: 4px;
      font-size: 0.85rem;
    }
  `],
})
export class PaginationComponent implements OnChanges {
  @Input() meta!: PaginationMeta;
  @Output() pageChange = new EventEmitter<number>();
  @Output() limitChange = new EventEmitter<number>();

  pageInput = 1;
  limitOptions = [10, 20, 30, 50, 100];

  ngOnChanges(): void {
    if (this.meta) this.pageInput = this.meta.page;
  }

  go(page: number): void {
    const p = Math.max(1, Math.min(page, this.meta.pages));
    if (p !== this.meta.page) this.pageChange.emit(p);
  }

  onLimitChange(value: number | string): void {
    this.limitChange.emit(+value);
  }
}

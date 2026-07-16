import { Component, Injectable, signal } from '@angular/core';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

interface ToastMessage {
  id: number;
  text: string;
  type: ToastType;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly messages = signal<ToastMessage[]>([]);
  private nextId = 0;

  show(text: string, type: ToastType = 'info', durationMs = 4000): void {
    const id = ++this.nextId;
    this.messages.update(msgs => [...msgs, { id, text, type }]);
    setTimeout(() => this.dismiss(id), durationMs);
  }

  success(text: string, durationMs = 8000): void { this.show(text, 'success', durationMs); }
  error(text: string, durationMs = 12000): void { this.show(text, 'error', durationMs); }
  warning(text: string, durationMs = 10000): void { this.show(text, 'warning', durationMs); }

  dismiss(id: number): void {
    this.messages.update(msgs => msgs.filter(m => m.id !== id));
  }
}

@Component({
  selector: 'app-toast',
  standalone: true,
  template: `
    <div class="toast-container">
      @for (msg of toast.messages(); track msg.id) {
        <div class="toast-item" [class]="'toast-' + msg.type" (click)="toast.dismiss(msg.id)">
          <span class="toast-icon">
            @switch (msg.type) {
              @case ('success') { ✓ }
              @case ('error') { ✕ }
              @case ('warning') { ⚠ }
              @default { ℹ }
            }
          </span>
          <span class="toast-text">{{ msg.text }}</span>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-container {
      position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
      z-index: 99999; display: flex; flex-direction: column; gap: 8px;
      max-width: 90vw; width: 420px; pointer-events: none;
    }
    .toast-item {
      display: flex; align-items: center; gap: 10px;
      padding: 14px 18px; border-radius: 10px;
      box-shadow: 0 8px 24px rgba(0,0,0,.18);
      font-size: .95rem; font-weight: 500; cursor: pointer; pointer-events: auto;
      animation: toast-in .3s ease-out;
    }
    .toast-icon { font-size: 1.1rem; flex-shrink: 0; }
    .toast-text { flex: 1; }
    .toast-success { background: #f0fdf4; color: #166534; border: 1px solid #bbf7d0; }
    .toast-error { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
    .toast-warning { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
    .toast-info { background: #eff6ff; color: #1e40af; border: 1px solid #bfdbfe; }
    @keyframes toast-in {
      from { opacity: 0; transform: translateY(-12px); }
      to { opacity: 1; transform: translateY(0); }
    }
  `],
})
export class ToastComponent {
  constructor(public toast: ToastService) {}
}

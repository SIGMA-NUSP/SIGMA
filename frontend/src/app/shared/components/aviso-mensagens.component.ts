import { Component, model } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Bloco reutilizável de mensagens de um cadastro de aviso (1 a {@link MAX_MENSAGENS}), com os botões
 * "+ Novo Aviso" e "Remover". Two-way via `[(mensagens)]`. Usado pelos painéis Escala, Agenda e
 * Pessoal do cadastro de avisos — o painel Verificação mantém o seu bloco inline (intocado). As
 * classes de layout (`.form-row`, `.btn-outline`) vêm do CSS global; `.req`/`.msg-actions` são locais.
 */
@Component({
  selector: 'app-aviso-mensagens',
  standalone: true,
  imports: [FormsModule],
  template: `
    @for (msg of mensagens(); track $index) {
      <div class="form-row">
        <label>{{ $index + 1 }}º Aviso <span class="req">*</span></label>
        <textarea [ngModel]="mensagens()[$index]" (ngModelChange)="atualizar($index, $event)"
                  [name]="'msg_' + $index" rows="2"></textarea>
      </div>
    }
    <div class="msg-actions">
      @if (mensagens().length < MAX_MENSAGENS) {
        <button type="button" class="btn-outline" (click)="add()">+ Novo Aviso</button>
      }
      @if (mensagens().length > 1) {
        <button type="button" class="btn-outline" (click)="remover()">Remover</button>
      }
    </div>
  `,
  styles: [`
    .form-row textarea { resize: vertical; width: 100%; }
    .req { color:#dc2626; }
    .msg-actions { display:flex; gap:8px; margin-bottom:4px; }
  `],
})
export class AvisoMensagensComponent {
  readonly MAX_MENSAGENS = 10;

  /** Lista das mensagens (two-way). Começa com uma mensagem vazia. */
  mensagens = model<string[]>(['']);

  atualizar(i: number, valor: string): void {
    const arr = [...this.mensagens()];
    arr[i] = valor;
    this.mensagens.set(arr);
  }

  add(): void {
    if (this.mensagens().length < this.MAX_MENSAGENS) this.mensagens.set([...this.mensagens(), '']);
  }

  remover(): void {
    if (this.mensagens().length > 1) this.mensagens.set(this.mensagens().slice(0, -1));
  }
}

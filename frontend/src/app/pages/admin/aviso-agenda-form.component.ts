import { Component, inject, output, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoMensagensComponent } from '../../shared/components/aviso-mensagens.component';
import { httpErrorMsg } from '../../core/helpers/http.helpers';

/**
 * Painel "Agenda" do cadastro de avisos: só mensagens. O backend força alvo TODOS (operadores e
 * técnicos), permanente e subtipo AGENDA — a vigência é por usuário (o "visto"), sem permanente/
 * duração no form nem seleção de público. Emite {@link cadastrado} no sucesso.
 */
@Component({
  selector: 'app-aviso-agenda-form',
  standalone: true,
  imports: [AvisoMensagensComponent],
  template: `
    <section class="card-custom painel-aviso">
      <p class="painel-hint">
        O aviso aparece como popup para operadores e técnicos ao entrarem na Agenda Legislativa,
        uma única vez por pessoa.
      </p>

      <app-aviso-mensagens [(mensagens)]="mensagens" />

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div class="painel-actions">
        <button class="btn-primary-custom" [disabled]="saving()" (click)="onSubmit()">
          {{ saving() ? 'Salvando...' : 'Cadastrar Aviso' }}
        </button>
      </div>
    </section>
  `,
  styles: [`
    :host { display:block; }
    .painel-aviso { max-width:720px; margin: 4px auto 24px; }
    .painel-hint { color: var(--muted, #6b7280); font-size:.85rem; margin: 0 0 12px; }
    .painel-actions { display:flex; justify-content:flex-end; margin-top:12px; }
  `],
})
export class AvisoAgendaFormComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  /** Emitido após um cadastro bem-sucedido — o pai recarrega a listagem. */
  cadastrado = output<void>();

  mensagens: string[] = [''];
  saving = signal(false);
  errorMsg = signal('');

  onSubmit(): void {
    this.errorMsg.set('');
    const msgs = this.mensagens.map(m => m.trim());
    if (msgs.some(m => !m)) { this.errorMsg.set('Preencha todas as mensagens.'); return; }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos', {
      tipo: 'AGENDA',
      mensagens: msgs,
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.mensagens = [''];
          this.cadastrado.emit();
        } else {
          this.errorMsg.set(res.message || res.error || 'Erro ao cadastrar.');
        }
      },
      error: err => {
        this.saving.set(false);
        this.errorMsg.set(httpErrorMsg(err, 'Erro ao cadastrar.'));
      },
    });
  }
}

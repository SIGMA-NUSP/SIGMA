import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoMensagensComponent } from '../../shared/components/aviso-mensagens.component';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';

interface EscalaDisponivel {
  id: number;
  data_inicio: string;
  data_fim: string;
  cadastro_numero: number | null;
  plenarios: { sala_id: number; sala_nome: string }[];
}

/**
 * Painel "Escala" do cadastro de avisos: escolhe uma escala (atual/futura) e os plenários dela; as
 * mensagens valem para os operadores escalados nesses plenários. Sem permanente/duração — a vigência
 * é o período da escala. Escala já com aviso não-desativado aparece travada ("— Cadastro nº X"). A
 * carga das escalas é FAIL-CLOSED: se falhar, o cadastro fica bloqueado (não dá para saber quais
 * escalas estão ocupadas). Emite {@link cadastrado} no sucesso.
 */
@Component({
  selector: 'app-aviso-escala-form',
  standalone: true,
  imports: [FormsModule, MultiSelectDropdownComponent, AvisoMensagensComponent, ErroCargaComponent, FmtDatePipe],
  template: `
    <section class="card-custom painel-aviso">
      <div class="form-row">
        <label>Escala <span class="req">*</span></label>
        <select [ngModel]="escalaId()" (ngModelChange)="onEscalaChange($event)" name="escala">
          <option [ngValue]="null">Selecione a escala...</option>
          @for (e of escalas(); track e.id) {
            <option [ngValue]="e.id" [disabled]="e.cadastro_numero != null">
              {{ e.data_inicio | fmtDate }} — {{ e.data_fim | fmtDate }}{{ e.cadastro_numero != null ? ' — Cadastro nº ' + e.cadastro_numero : '' }}
            </option>
          }
        </select>
      </div>

      @if (erroEscalas()) {
        <!-- FAIL-CLOSED: sem saber quais escalas já têm aviso, o dropdown mostraria todas como livres. -->
        <div class="form-row">
          <app-erro-carga [mensagem]="erroEscalas()" (tentarNovamente)="carregarEscalas()" />
        </div>
      }

      @if (escalaId() != null) {
        <div class="form-row">
          <label>Local <span class="req">*</span></label>
          <app-multi-select-dropdown
            [options]="plenarioOptions()"
            [selected]="selectedPlenarios"
            placeholder="Selecione um ou mais plenários..."
            (selectionChange)="selectedPlenarios = $event" />
        </div>
      }

      <app-aviso-mensagens [(mensagens)]="mensagens" />

      <div class="form-row">
        <label class="check-opt">
          <input type="checkbox" [(ngModel)]="manterAposCiencia" name="manter">
          Manter aviso após ciência do operador
        </label>
      </div>

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div class="painel-actions">
        <button class="btn-primary-custom" [disabled]="saving() || escalasIndisponiveis()" (click)="onSubmit()">
          {{ saving() ? 'Salvando...' : 'Cadastrar Aviso' }}
        </button>
      </div>
    </section>
  `,
  styles: [`
    :host { display:block; }
    .painel-aviso { max-width:720px; margin: 4px auto 24px; }
    .painel-actions { display:flex; justify-content:flex-end; margin-top:12px; }
    .req { color:#dc2626; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
  `],
})
export class AvisoEscalaFormComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  cadastrado = output<void>();

  escalas = signal<EscalaDisponivel[]>([]);
  erroEscalas = signal('');
  loadingEscalas = signal(true);

  escalaId = signal<number | null>(null);
  selectedPlenarios: string[] = [];
  mensagens: string[] = [''];
  manterAposCiencia = false;
  saving = signal(false);
  errorMsg = signal('');

  /** FAIL-CLOSED: enquanto a carga das escalas não tiver sucedido (em voo ou falhou), envio bloqueado. */
  escalasIndisponiveis = computed(() => this.loadingEscalas() || !!this.erroEscalas());

  /** Plenários da escala selecionada (fonte única: o próprio endpoint das escalas). Reage ao signal
   *  escalaId — por isso ele é signal e não propriedade, ao contrário dos demais campos do form. */
  plenarioOptions = computed<MultiSelectOption[]>(() => {
    const esc = this.escalas().find(e => e.id === this.escalaId());
    return (esc?.plenarios || []).map(p => ({ id: String(p.sala_id), label: p.sala_nome }));
  });

  ngOnInit(): void {
    this.carregarEscalas();
  }

  carregarEscalas(): void {
    this.erroEscalas.set('');
    this.loadingEscalas.set(true);
    this.api.get<any>('/api/admin/avisos/escalas-disponiveis').subscribe({
      next: res => {
        this.escalas.set(res?.data || []);
        this.loadingEscalas.set(false);
      },
      error: err => {
        this.loadingEscalas.set(false);
        this.erroEscalas.set(erroCargaMsg(err,
          'Não foi possível carregar as escalas. O cadastro fica bloqueado até recarregar.'));
      },
    });
  }

  /** Trocar de escala grava o novo id e zera os plenários (os da escala anterior não valem). */
  onEscalaChange(escalaId: number | null): void {
    this.escalaId.set(escalaId);
    this.selectedPlenarios = [];
  }

  onSubmit(): void {
    if (this.escalasIndisponiveis()) return;   // defesa dupla além do [disabled]
    this.errorMsg.set('');
    if (this.escalaId() == null) { this.errorMsg.set('Selecione a escala do aviso.'); return; }
    if (this.selectedPlenarios.length === 0) { this.errorMsg.set('Selecione ao menos um local.'); return; }
    const msgs = this.mensagens.map(m => m.trim());
    if (msgs.some(m => !m)) { this.errorMsg.set('Preencha todas as mensagens.'); return; }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos', {
      tipo: 'ESCALA',
      escala_id: this.escalaId(),
      sala_ids: this.selectedPlenarios.map(Number),
      manter_apos_ciencia: this.manterAposCiencia,
      mensagens: msgs,
    }).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.resetForm();
          this.carregarEscalas();   // a escala recém-usada passa a aparecer travada
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

  private resetForm(): void {
    this.escalaId.set(null);
    this.selectedPlenarios = [];
    this.mensagens = [''];
    this.manterAposCiencia = false;
  }
}

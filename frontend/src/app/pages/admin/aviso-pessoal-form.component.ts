import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../shared/components/toast.component';
import { AvisoMensagensComponent } from '../../shared/components/aviso-mensagens.component';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { ErroCargaComponent } from '../../shared/components/erro-carga.component';
import { erroCargaMsg, httpErrorMsg } from '../../core/helpers/http.helpers';

interface Pessoa { id: string; nome: string; tipo: 'OPERADOR' | 'TECNICO' | 'ADMINISTRADOR'; }

const SECAO: Record<string, string> = { OPERADOR: 'Operadores', TECNICO: 'Técnicos', ADMINISTRADOR: 'Administradores' };
const ORDEM_TIPO = ['OPERADOR', 'TECNICO', 'ADMINISTRADOR'];

/**
 * Painel "Pessoal" do cadastro de avisos, com dois modos (radio "Enviar para"):
 * - "Pessoas específicas": tipo PESSOAL, com ciência; multi-select em seções Operadores/Técnicos/
 *   Administradores (as três listas de {@code /api/admin/avisos/pessoas}, endpoint próprio de
 *   Avisos); checkbox "Manter após ciência" só aqui; a API recebe as listas mistas (alvo "PESSOAS").
 * - "Um grupo": tipo GERAL, sem ciência; um dos coletivos (Operadores/Técnicos/ambos/Admins).
 *
 * A carga das pessoas é FAIL-CLOSED no modo pessoas (envio bloqueado até a lista carregar). Emite
 * {@link cadastrado} no sucesso.
 */
@Component({
  selector: 'app-aviso-pessoal-form',
  standalone: true,
  imports: [FormsModule, MultiSelectDropdownComponent, AvisoMensagensComponent, ErroCargaComponent],
  template: `
    <section class="card-custom painel-aviso">
      <div class="form-row">
        <label>Enviar para <span class="req">*</span></label>
        <div class="radio-row">
          <label class="radio-opt"><input type="radio" [(ngModel)]="modo" name="modo" value="pessoas"> Pessoas específicas</label>
          <label class="radio-opt"><input type="radio" [(ngModel)]="modo" name="modo" value="grupo"> Um grupo</label>
        </div>
      </div>

      @if (modo === 'pessoas') {
        <div class="form-row">
          <label>Destinatário <span class="req">*</span></label>
          <app-multi-select-dropdown
            [options]="pessoaOptions()"
            [selected]="selectedPessoas"
            placeholder="Selecione uma ou mais pessoas..."
            (selectionChange)="selectedPessoas = $event" />
        </div>
        @if (erroPessoas()) {
          <div class="form-row">
            <app-erro-carga [mensagem]="erroPessoas()" (tentarNovamente)="carregarPessoas()" />
          </div>
        }
      } @else {
        <div class="form-row">
          <label>Grupo <span class="req">*</span></label>
          <select [(ngModel)]="grupo" name="grupo">
            <option value="TODOS_OPERADORES">Operadores</option>
            <option value="TODOS_TECNICOS">Técnicos</option>
            <option value="TODOS">Operadores e Técnicos</option>
            <option value="TODOS_ADMIN">Administradores</option>
          </select>
        </div>
      }

      <app-aviso-mensagens [(mensagens)]="mensagens" />

      <div class="form-row" style="margin-top:14px">
        <label>Aviso permanente <span class="req">*</span></label>
        <div class="radio-row">
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="true"> Sim</label>
          <label class="radio-opt"><input type="radio" [(ngModel)]="permanente" name="permanente" [value]="false"> Não</label>
          @if (!permanente) {
            <span class="duracao-inline">
              <label>Duração (dias) <span class="req">*</span></label>
              <input type="number" min="1" max="30" [(ngModel)]="duracaoDias" name="duracao_dias">
            </span>
          }
        </div>
      </div>

      @if (modo === 'pessoas') {
        <div class="form-row">
          <label class="check-opt">
            <input type="checkbox" [(ngModel)]="manterAposCiencia" name="manter">
            Manter aviso após ciência
          </label>
        </div>
      }

      @if (errorMsg()) { <div class="error-box">{{ errorMsg() }}</div> }

      <div class="painel-actions">
        <button class="btn-primary-custom" [disabled]="saving() || (modo === 'pessoas' && pessoasIndisponiveis())" (click)="onSubmit()">
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
    .radio-row { display:flex; align-items:center; gap:18px; flex-wrap:wrap; }
    .radio-opt { display:flex; align-items:center; gap:6px; font-weight:500; margin:0; cursor:pointer; }
    .radio-opt input { width:auto; }
    .duracao-inline { display:flex; align-items:center; gap:8px; }
    .duracao-inline label { margin:0; white-space:nowrap; }
    .duracao-inline input { width:90px; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
  `],
})
export class AvisoPessoalFormComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  cadastrado = output<void>();

  pessoas = signal<Pessoa[]>([]);
  erroPessoas = signal('');
  loadingPessoas = signal(true);

  modo: 'pessoas' | 'grupo' = 'pessoas';
  selectedPessoas: string[] = [];
  grupo = 'TODOS_OPERADORES';
  mensagens: string[] = [''];
  permanente = true;
  duracaoDias: number | null = null;
  manterAposCiencia = false;
  saving = signal(false);
  errorMsg = signal('');

  /** FAIL-CLOSED (modo pessoas): sem a lista de pessoas, o multi-select mentiria "ninguém disponível". */
  pessoasIndisponiveis = computed(() => this.loadingPessoas() || !!this.erroPessoas());

  /** Opções agrupadas nas seções Operadores/Técnicos/Administradores (nessa ordem; nomes já em ordem pt-BR). */
  pessoaOptions = computed<MultiSelectOption[]>(() =>
    [...this.pessoas()]
      .sort((a, b) => ORDEM_TIPO.indexOf(a.tipo) - ORDEM_TIPO.indexOf(b.tipo))   // sort estável preserva a ordem alfabética dentro do tipo
      .map(p => ({ id: p.id, label: p.nome, group: SECAO[p.tipo] ?? p.tipo })));

  ngOnInit(): void {
    this.carregarPessoas();
  }

  carregarPessoas(): void {
    this.erroPessoas.set('');
    this.loadingPessoas.set(true);
    this.api.get<any>('/api/admin/avisos/pessoas').subscribe({
      next: res => {
        this.pessoas.set(res?.data || []);
        this.loadingPessoas.set(false);
      },
      error: err => {
        this.loadingPessoas.set(false);
        this.erroPessoas.set(erroCargaMsg(err,
          'Não foi possível carregar a lista de pessoas. O cadastro fica bloqueado até recarregar.'));
      },
    });
  }

  onSubmit(): void {
    if (this.modo === 'pessoas' && this.pessoasIndisponiveis()) return;   // defesa dupla além do [disabled]
    this.errorMsg.set('');
    const msgs = this.mensagens.map(m => m.trim());
    if (msgs.some(m => !m)) { this.errorMsg.set('Preencha todas as mensagens.'); return; }
    if (!this.permanente && (!this.duracaoDias || this.duracaoDias < 1 || this.duracaoDias > 30)) {
      this.errorMsg.set('A duração deve estar entre 1 e 30 dias.'); return;
    }

    let payload: Record<string, unknown>;
    if (this.modo === 'pessoas') {
      if (this.selectedPessoas.length === 0) { this.errorMsg.set('Selecione ao menos um destinatário.'); return; }
      const tipoDe = new Map(this.pessoas().map(p => [p.id, p.tipo]));
      payload = {
        tipo: 'PESSOAL',
        alvo_tipo: 'PESSOAS',
        operador_ids: this.selectedPessoas.filter(id => tipoDe.get(id) === 'OPERADOR'),
        tecnico_ids: this.selectedPessoas.filter(id => tipoDe.get(id) === 'TECNICO'),
        admin_ids: this.selectedPessoas.filter(id => tipoDe.get(id) === 'ADMINISTRADOR'),
        permanente: this.permanente,
        duracao_dias: this.permanente ? null : this.duracaoDias,
        manter_apos_ciencia: this.manterAposCiencia,
        mensagens: msgs,
      };
    } else {
      payload = {
        tipo: 'GERAL',
        alvo_tipo: this.grupo,
        permanente: this.permanente,
        duracao_dias: this.permanente ? null : this.duracaoDias,
        mensagens: msgs,
      };
    }

    this.saving.set(true);
    this.api.post<any>('/api/admin/avisos', payload).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Aviso cadastrado com sucesso.');
          this.resetForm();
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
    this.selectedPessoas = [];
    this.mensagens = [''];
    this.permanente = true;
    this.duracaoDias = null;
    this.manterAposCiencia = false;
    // `modo` e `grupo` ficam como estão (o admin costuma cadastrar vários do mesmo tipo em sequência).
  }
}

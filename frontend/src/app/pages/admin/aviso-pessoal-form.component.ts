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
 * - "Pessoas específicas": tipo PESSOAL; multi-select em seções Operadores/Técnicos/Administradores
 *   (as três listas de {@code /api/admin/avisos/pessoas}, endpoint próprio de Avisos); a API recebe
 *   as listas mistas (alvo "PESSOAS").
 * - "Um grupo": tipo GERAL; um dos coletivos (Operadores/Técnicos/ambos/Admins).
 *
 * A ciência do destinatário é escolha do admin nos dois modos, com default por modo (pessoas pede,
 * grupo não) e comanda a existência do "manter após ciência" — trocar de modo volta ao default.
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
          <!-- O default de ciência muda por modo; a troca vem do evento do DOM (o ngModelChange do
               radio dispara também na sincronização do próprio ciclo de detecção). -->
          <label class="radio-opt"><input type="radio" [(ngModel)]="modo" name="modo" value="pessoas" (change)="onModoChange('pessoas')"> Pessoas específicas</label>
          <label class="radio-opt"><input type="radio" [(ngModel)]="modo" name="modo" value="grupo" (change)="onModoChange('grupo')"> Um grupo</label>
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

      <div class="form-row">
        <label class="check-opt">
          <input type="checkbox" [(ngModel)]="exigeCiencia" name="ciencia" (change)="onCienciaChange(exigeCiencia)">
          Exigir ciência do destinatário
        </label>
      </div>

      @if (exigeCiencia) {
        <div class="form-row">
          <label class="check-opt check-sub">
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
    .duracao-inline { display:flex; align-items:center; gap:8px; }
    .duracao-inline label { margin:0; white-space:nowrap; }
    .duracao-inline input { width:90px; }
    .check-opt { display:flex; align-items:center; gap:8px; font-weight:500; cursor:pointer; }
    .check-opt input { width:auto; }
    /* Subordinado à ciência: só existe quando ela está ligada. */
    .check-sub { margin-left:26px; }
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
  /** Mensagem a pessoas nasce pedindo ciência; comunicado a um grupo, não (defaults por modo). */
  exigeCiencia = true;
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

  /** Cada modo tem seu default de ciência: mensagem a pessoas pede, comunicado a grupo não. */
  onModoChange(modo: 'pessoas' | 'grupo'): void {
    this.modo = modo;
    this.onCienciaChange(modo === 'pessoas');
  }

  /** Sem ciência não há o que manter: desligar a ciência zera o "manter" junto com o campo escondido. */
  onCienciaChange(exige: boolean): void {
    this.exigeCiencia = exige;
    if (!exige) this.manterAposCiencia = false;
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
          'Não foi possível concluir a operação.'));
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
        exige_ciencia: this.exigeCiencia,
        manter_apos_ciencia: this.manterAposCiencia,
        mensagens: msgs,
      };
    } else {
      payload = {
        tipo: 'GERAL',
        alvo_tipo: this.grupo,
        permanente: this.permanente,
        duracao_dias: this.permanente ? null : this.duracaoDias,
        exige_ciencia: this.exigeCiencia,
        manter_apos_ciencia: this.manterAposCiencia,
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
    this.exigeCiencia = this.modo === 'pessoas';   // default de ciência do modo corrente
    // `modo` e `grupo` ficam como estão (o admin costuma cadastrar vários do mesmo tipo em sequência).
  }
}

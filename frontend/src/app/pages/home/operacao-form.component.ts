import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LookupService } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { extractDate, extractTime, toISODate } from '../../core/helpers/date.helpers';
import { SALA_DEMAIS_SALAS_ID, focusFirst } from '../../core/helpers/form.helpers';
import { httpErrorMsg } from '../../core/helpers/http.helpers';

type Situacao = 'inicial' | 'sem_sessao' | 'sem_entrada' | 'uma_entrada' | 'duas_entradas';

@Component({
  selector: 'app-operacao-form',
  standalone: true,
  imports: [FormsModule, RouterLink, MultiSelectDropdownComponent],
  template: `
    <div class="card-custom" style="max-width:800px; margin:0 auto">

      <!-- Info sessão (acima do título, como no original) -->
      @if (!editMode() && !loading() && sessaoAberta()) {
        <div class="sessao-header-row">
          <div class="sessao-operadores" [innerHTML]="infoOperadoresSessao()"></div>
        </div>
      }

      <!-- Header -->
      @if (editMode()) {
        <div class="detalhe-header">
          <h1>{{ isRO() ? 'Detalhe do Registro de Operação' : 'Editar Registro de Operação' }}</h1>
          @if (editData()?.['editado']) {
            <span class="badge-edited">editado</span>
          }
        </div>
      } @else {
        <h2 class="form-title">Registro de Operação de Áudio</h2>
        <p class="hint-text" style="margin-bottom:16px">Preencha os dados da operação de áudio realizada no local selecionado.</p>
      }

      @if (editMode() && isMultiOperador && !canEditOperacao() && editData()?.['operador_nome']) {
        <p class="hint-text" style="margin-bottom:16px">Somente {{ editData()!['operador_nome'] }} pode editar este formulário.</p>
      }

      @if (loading()) {
        <p style="color:var(--muted)">Carregando...</p>
      } @else {

        <form (ngSubmit)="onSubmit()">

          <!-- Local -->
          <div class="form-row">
            <label>Local <span class="req">*</span> @if (editData()?.['sala_editado']) { <span class="badge-edited">editado</span> }</label>
            @if (editMode()) {
              <input [value]="salaNome" readonly disabled style="width:100%" class="field-ro">
            } @else {
              <select [(ngModel)]="salaId" name="sala_id" (ngModelChange)="onSalaChange()" style="width:100%">
                <option value="">Selecione...</option>
                @for (s of lookup.salas(); track s.id) {
                  <option [value]="s.id">{{ s.nome }}</option>
                }
              </select>
            }
          </div>

          <!-- Nome da Sala (somente para "Demais Salas") -->
          @if (isDemaisSalas()) {
            <div class="form-row">
              <label>Nome da Sala <span class="req">*</span></label>
              <input [(ngModel)]="nomeDemaisSalas" name="nome_demais_salas"
                     [disabled]="formDisabled()"
                     [readonly]="isRO() || nomeDemaisSalasReadonly()"
                     [class.field-ro]="isRO() || nomeDemaisSalasReadonly()">
            </div>
          }

          <!-- Operadores (Plenário Principal) -->
          @if (isMultiOperador && !editMode()) {
            <div class="form-row">
              <label>Operadores <span class="req">*</span></label>
              <app-multi-select-dropdown
                [options]="operadorOptions()"
                [selected]="selectedOperadorIds"
                [lockedIds]="lockedOperadorIds"
                placeholder="Selecione operadores..."
                (selectionChange)="selectedOperadorIds = $event" />
            </div>
          }
          @if (isMultiOperador && editMode()) {
            <div class="form-row">
              <label>Operadores da Sessão</label>
              @if (isRO()) {
                <input type="text" class="field-ro" [value]="editData()?.['operadores_sessao']?.join(', ') || '—'" readonly>
              } @else {
                <app-multi-select-dropdown
                  [options]="operadorOptions()"
                  [selected]="selectedOperadorIds"
                  [lockedIds]="lockedOperadorIds"
                  placeholder="Selecione operadores..."
                  (selectionChange)="selectedOperadorIds = $event" />
              }
            </div>
          }

          <!-- Atividade Legislativa (condicional) -->
          @if (showComissao() && !isMultiOperador) {
            <div class="form-row">
              <label>Atividade Legislativa <span class="req">*</span> @if (editData()?.['comissao_editado']) { <span class="badge-edited">editado</span> }</label>
              @if (isRO() || camposSessaoReadonly()) {
                <div class="field-value">{{ comissaoNome || '-' }}</div>
              } @else {
                <select [(ngModel)]="comissaoId" name="comissao_id" [disabled]="formDisabled() || comissaoTravada" style="width:100%">
                  <option value="">Selecione o tipo</option>
                  @for (c of lookup.comissoes(); track c.id) {
                    <option [value]="c.id">{{ c.nome }}</option>
                  }
                </select>
              }
            </div>
          }

          <!-- Descrição do Evento -->
          <div class="form-row">
            <label>Descrição do Evento <span class="req">*</span> @if (editData()?.['nome_evento_editado']) { <span class="badge-edited">editado</span> }</label>
            <input [(ngModel)]="nomeEvento" name="nome_evento" placeholder="Ex.: 37ª reunião..." [disabled]="formDisabled()" [readonly]="isRO() || camposSessaoReadonly()" [class.field-ro]="isRO() || camposSessaoReadonly()">
          </div>

          <!-- Responsável pelo Evento (não exibido no Plenário Principal) -->
          @if (!isMultiOperador) {
          <div class="form-row">
            <label>Responsável pelo Evento <span class="req">*</span> @if (editData()?.['responsavel_evento_editado']) { <span class="badge-edited">editado</span> }</label>
            <input [(ngModel)]="responsavelEvento" name="responsavel_evento" placeholder="Ex.: Secretário da Reunião ou da Mesa" [disabled]="formDisabled()" [readonly]="isRO() || camposSessaoReadonly()" [class.field-ro]="isRO() || camposSessaoReadonly()">
          </div>
          }

          @if (isMultiOperador) {
          <!-- Plenário Principal: Data + Início + Término -->
          <div class="form-grid-3">
            <div class="form-row">
              <label>Data <span class="req">*</span> @if (editData()?.['data_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="date" [(ngModel)]="dataOperacao" name="data_operacao" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()">
            </div>
            <div class="form-row">
              <label>Início da sessão <span class="req">*</span> @if (editData()?.['horario_inicio_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horaInicio" name="hora_inicio" step="60" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()">
            </div>
            <div class="form-row">
              <label>Término da sessão <span class="req">*</span> @if (editData()?.['horario_termino_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horaFim" name="hora_fim" step="60" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()">
            </div>
          </div>

          <!-- Suspensões -->
          <div class="form-row">
            <label>Suspensões @if (editData()?.['suspensoes_editado']) { <span class="badge-edited">editado</span> }</label>
            @if (isRO()) {
              @for (susp of suspensoes; track $index) {
                <div class="field-value" style="margin-bottom:4px">
                  Suspensa em: {{ susp.hora_suspensao || '-' }} &mdash; Reaberta em: {{ susp.hora_reabertura || '-' }}
                </div>
              }
              @if (suspensoes.length === 0) {
                <div class="field-value">Nenhuma</div>
              }
            } @else {
              @for (susp of suspensoes; track $index) {
                <div class="form-grid-3" style="margin-bottom:8px; align-items:end">
                  <div class="form-row" style="margin-bottom:0">
                    <label style="font-size:.8rem; color:var(--muted)">Suspensa em:</label>
                    <input type="time" [(ngModel)]="susp.hora_suspensao" [name]="'susp_' + $index" step="60">
                  </div>
                  <div class="form-row" style="margin-bottom:0">
                    <label style="font-size:.8rem; color:var(--muted)">Reaberta em:</label>
                    <input type="time" [(ngModel)]="susp.hora_reabertura" [name]="'reab_' + $index" step="60">
                  </div>
                  <button type="button" class="btn-outline" style="height:38px; padding:0 12px; font-size:.8rem" (click)="removeSuspensao($index)">Remover</button>
                </div>
              }
              <button type="button" class="btn-outline" (click)="addSuspensao()" style="margin-top:4px">+ Adicionar Suspensão</button>
            }
          </div>
          } @else {

          <!-- Plenários Numerados: Data + Pauta + Início evento + Início operação -->
          <div class="form-grid-4">
            <div class="form-row">
              <label>Data <span class="req">*</span></label>
              <input type="date" [(ngModel)]="dataOperacao" name="data_operacao" [disabled]="formDisabled()" [readonly]="isRO() || editMode() || camposSessaoReadonly()" [class.field-ro]="isRO() || editMode() || camposSessaoReadonly()">
            </div>
            <div class="form-row">
              <label>Horário da Pauta @if (editData()?.['horario_pauta_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horarioPauta" name="horario_pauta" step="60" [disabled]="formDisabled()" [readonly]="isRO() || camposSessaoReadonly()" [class.field-ro]="isRO() || camposSessaoReadonly()" (change)="revalidarTodosHorarios()">
            </div>
            <div class="form-row">
              <label>Início do evento <span class="req">*</span> @if (editData()?.['horario_inicio_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horaInicio" name="hora_inicio" step="60" [disabled]="formDisabled()" [readonly]="isRO() || camposSessaoReadonly()" [class.field-ro]="isRO() || camposSessaoReadonly()" (change)="onHoraInicioChange()">
            </div>
            <div class="form-row">
              <label>Início da operação @if (!horaEntradaReadonly()) { <span class="req">*</span> } @if (editData()?.['hora_entrada_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horaEntrada" name="hora_entrada" step="60"
                     [disabled]="formDisabled()"
                     [readonly]="isRO() || horaEntradaReadonly()"
                     [class.field-ro]="isRO() || horaEntradaReadonly()"
                     (change)="onHoraEntradaChange()">
              @if (erroHoraEntrada) {
                <p class="erro-hora-entrada">{{ erroHoraEntrada }}</p>
              }
            </div>
          </div>

          <!-- Evento Encerrado + Término do evento + Término da operação -->
          <div class="form-grid-3">
            <div class="form-row">
              <label>Evento Encerrado</label>
              <div class="radio-row">
                <label><input type="radio" [(ngModel)]="eventoEncerrado" name="evento_encerrado" [value]="true" [disabled]="formDisabled() || editMode()" (ngModelChange)="onEventoEncerradoChange()"> Sim</label>
                <label><input type="radio" [(ngModel)]="eventoEncerrado" name="evento_encerrado" [value]="false" [disabled]="formDisabled() || editMode()" (ngModelChange)="onEventoEncerradoChange()"> Não</label>
              </div>
            </div>
            <div class="form-row">
              <label [class.label-sm]="editData()?.['horario_termino_editado']">Término do evento @if (eventoEncerrado) { <span class="req">*</span> } @if (editData()?.['horario_termino_editado']) { <span class="badge-edited badge-edited-sm">editado</span> }</label>
              <input type="time" [(ngModel)]="horaFim" name="hora_fim" step="60" [disabled]="formDisabled() || !eventoEncerrado" [readonly]="isRO()" [class.field-ro]="isRO() || !eventoEncerrado" (change)="onHoraFimChange()">
              @if (erroHoraFim) {
                <p class="erro-hora-entrada">{{ erroHoraFim }}</p>
              }
            </div>
            <div class="form-row">
              <label>Término da operação @if (!eventoEncerrado) { <span class="req">*</span> } @if (editData()?.['hora_saida_editado']) { <span class="badge-edited">editado</span> }</label>
              <input type="time" [(ngModel)]="horaSaida" name="hora_saida" step="60"
                     [disabled]="formDisabled() || eventoEncerrado"
                     [readonly]="isRO()"
                     [class.field-ro]="isRO() || eventoEncerrado"
                     (change)="revalidarTodosHorarios()">
              @if (erroHoraSaida) {
                <p class="erro-hora-entrada">{{ erroHoraSaida }}</p>
              }
            </div>
          </div>
          }

          <!-- USB -->
          <div class="form-grid-2">
            <div class="form-row">
              <label>Trilha do Gravador 01 @if (editData()?.['usb_01_editado']) { <span class="badge-edited">editado</span> }</label>
              <input [(ngModel)]="usb01" name="usb_01" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()">
            </div>
            <div class="form-row">
              <label>Trilha do Gravador 02 @if (editData()?.['usb_02_editado']) { <span class="badge-edited">editado</span> }</label>
              <input [(ngModel)]="usb02" name="usb_02" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()">
            </div>
          </div>

          <!-- Observações -->
          <div class="form-row">
            <label>Observações @if (editData()?.['observacoes_editado']) { <span class="badge-edited">editado</span> }</label>
            <textarea [(ngModel)]="observacoes" name="observacoes" rows="3" [disabled]="formDisabled()" [readonly]="isRO()" [class.field-ro]="isRO()"></textarea>
          </div>

          <!-- Anormalidade -->
          <div class="form-row">
            <label>Houve anormalidade?</label>
            <div class="radio-row">
              <label><input type="radio" [(ngModel)]="houveAnormalidade" name="houve_anormalidade" value="nao" [disabled]="formDisabled() || isRO() || originalAnormalidade"> Não</label>
              <label><input type="radio" [(ngModel)]="houveAnormalidade" name="houve_anormalidade" value="sim" [disabled]="formDisabled() || isRO() || originalAnormalidade"> Sim</label>
            </div>
            <p class="hint-text">Se marcar "Sim", após salvar este registro você será direcionado ao formulário de <em>Registro de Anormalidade</em>.</p>
          </div>

          <!-- Operador (somente leitura em edição) -->
          @if (editMode() && editData()?.['operador_nome']) {
            <div class="form-row">
              <label>Operador Responsável</label>
              <div class="field-value">{{ editData()!['operador_nome'] }}</div>
            </div>
          }

          <!-- Ações -->
          <div class="form-actions">
            @if (editMode()) {
              <button type="button" class="btn-secondary-custom" (click)="fechar()">Fechar Aba</button>
              @if (canEditOperacao()) {
                @if (isRO()) {
                  <button type="button" class="btn-primary-custom" (click)="enterEditMode()">Editar</button>
                } @else {
                  <button type="submit" class="btn-primary-custom" [disabled]="saving()">
                    {{ saving() ? 'Salvando...' : 'Salvar Alterações' }}
                  </button>
                }
              }
            } @else {
              <div class="actions-left">
                <a routerLink="/home" class="btn-secondary-custom">&larr; Voltar</a>
              </div>
              <div class="actions-right">
                @if (situacao() !== 'duas_entradas') {
                  <button type="submit" class="btn-primary-custom" [disabled]="saving() || formDisabled()">
                    {{ saving() ? 'Salvando...' : btnSalvarLabel() }}
                  </button>
                }
              </div>
            }
          </div>
        </form>
      }
    </div>
  `,
  styles: [`
    .req { color: var(--color-red); }
    .form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .form-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; align-items: end; }
    .form-grid-4 { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 16px; align-items: end; }
    .radio-row { display: flex; gap: 16px; }
    @media (max-width: 600px) {
      .form-grid-2, .form-grid-3, .form-grid-4 { grid-template-columns: 1fr; }
      .form-actions { flex-direction: column; }
      .actions-left, .actions-right { width: 100%; justify-content: center; }
    }
    .sessao-header-row {
      display: flex; justify-content: space-between; align-items: flex-start;
      gap: 12px; margin-bottom: 16px; flex-wrap: wrap;
      font-size: .85rem; color: var(--muted); line-height: 1.5;
      border-bottom: 1px solid #f3f4f6; padding-bottom: 12px;
    }
    .sessao-operadores { text-align: left; }
    .badge-edited {
      color: #94a3b8; font-size: .7rem; font-weight: 400;
      font-style: italic; margin-left: 4px;
    }
    .label-sm { font-size: .8rem; }
    .badge-edited-sm { font-size: .6rem; }
    .field-ro { background: #f8fafc !important; pointer-events: none; }
    textarea.field-ro { pointer-events: auto; }
    .hint-text { color: var(--muted); font-size: .8rem; margin: 6px 0 0; font-style: italic; }
    .form-actions {
      display: flex; justify-content: space-between; align-items: center;
      margin-top: 24px; flex-wrap: wrap; gap: 8px;
    }
    .actions-left, .actions-right { display: flex; gap: 8px; align-items: center; }
    .erro-hora-entrada {
      color: var(--color-red, #dc2626); font-size: .8rem; margin: 4px 0 0;
      font-weight: 500; line-height: 1.4;
    }
  `],
})
export class OperacaoFormComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private toast = inject(ToastService);
  lookup = inject(LookupService);

  loading = signal(true);
  saving = signal(false);

  // ── Modo edição (aberto via /operacao/edit?entrada_id=X) ──
  editMode = signal(false);
  readOnly = signal(true);

  /** true somente quando em modo edição E readonly (não no modo novo) */
  isRO(): boolean { return this.editMode() && this.readOnly(); }
  editData = signal<Record<string, any> | null>(null);
  private entradaIdEdit = 0;
  originalAnormalidade = false;
  salaNome = '';
  comissaoNome = '';

  // ── Estado da sessão (modo novo) ──
  situacao = signal<Situacao>('inicial');
  private estadoSessao: any = null;
  private isPrimeiroOp = false;
  comissaoTravada = false;
  entradaAberta1 = false;

  // ── Campos do formulário ──
  salaId = '';
  comissaoId = '';
  nomeDemaisSalas = '';
  nomeEvento = '';
  responsavelEvento = '';
  dataOperacao = toISODate(new Date());
  horarioPauta = '';
  horaInicio = '';
  horaFim = '';
  horaEntrada = '';
  horaSaida = '';
  usb01 = '';
  usb02 = '';
  observacoes = '';
  houveAnormalidade = 'nao';
  eventoEncerrado = true;
  erroHoraEntrada = '';
  erroHoraFim = '';
  erroHoraSaida = '';

  // ── Multi-operador (Plenário Principal) ──
  isMultiOperador = false;
  selectedOperadorIds: string[] = [];
  lockedOperadorIds: string[] = [];
  suspensoes: { hora_suspensao: string; hora_reabertura: string }[] = [];

  operadorOptions(): MultiSelectOption[] {
    return this.lookup.operadoresPlenario().map(op => ({ id: op.id + '', label: op.nome_completo || op.nome }));
  }

  addSuspensao(): void {
    this.suspensoes.push({ hora_suspensao: '', hora_reabertura: '' });
  }

  removeSuspensao(i: number): void {
    this.suspensoes.splice(i, 1);
  }

  ngOnInit(): void {
    this.lookup.loadSalasOperador();
    this.lookup.loadOperadores();
    this.lookup.loadComissoes();

    this.route.queryParams.subscribe(params => {
      const eid = params['entrada_id'];
      if (eid && this.route.snapshot.routeConfig?.path?.includes('edit')) {
        this.entradaIdEdit = +eid;
        this.editMode.set(true);
        this.loadEditData();
      } else {
        this.loading.set(false);
        if (params['sala_id']) {
          this.salaId = params['sala_id'];
          this.onSalaChange();
        }
      }
    });
  }

  // ═══ COMPUTEDS ═══

  sessaoAberta(): boolean { return this.estadoSessao?.existe_sessao_aberta === true; }

  infoOperadoresSessao(): string {
    const entradas: any[] = this.estadoSessao?.entradas_sessao || [];
    let nomes: string[];
    if (entradas.length) {
      const sorted = [...entradas].sort((a, b) => {
        const oa = +(a.ordem ?? a.seq ?? 0);
        const ob = +(b.ordem ?? b.seq ?? 0);
        return oa !== ob ? oa - ob : (a.entrada_id || 0) - (b.entrada_id || 0);
      });
      nomes = sorted.map(e => e.operador_nome || '—');
    } else {
      nomes = this.estadoSessao?.nomes_operadores_sessao || [];
    }
    if (!nomes.length) return '';

    const ordinais: Record<number, string> = {
      2: 'Segundo', 3: 'Terceiro', 4: 'Quarto', 5: 'Quinto',
      6: 'Sexto', 7: 'Sétimo', 8: 'Oitavo', 9: 'Nono', 10: 'Décimo'
    };
    const linhas = ['Registro aberto por ' + nomes[0] + '.'];
    const descricoes: string[] = [];
    for (let i = 1; i < nomes.length; i++) {
      const pos = i + 1;
      const prefixo = ordinais[pos] || pos + 'º';
      descricoes.push(prefixo + ' registro feito por ' + nomes[i]);
    }
    for (let j = 0; j < descricoes.length; j += 2) {
      if (j + 1 < descricoes.length) {
        linhas.push(descricoes[j] + ' • ' + descricoes[j + 1]);
      } else {
        linhas.push(descricoes[j]);
      }
    }
    return linhas.join('<br>');
  }

  formDisabled(): boolean {
    if (this.editMode()) return false;
    return this.situacao() === 'inicial' || this.situacao() === 'duas_entradas';
  }

  /** Campos da sessão (data, pauta, início, evento, comissão, responsável) são readonly para operadores que não são o primeiro */
  camposSessaoReadonly(): boolean {
    if (this.editMode()) return this.editData()?.['ordem'] !== 1;
    return this.sessaoAberta();
  }

  horaEntradaReadonly(): boolean {
    if (this.editMode()) return this.editData()?.['ordem'] === 1;
    return !this.sessaoAberta(); // 1º operador: readonly, espelha hora_inicio
  }

  showComissao(): boolean {
    const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
    if (!sala) return false;
    const nome = sala.nome.toLowerCase();
    // Plenário SEM número e Auditório → esconde comissão
    if (/audit[oó]rio/.test(nome)) return false;
    if (/plen[áa]rio(?!\s*\d)/.test(nome)) return false;
    // Plenário COM número (Plenário 01, Plenário 15) e outras salas → mostra comissão
    return true;
  }

  isDemaisSalas(): boolean {
    return Number(this.salaId) === SALA_DEMAIS_SALAS_ID;
  }

  /** O nome da sala é por sessão; só pode ser editado pelo 1º operador (criador) ou em sessão sem entradas. */
  nomeDemaisSalasReadonly(): boolean {
    if (this.editMode()) return this.editData()?.['ordem'] !== 1;
    return this.sessaoAberta();
  }

  btnSalvarLabel(): string {
    if (this.situacao() === 'uma_entrada') return 'Novo registro (2ª entrada)';
    return 'Salvar registro';
  }

  // ═══ MODO EDIÇÃO (aberto de outra aba) ═══

  private loadEditData(): void {
    this.loading.set(true);
    this.api.get<any>('/api/operador/operacao/detalhe', { entrada_id: this.entradaIdEdit }).subscribe({
      next: (res: any) => {
        const d = res?.data ?? res;
        this.editData.set(d);
        this.salaNome = d['sala_nome'] || '';
        this.salaId = String(d['sala_id'] || '');
        this.comissaoId = d['comissao_id'] ? String(d['comissao_id']) : '';
        this.comissaoNome = d['comissao_nome'] || '';
        this.nomeDemaisSalas = d['nome_demais_salas'] || '';
        this.nomeEvento = d['nome_evento'] || '';
        this.responsavelEvento = d['responsavel_evento'] || '';
        this.dataOperacao = extractDate(d['data'], this.dataOperacao);
        this.horarioPauta = extractTime(d['horario_pauta']);
        this.horaInicio = extractTime(d['horario_inicio']);
        this.horaFim = extractTime(d['horario_termino']);
        this.horaEntrada = extractTime(d['hora_entrada']);
        this.horaSaida = extractTime(d['hora_saida']);
        this.usb01 = d['usb_01'] || '';
        this.usb02 = d['usb_02'] || '';
        this.observacoes = d['observacoes'] || '';
        this.eventoEncerrado = !!d['horario_termino'];
        this.houveAnormalidade = d['houve_anormalidade'] ? 'sim' : 'nao';
        this.originalAnormalidade = d['houve_anormalidade'] || false;
        // Detectar multi-operador
        this.isMultiOperador = d['multi_operador'] === true;
        if (this.isMultiOperador) {
          this.suspensoes = (d['suspensoes'] || []).map((s: any) => ({
            hora_suspensao: extractTime(s['hora_suspensao']),
            hora_reabertura: extractTime(s['hora_reabertura']),
          }));
          this.selectedOperadorIds = (d['operadores_sessao_ids'] || []).map(String);
          const uid = this.auth.user()?.id || '';
          if (uid && !this.selectedOperadorIds.includes(uid)) this.selectedOperadorIds.push(uid);
          this.lockedOperadorIds = uid ? [uid] : [];
          this.lookup.loadOperadoresPlenario();
        }

        this.readOnly.set(true);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); this.toast.error('Erro ao carregar operação.'); },
    });
  }

  canEditOperacao(): boolean {
    return this.editData()?.['somente_leitura'] === false;
  }

  enterEditMode(): void { this.readOnly.set(false); }
  fechar(): void { window.close(); }

  // ═══ MODO NOVO ═══

  onSalaChange(): void {
    if (!this.salaId) {
      this.situacao.set('inicial');
      this.estadoSessao = null;
      this.isMultiOperador = false;
      this.nomeDemaisSalas = '';
      this.limparFormulario();
      return;
    }

    // Detectar multi-operador
    const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
    this.isMultiOperador = sala?.multi_operador === true;
    if (this.isMultiOperador) {
      this.lookup.loadOperadoresPlenario();
      this.suspensoes = [];
      const uid = this.auth.user()?.id || '';
      this.selectedOperadorIds = uid ? [uid] : [];
      this.lockedOperadorIds = uid ? [uid] : [];
    }

    // Limpar campos antes de carregar estado da nova sala
    this.limparFormulario();

    this.api.get<any>('/api/operacao/audio/estado-sessao', { sala_id: this.salaId }).subscribe({
      next: (res: any) => {
        const data = res.data || {};
        this.estadoSessao = data;
        this.comissaoTravada = false;

        // Demais Salas: se há sessão aberta, herda o nome livre dela; senão, limpa
        if (this.isDemaisSalas()) {
          this.nomeDemaisSalas = data.nome_demais_salas || '';
        } else {
          this.nomeDemaisSalas = '';
        }

        const entradasOp: any[] = data.entradas_operador || [];
        const sessaoAberta = data.existe_sessao_aberta === true;

        if (this.isMultiOperador) {
          // Plenário Principal: sempre sem_sessao (registro único, auto-encerrado)
          this.situacao.set('sem_sessao');
          this.isPrimeiroOp = true;
        } else if (!sessaoAberta && entradasOp.length === 0) {
          this.situacao.set('sem_sessao');
          this.isPrimeiroOp = true;
        } else if (sessaoAberta && entradasOp.length === 0) {
          this.situacao.set('sem_entrada');
          this.isPrimeiroOp = false;
          this.preencherComSessao(data);
        } else if (entradasOp.length === 1) {
          this.situacao.set('uma_entrada');
          this.isPrimeiroOp = false;
          this.entradaAberta1 = !entradasOp[0].horario_termino;
          if (this.entradaAberta1) {
            this.preencherComEntrada(entradasOp[0]);
          } else {
            this.limparFormulario();
            if (sessaoAberta) this.preencherComSessao(data);
          }
        } else if (entradasOp.length >= 2) {
          this.situacao.set('duas_entradas');
          this.limparFormulario();
        }

        // Travar comissão se sessão aberta e já tem valor
        if (sessaoAberta && data.comissao_id && this.showComissao()) {
          this.aplicarComissao(data.comissao_id);
          this.comissaoTravada = true;
        }

        this.aplicarRegraHorarios();
      },
      error: () => { this.situacao.set('sem_sessao'); },
    });
  }

  private preencherComSessao(data: any): void {
    const ultimaEntrada = data.entradas_sessao?.length
      ? data.entradas_sessao[data.entradas_sessao.length - 1]
      : null;
    if (ultimaEntrada) {
      this.preencherComEntrada(ultimaEntrada);
    } else {
      this.dataOperacao = data.data || this.dataOperacao;
      this.nomeEvento = data.nome_evento || '';
      this.responsavelEvento = data.responsavel_evento || '';
      this.horarioPauta = extractTime(data.horario_pauta);
      this.horaInicio = extractTime(data.horario_inicio);
      if (data.comissao_id) {
        this.aplicarComissao(data.comissao_id);
      }
    }
    // Limpar campos pessoais do operador
    this.horaEntrada = '';
    this.resetDadosOperador();
  }

  private preencherComEntrada(e: any): void {
    this.dataOperacao = extractDate(e.data_operacao || e.data || '', this.dataOperacao);
    this.nomeEvento = e.nome_evento || '';
    this.responsavelEvento = e.responsavel_evento || '';
    this.horarioPauta = extractTime(e.horario_pauta);
    this.horaInicio = extractTime(e.horario_inicio || e.hora_inicio);
    if (e.comissao_id) {
      this.aplicarComissao(e.comissao_id);
    }
    this.horaEntrada = extractTime(e.hora_entrada);
    this.resetDadosOperador();
  }

  /** Aplica a comissão (id + nome) a partir do id vindo do backend/estado. */
  private aplicarComissao(comissaoIdRaw: any): void {
    this.comissaoId = String(comissaoIdRaw);
    const c = this.lookup.comissoes().find(x => String(x.id) === this.comissaoId);
    if (c) this.comissaoNome = c.nome;
  }

  /** Reseta os campos pessoais do operador (não mexe em horaEntrada). */
  private resetDadosOperador(): void {
    this.horaFim = '';
    this.horaSaida = '';
    this.usb01 = '';
    this.usb02 = '';
    this.observacoes = '';
    this.houveAnormalidade = 'nao';
    this.eventoEncerrado = true;
  }

  private limparFormulario(): void {
    this.nomeEvento = '';
    this.responsavelEvento = '';
    this.horarioPauta = '';
    this.horaInicio = '';
    this.horaEntrada = '';
    this.erroHoraEntrada = '';
    this.erroHoraFim = '';
    this.erroHoraSaida = '';
    this.comissaoId = '';
    this.resetDadosOperador();
  }

  // ═══ SINCRONIZAÇÃO DE HORÁRIOS ═══

  revalidarTodosHorarios(): void {
    this.validarHoraEntrada();
    this.validarHoraFim();
    this.validarHoraSaida();
  }

  onHoraEntradaChange(): void {
    this.revalidarTodosHorarios();
  }

  onHoraInicioChange(): void {
    if (this.horaEntradaReadonly()) {
      this.horaEntrada = this.horaInicio;
    }
    this.revalidarTodosHorarios();
  }

  onHoraFimChange(): void {
    if (this.eventoEncerrado) {
      this.horaSaida = this.horaFim;
    }
    this.revalidarTodosHorarios();
  }

  onEventoEncerradoChange(): void {
    this.horaFim = '';
    this.horaSaida = '';
    this.revalidarTodosHorarios();
  }

  validarHoraEntrada(): void {
    this.erroHoraEntrada = '';
    if (this.isMultiOperador || !this.horaEntrada) return;

    const entradas: any[] = this.estadoSessao?.entradas_sessao || [];
    if (!entradas.length) return;

    const ordemAtual = entradas.length + 1;
    if (ordemAtual < 2) return;

    const anterior = entradas.find((e: any) => e.ordem === ordemAtual - 1);
    if (!anterior) return;

    const horaSaidaAnt = (anterior.hora_saida || '').substring(0, 5);
    if (!horaSaidaAnt) return;

    const heNorm = this.horaEntrada.substring(0, 5);
    if (heNorm < horaSaidaAnt) {
      const nome = anterior.operador_nome || 'anterior';
      this.erroHoraEntrada = `O horário de início da sua operação deve ser igual ou superior à ${horaSaidaAnt} (término da operação de ${nome})`;
    }
  }

  validarHoraFim(): void {
    this.erroHoraFim = '';
    if (!this.horaFim || !this.eventoEncerrado || this.isMultiOperador) return;
    // Término do evento deve ser maior que Início da operação
    const ref = this.horaEntrada || this.horaInicio;
    if (ref && extractTime(this.horaFim) <= extractTime(ref)) {
      this.erroHoraFim = `O término do evento deve ser posterior ao início da operação (${extractTime(ref)}).`;
      return;
    }
    // Quando encerrado, horaSaida = horaFim — validar contra operador seguinte
    const seg = this.dadosSeguinte();
    if (seg && extractTime(this.horaFim) > extractTime(seg.hora)) {
      this.erroHoraFim = `O término do evento não pode ser posterior ao início da operação de ${seg.nome} (${extractTime(seg.hora)}).`;
    }
  }

  validarHoraSaida(): void {
    this.erroHoraSaida = '';
    if (!this.horaSaida || this.eventoEncerrado || this.isMultiOperador) return;
    // Término da operação deve ser maior que Início da operação
    const ref = this.horaEntrada || this.horaInicio;
    if (ref && extractTime(this.horaSaida) <= extractTime(ref)) {
      this.erroHoraSaida = `O término da operação deve ser posterior ao início da operação (${extractTime(ref)}).`;
      return;
    }
    // Validar contra operador seguinte
    const seg = this.dadosSeguinte();
    if (seg && extractTime(this.horaSaida) > extractTime(seg.hora)) {
      this.erroHoraSaida = `O término da operação não pode ser posterior ao início da operação de ${seg.nome} (${extractTime(seg.hora)}).`;
    }
  }

  /** Retorna hora_entrada e nome do operador seguinte (se existir) */
  private dadosSeguinte(): { hora: string; nome: string } | null {
    // Modo edit standalone: dados vêm do backend
    if (this.editMode()) {
      const h = this.editData()?.['hora_entrada_seguinte'];
      if (h) return { hora: h, nome: this.editData()?.['operador_nome_seguinte'] || 'operador seguinte' };
      return null;
    }
    // Modo novo: dados vêm do estadoSessao
    const entradas: any[] = this.estadoSessao?.entradas_sessao || [];
    if (!entradas.length) return null;
    const ordemAtual = entradas.length + 1;
    const seguinte = entradas.find((e: any) => e.ordem === ordemAtual + 1);
    if (!seguinte) return null;
    const h = (seguinte.hora_entrada || '').substring(0, 5);
    if (!h) return null;
    return { hora: h, nome: seguinte.operador_nome || 'operador seguinte' };
  }

  private aplicarRegraHorarios(): void {
    if (this.horaEntradaReadonly() && this.horaInicio) {
      this.horaEntrada = this.horaInicio;
    }
    if (this.eventoEncerrado && this.horaFim) {
      this.horaSaida = this.horaFim;
    }
  }

  // ═══ SUBMIT ═══

  onSubmit(): void {
    if (!this.salaId && !this.editMode()) { focusFirst('sala_id'); return; }
    if (this.isDemaisSalas() && !this.nomeDemaisSalasReadonly() && !this.nomeDemaisSalas.trim()) {
      this.toast.warning('Informe o nome da sala.');
      focusFirst('nome_demais_salas');
      return;
    }
    if (!this.nomeEvento) { focusFirst('nome_evento'); return; }
    if (!this.horaInicio) { focusFirst('hora_inicio'); return; }

    if (this.isMultiOperador) {
      // Validações específicas Plenário Principal
      if (!this.editMode() && this.selectedOperadorIds.length === 0) { this.toast.warning('Selecione pelo menos um operador.'); return; }
      if (!this.horaFim && !this.isRO()) { focusFirst('hora_fim'); return; }
    } else {
      // Validações plenários numerados
      if (this.showComissao() && !this.comissaoId) { focusFirst('comissao_id'); return; }
      if (this.eventoEncerrado && !this.horaFim) { focusFirst('hora_fim'); return; }
      if (!this.eventoEncerrado && !this.horaSaida) { focusFirst('hora_saida'); return; }
      if (!this.horaEntradaReadonly() && !this.horaEntrada) { focusFirst('hora_entrada'); return; }
      if (this.erroHoraEntrada) { focusFirst('hora_entrada'); return; }
      if (this.erroHoraFim) { focusFirst('hora_fim'); return; }
      if (this.erroHoraSaida) { focusFirst('hora_saida'); return; }
    }

    if (this.editMode()) {
      this.submitEdit();
    } else {
      this.submitNew();
    }
  }

  private submitEdit(): void {
    this.saving.set(true);
    const payload = this.buildPayload();
    payload['entrada_id'] = this.entradaIdEdit;

    this.api.put<any>('/api/operacao/audio/editar-entrada', payload).subscribe({
      next: (res: any) => {
        this.saving.set(false);
        if (res.ok) {
          if (res.houve_anormalidade_nova) {
            this.toast.success('Edição salva com sucesso. Redirecionando para Registro de Anormalidade...');
            this.router.navigate(['/anormalidade'], {
              queryParams: { registro_id: res.registro_id, entrada_id: res.entrada_id, modo: 'novo' }
            });
          } else {
            this.toast.success('Registro atualizado com sucesso!');
            this.loadEditData();
          }
        } else {
          this.toast.error(res.error || 'Erro desconhecido');
        }
      },
      error: (err) => { this.saving.set(false); this.toast.error('Erro ao salvar: ' + httpErrorMsg(err, 'Erro de conexão', ['error'])); },
    });
  }

  private submitNew(): void {
    if (!this.salaId) { this.toast.warning('Selecione um local.'); return; }
    this.saving.set(true);

    const payload = this.buildPayload();
    payload['sala_id'] = parseInt(this.salaId, 10);

    this.api.post<any>('/api/operacao/audio/salvar-entrada', payload).subscribe({
      next: (res: any) => {
        this.saving.set(false);
        if (res.ok) {
          const tipoEfetivo = (res.tipo_evento || payload['tipo_evento'] || '').toLowerCase();
          const deveAbrirAnom = res.houve_anormalidade && (tipoEfetivo === 'operacao' || tipoEfetivo === 'outros');
          if (deveAbrirAnom) {
            this.toast.success('Registro salvo com sucesso. Redirecionando para Registro de Anormalidade...');
            this.router.navigate(['/anormalidade'], {
              queryParams: { registro_id: res.registro_id, entrada_id: res.entrada_id, modo: 'novo' }
            });
          } else {
            this.toast.success('Registro salvo com sucesso.');
            this.router.navigate(['/home']);
          }
        } else {
          this.toast.error(res.error || res.message || 'Erro desconhecido');
        }
      },
      error: (err) => { this.saving.set(false); this.toast.error('Erro ao salvar: ' + httpErrorMsg(err, 'Erro de conexão', ['error'])); },
    });
  }

  private buildPayload(): Record<string, any> {
    let tipoEvento = 'operacao';
    if (this.comissaoId) {
      const comissao = this.lookup.comissoes().find(c => String(c.id) === this.comissaoId);
      const texto = comissao?.nome?.toLowerCase() || '';
      tipoEvento = texto.includes('cessão de sala') || texto.includes('cessao de sala') ? 'cessao' : 'operacao';
    } else if (this.showComissao()) {
      tipoEvento = 'outros';
    } else {
      const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
      const nome = sala?.nome?.toLowerCase() || '';
      if (/audit[oó]rio/.test(nome)) tipoEvento = 'outros';
    }

    const payload: Record<string, any> = {
      comissao_id: this.isMultiOperador ? null : (this.comissaoId ? parseInt(this.comissaoId, 10) : null),
      data_operacao: this.dataOperacao,
      nome_demais_salas: this.isDemaisSalas() ? (this.nomeDemaisSalas?.trim() || null) : null,
      nome_evento: this.nomeEvento,
      responsavel_evento: this.isMultiOperador ? null : this.responsavelEvento,
      horario_pauta: this.isMultiOperador ? null : (this.horarioPauta || null),
      hora_inicio: this.horaInicio,
      hora_fim: this.isMultiOperador ? this.horaFim : (this.eventoEncerrado ? this.horaFim : null),
      hora_entrada: this.isMultiOperador ? (this.horaInicio || null) : (this.horaEntrada || null),
      hora_saida: this.isMultiOperador ? (this.horaFim || null) : (this.horaSaida || null),
      usb_01: this.usb01 || null,
      usb_02: this.usb02 || null,
      observacoes: this.observacoes || null,
      houve_anormalidade: this.houveAnormalidade,
      tipo_evento: tipoEvento,
    };

    if (this.isMultiOperador) {
      payload['suspensoes'] = this.suspensoes.filter(s => s.hora_suspensao || s.hora_reabertura);
      if (this.editMode()) {
        payload['operadores_sessao_ids'] = this.selectedOperadorIds;
      } else {
        payload['operadores_ids'] = this.selectedOperadorIds;
      }
    }

    return payload;
  }
}

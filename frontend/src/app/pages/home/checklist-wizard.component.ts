import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { LookupService } from '../../core/services/lookup.service';
import { ToastService } from '../../shared/components/toast.component';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { MultiSelectDropdownComponent, MultiSelectOption } from '../../shared/components/multi-select-dropdown.component';
import { extractDate, hhmmss, toISODate } from '../../core/helpers/date.helpers';

interface ChecklistItem { id: number; nome: string; tipo_widget: string; ordem?: number; }
interface Resposta { item_tipo_id: number; status: string | null; descricao_falha: string | null; valor_texto: string | null; }
interface EditItem {
  item_tipo_id: number; item_nome: string; tipo_widget: string;
  status: string; descricao_falha: string; valor_texto: string; editado: boolean;
}

@Component({
  selector: 'app-checklist-wizard',
  standalone: true,
  imports: [FormsModule, RouterLink, FmtDatePipe, MultiSelectDropdownComponent],
  template: `
    <div class="card-custom" style="max-width:700px; margin:0 auto">

      <!-- ═══ MODO EDIÇÃO (readonly + edição) ═══ -->
      @if (editMode()) {
        <div class="detalhe-header">
          <h1>Verificação de Plenários</h1>
          @if (editData()?.['editado']) {
            <span class="badge-edited">EDITADO</span>
          }
        </div>

        @if (editIsMultiOperador && !canEditChecklist() && editData()?.['criado_por_nome']) {
          <p class="hint-text" style="margin-bottom:16px">Somente {{ editData()!['criado_por_nome'] }} pode editar este formulário.</p>
        }

        @if (editLoading()) {
          <p class="text-muted-sm">Carregando...</p>
        } @else {
          <!-- 1) Identificação -->
          <div class="field-row grid-2" style="margin-bottom:16px">
            <div class="form-row">
              <label>Data</label>
              @if (readOnly()) {
                <div class="field-value">{{ dataOperacao | fmtDate }}</div>
              } @else {
                <input type="date" [(ngModel)]="dataOperacao" style="width:100%">
              }
            </div>
            <div class="form-row">
              <label>Local</label>
              @if (readOnly() || editSalaTravada) {
                <div class="field-value">{{ getSalaNome() }}</div>
              } @else {
                <select [(ngModel)]="salaId" (ngModelChange)="onEditSalaChange()" style="width:100%">
                  @for (s of lookup.salas(); track s.id) {
                    <option [value]="s.id">{{ s.nome }}</option>
                  }
                </select>
              }
            </div>
          </div>

          <!-- 1b) Operadores (Plenário Principal) -->
          @if (editIsMultiOperador) {
            @if (readOnly()) {
              <div class="form-row" style="margin-bottom:10px">
                <label style="font-weight:600; margin-bottom:4px">Cabine</label>
                <input type="text" class="field-ro" [value]="editCabineNomes.join(', ') || '—'" readonly>
              </div>
              <div class="form-row" style="margin-bottom:10px">
                <label style="font-weight:600; margin-bottom:4px">Plenário</label>
                <input type="text" class="field-ro" [value]="editPlenarioNomes.join(', ') || '—'" readonly>
              </div>
            } @else {
              <div class="form-row" style="margin-bottom:14px">
                <label style="font-weight:600; margin-bottom:8px">Cabine</label>
                <app-multi-select-dropdown
                  [options]="operadorOptions()"
                  [selected]="selectedCabine"
                  placeholder="Selecione operadores..."
                  (selectionChange)="selectedCabine = $event" />
              </div>
              <div class="form-row" style="margin-bottom:14px">
                <label style="font-weight:600; margin-bottom:8px">Plenário</label>
                <app-multi-select-dropdown
                  [options]="operadorOptions()"
                  [selected]="selectedPlenario"
                  placeholder="Selecione operadores..."
                  (selectionChange)="selectedPlenario = $event" />
              </div>
              @if (!readOnly() && !usuarioNosOperadores()) {
                <p style="color:var(--color-red); font-size:.85rem; margin-top:6px">Você deve estar em pelo menos um dos grupos (Cabine ou Plenário).</p>
              }
            }
          }

          <!-- 2) Itens -->
          <h3 style="margin:20px 0 10px; font-size:.95rem">Itens Verificados</h3>
          @for (item of editItems(); track item.item_tipo_id) {
            <div class="edit-item" [class.edit-item-falha]="item.status === 'Falha'">
              <div class="edit-item-header">
                <span class="edit-item-nome">{{ item.item_nome }}</span>
                <span class="edit-item-right">
                  @if (item.editado) {
                    <span class="badge-edited-sm">editado</span>
                  }
                  @if (readOnly() && item.tipo_widget !== 'text') {
                    <span [class]="item.status === 'Falha' ? 'status-falha' : 'status-ok'">
                      {{ item.status === 'Falha' ? '\u2716 Falha' : '\u2705 Ok' }}
                    </span>
                  }
                </span>
              </div>

              @if (readOnly()) {
                @if (item.tipo_widget === 'text') {
                  <div class="field-value" style="margin-top:6px">{{ item.valor_texto || '--' }}</div>
                }
                @if (item.status === 'Falha' && item.descricao_falha) {
                  <div class="falha-desc-inline">Descrição da falha: {{ item.descricao_falha }}</div>
                }
              } @else {
                @if (item.tipo_widget === 'text') {
                  <input type="text" [(ngModel)]="item.valor_texto" placeholder="Digite o valor..." style="width:100%; margin-top:6px">
                } @else {
                  <div class="edit-radios">
                    <label class="radio-card-sm" [class.selected]="item.status === 'Ok'">
                      <input type="radio" [name]="'status_' + item.item_tipo_id" value="Ok" [(ngModel)]="item.status"> ✅ Ok
                    </label>
                    <label class="radio-card-sm" [class.selected]="item.status === 'Falha'">
                      <input type="radio" [name]="'status_' + item.item_tipo_id" value="Falha" [(ngModel)]="item.status"> <span style="color:var(--color-red)">✖</span> Falha
                    </label>
                  </div>
                  @if (item.status === 'Falha') {
                    <div style="margin-top:6px">
                      <label style="font-size:.8rem; color:var(--muted)">Descrição da falha *</label>
                      <textarea [(ngModel)]="item.descricao_falha" rows="2" placeholder="Mínimo 10 caracteres..." style="width:100%"></textarea>
                      @if (item.descricao_falha.length > 0 && item.descricao_falha.length < MIN_DESC_FALHA) {
                        <p style="color:var(--color-red); font-size:.8rem; margin:4px 0 0">Mínimo 10 caracteres</p>
                      }
                    </div>
                  }
                }
              }
            </div>
          }

          <!-- 3) Observações -->
          <h3 style="margin:20px 0 10px; font-size:.95rem">Observações
            @if (editData()?.['observacoes_editado']) {
              <span class="badge-edited-sm">editado</span>
            }
          </h3>
          @if (readOnly()) {
            <div class="field-value obs-value">{{ observacoes || '' }}</div>
          } @else {
            <textarea [(ngModel)]="observacoes" rows="3" placeholder="Anotações gerais (opcional)" style="width:100%"></textarea>
          }

          <!-- Ações -->
          <div style="display:flex; justify-content:space-between; margin-top:24px">
            <button class="btn-secondary-custom" (click)="fechar()">Fechar Aba</button>
            @if (canEditChecklist()) {
              @if (readOnly()) {
                <button class="btn-primary-custom" (click)="enterEditMode()">Editar</button>
              } @else {
                <button class="btn-primary-custom" [disabled]="saving() || !canSaveEdit()" (click)="submitEdit()">
                  {{ saving() ? 'Salvando...' : 'Salvar Alterações' }}
                </button>
              }
            }
          </div>
        }
      } @else {

        <!-- ═══ MODO NOVO (wizard) ═══ -->
        <h1>Verificação de Plenários</h1>

        @if (step() === 'setup') {
          <p class="text-muted-sm">Selecione o local para iniciar a verificação.</p>
          <div class="setup-grid">
            <div>
              <label>Data</label>
              <input type="date" [(ngModel)]="dataOperacao" style="width:100%">
            </div>
            <div>
              <label>Local</label>
              <select [(ngModel)]="salaId" (ngModelChange)="onSalaChange()" style="width:100%">
                <option value="">Selecione...</option>
                @for (s of lookup.salas(); track s.id) {
                  <option [value]="s.id">{{ s.nome }}</option>
                }
              </select>
            </div>
          </div>
          <div style="display:flex; justify-content:space-between">
            <a routerLink="/home" class="btn-secondary-custom">&larr; Voltar</a>
            <button class="btn-primary-custom" [disabled]="!salaId" (click)="proceedFromSetup()">Avançar &rarr;</button>
          </div>
        }

        @if (step() === 'aviso' && avisoPendente()) {
          @if (avisoPendente()!.mensagens.length === 1) {
            <p class="text-muted-sm">Há um aviso para o {{ salaNome }}</p>
            <div class="aviso-box">
              <p class="aviso-msg">{{ avisoPendente()!.mensagens[0].texto }}</p>
            </div>
          } @else {
            <p class="text-muted-sm">Há {{ avisoPendente()!.mensagens.length }} avisos para o {{ salaNome }}</p>
            @for (m of avisoPendente()!.mensagens; track m.ordem) {
              <div class="aviso-box">
                <div class="aviso-header">Aviso nº {{ m.ordem }}</div>
                <p class="aviso-msg">{{ m.texto }}</p>
              </div>
            }
          }
          <label class="aviso-ciente">
            <input type="checkbox" [(ngModel)]="avisoCiente">
            Ciente
          </label>
          <div style="display:flex; justify-content:space-between; margin-top:20px">
            <button class="btn-secondary-custom" (click)="step.set('setup')">&larr; Voltar</button>
            <button class="btn-primary-custom" [disabled]="!avisoCiente" (click)="confirmarCiencia()">Avançar &rarr;</button>
          </div>
        }

        @if (step() === 'operadores') {
          <p class="text-muted-sm">Selecione os operadores da verificação.</p>
          <div class="form-row" style="margin-bottom:14px">
            <label style="font-weight:600; margin-bottom:8px">Cabine</label>
            <app-multi-select-dropdown
              [options]="operadorOptions()"
              [selected]="selectedCabine"
              placeholder="Selecione operadores..."
              (selectionChange)="selectedCabine = $event" />
          </div>
          <div class="form-row" style="margin-bottom:14px">
            <label style="font-weight:600; margin-bottom:8px">Plenário</label>
            <app-multi-select-dropdown
              [options]="operadorOptions()"
              [selected]="selectedPlenario"
              placeholder="Selecione operadores..."
              (selectionChange)="selectedPlenario = $event" />
          </div>
          @if (!usuarioNosOperadores()) {
            <p style="color:var(--color-red); font-size:.85rem; margin-bottom:12px">Você deve estar em pelo menos um dos grupos (Cabine ou Plenário).</p>
          }
          <div style="display:flex; justify-content:space-between">
            <button class="btn-secondary-custom" (click)="step.set('setup')">&larr; Voltar</button>
            <button class="btn-primary-custom" [disabled]="!usuarioNosOperadores() || (selectedCabine.length === 0 && selectedPlenario.length === 0)" (click)="startWizard()">Avançar &rarr;</button>
          </div>
        }

        @if (step() === 'wizard' && currentItem()) {
          <div class="wizard-info">
            <span class="wizard-label">Verificando:</span>
            <div class="wizard-sala">{{ salaNome }}</div>
          </div>

          <h2 class="wizard-item-title">{{ currentItem()!.nome }}</h2>

          @if (currentItem()!.tipo_widget === 'text') {
            <input type="text" [(ngModel)]="textValue" placeholder="Digite o valor..." style="width:100%" autofocus>
          } @else {
            <div class="wizard-radios-classic">
              <label class="radio-option">
                <input type="radio" name="status" value="Ok" [(ngModel)]="radioValue">
                <span class="radio-icon ok">&#x2705;</span> Ok
              </label>
              <label class="radio-option">
                <input type="radio" name="status" value="Falha" [(ngModel)]="radioValue">
                <span class="radio-icon falha">&#x2716;</span> Falha
              </label>
            </div>
            @if (radioValue === 'Falha') {
              <div style="margin-top:12px">
                <label>Descrição da falha:</label>
                <textarea [(ngModel)]="falhaDesc" rows="3" placeholder="Mínimo 10 caracteres..." style="width:100%"></textarea>
                @if (falhaDesc.length > 0 && falhaDesc.length < MIN_DESC_FALHA) {
                  <p style="color:var(--color-red); font-size:0.85rem; margin-top:4px">Insira no mínimo 10 caracteres</p>
                }
              </div>
            }
          }

          <div style="display:flex; justify-content:space-between; margin-top:20px">
            <button class="btn-secondary-custom" (click)="prevStep()">&larr; Voltar</button>
            <button class="btn-primary-custom" [disabled]="!canAdvance()" (click)="nextStep()">
              Confirmar e Avançar &rarr;
            </button>
          </div>
          <p style="text-align:center; color:var(--muted); font-size:0.9rem; margin-top:12px">
            Item {{ currentIndex() + 1 }} de {{ itens().length }}
          </p>
        }

        @if (step() === 'finish') {
          <h2>Observações</h2>
          <textarea [(ngModel)]="observacoes" rows="4" placeholder="Anotações gerais (opcional)" style="width:100%"></textarea>
          <div style="display:flex; justify-content:space-between; margin-top:20px">
            <button class="btn-secondary-custom" (click)="backFromFinish()">&larr; Voltar</button>
            <button class="btn-primary-custom" [disabled]="saving()" (click)="submit()">
              {{ saving() ? 'Salvando...' : 'Salvar Verificação' }}
            </button>
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .btn-secondary-custom {
      background: #fff; color: var(--text); border: 1px solid var(--border);
      border-radius: 999px; padding: 10px 20px; font-weight: 600; cursor: pointer; text-decoration: none;
      &:hover { background: var(--row-hover); }
    }
    .setup-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
    .form-row { margin-bottom: 0; }
    @media (max-width: 600px) {
      .setup-grid { grid-template-columns: 1fr; }
    }
    .wizard-info {
      background: #eff6ff; padding: 10px; border-radius: 6px; margin-bottom: 20px; border: 1px solid #dbeafe;
    }
    .wizard-label { color: var(--muted); font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 700; }
    .wizard-sala { font-size: 1.2rem; font-weight: 600; color: var(--text); }
    .wizard-item-title { font-size: 1.1rem; margin-bottom: 16px; }
    .wizard-radios-classic {
      display: flex; flex-direction: column; gap: 14px; margin: 8px 0;
    }
    .radio-option {
      display: flex; align-items: center; gap: 6px;
      cursor: pointer; font-size: 1rem; font-weight: 500;
    }
    .radio-option input[type="radio"] { width: 16px; height: 16px; margin: 0; cursor: pointer; }
    .radio-icon.ok { font-size: 1rem; }
    .radio-icon.falha { color: #dc2626; font-size: .9rem; }
    h3 { font-size: .95rem; margin: 24px 0 8px; color: var(--text); }
    .badge-edited {
      background: #f59e0b; color: #fff; font-size: .7rem; font-weight: 700;
      padding: 3px 8px; border-radius: 4px;
    }
    .badge-edited-sm { color: #f59e0b; font-size: .7rem; font-weight: 600; }
    .edit-item {
      border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px;
      margin-bottom: 6px; background: #fff;
    }
    .edit-item-falha { background: #fef2f2; border-color: #fecaca; }
    .edit-item-header { display: flex; justify-content: space-between; align-items: center; }
    .edit-item-right { display: flex; align-items: center; gap: 8px; }
    .edit-item-nome { font-weight: 600; font-size: .9rem; }
    .status-ok { color: #16a34a; font-weight: 600; font-size: .85rem; }
    .status-falha { color: #dc2626; font-weight: 600; font-size: .85rem; }
    .falha-desc-inline {
      margin-top: 6px; padding: 6px 12px; font-size: .85rem;
      background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-radius: 6px;
    }
    .field-ro { background: #f9fafb !important; color: #6b7280; cursor: not-allowed; width: 100%; }
    .hint-text { color: var(--muted); font-size: .8rem; margin: 6px 0 0; font-style: italic; }
    .edit-radios { display: flex; gap: 8px; margin-top: 6px; }
    .radio-card-sm {
      display: flex; align-items: center; gap: 4px;
      padding: 6px 14px; border: 1px solid var(--border); border-radius: 6px; cursor: pointer;
      font-size: .85rem; font-weight: 600;
      &.selected { border-color: var(--primary); background: #eff6ff; }
      input[type="radio"] { display: none; }
    }
  `],
})
export class ChecklistWizardComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private toast = inject(ToastService);
  lookup = inject(LookupService);

  // ── Modo ──
  editMode = signal(false);
  readOnly = signal(true);
  editLoading = signal(true);
  editData = signal<Record<string, any> | null>(null);
  editItems = signal<EditItem[]>([]);
  private checklistId = 0;

  // ── Wizard (modo novo) ──
  step = signal<'setup' | 'aviso' | 'operadores' | 'wizard' | 'finish'>('setup');
  avisoPendente = signal<{ cadastro_id: string; manter_apos_ciencia: boolean; mensagens: { ordem: number; texto: string }[] } | null>(null);
  avisoCiente = false;
  dataOperacao = toISODate(new Date());
  salaId = '';
  salaNome = '';

  itens = signal<ChecklistItem[]>([]);
  currentIndex = signal(0);
  respostas: Record<number, Resposta> = {};
  startTime: Date | null = null;

  radioValue = '';
  falhaDesc = '';
  textValue = '';
  observacoes = '';
  saving = signal(false);
  readonly MIN_DESC_FALHA = 10;

  currentItem = signal<ChecklistItem | null>(null);

  // ── Multi-operador (Plenário Principal) ──
  isMultiOperador = false;
  selectedCabine: string[] = [];
  selectedPlenario: string[] = [];

  // ── Multi-operador no modo edição ──
  editIsMultiOperador = false;
  editSalaTravada = false; // trava após salvar como Plenário Principal
  editCabineNomes: string[] = [];
  editPlenarioNomes: string[] = [];

  usuarioNosOperadores(): boolean {
    const uid = this.auth.user()?.id;
    if (!uid) return false;
    return this.selectedCabine.includes(uid) || this.selectedPlenario.includes(uid);
  }

  operadorOptions(): MultiSelectOption[] {
    return this.lookup.operadoresPlenario().map(op => ({ id: op.id + '', label: op.nome_completo || op.nome }));
  }

  // ── Rascunho (localStorage) ──
  private readonly DRAFT_MAX_AGE_MS = 2 * 60 * 60 * 1000; // 2 horas

  private get DRAFT_KEY(): string {
    const uid = this.auth.user()?.id || 'anonymous';
    return `checklist_draft_${uid}`;
  }

  ngOnInit(): void {
    this.lookup.loadSalasOperador();

    this.route.queryParams.subscribe(params => {
      const cid = params['checklist_id'];
      if (cid) {
        this.checklistId = +cid;
        this.editMode.set(true);
        this.loadEditData();
      } else {
        // Modo novo: verificar rascunho salvo
        const draft = this.loadDraft();
        if (draft) this.restoreDraft(draft);
      }
    });
  }

  // ═══ RASCUNHO (localStorage) ═══

  private saveDraft(step: string): void {
    try {
      const draft: Record<string, unknown> = {
        salaId: this.salaId,
        salaNome: this.salaNome,
        dataOperacao: this.dataOperacao,
        itens: this.itens(),
        currentIndex: this.currentIndex(),
        respostas: this.respostas,
        startTime: this.startTime ? this.startTime.toISOString() : null,
        step,
        savedAt: Date.now(),
        isMultiOperador: this.isMultiOperador,
        selectedCabine: this.selectedCabine,
        selectedPlenario: this.selectedPlenario,
      };
      localStorage.setItem(this.DRAFT_KEY, JSON.stringify(draft));
    } catch (e) {
      console.warn('Não foi possível salvar rascunho:', e);
    }
  }

  private clearDraft(): void {
    try { localStorage.removeItem(this.DRAFT_KEY); } catch (_) {}
  }

  private loadDraft(): any {
    try {
      const raw = localStorage.getItem(this.DRAFT_KEY);
      if (!raw) return null;
      const draft = JSON.parse(raw);
      if (!draft.salaId || !draft.itens || draft.itens.length === 0) return null;
      // Descarta rascunhos de um dia diferente ou com mais de 2 horas
      if (draft.savedAt) {
        const savedDate = new Date(draft.savedAt);
        const today = new Date();
        const dayChanged = savedDate.getFullYear() !== today.getFullYear()
          || savedDate.getMonth() !== today.getMonth()
          || savedDate.getDate() !== today.getDate();
        if (dayChanged || (Date.now() - draft.savedAt) > this.DRAFT_MAX_AGE_MS) {
          this.clearDraft();
          return null;
        }
      }
      return draft;
    } catch (e) {
      console.warn('Rascunho inválido, ignorando:', e);
      this.clearDraft();
      return null;
    }
  }

  private restoreDraft(draft: any): void {
    this.salaId = String(draft.salaId);
    this.salaNome = draft.salaNome;
    if (draft.dataOperacao) this.dataOperacao = draft.dataOperacao;
    this.itens.set(draft.itens);
    this.currentIndex.set(draft.currentIndex);
    this.respostas = draft.respostas || {};
    this.startTime = draft.startTime ? new Date(draft.startTime) : null;

    if (draft.isMultiOperador) {
      this.isMultiOperador = true;
      this.selectedCabine = draft.selectedCabine || [];
      this.selectedPlenario = draft.selectedPlenario || [];
      this.lookup.loadOperadoresPlenario();
    }

    if (draft.step === 'finish') {
      this.step.set('finish');
    } else {
      this.loadCurrentItem();
      this.step.set('wizard');
    }
  }

  // ═══ MODO EDIÇÃO ═══

  private loadEditData(): void {
    this.editLoading.set(true);
    this.api.get<any>('/api/operador/checklist/detalhe', { checklist_id: this.checklistId }).subscribe({
      next: (res: any) => {
        const d = res?.data ?? res;
        this.editData.set(d);
        this.dataOperacao = extractDate(d['data_operacao']);
        this.salaId = String(d['sala_id'] || '');
        this.observacoes = d['observacoes'] || '';

        const items: EditItem[] = (d['itens'] || []).map((it: any) => ({
          item_tipo_id: it['item_tipo_id'],
          item_nome: it['item_nome'],
          tipo_widget: it['tipo_widget'] || 'radio',
          status: it['status'] || '',
          descricao_falha: it['descricao_falha'] || '',
          valor_texto: it['valor_texto'] || '',
          editado: it['editado'] || false,
        }));
        this.editItems.set(items);

        // Multi-operador: baseado no estado SALVO no banco
        this.editIsMultiOperador = d['multi_operador'] === true;
        this.editSalaTravada = this.editIsMultiOperador; // trava se já é Plenário Principal
        if (this.editIsMultiOperador) {
          this.editCabineNomes = d['operadores_cabine'] || [];
          this.editPlenarioNomes = d['operadores_plenario'] || [];
          this.selectedCabine = (d['operadores_cabine_ids'] || []).map(String);
          this.selectedPlenario = (d['operadores_plenario_ids'] || []).map(String);
          this.lookup.loadOperadoresPlenario();
        } else {
          this.editCabineNomes = [];
          this.editPlenarioNomes = [];
          this.selectedCabine = [];
          this.selectedPlenario = [];
        }

        this.readOnly.set(true);
        this.editLoading.set(false);
      },
      error: () => {
        this.editLoading.set(false);
        this.toast.error('Erro ao carregar checklist.');
      },
    });
  }

  canEditChecklist(): boolean {
    return this.editData()?.['somente_leitura'] === false;
  }

  enterEditMode(): void { this.readOnly.set(false); }

  onEditSalaChange(): void {
    if (!this.salaId) return;

    // Detectar multi-operador da nova sala
    const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
    this.editIsMultiOperador = sala?.multi_operador === true;

    if (this.editIsMultiOperador) {
      this.lookup.loadOperadoresPlenario();
      if (this.selectedCabine.length === 0 && this.selectedPlenario.length === 0) {
        this.selectedCabine = [];
        this.selectedPlenario = [];
      }
    } else {
      this.selectedCabine = [];
      this.selectedPlenario = [];
    }

    // Carregar itens da nova sala e reconciliar com respostas existentes
    this.api.get<any>('/api/forms/checklist/itens-tipo', { sala_id: this.salaId }).subscribe(res => {
      const novosItens: { id: number; nome: string; tipo_widget: string }[] = res.data || [];
      const existentes = this.editItems();
      const existenteMap = new Map(existentes.map(e => [e.item_tipo_id, e]));

      const reconciliados: EditItem[] = novosItens.map(ni => {
        const prev = existenteMap.get(ni.id);
        if (prev) {
          return { ...prev, item_nome: ni.nome, tipo_widget: ni.tipo_widget };
        }
        return {
          item_tipo_id: ni.id,
          item_nome: ni.nome,
          tipo_widget: ni.tipo_widget,
          status: '',
          descricao_falha: '',
          valor_texto: '',
          editado: false,
        };
      });

      this.editItems.set(reconciliados);
    });
  }

  getSalaNome(): string {
    const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
    return sala?.nome || '';
  }

  canSaveEdit(): boolean {
    for (const item of this.editItems()) {
      if (item.tipo_widget !== 'text') {
        if (!item.status) return false;
        if (item.status === 'Falha' && item.descricao_falha.trim().length < this.MIN_DESC_FALHA) return false;
      }
    }
    if (this.editIsMultiOperador && !this.usuarioNosOperadores()) return false;
    return !!this.dataOperacao && !!this.salaId;
  }

  submitEdit(): void {
    this.saving.set(true);
    const payload: Record<string, unknown> = {
      checklist_id: this.checklistId,
      data_operacao: this.dataOperacao,
      sala_id: parseInt(this.salaId, 10),
      observacoes: this.observacoes || null,
      itens: this.editItems().map(it => ({
        item_tipo_id: it.item_tipo_id,
        status: it.tipo_widget === 'text' ? 'Ok' : it.status,
        descricao_falha: it.status === 'Falha' ? it.descricao_falha.trim() : null,
        valor_texto: it.tipo_widget === 'text' ? it.valor_texto.trim() : null,
      })),
    };

    if (this.editIsMultiOperador) {
      payload['operadores_cabine'] = this.selectedCabine;
      payload['operadores_plenario'] = this.selectedPlenario;
    }

    this.api.put<any>('/api/forms/checklist/editar', payload).subscribe({
      next: (res: any) => {
        this.saving.set(false);
        if (res.ok) {
          this.toast.success('Checklist atualizado com sucesso!');
          this.loadEditData(); // recarrega dados atualizados
        } else {
          this.toast.error(res.error || 'Erro desconhecido');
        }
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error('Erro ao salvar: ' + (err.error?.error || 'Erro de conexão'));
      },
    });
  }

  fechar(): void { window.close(); }

  // ═══ MODO NOVO (wizard) ═══

  onSalaChange(): void {
    const sala = this.lookup.salas().find(s => String(s.id) === this.salaId);
    this.isMultiOperador = sala?.multi_operador === true;
    if (this.isMultiOperador) {
      this.lookup.loadOperadoresPlenario();
    }
  }

  proceedFromSetup(): void {
    if (!this.salaId) return;
    const sel = this.lookup.salas().find(s => String(s.id) === this.salaId);
    this.salaNome = sel?.nome || '';
    this.api.get<any>('/api/forms/checklist/aviso-pendente', { sala_id: this.salaId }).subscribe({
      next: res => {
        if (res?.data) {
          this.avisoPendente.set(res.data);
          this.avisoCiente = false;
          this.step.set('aviso');
        } else {
          this.proceedAfterAviso();
        }
      },
      error: () => this.proceedAfterAviso(),
    });
  }

  private proceedAfterAviso(): void {
    if (this.isMultiOperador) this.step.set('operadores');
    else this.startWizard();
  }

  confirmarCiencia(): void {
    const a = this.avisoPendente();
    if (!a || !this.avisoCiente) return;
    this.api.post(`/api/forms/checklist/aviso/${a.cadastro_id}/ciencia`, { sala_id: this.salaId }).subscribe({
      next: () => { this.avisoPendente.set(null); this.proceedAfterAviso(); },
      error: () => this.toast.error('Erro ao registrar ciência.'),
    });
  }

  startWizard(): void {
    if (!this.salaId) return;
    const sel = this.lookup.salas().find(s => String(s.id) === this.salaId);
    this.salaNome = sel?.nome || '';

    this.api.get<any>('/api/forms/checklist/itens-tipo', { sala_id: this.salaId }).subscribe(res => {
      const items = res.data || [];
      if (items.length === 0) { this.toast.warning('Este local não possui itens de verificação configurados.'); return; }
      this.itens.set(items);
      this.currentIndex.set(0);
      if (!this.startTime) this.startTime = new Date();
      this.loadCurrentItem();
      this.step.set('wizard');
      this.saveDraft('wizard');
    });
  }

  private loadCurrentItem(): void {
    const item = this.itens()[this.currentIndex()];
    this.currentItem.set(item);
    const saved = this.respostas[item.id];
    this.radioValue = saved?.status || '';
    this.falhaDesc = saved?.descricao_falha || '';
    this.textValue = saved?.valor_texto || '';
  }

  canAdvance(): boolean {
    const item = this.currentItem();
    if (!item) return false;
    if (item.tipo_widget === 'text') return true;
    // Multi-operador: pode avançar sem marcar (validação final no submit)
    if (this.isMultiOperador && !this.radioValue) return true;
    if (!this.radioValue) return false;
    if (this.radioValue === 'Falha' && this.falhaDesc.trim().length < this.MIN_DESC_FALHA) return false;
    return true;
  }

  isLastItem(): boolean { return this.currentIndex() === this.itens().length - 1; }

  nextStep(): void {
    const item = this.currentItem()!;
    this.respostas[item.id] = {
      item_tipo_id: item.id,
      status: item.tipo_widget === 'text' ? 'Ok' : this.radioValue,
      descricao_falha: this.radioValue === 'Falha' ? this.falhaDesc.trim() : null,
      valor_texto: item.tipo_widget === 'text' ? this.textValue.trim() : null,
    };

    if (this.isLastItem()) {
      this.saveDraft('finish');
      this.step.set('finish');
    } else {
      this.currentIndex.update(i => i + 1);
      this.loadCurrentItem();
      this.saveDraft('wizard');
    }
  }

  prevStep(): void {
    // Salvar resposta do item atual antes de voltar (mesmo comportamento do nextStep)
    const item = this.currentItem();
    if (item) {
      this.respostas[item.id] = {
        item_tipo_id: item.id,
        status: item.tipo_widget === 'text' ? 'Ok' : (this.radioValue || null),
        descricao_falha: this.radioValue === 'Falha' ? this.falhaDesc.trim() : null,
        valor_texto: item.tipo_widget === 'text' ? this.textValue.trim() : null,
      };
    }

    if (this.currentIndex() > 0) {
      this.currentIndex.update(i => i - 1);
      this.loadCurrentItem();
    } else {
      this.step.set(this.isMultiOperador ? 'operadores' : 'setup');
    }
  }

  backFromFinish(): void {
    this.step.set('wizard');
    this.loadCurrentItem();
  }

  submit(): void {
    if (this.saving()) return; // Proteção contra duplo clique

    // Multi-operador: validar que todos os itens foram marcados
    if (this.isMultiOperador) {
      const faltantes: string[] = [];
      for (const item of this.itens()) {
        const resp = this.respostas[item.id];
        if (item.tipo_widget === 'text') continue;
        if (!resp || !resp.status) faltantes.push(item.nome);
      }
      if (faltantes.length > 0) {
        this.toast.warning('Itens sem marcação: ' + faltantes.join(', '));
        return;
      }
    }

    this.saving.set(true);

    const now = new Date();

    const payload: Record<string, unknown> = {
      data_operacao: this.dataOperacao,
      sala_id: parseInt(this.salaId, 10),
      hora_inicio_testes: this.startTime ? hhmmss(this.startTime) : null,
      hora_termino_testes: hhmmss(now),
      observacoes: this.observacoes || null,
      itens: Object.values(this.respostas),
    };

    if (this.isMultiOperador) {
      payload['operadores_cabine'] = this.selectedCabine;
      payload['operadores_plenario'] = this.selectedPlenario;
    }

    this.api.post<any>('/api/forms/checklist/registro', payload).subscribe({
      next: res => {
        if (res.ok) {
          this.clearDraft();
          this.toast.success('Verificação salva com sucesso!');
          this.router.navigate(['/home']);
          // Mantém saving=true durante redirecionamento
        } else {
          this.saving.set(false);
          this.toast.error(res.error || res.message || 'Erro desconhecido');
        }
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err.error?.error || 'Erro de conexão ao salvar.';
        this.toast.error(msg);
      },
    });
  }
}

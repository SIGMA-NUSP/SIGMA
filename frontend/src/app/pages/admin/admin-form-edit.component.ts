import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { LookupService } from '../../core/services/lookup.service';

interface EditItem { id?: number | null; nome: string; ativo: boolean; tipo_widget?: string; item_tipo_id?: number | null; _highlight?: boolean; }

@Component({
  selector: 'app-admin-form-edit',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <h1>Edição de Formulários</h1>
    <a routerLink="/admin/operacao-audio" class="back-link">← Voltar</a>

    <!-- Cards de seleção -->
    <div class="grid-cards cols-auto">
      <button class="card-custom card-pick card-disabled" disabled>
        <strong>Edição de Locais</strong><span class="text-muted-sm">Gerenciado pelo sistema</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeEntity()==='comissoes'" (click)="selectEntity('comissoes')">
        <strong>Edição de Comissões</strong><span class="text-muted-sm">Atividades legislativas</span>
      </button>
      <button class="card-custom card-pick" [class.active]="activeEntity()==='sala_config'" (click)="selectEntity('sala_config')">
        <strong>Edição dos Itens de Verificação</strong><span class="text-muted-sm">Config por sala</span>
      </button>
    </div>

    @if (activeEntity()) {
      <div class="card-custom" style="margin-bottom:16px">

        <!-- Dropdown de sala (só para sala_config) -->
        @if (activeEntity() === 'sala_config') {
          <div class="sala-select-row">
            <label>Selecione o local:</label>
            <select [(ngModel)]="selectedSalaId" (ngModelChange)="onSalaSelect()">
              <option value="">Selecione um local...</option>
              @for (s of activeSalas(); track s.id) { <option [value]="s.id">{{ s.nome }}</option> }
            </select>
          </div>
          @if (selectedSalaId) {
            <p class="info-msg">Configure quais itens de verificação aparecerão no formulário deste local. Você pode ativar/desativar itens, reordenar e adicionar novos.</p>
          }
        }

        @if (loading()) {
          <p style="color:var(--muted)">Carregando...</p>
        } @else if (activeEntity() !== 'sala_config' || selectedSalaId) {
          <div class="table-container">
            <table class="form-edit-table">
              <thead><tr>
                <th class="col-drag"></th>
                <th class="col-pos">Posição</th>
                <th>Nome</th>
                @if (activeEntity() === 'sala_config') { <th class="col-tipo">Tipo</th> }
                <th class="col-ativo">Ativa</th>
              </tr></thead>
              <tbody>
                <!-- Itens ativos -->
                @for (item of activeItems(); track item; let i = $index) {
                  <tr class="form-edit-row" [class.row-highlight]="item._highlight"
                      draggable="true"
                      (dragstart)="onDragStart($event, i, 'item')"
                      (dragover)="onDragOver($event, i)"
                      (drop)="onDrop($event, i)"
                      (dragend)="onDragEnd()">
                    <td class="col-drag drag-handle">⋮⋮</td>
                    <td class="col-pos">{{ i + 1 }}</td>
                    <td class="col-nome" (dblclick)="startEdit($event, item)">
                      @if (editingItem === item) {
                        <input #editInput type="text" [(ngModel)]="editValue" class="edit-input"
                               (blur)="finishEdit(item)" (keydown.enter)="finishEdit(item)" (keydown.escape)="cancelEdit()">
                      } @else {
                        {{ item.nome }}
                      }
                    </td>
                    @if (activeEntity() === 'sala_config') {
                      <td class="col-tipo">
                        <select [(ngModel)]="item.tipo_widget" (ngModelChange)="markChanged(item)" class="tipo-select">
                          <option value="radio">Ok/Falha</option>
                          <option value="text">Texto livre</option>
                        </select>
                      </td>
                    }
                    <td class="col-ativo"><input type="checkbox" [checked]="true" (change)="toggleAtivo(item, false)"></td>
                  </tr>
                }

                <!-- Linha para novo item -->
                <tr class="form-edit-row blank-row"
                    draggable="true"
                    (dragstart)="onDragStart($event, -1, 'blank')"
                    (dragover)="onDragOver($event, activeItems().length)"
                    (drop)="onDrop($event, activeItems().length)"
                    (dragend)="onDragEnd()">
                  <td class="col-drag drag-handle">⋮⋮</td>
                  <td class="col-pos"></td>
                  <td class="col-nome">
                    <input type="text" [(ngModel)]="newItemNome" class="new-input"
                           placeholder="Novo registro..."
                           (keydown.enter)="addItem()" (blur)="addItem()">
                  </td>
                  @if (activeEntity() === 'sala_config') {
                    <td class="col-tipo">
                      <select [(ngModel)]="newItemTipo" class="tipo-select">
                        <option value="radio">Ok/Falha</option>
                        <option value="text">Texto livre</option>
                      </select>
                    </td>
                  }
                  <td class="col-ativo"></td>
                </tr>

                <!-- Itens inativos -->
                @for (item of inactiveItems(); track item) {
                  <tr class="form-edit-row form-edit-row-inactive" [class.row-highlight]="item._highlight">
                    <td class="col-drag"></td>
                    <td class="col-pos"></td>
                    <td class="col-nome inactive-nome">{{ item.nome }}</td>
                    @if (activeEntity() === 'sala_config') {
                      <td class="col-tipo inactive-tipo">{{ item.tipo_widget === 'text' ? 'Texto livre' : 'Ok/Falha' }}</td>
                    }
                    <td class="col-ativo"><input type="checkbox" [checked]="false" (change)="toggleAtivo(item, true)"></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <!-- Barra de ações -->
          <div class="actions-bar">
            <button class="btn-secondary-custom" (click)="cancel()" [disabled]="saving()">Cancelar</button>
            <div class="actions-right">
              @if (activeEntity() === 'sala_config' && isPlenarioNumerado()) {
                <button class="btn-aplicar" (click)="aplicarTodas()" [disabled]="saving()">Aplicar a Todos os Locais</button>
              }
              <button class="btn-primary-custom" (click)="save()" [disabled]="!dirty() || saving()">
                {{ saving() ? 'Salvando...' : 'Salvar' + (selectedSalaNome() ? ' — ' + selectedSalaNome() : ' Alterações') }}
              </button>
            </div>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .grid-cards { margin-bottom:24px; }

    .sala-select-row {
      display:flex; align-items:center; gap:10px; margin-bottom:12px;
      label { font-weight:600; font-size:.9375rem; white-space:nowrap; }
      select { width:300px; }
    }
    .info-msg {
      background:#eff6ff; border:1px solid #bfdbfe; border-radius:8px;
      padding:10px 14px; font-size:.85rem; color:#1e40af; margin-bottom:16px;
    }

    .table-container { overflow-x:auto; }

    .form-edit-table {
      width:100%; border-collapse:collapse;
      th {
        background:var(--table-header-bg); font-weight:600; color:var(--muted);
        padding:8px 10px; border-bottom:1px solid var(--table-border);
        text-align:left; font-size:.85rem;
      }
      td { padding:6px 10px; border-bottom:1px solid var(--table-border); font-size:.9rem; }
    }

    .col-drag { width:32px; text-align:center; }
    .col-pos { width:70px; text-align:center; font-weight:600; color:var(--muted); font-size:.85rem; }
    .col-tipo { width:130px; }
    .col-ativo { width:70px; text-align:center; }
    .col-nome { cursor:default; }

    .drag-handle { cursor:grab; color:#94a3b8; font-size:.85rem; user-select:none; letter-spacing:-2px; }
    .drag-handle:active { cursor:grabbing; }

    .form-edit-row { transition:background .1s; }
    .form-edit-row:hover { background:var(--row-hover); }
    .form-edit-row.drag-over { border-top:2px solid var(--primary); }
    .row-highlight td { background:#eff6ff; }

    .form-edit-row-inactive {
      td { opacity:.55; }
    }
    .inactive-nome { color:#64748b; }
    .inactive-tipo { color:#64748b; font-size:.85rem; }

    .blank-row td { background:#f8fafc; }

    .new-input {
      width:100%; border:1px dashed var(--border) !important; background:transparent !important;
      padding:4px 8px !important; font-size:.9rem; min-height:auto !important;
      border-radius:4px !important;
      &::placeholder { color:#94a3b8; }
    }

    .edit-input {
      width:100%; border:1px solid var(--primary) !important; background:#fff !important;
      padding:4px 8px !important; font-size:.9rem; min-height:auto !important;
      border-radius:4px !important; outline:none;
      box-shadow:0 0 0 3px var(--ring) !important;
    }

    .tipo-select {
      width:100%; padding:4px 6px !important; font-size:.85rem;
      min-height:auto !important; border-radius:4px !important;
    }

    .actions-bar {
      display:flex; justify-content:space-between; align-items:center;
      margin-top:20px; padding-top:16px; border-top:1px solid var(--border);
      gap:8px; flex-wrap:wrap;
    }
    .actions-right { display:flex; gap:8px; }

    .btn-secondary-custom {
      background:#fff; color:var(--text); border:1px solid var(--border);
      border-radius:999px; padding:10px 20px; font-weight:600; cursor:pointer;
      &:hover { background:var(--row-hover); }
      &:disabled { opacity:.5; cursor:not-allowed; }
    }
    .btn-aplicar {
      background:#059669; color:#fff; border:none; border-radius:999px;
      padding:10px 20px; font-weight:600; cursor:pointer;
      &:hover { background:#047857; }
      &:disabled { opacity:.5; cursor:not-allowed; }
    }
  `],
})
export class AdminFormEditComponent implements OnInit {
  private api = inject(ApiService);
  lookup = inject(LookupService);

  activeEntity = signal<string | null>(null);
  allItems = signal<EditItem[]>([]);
  loading = signal(false);
  saving = signal(false);
  dirty = signal(false);

  newItemNome = '';
  newItemTipo = 'radio';
  selectedSalaId = '';

  editingItem: EditItem | null = null;
  editValue = '';

  private dragType: 'item' | 'blank' | null = null;
  private dragFromIndex = -1;
  private dragOverIndex = -1;

  ngOnInit(): void { this.lookup.loadSalas(); }

  selectedSalaNome(): string {
    if (!this.selectedSalaId) return '';
    const sala = this.lookup.salas().find((s: any) => String(s.id) === this.selectedSalaId);
    return sala?.nome || '';
  }

  isPlenarioNumerado(): boolean {
    if (!this.selectedSalaId) return false;
    const sala = this.lookup.salas().find((s: any) => String(s.id) === this.selectedSalaId);
    if (!sala) return false;
    const nome = (sala.nome || '').trim().toLowerCase();
    // Exclui "plenário" (sem número) e qualquer nome que não siga o padrão "plenário XX"
    return /^plen.rio \d+$/.test(nome);
  }

  activeSalas(): any[] {
    return this.lookup.salas().filter((s: any) => s.ativo !== false);
  }

  activeItems = computed<EditItem[]>(() => this.allItems().filter(it => it.ativo));

  inactiveItems = computed<EditItem[]>(() => this.allItems().filter(it => !it.ativo));

  selectEntity(entity: string): void {
    this.activeEntity.set(entity);
    this.dirty.set(false);
    this.newItemNome = '';
    this.editingItem = null;
    this.selectedSalaId = '';
    if (entity === 'sala_config') {
      this.allItems.set([]);
    } else {
      this.loadEntity(entity);
    }
  }

  onSalaSelect(): void {
    if (!this.selectedSalaId) { this.allItems.set([]); this.dirty.set(false); return; }
    this.loadSalaConfig(parseInt(this.selectedSalaId, 10));
  }

  private loadEntity(entity: string): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/form-edit/${entity}/list`).subscribe({
      next: res => {
        this.allItems.set(res.items || []);
        this.loading.set(false);
        this.dirty.set(false);
      },
      error: () => { this.allItems.set([]); this.loading.set(false); },
    });
  }

  private loadSalaConfig(salaId: number): void {
    this.loading.set(true);
    this.api.get<any>(`/api/admin/form-edit/sala-config/${salaId}/list`).subscribe({
      next: res => {
        const items = (res.items || []).map((it: any) => ({ ...it, tipo_widget: it.tipo_widget || 'radio' }));
        this.allItems.set(items);
        this.loading.set(false);
        this.dirty.set(false);
      },
      error: () => { this.allItems.set([]); this.loading.set(false); },
    });
  }

  markChanged(item: EditItem): void {
    item._highlight = true;
    this.dirty.set(true);
    this.allItems.set([...this.allItems()]);
  }

  // ── Inline edit (duplo clique) ──

  startEdit(event: MouseEvent, item: EditItem): void {
    if (!item.ativo) return;
    this.editingItem = item;
    this.editValue = item.nome;
    setTimeout(() => {
      const input = (event.target as HTMLElement).closest('td')?.querySelector('input') as HTMLInputElement;
      if (input) { input.focus(); input.select(); }
    });
  }

  finishEdit(item: EditItem): void {
    if (!this.editingItem) return;
    const newVal = this.editValue.trim();
    if (newVal && newVal !== item.nome) {
      if (!this.validarComissao(newVal)) return;
      item.nome = newVal;
      item._highlight = true;
      this.dirty.set(true);
      this.allItems.set([...this.allItems()]);
    }
    this.editingItem = null;
  }

  cancelEdit(): void {
    this.editingItem = null;
  }

  private validarComissao(nome: string): boolean {
    if (this.activeEntity() !== 'comissoes') return true;
    const parts = nome.split(' - ');
    if (parts.length < 2 || parts[0].trim().length < 2 || parts.slice(1).join(' - ').trim().length < 5) {
      alert('Formato obrigatório: "SIGLA - Nome completo"\n\nExemplo: CAE - Comissão de Assuntos Econômicos\n\n• A sigla deve ter pelo menos 2 caracteres\n• Usar " - " (espaço, hífen, espaço) como separador\n• O nome completo deve ter pelo menos 5 caracteres');
      return false;
    }
    return true;
  }

  // ── Adicionar novo item ──

  addItem(): void {
    if (!this.newItemNome.trim()) return;
    if (!this.validarComissao(this.newItemNome.trim())) return;
    const item: EditItem = { id: null, nome: this.newItemNome.trim(), ativo: true, _highlight: true };
    if (this.activeEntity() === 'sala_config') item.tipo_widget = this.newItemTipo;
    const active = this.activeItems();
    const inactive = this.inactiveItems();
    this.allItems.set([...active, item, ...inactive]);
    this.newItemNome = '';
    this.dirty.set(true);
  }

  // ── Ativar/desativar ──

  toggleAtivo(item: EditItem, newValue: boolean): void {
    item.ativo = newValue;
    item._highlight = true;
    this.dirty.set(true);
    const active = this.allItems().filter(it => it.ativo);
    const inactive = this.allItems().filter(it => !it.ativo);
    this.allItems.set([...active, ...inactive]);
  }

  // ── Drag-and-drop ──

  onDragStart(event: DragEvent, index: number, type: 'item' | 'blank'): void {
    this.dragType = type;
    this.dragFromIndex = index;
    event.dataTransfer!.effectAllowed = 'move';
    event.dataTransfer!.setData('text/plain', '');
  }

  onDragOver(event: DragEvent, index: number): void {
    event.preventDefault();
    event.dataTransfer!.dropEffect = 'move';
    this.dragOverIndex = index;
  }

  onDrop(event: DragEvent, toIndex: number): void {
    event.preventDefault();
    if (this.dragType === 'item' && this.dragFromIndex >= 0 && this.dragFromIndex !== toIndex) {
      const active = [...this.activeItems()];
      const inactive = this.inactiveItems();
      const [moved] = active.splice(this.dragFromIndex, 1);
      if (toIndex > this.dragFromIndex) toIndex--;
      moved._highlight = true;
      active.splice(toIndex, 0, moved);
      this.allItems.set([...active, ...inactive]);
      this.dirty.set(true);
    }
    this.onDragEnd();
  }

  onDragEnd(): void {
    this.dragType = null;
    this.dragFromIndex = -1;
    this.dragOverIndex = -1;
  }

  // ── Salvar ──

  save(): void {
    const entity = this.activeEntity();
    if (!entity) return;
    this.saving.set(true);

    const isSalaConfig = entity === 'sala_config';
    const endpoint = isSalaConfig
      ? `/api/admin/form-edit/sala-config/${this.selectedSalaId}/save`
      : `/api/admin/form-edit/${entity}/save`;
    const payload = isSalaConfig
      ? { items: this.allItems().map(it => ({ nome: it.nome, tipo_widget: it.tipo_widget || 'radio', ativo: it.ativo })) }
      : { items: this.allItems() };
    const successMsg = isSalaConfig ? 'Configuração salva com sucesso!' : 'Salvo com sucesso!';
    const reload = isSalaConfig
      ? () => this.loadSalaConfig(parseInt(this.selectedSalaId, 10))
      : () => this.loadEntity(entity);

    this.api.post<any>(endpoint, payload).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) { alert(successMsg); reload(); }
        else alert(res.message || 'Erro ao salvar.');
      },
      error: () => { this.saving.set(false); alert('Erro ao salvar.'); },
    });
  }

  aplicarTodas(): void {
    if (!this.selectedSalaId) { alert('Selecione uma sala primeiro.'); return; }
    if (!confirm('Deseja aplicar a configuração atual a TODOS os locais?\n\nIsso irá sobrescrever a configuração individual de cada local.')) return;
    this.saving.set(true);
    const payload = {
      source_sala_id: parseInt(this.selectedSalaId, 10),
      items: this.allItems().map(it => ({ nome: it.nome, tipo_widget: it.tipo_widget || 'radio', ativo: it.ativo })),
    };
    this.api.post<any>('/api/admin/form-edit/sala-config/aplicar-todas', payload).subscribe({
      next: res => {
        this.saving.set(false);
        if (res.ok) alert(`Configuração aplicada a ${res.salas_atualizadas} sala(s).`);
        else alert(res.message || 'Erro ao aplicar.');
      },
      error: () => { this.saving.set(false); alert('Erro ao aplicar.'); },
    });
  }

  cancel(): void {
    this.dirty.set(false);
    this.editingItem = null;
    const entity = this.activeEntity();
    if (!entity) return;
    if (entity === 'sala_config' && this.selectedSalaId) this.loadSalaConfig(parseInt(this.selectedSalaId, 10));
    else if (entity !== 'sala_config') this.loadEntity(entity);
  }
}

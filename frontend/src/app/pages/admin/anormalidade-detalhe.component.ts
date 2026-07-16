import { Component, inject, OnInit, signal, WritableSignal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';

@Component({
  selector: 'app-anormalidade-detalhe',
  standalone: true,
  imports: [FormsModule, FmtDatePipe, FmtTimePipe],
  template: `
    <div class="card-custom detalhe-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!d()) {
        <p class="text-muted-sm">Anormalidade não encontrada.</p>
      } @else {
        <div class="detalhe-header">
          <h1>Detalhe da Anormalidade</h1>
          <span class="badge-readonly">APENAS LEITURA</span>
        </div>

        <!-- 1) Dados do Evento -->
        <h3 class="section-title">1) Dados do Evento</h3>
        <div class="field-row grid-3">
          <div class="field">
            <label>Data</label>
            <div class="field-value">{{ d()!['data'] | fmtDate }}</div>
          </div>
          <div class="field">
            <label>Local do evento</label>
            <div class="field-value">{{ d()!['sala_nome'] }}</div>
          </div>
          <div class="field">
            <label>Nome do evento</label>
            <div class="field-value">{{ d()!['nome_evento'] || '-' }}</div>
          </div>
        </div>

        <!-- 2) Responsáveis -->
        <h3 class="section-title">2) Responsáveis</h3>
        <div class="field">
          <label>Nome do Secretário da Comissão, da Mesa ou do responsável pelo evento</label>
          <div class="field-value">{{ d()!['responsavel_evento'] || '-' }}</div>
        </div>

        <!-- 3) Detalhes da Anormalidade -->
        <h3 class="section-title">3) Detalhes da Anormalidade</h3>
        <div class="field">
          <label>Horário do início da anormalidade</label>
          <div class="field-value">{{ d()!['hora_inicio_anormalidade'] | fmtTime }}</div>
        </div>
        <div class="field">
          <label>Descrição da anormalidade</label>
          <div class="field-value obs-value">{{ d()!['descricao_anormalidade'] || '' }}</div>
        </div>

        <!-- Houve suspensão/adiamento/cancelamento -->
        <div class="toggle-row">
          <div class="field">
            <label>Houve suspensão, adiamento ou cancelamento do evento?</label>
            <div class="field-value" [class]="d()!['houve_prejuizo'] ? 'val-red' : 'val-green'">
              {{ d()!['houve_prejuizo'] ? 'Sim' : 'Não' }}
            </div>
          </div>
          @if (d()!['houve_prejuizo']) {
            <div class="field">
              <label>Duração do adiamento ou cancelamento do evento</label>
              <div class="field-value obs-value">{{ d()!['descricao_prejuizo'] || '' }}</div>
            </div>
          }
        </div>

        <!-- Houve reclamação -->
        <div class="toggle-row">
          <div class="field">
            <label>Houve reclamação?</label>
            <div class="field-value" [class]="d()!['houve_reclamacao'] ? 'val-red' : 'val-green'">
              {{ d()!['houve_reclamacao'] ? 'Sim' : 'Não' }}
            </div>
          </div>
          @if (d()!['houve_reclamacao']) {
            <div class="field">
              <label>Autor(es) e conteúdo resumido da reclamação</label>
              <div class="field-value obs-value">{{ d()!['autores_conteudo_reclamacao'] || '' }}</div>
            </div>
          }
        </div>

        <!-- Acionou manutenção -->
        <div class="toggle-row">
          <div class="field">
            <label>Foi necessário acionar a manutenção?</label>
            <div class="field-value" [class]="d()!['acionou_manutencao'] ? 'val-red' : 'val-green'">
              {{ d()!['acionou_manutencao'] ? 'Sim' : 'Não' }}
            </div>
          </div>
          @if (d()!['acionou_manutencao']) {
            <div class="field">
              <label>Horário do acionamento da manutenção</label>
              <div class="field-value">{{ d()!['hora_acionamento_manutencao'] | fmtTime }}</div>
            </div>
          }
        </div>

        <!-- Resolvida pelo operador -->
        <div class="toggle-row">
          <div class="field">
            <label>A anormalidade foi resolvida pela própria operação?</label>
            <div class="field-value" [class]="d()!['resolvida_pelo_operador'] ? 'val-green' : 'val-red'">
              {{ d()!['resolvida_pelo_operador'] ? 'Sim' : 'Não' }}
            </div>
          </div>
          @if (d()!['resolvida_pelo_operador']) {
            <div class="field">
              <label>Descrição do procedimento adotado</label>
              <div class="field-value obs-value">{{ d()!['procedimentos_adotados'] || '' }}</div>
            </div>
          }
        </div>

        <!-- Registrado por -->
        <div class="field" style="margin-top:16px">
          <label>Registrado por</label>
          <div class="field-value">{{ d()!['criado_por_nome'] || 'Sistema' }}</div>
        </div>

        <!-- 4) Observações Administrativas -->
        <h3 class="section-title">4) Observações</h3>

        <div class="field">
          <label>Observações do Supervisor</label>
          @if (canEditSupervisor()) {
            <textarea class="field-input" [(ngModel)]="obsSupervisor" rows="3" placeholder="Escreva a observação do supervisor..."></textarea>
            <div class="obs-actions">
              <button class="btn-save" (click)="salvarObsSupervisor()" [disabled]="savingSup()">{{ savingSup() ? 'Salvando...' : 'Salvar' }}</button>
            </div>
          } @else {
            <div class="field-value obs-value">{{ d()!['observacao_supervisor'] || '(nenhuma observação)' }}</div>
          }
        </div>

        <div class="field">
          <label>Observações do Chefe de Serviço</label>
          @if (canEditChefe()) {
            <textarea class="field-input" [(ngModel)]="obsChefe" rows="3" placeholder="Escreva a observação do chefe..."></textarea>
            <div class="obs-actions">
              <button class="btn-save" (click)="salvarObsChefe()" [disabled]="savingChefe()">{{ savingChefe() ? 'Salvando...' : 'Salvar' }}</button>
            </div>
          } @else {
            <div class="field-value obs-value">{{ d()!['observacao_chefe'] || '(nenhuma observação)' }}</div>
          }
        </div>

        <!-- Ações -->
        <div class="detalhe-actions">
          <button class="btn-fechar" (click)="fechar()">Fechar Aba</button>
          <button class="btn-imprimir" (click)="imprimir()">Imprimir</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .section-title { font-weight: 600; font-size: .95rem; color: #374151; margin: 20px 0 8px; }
    .toggle-row {
      display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: start;
      margin-bottom: 4px;
    }
    .field-input {
      width: 100%; padding: 8px 12px; font-size: .9rem;
      border: 2px solid var(--primary); border-radius: 6px;
      resize: vertical; font-family: inherit;
    }
    .obs-actions { display: flex; gap: 8px; margin-top: 6px; }
    .btn-save {
      background: var(--primary); color: #fff; border: none; border-radius: 6px;
      padding: 6px 16px; font-size: .85rem; font-weight: 600; cursor: pointer;
      &:hover { background: var(--primary-hover); }
      &:disabled { opacity: .5; cursor: not-allowed; }
    }
  `],
})
export class AnormalidadeDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);
  private auth = inject(AuthService);

  loading = signal(true);
  d = signal<Record<string, any> | null>(null);
  savingSup = signal(false);
  savingChefe = signal(false);
  isAdmin = signal(false);

  obsSupervisor = '';
  obsChefe = '';

  private anomId = 0;

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('id');
    if (!id) { this.loading.set(false); return; }
    this.anomId = +id;

    // Detecta se é rota admin ou operador
    const url = this.route.snapshot.pathFromRoot.map(r => r.url.map(s => s.path).join('/')).join('/');
    this.isAdmin.set(url.includes('admin'));
    const endpoint = this.isAdmin()
      ? '/api/admin/anormalidade/detalhe'
      : '/api/operador/anormalidade/detalhe';

    this.api.get<any>(endpoint, { id: this.anomId }).subscribe({
      next: (res: any) => {
        const data = res?.data ?? res;
        this.d.set(data);
        this.obsSupervisor = data?.observacao_supervisor || '';
        this.obsChefe = data?.observacao_chefe || '';
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  canEditSupervisor(): boolean {
    if (!this.isAdmin()) return false;
    return this.auth.user()?.canEditObsSupervisor === true;
  }

  canEditChefe(): boolean {
    if (!this.isAdmin()) return false;
    return this.auth.user()?.canEditObsChefe === true;
  }

  private salvarObs(valor: string, saving: WritableSignal<boolean>, endpoint: string, sucessoMsg: string): void {
    if (!valor.trim()) return;
    saving.set(true);
    this.api.post(endpoint, { id: this.anomId, observacao: valor.trim() }).subscribe({
      next: () => { saving.set(false); alert(sucessoMsg); },
      error: () => { saving.set(false); alert('Erro ao salvar observação.'); },
    });
  }

  salvarObsSupervisor(): void {
    this.salvarObs(this.obsSupervisor, this.savingSup, '/api/admin/anormalidade/observacao-supervisor', 'Observação do supervisor salva com sucesso.');
  }

  salvarObsChefe(): void {
    this.salvarObs(this.obsChefe, this.savingChefe, '/api/admin/anormalidade/observacao-chefe', 'Observação do chefe salva com sucesso.');
  }

  fechar(): void { window.close(); }
  imprimir(): void { window.print(); }
}

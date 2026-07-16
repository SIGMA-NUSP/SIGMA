import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';
import { duracaoHms } from '../../core/helpers/date.helpers';
import { asArray } from '../../core/helpers/format.helpers';
import { VersoesHistoricoComponent, VersaoMeta } from '../../shared/components/versoes-historico.component';

@Component({
  selector: 'app-checklist-detalhe',
  standalone: true,
  imports: [FmtDatePipe, FmtTimePipe, VersoesHistoricoComponent],
  template: `
    <div class="card-custom detalhe-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!dataAtual()) {
        <p class="text-muted-sm">Checklist não encontrado.</p>
      } @else {
        <div class="detalhe-header">
          <h1>Detalhe da Verificação de Plenário</h1>
          <span class="badge-readonly">APENAS LEITURA</span>
        </div>

        <!-- Tabs Atual / Histórico + carga de versões -->
        <app-versoes-historico
          endpointBase="/api/admin/checklist/historico"
          idParamName="checklist_id"
          [idValue]="dataAtual()!['id']"
          [historicoHabilitado]="!!dataAtual()?.['editado']"
          (modoChange)="viewMode.set($event)"
          (versaoCarregada)="onVersaoCarregada($event)" />

        @if (dataDisplay(); as dd) {
          <!-- 1) Identificação -->
          <h3>1) Identificação</h3>
          <div class="field-row two-cols">
            <div class="field">
              <label>Data</label>
              <div class="field-value">{{ dd['data_operacao'] | fmtDate }}</div>
            </div>
            <div class="field">
              <label>Local</label>
              <div class="field-value">{{ dd['sala_nome'] }}</div>
            </div>
          </div>
          <div class="field-row three-cols">
            <div class="field">
              <label>Início</label>
              <div class="field-value">{{ dd['hora_inicio_testes'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Término</label>
              <div class="field-value">{{ dd['hora_termino_testes'] | fmtTime }}</div>
            </div>
            <div class="field">
              <label>Duração</label>
              <div class="field-value">{{ calcDuracao() }}</div>
            </div>
          </div>

          <!-- 2) Itens Verificados -->
          <h3>2) Itens Verificados</h3>
          @for (it of itensDisplay(); track it['id']) {
            <div class="item-row" [class.item-falha]="it['status'] === 'Falha'">
              <span class="item-nome">{{ it['item_nome'] }}</span>
              @if (it['tipo_widget'] !== 'text') {
                <span class="item-status" [class]="it['status'] === 'Falha' ? 'status-falha' : 'status-ok'">
                  {{ it['status'] === 'Falha' ? '✖ Falha' : '✅ Ok' }}
                </span>
              }
            </div>
            @if (it['status'] === 'Falha' && it['descricao_falha']) {
              <div class="item-desc falha-desc">
                <strong>Descrição da falha:</strong> {{ it['descricao_falha'] }}
              </div>
            }
            @if (it['valor_texto']) {
              <div class="item-desc texto-desc">
                {{ it['valor_texto'] }}
              </div>
            }
          }

          <!-- 3) Observações -->
          <h3>3) Observações</h3>
          <div class="field">
            <label>Anotações gerais</label>
            <div class="field-value obs-value">{{ dd['observacoes'] || '' }}</div>
          </div>

          <!-- 4) Responsável -->
          <h3>4) Responsável</h3>
          @if (dd['operadores_cabine'] || dd['operadores_plenario']) {
            <div class="field-grid two-cols">
              <div class="field">
                <label>Cabine</label>
                <div class="field-value">{{ asArray(dd['operadores_cabine']).join(', ') || '-' }}</div>
              </div>
              <div class="field">
                <label>Plenário</label>
                <div class="field-value">{{ asArray(dd['operadores_plenario']).join(', ') || '-' }}</div>
              </div>
            </div>
            <div class="field">
              <label>Preenchido por</label>
              <div class="field-value">{{ dd['operador_nome'] }}</div>
            </div>
          } @else {
            <div class="field">
              <label>Verificado por</label>
              <div class="field-value">{{ dd['operador_nome'] }}</div>
            </div>
          }
        }

        <!-- Ações -->
        <div class="detalhe-actions">
          <button class="btn-fechar" (click)="fechar()">Fechar Aba</button>
          <button class="btn-imprimir" (click)="imprimir()">Imprimir</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .badge-readonly { background: #047857; }
    h3 { font-size: .95rem; margin: 24px 0 8px; color: var(--text); }
    .two-cols { grid-template-columns: 1fr 1fr; }
    .three-cols { grid-template-columns: 1fr 1fr 1fr; }
    .item-row {
      display: flex; justify-content: space-between; align-items: center;
      padding: 10px 16px; border: 1px solid var(--border); border-radius: 6px;
      margin-bottom: 4px; background: #fff;
    }
    .item-falha { background: #fef2f2; }
    .item-nome { font-size: .9rem; }
    .item-status { font-size: .85rem; font-weight: 600; white-space: nowrap; }
    .status-ok { color: #16a34a; }
    .status-falha { color: #dc2626; }
    .item-desc {
      margin: 0 0 4px 0; padding: 6px 16px; font-size: .85rem;
      border-radius: 0 0 6px 6px; margin-top: -4px;
    }
    .falha-desc { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-top: none; }
    .texto-desc { background: #f8fafc; border: 1px solid var(--border); border-top: none; }
  `],
})
export class ChecklistDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);

  loading = signal(true);
  dataAtual = signal<Record<string, any> | null>(null);
  itensAtual = signal<Record<string, any>[]>([]);

  viewMode = signal<'atual' | 'historico'>('atual');
  dataVersao = signal<Record<string, any> | null>(null);
  itensVersao = signal<Record<string, any>[]>([]);

  dataDisplay = computed<Record<string, any> | null>(() =>
    this.viewMode() === 'historico' ? this.dataVersao() : this.dataAtual()
  );
  itensDisplay = computed<Record<string, any>[]>(() =>
    this.viewMode() === 'historico' ? this.itensVersao() : this.itensAtual()
  );

  asArray = asArray;

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('checklist_id');
    if (!id) { this.loading.set(false); return; }

    this.api.get<any>('/api/admin/checklist/detalhe', { checklist_id: +id }).subscribe({
      next: (res: any) => {
        const d = res?.data ?? res;
        this.dataAtual.set(d);
        this.itensAtual.set(d?.itens ?? []);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  onVersaoCarregada(e: { meta: VersaoMeta; dados: Record<string, any> | null }): void {
    const d = e.dados;
    this.dataVersao.set(d);
    this.itensVersao.set(d?.['itens'] ?? []);
  }

  calcDuracao = computed<string>(() => {
    const d = this.dataDisplay();
    if (!d) return '-';
    return duracaoHms(String(d['hora_inicio_testes'] || ''), String(d['hora_termino_testes'] || ''));
  });

  fechar(): void { window.close(); }

  imprimir(): void { window.print(); }
}

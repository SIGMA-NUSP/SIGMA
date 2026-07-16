import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { FmtDatePipe } from '../../shared/pipes/fmt-date.pipe';
import { FmtTimePipe } from '../../shared/pipes/fmt-time.pipe';
import { asArray } from '../../core/helpers/format.helpers';
import { VersoesHistoricoComponent, VersaoMeta } from '../../shared/components/versoes-historico.component';

@Component({
  selector: 'app-operacao-detalhe',
  standalone: true,
  imports: [FmtDatePipe, FmtTimePipe, VersoesHistoricoComponent],
  template: `
    <div class="card-custom detalhe-card">
      @if (loading()) {
        <p class="text-muted-sm">Carregando...</p>
      } @else if (!dAtual()) {
        <p class="text-muted-sm">Registro não encontrado.</p>
      } @else {
        <div class="detalhe-header">
          <div>
            <h1>Detalhe do Registro de Operação</h1>
            <p class="text-muted-sm">Visualização administrativa do registro nº {{ dAtual()!['id'] }}</p>
          </div>
          <span class="badge-readonly">APENAS LEITURA</span>
        </div>

        <!-- Tabs Atual / Histórico + carga de versões -->
        <app-versoes-historico
          endpointBase="/api/admin/operacao/historico"
          idParamName="entrada_id"
          [idValue]="dAtual()!['id']"
          [historicoHabilitado]="!!dAtual()?.['editado']"
          (modoChange)="viewMode.set($event)"
          (versaoCarregada)="onVersaoCarregada($event)" />

        @if (dDisplay(); as dd) {
          <!-- Local -->
          <div class="field">
            <label>Local</label>
            <div class="field-value">{{ dd['sala_nome'] }}</div>
          </div>

          @if (dd['nome_demais_salas']) {
            <div class="field">
              <label>Nome da Sala</label>
              <div class="field-value">{{ dd['nome_demais_salas'] }}</div>
            </div>
          }

          @if (dd['multi_operador']) {
            <!-- ═══ PLENÁRIO PRINCIPAL ═══ -->

            <div class="field">
              <label>Descrição do Evento</label>
              <div class="field-value">{{ dd['nome_evento'] }}</div>
            </div>

            <div class="field-row grid-3">
              <div class="field">
                <label>Data</label>
                <div class="field-value">{{ dd['data'] | fmtDate }}</div>
              </div>
              <div class="field">
                <label>Início da sessão</label>
                <div class="field-value">{{ dd['horario_inicio'] | fmtTime }}</div>
              </div>
              <div class="field">
                <label>Término da sessão</label>
                <div class="field-value">{{ dd['horario_termino'] | fmtTime }}</div>
              </div>
            </div>

            <div class="field">
              <label>Suspensões</label>
              @if (asArray(dd['suspensoes']).length > 0) {
                @for (s of asArray(dd['suspensoes']); track $index) {
                  <div class="field-value" style="margin-bottom:4px">
                    Suspensa em: {{ s['hora_suspensao'] }} &mdash; Reaberta em: {{ s['hora_reabertura'] }}
                  </div>
                }
              } @else {
                <div class="field-value">Nenhuma</div>
              }
            </div>

            <div class="field-row grid-2">
              <div class="field">
                <label>Trilha do Gravador 01</label>
                <div class="field-value">{{ dd['usb_01'] || '-' }}</div>
              </div>
              <div class="field">
                <label>Trilha do Gravador 02</label>
                <div class="field-value">{{ dd['usb_02'] || '-' }}</div>
              </div>
            </div>

            <div class="field">
              <label>Observações</label>
              <div class="field-value obs-value">{{ dd['observacoes'] || '' }}</div>
            </div>

            <div class="field">
              <label>Houve Anormalidade?</label>
              <div class="field-value" [class]="dd['houve_anormalidade'] ? 'val-falha' : ''">
                {{ dd['houve_anormalidade'] ? 'Sim' : 'Não' }}
              </div>
            </div>

            <div class="field">
              <label>Preenchido por</label>
              <div class="field-value">{{ dd['operador_nome'] }}</div>
            </div>

            @if (dd['operadores_sessao']) {
              <div class="field">
                <label>Operadores da Sessão</label>
                <div class="field-value">{{ asArray(dd['operadores_sessao']).join(', ') }}</div>
              </div>
            }

          } @else {
            <!-- ═══ PLENÁRIOS NUMERADOS ═══ -->

            <div class="field">
              <label>Atividade Legislativa</label>
              <div class="field-value">{{ dd['comissao_nome'] || '-' }}</div>
            </div>

            <div class="field">
              <label>Descrição do Evento</label>
              <div class="field-value">{{ dd['nome_evento'] }}</div>
            </div>

            <div class="field">
              <label>Responsável pelo Evento</label>
              <div class="field-value">{{ dd['responsavel_evento'] || '-' }}</div>
            </div>

            <div class="field-row grid-4">
              <div class="field">
                <label>Data</label>
                <div class="field-value">{{ dd['data'] | fmtDate }}</div>
              </div>
              <div class="field">
                <label>Horário de Pauta</label>
                <div class="field-value">{{ dd['horario_pauta'] | fmtTime }}</div>
              </div>
              <div class="field">
                <label>Hora de Início</label>
                <div class="field-value">{{ dd['horario_inicio'] | fmtTime }}</div>
              </div>
              <div class="field">
                <label>Evento Encerrado?</label>
                <div class="field-value">{{ dd['horario_termino'] ? 'Sim' : 'Não' }}</div>
              </div>
            </div>

            <div class="field-row grid-3">
              <div class="field">
                <label>Hora de Término</label>
                <div class="field-value">{{ dd['horario_termino'] | fmtTime }}</div>
              </div>
              <div class="field">
                <label>Hora de Entrada (operador)</label>
                <div class="field-value">{{ dd['hora_entrada'] | fmtTime }}</div>
              </div>
              <div class="field">
                <label>Hora de Saída (operador)</label>
                <div class="field-value">{{ dd['hora_saida'] | fmtTime }}</div>
              </div>
            </div>

            <div class="field-row grid-2">
              <div class="field">
                <label>Trilha do Gravador 01</label>
                <div class="field-value">{{ dd['usb_01'] || '-' }}</div>
              </div>
              <div class="field">
                <label>Trilha do Gravador 02</label>
                <div class="field-value">{{ dd['usb_02'] || '-' }}</div>
              </div>
            </div>

            <div class="field">
              <label>Observações</label>
              <div class="field-value obs-value">{{ dd['observacoes'] || '' }}</div>
            </div>

            <div class="field">
              <label>Houve Anormalidade?</label>
              <div class="field-value" [class]="dd['houve_anormalidade'] ? 'val-falha' : ''">
                {{ dd['houve_anormalidade'] ? 'Sim' : 'Não' }}
              </div>
            </div>

            <div class="field">
              <label>Operador Responsável</label>
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
    .val-falha { color: #dc2626; font-weight: 700; }
  `],
})
export class OperacaoDetalheComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);

  loading = signal(true);
  dAtual = signal<Record<string, any> | null>(null);

  viewMode = signal<'atual' | 'historico'>('atual');
  dVersao = signal<Record<string, any> | null>(null);

  dDisplay = computed<Record<string, any> | null>(() =>
    this.viewMode() === 'historico' ? this.dVersao() : this.dAtual()
  );

  asArray = asArray;

  ngOnInit(): void {
    const id = this.route.snapshot.queryParamMap.get('entrada_id');
    if (!id) { this.loading.set(false); return; }

    this.api.get<any>('/api/admin/operacao/detalhe', { entrada_id: +id }).subscribe({
      next: (res: any) => {
        this.dAtual.set(res?.data ?? res);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); },
    });
  }

  onVersaoCarregada(e: { meta: VersaoMeta; dados: Record<string, any> | null }): void {
    this.dVersao.set(e.dados);
  }

  fechar(): void { window.close(); }
  imprimir(): void { window.print(); }
}

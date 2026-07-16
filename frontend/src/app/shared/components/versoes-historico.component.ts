import { Component, computed, inject, input, output, signal } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { FmtDateTimePipe } from '../pipes/fmt-datetime.pipe';

export type VersaoMeta = {
  historico_id: number;
  numero_versao: number;
  editado_por: string | null;
  editado_por_nome: string | null;
  editado_em: string | null;
};

/**
 * Tabs "Atual / Histórico" + banner + chips de versão usados nas telas de detalhe admin
 * (operacao-detalhe e checklist-detalhe). Encapsula a carga preguiçosa das versões:
 * ao entrar no modo histórico pela 1ª vez, busca a lista (mais recente primeiro) e
 * auto-seleciona a versão mais recente; cada seleção busca os dados brutos daquela versão.
 *
 * O host mantém o corpo (campos/itens) e computa o que exibir a partir dos outputs:
 * `modoChange` sincroniza o modo e `versaoCarregada` entrega os dados brutos da versão
 * (o host interpreta `itens` etc.). As classes de estilo são globais em styles.scss.
 */
@Component({
  selector: 'app-versoes-historico',
  standalone: true,
  imports: [FmtDateTimePipe],
  template: `
    <!-- Tabs Atual / Histórico -->
    <div class="detalhe-tabs">
      <button class="detalhe-tab" [class.active]="modo()==='atual'" (click)="selecionarAtual()">Atual</button>
      <button class="detalhe-tab" [class.active]="modo()==='historico'" [disabled]="!historicoHabilitado()" (click)="selecionarHistorico()">
        Histórico
        @if (totalVersoes() > 0) { <span class="detalhe-tab-badge">{{ totalVersoes() }}</span> }
      </button>
    </div>

    @if (modo()==='historico') {
      @if (loadingVersoes()) {
        <p class="text-muted-sm">Carregando histórico...</p>
      } @else if (versoes().length === 0) {
        <p class="text-muted-sm">Nenhuma versão anterior encontrada.</p>
      } @else {
        @if (versaoExibida(); as v) {
          <div class="historico-banner">
            <strong>Versão {{ v.numero_versao }}</strong> —
            Salva em {{ v.editado_em | fmtDateTime }}
            @if (v.editado_por_nome) { por <strong>{{ v.editado_por_nome }}</strong> }
          </div>
        }
        <div class="versoes-list">
          @for (v of versoes(); track v.historico_id) {
            <button class="versao-chip" [class.active]="versaoExibida()?.historico_id === v.historico_id" (click)="selecionarVersao(v)">
              v{{ v.numero_versao }} · {{ v.editado_em | fmtDateTime }}
            </button>
          }
        </div>
      }
    }
  `,
  styles: [`:host { display: block; }`],
})
export class VersoesHistoricoComponent {
  private api = inject(ApiService);

  /** Ex.: '/api/admin/operacao/historico' | '/api/admin/checklist/historico'. */
  endpointBase = input.required<string>();
  /** Nome do parâmetro do id do registro na query. Ex.: 'entrada_id' | 'checklist_id'. */
  idParamName = input.required<string>();
  /** Valor do id do registro atual (host passa dAtual()['id']). */
  idValue = input.required<number>();
  /** Habilita a tab Histórico (host passa !!dAtual()?.['editado']). */
  historicoHabilitado = input<boolean>(false);

  modoChange = output<'atual' | 'historico'>();
  versaoCarregada = output<{ meta: VersaoMeta; dados: Record<string, any> | null }>();

  modo = signal<'atual' | 'historico'>('atual');
  versoes = signal<VersaoMeta[]>([]);
  loadingVersoes = signal(false);
  versaoExibida = signal<VersaoMeta | null>(null);

  totalVersoes = computed(() => this.versoes().length);

  selecionarAtual(): void {
    this.modo.set('atual');
    this.modoChange.emit('atual');
  }

  selecionarHistorico(): void {
    this.modo.set('historico');
    this.modoChange.emit('historico');
    if (this.versoes().length === 0) this.carregarVersoes();
  }

  private carregarVersoes(): void {
    const id = this.idValue();
    if (!id) return;
    this.loadingVersoes.set(true);
    this.api.get<any>(this.endpointBase(), { [this.idParamName()]: +id }).subscribe({
      next: (res: any) => {
        const lista = (res?.data ?? []) as VersaoMeta[];
        const ordenadas = [...lista].reverse();
        this.versoes.set(ordenadas);
        this.loadingVersoes.set(false);
        if (ordenadas.length > 0) this.selecionarVersao(ordenadas[0]);
      },
      error: () => { this.loadingVersoes.set(false); },
    });
  }

  selecionarVersao(v: VersaoMeta): void {
    this.versaoExibida.set(v);
    this.api.get<any>(this.endpointBase() + '/versao', { historico_id: v.historico_id }).subscribe({
      next: (res: any) => { this.versaoCarregada.emit({ meta: v, dados: res?.data ?? res }); },
      error: () => { this.versaoCarregada.emit({ meta: v, dados: null }); },
    });
  }
}

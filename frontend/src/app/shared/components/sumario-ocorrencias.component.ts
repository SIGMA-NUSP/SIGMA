import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { ANO_MINIMO_SUMARIO, MESES } from '../../core/helpers/table.helpers';
import { ColumnFilterComponent, ColumnFilterDef, ColumnFilterState } from './column-filter.component';
import { anosNavegaveis } from './mes-ano-selector.component';

/** Uma coluna do sumário: o código impresso na folha e quantos dias ele soma no período. */
interface Ocorrencia { codigo: string; total: number; }

/** Uma linha: o funcionário e os dias dele por código (só os códigos com contagem). */
interface FuncionarioSumario {
  pessoa_id: string;
  pessoa_tipo: string;
  nome: string;
  contagens: Record<string, number>;
}

interface SumarioData {
  de: string;
  ate: string;
  ocorrencias: Ocorrencia[];
  funcionarios: FuncionarioSumario[];
}

/**
 * Significado dos códigos que o cartão Secullum imprime, como a supervisora da empresa os define.
 * O sumário mostra sempre o CÓDIGO (é o que está na folha, e é por ele que a conferência é feita);
 * a tradução aparece como tooltip. Código novo — o Secullum pode criar um a qualquer momento —
 * vira coluna do mesmo jeito, só que sem tooltip.
 */
export const TRADUCAO_OCORRENCIA: Record<string, string> = {
  BancN: 'Banco de Horas',
  Feriado: 'Feriado',
  FERNC: 'Férias',
  'P.facul': 'Ponto Facultativo',
  Atecc: 'Atestado Com Cobertura',
  Atesc: 'Atestado Sem Cobertura',
  Atjus: 'Atestado Judicial',
  Falta: 'Falta',
};

/** Índice em caixa baixa: a folha pode imprimir o mesmo código com outra caixa. */
const TRADUCAO_POR_CODIGO = new Map(
  Object.entries(TRADUCAO_OCORRENCIA).map(([codigo, texto]) => [codigo.toLowerCase(), texto]),
);

/**
 * Card admin "Sumário": matriz funcionários × ocorrências das folhas de ponto ("Ocorrências
 * Secullum") num intervalo de competências. As colunas são as ocorrências que as folhas do
 * período realmente trouxeram, da mais frequente para a menos; a célula conta DIAS.
 *
 * <p>Feriado, ponto facultativo e dispensa de ponto não entram (o backend os retira): valem para a
 * equipe inteira nos mesmos dias e só encheriam a tabela. A escolha de qual folha representa cada
 * mês — definitiva, prévia ou a última semanal — também é do backend; aqui é só apresentação.
 *
 * <p>O recorte da tela é local: filtro de valores na coluna Funcionário, classificação em todas, e
 * o rodapé de totais soma as linhas exibidas — filtrar muda o total junto.
 */
@Component({
  selector: 'app-sumario-ocorrencias',
  standalone: true,
  imports: [ColumnFilterComponent],
  template: `
    <div class="section-header"><h2>Ocorrências Secullum</h2></div>

    <div class="barra">
      <span class="rot">De</span>
      <select class="sel-mes" aria-label="Mês inicial" (change)="onMes('de', $event)">
        @for (m of meses; track $index) {
          <option [value]="$index + 1" [selected]="deMes() === $index + 1">{{ m }}</option>
        }
      </select>
      <select class="sel-ano" aria-label="Ano inicial" (change)="onAno('de', $event)">
        @for (a of anos; track a) { <option [value]="a" [selected]="deAno() === a">{{ a }}</option> }
      </select>

      <span class="rot">Até</span>
      <select class="sel-mes" aria-label="Mês final" (change)="onMes('ate', $event)">
        @for (m of meses; track $index) {
          <option [value]="$index + 1" [selected]="ateMes() === $index + 1">{{ m }}</option>
        }
      </select>
      <select class="sel-ano" aria-label="Ano final" (change)="onAno('ate', $event)">
        @for (a of anos; track a) { <option [value]="a" [selected]="ateAno() === a">{{ a }}</option> }
      </select>
    </div>

    @if (erro()) {
      <div class="error-box">{{ erro() }}</div>
    } @else if (carregando()) {
      <p class="text-muted-sm">Carregando sumário...</p>
    } @else if (funcionarios().length === 0) {
      <p class="empty-state">Nenhuma folha publicada no período selecionado.</p>
    } @else if (ocorrencias().length === 0) {
      <p class="empty-state">Nenhuma ocorrência registrada nas folhas do período.</p>
    } @else {
      <div class="table-container">
        <table class="data-table sumario">
          <thead>
            <tr>
              <th class="col-nome">
                <app-column-filter [col]="colFuncionario" [distinctValues]="nomesDistintos()"
                                   [currentSort]="sortKey()" [currentDir]="sortDir()"
                                   (sortChange)="onSort($event)" (filterChange)="onFiltroNomes($event)" />
              </th>
              @for (o of ocorrencias(); track o.codigo) {
                <!-- attr.title (e não title): a property recebendo null viraria o texto "null" -->
                <!-- Sem distinctValues o painel só oferece Classificar: contagem não se filtra por valor -->
                <th scope="col" class="col-oc" [class.traduzida]="!!traducao(o.codigo)"
                    [attr.title]="traducao(o.codigo) || null">
                  <app-column-filter [col]="colOcorrencia(o.codigo)"
                                     [currentSort]="sortKey()" [currentDir]="sortDir()"
                                     (sortChange)="onSort($event)" />
                </th>
              }
            </tr>
          </thead>
          <tbody>
            <!-- A pessoa é o par (id, tipo): os três cadastros não compartilham chave -->
            @for (f of linhas(); track f.pessoa_tipo + ':' + f.pessoa_id) {
              <tr>
                <td class="col-nome">{{ f.nome }}</td>
                @for (o of ocorrencias(); track o.codigo) {
                  <td class="col-oc">{{ contagem(f, o.codigo) }}</td>
                }
              </tr>
            } @empty {
              <tr><td class="empty-state" [attr.colspan]="ocorrencias().length + 1">
                Nenhum funcionário corresponde ao filtro.
              </td></tr>
            }
          </tbody>
          <tfoot>
            <!-- O rodapé soma as linhas exibidas: com filtro ativo, o total acompanha o recorte -->
            <tr>
              <td class="col-nome">Total</td>
              @for (o of ocorrencias(); track o.codigo) { <td class="col-oc">{{ totalVisivel(o.codigo) }}</td> }
            </tr>
          </tfoot>
        </table>
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .barra {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
      justify-content: center; margin-bottom: 16px;
    }
    .rot { color: var(--muted); font-size: .9rem; }

    .sumario .col-oc { text-align: center; white-space: nowrap; }
    /* Só a coluna que TEM tradução promete a dica ao pousar o mouse */
    .sumario thead .col-oc.traduzida { cursor: help; }
    .sumario .col-nome { white-space: nowrap; }
    .sumario tfoot td { font-weight: 600; background: var(--row-hover); }
  `],
})
export class SumarioOcorrenciasComponent implements OnInit {
  private api = inject(ApiService);

  /** MESES é 1-based (índice 0 = ''); aqui itera a lista 0-based. */
  readonly meses = MESES.slice(1);
  private readonly hoje = new Date();
  /** O sumário navega até o acervo histórico — piso próprio, anterior à implantação do módulo. */
  readonly anos = anosNavegaveis(this.hoje, ANO_MINIMO_SUMARIO);

  // Período default: todo o acervo, do primeiro mês navegável ao mês corrente (não há folha futura).
  deMes = signal(1);
  deAno = signal(ANO_MINIMO_SUMARIO);
  ateMes = signal(this.hoje.getMonth() + 1);
  ateAno = signal(this.hoje.getFullYear());

  sumario = signal<SumarioData | null>(null);
  carregando = signal(false);
  erro = signal('');

  ocorrencias = computed(() => this.sumario()?.ocorrencias ?? []);
  funcionarios = computed(() => this.sumario()?.funcionarios ?? []);

  /** Coluna Funcionário: a única com filtro de valores — nas demais o painel só classifica. */
  protected readonly colFuncionario: ColumnFilterDef =
    { key: 'nome', label: 'Funcionário', type: 'text', sortable: true };
  /** Defs das colunas de ocorrência, uma por código — recriar o objeto a cada render reiniciaria o painel. */
  private readonly defsOcorrencia = new Map<string, ColumnFilterDef>();

  sortKey = signal('');
  sortDir = signal('');
  /** Nomes marcados no filtro da coluna Funcionário; null = todos. */
  nomesSelecionados = signal<string[] | null>(null);

  /** Linhas exibidas: as do período, recortadas pelo filtro de nomes e na ordem pedida. */
  linhas = computed(() => {
    const selecionados = this.nomesSelecionados();
    let out = this.funcionarios();
    if (selecionados !== null) {
      const nomes = new Set(selecionados);
      out = out.filter(f => nomes.has(f.nome));
    }
    const key = this.sortKey();
    const dir = this.sortDir();
    if (!key || !dir) return out;
    const mul = dir === 'desc' ? -1 : 1;
    out = [...out];
    if (key === 'nome') {
      out.sort((a, b) => mul * a.nome.localeCompare(b.nome));
    } else if (key.startsWith('oc:')) {
      const codigo = key.slice(3);
      out.sort((a, b) => mul * ((a.contagens?.[codigo] ?? 0) - (b.contagens?.[codigo] ?? 0)));
    }
    return out;
  });

  /** Valores do filtro da coluna Funcionário: os nomes do período, na ordem em que já vêm. */
  nomesDistintos = computed(() => this.funcionarios().map(f => ({ value: f.nome, label: f.nome })));

  /** Totais do rodapé, somados das linhas exibidas — filtrar muda o total junto. */
  private totaisVisiveis = computed(() => {
    const totais = new Map<string, number>();
    for (const f of this.linhas()) {
      for (const [codigo, n] of Object.entries(f.contagens ?? {})) {
        totais.set(codigo, (totais.get(codigo) ?? 0) + n);
      }
    }
    return totais;
  });

  /** Só a resposta da consulta mais nova vale: trocar de período rápido põe duas em voo. */
  private seq = 0;
  /** A consulta em voo, para abortá-la quando o período muda — ela é a mais cara do módulo. */
  private emVoo?: Subscription;

  ngOnInit(): void {
    this.carregar();
  }

  /** Tradução oficial do código, ou vazio — sem ela o cabeçalho fica sem tooltip. */
  traducao(codigo: string): string {
    return TRADUCAO_POR_CODIGO.get(codigo.toLowerCase()) ?? '';
  }

  /** Dias daquele código para o funcionário; o backend omite as células zeradas. */
  contagem(f: FuncionarioSumario, codigo: string): string {
    const n = f.contagens?.[codigo];
    return n ? String(n) : '';
  }

  /** Def da coluna daquele código, sempre a mesma instância. */
  colOcorrencia(codigo: string): ColumnFilterDef {
    let def = this.defsOcorrencia.get(codigo);
    if (!def) {
      def = { key: 'oc:' + codigo, label: codigo, type: 'text', sortable: true };
      this.defsOcorrencia.set(codigo, def);
    }
    return def;
  }

  onSort(ev: { sort: string; direction: string }): void {
    this.sortKey.set(ev.sort);
    this.sortDir.set(ev.direction);
  }

  onFiltroNomes(ev: { key: string; state: ColumnFilterState | null }): void {
    this.nomesSelecionados.set(ev.state?.values ?? null);
  }

  /** Total da coluna nas linhas exibidas. */
  totalVisivel(codigo: string): number {
    return this.totaisVisiveis().get(codigo) ?? 0;
  }

  onMes(campo: 'de' | 'ate', ev: Event): void {
    const mes = Number((ev.target as HTMLSelectElement).value);
    if (!Number.isInteger(mes) || mes < 1 || mes > 12) return;
    if (campo === 'de') this.deMes.set(mes); else this.ateMes.set(mes);
    this.carregar();
  }

  onAno(campo: 'de' | 'ate', ev: Event): void {
    const ano = Number((ev.target as HTMLSelectElement).value);
    if (!this.anos.includes(ano)) return;
    if (campo === 'de') this.deAno.set(ano); else this.ateAno.set(ano);
    this.carregar();
  }

  /**
   * Consulta o período. O intervalo invertido nem chega ao servidor: a recusa dele seria correta,
   * mas o admin que só quis trocar o mês final veria uma caixa de erro no lugar da tabela.
   *
   * <p>A marca de recência é tomada ANTES de qualquer saída: a consulta que já está em voo tem de
   * ser invalidada mesmo quando o novo período nem chega a ser consultado — senão a resposta dela
   * escreveria por cima da caixa que explica o intervalo invertido.
   */
  private carregar(): void {
    const seq = ++this.seq;
    this.emVoo?.unsubscribe();   // aborta a consulta anterior: ela já não vale, e não é barata
    const de = competencia(this.deAno(), this.deMes());
    const ate = competencia(this.ateAno(), this.ateMes());
    if (ate < de) {
      this.sumario.set(null);
      this.carregando.set(false);
      this.erro.set('O mês final não pode ser anterior ao inicial.');
      return;
    }

    this.carregando.set(true);
    this.erro.set('');
    this.emVoo = this.api.get<{ ok: boolean; data: SumarioData }>(
      '/api/admin/ponto/ocorrencias/sumario', { de, ate }).subscribe({
      next: res => {
        if (seq !== this.seq) return;   // uma consulta mais nova está em voo
        this.sumario.set(res.data ?? null);
        this.carregando.set(false);
      },
      error: err => {
        if (seq !== this.seq) return;
        this.sumario.set(null);
        this.carregando.set(false);
        this.erro.set(httpErrorMsg(err, 'Erro ao carregar o sumário de ocorrências.'));
      },
    });
  }
}

/** Competência no formato do endpoint ("2026-07"), comparável como texto. */
function competencia(ano: number, mes: number): string {
  return `${ano}-${String(mes).padStart(2, '0')}`;
}

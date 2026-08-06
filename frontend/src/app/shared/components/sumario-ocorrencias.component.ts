import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { httpErrorMsg } from '../../core/helpers/http.helpers';
import { ClientPager } from '../../core/helpers/client-pager';
import { MESES } from '../../core/helpers/table.helpers';
import { anosNavegaveis } from './mes-ano-selector.component';
import { PaginationComponent } from './pagination.component';

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
  DISPOSI: 'À disposição (dispensa de ponto)',
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
 * <p>Feriado e ponto facultativo não entram (o backend os retira): valem para a equipe inteira nos
 * mesmos dias e só encheriam a tabela. A escolha de qual folha representa cada mês — definitiva,
 * prévia ou a última semanal — também é do backend; aqui é só apresentação.
 */
@Component({
  selector: 'app-sumario-ocorrencias',
  standalone: true,
  imports: [PaginationComponent],
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

      <button type="button" class="btn-outline btn-ano" (click)="anoInteiro()">Ano inteiro</button>
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
              <th class="col-nome">Funcionário</th>
              @for (o of ocorrencias(); track o.codigo) {
                <!-- attr.title (e não title): a property recebendo null viraria o texto "null" -->
                <th scope="col" class="col-oc" [class.traduzida]="!!traducao(o.codigo)"
                    [attr.title]="traducao(o.codigo) || null">{{ o.codigo }}</th>
              }
            </tr>
          </thead>
          <tbody>
            <!-- A pessoa é o par (id, tipo): os três cadastros não compartilham chave -->
            @for (f of pager.rows(); track f.pessoa_tipo + ':' + f.pessoa_id) {
              <tr>
                <td class="col-nome">{{ f.nome }}</td>
                @for (o of ocorrencias(); track o.codigo) {
                  <td class="col-oc">{{ contagem(f, o.codigo) }}</td>
                }
              </tr>
            }
          </tbody>
          <tfoot>
            <!-- Total do PERÍODO e de TODOS: a tabela é paginada, e a soma da página enganaria -->
            <tr>
              <td class="col-nome">Total (todos os funcionários)</td>
              @for (o of ocorrencias(); track o.codigo) { <td class="col-oc">{{ o.total }}</td> }
            </tr>
          </tfoot>
        </table>
      </div>
      <app-pagination [meta]="pager.meta()" (pageChange)="pager.onPage($event)" (limitChange)="pager.onLimit($event)" />
    }
  `,
  styles: [`
    :host { display: block; }
    .barra {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
      justify-content: center; margin-bottom: 16px;
    }
    .rot { color: var(--muted); font-size: .9rem; }
    .btn-ano { margin-left: 6px; }

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
  readonly anos = anosNavegaveis(this.hoje);

  // Período default: o ano corrente inteiro — o recorte que o admin mais pede.
  deMes = signal(1);
  deAno = signal(this.hoje.getFullYear());
  ateMes = signal(12);
  ateAno = signal(this.hoje.getFullYear());

  sumario = signal<SumarioData | null>(null);
  carregando = signal(false);
  erro = signal('');

  ocorrencias = computed(() => this.sumario()?.ocorrencias ?? []);
  funcionarios = computed(() => this.sumario()?.funcionarios ?? []);
  protected pager = new ClientPager(this.funcionarios);

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

  /** Atalho do filtro: janeiro a dezembro do ano já escolhido no início do intervalo. */
  anoInteiro(): void {
    this.deMes.set(1);
    this.ateMes.set(12);
    this.ateAno.set(this.deAno());
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
    this.pager.onPage(1);   // outro período, outra lista de funcionários
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

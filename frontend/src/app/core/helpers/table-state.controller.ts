import { DestroyRef, inject, signal } from '@angular/core';
import { ApiService } from '../services/api.service';
import { PaginationMeta, TableState } from '../models/user.model';
import { ColumnFilterState } from '../../shared/components/column-filter.component';
import { erroCargaMsg } from './http.helpers';
import { buildFilters } from './table.helpers';

export interface TableStateOptions {
  /** Endpoint da listagem; função para casos de endpoint dinâmico (ex.: agrupar por sala). */
  endpoint: string | (() => string);
  defaultSort: string;
  defaultDir: 'asc' | 'desc';
  limit?: number;
  /** Mensagem do canal de erro quando a resposta não traz uma (C7). */
  erroMsg?: string;
}

/**
 * Controlador reutilizável do padrão de listagem server-side: encapsula
 * state/filters/rows/meta/loading, load() via ApiService e os handlers de
 * sort, filtro de coluna, busca com debounce (400ms, limpo no destroy) e
 * paginação. O componente instancia 1 controlador por tabela e o template
 * continua usando column-filter/pagination, apontando para o controlador.
 *
 * Acordeões e transformações de linha ficam no componente: o controlador
 * cobre APENAS o ciclo state/load/sort/filter/search/page (as linhas expandem
 * mutando os objetos de rows() e re-setando o signal, como hoje).
 *
 * Canal de erro (C7): uma leitura que falha NÃO pode virar "lista vazia" — o
 * `erro` fica preenchido e o `meta` é limpo (senão o rodapé segue exibindo o
 * total da carga anterior). Cabe ao template consumir o canal e oferecer o
 * retry (`load()`), distinguindo o erro do vazio de verdade.
 *
 * Recência (C13b/F61): a resposta que chega por ÚLTIMO não é necessariamente a do
 * pedido mais recente — ver `load()`.
 *
 * ⚠️ Instanciar SOMENTE em contexto de injeção (field initializer ou construtor
 * do componente): o construtor usa inject(DestroyRef) para limpar o debounce.
 */
export class TableStateController<T = Record<string, unknown>> {
  state: TableState;
  filters: Record<string, ColumnFilterState> = {};
  rows = signal<T[]>([]);
  meta = signal<PaginationMeta | null>(null);
  loading = signal(true);
  /** Canal de erro da carga (C7): '' = sem erro. Limpo no início de toda carga (inclusive no retry). */
  erro = signal('');
  /** Texto da busca global (ngModel); lido no disparo do debounce, como nos componentes originais. */
  searchText = '';

  private searchDebounce: ReturnType<typeof setTimeout> | undefined;

  /**
   * Token de recência das cargas (F61): só a resposta do pedido MAIS RECENTE é aplicada. Sem ele,
   * quem responde por último escreve rows/meta/loading/erro — mesmo sendo o pedido mais antigo.
   * É o F43 (curado no C8 para o banco de horas) vivendo no motor de TODAS as listagens.
   */
  private seq = 0;

  constructor(private api: ApiService, private opts: TableStateOptions) {
    this.state = { page: 1, limit: opts.limit ?? 10, sort: opts.defaultSort, direction: opts.defaultDir, search: '' };
    inject(DestroyRef).onDestroy(() => clearTimeout(this.searchDebounce));
  }

  private endpoint(): string {
    return typeof this.opts.endpoint === 'function' ? this.opts.endpoint() : this.opts.endpoint;
  }

  /**
   * Carga da página corrente. Dois cliques de paginação, um sort seguido de um filtro ou duas
   * mutações que recarregam põem DUAS cargas em voo; a resposta obsoleta é descartada por
   * completo — não toca `rows`, `meta`, `loading` nem `erro`. Sem o guard, a tela inteira (linhas
   * E rodapé, que vêm da MESMA resposta) volta para o pedido abandonado — e, na corrida
   * sort→filtro, o chip do filtro fica ativo sobre linhas NÃO filtradas. O guard vale também no `error`:
   * sem ele, uma carga velha que FALHA sobrescreveria com erro (e `meta: null`, desde o C7) uma
   * carga nova bem-sucedida — a pior faceta do F61. Token (e não `switchMap`), como no C8: o
   * pedido antigo não é abortado, só ignorado.
   */
  load(): void {
    const seq = ++this.seq;
    this.loading.set(true);
    this.erro.set('');
    this.state.filters = buildFilters(this.filters);
    this.api.getList(this.endpoint(), this.state).subscribe({
      next: r => {
        if (seq !== this.seq) return;   // obsoleta: há uma carga mais nova em voo
        this.rows.set((r.data || []) as unknown as T[]);
        this.meta.set(r.meta || null);
        this.loading.set(false);
      },
      error: err => {
        if (seq !== this.seq) return;   // a falha de uma carga velha não apaga o que a nova trouxe
        this.rows.set([]);
        this.meta.set(null);   // o rodapé não pode exibir o total da carga anterior
        this.loading.set(false);
        this.erro.set(erroCargaMsg(err, this.opts.erroMsg ?? 'Não foi possível carregar a lista.'));
      },
    });
  }

  onSort(e: { sort: string; direction: string }): void {
    this.state.sort = e.sort;
    this.state.direction = e.direction;
    this.state.page = 1;
    this.load();
  }

  onFilter(e: { key: string; state: ColumnFilterState | null }): void {
    if (e.state) this.filters[e.key] = e.state;
    else delete this.filters[e.key];
    this.state.page = 1;
    this.load();
  }

  onSearch(): void {
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => {
      this.state.search = this.searchText;
      this.state.page = 1;
      this.load();
    }, 400);
  }

  onPage(p: number): void {
    this.state.page = p;
    this.load();
  }

  onLimit(l: number): void {
    this.state.limit = l;
    this.state.page = 1;
    this.load();
  }
}

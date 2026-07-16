import { Signal, computed, signal } from '@angular/core';
import { PaginationMeta } from '../models/user.model';

/**
 * Paginação client-side sobre uma lista já carregada inteira (Signal), no
 * mesmo modelo visual das listagens server-side: produz o PaginationMeta
 * consumido pelo <app-pagination> e o recorte (rows) da página corrente.
 * Para as tabelas pequenas que a API entrega completas (lotes de ponto,
 * minhas folhas), onde paginar no servidor não compensa.
 * A página corrente é clampada ao total de páginas — se a lista encolher
 * num reload, a exibição cai na última página válida.
 */
export class ClientPager<T> {
  private readonly pageRaw = signal(1);
  private readonly limit = signal(10);

  constructor(private readonly source: Signal<T[]>) {}

  private readonly pages = computed(() => Math.max(1, Math.ceil(this.source().length / this.limit())));
  private readonly page = computed(() => Math.min(this.pageRaw(), this.pages()));

  readonly meta = computed<PaginationMeta>(() => ({
    page: this.page(),
    limit: this.limit(),
    total: this.source().length,
    pages: this.pages(),
  }));

  readonly rows = computed<T[]>(() => {
    const ini = (this.page() - 1) * this.limit();
    return this.source().slice(ini, ini + this.limit());
  });

  onPage(p: number): void {
    this.pageRaw.set(Math.max(1, Math.min(p, this.pages())));
  }

  onLimit(l: number): void {
    this.limit.set(l);
    this.pageRaw.set(1);
  }
}

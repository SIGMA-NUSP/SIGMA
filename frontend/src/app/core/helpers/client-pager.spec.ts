import { signal } from '@angular/core';
import { ClientPager } from './client-pager';

/** Lista [1..n] para os cenários. */
function lista(n: number): number[] {
  return Array.from({ length: n }, (_, i) => i + 1);
}

describe('ClientPager', () => {
  it('produz meta e recorte da primeira página (limit default 10)', () => {
    const pager = new ClientPager(signal(lista(25)));
    expect(pager.meta()).toEqual({ page: 1, limit: 10, total: 25, pages: 3 });
    expect(pager.rows()).toEqual(lista(10));
  });

  it('onPage navega e a última página traz só o resto', () => {
    const pager = new ClientPager(signal(lista(25)));
    pager.onPage(3);
    expect(pager.meta().page).toBe(3);
    expect(pager.rows()).toEqual([21, 22, 23, 24, 25]);
  });

  it('onPage clampa ao intervalo válido (abaixo de 1 e acima do total)', () => {
    const pager = new ClientPager(signal(lista(25)));
    pager.onPage(99);
    expect(pager.meta().page).toBe(3);
    pager.onPage(-5);
    expect(pager.meta().page).toBe(1);
  });

  it('onLimit muda o tamanho da página e volta à página 1', () => {
    const pager = new ClientPager(signal(lista(25)));
    pager.onPage(3);
    pager.onLimit(20);
    expect(pager.meta()).toEqual({ page: 1, limit: 20, total: 25, pages: 2 });
    expect(pager.rows()).toEqual(lista(20));
  });

  it('lista que encolhe num reload clampa a página corrente à última válida', () => {
    const fonte = signal(lista(25));
    const pager = new ClientPager(fonte);
    pager.onPage(3);
    fonte.set(lista(12));
    expect(pager.meta()).toEqual({ page: 2, limit: 10, total: 12, pages: 2 });
    expect(pager.rows()).toEqual([11, 12]);
  });

  it('lista vazia produz total 0, pages 1 e recorte vazio', () => {
    const pager = new ClientPager(signal<number[]>([]));
    expect(pager.meta()).toEqual({ page: 1, limit: 10, total: 0, pages: 1 });
    expect(pager.rows()).toEqual([]);
  });
});

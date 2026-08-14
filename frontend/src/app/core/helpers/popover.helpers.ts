/** Onde o popover ancorado nasce, em coordenadas de viewport. */
export interface AncoraPopover {
  x: number;
  y: number;
  /** Não havia espaço abaixo: a lista cresce para cima a partir do topo do elemento. */
  acima: boolean;
}

/** Tamanho máximo da caixa — o mesmo do CSS, e o que permite o encaixe sem medir o DOM. */
const POPOVER_LARGURA = 260;
const POPOVER_ALTURA = 280;
const MARGEM_TELA = 8;

/**
 * Onde o popover cabe a partir do elemento clicado. As últimas linhas de uma tabela ficam no rodapé
 * da tela e as últimas colunas na borda direita: sem o encaixe, a lista abriria fora da janela. O
 * cálculo é aritmético (a caixa tem tamanho máximo conhecido) — medir o elemento renderizado
 * exigiria esperar o frame seguinte, e a lista já nasce no lugar certo.
 */
export function ancoraDoClique(ev: MouseEvent): AncoraPopover {
  const r = (ev.currentTarget as HTMLElement | null)?.getBoundingClientRect();
  const esquerda = r?.left ?? 0, base = r?.bottom ?? 0, topo = r?.top ?? 0;
  const limiteX = Math.max(MARGEM_TELA, window.innerWidth - POPOVER_LARGURA - MARGEM_TELA);
  const espacoAbaixo = window.innerHeight - base;
  const acima = espacoAbaixo < POPOVER_ALTURA && topo > espacoAbaixo;
  return {
    x: Math.round(Math.min(Math.max(esquerda, MARGEM_TELA), limiteX)),
    y: Math.round(acima ? topo : base),
    acima,
  };
}

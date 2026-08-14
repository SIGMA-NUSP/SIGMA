import { ancoraDoClique } from './popover.helpers';

/** Um clique cujo alvo ocupa o retângulo informado — é dele que a âncora sai. */
function clique(r: { left: number; top: number; bottom: number }): MouseEvent {
  return {
    currentTarget: { getBoundingClientRect: () => r },
  } as unknown as MouseEvent;
}

/** A janela do teste: as medidas são as que o encaixe consulta. */
function janela(largura: number, altura: number): void {
  Object.defineProperty(window, 'innerWidth', { value: largura, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: altura, configurable: true });
}

describe('ancoraDoClique', () => {
  it('com espaço de sobra, o popover nasce colado embaixo do elemento', () => {
    janela(1200, 900);

    expect(ancoraDoClique(clique({ left: 300, top: 200, bottom: 230 })))
      .toEqual({ x: 300, y: 230, acima: false });
  });

  it('sem espaço abaixo, nasce no topo do elemento e cresce para cima', () => {
    janela(1200, 700);

    // 690 de base deixa 10px até o rodapé — menos que a altura da caixa, e há espaço acima
    expect(ancoraDoClique(clique({ left: 300, top: 660, bottom: 690 })))
      .toEqual({ x: 300, y: 660, acima: true });
  });

  it('elemento no rodapé mas sem espaço acima também mantém o popover embaixo', () => {
    janela(1200, 300);

    // 120 acima e 180 abaixo: nenhum dos dois cabe, e abaixo é o lado mais folgado
    expect(ancoraDoClique(clique({ left: 10, top: 90, bottom: 120 })).acima).toBe(false);
  });

  it('perto da borda direita, o popover recua para caber na janela', () => {
    janela(1000, 900);

    // 1000 - 260 (largura) - 8 (margem) = 732 é o ponto mais à direita possível
    expect(ancoraDoClique(clique({ left: 980, top: 100, bottom: 130 })).x).toBe(732);
  });

  it('perto da borda esquerda, respeita a margem mínima', () => {
    janela(1000, 900);

    expect(ancoraDoClique(clique({ left: 2, top: 100, bottom: 130 })).x).toBe(8);
  });

  it('janela estreita demais para a caixa mantém o popover na margem', () => {
    janela(200, 900);

    expect(ancoraDoClique(clique({ left: 150, top: 100, bottom: 130 })).x).toBe(8);
  });

  it('clique sem elemento de origem ancora no canto, sem quebrar', () => {
    janela(1200, 900);

    expect(ancoraDoClique({ currentTarget: null } as unknown as MouseEvent))
      .toEqual({ x: 8, y: 0, acima: false });
  });
});

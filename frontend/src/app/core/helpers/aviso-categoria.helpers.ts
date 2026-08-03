/**
 * Apresentação das quatro categorias de comunicação que o backend envia em `categoria`: o rótulo
 * pt-BR, a classe de estilo (as cores moram em `styles.scss`) e o ícone do selo.
 *
 * Fonte única do popup, da tabela de comunicações e da página de detalhe — o mesmo aviso tem que
 * sair com o mesmo selo nas três telas.
 */
export type CodigoCategoria = 'AVISO' | 'COMUNICADO' | 'MENSAGEM' | 'NOTIFICACAO';

export interface CategoriaAviso {
  /** Rótulo exibido: abre o título do popup e nomeia o selo (a caixa alta é do CSS). */
  label: string;
  /** Classe global que carrega as cores da categoria (`styles.scss`). */
  classe: string;
  /** Traços do ícone do selo, desenhados numa grade 24×24 (mesmo padrão dos demais SVGs do projeto). */
  icone: string[];
}

const CATEGORIAS: Record<CodigoCategoria, CategoriaAviso> = {
  AVISO: {
    label: 'Aviso',
    classe: 'cat-aviso',
    icone: [   // triângulo de atenção
      'M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z',
      'M12 9v4',
      'M12 17h.01',
    ],
  },
  COMUNICADO: {
    label: 'Comunicado',
    classe: 'cat-comunicado',
    icone: [   // megafone
      'm3 11 18-5v12L3 14v-3z',
      'M11.6 16.8a3 3 0 1 1-5.8-1.6',
    ],
  },
  MENSAGEM: {
    label: 'Mensagem',
    classe: 'cat-mensagem',
    icone: [   // envelope
      'M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z',
      'M22 6 12 13 2 6',
    ],
  },
  NOTIFICACAO: {
    label: 'Notificação',
    classe: 'cat-notificacao',
    icone: [   // sino
      'M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9',
      'M13.73 21a2 2 0 0 1-3.46 0',
    ],
  },
};

/**
 * Apresentação de um código de categoria. Código desconhecido ou ausente cai em Aviso — a leitura
 * mais neutra e o visual histórico do popup —, para que nenhum aviso deixe de ser exibido.
 */
export function categoriaAviso(codigo: string | null | undefined): CategoriaAviso {
  return CATEGORIAS[codigo as CodigoCategoria] ?? CATEGORIAS.AVISO;
}

/**
 * Título de uma comunicação: a categoria, seguida do contexto quando ele existir
 * ("Comunicado — Agenda Legislativa"). Mensagem não tem contexto e sai só como "Mensagem".
 */
export function tituloComunicacao(codigo: string | null | undefined, contexto: string | null | undefined): string {
  const label = categoriaAviso(codigo).label;
  return contexto ? `${label} — ${contexto}` : label;
}

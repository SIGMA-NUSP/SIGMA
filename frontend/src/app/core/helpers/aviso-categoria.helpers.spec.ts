import { categoriaAviso, tituloComunicacao } from './aviso-categoria.helpers';

/**
 * Apresentação das categorias de comunicação: rótulo, classe de cor e ícone. As três telas que
 * exibem um aviso (popup, tabela e detalhe) leem daqui — divergir aqui é divergir em todas.
 */
describe('aviso-categoria.helpers', () => {
  describe('categoriaAviso', () => {
    it('cada categoria tem rótulo pt-BR, classe de cor própria e ícone', () => {
      const esperado = [
        { codigo: 'AVISO', label: 'Aviso', classe: 'cat-aviso' },
        { codigo: 'COMUNICADO', label: 'Comunicado', classe: 'cat-comunicado' },
        { codigo: 'MENSAGEM', label: 'Mensagem', classe: 'cat-mensagem' },
        { codigo: 'NOTIFICACAO', label: 'Notificação', classe: 'cat-notificacao' },
      ];
      for (const e of esperado) {
        const cat = categoriaAviso(e.codigo);
        expect(cat.label).toBe(e.label);
        expect(cat.classe).toBe(e.classe);
        expect(cat.icone.length).toBeGreaterThan(0);
      }
    });

    it('cada categoria tem um ícone distinto — o selo se distingue sem depender da cor', () => {
      const icones = ['AVISO', 'COMUNICADO', 'MENSAGEM', 'NOTIFICACAO']
        .map(c => categoriaAviso(c).icone.join('|'));
      expect(new Set(icones).size).toBe(4);
    });

    it('código ausente ou desconhecido cai em Aviso, para nenhuma comunicação sumir da tela', () => {
      for (const entrada of [null, undefined, '', 'INVENTADO']) {
        expect(categoriaAviso(entrada).label).toBe('Aviso');
        expect(categoriaAviso(entrada).classe).toBe('cat-aviso');
      }
    });
  });

  describe('tituloComunicacao', () => {
    it('com contexto: "categoria — contexto"', () => {
      expect(tituloComunicacao('AVISO', 'Verificação')).toBe('Aviso — Verificação');
      expect(tituloComunicacao('COMUNICADO', 'Agenda Legislativa')).toBe('Comunicado — Agenda Legislativa');
      expect(tituloComunicacao('NOTIFICACAO', 'Folha semanal disponível')).toBe('Notificação — Folha semanal disponível');
    });

    it('sem contexto: só a categoria, sem travessão solto', () => {
      for (const vazio of [null, undefined, '']) {
        expect(tituloComunicacao('MENSAGEM', vazio)).toBe('Mensagem');
      }
    });
  });
});

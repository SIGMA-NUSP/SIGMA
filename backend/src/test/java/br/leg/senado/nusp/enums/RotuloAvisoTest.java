package br.leg.senado.nusp.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapa exibido de cada aviso: categoria + contextos, derivados do par tipo+subtipo gravado.
 * É a fonte única de que popup, listagem e detalhe dependem — um valor trocado aqui muda o rótulo
 * e a cor em todas as telas de uma vez, e é por isso que o mapa inteiro está fixado em teste.
 */
class RotuloAvisoTest {

    private static void assertRotulo(RotuloAviso r, CategoriaAviso categoria,
                                     String contextoPopup, String contextoTabela) {
        assertEquals(categoria, r.categoria());
        assertEquals(contextoPopup, r.contextoPopup());
        assertEquals(contextoTabela, r.contextoTabela());
    }

    private static RotuloAviso doSubtipo(SubtipoAviso s) {
        return RotuloAviso.de(TipoAviso.PESSOAL, s);   // o tipo é irrelevante quando há subtipo
    }

    @Nested
    @DisplayName("subtipo → categoria e contextos")
    class PorSubtipo {

        @Test
        @DisplayName("os avisos de operação (Escala) são AVISO, com o contexto igual nas duas leituras")
        void escala() {
            assertRotulo(doSubtipo(SubtipoAviso.ESCALA), CategoriaAviso.AVISO, "Escala", "Escala");
        }

        @Test
        @DisplayName("Agenda e os grupos são COMUNICADO; GRUPO_TODOS lê 'Todos' no popup e 'Operadores e Técnicos' na tabela")
        void comunicados() {
            assertRotulo(doSubtipo(SubtipoAviso.AGENDA), CategoriaAviso.COMUNICADO, "Agenda Legislativa", "Agenda");
            assertRotulo(doSubtipo(SubtipoAviso.GRUPO_OPERADORES), CategoriaAviso.COMUNICADO, "Operadores", "Operadores");
            assertRotulo(doSubtipo(SubtipoAviso.GRUPO_TECNICOS), CategoriaAviso.COMUNICADO, "Técnicos", "Técnicos");
            assertRotulo(doSubtipo(SubtipoAviso.GRUPO_TODOS), CategoriaAviso.COMUNICADO, "Todos", "Operadores e Técnicos");
            assertRotulo(doSubtipo(SubtipoAviso.GRUPO_ADMINISTRADORES), CategoriaAviso.COMUNICADO, "Administradores", "Administradores");
        }

        @Test
        @DisplayName("o cadastro Pessoal é MENSAGEM e não tem contexto: a categoria já o identifica")
        void pessoal() {
            assertRotulo(doSubtipo(SubtipoAviso.PESSOAL), CategoriaAviso.MENSAGEM, "", "");
        }

        @Test
        @DisplayName("o que o sistema dispara sozinho (folha publicada e desfecho de folga) é NOTIFICACAO")
        void programaticos() {
            assertRotulo(doSubtipo(SubtipoAviso.FOLHA_SEMANAL), CategoriaAviso.NOTIFICACAO,
                    "Folha semanal disponível", "Folha Semanal");
            assertRotulo(doSubtipo(SubtipoAviso.FOLHA_MENSAL), CategoriaAviso.NOTIFICACAO,
                    "Folha mensal disponível", "Folha Mensal");
            assertRotulo(doSubtipo(SubtipoAviso.SOLICITACAO_APROVADA), CategoriaAviso.NOTIFICACAO,
                    "Solicitação aprovada", "Solicitação Banco");
            assertRotulo(doSubtipo(SubtipoAviso.SOLICITACAO_REJEITADA), CategoriaAviso.NOTIFICACAO,
                    "Solicitação rejeitada", "Solicitação Banco");
            // A folha com dia pela metade se anuncia por si na tabela do admin: é o que distingue as
            // duas linhas da mesma publicação sem precisar abrir cada uma.
            assertRotulo(doSubtipo(SubtipoAviso.FOLHA_REGISTRO_INCOMPLETO), CategoriaAviso.NOTIFICACAO,
                    "Folha disponível", "Registros incompletos");
        }
    }

    @Nested
    @DisplayName("sem subtipo → fallback no tipo (Verificação e todo o legado)")
    class PorTipo {

        @Test
        @DisplayName("Verificação é AVISO com contexto 'Verificação'")
        void verificacao() {
            assertRotulo(RotuloAviso.de(TipoAviso.VERIFICACAO, null), CategoriaAviso.AVISO,
                    "Verificação", "Verificação");
        }

        @Test
        @DisplayName("PESSOAL legado (gravado antes do subtipo) cai em MENSAGEM sem contexto — nunca quebra")
        void pessoalLegado() {
            assertRotulo(RotuloAviso.de(TipoAviso.PESSOAL, null), CategoriaAviso.MENSAGEM, "", "");
        }

        @Test
        @DisplayName("ESCALA/AGENDA/GERAL sem subtipo caem no rótulo do próprio tipo")
        void demaisTipos() {
            assertRotulo(RotuloAviso.de(TipoAviso.ESCALA, null), CategoriaAviso.AVISO, "Escala", "Escala");
            assertRotulo(RotuloAviso.de(TipoAviso.AGENDA, null), CategoriaAviso.COMUNICADO,
                    "Agenda Legislativa", "Agenda");
            assertRotulo(RotuloAviso.de(TipoAviso.GERAL, null), CategoriaAviso.COMUNICADO, "Geral", "Geral");
        }

        @Test
        @DisplayName("o subtipo manda quando existe, mesmo contradizendo o tipo")
        void subtipoTemPrecedencia() {
            assertRotulo(RotuloAviso.de(TipoAviso.PESSOAL, SubtipoAviso.FOLHA_SEMANAL),
                    CategoriaAviso.NOTIFICACAO, "Folha semanal disponível", "Folha Semanal");
        }

        @Test
        @DisplayName("tipo e subtipo nulos: MENSAGEM sem contexto, para a exibição nunca quebrar")
        void tudoNulo() {
            assertRotulo(RotuloAviso.de(null, null), CategoriaAviso.MENSAGEM, "", "");
        }
    }

    @Nested
    @DisplayName("contratos do mapa")
    class Contratos {

        @Test
        @DisplayName("nenhum rótulo tem vírgula: a listagem projeta estes textos numa lista de colunas separada por vírgula")
        void semVirgula() {
            for (SubtipoAviso s : SubtipoAviso.values()) semVirgula(s.getRotulo(), s.name());
            for (TipoAviso t : TipoAviso.values()) semVirgula(t.getRotulo(), t.name());
        }

        private void semVirgula(RotuloAviso r, String origem) {
            assertFalse(r.contextoPopup().contains(","), origem + " tem vírgula no contexto do popup");
            assertFalse(r.contextoTabela().contains(","), origem + " tem vírgula no contexto da tabela");
        }

        @Test
        @DisplayName("nenhum rótulo tem aspa simples: os textos entram como literal no SQL da listagem")
        void semAspaSimples() {
            for (SubtipoAviso s : SubtipoAviso.values()) {
                assertFalse(s.getRotulo().contextoPopup().contains("'"), s + " tem aspa no contexto do popup");
                assertFalse(s.getRotulo().contextoTabela().contains("'"), s + " tem aspa no contexto da tabela");
            }
            for (TipoAviso t : TipoAviso.values()) {
                assertFalse(t.getRotulo().contextoPopup().contains("'"), t + " tem aspa no contexto do popup");
                assertFalse(t.getRotulo().contextoTabela().contains("'"), t + " tem aspa no contexto da tabela");
            }
        }

        @Test
        @DisplayName("todo tipo e todo subtipo têm categoria e contextos preenchidos (contexto pode ser vazio, nunca nulo)")
        void mapaCompleto() {
            for (SubtipoAviso s : SubtipoAviso.values()) completo(s.getRotulo(), s.name());
            for (TipoAviso t : TipoAviso.values()) completo(t.getRotulo(), t.name());
        }

        private void completo(RotuloAviso r, String origem) {
            assertNotNull(r.categoria(), origem + " sem categoria");
            assertNotNull(r.contextoPopup(), origem + " com contexto de popup nulo");
            assertNotNull(r.contextoTabela(), origem + " com contexto de tabela nulo");
        }

        @Test
        @DisplayName("toda categoria tem label pt-BR para o selo e o título do popup")
        void categoriasComLabel() {
            assertEquals("Aviso", CategoriaAviso.AVISO.getLabel());
            assertEquals("Comunicado", CategoriaAviso.COMUNICADO.getLabel());
            assertEquals("Mensagem", CategoriaAviso.MENSAGEM.getLabel());
            assertEquals("Notificação", CategoriaAviso.NOTIFICACAO.getLabel());
            for (CategoriaAviso c : CategoriaAviso.values())
                assertTrue(c.getLabel() != null && !c.getLabel().isBlank(), c + " sem label");
        }
    }
}

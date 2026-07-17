package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarcacaoServiceTest {

    @Mock private PontoDiaMarcacaoRepository diaRepo;
    @Mock private PontoPessoaMarcacaoRepository pessoaRepo;
    @Mock private PessoaCadastroLookup pessoaCadastro;

    @InjectMocks
    private MarcacaoService service;

    private static final String ADMIN = "adm-1";
    private static final LocalDate INI_JUL = LocalDate.of(2026, 7, 1);
    private static final LocalDate INI_AGO = LocalDate.of(2026, 8, 1);

    /** A pessoa do par existe no cadastro DAQUELE tipo — pré-condição dos ramos pessoais que gravam/removem. */
    private void pessoaExiste(String pessoaId, String pessoaTipo) {
        when(pessoaCadastro.existe(pessoaId, pessoaTipo)).thenReturn(true);
    }

    private static PontoDiaMarcacao global(LocalDate data, TipoDiaMarcacao tipo) {
        PontoDiaMarcacao m = new PontoDiaMarcacao();
        m.setData(data);
        m.setTipo(tipo);
        return m;
    }

    private static PontoPessoaMarcacao pessoal(String pessoaId, String pessoaTipo,
                                               LocalDate data, TipoPessoaMarcacao tipo) {
        PontoPessoaMarcacao m = new PontoPessoaMarcacao();
        m.setPessoaId(pessoaId);
        m.setPessoaTipo(pessoaTipo);
        m.setData(data);
        m.setTipo(tipo);
        return m;
    }

    @Test
    @DisplayName("listar usa range sargável [1º dia, 1º do mês seguinte) e devolve data ISO + tipo")
    void listarRange() {
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(global(LocalDate.of(2026, 7, 9), TipoDiaMarcacao.FERIADO)));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(any(), any())).thenReturn(List.of());

        Map<String, Object> out = service.listar(2026, 7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> globais = (List<Map<String, Object>>) out.get("globais");
        assertEquals(1, globais.size());
        assertEquals("2026-07-09", globais.get(0).get("data"));
        assertEquals("FERIADO", globais.get(0).get("tipo"));
        assertTrue(((List<?>) out.get("pessoais")).isEmpty());
    }

    @Test
    @DisplayName("listar devolve as marcações pessoais do mês (pessoa, tipo, data ISO), no mesmo range das globais")
    void listarPessoais() {
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(global(LocalDate.of(2026, 7, 24), TipoDiaMarcacao.PONTO_FACULTATIVO)));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(
                        pessoal("op-1", "OPERADOR", LocalDate.of(2026, 7, 10), TipoPessoaMarcacao.FERIAS),
                        pessoal("tec-7", "TECNICO", LocalDate.of(2026, 7, 20), TipoPessoaMarcacao.A_DISPOSICAO)));

        Map<String, Object> out = service.listar(2026, 7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pessoais = (List<Map<String, Object>>) out.get("pessoais");
        assertEquals(2, pessoais.size());
        assertEquals("op-1", pessoais.get(0).get("pessoa_id"));
        assertEquals("OPERADOR", pessoais.get(0).get("pessoa_tipo"));
        assertEquals("2026-07-10", pessoais.get(0).get("data"));
        assertEquals("FERIAS", pessoais.get(0).get("tipo"));
        assertEquals("tec-7", pessoais.get(1).get("pessoa_id"));
        assertEquals("TECNICO", pessoais.get(1).get("pessoa_tipo"));
        assertEquals("A_DISPOSICAO", pessoais.get(1).get("tipo"));
        // as duas listas convivem no mesmo payload
        assertEquals(1, ((List<?>) out.get("globais")).size());
    }

    @Test
    @DisplayName("mês inválido → 400")
    void mesInvalido() {
        assertThrows(ServiceValidationException.class, () -> service.listar(2026, 13));
    }

    @Test
    @DisplayName("mês 0 e mês negativo → 400, mesmo contrato do 13 (nenhum repositório é consultado)")
    void mesZeroOuNegativo() {
        for (int mes : new int[]{0, -1}) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.listar(2026, mes));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().startsWith("Mês inválido"), ex.getMessage());
        }
        verifyNoInteractions(diaRepo, pessoaRepo);
    }

    @Test
    @DisplayName("ano fora de [2000, 2100] → 400 (o mês válido não salva o par)")
    void anoForaDoIntervalo() {
        for (int ano : new int[]{1999, 2101}) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.listar(ano, 7));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().startsWith("Ano inválido"), ex.getMessage());
        }
        verifyNoInteractions(diaRepo, pessoaRepo);
    }

    @Test
    @DisplayName("aplicarLote global: upsert (insert + update) e remoção por DATA")
    void globalUpsertERemove() {
        // 09/07 não existe → insert; 10/07 existe (FERIADO) → update p/ PONTO_FACULTATIVO
        when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
        PontoDiaMarcacao existente = global(LocalDate.of(2026, 7, 10), TipoDiaMarcacao.FERIADO);
        when(diaRepo.findByData(LocalDate.of(2026, 7, 10))).thenReturn(Optional.of(existente));

        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(
                        Map.of("data", "2026-07-09", "tipo", "FERIADO"),
                        Map.of("data", "2026-07-10", "tipo", "PONTO_FACULTATIVO")),
                "remover", List.of("2026-07-11")));

        service.aplicarLote(body, ADMIN);

        verify(diaRepo).save(argThat(m -> LocalDate.of(2026, 7, 9).equals(m.getData())
                && m.getTipo() == TipoDiaMarcacao.FERIADO && ADMIN.equals(m.getCriadoPorId())));
        verify(diaRepo).save(argThat(m -> m == existente
                && m.getTipo() == TipoDiaMarcacao.PONTO_FACULTATIVO));   // update in-place
        verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
    }

    @Test
    @DisplayName("aplicarLote pessoal: pessoa_tipo inválido → 400")
    void pessoaTipoInvalido() {
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "XPTO", "aplicar", List.of()));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(pessoaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: tipo de marcação inválido → 400 (não 500)")
    void tipoInvalido() {
        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "XPTO"))));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote pessoal válido: upsert por (pessoa, tipo, dia), com caixa e espaços normalizados")
    void pessoalUpsert() {
        pessoaExiste("op-1", "OPERADOR");
        when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 9)))
                .thenReturn(Optional.empty());
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "  operador  ",   // minúsculo e com espaços (normaliza)
                "aplicar", List.of(Map.of("data", " 2026-07-09 ", "tipo", " ATESTADO "))));

        service.aplicarLote(body, ADMIN);

        verify(pessoaRepo).save(argThat(m -> "op-1".equals(m.getPessoaId())
                && "OPERADOR".equals(m.getPessoaTipo())
                && LocalDate.of(2026, 7, 9).equals(m.getData())
                && m.getTipo() == TipoPessoaMarcacao.ATESTADO
                && ADMIN.equals(m.getCriadoPorId())));
    }

    @Test
    @DisplayName("aplicarLote pessoal: remoção por (pessoa, tipo, dia) — pessoa_tipo normalizado, nada é salvo")
    void pessoalRemove() {
        pessoaExiste("tec-7", "TECNICO");
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "tec-7", "pessoa_tipo", "tecnico",   // minúsculo também na remoção
                "remover", List.of("2026-07-09", "2026-07-10")));

        service.aplicarLote(body, ADMIN);

        verify(pessoaRepo).deleteByPessoaIdAndPessoaTipoAndData("tec-7", "TECNICO", LocalDate.of(2026, 7, 9));
        verify(pessoaRepo).deleteByPessoaIdAndPessoaTipoAndData("tec-7", "TECNICO", LocalDate.of(2026, 7, 10));
        verify(pessoaRepo, never()).save(any());
        verifyNoInteractions(diaRepo);
    }

    @Test
    @DisplayName("aplicarLote com globais E pessoais no mesmo body: os quatro ramos rodam (o 1º bloco não encerra o lote)")
    void globaisEPessoaisNoMesmoLote() {
        pessoaExiste("op-1", "OPERADOR");
        PontoDiaMarcacao gExistente = global(LocalDate.of(2026, 7, 9), TipoDiaMarcacao.FERIADO);
        when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.of(gExistente));
        PontoPessoaMarcacao pExistente =
                pessoal("op-1", "OPERADOR", LocalDate.of(2026, 7, 14), TipoPessoaMarcacao.FERIAS);
        when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 14)))
                .thenReturn(Optional.of(pExistente));

        Map<String, Object> body = Map.of(
                "globais", Map.of(
                        "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "PONTO_FACULTATIVO")),
                        "remover", List.of("2026-07-11")),
                "pessoais", Map.of(
                        "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                        "aplicar", List.of(Map.of("data", "2026-07-14", "tipo", "LICENCA_MEDICA")),
                        "remover", List.of("2026-07-15")));

        service.aplicarLote(body, ADMIN);

        verify(diaRepo).save(argThat(m -> m == gExistente
                && m.getTipo() == TipoDiaMarcacao.PONTO_FACULTATIVO && ADMIN.equals(m.getCriadoPorId())));
        verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
        verify(pessoaRepo).save(argThat(m -> m == pExistente
                && m.getTipo() == TipoPessoaMarcacao.LICENCA_MEDICA && ADMIN.equals(m.getCriadoPorId())));
        verify(pessoaRepo).deleteByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("body com shape errado (globais como lista) → 400, não 500")
    void shapeInvalido() {
        Map<String, Object> body = Map.of("globais", List.of("x"));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("item de aplicar não-objeto (string) → 400, não 500")
    void itemInvalido() {
        Map<String, Object> body = Map.of("globais", Map.of("aplicar", List.of("2026-07-09")));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: data mal formatada (dd-MM-aaaa) no item → 400, não 500")
    void dataMalFormatadaEmAplicar() {
        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "09-07-2026", "tipo", "FERIADO"))));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("AAAA-MM-DD"), ex.getMessage());
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: item sem data → 400 ('Data obrigatória'), não 500")
    void dataAusenteEmAplicar() {
        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("tipo", "FERIADO"))));   // sem a chave "data"

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("Data obrigatória.", ex.getMessage());
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: data inexistente no calendário (2026-02-30) na remoção → 400 e nada é removido")
    void dataMalFormatadaEmRemover() {
        pessoaExiste("op-1", "OPERADOR");
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                "remover", List.of("2026-02-30")));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("AAAA-MM-DD"), ex.getMessage());
        verify(pessoaRepo, never()).deleteByPessoaIdAndPessoaTipoAndData(any(), any(), any());
        verify(pessoaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote pessoal: pessoa_id ausente ou em branco → 400 (a outra metade da guarda)")
    void pessoaIdEmBranco() {
        Map<String, Object> semId = Map.of("pessoais", Map.of(
                "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "FERIAS"))));
        Map<String, Object> idEmBranco = Map.of("pessoais", Map.of(
                "pessoa_id", "   ", "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "FERIAS"))));

        for (Map<String, Object> body : List.of(semId, idEmBranco)) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
        verify(pessoaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: tipo ausente ou em branco → 400 'Tipo … obrigatório.' (ramo distinto do tipo inválido)")
    void tipoAusenteOuEmBranco() {
        Map<String, Object> globalSemTipo = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "2026-07-09"))));                       // sem "tipo"
        Map<String, Object> globalTipoVazio = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "  "))));         // em branco

        for (Map<String, Object> body : List.of(globalSemTipo, globalTipoVazio)) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Tipo de marcação global obrigatório.", ex.getMessage());
        }

        pessoaExiste("op-1", "OPERADOR");
        Map<String, Object> pessoalSemTipo = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09"))));
        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(pessoalSemTipo, ADMIN));
        assertEquals("Tipo de marcação pessoal obrigatório.", ex.getMessage());

        verify(diaRepo, never()).save(any());
        verify(pessoaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote pessoal: tipo de marcação inválido → 400, não 500 (o ramo pessoal também é guardado)")
    void tipoPessoalInvalido() {
        pessoaExiste("op-1", "OPERADOR");
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "XPTO"))));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("XPTO"), ex.getMessage());
        verify(pessoaRepo, never()).save(any());
    }

    @Test
    @DisplayName("aplicarLote: 'aplicar' como string (não-lista) → 400, não 500")
    void aplicarNaoEhLista() {
        Map<String, Object> body = Map.of("globais", Map.of("aplicar", "2026-07-09"));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("deve ser uma lista"), ex.getMessage());
        verify(diaRepo, never()).save(any());
    }

    @Test
    @DisplayName("listar em dezembro e em janeiro: os meses-borda são válidos e o range vira o ano corretamente")
    void listarBordasDoAno() {
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.of(global(LocalDate.of(2026, 12, 25), TipoDiaMarcacao.FERIADO)));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.of(pessoal("op-1", "OPERADOR", LocalDate.of(2026, 12, 28),
                        TipoPessoaMarcacao.RECESSO)));
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)))
                .thenReturn(List.of(global(LocalDate.of(2026, 1, 1), TipoDiaMarcacao.FERIADO)));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)))
                .thenReturn(List.of());

        Map<String, Object> dez = service.listar(2026, 12);   // fim = 2027-01-01 (vira o ano)
        Map<String, Object> jan = service.listar(2026, 1);

        assertEquals(1, ((List<?>) dez.get("globais")).size());
        assertEquals(1, ((List<?>) dez.get("pessoais")).size());
        assertEquals(1, ((List<?>) jan.get("globais")).size());
    }

    @Test
    @DisplayName("aplicarLote com body nulo: não faz nada e não estoura")
    void bodyNulo() {
        assertDoesNotThrow(() -> service.aplicarLote(null, ADMIN));
        verifyNoInteractions(diaRepo, pessoaRepo);
    }

    // ══════════════════════════════════════════════════════════════
    // A marcação pessoal exige que a pessoa EXISTA
    // ══════════════════════════════════════════════════════════════

    /**
     * PNT_PESSOA_MARCACAO é polimórfica e não tem FK (changelog 038): sem a guarda, o par (id, tipo)
     * era gravado sem que ninguém conferisse o cadastro. A linha órfã resultante não aparecia na
     * grade/XLSX nem nos dias bloqueados do banco de horas — todos cruzam pelo par REAL — e o modal
     * não a removia: o admin marcava "Férias" e nada acontecia, sem erro nenhum.
     *
     * <p>A guarda fica no TOPO do ramo pessoal, e por isso cobre os dois lados do lote (aplicar e
     * remover). O par TROCADO é o caso que separa esta correção de um {@code existsById} qualquer: o
     * id existe — no cadastro do OUTRO tipo.
     */
    @Nested
    @DisplayName("pessoa inexistente (ou par trocado) não grava marcação órfã")
    class PessoaDaMarcacao {

        @Test
        @DisplayName("pessoa inexistente no APLICAR → 400 e nada gravado")
        void pessoaInexistenteNoAplicar() {
            when(pessoaCadastro.existe("fantasma", "OPERADOR")).thenReturn(false);
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "fantasma", "pessoa_tipo", "OPERADOR",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "FERIAS"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Funcionário não encontrado (pessoa_id / pessoa_tipo).", ex.getMessage());
            verify(pessoaRepo, never()).save(any());
            verify(pessoaRepo, never()).findByPessoaIdAndPessoaTipoAndData(any(), any(), any());
        }

        @Test
        @DisplayName("pessoa inexistente no REMOVER → 400 e nada removido (a guarda cobre os dois ramos)")
        void pessoaInexistenteNoRemover() {
            when(pessoaCadastro.existe("fantasma", "TECNICO")).thenReturn(false);
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "fantasma", "pessoa_tipo", "TECNICO",
                    "remover", List.of("2026-07-09")));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verify(pessoaRepo, never()).deleteByPessoaIdAndPessoaTipoAndData(any(), any(), any());
            verify(pessoaRepo, never()).save(any());
        }

        /**
         * O teste que um {@code existsById} genérico não passaria: o id É de alguém — de um OPERADOR —,
         * mas o corpo diz TECNICO. A gravação usaria o par (op-1, TECNICO), que nenhuma leitura do
         * módulo procura. É por isso que a checagem é feita no cadastro DAQUELE tipo.
         */
        @Test
        @DisplayName("par trocado (id de OPERADOR com pessoa_tipo TECNICO) → 400 e nada gravado")
        void parTrocado() {
            when(pessoaCadastro.existe("op-1", "TECNICO")).thenReturn(false);   // existe como OPERADOR, não como TECNICO
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "TECNICO",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "FERIAS"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Funcionário não encontrado (pessoa_id / pessoa_tipo).", ex.getMessage());
            verify(pessoaRepo, never()).save(any());
        }

        @Test
        @DisplayName("o par é conferido no cadastro DO TIPO informado, com o tipo já normalizado")
        void consultaOCadastroDoTipoInformado() {
            pessoaExiste("adm-9", "ADMINISTRADOR");
            when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("adm-9", "ADMINISTRADOR", LocalDate.of(2026, 7, 9)))
                    .thenReturn(Optional.empty());
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "adm-9", "pessoa_tipo", " administrador ",   // normalizado antes da consulta
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "RECESSO"))));

            service.aplicarLote(body, ADMIN);

            verify(pessoaCadastro).existe("adm-9", "ADMINISTRADOR");
            verify(pessoaRepo).save(argThat(m -> "adm-9".equals(m.getPessoaId())
                    && "ADMINISTRADOR".equals(m.getPessoaTipo())
                    && m.getTipo() == TipoPessoaMarcacao.RECESSO));
        }

        /** O ramo GLOBAL não tem pessoa: a guarda nova não pode pedir cadastro nenhum para um feriado. */
        @Test
        @DisplayName("ramo global (feriado) segue sem consultar cadastro de pessoa")
        void ramoGlobalIntocado() {
            when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo", "FERIADO")),
                    "remover", List.of("2026-07-11")));

            service.aplicarLote(body, ADMIN);

            verify(diaRepo).save(any());
            verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
            verifyNoInteractions(pessoaCadastro, pessoaRepo);
        }
    }
}

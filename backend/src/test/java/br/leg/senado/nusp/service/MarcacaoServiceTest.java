package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Mock private PontoTipoMarcacaoRepository tipoRepo;
    @Mock private PessoaCadastroLookup pessoaCadastro;

    @InjectMocks
    private MarcacaoService service;

    private static final String ADMIN = "adm-1";
    private static final LocalDate INI_JUL = LocalDate.of(2026, 7, 1);
    private static final LocalDate INI_AGO = LocalDate.of(2026, 8, 1);

    // Catálogo de tipos usado pelas fixtures: o tipo da marcação é uma LINHA, e o
    // escopo dela decide se marca o dia de todos (GLOBAL) ou um funcionário (INDIVIDUAL).
    private static final PontoTipoMarcacao FERIADO = tipoGlobal("tipo-feriado", "Feriado", "FER");
    private static final PontoTipoMarcacao FACULTATIVO = tipoGlobal("tipo-facultativo", "Ponto facultativo", "PF");
    private static final PontoTipoMarcacao FERIAS = tipoIndividual("tipo-ferias", "Férias", "FRS");
    private static final PontoTipoMarcacao A_DISPOSICAO = tipoIndividual("tipo-disposicao", "À disposição", "ADP");
    private static final PontoTipoMarcacao ATESTADO = tipoIndividual("tipo-atestado", "Atestado", "ATE");
    private static final PontoTipoMarcacao LICENCA = tipoIndividual("tipo-licenca", "Licença médica", "LM");
    private static final PontoTipoMarcacao RECESSO = tipoIndividual("tipo-recesso", "Recesso", "REC");

    // O tipo que o funcionário declara na própria folha ao retificar o dia: está no mesmo catálogo,
    // mas não é do administrador. O de escopo GLOBAL prova que a recusa não vem do escopo.
    private static final PontoTipoMarcacao BANCO_DE_HORAS =
            doFuncionario(tipoIndividual("tipo-banco", "Banco de horas", "Ban"));
    private static final PontoTipoMarcacao COMPENSACAO =
            doFuncionario(tipoGlobal("tipo-compensacao", "Compensação", "Cmp"));

    /** A pessoa do par existe no cadastro DAQUELE tipo — pré-condição dos ramos pessoais que gravam/removem. */
    private void pessoaExiste(String pessoaId, String pessoaTipo) {
        when(pessoaCadastro.existe(pessoaId, pessoaTipo)).thenReturn(true);
    }

    private static PontoTipoMarcacao tipo(String id, String nome, String badge, String escopo) {
        PontoTipoMarcacao t = new PontoTipoMarcacao();
        t.setId(id);
        t.setNome(nome);
        t.setBadge(badge);
        t.setEscopo(escopo);
        return t;
    }

    private static PontoTipoMarcacao tipoGlobal(String id, String nome, String badge) {
        return tipo(id, nome, badge, PontoTipoMarcacao.ESCOPO_GLOBAL);
    }

    private static PontoTipoMarcacao tipoIndividual(String id, String nome, String badge) {
        return tipo(id, nome, badge, PontoTipoMarcacao.ESCOPO_INDIVIDUAL);
    }

    /** Marca o tipo como o que o funcionário escolhe ao retificar o próprio dia. */
    private static PontoTipoMarcacao doFuncionario(PontoTipoMarcacao t) {
        t.setVisivelFuncionario(true);
        return t;
    }

    private static PontoDiaMarcacao global(LocalDate data, String tipoId) {
        PontoDiaMarcacao m = new PontoDiaMarcacao();
        m.setData(data);
        m.setTipoId(tipoId);
        return m;
    }

    private static PontoPessoaMarcacao pessoal(String pessoaId, String pessoaTipo,
                                               LocalDate data, String tipoId) {
        PontoPessoaMarcacao m = new PontoPessoaMarcacao();
        m.setPessoaId(pessoaId);
        m.setPessoaTipo(pessoaTipo);
        m.setData(data);
        m.setTipoId(tipoId);
        return m;
    }

    @Test
    @DisplayName("listar usa range sargável [1º dia, 1º do mês seguinte) e devolve data ISO + tipo do catálogo")
    void listarRange() {
        when(tipoRepo.findAll()).thenReturn(List.of(FERIADO));
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(global(LocalDate.of(2026, 7, 9), FERIADO.getId())));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(any(), any())).thenReturn(List.of());

        Map<String, Object> out = service.listar(2026, 7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> globais = (List<Map<String, Object>>) out.get("globais");
        assertEquals(1, globais.size());
        assertEquals("2026-07-09", globais.get(0).get("data"));
        // a tela recebe o id (para reenviar no lote) e o texto pronto (nome + badge), sem mapa de rótulos
        assertEquals("tipo-feriado", globais.get(0).get("tipo_id"));
        assertEquals("Feriado", globais.get(0).get("nome"));
        assertEquals("FER", globais.get(0).get("badge"));
        assertTrue(((List<?>) out.get("pessoais")).isEmpty());
    }

    @Test
    @DisplayName("listar devolve as marcações pessoais do mês (pessoa, tipo, data ISO), no mesmo range das globais")
    void listarPessoais() {
        when(tipoRepo.findAll()).thenReturn(List.of(FACULTATIVO, FERIAS, A_DISPOSICAO));
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(global(LocalDate.of(2026, 7, 24), FACULTATIVO.getId())));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(
                        pessoal("op-1", "OPERADOR", LocalDate.of(2026, 7, 10), FERIAS.getId()),
                        pessoal("tec-7", "TECNICO", LocalDate.of(2026, 7, 20), A_DISPOSICAO.getId())));

        Map<String, Object> out = service.listar(2026, 7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pessoais = (List<Map<String, Object>>) out.get("pessoais");
        assertEquals(2, pessoais.size());
        assertEquals("op-1", pessoais.get(0).get("pessoa_id"));
        assertEquals("OPERADOR", pessoais.get(0).get("pessoa_tipo"));
        assertEquals("2026-07-10", pessoais.get(0).get("data"));
        assertEquals("tipo-ferias", pessoais.get(0).get("tipo_id"));
        assertEquals("Férias", pessoais.get(0).get("nome"));
        assertEquals("FRS", pessoais.get(0).get("badge"));
        assertEquals("tec-7", pessoais.get(1).get("pessoa_id"));
        assertEquals("TECNICO", pessoais.get(1).get("pessoa_tipo"));
        assertEquals("tipo-disposicao", pessoais.get(1).get("tipo_id"));
        assertEquals("À disposição", pessoais.get(1).get("nome"));
        // as duas listas convivem no mesmo payload
        assertEquals(1, ((List<?>) out.get("globais")).size());
    }

    @Test
    @DisplayName("listar com tipo fora do catálogo: nome cai no próprio id e badge vem vazia (a tela nunca fica muda)")
    void listarTipoForaDoCatalogo() {
        when(tipoRepo.findAll()).thenReturn(List.of(FERIADO));   // catálogo sem os tipos referenciados abaixo
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(global(LocalDate.of(2026, 7, 9), "tipo-sumiu")));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(INI_JUL, INI_AGO))
                .thenReturn(List.of(pessoal("op-1", "OPERADOR", LocalDate.of(2026, 7, 10), "tipo-sumiu-tambem")));

        Map<String, Object> out = service.listar(2026, 7);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> globais = (List<Map<String, Object>>) out.get("globais");
        assertEquals("tipo-sumiu", globais.get(0).get("tipo_id"));
        assertEquals("tipo-sumiu", globais.get(0).get("nome"));
        assertEquals("", globais.get(0).get("badge"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pessoais = (List<Map<String, Object>>) out.get("pessoais");
        assertEquals("tipo-sumiu-tambem", pessoais.get(0).get("tipo_id"));
        assertEquals("tipo-sumiu-tambem", pessoais.get(0).get("nome"));
        assertEquals("", pessoais.get(0).get("badge"));
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
        verifyNoInteractions(diaRepo, pessoaRepo, tipoRepo);
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
        verifyNoInteractions(diaRepo, pessoaRepo, tipoRepo);
    }

    @Test
    @DisplayName("aplicarLote global: upsert (insert + update) e remoção por DATA")
    void globalUpsertERemove() {
        // 09/07 não existe → insert; 10/07 existe (Feriado) → update p/ Ponto facultativo
        when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
        PontoDiaMarcacao existente = global(LocalDate.of(2026, 7, 10), FERIADO.getId());
        when(diaRepo.findByData(LocalDate.of(2026, 7, 10))).thenReturn(Optional.of(existente));
        when(tipoRepo.findById(FERIADO.getId())).thenReturn(Optional.of(FERIADO));
        when(tipoRepo.findById(FACULTATIVO.getId())).thenReturn(Optional.of(FACULTATIVO));

        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(
                        Map.of("data", "2026-07-09", "tipo_id", "tipo-feriado"),
                        Map.of("data", "2026-07-10", "tipo_id", "tipo-facultativo")),
                "remover", List.of("2026-07-11")));

        service.aplicarLote(body, ADMIN);

        verify(diaRepo).saveAndFlush(argThat(m -> LocalDate.of(2026, 7, 9).equals(m.getData())
                && "tipo-feriado".equals(m.getTipoId()) && ADMIN.equals(m.getCriadoPorId())));
        verify(diaRepo).saveAndFlush(argThat(m -> m == existente
                && "tipo-facultativo".equals(m.getTipoId())));   // update in-place
        verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
    }

    @Test
    @DisplayName("aplicarLote pessoal: pessoa_tipo inválido → 400")
    void pessoaTipoInvalido() {
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "XPTO", "aplicar", List.of()));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(pessoaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote pessoal válido: upsert por (pessoa, tipo, dia), com caixa e espaços normalizados")
    void pessoalUpsert() {
        pessoaExiste("op-1", "OPERADOR");
        when(tipoRepo.findById(ATESTADO.getId())).thenReturn(Optional.of(ATESTADO));
        when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 9)))
                .thenReturn(Optional.empty());
        Map<String, Object> body = Map.of("pessoais", Map.of(
                "pessoa_id", "op-1", "pessoa_tipo", "  operador  ",   // minúsculo e com espaços (normaliza)
                "aplicar", List.of(Map.of("data", " 2026-07-09 ", "tipo_id", " tipo-atestado "))));

        service.aplicarLote(body, ADMIN);

        verify(pessoaRepo).saveAndFlush(argThat(m -> "op-1".equals(m.getPessoaId())
                && "OPERADOR".equals(m.getPessoaTipo())
                && LocalDate.of(2026, 7, 9).equals(m.getData())
                && "tipo-atestado".equals(m.getTipoId())
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
        verify(pessoaRepo, never()).saveAndFlush(any());
        // desmarcar não precisa de tipo: o catálogo nem é consultado
        verifyNoInteractions(diaRepo, tipoRepo);
    }

    @Test
    @DisplayName("aplicarLote com globais E pessoais no mesmo body: os quatro ramos rodam (o 1º bloco não encerra o lote)")
    void globaisEPessoaisNoMesmoLote() {
        pessoaExiste("op-1", "OPERADOR");
        PontoDiaMarcacao gExistente = global(LocalDate.of(2026, 7, 9), FERIADO.getId());
        when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.of(gExistente));
        PontoPessoaMarcacao pExistente =
                pessoal("op-1", "OPERADOR", LocalDate.of(2026, 7, 14), FERIAS.getId());
        when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 14)))
                .thenReturn(Optional.of(pExistente));
        when(tipoRepo.findById(FACULTATIVO.getId())).thenReturn(Optional.of(FACULTATIVO));
        when(tipoRepo.findById(LICENCA.getId())).thenReturn(Optional.of(LICENCA));

        Map<String, Object> body = Map.of(
                "globais", Map.of(
                        "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-facultativo")),
                        "remover", List.of("2026-07-11")),
                "pessoais", Map.of(
                        "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                        "aplicar", List.of(Map.of("data", "2026-07-14", "tipo_id", "tipo-licenca")),
                        "remover", List.of("2026-07-15")));

        service.aplicarLote(body, ADMIN);

        verify(diaRepo).saveAndFlush(argThat(m -> m == gExistente
                && "tipo-facultativo".equals(m.getTipoId()) && ADMIN.equals(m.getCriadoPorId())));
        verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
        verify(pessoaRepo).saveAndFlush(argThat(m -> m == pExistente
                && "tipo-licenca".equals(m.getTipoId()) && ADMIN.equals(m.getCriadoPorId())));
        verify(pessoaRepo).deleteByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("body com shape errado (globais como lista) → 400, não 500")
    void shapeInvalido() {
        Map<String, Object> body = Map.of("globais", List.of("x"));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(diaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("item de aplicar não-objeto (string) → 400, não 500")
    void itemInvalido() {
        Map<String, Object> body = Map.of("globais", Map.of("aplicar", List.of("2026-07-09")));
        assertThrows(ServiceValidationException.class, () -> service.aplicarLote(body, ADMIN));
        verify(diaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote: data mal formatada (dd-MM-aaaa) no item → 400, não 500")
    void dataMalFormatadaEmAplicar() {
        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "09-07-2026", "tipo_id", "tipo-feriado"))));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("AAAA-MM-DD"), ex.getMessage());
        verify(diaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote: item sem data → 400 ('Data obrigatória'), não 500")
    void dataAusenteEmAplicar() {
        Map<String, Object> body = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("tipo_id", "tipo-feriado"))));   // sem a chave "data"

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("Data obrigatória.", ex.getMessage());
        verify(diaRepo, never()).saveAndFlush(any());
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
        verify(pessoaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote pessoal: pessoa_id ausente ou em branco → 400 (a outra metade da guarda)")
    void pessoaIdEmBranco() {
        Map<String, Object> semId = Map.of("pessoais", Map.of(
                "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-ferias"))));
        Map<String, Object> idEmBranco = Map.of("pessoais", Map.of(
                "pessoa_id", "   ", "pessoa_tipo", "OPERADOR",
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-ferias"))));

        for (Map<String, Object> body : List.of(semId, idEmBranco)) {
            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
        verify(pessoaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote: tipo_id ausente ou em branco → 400 'Tipo … obrigatório.' (ramo distinto do tipo inexistente)")
    void tipoAusenteOuEmBranco() {
        Map<String, Object> globalSemTipo = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "2026-07-09"))));                          // sem "tipo_id"
        Map<String, Object> globalTipoVazio = Map.of("globais", Map.of(
                "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "  "))));         // em branco

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

        verify(tipoRepo, never()).findById(any());   // sem id, o catálogo nem é consultado
        verify(diaRepo, never()).saveAndFlush(any());
        verify(pessoaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aplicarLote: 'aplicar' como string (não-lista) → 400, não 500")
    void aplicarNaoEhLista() {
        Map<String, Object> body = Map.of("globais", Map.of("aplicar", "2026-07-09"));

        ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                () -> service.aplicarLote(body, ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("deve ser uma lista"), ex.getMessage());
        verify(diaRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("listar em dezembro e em janeiro: os meses-borda são válidos e o range vira o ano corretamente")
    void listarBordasDoAno() {
        when(tipoRepo.findAll()).thenReturn(List.of(FERIADO, RECESSO));
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.of(global(LocalDate.of(2026, 12, 25), FERIADO.getId())));
        when(pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.of(pessoal("op-1", "OPERADOR", LocalDate.of(2026, 12, 28), RECESSO.getId())));
        when(diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)))
                .thenReturn(List.of(global(LocalDate.of(2026, 1, 1), FERIADO.getId())));
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
        verifyNoInteractions(diaRepo, pessoaRepo, tipoRepo);
    }

    // ══════════════════════════════════════════════════════════════
    // O tipo vem do catálogo, e o escopo dele delimita onde pode ser usado
    // ══════════════════════════════════════════════════════════════

    /**
     * O tipo da marcação é uma linha do catálogo, escolhida por id: o lote traz o {@code tipo_id} e o
     * service confere a existência e o ESCOPO antes de gravar. Marcar um funcionário com um tipo de
     * todos (ou o dia de todos com um tipo individual) gravaria uma marcação que nenhuma leitura do
     * módulo mostraria de volta — some da grade e do modal, sem erro nenhum para o admin. Por isso a
     * validação é 400 com mensagem específica, e nada é salvo.
     *
     * <p>O mesmo catálogo guarda o tipo que o FUNCIONÁRIO declara ao retificar o próprio dia. Esse
     * não é do administrador: usá-lo como marcação faria a planilha contar como folga um dia que
     * ninguém declarou nem aprovou. A recusa é a mesma do escopo trocado — e independe do escopo.
     */
    @Nested
    @DisplayName("tipo do catálogo: id inexistente, escopo trocado e tipo do funcionário não gravam marcação")
    class TipoDoCatalogo {

        @Test
        @DisplayName("global com tipo_id inexistente → 400 com o id na mensagem e nada gravado")
        void tipoGlobalInexistente() {
            when(tipoRepo.findById("xpto")).thenReturn(Optional.empty());
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "xpto"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Tipo de ocorrência não encontrado: xpto", ex.getMessage());
            verify(diaRepo, never()).saveAndFlush(any());
            verify(diaRepo, never()).findByData(any());
        }

        @Test
        @DisplayName("pessoal com tipo_id inexistente → 400 e nada gravado (o ramo pessoal também é guardado)")
        void tipoPessoalInexistente() {
            pessoaExiste("op-1", "OPERADOR");
            when(tipoRepo.findById("xpto")).thenReturn(Optional.empty());
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "xpto"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Tipo de ocorrência não encontrado: xpto", ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("tipo INDIVIDUAL usado como marcação global → 400 e nada gravado")
        void tipoIndividualNaoMarcaODiaDeTodos() {
            when(tipoRepo.findById(FERIAS.getId())).thenReturn(Optional.of(FERIAS));
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-ferias"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("O tipo \"Férias\" não pode ser usado como marcação global.", ex.getMessage());
            verify(diaRepo, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("tipo GLOBAL usado como marcação pessoal → 400 e nada gravado")
        void tipoGlobalNaoMarcaFuncionario() {
            pessoaExiste("op-1", "OPERADOR");
            when(tipoRepo.findById(FERIADO.getId())).thenReturn(Optional.of(FERIADO));
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-feriado"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("O tipo \"Feriado\" não pode ser usado como marcação pessoal.", ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
            verify(pessoaRepo, never()).findByPessoaIdAndPessoaTipoAndData(any(), any(), any());
        }

        @Test
        @DisplayName("tipo do funcionário não vira marcação pessoal, mesmo sendo INDIVIDUAL")
        void tipoDoFuncionarioNaoMarcaFuncionario() {
            pessoaExiste("op-1", "OPERADOR");
            when(tipoRepo.findById(BANCO_DE_HORAS.getId())).thenReturn(Optional.of(BANCO_DE_HORAS));
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-banco"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            // o escopo CASA com o lado marcado: o que recusa é o tipo ser do funcionário
            assertEquals("O tipo \"Banco de horas\" não pode ser usado como marcação pessoal.", ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
            verify(pessoaRepo, never()).findByPessoaIdAndPessoaTipoAndData(any(), any(), any());
        }

        @Test
        @DisplayName("tipo do funcionário não vira marcação geral, mesmo sendo GLOBAL")
        void tipoDoFuncionarioNaoMarcaODiaDeTodos() {
            when(tipoRepo.findById(COMPENSACAO.getId())).thenReturn(Optional.of(COMPENSACAO));
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-compensacao"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("O tipo \"Compensação\" não pode ser usado como marcação global.", ex.getMessage());
            verify(diaRepo, never()).saveAndFlush(any());
            verify(diaRepo, never()).findByData(any());
        }

        @Test
        @DisplayName("tipo excluído entre a leitura e a gravação: a FK recusa e o admin lê o motivo, não um erro interno")
        void tipoExcluidoDuranteAMarcacao() {
            when(tipoRepo.findById(FERIADO.getId())).thenReturn(Optional.of(FERIADO));
            when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
            when(diaRepo.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("ORA-02291"));
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-feriado"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("O tipo \"Feriado\" não está mais disponível.", ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A marcação pessoal exige que a pessoa EXISTA
    // ══════════════════════════════════════════════════════════════

    /**
     * PNT_PESSOA_MARCACAO é polimórfica e não tem FK: sem a guarda, o par (id, tipo) era gravado sem
     * que ninguém conferisse o cadastro. A linha órfã resultante não aparecia na grade/XLSX nem nos
     * dias bloqueados do banco de horas — todos cruzam pelo par REAL — e o modal não a removia: o
     * admin marcava "Férias" e nada acontecia, sem erro nenhum.
     *
     * <p>A guarda fica no TOPO do ramo pessoal, e por isso cobre os dois lados do lote (aplicar e
     * remover). O par TROCADO é o caso que separa esta regra de um {@code existsById} qualquer: o
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
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-ferias"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Funcionário não encontrado (pessoa_id / pessoa_tipo).", ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
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
            verify(pessoaRepo, never()).saveAndFlush(any());
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
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-ferias"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("Funcionário não encontrado (pessoa_id / pessoa_tipo).", ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("o par é conferido no cadastro DO TIPO informado, com o tipo já normalizado")
        void consultaOCadastroDoTipoInformado() {
            pessoaExiste("adm-9", "ADMINISTRADOR");
            when(tipoRepo.findById(RECESSO.getId())).thenReturn(Optional.of(RECESSO));
            when(pessoaRepo.findByPessoaIdAndPessoaTipoAndData("adm-9", "ADMINISTRADOR", LocalDate.of(2026, 7, 9)))
                    .thenReturn(Optional.empty());
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "adm-9", "pessoa_tipo", " administrador ",   // normalizado antes da consulta
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-recesso"))));

            service.aplicarLote(body, ADMIN);

            verify(pessoaCadastro).existe("adm-9", "ADMINISTRADOR");
            verify(pessoaRepo).saveAndFlush(argThat(m -> "adm-9".equals(m.getPessoaId())
                    && "ADMINISTRADOR".equals(m.getPessoaTipo())
                    && "tipo-recesso".equals(m.getTipoId())));
        }

        /** O ramo GLOBAL não tem pessoa: a guarda não pode pedir cadastro nenhum para um feriado. */
        @Test
        @DisplayName("ramo global (feriado) segue sem consultar cadastro de pessoa")
        void ramoGlobalIntocado() {
            when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
            when(tipoRepo.findById(FERIADO.getId())).thenReturn(Optional.of(FERIADO));
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-feriado")),
                    "remover", List.of("2026-07-11")));

            service.aplicarLote(body, ADMIN);

            verify(diaRepo).saveAndFlush(any());
            verify(diaRepo).deleteByData(LocalDate.of(2026, 7, 11));
            verifyNoInteractions(pessoaCadastro, pessoaRepo);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // A ocorrência geral do dia prevalece sobre a individual
    // ══════════════════════════════════════════════════════════════

    /**
     * A marcação geral vale para todos os funcionários daquela data e é a que a grade mostra;
     * a individual do mesmo dia fica escondida sob ela. Marcar um funcionário num dia que já tem
     * geral gravaria, portanto, uma ocorrência invisível — o servidor recusa, e não é a tela que
     * garante isso (o bloqueio do clique é conveniência, não regra).
     *
     * <p>A REMOÇÃO continua valendo: é o caminho para limpar o que ficou escondido sob a geral.
     * E aplicar a geral não toca nas individuais — elas reaparecem quando a geral sai.
     */
    @Nested
    @DisplayName("dia com ocorrência geral não aceita marcação individual")
    class GeralPrevalece {

        @Test
        @DisplayName("aplicar pessoal em dia com geral → 400 orientando a recarregar, sem gravar e sem consultar o catálogo")
        void pessoalEmDiaComGeral() {
            pessoaExiste("op-1", "OPERADOR");
            when(diaRepo.findByData(LocalDate.of(2026, 7, 9)))
                    .thenReturn(Optional.of(global(LocalDate.of(2026, 7, 9), FERIADO.getId())));
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-atestado"))));

            ServiceValidationException ex = assertThrows(ServiceValidationException.class,
                    () -> service.aplicarLote(body, ADMIN));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("09/07/2026"), ex.getMessage());   // data como o admin lê
            assertTrue(ex.getMessage().contains("Recarregue"), ex.getMessage());
            verify(pessoaRepo, never()).saveAndFlush(any());
            verify(pessoaRepo, never()).findByPessoaIdAndPessoaTipoAndData(any(), any(), any());
            verify(tipoRepo, never()).findById(any());
        }

        @Test
        @DisplayName("remover pessoal em dia com geral segue permitido — é como se limpa o que está escondido")
        void remocaoPessoalContinuaValida() {
            pessoaExiste("op-1", "OPERADOR");
            Map<String, Object> body = Map.of("pessoais", Map.of(
                    "pessoa_id", "op-1", "pessoa_tipo", "OPERADOR",
                    "remover", List.of("2026-07-09")));

            service.aplicarLote(body, ADMIN);

            verify(pessoaRepo).deleteByPessoaIdAndPessoaTipoAndData("op-1", "OPERADOR", LocalDate.of(2026, 7, 9));
            verify(diaRepo, never()).findByData(any());   // a guarda é só do aplicar
        }

        @Test
        @DisplayName("aplicar a geral não apaga as individuais do dia: elas só ficam escondidas")
        void geralNaoApagaIndividuais() {
            when(diaRepo.findByData(LocalDate.of(2026, 7, 9))).thenReturn(Optional.empty());
            when(tipoRepo.findById(FERIADO.getId())).thenReturn(Optional.of(FERIADO));
            Map<String, Object> body = Map.of("globais", Map.of(
                    "aplicar", List.of(Map.of("data", "2026-07-09", "tipo_id", "tipo-feriado"))));

            service.aplicarLote(body, ADMIN);

            verify(diaRepo).saveAndFlush(any());
            verifyNoInteractions(pessoaRepo);
        }
    }
}

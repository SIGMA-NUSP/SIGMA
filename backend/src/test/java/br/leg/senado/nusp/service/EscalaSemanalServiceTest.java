package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.EscalaFuncao;
import br.leg.senado.nusp.entity.EscalaOperador;
import br.leg.senado.nusp.entity.EscalaSemanal;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.EscalaFuncaoRepository;
import br.leg.senado.nusp.repository.EscalaOperadorRepository;
import br.leg.senado.nusp.repository.EscalaSemanalRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.SalaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários de EscalaSemanalService com os 6 repositories mockados (sem
 * EntityManager). CRUD/consultas por asserção exata; rodízio COM escala
 * anterior por asserção exata (sem vaga órfã e sem entrante o sorteio não
 * participa — determinístico); rodízio SEM escala anterior (distribuição
 * inicial aleatória) apenas por propriedades invariantes, nunca igualdade
 * exata. minhaEscalaHoje/operadoresEscaladosHoje usam o relógio real →
 * fixtures relativas a hoje, válidas em qualquer dia.
 */
@ExtendWith(MockitoExtension.class)
class EscalaSemanalServiceTest {

    @Mock private EscalaSemanalRepository escalaRepo;
    @Mock private EscalaOperadorRepository escalaOpRepo;
    @Mock private EscalaFuncaoRepository escalaFuncaoRepo;
    @Mock private SalaRepository salaRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private AdministradorRepository adminRepo;
    @Mock private AvisoService avisoService;

    @InjectMocks private EscalaSemanalService service;

    /** Espelho do CICLO_NOMES privado do SUT (ordem do rodízio): mudança lá quebra aqui de propósito. */
    private static final String[] CICLO_NOMES = {
            "Plenário 19", "Plenário 15", "Plenário 13", "Plenário 09",
            "Plenário 07", "Plenário 03", "Plenário 02", "Plenário 06"
    };

    private static final LocalDate INICIO = LocalDate.of(2026, 7, 13);
    private static final LocalDate FIM = LocalDate.of(2026, 7, 17);

    // ══ Fixtures ═════════════════════════════════════════════════

    private static Sala sala(int id, String nome) {
        var s = new Sala();
        s.setId(id);
        s.setNome(nome);
        return s;
    }

    private static Operador operador(String id, String nomeExibicao, String turno) {
        var o = new Operador();
        o.setId(id);
        o.setNomeExibicao(nomeExibicao);
        o.setTurno(turno);
        return o;
    }

    private static EscalaSemanal escalaDe(Long id, LocalDate inicio, LocalDate fim, String criadoPor) {
        var e = new EscalaSemanal();
        e.setId(id);
        e.setDataInicio(inicio);
        e.setDataFim(fim);
        e.setCriadoPor(criadoPor);
        return e;
    }

    private static EscalaOperador vinculo(long escalaId, int salaId, String operadorId, String turno) {
        var v = new EscalaOperador();
        v.setEscalaId(escalaId);
        v.setSalaId(salaId);
        v.setOperadorId(operadorId);
        v.setTurno(turno);
        return v;
    }

    private static EscalaFuncao funcao(long escalaId, String tipo, String operadorId) {
        var f = new EscalaFuncao();
        f.setEscalaId(escalaId);
        f.setTipo(tipo);
        f.setOperadorId(operadorId);
        return f;
    }

    /** 8 salas ativas com os nomes do ciclo; sala de id i = CICLO_NOMES[i-1] → ciclo = [1..8]. */
    private static List<Sala> salasDoCiclo() {
        List<Sala> salas = new ArrayList<>();
        for (int i = 0; i < CICLO_NOMES.length; i++) salas.add(sala(i + 1, CICLO_NOMES[i]));
        return salas;
    }

    /** Participantes m1..mN (turno M) e v1..vN (turno V). */
    private static List<Operador> participantes(int qtdM, int qtdV) {
        List<Operador> ops = new ArrayList<>();
        for (int i = 1; i <= qtdM; i++) ops.add(operador("m" + i, "M" + i, "M"));
        for (int i = 1; i <= qtdV; i++) ops.add(operador("v" + i, "V" + i, "V"));
        return ops;
    }

    private static Set<String> idsPorTurno(List<Operador> ops, String turno) {
        return ops.stream().filter(o -> turno.equals(o.getTurno()))
                .map(Operador::getId).collect(Collectors.toSet());
    }

    /** Stub do save que carimba o id na entidade e a devolve (IDENTITY simulado). */
    private AtomicReference<EscalaSemanal> stubSaveCarimbandoId(long id) {
        var ref = new AtomicReference<EscalaSemanal>();
        when(escalaRepo.save(any(EscalaSemanal.class))).thenAnswer(inv -> {
            EscalaSemanal e = inv.getArgument(0);
            e.setId(id);
            ref.set(e);
            return e;
        });
        return ref;
    }

    /** findById devolvendo a escala carimbada pelo save — para o obterEscala final do salvarEscala. */
    private void stubFindByIdDaSalva(long id, AtomicReference<EscalaSemanal> ref) {
        when(escalaRepo.findById(id)).thenAnswer(inv -> Optional.ofNullable(ref.get()));
    }

    /** findById de operador resolvido por lookup real no conjunto da fixture (nunca resposta fixa cega). */
    private void stubFindOperadorPorId(List<Operador> ops) {
        Map<String, Operador> porId = new HashMap<>();
        ops.forEach(o -> porId.put(o.getId(), o));
        when(operadorRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(porId.get((String) inv.getArgument(0))));
    }

    /**
     * Escala anterior (id 50) completa: sala i ← (mi, vi) para i=1..8.
     * Com todos os 16 ainda participantes e sem entrante, dispM/dispV ficam
     * vazios e o rodízio é 100% determinístico.
     */
    private void stubEscalaAnteriorCompleta() {
        var anterior = escalaDe(50L, INICIO.minusDays(7), INICIO.minusDays(3), "sistema");
        when(escalaRepo.findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO))
                .thenReturn(Optional.of(anterior));
        List<EscalaOperador> vinculos = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            vinculos.add(vinculo(50L, i, "m" + i, "M"));
            vinculos.add(vinculo(50L, i, "v" + i, "V"));
        }
        when(escalaOpRepo.findByEscalaId(50L)).thenReturn(vinculos);
    }

    /** Rotação esperada da escala anterior completa: a dupla da sala i vai para a seguinte (1→2→…→8→1). */
    private static Map<Integer, List<String>> rotacaoEsperadaCompleta() {
        Map<Integer, List<String>> esperado = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            int destino = (i % 8) + 1;
            esperado.put(destino, List.of("m" + i, "v" + i));
        }
        return esperado;
    }

    /** Verifica um save exato por slot preenchido; a lista é [opM, opV], com null para slot vazio (sem save). */
    private void verificarSavesExatos(Map<Integer, List<String>> esperado) {
        int totalSaves = 0;
        for (var entry : esperado.entrySet()) {
            final int salaId = entry.getKey();
            final String opM = entry.getValue().get(0);
            final String opV = entry.getValue().get(1);
            if (opM != null) {
                verify(escalaOpRepo).save(argThat(eo -> eo.getSalaId() == salaId
                        && opM.equals(eo.getOperadorId()) && "M".equals(eo.getTurno())));
                totalSaves++;
            }
            if (opV != null) {
                verify(escalaOpRepo).save(argThat(eo -> eo.getSalaId() == salaId
                        && opV.equals(eo.getOperadorId()) && "V".equals(eo.getTurno())));
                totalSaves++;
            }
        }
        verify(escalaOpRepo, times(totalSaves)).save(any());
    }

    /** Escala vigente hoje (hoje-1 a hoje+1): válida em qualquer dia; o stub casa a data consultada dentro do período. */
    private EscalaSemanal stubEscalaVigenteHoje(long id) {
        var escala = escalaDe(id, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null);
        when(escalaRepo.findVigentesPorData(argThat(d ->
                d != null && !d.isBefore(escala.getDataInicio()) && !d.isAfter(escala.getDataFim()))))
                .thenReturn(List.of(escala));
        return escala;
    }

    // ══ Testes ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("listarEscalas / listarEscalasPaginado")
    class Listar {

        @Test
        @DisplayName("listarEscalas — shape do Map e criado_por resolvido no AdministradorRepository, com fallback para o username cru")
        void listarEscalas_shapeECriadoPorResolvido() {
            var e1 = escalaDe(1L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10), "douglas.antunes");
            e1.setCriadoEm(LocalDateTime.of(2026, 7, 1, 10, 30));
            var e2 = escalaDe(2L, LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 3), "fantasma");
            when(escalaRepo.findAllOrderByDataInicioDesc()).thenReturn(List.of(e1, e2));
            var admin = new Administrador();
            admin.setNomeCompleto("Douglas Antunes");
            when(adminRepo.findByUsername("douglas.antunes")).thenReturn(Optional.of(admin));
            when(adminRepo.findByUsername("fantasma")).thenReturn(Optional.empty());

            var out = service.listarEscalas();

            assertEquals(2, out.size());
            Map<String, Object> m1 = out.get(0);
            assertEquals(1L, m1.get("id"));
            assertEquals("2026-07-06", m1.get("data_inicio"));
            assertEquals("2026-07-10", m1.get("data_fim"));
            assertEquals("Douglas Antunes", m1.get("criado_por"));
            assertEquals("2026-07-01T10:30", m1.get("criado_em"));
            // fallback: o valor esperado vem da ENTIDADE (criadoPor), não do default do mock
            assertEquals("fantasma", out.get(1).get("criado_por"));
            assertNull(out.get(1).get("criado_em"));
        }

        @Test
        @DisplayName("listarEscalasPaginado — fatia a página pedida e monta meta {page, limit, total, pages}")
        void listarEscalasPaginado_fatiaEMeta() {
            // criadoPor null: toMap não consulta o adminRepo — foco na aritmética da paginação
            when(escalaRepo.findAllOrderByDataInicioDesc()).thenReturn(List.of(
                    escalaDe(1L, INICIO, FIM, null),
                    escalaDe(2L, INICIO, FIM, null),
                    escalaDe(3L, INICIO, FIM, null)));

            var out = service.listarEscalasPaginado(2, 2);

            @SuppressWarnings("unchecked")
            var data = (List<Map<String, Object>>) out.get("data");
            assertEquals(1, data.size());
            assertEquals(3L, data.get(0).get("id"));
            Map<?, ?> meta = (Map<?, ?>) out.get("meta");
            assertEquals(2, meta.get("page"));
            assertEquals(2, meta.get("limit"));
            assertEquals(3, meta.get("total"));
            assertEquals(2, meta.get("pages"));
        }

        @Test
        @DisplayName("listarEscalasPaginado — normaliza page<1 e limit<1 para (1, 10); vazio dá total=0 e pages=1")
        void listarEscalasPaginado_normalizacaoEVazio() {
            when(escalaRepo.findAllOrderByDataInicioDesc()).thenReturn(List.of());

            var out = service.listarEscalasPaginado(0, 0);

            // meta é aritmética derivada no SUT, não eco de stub
            assertEquals(List.of(), out.get("data"));
            Map<?, ?> meta = (Map<?, ?>) out.get("meta");
            assertEquals(1, meta.get("page"));
            assertEquals(10, meta.get("limit"));
            assertEquals(0, meta.get("total"));
            assertEquals(1, meta.get("pages"));
        }
    }

    @Nested
    @DisplayName("obterEscala")
    class Obter {

        @Test
        @DisplayName("obterEscala — agrupa salas e funções e monta o resumo na ordem: salas ativas com vínculo, Apoio, Fechamento")
        void obterEscala_shapeCompleto() {
            var escala = escalaDe(10L, INICIO, FIM, "chefe");
            when(escalaRepo.findById(10L)).thenReturn(Optional.of(escala));
            var adminChefe = new Administrador();
            adminChefe.setNomeCompleto("Chefe da Seção");
            when(adminRepo.findByUsername("chefe")).thenReturn(Optional.of(adminChefe));
            when(escalaOpRepo.findByEscalaId(10L)).thenReturn(List.of(
                    vinculo(10L, 1, "a", "M"),
                    vinculo(10L, 1, "b", "V"),
                    vinculo(10L, 2, "c", "M")));
            // FECHAMENTO devolvido ANTES do Apoio de propósito: a ordem do resumo é fixa (Apoio primeiro)
            when(escalaFuncaoRepo.findByEscalaId(10L)).thenReturn(List.of(
                    funcao(10L, "FECHAMENTO", "a"),
                    funcao(10L, "APOIO_COMISSOES", "b")));
            when(operadorRepo.findAll()).thenReturn(List.of(
                    operador("a", "Ana", "M"), operador("b", "Beto", "V"), operador("c", "Caio", "M")));
            // sala 3 ativa mas sem vínculo: não entra no resumo
            when(salaRepo.findAtivasOrdenadas()).thenReturn(List.of(
                    sala(1, "Plenário 19"), sala(2, "Plenário 15"), sala(3, "Plenário 13")));

            var out = service.obterEscala(10L);

            assertEquals(10L, out.get("id"));
            assertEquals("Chefe da Seção", out.get("criado_por"));
            assertEquals(Map.of(1, List.of("a", "b"), 2, List.of("c")), out.get("salas"));
            assertEquals(Map.of("FECHAMENTO", List.of("a"), "APOIO_COMISSOES", List.of("b")), out.get("funcoes"));

            @SuppressWarnings("unchecked")
            var resumo = (List<Map<String, Object>>) out.get("resumo");
            assertEquals(4, resumo.size());
            assertEquals(1, resumo.get(0).get("sala_id"));
            assertEquals("Plenário 19", resumo.get(0).get("sala_nome"));
            assertEquals("Ana, Beto", resumo.get(0).get("operadores"));
            assertEquals(List.of("a", "b"), resumo.get(0).get("operadores_ids"));
            @SuppressWarnings("unchecked")
            var detalhe = (List<Map<String, Object>>) resumo.get(0).get("operadores_detalhe");
            assertEquals("M", detalhe.get(0).get("turno"));
            assertEquals("V", detalhe.get(1).get("turno"));
            assertEquals(2, resumo.get(1).get("sala_id"));
            // funções fecham o resumo na ordem fixa Apoio → Fechamento, sem sala_id e sem detalhe
            assertEquals("Apoio às Comissões", resumo.get(2).get("sala_nome"));
            assertFalse(resumo.get(2).containsKey("sala_id"));
            assertEquals("Beto", resumo.get(2).get("operadores"));
            assertEquals(List.of(), resumo.get(2).get("operadores_detalhe"));
            assertEquals("Fechamento dos Plenários", resumo.get(3).get("sala_nome"));
            assertEquals("Ana", resumo.get(3).get("operadores"));
        }

        @Test
        @DisplayName("obterEscala — id inexistente lança 404")
        void obterEscala_naoEncontrada() {
            when(escalaRepo.findById(99L)).thenReturn(Optional.empty());

            var ex = assertThrows(ServiceValidationException.class, () -> service.obterEscala(99L));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("Escala não encontrada.", ex.getMessage());
            verify(escalaRepo).findById(99L); // guarda do stub igual ao default do Mockito
        }
    }

    @Nested
    @DisplayName("salvarEscala")
    class Salvar {

        @Test
        @DisplayName("salvarEscala — id null cria: grava criadoPor e período, recria vínculos (turno do payload vence; sem payload cai no cadastro) e aceita os 2 tipos de função")
        void salvarEscala_criaComVinculosEFuncoes() {
            var ref = stubSaveCarimbandoId(100L);
            stubFindByIdDaSalva(100L, ref);
            // beta não vem no turnosPorSala → turno buscado no cadastro; "M" é distinto do payload
            // de alfa ("V") para a asserção discriminar as duas fontes de turno
            when(operadorRepo.findById("beta")).thenReturn(Optional.of(operador("beta", "Beto", "M")));

            Map<Integer, List<String>> salasOperadores = new LinkedHashMap<>();
            salasOperadores.put(1, List.of("alfa", "beta"));
            Map<Integer, Map<String, String>> turnos = Map.of(1, Map.of("alfa", "V"));
            Map<String, List<String>> funcoes = new LinkedHashMap<>();
            funcoes.put("APOIO_COMISSOES", List.of("alfa"));
            funcoes.put("FECHAMENTO", List.of("beta"));

            var out = service.salvarEscala(null, INICIO, FIM, salasOperadores, turnos, funcoes, "admin.x");

            verify(escalaRepo).save(argThat(e -> "admin.x".equals(e.getCriadoPor())
                    && INICIO.equals(e.getDataInicio()) && FIM.equals(e.getDataFim())));
            verify(escalaOpRepo).deleteByEscalaId(100L);
            verify(escalaOpRepo).save(argThat(eo -> eo.getEscalaId() == 100L && eo.getSalaId() == 1
                    && "alfa".equals(eo.getOperadorId()) && "V".equals(eo.getTurno())));
            verify(escalaOpRepo).save(argThat(eo -> eo.getEscalaId() == 100L && eo.getSalaId() == 1
                    && "beta".equals(eo.getOperadorId()) && "M".equals(eo.getTurno())));
            verify(escalaOpRepo, times(2)).save(any());
            verify(escalaFuncaoRepo).deleteByEscalaId(100L);
            verify(escalaFuncaoRepo).save(argThat(f -> f.getEscalaId() == 100L
                    && "APOIO_COMISSOES".equals(f.getTipo()) && "alfa".equals(f.getOperadorId())));
            verify(escalaFuncaoRepo).save(argThat(f -> f.getEscalaId() == 100L
                    && "FECHAMENTO".equals(f.getTipo()) && "beta".equals(f.getOperadorId())));
            verify(escalaFuncaoRepo, times(2)).save(any());
            assertEquals(100L, out.get("id")); // retorno = obterEscala da escala recém-salva
        }

        @Test
        @DisplayName("salvarEscala — id existente atualiza o período e PRESERVA o criadoPor original")
        void salvarEscala_atualizaPreservandoCriadoPor() {
            var existente = escalaDe(7L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), "criador.original");
            when(escalaRepo.findById(7L)).thenReturn(Optional.of(existente));
            when(escalaRepo.save(any(EscalaSemanal.class))).thenAnswer(inv -> inv.getArgument(0));

            var out = service.salvarEscala(7L, INICIO, FIM, null, null, null, "quem.edita");

            verify(escalaRepo).save(argThat(e -> "criador.original".equals(e.getCriadoPor())
                    && INICIO.equals(e.getDataInicio()) && FIM.equals(e.getDataFim())));
            // vínculos sempre recriados: com payload nulo, só os deletes — nenhum save de vínculo/função
            verify(escalaOpRepo).deleteByEscalaId(7L);
            verify(escalaOpRepo, never()).save(any());
            verify(escalaFuncaoRepo).deleteByEscalaId(7L);
            verify(escalaFuncaoRepo, never()).save(any());
            assertEquals(7L, out.get("id"));
            assertEquals("2026-07-13", out.get("data_inicio"));
        }

        @Test
        @DisplayName("salvarEscala — id inexistente lança 404 sem salvar nem deletar nada")
        void salvarEscala_idInexistente() {
            when(escalaRepo.findById(99L)).thenReturn(Optional.empty());

            var ex = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEscala(99L, INICIO, FIM, null, null, null, "x"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verify(escalaRepo).findById(99L);
            verify(escalaRepo, never()).save(any());
            verifyNoInteractions(escalaOpRepo, escalaFuncaoRepo);
        }

        @Test
        @DisplayName("salvarEscala — período inválido (datas nulas ou fim < início) lança 400 ANTES de qualquer escrita")
        void salvarEscala_periodoInvalidoNaoEscreve() {
            var exNulas = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEscala(null, null, FIM, null, null, null, "x"));
            assertEquals("Data início e data fim são obrigatórias.", exNulas.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, exNulas.getStatus());

            var exInvertido = assertThrows(ServiceValidationException.class,
                    () -> service.salvarEscala(null, FIM, INICIO, null, null, null, "x"));
            assertEquals("Data fim não pode ser anterior à data início.", exInvertido.getMessage());

            verifyNoInteractions(escalaRepo, escalaOpRepo, escalaFuncaoRepo, salaRepo, operadorRepo, adminRepo);
        }

        @Test
        @DisplayName("salvarEscala — tipo de função desconhecido é rejeitado; caracteriza: o save da escala e os deletes JÁ ocorreram (rollback fica com o @Transactional)")
        void salvarEscala_tipoFuncaoInvalido() {
            stubSaveCarimbandoId(100L);

            var ex = assertThrows(ServiceValidationException.class, () ->
                    service.salvarEscala(null, INICIO, FIM, null, null, Map.of("COPA", List.of("alfa")), "x"));

            assertEquals("Tipo de função inválido: COPA", ex.getMessage());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            verify(escalaFuncaoRepo, never()).save(any());
            // A validação do tipo roda DEPOIS do save e dos deletes (ordem real do SUT — comportamento
            // atual caracterizado; em produção o @Transactional desfaz tudo no rollback).
            verify(escalaRepo).save(any(EscalaSemanal.class));
            verify(escalaOpRepo).deleteByEscalaId(100L);
            verify(escalaFuncaoRepo).deleteByEscalaId(100L);
        }
    }

    @Nested
    @DisplayName("excluirEscala")
    class Excluir {

        @Test
        @DisplayName("excluirEscala — encontrada: apaga os avisos vinculados (F59) e delega ao delete do repositório")
        void excluirEscala_sucesso() {
            var escala = escalaDe(5L, INICIO, FIM, null);
            when(escalaRepo.findById(5L)).thenReturn(Optional.of(escala));

            service.excluirEscala(5L);

            // F59: os avisos de ESCALA saem ANTES da escala (a FK ESCALA_ID barraria o delete).
            var ordem = inOrder(avisoService, escalaRepo);
            ordem.verify(avisoService).excluirPorEscala(5L);
            ordem.verify(escalaRepo).delete(escala);
        }

        @Test
        @DisplayName("excluirEscala — inexistente lança 404 e não deleta nada")
        void excluirEscala_naoEncontrada() {
            when(escalaRepo.findById(99L)).thenReturn(Optional.empty());

            var ex = assertThrows(ServiceValidationException.class, () -> service.excluirEscala(99L));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            verify(escalaRepo, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("minhaEscalaHoje / operadoresEscaladosHoje — SUT usa o relógio real, fixtures relativas a hoje")
    class EscaladosHoje {

        @Test
        @DisplayName("minhaEscalaHoje — devolve as salas do operador na escala vigente (hoje-1 a hoje+1)")
        void minhaEscalaHoje_operadorEscalado() {
            var vigente = stubEscalaVigenteHoje(30L);
            when(escalaOpRepo.findByEscalaIdAndOperadorId(30L, "op1")).thenReturn(List.of(
                    vinculo(30L, 2, "op1", "M"),
                    vinculo(30L, 5, "op1", "M")));
            when(salaRepo.findAll()).thenReturn(List.of(sala(2, "Plenário 15"), sala(5, "Plenário 07")));

            var out = service.minhaEscalaHoje("op1");

            assertEquals(2, out.size());
            var primeira = out.get(0);
            assertEquals(2, primeira.get("sala_id"));
            assertEquals("Plenário 15", primeira.get("sala_nome"));
            assertEquals(30L, primeira.get("escala_id"));
            assertEquals(vigente.getDataInicio().toString(), primeira.get("data_inicio"));
            assertEquals(vigente.getDataFim().toString(), primeira.get("data_fim"));
            assertEquals("Plenário 07", out.get(1).get("sala_nome"));
        }

        @Test
        @DisplayName("minhaEscalaHoje — sem escala vigente devolve lista vazia sem consultar vínculos nem salas")
        void minhaEscalaHoje_semEscalaVigente() {
            when(escalaRepo.findVigentesPorData(any(LocalDate.class))).thenReturn(List.of());

            assertEquals(List.of(), service.minhaEscalaHoje("op1"));

            // o retorno vazio é o early-return provado pela ausência de interações
            verifyNoInteractions(escalaOpRepo, salaRepo);
        }

        @Test
        @DisplayName("operadoresEscaladosHoje — agrupa nome_exibicao por sala na escala vigente")
        void operadoresEscaladosHoje_agrupaPorSala() {
            stubEscalaVigenteHoje(31L);
            when(escalaOpRepo.findByEscalaId(31L)).thenReturn(List.of(
                    vinculo(31L, 2, "a", "M"),
                    vinculo(31L, 2, "b", "V"),
                    vinculo(31L, 5, "c", "M")));
            when(operadorRepo.findAll()).thenReturn(List.of(
                    operador("a", "Ana", "M"), operador("b", "Beto", "V"), operador("c", "Caio", "M")));

            var out = service.operadoresEscaladosHoje();

            assertEquals(Map.of(2, List.of("Ana", "Beto"), 5, List.of("Caio")), out);
        }
    }

    @Nested
    @DisplayName("gerarEscalaRodizio — com escala anterior o caminho é determinístico")
    class GerarRodizio {

        @Test
        @DisplayName("rotação completa: a dupla (M,V) de cada plenário vai para o seguinte do ciclo e a escala é PERSISTIDA")
        void gerarEscalaRodizio_rotacaoCompletaEPersiste() {
            when(operadorRepo.findParticipantesEscala()).thenReturn(participantes(8, 8));
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            stubEscalaAnteriorCompleta();
            stubFindOperadorPorId(participantes(8, 8));
            var ref = stubSaveCarimbandoId(100L);
            stubFindByIdDaSalva(100L, ref);
            // leitura do obterEscala final: stub explícito — o método já tem stub p/ 50L e a
            // chamada com 100L sem stub é o gatilho clássico de mismatch do strict-stubs
            when(escalaOpRepo.findByEscalaId(100L)).thenReturn(List.of());

            var out = service.gerarEscalaRodizio(INICIO, FIM, "admin.x");

            // sem vaga órfã e sem entrante o sorteio não participa: asserção exata das 16 atribuições
            verificarSavesExatos(rotacaoEsperadaCompleta());
            verify(escalaOpRepo).deleteByEscalaId(100L);
            verify(escalaRepo).save(argThat(e -> "admin.x".equals(e.getCriadoPor())
                    && INICIO.equals(e.getDataInicio()) && FIM.equals(e.getDataFim())));
            assertEquals(100L, out.get("id"));
        }

        @Test
        @DisplayName("saída + entrante único: m9 assume a vaga órfã de m1 e rotaciona junto — asserção exata das 16 posições (determinístico)")
        void gerarEscalaRodizio_saidaEEntranteUnico() {
            // m1 saiu; m9 entrou — shuffle de lista unitária é determinístico
            List<Operador> atuais = participantes(8, 8).stream()
                    .filter(o -> !o.getId().equals("m1"))
                    .collect(Collectors.toCollection(ArrayList::new));
            atuais.add(operador("m9", "M9", "M"));
            when(operadorRepo.findParticipantesEscala()).thenReturn(atuais);
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            stubEscalaAnteriorCompleta(); // a anterior ainda referencia m1 na sala 1
            stubFindOperadorPorId(atuais);
            var ref = stubSaveCarimbandoId(100L);
            stubFindByIdDaSalva(100L, ref);
            when(escalaOpRepo.findByEscalaId(100L)).thenReturn(List.of()); // leitura do obterEscala final

            service.gerarEscalaRodizio(INICIO, FIM, "admin.x");

            // rotação completa com m9 no lugar de m1 (sala 1 → sala 2); m1 não aparece em nenhum save
            var esperado = rotacaoEsperadaCompleta();
            esperado.put(2, List.of("m9", "v1"));
            verificarSavesExatos(esperado);
            verify(escalaOpRepo, never()).save(argThat(eo -> "m1".equals(eo.getOperadorId())));
        }

        @Test
        @DisplayName("duas vagas órfãs (saem m1 e m8, entra só m9): o preenchimento precede a rotação — m9 cobre a primeira órfã do ciclo e o slot excedente roda vazio")
        void gerarEscalaRodizio_duasOrfasEntranteUnico() {
            List<Operador> atuais = participantes(8, 8).stream()
                    .filter(o -> !o.getId().equals("m1") && !o.getId().equals("m8"))
                    .collect(Collectors.toCollection(ArrayList::new));
            atuais.add(operador("m9", "M9", "M"));
            when(operadorRepo.findParticipantesEscala()).thenReturn(atuais);
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            stubEscalaAnteriorCompleta(); // a anterior referencia m1 (sala 1) e m8 (sala 8)
            stubFindOperadorPorId(atuais);
            var ref = stubSaveCarimbandoId(100L);
            stubFindByIdDaSalva(100L, ref);
            when(escalaOpRepo.findByEscalaId(100L)).thenReturn(List.of()); // leitura do obterEscala final

            service.gerarEscalaRodizio(INICIO, FIM, "admin.x");

            // Órfãs pré-rotação nas salas 1 (m1) e 8 (m8); o único disponível (m9) preenche a
            // PRIMEIRA do ciclo (sala 1) e rotaciona para a sala 2; a órfã da sala 8 roda vazia
            // para a sala 1 → 15 saves. Se o SUT preenchesse DEPOIS de rotacionar, m9 cairia na
            // sala 1 — este caso discrimina a ordem interna que o título alega.
            var esperado = rotacaoEsperadaCompleta();
            esperado.put(2, List.of("m9", "v1"));
            esperado.put(1, Arrays.asList(null, "v8"));
            verificarSavesExatos(esperado);
            verify(escalaOpRepo, never()).save(argThat(eo ->
                    "m1".equals(eo.getOperadorId()) || "m8".equals(eo.getOperadorId())));
        }

        @Test
        @DisplayName("mais de 8 operadores no turno Matutino: rejeita antes de qualquer leitura de sala ou escrita")
        void gerarEscalaRodizio_maisDeOitoNoMatutino() {
            when(operadorRepo.findParticipantesEscala()).thenReturn(participantes(9, 0));

            var ex = assertThrows(ServiceValidationException.class,
                    () -> service.gerarEscalaRodizio(INICIO, FIM, "x"));

            assertEquals("Há 9 operadores no turno Matutino, máximo 8. Ajuste antes de gerar a escala.",
                    ex.getMessage());
            verifyNoInteractions(salaRepo, escalaRepo, escalaOpRepo, escalaFuncaoRepo);
        }

        @Test
        @DisplayName("mais de 8 operadores no turno Vespertino: rejeita")
        void gerarEscalaRodizio_maisDeOitoNoVespertino() {
            when(operadorRepo.findParticipantesEscala()).thenReturn(participantes(0, 9));

            var ex = assertThrows(ServiceValidationException.class,
                    () -> service.gerarEscalaRodizio(INICIO, FIM, "x"));

            assertEquals("Há 9 operadores no turno Vespertino, máximo 8. Ajuste antes de gerar a escala.",
                    ex.getMessage());
            verifyNoInteractions(salaRepo, escalaRepo, escalaOpRepo, escalaFuncaoRepo);
        }

        @Test
        @DisplayName("sala do ciclo ausente das ativas: rejeita com o nome da sala, sem escrever nada")
        void gerarEscalaRodizio_salaDoCicloAusente() {
            when(operadorRepo.findParticipantesEscala()).thenReturn(participantes(1, 0));
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo().subList(0, 7)); // sem "Plenário 06"

            var ex = assertThrows(ServiceValidationException.class,
                    () -> service.gerarEscalaRodizio(INICIO, FIM, "x"));

            assertEquals("Sala 'Plenário 06' não encontrada.", ex.getMessage());
            verifyNoInteractions(escalaRepo, escalaOpRepo, escalaFuncaoRepo);
        }
    }

    @Nested
    @DisplayName("gerarPreviaEscalaRodizio")
    class PreviaRodizio {

        @Test
        @DisplayName("prévia com escala anterior: mapa rotacionado exato e NENHUM efeito de escrita")
        void gerarPrevia_naoEscreveERotacionaExato() {
            when(operadorRepo.findParticipantesEscala()).thenReturn(participantes(8, 8));
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            stubEscalaAnteriorCompleta();

            var out = service.gerarPreviaEscalaRodizio(INICIO, FIM);

            assertEquals(rotacaoEsperadaCompleta(), out.get("salas"));
            // nenhum save/delete em NENHUM dos 6 repos: só as 4 leituras esperadas, e mais nada
            verify(escalaRepo).findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO);
            verify(escalaOpRepo).findByEscalaId(50L);
            verify(salaRepo).findAtivasOrdenadas();
            verify(operadorRepo).findParticipantesEscala();
            verifyNoMoreInteractions(escalaRepo, escalaOpRepo, salaRepo, operadorRepo);
            verifyNoInteractions(escalaFuncaoRepo, adminRepo);
        }

        @Test
        @DisplayName("prévia sem escala anterior (8M+8V): propriedades do sorteio — cada participante exatamente 1x, 1 M + 1 V por sala; nunca igualdade exata")
        void gerarPrevia_distribuicaoInicialPropriedades() {
            var todos = participantes(8, 8);
            when(operadorRepo.findParticipantesEscala()).thenReturn(todos);
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            when(escalaRepo.findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO))
                    .thenReturn(Optional.empty());
            Set<String> idsM = idsPorTurno(todos, "M");
            Set<String> idsV = idsPorTurno(todos, "V");
            Set<String> idsTodos = todos.stream().map(Operador::getId).collect(Collectors.toSet());

            // 20 amostras na mesma execução: as invariantes não podem depender do embaralhamento
            for (int i = 0; i < 20; i++) {
                @SuppressWarnings("unchecked")
                var salas = (Map<Integer, List<String>>) service.gerarPreviaEscalaRodizio(INICIO, FIM).get("salas");

                assertEquals(8, salas.size(), "todas as salas do ciclo presentes no mapa");
                List<String> distribuidos = salas.values().stream().flatMap(List::stream).toList();
                assertEquals(16, distribuidos.size(), "todo participante distribuído");
                assertEquals(idsTodos, new HashSet<>(distribuidos), "exatamente os participantes, sem repetição");
                for (var e : salas.entrySet()) {
                    assertEquals(2, e.getValue().size(), "sala " + e.getKey() + " com 1 M + 1 V");
                    assertTrue(idsM.contains(e.getValue().get(0)), "primeiro da lista é do turno M");
                    assertTrue(idsV.contains(e.getValue().get(1)), "segundo da lista é do turno V");
                }
            }
            // nenhum efeito de escrita em NENHUM repo nas 20 execuções: só as 3 leituras por rodada
            verify(escalaRepo, times(20)).findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO);
            verify(salaRepo, times(20)).findAtivasOrdenadas();
            verify(operadorRepo, times(20)).findParticipantesEscala();
            verifyNoMoreInteractions(escalaRepo, salaRepo, operadorRepo);
            verifyNoInteractions(escalaOpRepo, escalaFuncaoRepo, adminRepo);
        }

        @Test
        @DisplayName("prévia sem anterior com turnos desiguais (5M+3V): todos alocados, no máximo 1 por turno por sala")
        void gerarPrevia_distribuicaoParcialPropriedades() {
            var todos = participantes(5, 3);
            when(operadorRepo.findParticipantesEscala()).thenReturn(todos);
            when(salaRepo.findAtivasOrdenadas()).thenReturn(salasDoCiclo());
            when(escalaRepo.findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO))
                    .thenReturn(Optional.empty());
            Set<String> idsM = idsPorTurno(todos, "M");
            Set<String> idsV = idsPorTurno(todos, "V");
            Set<String> idsTodos = todos.stream().map(Operador::getId).collect(Collectors.toSet());

            for (int i = 0; i < 20; i++) {
                @SuppressWarnings("unchecked")
                var salas = (Map<Integer, List<String>>) service.gerarPreviaEscalaRodizio(INICIO, FIM).get("salas");

                List<String> distribuidos = salas.values().stream().flatMap(List::stream).toList();
                assertEquals(8, distribuidos.size(), "todo participante distribuído (5M+3V)");
                assertEquals(idsTodos, new HashSet<>(distribuidos), "exatamente os participantes, sem repetição");
                for (var e : salas.entrySet()) {
                    long m = e.getValue().stream().filter(idsM::contains).count();
                    long v = e.getValue().stream().filter(idsV::contains).count();
                    assertTrue(m <= 1, "sala " + e.getKey() + " com no máximo 1 do turno M");
                    assertTrue(v <= 1, "sala " + e.getKey() + " com no máximo 1 do turno V");
                }
            }
            // nenhum efeito de escrita em NENHUM repo nas 20 execuções: só as 3 leituras por rodada
            verify(escalaRepo, times(20)).findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(INICIO);
            verify(salaRepo, times(20)).findAtivasOrdenadas();
            verify(operadorRepo, times(20)).findParticipantesEscala();
            verifyNoMoreInteractions(escalaRepo, salaRepo, operadorRepo);
            verifyNoInteractions(escalaOpRepo, escalaFuncaoRepo, adminRepo);
        }
    }
}

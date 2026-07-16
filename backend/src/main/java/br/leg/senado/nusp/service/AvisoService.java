package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.AvisoMensagem;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Serviço único de avisos. Genérico para todos os tipos (VERIFICACAO, ESCALA,
 * PESSOAL, AGENDA, GERAL) e todos os públicos (sala / operador / técnico /
 * coletivos). Nesta entrega o frontend só exercita o tipo VERIFICACAO com
 * público SALA, mas a camada de serviço já aceita todas as variantes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvisoService {

    private final AvisoCadastroRepository cadastroRepo;
    private final AvisoMensagemRepository mensagemRepo;
    private final AvisoAlvoRepository alvoRepo;
    private final AvisoCienciaRepository cienciaRepo;
    private final SalaRepository salaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository adminRepo;
    private final AvisoCienciaWriter cienciaWriter;
    private final EntityManager entityManager;

    private static final int MAX_MENSAGENS = 10;
    private static final int MAX_DURACAO_DIAS = 30;

    /** Payload de criação (montado pelo controller a partir do JSON snake_case). */
    public record CriarAvisoRequest(
            String tipo,
            Boolean permanente,
            Integer duracaoDias,
            Boolean manterAposCiencia,
            List<String> mensagens,
            String alvoTipo,
            List<Integer> salaIds,
            List<String> operadorIds,
            List<String> tecnicoIds) {}

    // ═══ Cadastro (admin) ═══════════════════════════════════════

    @Transactional
    public Map<String, Object> criar(CriarAvisoRequest req, String criadoPorId) {
        // ── Tipo ──
        TipoAviso tipo = parseTipo(req.tipo());

        // ── Mensagens ──
        List<String> mensagens = (req.mensagens() == null ? List.<String>of() : req.mensagens())
                .stream().map(m -> m == null ? "" : m.trim()).toList();
        if (mensagens.isEmpty()) throw new ServiceValidationException("Informe ao menos uma mensagem.");
        if (mensagens.size() > MAX_MENSAGENS)
            throw new ServiceValidationException("Máximo de " + MAX_MENSAGENS + " avisos por cadastro.");
        if (mensagens.stream().anyMatch(String::isBlank))
            throw new ServiceValidationException("Todas as mensagens devem ser preenchidas.");

        // ── Permanente / duração ──
        boolean permanente = req.permanente() == null || req.permanente(); // default true
        Integer duracao = permanente ? null : req.duracaoDias();
        if (!permanente && (duracao == null || duracao < 1 || duracao > MAX_DURACAO_DIAS))
            throw new ServiceValidationException("A duração deve estar entre 1 e " + MAX_DURACAO_DIAS + " dias.");
        boolean manter = req.manterAposCiencia() != null && req.manterAposCiencia();

        // ── Público (alvo) ──
        AlvoTipoAviso alvoTipo = parseAlvoTipo(req.alvoTipo());
        // Deduplica para não gerar linhas de alvo repetidas (sem UNIQUE no schema).
        List<Integer> salaIds = (req.salaIds() == null ? List.<Integer>of() : req.salaIds()).stream().distinct().toList();
        List<String> operadorIds = (req.operadorIds() == null ? List.<String>of() : req.operadorIds()).stream().distinct().toList();
        List<String> tecnicoIds = (req.tecnicoIds() == null ? List.<String>of() : req.tecnicoIds()).stream().distinct().toList();
        validarAlvo(alvoTipo, salaIds, operadorIds, tecnicoIds);

        // Regra: no máximo 1 aviso ativo por sala (para o mesmo tipo).
        if (alvoTipo == AlvoTipoAviso.SALA) validarSalasLivres(tipo, salaIds);

        // ── Autor (FK) ──
        adminRepo.findById(criadoPorId).orElseThrow(() ->
                new ServiceValidationException("Administrador inválido.", HttpStatus.NOT_FOUND));

        LocalDateTime agora = LocalDateTime.now();

        // ── Cadastro ──
        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(tipo);
        cad.setPermanente(permanente);
        cad.setDuracaoDias(duracao);
        cad.setManterAposCiencia(manter);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(permanente ? null : agora.plusDays(duracao));
        cad = cadastroRepo.save(cad);

        // ── Mensagens ──
        int ordem = 1;
        for (String texto : mensagens) {
            AvisoMensagem m = new AvisoMensagem();
            m.setCadastroId(cad.getId());
            m.setOrdem(ordem++);
            m.setTexto(texto);
            mensagemRepo.save(m);
        }

        // ── Alvos ──
        List<AvisoAlvo> alvos = montarAlvos(cad.getId(), alvoTipo, salaIds, operadorIds, tecnicoIds);
        alvoRepo.saveAll(alvos);

        log.info("Aviso cadastro #{} criado (tipo={}, {} mensagens, alvo={}, {} alvo(s)) por {}",
                cad.getNumero(), tipo, mensagens.size(), alvoTipo, alvos.size(), criadoPorId);
        return toResumo(cad);
    }

    @Transactional
    public void desativar(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        if (cad.getStatus() == StatusAviso.DESATIVADO)
            throw new ServiceValidationException("Aviso já está desativado.");
        cad.setStatus(StatusAviso.DESATIVADO);
        cad.setDesativadoEm(LocalDateTime.now());
        cadastroRepo.save(cad);
        log.info("Aviso cadastro #{} desativado", cad.getNumero());
    }

    // ═══ Criação programática (ex.: publicação de folha de ponto) ═══

    /** Destinatário individual de um aviso (pessoa + papel). */
    public record DestinatarioAviso(String pessoaId, PapelPessoa papel) {}

    /**
     * Aviso PESSOAL sem proveniência de lote — é o caminho do desfecho de folga do banco de horas
     * (e de qualquer outro futuro que não nasça de uma publicação). {@code ORIGEM_LOTE_ID} fica NULL,
     * e é isso que mantém estes avisos FORA da exclusão de um lote (F59): ela só apaga o que a
     * publicação marcou como seu.
     */
    @Transactional
    public void criarPessoalIndividual(List<DestinatarioAviso> destinatarios, String mensagem, String criadoPorId) {
        criarPessoalIndividual(destinatarios, mensagem, criadoPorId, null);
    }

    /**
     * Cria um aviso PESSOAL (permanente, some após a ciência) com uma única
     * mensagem e um alvo individual por destinatário (operador/técnico/admin).
     * Usado pela publicação de folha de ponto. Os IDs vêm de vínculo interno
     * confiável (integridade garantida pelas FKs), por isso não revalida cada
     * pessoa como o cadastro do form admin. Sem destinatários válidos, é no-op.
     *
     * <p>{@code origemLoteId} é a PROVENIÊNCIA (F59): o lote cuja publicação criou este aviso.
     * Excluir aquele lote apaga exatamente os cadastros marcados com ele — e nenhum outro.
     * {@code null} para as origens que não são uma publicação.
     */
    @Transactional
    public void criarPessoalIndividual(List<DestinatarioAviso> destinatarios, String mensagem,
                                       String criadoPorId, String origemLoteId) {
        if (mensagem == null || mensagem.isBlank())
            throw new ServiceValidationException("Mensagem do aviso é obrigatória.");
        adminRepo.findById(criadoPorId).orElseThrow(() ->
                new ServiceValidationException("Administrador inválido.", HttpStatus.NOT_FOUND));

        // Dedup por (papel, pessoa); ignora entradas incompletas.
        List<DestinatarioAviso> validos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (DestinatarioAviso d : (destinatarios == null ? List.<DestinatarioAviso>of() : destinatarios)) {
            if (d == null || d.pessoaId() == null || d.papel() == null) continue;
            if (vistos.add(d.papel() + ":" + d.pessoaId())) validos.add(d);
        }
        if (validos.isEmpty()) return;

        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(TipoAviso.PESSOAL);
        cad.setPermanente(true);          // sem prazo
        cad.setManterAposCiencia(false);  // some quando a pessoa marca ciência
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setOrigemLoteId(origemLoteId);   // F59: NULL fora da publicação de folha
        cad = cadastroRepo.save(cad);

        AvisoMensagem m = new AvisoMensagem();
        m.setCadastroId(cad.getId());
        m.setOrdem(1);
        m.setTexto(mensagem.trim());
        mensagemRepo.save(m);

        List<AvisoAlvo> alvos = new ArrayList<>();
        for (DestinatarioAviso d : validos) {
            AvisoAlvo a = new AvisoAlvo();
            a.setCadastroId(cad.getId());
            switch (d.papel()) {
                case OPERADOR -> { a.setAlvoTipo(AlvoTipoAviso.OPERADOR); a.setOperadorId(d.pessoaId()); }
                case TECNICO  -> { a.setAlvoTipo(AlvoTipoAviso.TECNICO);  a.setTecnicoId(d.pessoaId()); }
                case ADMIN    -> { a.setAlvoTipo(AlvoTipoAviso.ADMIN);    a.setAdminId(d.pessoaId()); }
            }
            alvos.add(a);
        }
        alvoRepo.saveAll(alvos);
        log.info("Aviso PESSOAL #{} criado com {} destinatário(s) por {}", cad.getNumero(), alvos.size(), criadoPorId);
    }

    // ═══ Listagem (admin) ═══════════════════════════════════════

    private static final String TIPO_EXPR =
            "CASE c.TIPO " +
            "WHEN 'VERIFICACAO' THEN 'Verificação' " +
            "WHEN 'ESCALA' THEN 'Escala' " +
            "WHEN 'PESSOAL' THEN 'Pessoal' " +
            "WHEN 'AGENDA' THEN 'Agenda' " +
            "WHEN 'GERAL' THEN 'Geral' END";

    private static final Map<String, String> SORT;
    private static final Map<String, String> COL_MAP;
    private static final Map<String, String> COL_TYPES;
    static {
        SORT = new LinkedHashMap<>();
        SORT.put("data", "c.CRIADO_EM");
        SORT.put("numero", "c.NUMERO");
        SORT.put("tipo", TIPO_EXPR);
        SORT.put("expira", "c.EXPIRA_EM");
        SORT.put("status", "c.STATUS");
        SORT.put("criado_por", "ad.NOME_COMPLETO");

        COL_MAP = new LinkedHashMap<>();
        COL_MAP.put("tipo", TIPO_EXPR);
        COL_MAP.put("data", "c.CRIADO_EM");
        COL_MAP.put("expira", "c.EXPIRA_EM");
        COL_MAP.put("status", "c.STATUS");
        COL_MAP.put("criado_por", "ad.NOME_COMPLETO");

        COL_TYPES = new LinkedHashMap<>();
        COL_TYPES.put("tipo", "text");
        COL_TYPES.put("data", "date");
        COL_TYPES.put("expira", "date");
        COL_TYPES.put("status", "text");
        COL_TYPES.put("criado_por", "text");
    }

    public DashboardQueryHelper.PagedResult listarTodosPaginado(int page, int limit,
            String search, String sort, String direction, Map<String, Object> filters) {
        if (page < 1) page = 1;
        if (limit < 1) limit = 10;
        String selectCols =
                "c.ID, c.NUMERO, " + TIPO_EXPR + " AS tipo, " +
                "c.CRIADO_EM AS criado_em, ad.NOME_COMPLETO AS criado_por, " +
                "c.EXPIRA_EM AS expira_em, c.STATUS AS status, c.PERMANENTE AS permanente";
        String fromJoins =
                "FROM FRM_AVISO_CADASTRO c " +
                "LEFT JOIN PES_ADMINISTRADOR ad ON ad.ID = c.CRIADO_POR_ID";
        return DashboardQueryHelper.executePagedQuery(entityManager,
                selectCols, fromJoins,
                null, SORT,
                List.of("ad.NOME_COMPLETO", "TO_CHAR(c.NUMERO)"),
                COL_MAP, COL_TYPES,
                page, limit, search, sort, direction, null, filters,
                "c.ID DESC");
    }

    /** Detalhe completo de um cadastro (cabeçalho + mensagens + alvos + cientes). */
    public Map<String, Object> obterDetalhe(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        Map<String, Object> m = toResumo(cad);
        m.put("mensagens", mensagemRepo.findByCadastroIdOrderByOrdem(id).stream()
                .map(this::mensagemToMap).toList());
        // Resolve nomes de alvos/ciências com mapas carregados no máx. 1× por tipo (lazy) — evita N+1 (Q18).
        ResolvedorNomes nomes = new ResolvedorNomes();
        m.put("alvos", alvoRepo.findByCadastroId(id).stream()
                .map(a -> alvoToMap(a, nomes)).toList());
        m.put("cientes", cad.getTipo() != null && cad.getTipo().exigeCiencia()
                ? cienciaRepo.findByCadastroIdOrderByCienteEm(id).stream().map(c -> cienciaToMap(c, nomes)).toList()
                : List.of());
        return m;
    }

    // ═══ Consulta pelo destinatário (verificação) ═══════════════

    /**
     * Retorna o aviso ativo de tipo VERIFICACAO pendente para a pessoa na sala
     * informada, ou vazio. "Pendente" = aviso ativo cuja sala-alvo contém salaId
     * e que (a) tem manter_apos_ciencia=1 — sempre reaparece — ou (b) a pessoa
     * ainda não marcou ciência. Se houver mais de um candidato, retorna o mais
     * antigo (FIFO); os demais aparecem nas próximas entradas.
     */
    @Transactional
    public Optional<Map<String, Object>> buscarPendenteVerificacao(Integer salaId, String pessoaId, PapelPessoa papel) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.ID, c.MANTER_APOS_CIENCIA
                  FROM FRM_AVISO_CADASTRO c
                  JOIN FRM_AVISO_ALVO al ON al.CADASTRO_ID = c.ID
                 WHERE c.TIPO = 'VERIFICACAO'
                   AND c.STATUS = 'Ativo'
                   AND (c.PERMANENTE = 1 OR c.EXPIRA_EM IS NULL OR c.EXPIRA_EM > SYSTIMESTAMP)
                   AND al.ALVO_TIPO = 'SALA'
                   AND al.SALA_ID = ?
                 ORDER BY c.CRIADO_EM
                """).setParameter(1, salaId).getResultList();

        for (Object[] r : rows) {
            String cadastroId = (String) r[0];
            boolean manter = ((Number) r[1]).intValue() == 1;
            boolean jaCiente = temCiencia(cadastroId, salaId, pessoaId, papel);
            if (!manter && jaCiente) continue; // já marcou NESTA sala e não é pra manter → pula
            return Optional.of(montarPayloadPendente(cadastroId, manter, TipoAviso.VERIFICACAO));
        }
        return Optional.empty();
    }

    /**
     * Avisos pendentes da pessoa pelo CONTEXTO da tela: "agenda" exibe também
     * os avisos de AGENDA (telas de agenda); qualquer outro contexto exibe
     * ESCALA/PESSOAL/GERAL.
     */
    @Transactional
    public List<Map<String, Object>> buscarPendentes(String pessoaId, PapelPessoa papel, String contexto) {
        List<TipoAviso> tipos = "agenda".equalsIgnoreCase(contexto)
                ? List.of(TipoAviso.ESCALA, TipoAviso.PESSOAL, TipoAviso.GERAL, TipoAviso.AGENDA)
                : List.of(TipoAviso.ESCALA, TipoAviso.PESSOAL, TipoAviso.GERAL);
        return buscarPendentes(pessoaId, papel, tipos);
    }

    /**
     * Avisos pendentes (sem sala) para a pessoa logada, dentre os TIPOS pedidos
     * (ex.: ESCALA/PESSOAL/GERAL em qualquer página; AGENDA nas telas de agenda).
     * Considera os alvos individuais do papel e os coletivos correspondentes.
     * Tipos que exigem ciência saem da lista quando a pessoa já deu ciência (e
     * não é "manter após ciência"); tipos sem ciência (GERAL/AGENDA) são sempre
     * retornados — o front os dispensa por sessão. Do mais antigo ao mais novo.
     */
    @Transactional
    public List<Map<String, Object>> buscarPendentes(String pessoaId, PapelPessoa papel, List<TipoAviso> tipos) {
        if (tipos == null || tipos.isEmpty()) return List.of();
        String condAlvo = switch (papel) {
            case OPERADOR -> "(al.ALVO_TIPO = 'OPERADOR' AND al.OPERADOR_ID = ?) OR al.ALVO_TIPO IN ('TODOS_OPERADORES','TODOS')";
            case TECNICO  -> "(al.ALVO_TIPO = 'TECNICO' AND al.TECNICO_ID = ?) OR al.ALVO_TIPO IN ('TODOS_TECNICOS','TODOS')";
            case ADMIN    -> "(al.ALVO_TIPO = 'ADMIN' AND al.ADMIN_ID = ?) OR al.ALVO_TIPO = 'TODOS_ADMIN'";
        };
        // Valores do enum (controlados) → seguro montar o IN diretamente.
        String inTipos = String.join(",", tipos.stream().map(t -> "'" + t.name() + "'").toList());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT c.ID, c.MANTER_APOS_CIENCIA, c.TIPO " +
                "  FROM FRM_AVISO_CADASTRO c " +
                " WHERE c.TIPO IN (" + inTipos + ") " +
                "   AND c.STATUS = 'Ativo' " +
                "   AND (c.PERMANENTE = 1 OR c.EXPIRA_EM IS NULL OR c.EXPIRA_EM > SYSTIMESTAMP) " +
                "   AND EXISTS (SELECT 1 FROM FRM_AVISO_ALVO al WHERE al.CADASTRO_ID = c.ID AND (" + condAlvo + ")) " +
                " ORDER BY c.CRIADO_EM")
                .setParameter(1, pessoaId).getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String cadastroId = (String) r[0];
            boolean manter = ((Number) r[1]).intValue() == 1;
            TipoAviso tipo = TipoAviso.fromString((String) r[2]);
            // Tipos com ciência: se já ciente e não é "manter", já não está pendente.
            if (tipo.exigeCiencia() && !manter && temCiencia(cadastroId, null, pessoaId, papel)) continue;
            out.add(montarPayloadPendente(cadastroId, manter, tipo));
        }
        return out;
    }

    /**
     * Registra a ciência da pessoa no cadastro. Só a primeira ciência conta:
     * se já existe, não faz nada (cenário normal de manter_apos_ciencia=1).
     * O índice único parcial no banco é a rede de segurança contra corrida.
     */
    @Transactional
    public void registrarCiencia(String cadastroId, Integer salaId, String pessoaId, PapelPessoa papel) {
        AvisoCadastro cad = cadastroRepo.findById(cadastroId).orElseThrow(() ->
                new ServiceValidationException("Aviso não encontrado.", HttpStatus.NOT_FOUND));
        if (cad.getTipo() == null || !cad.getTipo().exigeCiencia())
            throw new ServiceValidationException("Este tipo de aviso não registra ciência.");
        // Sala obrigatória só para tipos amarrados a sala (VERIFICACAO); PESSOAL não tem sala.
        if (cad.getTipo().exigeSala() && salaId == null)
            throw new ServiceValidationException("Sala é obrigatória para registrar ciência.");
        // Aviso não mais ativo (expirou/desativou entre a exibição e o clique):
        // nada a registrar, mas não bloqueia o destinatário.
        if (cad.getStatus() != StatusAviso.ATIVO) return;
        if (temCiencia(cadastroId, salaId, pessoaId, papel)) return;

        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cadastroId);
        c.setSalaId(salaId);
        switch (papel) {
            case OPERADOR -> c.setOperadorId(pessoaId);
            case TECNICO  -> c.setTecnicoId(pessoaId);
            case ADMIN    -> c.setAdminId(pessoaId);
        }
        c.setCienteEm(LocalDateTime.now());
        try {
            cienciaWriter.inserir(c); // REQUIRES_NEW: isola eventual violação de unicidade em corrida
            log.info("Ciência registrada: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
        } catch (DataIntegrityViolationException e) {
            // Corrida: outra requisição já gravou a ciência. Operação idempotente — ignora.
            log.debug("Ciência concorrente já registrada: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
        }
    }

    // ═══ Rotina de expiração ════════════════════════════════════

    /** A cada 15 minutos, marca como Expirado os avisos ativos não-permanentes vencidos. */
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void expirarAvisos() {
        int n = entityManager.createNativeQuery("""
                UPDATE FRM_AVISO_CADASTRO
                   SET STATUS = 'Expirado'
                 WHERE STATUS = 'Ativo'
                   AND PERMANENTE = 0
                   AND EXPIRA_EM IS NOT NULL
                   AND EXPIRA_EM < SYSTIMESTAMP
                """).executeUpdate();
        if (n > 0) log.info("[avisos] {} aviso(s) marcado(s) como Expirado.", n);
    }

    // ═══ Helpers ════════════════════════════════════════════════

    private TipoAviso parseTipo(String v) {
        if (v == null || v.isBlank()) throw new ServiceValidationException("Tipo de aviso é obrigatório.");
        try { return TipoAviso.fromString(v); }
        catch (IllegalArgumentException e) { throw new ServiceValidationException("Tipo de aviso inválido."); }
    }

    private AlvoTipoAviso parseAlvoTipo(String v) {
        if (v == null || v.isBlank()) throw new ServiceValidationException("Tipo de público é obrigatório.");
        try { return AlvoTipoAviso.fromString(v); }
        catch (IllegalArgumentException e) { throw new ServiceValidationException("Tipo de público inválido."); }
    }

    /** Próximo "número humano" do cadastro (sequence Oracle). */
    private long proximoNumeroCadastro() {
        Number n = (Number) entityManager
                .createNativeQuery("SELECT SEQ_FRM_AVISO_CADASTRO.NEXTVAL FROM DUAL")
                .getSingleResult();
        return n.longValue();
    }

    private void validarAlvo(AlvoTipoAviso alvoTipo, List<Integer> salaIds,
                             List<String> operadorIds, List<String> tecnicoIds) {
        switch (alvoTipo) {
            case SALA -> {
                if (salaIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um local.");
                if (!operadorIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público por sala não aceita operadores/técnicos individuais.");
                for (Integer sid : salaIds)
                    salaRepo.findById(sid).orElseThrow(() ->
                            new ServiceValidationException("Local inválido: " + sid, HttpStatus.NOT_FOUND));
            }
            case OPERADOR -> {
                if (operadorIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um operador.");
                if (!salaIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público por operador não aceita salas/técnicos.");
                for (String oid : operadorIds)
                    operadorRepo.findById(oid).orElseThrow(() ->
                            new ServiceValidationException("Operador inválido: " + oid, HttpStatus.NOT_FOUND));
            }
            case TECNICO -> {
                if (tecnicoIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um técnico.");
                if (!salaIds.isEmpty() || !operadorIds.isEmpty())
                    throw new ServiceValidationException("Público por técnico não aceita salas/operadores.");
                for (String tid : tecnicoIds)
                    tecnicoRepo.findById(tid).orElseThrow(() ->
                            new ServiceValidationException("Técnico inválido: " + tid, HttpStatus.NOT_FOUND));
            }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS -> {
                if (!salaIds.isEmpty() || !operadorIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público coletivo não aceita seleção individual.");
            }
            // ADMIN/TODOS_ADMIN existem no enum, no CHECK do banco e na leitura, mas este cadastro
            // não sabe montá-los (ADMIN só é gravado por criarPessoalIndividual). Antes escapavam do
            // switch sem validação e sem alvo → aviso ATIVO e invisível. O default garante que
            // nenhuma constante futura repita o silêncio.
            case ADMIN, TODOS_ADMIN -> throw publicoNaoSuportado(alvoTipo);
            default -> throw publicoNaoSuportado(alvoTipo);
        }
    }

    /** Público que o cadastro do admin não produz — falha em voz alta em vez de gerar aviso órfão. */
    private ServiceValidationException publicoNaoSuportado(AlvoTipoAviso alvoTipo) {
        return new ServiceValidationException(
                "Público não suportado neste cadastro: " + alvoTipo + ".");
    }

    /** Rejeita criação se alguma sala já tem aviso ativo do mesmo tipo (1 aviso ativo por sala). */
    private void validarSalasLivres(TipoAviso tipo, List<Integer> salaIds) {
        for (Integer sid : salaIds) {
            List<?> ocup = entityManager.createNativeQuery("""
                    SELECT c.NUMERO
                      FROM FRM_AVISO_ALVO al
                      JOIN FRM_AVISO_CADASTRO c ON c.ID = al.CADASTRO_ID
                     WHERE al.ALVO_TIPO = 'SALA'
                       AND al.SALA_ID = ?
                       AND c.TIPO = ?
                       AND c.STATUS = 'Ativo'
                     FETCH FIRST 1 ROW ONLY
                    """).setParameter(1, sid).setParameter(2, tipo.name()).getResultList();
            if (!ocup.isEmpty()) {
                long numero = ((Number) ocup.get(0)).longValue();
                String nome = salaRepo.findById(sid).map(s -> s.getNome()).orElse("Sala " + sid);
                throw new ServiceValidationException(
                        nome + " já possui um aviso ativo (cadastro nº " + numero + "). Desative-o antes de cadastrar outro.");
            }
        }
    }

    /** Salas com aviso ativo do tipo informado → [{sala_id, numero}]. Alimenta o bloqueio no form do admin. */
    public List<Map<String, Object>> salasOcupadas(String tipoStr) {
        TipoAviso tipo = parseTipo(tipoStr);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT al.SALA_ID, c.NUMERO
                  FROM FRM_AVISO_ALVO al
                  JOIN FRM_AVISO_CADASTRO c ON c.ID = al.CADASTRO_ID
                 WHERE al.ALVO_TIPO = 'SALA'
                   AND c.TIPO = ?
                   AND c.STATUS = 'Ativo'
                 ORDER BY al.SALA_ID
                """).setParameter(1, tipo.name()).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sala_id", ((Number) r[0]).intValue());
            m.put("numero", ((Number) r[1]).longValue());
            out.add(m);
        }
        return out;
    }

    private List<AvisoAlvo> montarAlvos(String cadastroId, AlvoTipoAviso alvoTipo,
                                        List<Integer> salaIds, List<String> operadorIds, List<String> tecnicoIds) {
        List<AvisoAlvo> alvos = new ArrayList<>();
        switch (alvoTipo) {
            case SALA -> { for (Integer sid : salaIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.SALA); a.setSalaId(sid); alvos.add(a);
            } }
            case OPERADOR -> { for (String oid : operadorIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.OPERADOR); a.setOperadorId(oid); alvos.add(a);
            } }
            case TECNICO -> { for (String tid : tecnicoIds) {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(AlvoTipoAviso.TECNICO); a.setTecnicoId(tid); alvos.add(a);
            } }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS -> {
                AvisoAlvo a = new AvisoAlvo(); a.setCadastroId(cadastroId); a.setAlvoTipo(alvoTipo); alvos.add(a);
            }
            // Inalcançável: validarAlvo já barrou. Fecha o switch para que um público sem produtor
            // nunca mais gere cadastro sem linha em FRM_AVISO_ALVO.
            case ADMIN, TODOS_ADMIN -> throw publicoNaoSuportado(alvoTipo);
            default -> throw publicoNaoSuportado(alvoTipo);
        }
        return alvos;
    }

    /** Já existe ciência desta pessoa neste cadastro? Sem sala (salaId null) usa a chave (cadastro, pessoa). */
    private boolean temCiencia(String cadastroId, Integer salaId, String pessoaId, PapelPessoa papel) {
        return switch (papel) {
            case OPERADOR -> (salaId == null
                    ? cienciaRepo.findByCadastroIdAndOperadorId(cadastroId, pessoaId)
                    : cienciaRepo.findByCadastroIdAndSalaIdAndOperadorId(cadastroId, salaId, pessoaId)).isPresent();
            case TECNICO -> (salaId == null
                    ? cienciaRepo.findByCadastroIdAndTecnicoId(cadastroId, pessoaId)
                    : cienciaRepo.findByCadastroIdAndSalaIdAndTecnicoId(cadastroId, salaId, pessoaId)).isPresent();
            case ADMIN -> cienciaRepo.findByCadastroIdAndAdminId(cadastroId, pessoaId).isPresent();
        };
    }

    private Map<String, Object> montarPayloadPendente(String cadastroId, boolean manter, TipoAviso tipo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cadastro_id", cadastroId);
        m.put("tipo", tipo.name());
        m.put("exige_ciencia", tipo.exigeCiencia());
        m.put("manter_apos_ciencia", manter);
        m.put("mensagens", mensagemRepo.findByCadastroIdOrderByOrdem(cadastroId).stream()
                .map(this::mensagemToMap).toList());
        return m;
    }

    private Map<String, Object> mensagemToMap(AvisoMensagem x) {
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("ordem", x.getOrdem());
        mm.put("texto", x.getTexto());
        return mm;
    }

    private Map<String, Object> alvoToMap(AvisoAlvo a, ResolvedorNomes nomes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alvo_tipo", a.getAlvoTipo().name());
        String desc = switch (a.getAlvoTipo()) {
            case SALA -> nomes.sala(a.getSalaId());
            case OPERADOR -> nomes.operador(a.getOperadorId());
            case TECNICO -> nomes.tecnico(a.getTecnicoId());
            case ADMIN -> nomes.admin(a.getAdminId());
            case TODOS_OPERADORES -> "Todos os operadores";
            case TODOS_TECNICOS -> "Todos os técnicos";
            case TODOS_ADMIN -> "Todos os administradores";
            case TODOS -> "Todos";
        };
        m.put("descricao", desc);
        return m;
    }

    private Map<String, Object> cienciaToMap(AvisoCiencia c, ResolvedorNomes nomes) {
        Map<String, Object> m = new LinkedHashMap<>();
        String nome;
        String papel;
        if (c.getOperadorId() != null) {
            papel = "Operador";
            nome = nomes.operador(c.getOperadorId());
        } else if (c.getTecnicoId() != null) {
            papel = "Técnico";
            nome = nomes.tecnico(c.getTecnicoId());
        } else {
            papel = "Administrador";
            nome = nomes.admin(c.getAdminId());
        }
        m.put("nome", nome);
        m.put("papel", papel);
        m.put("ciente_em", c.getCienteEm() != null ? c.getCienteEm().toString() : null);
        return m;
    }

    /**
     * Resolve nomes de alvos/ciências (sala/operador/técnico/admin) carregando cada mapa
     * CAD_SALA/PES_* no máximo 1× (lazy, só os tipos presentes) — elimina o findById por
     * alvo/ciência (Q18). Fallbacks idênticos ao anterior: "Sala &lt;id&gt;" para sala, id cru
     * para pessoas (get()==null cobre tanto ausência quanto nome nulo, como o .map().orElse()).
     */
    private final class ResolvedorNomes {
        private Map<Integer, String> salas;
        private Map<String, String> operadores, tecnicos, admins;

        String sala(Integer id) {
            if (salas == null) {
                salas = new HashMap<>();
                salaRepo.findAll().forEach(s -> salas.put(s.getId(), s.getNome()));
            }
            String n = salas.get(id);
            return n != null ? n : "Sala " + id;
        }

        String operador(String id) {
            if (operadores == null) {
                operadores = new HashMap<>();
                operadorRepo.findAll().forEach(o -> operadores.put(o.getId(), o.getNomeCompleto()));
            }
            String n = operadores.get(id);
            return n != null ? n : id;
        }

        String tecnico(String id) {
            if (tecnicos == null) {
                tecnicos = new HashMap<>();
                tecnicoRepo.findAll().forEach(t -> tecnicos.put(t.getId(), t.getNomeCompleto()));
            }
            String n = tecnicos.get(id);
            return n != null ? n : id;
        }

        String admin(String id) {
            if (admins == null) {
                admins = new HashMap<>();
                adminRepo.findAll().forEach(a -> admins.put(a.getId(), a.getNomeCompleto()));
            }
            String n = admins.get(id);
            return n != null ? n : id;
        }
    }

    private Map<String, Object> toResumo(AvisoCadastro c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("numero", c.getNumero());
        m.put("tipo", c.getTipo() != null ? c.getTipo().name() : null);
        m.put("tipo_label", c.getTipo() != null ? c.getTipo().getLabel() : null);
        m.put("permanente", Boolean.TRUE.equals(c.getPermanente()));
        m.put("duracao_dias", c.getDuracaoDias());
        m.put("manter_apos_ciencia", Boolean.TRUE.equals(c.getManterAposCiencia()));
        m.put("status", c.getStatus() != null ? c.getStatus().getValor() : null);
        m.put("criado_em", c.getCriadoEm() != null ? c.getCriadoEm().toString() : null);
        m.put("expira_em", c.getExpiraEm() != null ? c.getExpiraEm().toString() : null);
        m.put("criado_por", adminRepo.findById(c.getCriadoPorId())
                .map(a -> a.getNomeCompleto()).orElse(c.getCriadoPorId()));
        return m;
    }
}

package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoBancoSaldoRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.DashboardQueryHelper.PagedResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static br.leg.senado.nusp.service.NativeQueryUtils.textoLimitado;

/**
 * Banco de Horas do funcionário (Bloco C / E7): saldo + dias solicitáveis,
 * solicitação de folga (débito congelado — Q3), cancelamento (Q19) e a
 * listagem "Minhas Solicitações" (C-4). O saldo vem do cache de
 * PNT_BANCO_SALDO (recalculado pelo {@link SaldoAberturaService} nos eventos);
 * a criação e o cancelamento re-ancoram a pessoa na MESMA transação (Q10,
 * gotcha 3). Endpoints do funcionário resolvem o dono SEMPRE pelo principal
 * (gotcha 5) — admin com folha (SERVIDOR_PUBLICO=0) usa os mesmos endpoints.
 */
@Service
@RequiredArgsConstructor
public class BancoHorasService {

    private static final List<StatusSolicitacaoFolga> STATUS_VIVOS =
            List.of(StatusSolicitacaoFolga.PENDENTE, StatusSolicitacaoFolga.APROVADO);

    /** Teto do motivo de rejeição (F47) — o mesmo da observação da retificação (RetificacaoService). */
    private static final int MAX_MOTIVO_REJEICAO = 300;

    // Motivos de bloqueio de dia (Q12/F#4) — textos exibidos no calendário.
    private static final String MOTIVO_PASSADO   = "Dia já transcorrido";
    private static final String MOTIVO_FDS       = "Fim de semana";
    private static final String MOTIVO_PENDENTE  = "Solicitação pendente";
    private static final String MOTIVO_APROVADO  = "Folga aprovada";
    private static final String MOTIVO_FORA_MES  = "Fora do mês corrente";

    /** Mensagem única do gate de carga horária (Q17) — NULL ou fora de {30, 40}. */
    private static final String MSG_CARGA_INVALIDA =
            "Sua carga horária não está cadastrada corretamente. Procure a Gestão de Pessoas.";

    private static final Map<String, String> MS_SORT = new LinkedHashMap<>() {{
        put("data_folga", "s.DATA_FOLGA");
        put("status", "s.STATUS");
        put("deliberado_por", "adm.NOME_COMPLETO");
    }};

    /**
     * Nome do solicitante (polimórfico), resolvido na própria SQL — assim o
     * filtro de coluna, o "Buscar" e a ordenação por Nome (D-1.3/D-4.2)
     * funcionam nativamente pelo executePagedQuery, sem N+1. Usa CASE (e não
     * COALESCE) de propósito: o parseColumnNames do helper separa o selectCols
     * por vírgula — uma função com vírgulas internas quebraria a contagem de
     * colunas; o CASE não tem vírgulas de topo.
     */
    private static final String NOME_SOLICITANTE =
            "CASE s.PESSOA_TIPO WHEN 'OPERADOR' THEN po.NOME_COMPLETO " +
            "WHEN 'TECNICO' THEN pt.NOME_COMPLETO ELSE pa.NOME_COMPLETO END";

    /**
     * Colunas ordenáveis da tabela "Solicitações" do admin (D-1). A 1ª entrada
     * é a ordenação default COMPOSTA (D-4.1): pendentes primeiro, depois por
     * dia; os filtros/cliques de coluna a sobrepõem (D-4.2). Desempate estável
     * final via tiebreaker s.ID (paginação consistente).
     */
    private static final Map<String, String> SOL_ADMIN_SORT = new LinkedHashMap<>() {{
        put("padrao", "CASE s.STATUS WHEN 'PENDENTE' THEN 0 ELSE 1 END, s.DATA_FOLGA");
        put("nome", NOME_SOLICITANTE);
        put("data_folga", "s.DATA_FOLGA");
        put("status", "s.STATUS");
    }};

    private final EntityManager em;
    private final PontoBancoSaldoRepository saldoRepo;
    private final PontoSolicitacaoFolgaRepository solicitacaoRepo;
    private final PontoDiaMarcacaoRepository diaMarcacaoRepo;
    private final PontoPessoaMarcacaoRepository pessoaMarcacaoRepo;
    private final SaldoAberturaService saldoAberturaService;
    private final AvisoService avisoService;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;
    /** Relógio da aplicação ({@link br.leg.senado.nusp.config.ClockConfig}) — em produção, a zona default da JVM. */
    private final Clock clock;

    // ══ GET /api/ponto/banco ══════════════════════════════════════

    /**
     * Saldo atual + situação do mês pedido ({ano, mes} do seletor): folgas
     * APROVADAS (Q13) e os dias bloqueados para solicitação (Q12/F#4). Lê o
     * cache de PNT_BANCO_SALDO — sem linha (pessoa nunca ancorada nem com
     * pedido) o saldo é 0 e "sem folha oficial".
     */
    @Transactional(readOnly = true)
    public Map<String, Object> consultar(String pessoaId, String role, int ano, int mes) {
        String pessoaTipo = pessoaTipoDeRole(role);
        LocalDate ini = MarcacaoService.inicioMes(ano, mes);
        LocalDate fim = ini.plusMonths(1);
        int carga = cargaObrigatoria(pessoaId, pessoaTipo);   // gate Q17 — 409 bloqueia a UI

        PontoBancoSaldo saldo = saldoRepo.findByPessoaIdAndPessoaTipo(pessoaId, pessoaTipo).orElse(null);
        int saldoMin = saldo != null ? saldo.getSaldoBancoMin() : 0;
        boolean semFolhaOficial = saldo == null || saldo.getAncoraData() == null;

        List<PontoSolicitacaoFolga> vivasMes =
                solicitacaoRepo.findPorStatusNoRange(pessoaId, pessoaTipo, STATUS_VIVOS, ini, fim);
        long folgasMes = vivasMes.stream()
                .filter(s -> s.getStatus() == StatusSolicitacaoFolga.APROVADO).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saldo_min", saldoMin);
        out.put("saldo_fmt", formatarSaldo(saldoMin));
        if (semFolhaOficial) out.put("sem_folha_oficial", true);
        out.put("carga_horaria", carga);
        out.put("folgas_mes", folgasMes);
        out.put("dias_bloqueados", diasBloqueados(pessoaId, pessoaTipo, ini, vivasMes));
        return out;
    }

    // ══ POST /api/ponto/banco/solicitar ═══════════════════════════

    /**
     * Cria 1 solicitação PENDENTE por dia (C-5.3/C-5.4), TRANSACIONAL:
     * revalida cada dia contra as regras do calendário + saldo suficiente para
     * a soma, com recálculo do saldo DENTRO da transação (Q10); débito
     * congelado 360/480 pela CARGA_HORARIA na criação (Q3). Corrida de dia
     * duplicado morre na FBI UQ_PNT_SOLF_VIVA → 400 amigável (gotcha 3).
     */
    @Transactional
    public Map<String, Object> solicitar(String pessoaId, String role, Map<String, Object> body) {
        String pessoaTipo = pessoaTipoDeRole(role);
        List<LocalDate> dias = parseDias(body);
        int debitoPorDia = minutosPorDia(cargaObrigatoria(pessoaId, pessoaTipo));

        // Serializa pedidos concorrentes da MESMA pessoa (dias distintos não colidem
        // na FBI; sem o lock, dois pedidos simultâneos validariam o mesmo saldo).
        saldoRepo.lockPorPessoa(pessoaId, pessoaTipo);
        // Q10: o saldo vigente é recalculado agora, dentro da transação.
        int saldoVigente = saldoAberturaService.reancorar(pessoaId, pessoaTipo).getSaldoBancoMin();

        LocalDate hoje = LocalDate.now(clock);
        LocalDate ini = hoje.withDayOfMonth(1);
        LocalDate fim = ini.plusMonths(1);
        Bloqueios bloqueios = carregarBloqueios(pessoaId, pessoaTipo, ini, fim,
                vivasPorDia(solicitacaoRepo.findPorStatusNoRange(pessoaId, pessoaTipo, STATUS_VIVOS, ini, fim)));
        for (LocalDate d : dias) {
            String motivo = d.isBefore(ini) || !d.isBefore(fim)
                    ? MOTIVO_FORA_MES
                    : motivoBloqueio(d, hoje, bloqueios);
            if (motivo != null) {
                throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(d)
                        + " não pode ser solicitado: " + motivo + ".");
            }
        }

        long totalDebito = (long) debitoPorDia * dias.size();
        if (saldoVigente < totalDebito) {
            throw new ServiceValidationException("Saldo insuficiente: a solicitação debita "
                    + formatarSaldo((int) totalDebito) + " e o saldo atual é "
                    + formatarSaldo(saldoVigente) + ".");
        }

        List<PontoSolicitacaoFolga> novas = new ArrayList<>();
        for (LocalDate d : dias) {
            PontoSolicitacaoFolga s = new PontoSolicitacaoFolga();
            s.setPessoaId(pessoaId);
            s.setPessoaTipo(pessoaTipo);
            s.setDataFolga(d);
            s.setMinutosDebitados(debitoPorDia);
            novas.add(s);
        }
        try {
            solicitacaoRepo.saveAllAndFlush(novas);   // flush força a FBI a decidir agora
        } catch (DataIntegrityViolationException e) {
            // corrida (pedido concorrente do mesmo dia entre a validação e o insert):
            // a FBI é a autoridade final — mesma resposta amigável da validação (gotcha 3).
            throw new ServiceValidationException(
                    "Já existe solicitação em andamento para um dos dias selecionados.");
        }

        // A criação atualiza o cache (pessoa única) — os débitos novos entram na soma.
        PontoBancoSaldo atualizado = saldoAberturaService.reancorar(pessoaId, pessoaTipo);

        List<Map<String, Object>> criadas = new ArrayList<>();
        for (PontoSolicitacaoFolga s : novas) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", s.getId());
            c.put("data", s.getDataFolga().toString());
            criadas.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("criadas", criadas);
        out.put("saldo_min", atualizado.getSaldoBancoMin());
        out.put("saldo_fmt", formatarSaldo(atualizado.getSaldoBancoMin()));
        return out;
    }

    // ══ PATCH /api/ponto/banco/solicitacao/{id}/cancelar ══════════

    /**
     * Cancela uma solicitação PENDENTE do próprio dono (Q19): muda o status
     * para CANCELADO (sem delete físico) — a linha sai da soma do saldo
     * ("estorno" implícito) e libera o dia na FBI. Re-ancora o cache.
     */
    @Transactional
    public Map<String, Object> cancelar(String solicitacaoId, String pessoaId, String role) {
        String pessoaTipo = pessoaTipoDeRole(role);
        PontoSolicitacaoFolga s = solicitacaoRepo.findById(solicitacaoId)
                .orElseThrow(() -> new ServiceValidationException("Solicitação não encontrada.", HttpStatus.NOT_FOUND));
        // 403 depois do 404 (anti-enumeração); dono via principal, nunca payload (gotcha 5).
        if (!s.getPessoaId().equals(pessoaId) || !s.getPessoaTipo().equals(pessoaTipo)) {
            throw new ServiceValidationException("Acesso negado a esta solicitação.", HttpStatus.FORBIDDEN);
        }
        if (s.getStatus() != StatusSolicitacaoFolga.PENDENTE) {
            throw new ServiceValidationException("Apenas solicitações pendentes podem ser canceladas.");
        }
        s.setStatus(StatusSolicitacaoFolga.CANCELADO);
        solicitacaoRepo.save(s);
        PontoBancoSaldo saldo = saldoAberturaService.reancorar(pessoaId, pessoaTipo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("status", s.getStatus().getValor());
        out.put("saldo_min", saldo.getSaldoBancoMin());
        out.put("saldo_fmt", formatarSaldo(saldo.getSaldoBancoMin()));
        return out;
    }

    // ══ GET /api/ponto/banco/solicitacoes (+ /relatorio) ══════════

    public PagedResult listMinhasSolicitacoes(String pessoaId, String role, int page, int limit,
                                              String sort, String dir, Map<String, Object> filters) {
        return listMinhasSolicitacoes(pessoaId, role, page, limit, sort, dir, filters, false);
    }

    public PagedResult listMinhasSolicitacoes(String pessoaId, String role, int page, int limit,
                                              String sort, String dir, Map<String, Object> filters, boolean somenteDados) {
        String pessoaTipo = pessoaTipoDeRole(role);
        // Ownership do dono no fromJoins com binds via baseParams (nunca interpolados — Q13 db-plan).
        return DashboardQueryHelper.executePagedQuery(em,
                "s.ID, s.DATA_FOLGA AS data_folga, s.STATUS, " +
                "adm.NOME_COMPLETO AS deliberado_por, s.MOTIVO_REJEICAO AS motivo",
                "FROM PNT_SOLICITACAO_FOLGA s " +
                "LEFT JOIN PES_ADMINISTRADOR adm ON adm.ID = s.DELIBERADO_POR_ID " +
                "WHERE s.PESSOA_ID = ? AND s.PESSOA_TIPO = ?",
                "s.DATA_FOLGA", MS_SORT, List.of(),
                Map.of("data_folga", "s.DATA_FOLGA", "status", "s.STATUS"),
                Map.of("data_folga", "date", "status", "text"),
                page, limit, null, sort, dir, null, filters,
                "s.CRIADO_EM DESC", somenteDados, List.of(pessoaId, pessoaTipo));
    }

    // ══ Deliberação do admin (Bloco D / E8) ═══════════════════════

    // ── GET /api/admin/ponto/banco/solicitacoes (+ /relatorio) ──

    public PagedResult listSolicitacoesAdmin(String callerId, int page, int limit, String sort,
                                             String dir, String search, Map<String, Object> filters) {
        return listSolicitacoesAdmin(callerId, page, limit, sort, dir, search, filters, false);
    }

    /**
     * Fila "Solicitações" de TODOS os funcionários (D-1): Nome (COALESCE dos 3
     * tipos), Saldo (cache SALDO_BANCO_MIN), Dia e Status; filtros/busca/ordenação
     * pelo executePagedQuery, ordenação default composta D-4.1. Por linha, injeta
     * as flags {@code pode_deliberar} (T-1.2/T-1.3 para o caller — Q34) e
     * {@code atrasada} (PENDENTE com dia passado — Q11). Sem ownership: o admin vê
     * tudo (o matcher /api/admin/** já garante o papel).
     */
    public PagedResult listSolicitacoesAdmin(String callerId, int page, int limit, String sort,
                                             String dir, String search, Map<String, Object> filters,
                                             boolean somenteDados) {
        PagedResult result = DashboardQueryHelper.executePagedQuery(em,
                "s.ID, s.PESSOA_ID AS pessoa_id, s.PESSOA_TIPO AS pessoa_tipo, " +
                NOME_SOLICITANTE + " AS nome, sal.SALDO_BANCO_MIN AS saldo_min, " +
                "s.DATA_FOLGA AS data_folga, s.STATUS, " +
                "adm.NOME_COMPLETO AS deliberado_por, s.MOTIVO_REJEICAO AS motivo",
                "FROM PNT_SOLICITACAO_FOLGA s " +
                "LEFT JOIN PES_OPERADOR po ON po.ID = s.PESSOA_ID AND s.PESSOA_TIPO = 'OPERADOR' " +
                "LEFT JOIN PES_TECNICO pt ON pt.ID = s.PESSOA_ID AND s.PESSOA_TIPO = 'TECNICO' " +
                "LEFT JOIN PES_ADMINISTRADOR pa ON pa.ID = s.PESSOA_ID AND s.PESSOA_TIPO = 'ADMINISTRADOR' " +
                "LEFT JOIN PES_ADMINISTRADOR adm ON adm.ID = s.DELIBERADO_POR_ID " +
                "LEFT JOIN PNT_BANCO_SALDO sal ON sal.PESSOA_ID = s.PESSOA_ID AND sal.PESSOA_TIPO = s.PESSOA_TIPO",
                "s.DATA_FOLGA", SOL_ADMIN_SORT, List.of(NOME_SOLICITANTE),
                Map.of("nome", NOME_SOLICITANTE, "data_folga", "s.DATA_FOLGA", "status", "s.STATUS"),
                Map.of("nome", "text", "data_folga", "date", "status", "text"),
                page, limit, search, sort, dir, null, filters,
                "s.ID", somenteDados, List.of());
        if (!somenteDados) anotarDeliberacao(result.data(), callerId);
        return result;
    }

    /** Injeta {@code pode_deliberar} e {@code atrasada} por linha (T-1.2/T-1.3/Q11) — 1 leitura do servidorPublico do caller. */
    private void anotarDeliberacao(List<Map<String, Object>> rows, String callerId) {
        boolean callerSp = Boolean.TRUE.equals(
                administradorRepo.findById(callerId).map(Administrador::getServidorPublico).orElse(false));
        LocalDate hoje = LocalDate.now(clock);
        for (Map<String, Object> r : rows) {
            String pessoaId = String.valueOf(r.get("pessoa_id"));
            String pessoaTipo = String.valueOf(r.get("pessoa_tipo"));
            boolean pendente = "PENDENTE".equals(String.valueOf(r.get("status")));
            LocalDate dia = parseDataFolga(r.get("data_folga"));
            r.put("pode_deliberar", podeDeliberar(callerId, callerSp, pessoaId, pessoaTipo));
            r.put("atrasada", pendente && dia != null && dia.isBefore(hoje));
        }
    }

    /** T-1.2 (não delibera o próprio pedido) + T-1.3 (só admin SP=1 delibera pedido de admin). Q33: op/téc por qualquer admin. */
    private static boolean podeDeliberar(String callerId, boolean callerSp, String pessoaId, String pessoaTipo) {
        if (callerId.equals(pessoaId)) return false;                          // T-1.2
        return !("ADMINISTRADOR".equals(pessoaTipo) && !callerSp);            // T-1.3
    }

    /** Rows do relatório admin (Q27): o Nome já vem da SQL — só formata o Saldo em ±HH:MM. */
    public List<Map<String, Object>> enriquecerRowsParaRelatorioSolicitacoesAdmin(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rawRows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Object saldo = r.get("saldo_min");
            m.put("saldo", saldo instanceof Number n ? formatarSaldo(n.intValue()) : "--");
            rows.add(m);
        }
        return rows;
    }

    // ── POST .../solicitacao/{id}/aprovar · /rejeitar ──

    /**
     * Aprova uma solicitação PENDENTE (T-1.4: validação sempre no service): 404 →
     * 403 (T-1.2/T-1.3) → só PENDENTE. Aprovar mantém o débito na soma dos vivos
     * (o saldo não muda), mas re-ancora para reprecificar o cache; dispara aviso
     * PESSOAL ao solicitante (Q15).
     */
    @Transactional
    public Map<String, Object> aprovar(String solicitacaoId, String callerId) {
        PontoSolicitacaoFolga s = deliberavel(solicitacaoId, callerId);
        s.setStatus(StatusSolicitacaoFolga.APROVADO);
        s.setDeliberadoPorId(callerId);
        s.setDeliberadoEm(LocalDateTime.now(clock));
        s.setMotivoRejeicao(null);
        solicitacaoRepo.save(s);
        saldoAberturaService.reancorar(s.getPessoaId(), s.getPessoaTipo());
        notificarDeliberacao(s, callerId, true, null);
        return deliberacaoResposta(s);
    }

    /**
     * Rejeita uma solicitação PENDENTE com motivo obrigatório (D-3.2): a linha
     * sai da soma dos vivos — o saldo volta a subir (estorno implícito, C-5.6) —
     * e re-ancora o cache; dispara aviso PESSOAL com o motivo (Q15).
     *
     * <p>O motivo tem TETO (F47): {@code MOTIVO_REJEICAO} é VARCHAR2(1000) em bytes, e um texto
     * colado de um e-mail/norma estourava a coluna — ORA-12899 → handler genérico → 500 com um
     * toast que não dizia ao admin que a causa era o tamanho, deixando a rejeição impossível.
     */
    @Transactional
    public Map<String, Object> rejeitar(String solicitacaoId, String callerId, String motivo) {
        String m = motivo == null ? "" : motivo.strip();
        if (m.isEmpty()) {
            throw new ServiceValidationException("Informe o motivo da rejeição.");
        }
        textoLimitado(m, MAX_MOTIVO_REJEICAO, "O motivo da rejeição");
        PontoSolicitacaoFolga s = deliberavel(solicitacaoId, callerId);
        s.setStatus(StatusSolicitacaoFolga.REJEITADO);
        s.setDeliberadoPorId(callerId);
        s.setDeliberadoEm(LocalDateTime.now(clock));
        s.setMotivoRejeicao(m);
        solicitacaoRepo.save(s);
        saldoAberturaService.reancorar(s.getPessoaId(), s.getPessoaTipo());
        notificarDeliberacao(s, callerId, false, m);
        return deliberacaoResposta(s);
    }

    /** Carrega a solicitação e aplica a ordem 404 → 403 (T-1.2/T-1.3) → só PENDENTE (deliberável). */
    private PontoSolicitacaoFolga deliberavel(String solicitacaoId, String callerId) {
        PontoSolicitacaoFolga s = solicitacaoRepo.findById(solicitacaoId)
                .orElseThrow(() -> new ServiceValidationException("Solicitação não encontrada.", HttpStatus.NOT_FOUND));
        validarDeliberacao(s, callerId);
        if (s.getStatus() != StatusSolicitacaoFolga.PENDENTE) {
            throw new ServiceValidationException("Apenas solicitações pendentes podem ser deliberadas.");
        }
        return s;
    }

    /** Autorização fina da deliberação (T-1.2/T-1.3), no service — nunca só na UI (T-1.4). */
    private void validarDeliberacao(PontoSolicitacaoFolga s, String callerId) {
        if (callerId.equals(s.getPessoaId())) {                                   // T-1.2
            throw new ServiceValidationException("Você não pode deliberar o próprio pedido.", HttpStatus.FORBIDDEN);
        }
        if ("ADMINISTRADOR".equals(s.getPessoaTipo())) {                          // T-1.3
            boolean callerSp = Boolean.TRUE.equals(
                    administradorRepo.findById(callerId).map(Administrador::getServidorPublico).orElse(false));
            if (!callerSp) {
                throw new ServiceValidationException(
                        "Apenas administradores servidores públicos podem deliberar pedidos de administradores.",
                        HttpStatus.FORBIDDEN);
            }
        }
    }

    /** Aviso PESSOAL do desfecho ao solicitante (Q15/gotcha 6) — reusa {@link AvisoService#criarPessoalIndividual}. */
    private void notificarDeliberacao(PontoSolicitacaoFolga s, String adminId, boolean aprovado, String motivo) {
        PapelPessoa papel = papelDePessoaTipo(s.getPessoaTipo());
        if (papel == null) return;   // tipo já validado no fluxo; guarda contra o no-op silencioso
        String dia = ReportConfig.fmtDate(s.getDataFolga());
        String mensagem = aprovado
                ? "Sua solicitação de folga para " + dia + " foi APROVADA."
                : "Sua solicitação de folga para " + dia + " foi REJEITADA."
                        + (motivo != null && !motivo.isBlank() ? " Motivo: " + motivo.trim() + "." : "");
        SubtipoAviso subtipo = aprovado ? SubtipoAviso.SOLICITACAO_APROVADA : SubtipoAviso.SOLICITACAO_REJEITADA;
        avisoService.criarPessoalIndividual(
                List.of(new AvisoService.DestinatarioAviso(s.getPessoaId(), papel)), mensagem, adminId, subtipo);
    }

    private static PapelPessoa papelDePessoaTipo(String pessoaTipo) {
        return switch (pessoaTipo == null ? "" : pessoaTipo) {
            case "OPERADOR"      -> PapelPessoa.OPERADOR;
            case "TECNICO"       -> PapelPessoa.TECNICO;
            case "ADMINISTRADOR" -> PapelPessoa.ADMIN;
            default -> null;
        };
    }

    private Map<String, Object> deliberacaoResposta(PontoSolicitacaoFolga s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("status", s.getStatus().getValor());
        if (s.getMotivoRejeicao() != null) out.put("motivo", s.getMotivoRejeicao());
        return out;
    }

    /** DATE nativo (String {@code "yyyy-MM-dd..."}) → LocalDate; null/inválido → null. */
    private static LocalDate parseDataFolga(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.length() >= 10) s = s.substring(0, 10);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ══ Helpers ═══════════════════════════════════════════════════

    /** Q23: minutos com sinal → {@code ±HH:MM} (ex.: {@code +26:10}, {@code -02:03}, {@code +00:00}). */
    public static String formatarSaldo(int minutos) {
        int abs = Math.abs(minutos);
        return (minutos < 0 ? "-" : "+") + String.format("%02d:%02d", abs / 60, abs % 60);
    }

    /** Papel do principal → PESSOA_TIPO das tabelas PNT_* (dono sempre via principal — gotcha 5). */
    private String pessoaTipoDeRole(String role) {
        return switch (role == null ? "" : role) {
            case "operador"      -> "OPERADOR";
            case "tecnico"       -> "TECNICO";
            case "administrador" -> "ADMINISTRADOR";
            default -> throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);
        };
    }

    /**
     * CARGA_HORARIA da pessoa, exigindo valor válido (30 ou 40 — únicas
     * jornadas do quadro): NULL ou outro valor bloqueia com a mensagem da
     * Gestão de Pessoas (Q17) em 409 — nunca assumir jornada.
     */
    private int cargaObrigatoria(String pessoaId, String pessoaTipo) {
        Integer carga = switch (pessoaTipo) {
            case "OPERADOR" -> operadorRepo.findById(pessoaId).map(Operador::getCargaHoraria).orElse(null);
            case "TECNICO"  -> tecnicoRepo.findById(pessoaId).map(Tecnico::getCargaHoraria).orElse(null);
            default         -> administradorRepo.findById(pessoaId).map(Administrador::getCargaHoraria).orElse(null);
        };
        if (carga == null || (carga != 30 && carga != 40)) {
            throw new ServiceValidationException(MSG_CARGA_INVALIDA, HttpStatus.CONFLICT);
        }
        return carga;
    }

    /** Débito de 1 dia de folga (Q3): jornada 30h → 360 min (6h); 40h → 480 min (8h). */
    private static int minutosPorDia(int carga) {
        return carga == 30 ? 360 : 480;
    }

    /** {@code dias: ["YYYY-MM-DD", ...]} do body — não-vazia, datas ISO, sem repetição. */
    private List<LocalDate> parseDias(Map<String, Object> body) {
        Object raw = body != null ? body.get("dias") : null;
        if (!(raw instanceof List<?> lista) || lista.isEmpty()) {
            throw new ServiceValidationException("Informe ao menos um dia em 'dias'.");
        }
        List<LocalDate> dias = new ArrayList<>();
        Set<LocalDate> vistos = new HashSet<>();
        for (Object o : lista) {
            String texto = o == null ? "" : o.toString().strip();
            LocalDate d;
            try {
                d = LocalDate.parse(texto);   // ISO YYYY-MM-DD (gotcha 4)
            } catch (Exception e) {
                throw new ServiceValidationException("Data inválida (use AAAA-MM-DD): " + texto);
            }
            if (!vistos.add(d)) {
                throw new ServiceValidationException("Dia repetido na solicitação: " + ReportConfig.fmtDate(d) + ".");
            }
            dias.add(d);
        }
        return dias;
    }

    /** Marcações (globais + da pessoa) e pedidos vivos do range, indexados por dia. */
    private record Bloqueios(Map<LocalDate, TipoDiaMarcacao> globais,
                             Map<LocalDate, TipoPessoaMarcacao> pessoais,
                             Map<LocalDate, StatusSolicitacaoFolga> vivas) {}

    private Bloqueios carregarBloqueios(String pessoaId, String pessoaTipo, LocalDate ini, LocalDate fim,
                                        Map<LocalDate, StatusSolicitacaoFolga> vivas) {
        Map<LocalDate, TipoDiaMarcacao> globais = new HashMap<>();
        for (PontoDiaMarcacao m : diaMarcacaoRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim)) {
            globais.put(m.getData(), m.getTipo());
        }
        Map<LocalDate, TipoPessoaMarcacao> pessoais = new HashMap<>();
        for (PontoPessoaMarcacao m : pessoaMarcacaoRepo
                .findByPessoaIdAndPessoaTipoAndDataGreaterThanEqualAndDataLessThan(pessoaId, pessoaTipo, ini, fim)) {
            pessoais.put(m.getData(), m.getTipo());
        }
        return new Bloqueios(globais, pessoais, vivas);
    }

    private Map<LocalDate, StatusSolicitacaoFolga> vivasPorDia(List<PontoSolicitacaoFolga> vivas) {
        Map<LocalDate, StatusSolicitacaoFolga> porDia = new HashMap<>();
        for (PontoSolicitacaoFolga s : vivas) porDia.put(s.getDataFolga(), s.getStatus());
        return porDia;
    }

    /**
     * Motivo de bloqueio do dia (Q12/F#4), na ordem do plano: passado/hoje →
     * fim de semana → marcação global → marcação pessoa-dia → pedido vivo.
     * {@code null} = dia solicitável.
     */
    private String motivoBloqueio(LocalDate d, LocalDate hoje, Bloqueios b) {
        if (!d.isAfter(hoje)) return MOTIVO_PASSADO;
        DayOfWeek dw = d.getDayOfWeek();
        if (dw == DayOfWeek.SATURDAY || dw == DayOfWeek.SUNDAY) return MOTIVO_FDS;
        TipoDiaMarcacao global = b.globais().get(d);
        if (global != null) return global.getRotulo();
        TipoPessoaMarcacao pessoal = b.pessoais().get(d);
        if (pessoal != null) return pessoal.getRotulo();
        StatusSolicitacaoFolga viva = b.vivas().get(d);
        if (viva == StatusSolicitacaoFolga.PENDENTE) return MOTIVO_PENDENTE;
        if (viva == StatusSolicitacaoFolga.APROVADO) return MOTIVO_APROVADO;
        return null;
    }

    /**
     * Dias bloqueados do mês pedido, com motivo (para o calendário desabilitar
     * e explicar). Mês diferente do corrente: bloqueia o mês inteiro (Q12 —
     * só dias úteis futuros do MÊS CORRENTE são solicitáveis); as folgas
     * aprovadas seguem visíveis pela contagem/tabela.
     */
    private List<Map<String, Object>> diasBloqueados(String pessoaId, String pessoaTipo,
                                                     LocalDate ini, List<PontoSolicitacaoFolga> vivasMes) {
        LocalDate hoje = LocalDate.now(clock);
        LocalDate fim = ini.plusMonths(1);
        List<Map<String, Object>> out = new ArrayList<>();

        boolean mesCorrente = ini.getYear() == hoje.getYear() && ini.getMonth() == hoje.getMonth();
        if (!mesCorrente) {
            for (LocalDate d = ini; d.isBefore(fim); d = d.plusDays(1)) {
                out.add(diaBloqueado(d, MOTIVO_FORA_MES));
            }
            return out;
        }

        Bloqueios bloqueios = carregarBloqueios(pessoaId, pessoaTipo, ini, fim, vivasPorDia(vivasMes));

        for (LocalDate d = ini; d.isBefore(fim); d = d.plusDays(1)) {
            String motivo = motivoBloqueio(d, hoje, bloqueios);
            if (motivo != null) out.add(diaBloqueado(d, motivo));
        }
        return out;
    }

    private Map<String, Object> diaBloqueado(LocalDate d, String motivo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", d.toString());   // YYYY-MM-DD (gotcha 4)
        m.put("motivo", motivo);
        return m;
    }
}

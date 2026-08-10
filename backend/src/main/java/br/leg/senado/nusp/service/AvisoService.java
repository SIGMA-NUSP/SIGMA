package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.AvisoAlvo;
import br.leg.senado.nusp.entity.AvisoCadastro;
import br.leg.senado.nusp.entity.AvisoCiencia;
import br.leg.senado.nusp.entity.AvisoMensagem;
import br.leg.senado.nusp.entity.EscalaOperador;
import br.leg.senado.nusp.entity.EscalaSemanal;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.enums.AlvoTipoAviso;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.RotuloAviso;
import br.leg.senado.nusp.enums.StatusAviso;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.enums.TipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final EscalaSemanalRepository escalaRepo;
    private final EscalaOperadorRepository escalaOpRepo;
    private final AvisoCienciaWriter cienciaWriter;
    private final EntityManager entityManager;

    private static final int MAX_MENSAGENS = 10;
    private static final int MAX_DURACAO_DIAS = 30;

    /**
     * Seletor de alvo especial do card "Pessoal" (modo "Pessoas específicas"): não é um valor de
     * {@link AlvoTipoAviso} (não se persiste), mas um sinal de que o payload traz listas MISTAS
     * (operador + técnico + admin) num único cadastro PESSOAL. Tratado antes de {@link #parseAlvoTipo}.
     */
    private static final String MODO_PESSOAS = "PESSOAS";

    /**
     * Payload de criação (montado pelo controller a partir do JSON snake_case). {@code exigeCiencia}
     * nulo = o form não ofereceu a escolha (tipo sem controle ou payload antigo) → vale o padrão do
     * tipo.
     */
    public record CriarAvisoRequest(
            String tipo,
            Boolean permanente,
            Integer duracaoDias,
            Boolean manterAposCiencia,
            List<String> mensagens,
            String alvoTipo,
            List<Integer> salaIds,
            List<String> operadorIds,
            List<String> tecnicoIds,
            List<String> adminIds,
            Long escalaId,
            Boolean exigeCiencia) {}

    // ═══ Cadastro (admin) ═══════════════════════════════════════

    @Transactional
    public Map<String, Object> criar(CriarAvisoRequest req, String criadoPorId) {
        TipoAviso tipo = parseTipo(req.tipo());
        List<String> mensagens = validarMensagens(req.mensagens());

        // Ramo ESCALA: vínculo à escala, alvo SALA nos plenários dela, sempre permanente (§5).
        if (tipo == TipoAviso.ESCALA) return criarEscala(req, mensagens, criadoPorId);
        // Ramo AGENDA: popup nas telas de agenda para todos os operadores/técnicos, permanente (§6).
        if (tipo == TipoAviso.AGENDA) return criarAgenda(req, mensagens, criadoPorId);
        // Card "Pessoal" — modo "Pessoas específicas": listas MISTAS (op/téc/adm) num só cadastro PESSOAL (§7.2).
        if (MODO_PESSOAS.equalsIgnoreCase(req.alvoTipo())) return criarPessoas(req, mensagens, criadoPorId);

        // ── Ciência e vigência (permanente/duração/expira + manter) ──
        boolean exigeCiencia = resolverExigeCiencia(tipo, req.exigeCiencia());
        Vigencia vig = resolverVigencia(req, exigeCiencia);

        // ── Público (alvo) ──
        AlvoTipoAviso alvoTipo = parseAlvoTipo(req.alvoTipo());
        // Deduplica para não gerar linhas de alvo repetidas (sem UNIQUE no schema).
        List<Integer> salaIds = distinctInt(req.salaIds());
        List<String> operadorIds = distinctStr(req.operadorIds());
        List<String> tecnicoIds = distinctStr(req.tecnicoIds());
        List<String> adminIds = distinctStr(req.adminIds());
        validarAlvo(alvoTipo, salaIds, operadorIds, tecnicoIds, adminIds);

        // Regra: no máximo 1 aviso ativo por sala (para o mesmo tipo).
        if (alvoTipo == AlvoTipoAviso.SALA) validarSalasLivres(tipo, salaIds);

        // ── Autor (FK) ──
        validarAutor(criadoPorId);

        // ── Cadastro ──
        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(tipo);
        cad.setSubtipo(subtipoDeGrupo(tipo, alvoTipo));  // GRUPO_* p/ GERAL+coletivo; nulo p/ Verificação (§2)
        cad.setPermanente(vig.permanente());
        cad.setDuracaoDias(vig.duracaoDias());
        cad.setManterAposCiencia(vig.manter());
        cad.setExigeCiencia(exigeCiencia);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(vig.expiraEm());
        cad = cadastroRepo.save(cad);

        gravarMensagens(cad.getId(), mensagens);

        // ── Alvos ──
        List<AvisoAlvo> alvos = montarAlvos(cad.getId(), alvoTipo, salaIds, operadorIds, tecnicoIds, adminIds);
        alvoRepo.saveAll(alvos);

        log.info("Aviso cadastro #{} criado (tipo={}, {} mensagens, alvo={}, {} alvo(s)) por {}",
                cad.getNumero(), tipo, mensagens.size(), alvoTipo, alvos.size(), criadoPorId);
        return toResumo(cad);
    }

    // ═══ Aviso de Agenda (§6) e card Pessoal (§7) ═══════════════

    /**
     * Ramo AGENDA do {@link #criar}. Popup nas telas de Agenda Legislativa para TODOS os operadores e
     * técnicos (alvo coletivo TODOS, que o {@code buscarPendentes} já resolve; admins não veem). Sempre
     * permanente e sem duração — a vigência é POR USUÁRIO (o "visto", §6.2), não temporal. Sem ciência.
     */
    private Map<String, Object> criarAgenda(CriarAvisoRequest req, List<String> mensagens, String criadoPorId) {
        validarAutor(criadoPorId);

        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(TipoAviso.AGENDA);
        cad.setSubtipo(SubtipoAviso.AGENDA);
        cad.setPermanente(true);          // vigência por usuário (visto); sem duração/expira
        cad.setDuracaoDias(null);
        cad.setExigeCiencia(false);       // AGENDA nunca exige ciência — o form nem oferece a escolha
        cad.setManterAposCiencia(false);  // sem ciência, não há o que manter
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(null);
        cad = cadastroRepo.save(cad);

        gravarMensagens(cad.getId(), mensagens);

        List<AvisoAlvo> alvos = montarAlvos(cad.getId(), AlvoTipoAviso.TODOS, List.of(), List.of(), List.of(), List.of());
        alvoRepo.saveAll(alvos);

        log.info("Aviso AGENDA #{} criado ({} mensagens) por {}", cad.getNumero(), mensagens.size(), criadoPorId);
        return toResumo(cad);
    }

    /**
     * Card "Pessoal", modo "Pessoas específicas" (§7.2): um único cadastro PESSOAL com destinatários
     * MISTOS — operadores, técnicos e/ou administradores selecionados por nome. É o espelho, via form,
     * do que {@link #criarPessoalIndividual} grava internamente na publicação de folha; a diferença é
     * que aqui cada pessoa é revalidada (o form pode enviar id inválido). Ciência escolhida no form
     * (default ligada), permanente ou com duração, "manter após ciência" opcional. NÃO persiste o
     * seletor "PESSOAS" (não é alvoTipo).
     */
    private Map<String, Object> criarPessoas(CriarAvisoRequest req, List<String> mensagens, String criadoPorId) {
        boolean exigeCiencia = resolverExigeCiencia(TipoAviso.PESSOAL, req.exigeCiencia());
        Vigencia vig = resolverVigencia(req, exigeCiencia);

        List<String> operadorIds = distinctStr(req.operadorIds());
        List<String> tecnicoIds = distinctStr(req.tecnicoIds());
        List<String> adminIds = distinctStr(req.adminIds());
        if (operadorIds.isEmpty() && tecnicoIds.isEmpty() && adminIds.isEmpty())
            throw new ServiceValidationException("Selecione ao menos um destinatário.");
        for (String oid : operadorIds)
            operadorRepo.findById(oid).orElseThrow(() ->
                    new ServiceValidationException("Operador inválido: " + oid, HttpStatus.NOT_FOUND));
        for (String tid : tecnicoIds)
            tecnicoRepo.findById(tid).orElseThrow(() ->
                    new ServiceValidationException("Técnico inválido: " + tid, HttpStatus.NOT_FOUND));
        for (String aid : adminIds)
            adminRepo.findById(aid).orElseThrow(() ->
                    new ServiceValidationException("Administrador inválido: " + aid, HttpStatus.NOT_FOUND));

        validarAutor(criadoPorId);

        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(TipoAviso.PESSOAL);
        cad.setSubtipo(SubtipoAviso.PESSOAL);
        cad.setPermanente(vig.permanente());
        cad.setDuracaoDias(vig.duracaoDias());
        cad.setManterAposCiencia(vig.manter());
        cad.setExigeCiencia(exigeCiencia);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(vig.expiraEm());
        cad = cadastroRepo.save(cad);

        gravarMensagens(cad.getId(), mensagens);

        List<AvisoAlvo> alvos = montarAlvosMistos(cad.getId(), operadorIds, tecnicoIds, adminIds);
        alvoRepo.saveAll(alvos);

        log.info("Aviso PESSOAL (form) #{} criado ({} destinatário(s): {} op, {} téc, {} adm) por {}",
                cad.getNumero(), alvos.size(), operadorIds.size(), tecnicoIds.size(), adminIds.size(), criadoPorId);
        return toResumo(cad);
    }

    /**
     * Pessoas do multi-select do card Pessoal (§7.1): operadores + técnicos + administradores no
     * shape {@code {id, nome, tipo}}, em ordem alfabética pt-BR (F30). Endpoint próprio de Avisos —
     * existe para o form não depender do módulo de Ponto ({@code /api/admin/ponto/pessoas}), cuja
     * lista interna ainda carrega a ordenação de matching de folha (nome mais longo primeiro).
     */
    public List<Map<String, Object>> listarPessoas() {
        List<Map<String, Object>> out = new ArrayList<>();
        operadorRepo.findAll().forEach(o -> out.add(pessoaToMap(o.getId(), o.getNomeCompleto(), "OPERADOR")));
        tecnicoRepo.findAll().forEach(t -> out.add(pessoaToMap(t.getId(), t.getNomeCompleto(), "TECNICO")));
        adminRepo.findAll().forEach(a -> out.add(pessoaToMap(a.getId(), a.getNomeCompleto(), "ADMINISTRADOR")));
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("nome")), NativeQueryUtils.ORDEM_TEXTO_PT_BR));
        return out;
    }

    private static Map<String, Object> pessoaToMap(String id, String nome, String tipo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("nome", nome);
        m.put("tipo", tipo);
        return m;
    }

    // ═══ Aviso de Escala (§5) ═══════════════════════════════════

    /**
     * Ramo ESCALA do {@link #criar}. O aviso se vincula a uma escala (ESCALA_ID) e tem como alvo os
     * PLENÁRIOS dela (linhas de alvo SALA); o destinatário efetivo (os operadores escalados) e a
     * vigência (Pendente/Ativo/Expirado) são derivados da escala na hora de exibir/listar, não aqui.
     * Sempre permanente (sem duração): a janela é o período da escala. Trava de 1 cadastro por escala.
     */
    private Map<String, Object> criarEscala(CriarAvisoRequest req, List<String> mensagens, String criadoPorId) {
        validarAutor(criadoPorId);

        Long escalaId = req.escalaId();
        if (escalaId == null) throw new ServiceValidationException("Selecione a escala do aviso.");
        escalaRepo.findById(escalaId).orElseThrow(() ->
                new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));

        // "Plenário numerado" = plenário com operador vinculado NAQUELA escala (§9, decisão 9).
        Set<Integer> plenariosDaEscala = plenariosDaEscala(escalaId);
        if (plenariosDaEscala.isEmpty())
            throw new ServiceValidationException("A escala selecionada não tem operadores em nenhum plenário.");

        List<Integer> salaIds = (req.salaIds() == null ? List.<Integer>of() : req.salaIds()).stream().distinct().toList();
        if (salaIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um local.");
        for (Integer sid : salaIds)
            if (!plenariosDaEscala.contains(sid))
                throw new ServiceValidationException("Local fora da escala selecionada: " + sid + ".");

        // 1 cadastro por escala (análogo ao "1 aviso ativo por sala") — conta os não-desativados.
        Long ocupadaPor = numeroCadastroEscalaAtivo(escalaId);
        if (ocupadaPor != null)
            throw new ServiceValidationException("Só é permitido um aviso por escala.");

        boolean exigeCiencia = resolverExigeCiencia(TipoAviso.ESCALA, req.exigeCiencia());
        boolean manter = resolverManter(req.manterAposCiencia(), exigeCiencia);

        AvisoCadastro cad = new AvisoCadastro();
        cad.setNumero(proximoNumeroCadastro());
        cad.setTipo(TipoAviso.ESCALA);
        cad.setSubtipo(SubtipoAviso.ESCALA);
        cad.setEscalaId(escalaId);
        cad.setPermanente(true);          // vigência = período da escala; sem duração/expira
        cad.setDuracaoDias(null);
        cad.setManterAposCiencia(manter);
        cad.setExigeCiencia(exigeCiencia);
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setExpiraEm(null);
        cad = cadastroRepo.save(cad);

        gravarMensagens(cad.getId(), mensagens);

        List<AvisoAlvo> alvos = montarAlvos(cad.getId(), AlvoTipoAviso.SALA, salaIds, List.of(), List.of(), List.of());
        alvoRepo.saveAll(alvos);

        log.info("Aviso ESCALA #{} criado (escala={}, {} plenário(s), manter={}) por {}",
                cad.getNumero(), escalaId, salaIds.size(), manter, criadoPorId);
        return toResumo(cad);
    }

    /**
     * Escalas atual e futuras para o painel do aviso de Escala: cada uma com o período, a ocupação
     * (número do cadastro de aviso não-desativado que a trava, ou nulo) e os plenários com operador
     * vinculado. É a fonte tanto do dropdown de escalas quanto do multi-select de plenários (§5.1).
     */
    public List<Map<String, Object>> escalasDisponiveis() {
        List<EscalaSemanal> escalas = escalaRepo.findAtualEFuturas(LocalDate.now());
        Map<Long, Long> ocupacao = ocupacaoPorEscala();
        Map<Integer, String> nomeSala = salaRepo.findAll().stream()
                .collect(Collectors.toMap(Sala::getId, Sala::getNome));

        List<Map<String, Object>> out = new ArrayList<>();
        for (EscalaSemanal e : escalas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("data_inicio", e.getDataInicio().toString());
            m.put("data_fim", e.getDataFim().toString());
            m.put("cadastro_numero", ocupacao.get(e.getId()));   // nulo = escala livre
            List<Map<String, Object>> plenarios = escalaOpRepo.findByEscalaId(e.getId()).stream()
                    .map(EscalaOperador::getSalaId).filter(Objects::nonNull).distinct().sorted()
                    .map(sid -> {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("sala_id", sid);
                        p.put("sala_nome", nomeSala.getOrDefault(sid, "Sala " + sid));
                        return p;
                    }).toList();
            m.put("plenarios", plenarios);
            out.add(m);
        }
        return out;
    }

    /** Plenários (sala_ids distintos) com operador vinculado na escala. */
    private Set<Integer> plenariosDaEscala(Long escalaId) {
        return escalaOpRepo.findByEscalaId(escalaId).stream()
                .map(EscalaOperador::getSalaId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** Número do cadastro de aviso ESCALA não-desativado desta escala (a trava 1-por-escala), ou nulo. */
    private Long numeroCadastroEscalaAtivo(Long escalaId) {
        List<?> r = entityManager.createNativeQuery(
                "SELECT NUMERO FROM FRM_AVISO_CADASTRO "
              + " WHERE TIPO = 'ESCALA' AND ESCALA_ID = ? AND STATUS <> 'Desativado' "
              + " FETCH FIRST 1 ROW ONLY")
                .setParameter(1, escalaId).getResultList();
        return r.isEmpty() ? null : ((Number) r.get(0)).longValue();
    }

    /** escala_id → número do cadastro de aviso não-desativado (ocupação de todas as escalas, 1 query). */
    private Map<Long, Long> ocupacaoPorEscala() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT ESCALA_ID, NUMERO FROM FRM_AVISO_CADASTRO "
              + " WHERE TIPO = 'ESCALA' AND STATUS <> 'Desativado' AND ESCALA_ID IS NOT NULL").getResultList();
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return m;
    }

    /**
     * Exclusão FÍSICA dos cadastros de aviso vinculados a uma escala (F59): chamada por
     * {@link EscalaSemanalService#excluirEscala} ANTES de apagar a escala — a FK ESCALA_ID barraria
     * o delete. O delete do cadastro leva mensagens/alvos/ciências por cascade do banco.
     */
    @Transactional
    public void excluirPorEscala(Long escalaId) {
        for (AvisoCadastro cad : cadastroRepo.findByEscalaId(escalaId)) {
            cadastroRepo.delete(cad);   // ON DELETE CASCADE leva mensagens/alvos/ciências
            log.info("Aviso ESCALA #{} excluído junto com a escala {}", cad.getNumero(), escalaId);
        }
    }

    @Transactional
    public void desativar(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Comunicação não encontrada.", HttpStatus.NOT_FOUND));
        if (cad.getStatus() == StatusAviso.DESATIVADO)
            throw new ServiceValidationException("Aviso já está desativado.");
        cad.setStatus(StatusAviso.DESATIVADO);
        cad.setDesativadoEm(LocalDateTime.now());
        cadastroRepo.save(cad);
        log.info("Aviso cadastro #{} desativado", cad.getNumero());
    }

    // ═══ Criação programática (ex.: publicação de folha de ponto) ═══

    /**
     * Destinatário individual de um aviso: a pessoa, o papel por que ela é alcançada e — quando há —
     * o complemento que só ela lê, depois das mensagens comuns do cadastro.
     *
     * <p>O complemento faz parte da identidade do destinatário: a mesma pessoa com complementos
     * diferentes são objetos diferentes. Quem agrupa destinatários em conjunto deve chavear pela
     * forma sem complemento, sob pena de a mesma pessoa entrar duas vezes.
     */
    public record DestinatarioAviso(String pessoaId, PapelPessoa papel, String complemento) {

        /** Destinatário sem nada dirigido só a ele — a forma comum. */
        public DestinatarioAviso(String pessoaId, PapelPessoa papel) {
            this(pessoaId, papel, null);
        }
    }

    /**
     * Aviso PESSOAL sem proveniência de lote — é o caminho do desfecho de folga do banco de horas
     * (subtipo {@code SOLICITACAO_APROVADA}/{@code SOLICITACAO_REJEITADA}) e de qualquer outro futuro
     * que não nasça de uma publicação. {@code ORIGEM_LOTE_ID} fica NULL, e é isso que mantém estes
     * avisos FORA da exclusão de um lote (F59): ela só apaga o que a publicação marcou como seu.
     */
    @Transactional
    public void criarPessoalIndividual(List<DestinatarioAviso> destinatarios, String mensagem,
                                       String criadoPorId, SubtipoAviso subtipo) {
        criarPessoalIndividual(destinatarios, mensagem, criadoPorId, subtipo, null);
    }

    /**
     * Cria um aviso PESSOAL (permanente, some após a ciência) com um alvo individual por
     * destinatário (operador/técnico/admin) e a mensagem informada — comum a todos eles. O que muda
     * de uma pessoa para outra é o complemento do alvo dela, que ela lê logo depois, na mesma janela.
     *
     * <p>A mensagem em branco é recusada antes de qualquer leitura, porque um aviso sem texto não
     * teria o que dizer a quem o recebesse.
     *
     * <p>Os IDs vêm de vínculo interno confiável (integridade garantida pelas FKs), por isso não
     * revalida cada pessoa como o cadastro do form admin. Sem destinatários válidos, é no-op.
     *
     * <p>{@code subtipo} é o código do qual o popup e a tabela do admin derivam os rótulos; o texto
     * da mensagem não depende dele.
     *
     * <p>{@code origemLoteId} é a PROVENIÊNCIA: o lote cuja publicação criou este aviso. Excluir
     * aquele lote apaga exatamente os cadastros marcados com ele — e nenhum outro. {@code null} para
     * as origens que não são uma publicação.
     */
    @Transactional
    public void criarPessoalIndividual(List<DestinatarioAviso> destinatarios, String mensagem,
                                       String criadoPorId, SubtipoAviso subtipo, String origemLoteId) {
        if (mensagem == null || mensagem.isBlank())
            throw new ServiceValidationException("Mensagem do aviso é obrigatória.");
        criarPessoalIndividual(destinatarios, List.of(mensagem), criadoPorId, subtipo, origemLoteId);
    }

    /**
     * Variante com mais de um texto: o desfecho de uma solicitação parcialmente aprovada diz duas
     * coisas — o que foi aprovado e o que foi rejeitado —, e cada uma delas é lida na sua caixa.
     */
    @Transactional
    public void criarPessoalIndividual(List<DestinatarioAviso> destinatarios, List<String> mensagens,
                                       String criadoPorId, SubtipoAviso subtipo, String origemLoteId) {
        if (mensagens == null || mensagens.isEmpty())
            throw new ServiceValidationException("Mensagem do aviso é obrigatória.");
        List<String> textos = validarMensagens(mensagens);
        validarAutor(criadoPorId);

        // Dedup por (papel, pessoa) — o primeiro complemento da pessoa é o que vale; ignora entradas incompletas.
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
        cad.setExigeCiencia(true);        // o destinatário precisa confirmar que leu
        cad.setManterAposCiencia(false);  // some quando a pessoa marca ciência
        cad.setStatus(StatusAviso.ATIVO);
        cad.setCriadoPorId(criadoPorId);
        cad.setSubtipo(subtipo);             // de onde saem a categoria e o contexto exibidos
        cad.setOrigemLoteId(origemLoteId);   // NULL fora da publicação de folha
        cad = cadastroRepo.save(cad);

        gravarMensagens(cad.getId(), textos);

        List<AvisoAlvo> alvos = new ArrayList<>();
        for (DestinatarioAviso d : validos) {
            AvisoAlvo a = new AvisoAlvo();
            a.setCadastroId(cad.getId());
            switch (d.papel()) {
                case OPERADOR -> { a.setAlvoTipo(AlvoTipoAviso.OPERADOR); a.setOperadorId(d.pessoaId()); }
                case TECNICO  -> { a.setAlvoTipo(AlvoTipoAviso.TECNICO);  a.setTecnicoId(d.pessoaId()); }
                case ADMIN    -> { a.setAlvoTipo(AlvoTipoAviso.ADMIN);    a.setAdminId(d.pessoaId()); }
            }
            // Complemento em branco é o mesmo que nenhum: a gravação não distingue os dois.
            a.setComplemento(d.complemento() == null || d.complemento().isBlank() ? null : d.complemento().trim());
            alvos.add(a);
        }
        alvoRepo.saveAll(alvos);
        log.info("Aviso PESSOAL #{} criado com {} destinatário(s) por {}", cad.getNumero(), alvos.size(), criadoPorId);
    }

    /**
     * Encerra as comunicações das folhas que saíram de cena. Publicar uma folha por cima de outra
     * deixa a comunicação da antiga falando de um documento que o dono não alcança mais — e o popup
     * dela continuaria pedindo ciência de algo que não existe.
     *
     * <p>Recebe, por lote de origem, quem teve a folha daquele lote substituída. Cada cadastro
     * nascido daquela publicação é olhado uma vez:
     *
     * <ul>
     *   <li>nenhum destinatário atingido → fica como está;</li>
     *   <li>todos atingidos → desativado;</li>
     *   <li>parte atingida → desativado, e um cadastro <b>remanescente</b> nasce com os
     *       destinatários que não foram substituídos, carregando as mesmas mensagens, o complemento
     *       de cada um e as ciências já registradas — quem já leu não vê o popup de novo, quem não
     *       leu continua devendo. O antigo fica no histórico do admin.</li>
     * </ul>
     */
    @Transactional
    public void encerrarComunicacoesDeFolhasSubstituidas(Map<String, Set<String>> pessoasPorLoteOrigem) {
        if (pessoasPorLoteOrigem == null || pessoasPorLoteOrigem.isEmpty()) return;
        for (Map.Entry<String, Set<String>> origem : pessoasPorLoteOrigem.entrySet()) {
            for (AvisoCadastro cad : cadastroRepo.findByOrigemLoteId(origem.getKey())) {
                if (cad.getStatus() != StatusAviso.ATIVO) continue;
                List<AvisoAlvo> alvos = alvoRepo.findByCadastroId(cad.getId());
                List<AvisoAlvo> remanescentes = alvos.stream()
                        .filter(a -> !origem.getValue().contains(pessoaDoAlvo(a)))
                        .toList();
                if (remanescentes.size() == alvos.size()) continue;   // ninguém deste cadastro foi substituído

                cad.setStatus(StatusAviso.DESATIVADO);
                cad.setDesativadoEm(LocalDateTime.now());
                cadastroRepo.save(cad);
                log.info("Aviso cadastro #{} desativado: folha substituída", cad.getNumero());
                if (!remanescentes.isEmpty()) criarRemanescente(cad, remanescentes);
            }
        }
    }

    /** A pessoa de um alvo individual; nulo nos alvos coletivos, que não nomeiam ninguém. */
    private static String pessoaDoAlvo(AvisoAlvo a) {
        if (a.getOperadorId() != null) return a.getOperadorId();
        if (a.getTecnicoId() != null) return a.getTecnicoId();
        return a.getAdminId();
    }

    /**
     * Recria o cadastro só com quem sobrou. Mantém o vínculo com o lote de origem — a exclusão
     * daquele lote continua levando embora tudo o que nasceu dele — e copia as ciências, para que
     * ninguém receba de novo o que já leu. Nascendo com todos já cientes, encerra-se de imediato.
     */
    private void criarRemanescente(AvisoCadastro antigo, List<AvisoAlvo> remanescentes) {
        AvisoCadastro novo = new AvisoCadastro();
        novo.setNumero(proximoNumeroCadastro());
        novo.setTipo(antigo.getTipo());
        novo.setSubtipo(antigo.getSubtipo());
        novo.setPermanente(antigo.getPermanente());
        novo.setDuracaoDias(antigo.getDuracaoDias());
        novo.setManterAposCiencia(antigo.getManterAposCiencia());
        novo.setExigeCiencia(antigo.getExigeCiencia());
        novo.setStatus(StatusAviso.ATIVO);
        novo.setCriadoPorId(antigo.getCriadoPorId());
        novo.setExpiraEm(antigo.getExpiraEm());
        novo.setEscalaId(antigo.getEscalaId());
        novo.setOrigemLoteId(antigo.getOrigemLoteId());
        novo = cadastroRepo.save(novo);

        List<AvisoMensagem> mensagens = new ArrayList<>();
        for (AvisoMensagem m : mensagemRepo.findByCadastroIdOrderByOrdem(antigo.getId())) {
            AvisoMensagem copia = new AvisoMensagem();
            copia.setCadastroId(novo.getId());
            copia.setOrdem(m.getOrdem());
            copia.setTexto(m.getTexto());
            mensagens.add(copia);
        }
        mensagemRepo.saveAll(mensagens);

        Set<String> pessoas = new HashSet<>();
        List<AvisoAlvo> alvos = new ArrayList<>();
        for (AvisoAlvo a : remanescentes) {
            AvisoAlvo copia = new AvisoAlvo();
            copia.setCadastroId(novo.getId());
            copia.setAlvoTipo(a.getAlvoTipo());
            copia.setSalaId(a.getSalaId());
            copia.setOperadorId(a.getOperadorId());
            copia.setTecnicoId(a.getTecnicoId());
            copia.setAdminId(a.getAdminId());
            copia.setComplemento(a.getComplemento());
            alvos.add(copia);
            if (pessoaDoAlvo(a) != null) pessoas.add(pessoaDoAlvo(a));
        }
        alvoRepo.saveAll(alvos);

        List<AvisoCiencia> ciencias = new ArrayList<>();
        for (AvisoCiencia c : cienciaRepo.findByCadastroIdOrderByCienteEm(antigo.getId())) {
            String pessoa = c.getOperadorId() != null ? c.getOperadorId()
                    : c.getTecnicoId() != null ? c.getTecnicoId() : c.getAdminId();
            if (pessoa == null || !pessoas.contains(pessoa)) continue;
            AvisoCiencia copia = new AvisoCiencia();
            copia.setCadastroId(novo.getId());
            copia.setSalaId(c.getSalaId());
            copia.setOperadorId(c.getOperadorId());
            copia.setTecnicoId(c.getTecnicoId());
            copia.setAdminId(c.getAdminId());
            copia.setCienteEm(c.getCienteEm());
            ciencias.add(copia);
        }
        cienciaRepo.saveAll(ciencias);

        log.info("Aviso cadastro #{} criado com os {} destinatário(s) restantes do #{}",
                novo.getNumero(), alvos.size(), antigo.getNumero());
        entityManager.flush();   // a conta de pendentes é feita em SQL: ela precisa enxergar o que acabou de nascer
        desativarSeTodosCientes(novo);
    }

    // ═══ Listagem (admin) ═══════════════════════════════════════

    /**
     * Contexto exibido na coluna "Tipo" da listagem (ao lado do selo da categoria): derivado do
     * SUBTIPO — mais específico — com FALLBACK no TIPO para o legado e a Verificação (SUBTIPO nulo).
     * NULO para a MENSAGEM, que se identifica só pelo selo.
     */
    private static final String CONTEXTO_TABELA_EXPR = caseDoRotulo(RotuloAviso::contextoTabela);

    /** Código da categoria da comunicação (mesma fonte do contexto) — alimenta o selo da tabela. */
    private static final String CATEGORIA_EXPR = caseDoRotulo(r -> r.categoria().name());

    /**
     * Monta o CASE SQL que projeta um pedaço do {@link RotuloAviso} a partir das colunas SUBTIPO/TIPO,
     * na MESMA precedência da resolução em Java (subtipo primeiro, tipo como fallback) — assim a
     * listagem nunca diverge do popup e do detalhe. Texto vazio vira NULL (coluna em branco).
     * ⚠️ Nenhum rótulo pode conter vírgula: o helper de paginação faz split do selectCols por vírgula.
     */
    private static String caseDoRotulo(Function<RotuloAviso, String> parte) {
        StringBuilder sb = new StringBuilder("CASE ");
        for (SubtipoAviso s : SubtipoAviso.values())
            sb.append("WHEN c.SUBTIPO = '").append(s.name()).append("' THEN ")
              .append(literalSql(parte.apply(s.getRotulo()))).append(' ');
        for (TipoAviso t : TipoAviso.values())
            sb.append("WHEN c.TIPO = '").append(t.name()).append("' THEN ")
              .append(literalSql(parte.apply(t.getRotulo()))).append(' ');
        return sb.append("END").toString();
    }

    /** Literal SQL do rótulo; vazio vira NULL para a coluna ficar em branco. */
    private static String literalSql(String v) {
        return (v == null || v.isBlank()) ? "NULL" : "'" + v + "'";
    }

    /**
     * Status EXIBIDO na listagem. Para ESCALA é calculado das datas da escala (Pendente/Ativo/
     * Expirado); um cadastro DESATIVADO gravado prevalece sobre o cálculo (§5.3). Demais tipos usam
     * o STATUS gravado. Depende do LEFT JOIN OPR_ESCALA_SEMANAL (alias es) no fromJoins.
     */
    private static final String STATUS_EXPR =
            "CASE WHEN c.STATUS = 'Desativado' THEN 'Desativado' " +
            "WHEN c.TIPO = 'AGENDA' THEN '—' " +   // AGENDA não tem ciclo temporal: morre por usuário (visto), §6.3
            "WHEN c.TIPO = 'ESCALA' THEN " +
            "  CASE WHEN es.DATA_INICIO > TRUNC(SYSDATE) THEN 'Pendente' " +
            "       WHEN es.DATA_FIM < TRUNC(SYSDATE) THEN 'Expirado' " +
            "       ELSE 'Ativo' END " +
            "ELSE c.STATUS END";

    /** "Expira em" exibido: DATA_FIM da escala para ESCALA; EXPIRA_EM gravado para os demais (§8). */
    private static final String EXPIRA_EXPR =
            "CASE WHEN c.TIPO = 'ESCALA' THEN CAST(es.DATA_FIM AS TIMESTAMP) ELSE c.EXPIRA_EM END";

    private static final Map<String, String> SORT;
    private static final Map<String, String> COL_MAP;
    private static final Map<String, String> COL_TYPES;
    static {
        SORT = new LinkedHashMap<>();
        SORT.put("data", "c.CRIADO_EM");
        SORT.put("numero", "c.NUMERO");
        SORT.put("tipo", CONTEXTO_TABELA_EXPR);
        SORT.put("expira", EXPIRA_EXPR);
        SORT.put("status", STATUS_EXPR);
        SORT.put("criado_por", "ad.NOME_COMPLETO");

        COL_MAP = new LinkedHashMap<>();
        COL_MAP.put("tipo", CONTEXTO_TABELA_EXPR);
        COL_MAP.put("data", "c.CRIADO_EM");
        COL_MAP.put("expira", EXPIRA_EXPR);
        COL_MAP.put("status", STATUS_EXPR);
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
                "c.ID, c.NUMERO, " + CONTEXTO_TABELA_EXPR + " AS tipo, " + CATEGORIA_EXPR + " AS categoria, " +
                "c.CRIADO_EM AS criado_em, ad.NOME_COMPLETO AS criado_por, " +
                EXPIRA_EXPR + " AS expira_em, " + STATUS_EXPR + " AS status, c.PERMANENTE AS permanente";
        // LEFT JOIN da escala: alimenta o status/expira calculados do aviso de ESCALA (es.* nulo p/ os demais).
        String fromJoins =
                "FROM FRM_AVISO_CADASTRO c " +
                "LEFT JOIN PES_ADMINISTRADOR ad ON ad.ID = c.CRIADO_POR_ID " +
                "LEFT JOIN OPR_ESCALA_SEMANAL es ON es.ID = c.ESCALA_ID";
        return DashboardQueryHelper.executePagedQuery(entityManager,
                selectCols, fromJoins,
                null, SORT,
                List.of("ad.NOME_COMPLETO", "TO_CHAR(c.NUMERO)"),
                COL_MAP, COL_TYPES,
                page, limit, search, sort, direction, null, filters,
                "c.ID DESC");
    }

    /** Detalhe completo de um cadastro (cabeçalho + mensagens + alvos + cientes + destinatários por tipo). */
    public Map<String, Object> obterDetalhe(String id) {
        AvisoCadastro cad = cadastroRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("Comunicação não encontrada.", HttpStatus.NOT_FOUND));
        Map<String, Object> m = toResumo(cad);
        // Campo "Tipo": selo da categoria + contexto ao lado — mesma regra da listagem (subtipo → fallback no tipo).
        RotuloAviso rotulo = RotuloAviso.de(cad.getTipo(), cad.getSubtipo());
        m.put("subtipo", cad.getSubtipo() != null ? cad.getSubtipo().name() : null);
        m.put("categoria", rotulo.categoria().name());
        m.put("tipo_tabela", rotulo.contextoTabela());
        m.put("mensagens", mensagemRepo.findByCadastroIdOrderByOrdem(id).stream()
                .map(this::mensagemToMap).toList());
        // Resolve nomes de alvos/ciências com mapas carregados no máx. 1× por tipo (lazy) — evita N+1 (Q18).
        ResolvedorNomes nomes = new ResolvedorNomes();
        List<AvisoAlvo> alvos = alvoRepo.findByCadastroId(id);
        m.put("alvos", alvos.stream().map(a -> alvoToMap(a, nomes)).toList());
        // Carrega as ciências/vistos 1× — reusados por cientes/destinatarios/exibido. Todo cadastro tem
        // registro: quem exige ciência guarda a ciência; quem não exige guarda o visto da exibição.
        boolean exigeCiencia = Boolean.TRUE.equals(cad.getExigeCiencia());
        List<AvisoCiencia> ciencias = cienciaRepo.findByCadastroIdOrderByCienteEm(id);
        List<Map<String, Object>> registros = ciencias.stream().map(c -> cienciaToMap(c, nomes)).toList();
        m.put("cientes", exigeCiencia ? registros : List.of());
        // Escala do aviso carregada 1× — alimenta o bloco 'escala', o status/expira calculados e os destinatários.
        EscalaSemanal esc = (cad.getTipo() == TipoAviso.ESCALA && cad.getEscalaId() != null)
                ? escalaRepo.findById(cad.getEscalaId()).orElse(null) : null;
        // Status/expira COERENTES com a listagem (§5.b): sobrescrevem o valor gravado que o toResumo trouxe.
        m.put("status", statusDetalhe(cad, esc));
        m.put("expira_em", expiraDetalhe(cad, esc, (String) m.get("expira_em")));
        // ESCALA: período da escala + plenários selecionados (§3.2/§3.3).
        if (esc != null) m.put("escala", montarBlocoEscala(esc, alvos, nomes));
        // Destinatários resolvidos por tipo (§5.d) — cruzados por ID no backend (nomes têm homônimos).
        if (cad.getTipo() == TipoAviso.ESCALA)
            m.put("destinatarios", destinatariosEscala(cad, alvos, ciencias, nomes));
        else if (cad.getTipo() == TipoAviso.PESSOAL)
            m.put("destinatarios", destinatariosPessoal(alvos, ciencias, nomes));
        // Sem ciência exigida: a lista de "visto" (reusa FRM_AVISO_CIENCIA) aparece como "Exibido para".
        if (!exigeCiencia) m.put("exibido_para", registros);
        return m;
    }

    /**
     * Status exibido no detalhe, coerente com o da listagem (§5.b / {@code STATUS_EXPR}): DESATIVADO gravado
     * prevalece; AGENDA é "—"; ESCALA é Pendente/Ativo/Expirado calculado das datas da escala; demais usam o
     * STATUS gravado. Sem repetir SQL — a escala já veio carregada.
     */
    private String statusDetalhe(AvisoCadastro cad, EscalaSemanal esc) {
        if (cad.getStatus() == StatusAviso.DESATIVADO) return StatusAviso.DESATIVADO.getValor();
        if (cad.getTipo() == TipoAviso.AGENDA) return "—";
        if (cad.getTipo() == TipoAviso.ESCALA && esc != null
                && esc.getDataInicio() != null && esc.getDataFim() != null) {
            LocalDate hoje = LocalDate.now();
            if (esc.getDataInicio().isAfter(hoje)) return "Pendente";
            if (esc.getDataFim().isBefore(hoje)) return "Expirado";
            return "Ativo";
        }
        return cad.getStatus() != null ? cad.getStatus().getValor() : null;
    }

    /** "Expira em" do detalhe: ESCALA usa o DATA_FIM da escala; os demais mantêm o EXPIRA_EM gravado (§5.b). */
    private String expiraDetalhe(AvisoCadastro cad, EscalaSemanal esc, String expiraGravado) {
        if (cad.getTipo() == TipoAviso.ESCALA)
            return (esc != null && esc.getDataFim() != null) ? esc.getDataFim().toString() : null;
        return expiraGravado;
    }

    /** Bloco "escala" do detalhe do aviso de ESCALA: período da escala + plenários (linhas de alvo SALA). */
    private Map<String, Object> montarBlocoEscala(EscalaSemanal esc, List<AvisoAlvo> alvos, ResolvedorNomes nomes) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", esc.getId());
        e.put("data_inicio", esc.getDataInicio() != null ? esc.getDataInicio().toString() : null);
        e.put("data_fim", esc.getDataFim() != null ? esc.getDataFim().toString() : null);
        List<Map<String, Object>> plenarios = alvos.stream()
                .filter(a -> a.getAlvoTipo() == AlvoTipoAviso.SALA && a.getSalaId() != null)
                .map(a -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("sala_id", a.getSalaId());
                    p.put("sala_nome", nomes.sala(a.getSalaId()));
                    return p;
                }).toList();
        e.put("plenarios", plenarios);
        return e;
    }

    /**
     * Destinatários da ESCALA (§5.d): operadores ATUALMENTE vinculados aos plenários-alvo na escala do
     * aviso (idioma do JOIN de {@code buscarPendentes}), cada um com seus plenários e a ciência
     * (pendente = {@code ciente_em} nulo); + os cientes que já não estão na escala (troca de operador ou
     * ciência "contaminada"), marcados {@code fora_do_publico}. A ordenação final é do front (§4.6).
     */
    private List<Map<String, Object>> destinatariosEscala(AvisoCadastro cad, List<AvisoAlvo> alvos,
                                                          List<AvisoCiencia> ciencias, ResolvedorNomes nomes) {
        Set<Integer> salasAlvo = alvos.stream()
                .filter(a -> a.getAlvoTipo() == AlvoTipoAviso.SALA && a.getSalaId() != null)
                .map(AvisoAlvo::getSalaId).collect(Collectors.toSet());
        // Operador → plenários-alvo aos quais está vinculado na escala do aviso (agrega M/V na mesma linha).
        Map<String, Set<Integer>> plenariosPorOperador = new LinkedHashMap<>();
        if (cad.getEscalaId() != null)
            for (EscalaOperador eo : escalaOpRepo.findByEscalaId(cad.getEscalaId()))
                if (eo.getOperadorId() != null && eo.getSalaId() != null && salasAlvo.contains(eo.getSalaId()))
                    plenariosPorOperador.computeIfAbsent(eo.getOperadorId(), k -> new TreeSet<>()).add(eo.getSalaId());
        // Ciência por operador (a ciência da ESCALA é sem sala, na coluna OPERADOR_ID).
        Map<String, LocalDateTime> cienciaPorOperador = new HashMap<>();
        for (AvisoCiencia c : ciencias)
            if (c.getOperadorId() != null) cienciaPorOperador.put(c.getOperadorId(), c.getCienteEm());

        List<Map<String, Object>> out = new ArrayList<>();
        // Vinculados atuais (o público de hoje): pendentes e cientes.
        for (Map.Entry<String, Set<Integer>> e : plenariosPorOperador.entrySet()) {
            List<String> plenarios = e.getValue().stream().map(nomes::sala).toList();
            out.add(linhaDestinatario(nomes.operador(e.getKey()), "Operador", plenarios,
                    cienciaPorOperador.get(e.getKey()), false));
        }
        // Cientes que não estão mais no público atual → fim da tabela, marcados (decisão 4).
        for (AvisoCiencia c : ciencias) {
            if (c.getOperadorId() != null && plenariosPorOperador.containsKey(c.getOperadorId())) continue;
            out.add(linhaDestinatario(nomeDaCiencia(c, nomes), papelDaCiencia(c), List.of(), c.getCienteEm(), true));
        }
        return out;
    }

    /**
     * Destinatários do PESSOAL (§5.d): os alvos individuais (operador/técnico/admin) cruzados POR ID com as
     * ciências (pendente = {@code ciente_em} nulo); + as ciências de quem não é destinatário, marcadas
     * {@code fora_do_publico}. Cobre também os programáticos (Folha/Solicitação) e o legado (mesmo tipo PESSOAL).
     *
     * <p>Quem recebeu um complemento o traz na própria linha: é assim que o administrador lê o que foi
     * dito a cada pessoa — a comunicação é uma só, mas o texto final de cada uma pode ser diferente.
     */
    private List<Map<String, Object>> destinatariosPessoal(List<AvisoAlvo> alvos,
                                                           List<AvisoCiencia> ciencias, ResolvedorNomes nomes) {
        Map<String, LocalDateTime> cienciaPorChave = new HashMap<>();
        for (AvisoCiencia c : ciencias) cienciaPorChave.put(chaveCiencia(c), c.getCienteEm());

        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> chavesAlvo = new HashSet<>();
        for (AvisoAlvo a : alvos) {
            String nome, papel, chave;
            switch (a.getAlvoTipo()) {
                case OPERADOR -> { nome = nomes.operador(a.getOperadorId()); papel = "Operador"; chave = "OPERADOR:" + a.getOperadorId(); }
                case TECNICO  -> { nome = nomes.tecnico(a.getTecnicoId());   papel = "Técnico";       chave = "TECNICO:" + a.getTecnicoId(); }
                case ADMIN    -> { nome = nomes.admin(a.getAdminId());       papel = "Administrador"; chave = "ADMIN:" + a.getAdminId(); }
                default -> { continue; }   // PESSOAL não tem alvo coletivo/SALA — ignora defensivamente
            }
            chavesAlvo.add(chave);
            Map<String, Object> linha = linhaDestinatario(nome, papel, null, cienciaPorChave.get(chave), false);
            if (a.getComplemento() != null && !a.getComplemento().isBlank())
                linha.put("complemento", a.getComplemento());
            out.add(linha);
        }
        // Ciência de quem não é destinatário (a API aceita — §5.e) → visível e marcada, no fim.
        for (AvisoCiencia c : ciencias) {
            if (chavesAlvo.contains(chaveCiencia(c))) continue;
            out.add(linhaDestinatario(nomeDaCiencia(c, nomes), papelDaCiencia(c), null, c.getCienteEm(), true));
        }
        return out;
    }

    /** Linha de destinatário: {@code {nome, papel, plenarios?, ciente_em, fora_do_publico?}} (§5.d). */
    private Map<String, Object> linhaDestinatario(String nome, String papel, List<String> plenarios,
                                                  LocalDateTime cienteEm, boolean foraDoPublico) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", nome);
        m.put("papel", papel);
        if (plenarios != null) m.put("plenarios", plenarios);   // só a ESCALA tem plenários
        m.put("ciente_em", cienteEm != null ? cienteEm.toString() : null);
        if (foraDoPublico) m.put("fora_do_publico", true);
        return m;
    }

    /** Chave (papel:id) da ciência para cruzar com os alvos individuais do PESSOAL. */
    private String chaveCiencia(AvisoCiencia c) {
        if (c.getOperadorId() != null) return "OPERADOR:" + c.getOperadorId();
        if (c.getTecnicoId() != null) return "TECNICO:" + c.getTecnicoId();
        return "ADMIN:" + c.getAdminId();
    }

    private String nomeDaCiencia(AvisoCiencia c, ResolvedorNomes nomes) {
        if (c.getOperadorId() != null) return nomes.operador(c.getOperadorId());
        if (c.getTecnicoId() != null) return nomes.tecnico(c.getTecnicoId());
        return nomes.admin(c.getAdminId());
    }

    private String papelDaCiencia(AvisoCiencia c) {
        if (c.getOperadorId() != null) return "Operador";
        if (c.getTecnicoId() != null) return "Técnico";
        return "Administrador";
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
                SELECT c.ID, c.MANTER_APOS_CIENCIA, c.EXIGE_CIENCIA
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
            boolean exigeCiencia = ((Number) r[2]).intValue() == 1;
            boolean jaCiente = temCiencia(cadastroId, salaId, pessoaId, papel);
            if (!manter && jaCiente) continue; // já marcou NESTA sala e não é pra manter → pula
            // Verificação é aviso de SALA: o alvo não é uma pessoa, e não há a quem personalizar.
            return Optional.of(montarPayloadPendente(cadastroId, manter, TipoAviso.VERIFICACAO, null, exigeCiencia, null));
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
     * Cadastros que exigem ciência saem da lista quando a pessoa já deu ciência;
     * os que não exigem saem depois de exibidos uma vez (o visto que o front
     * registra). Só o "manter após ciência" reaparece a cada login. Do mais antigo
     * ao mais novo.
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

        // Ramo dinâmico do aviso de ESCALA (só OPERADOR): o destinatário não é gravado — resolve-se
        // cruzando os plenários-alvo do cadastro com o vínculo do operador na escala VIGENTE hoje
        // (janela DATA_INICIO..DATA_FIM). Técnicos/admins não entram em escala → sem ramo (§5.2).
        boolean incluiEscala = tipos.contains(TipoAviso.ESCALA) && papel == PapelPessoa.OPERADOR;
        String condEscala = incluiEscala
                ? " OR (c.TIPO = 'ESCALA' AND EXISTS ("
                + "SELECT 1 FROM FRM_AVISO_ALVO ale "
                + "JOIN OPR_ESCALA_SEMANAL es ON es.ID = c.ESCALA_ID "
                + "JOIN OPR_ESCALA_OPERADOR eo ON eo.ESCALA_ID = es.ID AND eo.SALA_ID = ale.SALA_ID "
                + "WHERE ale.CADASTRO_ID = c.ID AND ale.ALVO_TIPO = 'SALA' AND eo.OPERADOR_ID = ? "
                + "AND TRUNC(SYSDATE) BETWEEN es.DATA_INICIO AND es.DATA_FIM))"
                : "";

        Query q = entityManager.createNativeQuery(
                "SELECT c.ID, c.MANTER_APOS_CIENCIA, c.TIPO, c.SUBTIPO, c.EXIGE_CIENCIA " +
                "  FROM FRM_AVISO_CADASTRO c " +
                " WHERE c.TIPO IN (" + inTipos + ") " +
                "   AND c.STATUS = 'Ativo' " +
                "   AND (c.PERMANENTE = 1 OR c.EXPIRA_EM IS NULL OR c.EXPIRA_EM > SYSTIMESTAMP) " +
                "   AND ((c.TIPO <> 'ESCALA' AND EXISTS (SELECT 1 FROM FRM_AVISO_ALVO al WHERE al.CADASTRO_ID = c.ID AND (" + condAlvo + ")))" + condEscala + ") " +
                " ORDER BY c.CRIADO_EM");
        q.setParameter(1, pessoaId);
        if (incluiEscala) q.setParameter(2, pessoaId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String cadastroId = (String) r[0];
            boolean manter = ((Number) r[1]).intValue() == 1;
            TipoAviso tipo = TipoAviso.fromString((String) r[2]);
            SubtipoAviso subtipo = SubtipoAviso.fromString((String) r[3]);
            boolean exigeCiencia = ((Number) r[4]).intValue() == 1;
            // Um registro na tabela de ciência (a ciência de quem exige, o visto da exibição de quem
            // não exige) tira o aviso da lista — para sempre, naquele usuário. A única exceção é o
            // cadastro com ciência E "manter após ciência": esse reaparece a cada login.
            if (!(exigeCiencia && manter) && temCiencia(cadastroId, null, pessoaId, papel)) continue;
            out.add(montarPayloadPendente(cadastroId, manter, tipo, subtipo, exigeCiencia,
                    complementoDaPessoa(cadastroId, pessoaId, papel)));
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
                new ServiceValidationException("Comunicação não encontrada.", HttpStatus.NOT_FOUND));
        if (!Boolean.TRUE.equals(cad.getExigeCiencia()))
            throw new ServiceValidationException("Esta comunicação não registra ciência.");
        // Sala obrigatória só para tipos amarrados a sala (VERIFICACAO); PESSOAL não tem sala.
        if (cad.getTipo() != null && cad.getTipo().exigeSala() && salaId == null)
            throw new ServiceValidationException("Sala é obrigatória para registrar ciência.");
        // Aviso não mais ativo (expirou/desativou entre a exibição e o clique):
        // nada a registrar, mas não bloqueia o destinatário.
        if (cad.getStatus() != StatusAviso.ATIVO) return;
        if (temCiencia(cadastroId, salaId, pessoaId, papel)) return;

        inserirCienciaIdempotente(cad, salaId, pessoaId, papel);
        desativarSeTodosCientes(cad);
    }

    /**
     * Comunicação que se encerra sozinha quando o último destinatário registra ciência: ela já
     * cumpriu o que tinha a fazer e não precisa continuar na lista de ativas. Ficam de fora a que
     * pede para ser mantida (essa volta a cada login, por escolha do admin), a que não pede ciência
     * e o aviso de escala, cujo fim é a data da escala.
     */
    private boolean encerraComACienciaDeTodos(AvisoCadastro cad) {
        return Boolean.TRUE.equals(cad.getExigeCiencia())
                && !Boolean.TRUE.equals(cad.getManterAposCiencia())
                && cad.getTipo() != TipoAviso.ESCALA;
    }

    /**
     * Desativa o cadastro se não restou destinatário pendente. O UPDATE é condicionado ao status
     * ativo: duas últimas ciências simultâneas contam zero pendentes cada uma, e só a primeira
     * encontra o cadastro para desativar.
     */
    private void desativarSeTodosCientes(AvisoCadastro cad) {
        if (!encerraComACienciaDeTodos(cad)) return;
        if (destinatariosSemCiencia(cad) > 0) return;
        int n = entityManager.createNativeQuery("""
                UPDATE FRM_AVISO_CADASTRO
                   SET STATUS = 'Desativado', DESATIVADO_EM = SYSTIMESTAMP
                 WHERE ID = ? AND STATUS = 'Ativo'
                """).setParameter(1, cad.getId()).executeUpdate();
        if (n > 0) log.info("Aviso cadastro #{} desativado: todos os destinatários deram ciência", cad.getNumero());
    }

    /**
     * Quantos destinatários ainda não registraram ciência. A verificação de sala conta SALAS (a
     * ciência é por sala, não por pessoa); a comunicação a um grupo conta o público ATUAL dele —
     * quem for cadastrado depois não entra na conta.
     */
    private long destinatariosSemCiencia(AvisoCadastro cad) {
        return switch (cad.getTipo()) {
            case VERIFICACAO -> contarUm("""
                    SELECT COUNT(*) FROM FRM_AVISO_ALVO al
                     WHERE al.CADASTRO_ID = ? AND al.ALVO_TIPO = 'SALA'
                       AND NOT EXISTS (SELECT 1 FROM FRM_AVISO_CIENCIA ci
                                        WHERE ci.CADASTRO_ID = al.CADASTRO_ID AND ci.SALA_ID = al.SALA_ID)
                    """, cad.getId());
            case PESSOAL -> contarUm("""
                    SELECT COUNT(*) FROM FRM_AVISO_ALVO al
                     WHERE al.CADASTRO_ID = ? AND al.ALVO_TIPO IN ('OPERADOR','TECNICO','ADMIN')
                       AND NOT EXISTS (SELECT 1 FROM FRM_AVISO_CIENCIA ci
                                        WHERE ci.CADASTRO_ID = al.CADASTRO_ID
                                          AND (ci.OPERADOR_ID = al.OPERADOR_ID
                                            OR ci.TECNICO_ID = al.TECNICO_ID
                                            OR ci.ADMIN_ID = al.ADMIN_ID))
                    """, cad.getId());
            case GERAL -> publicoDoGrupoSemCiencia(cad);
            default -> 1;   // tipo que não se encerra por ciência: nunca chega a zero
        };
    }

    /** Pessoas do público de cada grupo-alvo que ainda não deram ciência, somadas. */
    private long publicoDoGrupoSemCiencia(AvisoCadastro cad) {
        long faltam = 0;
        for (AvisoAlvo al : alvoRepo.findByCadastroId(cad.getId())) {
            if (al.getAlvoTipo() == null) continue;
            faltam += switch (al.getAlvoTipo()) {
                case TODOS_OPERADORES -> semCiencia("PES_OPERADOR", "OPERADOR_ID", cad.getId());
                case TODOS_TECNICOS -> semCiencia("PES_TECNICO", "TECNICO_ID", cad.getId());
                case TODOS_ADMIN -> semCiencia("PES_ADMINISTRADOR", "ADMIN_ID", cad.getId());
                case TODOS -> semCiencia("PES_OPERADOR", "OPERADOR_ID", cad.getId())
                            + semCiencia("PES_TECNICO", "TECNICO_ID", cad.getId());
                default -> 0;
            };
        }
        return faltam;
    }

    /** Quantas pessoas da tabela do papel ainda não constam na ciência do cadastro. */
    private long semCiencia(String tabelaPessoa, String colunaCiencia, String cadastroId) {
        return contarUm("SELECT COUNT(*) FROM " + tabelaPessoa + " p "
                + " WHERE NOT EXISTS (SELECT 1 FROM FRM_AVISO_CIENCIA ci "
                + "                    WHERE ci.CADASTRO_ID = ? AND ci." + colunaCiencia + " = p.ID)", cadastroId);
    }

    private long contarUm(String sql, String cadastroId) {
        Object n = entityManager.createNativeQuery(sql).setParameter(1, cadastroId).getSingleResult();
        return ((Number) n).longValue();
    }

    /**
     * Registra o "visto" da pessoa numa comunicação SEM ciência exigida: grava na MESMA tabela de
     * ciência ({@code FRM_AVISO_CIENCIA}, sem sala), com semântica de "exibido 1× por usuário" — é o
     * que faz a comunicação não voltar nos próximos logins. Quem exige ciência tem o registro próprio
     * ({@link #registrarCiencia}) e é recusado aqui. Idempotente e tolerante a corrida; aviso
     * não-ATIVO é no-op silencioso.
     */
    @Transactional
    public void registrarVisto(String cadastroId, String pessoaId, PapelPessoa papel) {
        AvisoCadastro cad = cadastroRepo.findById(cadastroId).orElseThrow(() ->
                new ServiceValidationException("Comunicação não encontrada.", HttpStatus.NOT_FOUND));
        if (Boolean.TRUE.equals(cad.getExigeCiencia()))
            throw new ServiceValidationException("Esta comunicação não registra visualização.");
        if (cad.getStatus() != StatusAviso.ATIVO) return;      // desativado entre a exibição e o registro
        if (temCiencia(cadastroId, null, pessoaId, papel)) return;

        inserirCienciaIdempotente(cad, null, pessoaId, papel);
    }

    /**
     * Grava a linha de {@code FRM_AVISO_CIENCIA} da ciência (com sala) ou do visto (sem sala), na
     * coluna do papel. O {@link AvisoCienciaWriter#inserir} roda em REQUIRES_NEW: uma corrida que viole
     * o índice único vira {@link DataIntegrityViolationException}, capturada aqui — a operação é idempotente.
     */
    private void inserirCienciaIdempotente(AvisoCadastro cad, Integer salaId, String pessoaId, PapelPessoa papel) {
        AvisoCiencia c = new AvisoCiencia();
        c.setCadastroId(cad.getId());
        c.setSalaId(salaId);
        switch (papel) {
            case OPERADOR -> c.setOperadorId(pessoaId);
            case TECNICO  -> c.setTecnicoId(pessoaId);
            case ADMIN    -> c.setAdminId(pessoaId);
        }
        c.setCienteEm(LocalDateTime.now());
        try {
            cienciaWriter.inserir(c); // REQUIRES_NEW: isola eventual violação de unicidade em corrida
            log.info("Ciência/visto registrado: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
        } catch (DataIntegrityViolationException e) {
            // Corrida: outra requisição já gravou. Operação idempotente — ignora.
            log.debug("Ciência/visto concorrente já registrado: cadastro #{} sala {} por {} ({})", cad.getNumero(), salaId, pessoaId, papel);
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

    /** Regras de vigência do form (permanente/duração/expira) + "manter após ciência". */
    private record Vigencia(boolean permanente, Integer duracaoDias, LocalDateTime expiraEm, boolean manter) {}

    /**
     * Resolve permanente/duração/expira e o "manter após ciência" a partir do payload — regra comum ao
     * fluxo padrão (Verificação/Grupo) e ao modo "Pessoas específicas" do card Pessoal. Default é
     * permanente; não-permanente exige duração 1..{@value #MAX_DURACAO_DIAS} e calcula EXPIRA_EM = agora + dias.
     */
    private Vigencia resolverVigencia(CriarAvisoRequest req, boolean exigeCiencia) {
        boolean permanente = req.permanente() == null || req.permanente(); // default true
        Integer duracao = permanente ? null : req.duracaoDias();
        if (!permanente && (duracao == null || duracao < 1 || duracao > MAX_DURACAO_DIAS))
            throw new ServiceValidationException("A duração deve estar entre 1 e " + MAX_DURACAO_DIAS + " dias.");
        LocalDateTime expiraEm = permanente ? null : LocalDateTime.now().plusDays(duracao);
        return new Vigencia(permanente, duracao, expiraEm, resolverManter(req.manterAposCiencia(), exigeCiencia));
    }

    /**
     * Exigência de ciência do cadastro: nos tipos configuráveis vale a escolha do form (ausente → o
     * padrão do tipo); em Verificação e Agenda o valor é IMPOSTO — o form não exibe o controle e um
     * payload que insista no contrário é ignorado, não recusado.
     */
    private boolean resolverExigeCiencia(TipoAviso tipo, Boolean escolha) {
        if (!tipo.cienciaConfiguravel()) return tipo.cienciaPadrao();
        return escolha != null ? escolha : tipo.cienciaPadrao();
    }

    /** "Manter após ciência" só existe com ciência ligada: sem ela não há ciência para o aviso sobreviver. */
    private boolean resolverManter(Boolean escolha, boolean exigeCiencia) {
        return exigeCiencia && Boolean.TRUE.equals(escolha);
    }

    /**
     * Subtipo do card "Um grupo" (tipo GERAL): mapeia o coletivo escolhido ao código GRUPO_* (§2).
     * Para qualquer outro tipo (Verificação etc.) devolve {@code null} — subtipo nulo → fallback no
     * label do tipo na tabela do admin.
     */
    private SubtipoAviso subtipoDeGrupo(TipoAviso tipo, AlvoTipoAviso alvoTipo) {
        if (tipo != TipoAviso.GERAL) return null;
        return switch (alvoTipo) {
            case TODOS_OPERADORES -> SubtipoAviso.GRUPO_OPERADORES;
            case TODOS_TECNICOS -> SubtipoAviso.GRUPO_TECNICOS;
            case TODOS -> SubtipoAviso.GRUPO_TODOS;
            case TODOS_ADMIN -> SubtipoAviso.GRUPO_ADMINISTRADORES;
            default -> null;
        };
    }

    private static List<Integer> distinctInt(List<Integer> raw) {
        return (raw == null ? List.<Integer>of() : raw).stream().distinct().toList();
    }

    private static List<String> distinctStr(List<String> raw) {
        return (raw == null ? List.<String>of() : raw).stream().distinct().toList();
    }

    /** Trima e valida as mensagens (1..MAX_MENSAGENS, nenhuma em branco). */
    private List<String> validarMensagens(List<String> raw) {
        List<String> mensagens = (raw == null ? List.<String>of() : raw)
                .stream().map(m -> m == null ? "" : m.trim()).toList();
        if (mensagens.isEmpty()) throw new ServiceValidationException("Informe ao menos uma mensagem.");
        if (mensagens.size() > MAX_MENSAGENS)
            throw new ServiceValidationException("Máximo de " + MAX_MENSAGENS + " avisos por cadastro.");
        if (mensagens.stream().anyMatch(String::isBlank))
            throw new ServiceValidationException("Todas as mensagens devem ser preenchidas.");
        return mensagens;
    }

    /** Autor: FK obrigatória para PES_ADMINISTRADOR. */
    private void validarAutor(String criadoPorId) {
        adminRepo.findById(criadoPorId).orElseThrow(() ->
                new ServiceValidationException("Administrador inválido.", HttpStatus.NOT_FOUND));
    }

    /** Grava as mensagens do cadastro na ordem 1..N. */
    private void gravarMensagens(String cadastroId, List<String> mensagens) {
        int ordem = 1;
        for (String texto : mensagens) {
            AvisoMensagem m = new AvisoMensagem();
            m.setCadastroId(cadastroId);
            m.setOrdem(ordem++);
            m.setTexto(texto);
            mensagemRepo.save(m);
        }
    }

    /** Próximo "número humano" do cadastro (sequence Oracle). */
    private long proximoNumeroCadastro() {
        Number n = (Number) entityManager
                .createNativeQuery("SELECT SEQ_FRM_AVISO_CADASTRO.NEXTVAL FROM DUAL")
                .getSingleResult();
        return n.longValue();
    }

    private void validarAlvo(AlvoTipoAviso alvoTipo, List<Integer> salaIds,
                             List<String> operadorIds, List<String> tecnicoIds, List<String> adminIds) {
        switch (alvoTipo) {
            case SALA -> {
                if (salaIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um local.");
                if (!operadorIds.isEmpty() || !tecnicoIds.isEmpty() || !adminIds.isEmpty())
                    throw new ServiceValidationException("Público por sala não aceita operadores/técnicos individuais.");
                for (Integer sid : salaIds)
                    salaRepo.findById(sid).orElseThrow(() ->
                            new ServiceValidationException("Local inválido: " + sid, HttpStatus.NOT_FOUND));
            }
            case OPERADOR -> {
                if (operadorIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um operador.");
                if (!salaIds.isEmpty() || !tecnicoIds.isEmpty() || !adminIds.isEmpty())
                    throw new ServiceValidationException("Público por operador não aceita salas/técnicos.");
                for (String oid : operadorIds)
                    operadorRepo.findById(oid).orElseThrow(() ->
                            new ServiceValidationException("Operador inválido: " + oid, HttpStatus.NOT_FOUND));
            }
            case TECNICO -> {
                if (tecnicoIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um técnico.");
                if (!salaIds.isEmpty() || !operadorIds.isEmpty() || !adminIds.isEmpty())
                    throw new ServiceValidationException("Público por técnico não aceita salas/operadores.");
                for (String tid : tecnicoIds)
                    tecnicoRepo.findById(tid).orElseThrow(() ->
                            new ServiceValidationException("Técnico inválido: " + tid, HttpStatus.NOT_FOUND));
            }
            // Administrador individual — destravado na decisão 18 (o card Pessoal passou a oferecê-lo, e
            // parte das pessoas com folha vive em PES_ADMINISTRADOR). Antes o cadastro do form o recusava.
            case ADMIN -> {
                if (adminIds.isEmpty()) throw new ServiceValidationException("Selecione ao menos um administrador.");
                if (!salaIds.isEmpty() || !operadorIds.isEmpty() || !tecnicoIds.isEmpty())
                    throw new ServiceValidationException("Público por administrador não aceita salas/operadores/técnicos.");
                for (String aid : adminIds)
                    adminRepo.findById(aid).orElseThrow(() ->
                            new ServiceValidationException("Administrador inválido: " + aid, HttpStatus.NOT_FOUND));
            }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS_ADMIN, TODOS -> {
                if (!salaIds.isEmpty() || !operadorIds.isEmpty() || !tecnicoIds.isEmpty() || !adminIds.isEmpty())
                    throw new ServiceValidationException("Público coletivo não aceita seleção individual.");
            }
            // Proteção contra qualquer público futuro sem produtor: falha em voz alta em vez de gerar um
            // cadastro ATIVO e sem linha de alvo (aviso invisível). ADMIN/TODOS_ADMIN saíram daqui na
            // decisão 18; o default segue barrando o que o cadastro não sabe montar.
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
                        nome + " já possui um aviso ativo (cadastro nº " + numero + ").");
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

    private List<AvisoAlvo> montarAlvos(String cadastroId, AlvoTipoAviso alvoTipo, List<Integer> salaIds,
                                        List<String> operadorIds, List<String> tecnicoIds, List<String> adminIds) {
        List<AvisoAlvo> alvos = new ArrayList<>();
        switch (alvoTipo) {
            case SALA -> { for (Integer sid : salaIds) {
                AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.SALA); a.setSalaId(sid); alvos.add(a);
            } }
            case OPERADOR -> { for (String oid : operadorIds) {
                AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.OPERADOR); a.setOperadorId(oid); alvos.add(a);
            } }
            case TECNICO -> { for (String tid : tecnicoIds) {
                AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.TECNICO); a.setTecnicoId(tid); alvos.add(a);
            } }
            case ADMIN -> { for (String aid : adminIds) {
                AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.ADMIN); a.setAdminId(aid); alvos.add(a);
            } }
            case TODOS_OPERADORES, TODOS_TECNICOS, TODOS_ADMIN, TODOS ->
                alvos.add(novoAlvo(cadastroId, alvoTipo));   // coletivo: 1 linha, sem FK
            // Inalcançável: validarAlvo já barrou. Fecha o switch para que um público sem produtor
            // nunca mais gere cadastro sem linha em FRM_AVISO_ALVO.
            default -> throw publicoNaoSuportado(alvoTipo);
        }
        return alvos;
    }

    /** Fábrica de uma linha de alvo já com cadastro e tipo preenchidos (as FKs ficam por conta do chamador). */
    private AvisoAlvo novoAlvo(String cadastroId, AlvoTipoAviso alvoTipo) {
        AvisoAlvo a = new AvisoAlvo();
        a.setCadastroId(cadastroId);
        a.setAlvoTipo(alvoTipo);
        return a;
    }

    /** Linhas de alvo MISTAS (operador/técnico/admin) de um cadastro PESSOAL do card Pessoal (§7.2). */
    private List<AvisoAlvo> montarAlvosMistos(String cadastroId, List<String> operadorIds,
                                              List<String> tecnicoIds, List<String> adminIds) {
        List<AvisoAlvo> alvos = new ArrayList<>();
        for (String oid : operadorIds) { AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.OPERADOR); a.setOperadorId(oid); alvos.add(a); }
        for (String tid : tecnicoIds) { AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.TECNICO); a.setTecnicoId(tid); alvos.add(a); }
        for (String aid : adminIds) { AvisoAlvo a = novoAlvo(cadastroId, AlvoTipoAviso.ADMIN); a.setAdminId(aid); alvos.add(a); }
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

    /**
     * Payload do popup. {@code categoria} comanda o rótulo e o visual da caixa; {@code titulo} é o
     * CONTEXTO que complementa o título ("Comunicado — Agenda Legislativa") e vem vazio quando a
     * categoria basta (Mensagem). Ambos derivam do subtipo quando houver, senão do tipo — a
     * Verificação e o legado PESSOAL não têm subtipo.
     *
     * <p>O {@code complemento} dirigido a quem está lendo entra como o ÚLTIMO texto da lista, logo
     * depois das mensagens comuns: para quem exibe a janela é um texto como os outros, e é por isso
     * que a personalização não precisou de campo novo no payload nem de tratamento na tela.
     */
    private Map<String, Object> montarPayloadPendente(String cadastroId, boolean manter, TipoAviso tipo,
                                                      SubtipoAviso subtipo, boolean exigeCiencia,
                                                      String complemento) {
        RotuloAviso rotulo = RotuloAviso.de(tipo, subtipo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cadastro_id", cadastroId);
        m.put("tipo", tipo.name());
        m.put("categoria", rotulo.categoria().name());
        m.put("titulo", rotulo.contextoPopup());
        m.put("exige_ciencia", exigeCiencia);
        m.put("manter_apos_ciencia", manter);
        List<Map<String, Object>> mensagens = new ArrayList<>(
                mensagemRepo.findByCadastroIdOrderByOrdem(cadastroId).stream().map(this::mensagemToMap).toList());
        if (complemento != null && !complemento.isBlank()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("ordem", mensagens.size() + 1);   // as gravadas ocupam 1..N
            extra.put("texto", complemento);
            mensagens.add(extra);
        }
        m.put("mensagens", mensagens);
        return m;
    }

    /**
     * O complemento que o cadastro dirige a esta pessoa, ou nulo. Quem é alcançado apenas por um
     * alvo coletivo nunca tem: o coletivo não personaliza ninguém.
     */
    private String complementoDaPessoa(String cadastroId, String pessoaId, PapelPessoa papel) {
        AlvoTipoAviso alvoTipo = switch (papel) {
            case OPERADOR -> AlvoTipoAviso.OPERADOR;
            case TECNICO  -> AlvoTipoAviso.TECNICO;
            case ADMIN    -> AlvoTipoAviso.ADMIN;
        };
        return alvoRepo.findComplementosDaPessoa(cadastroId, alvoTipo, pessoaId).stream()
                .filter(c -> c != null && !c.isBlank())
                .findFirst().orElse(null);
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
        // Sala da ciência (§5.c): a Verificação registra por sala → coluna Local na tabela; nulo nos demais.
        m.put("sala_id", c.getSalaId());
        m.put("sala_nome", c.getSalaId() != null ? nomes.sala(c.getSalaId()) : null);
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
        m.put("exige_ciencia", Boolean.TRUE.equals(c.getExigeCiencia()));
        m.put("manter_apos_ciencia", Boolean.TRUE.equals(c.getManterAposCiencia()));
        m.put("status", c.getStatus() != null ? c.getStatus().getValor() : null);
        m.put("criado_em", c.getCriadoEm() != null ? c.getCriadoEm().toString() : null);
        m.put("expira_em", c.getExpiraEm() != null ? c.getExpiraEm().toString() : null);
        m.put("criado_por", adminRepo.findById(c.getCriadoPorId())
                .map(a -> a.getNomeCompleto()).orElse(c.getCriadoPorId()));
        return m;
    }
}

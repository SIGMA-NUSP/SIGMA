package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.*;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.enums.Turno;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

import static br.leg.senado.nusp.service.NativeQueryUtils.blankToNull;
import static br.leg.senado.nusp.service.NativeQueryUtils.isDonoOuAdicional;

/**
 * Equivale ao checklist_service.py do Python (233 linhas).
 * Lógica de registro e edição de checklists "Testes Diários".
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final ChecklistRepository checklistRepo;
    private final ChecklistRespostaRepository respostaRepo;
    private final ChecklistItemTipoRepository itemTipoRepo;
    private final ChecklistOperadorRepository checklistOperadorRepo;
    private final SalaRepository salaRepo;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // ── Validação de itens ────────────────────────

    /**
     * Equivale a _validar_itens_checklist() do Python.
     * Valida a lista de itens e retorna o total de itens marcados.
     */
    /**
     * Lista de itens do payload cru, de forma defensiva (F29).
     *
     * <p>O {@code getOrDefault(..., List.of())} NÃO protegia de um {@code "itens": null} — a chave
     * existe, o valor é null e o {@code validarItens} estourava NPE — nem do cast, que some no
     * erasure: {@code "itens": ["x"]} passava aqui e explodia em {@code ClassCastException} lá
     * dentro. Ambos viravam 500 por um corpo torto do cliente. O próprio arquivo já usava a forma
     * defensiva ({@code instanceof List}) para os operadores; agora os itens seguem a mesma regra.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itensDoBody(Map<String, Object> body) {
        Object raw = body.get("itens");
        if (!(raw instanceof List<?> lista)) return List.of();
        for (Object item : lista) {
            if (item != null && !(item instanceof Map)) {
                throw new ServiceValidationException("Itens do formulário em formato inválido.");
            }
        }
        return (List<Map<String, Object>>) lista;
    }

    private int validarItens(List<Map<String, Object>> itens) {
        List<Integer> invalid = new ArrayList<>();
        List<Integer> falhaSemDesc = new ArrayList<>();
        int totalMarcados = 0;

        for (int idx = 0; idx < itens.size(); idx++) {
            Map<String, Object> it = itens.get(idx);
            if (it == null) {
                invalid.add(idx);
                continue;
            }

            Object itemId = it.get("item_tipo_id");
            String nome = str(it.get("nome")).strip();
            if (itemId == null && nome.isEmpty()) {
                invalid.add(idx);
                continue;
            }

            String status = str(it.get("status")).strip();
            String descFalha = str(it.get("descricao_falha")).strip();
            String valorTexto = str(it.get("valor_texto")).strip();

            if (!status.isEmpty() || !valorTexto.isEmpty()) {
                totalMarcados++;
                if ("falha".equalsIgnoreCase(status) && (descFalha.isEmpty() || descFalha.length() < 10)) {
                    falhaSemDesc.add(idx);
                }
            }
        }

        if (!invalid.isEmpty())
            throw new ServiceValidationException("Itens de checklist inválidos (deve conter 'item_tipo_id').");
        if (totalMarcados == 0)
            throw new ServiceValidationException("Pelo menos um item do checklist deve ser preenchido.");
        if (!falhaSemDesc.isEmpty())
            throw new ServiceValidationException(
                    "Itens marcados como Falha precisam de descrição com no mínimo 10 caracteres.");

        return totalMarcados;
    }

    private static String str(Object o) {
        String s = NativeQueryUtils.str(o);
        return s == null ? "" : s;
    }

    /**
     * Permissão de edição de um checklist: apenas o criador ou operadores
     * adicionais (junction FRM_CHECKLIST_OPERADOR). Fixos do Plenário
     * Principal têm leitura via dashboard, mas não escrita.
     */
    private void validarPermissaoEdicao(long checklistId, String userId) {
        if (!isDonoOuAdicional(entityManager, "FRM_CHECKLIST", "CRIADO_POR",
                "FRM_CHECKLIST_OPERADOR", "CHECKLIST_ID", checklistId, userId))
            throw new ServiceValidationException("Acesso negado.", HttpStatus.FORBIDDEN);
    }

    /** Resolve item_tipo_id (pelo id direto ou pelo nome). */
    private Integer resolveItemTipoId(Map<String, Object> item, Map<String, Integer> nomeToId) {
        Object idRaw = item.get("item_tipo_id");
        if (idRaw != null) {
            try {
                return Integer.parseInt(idRaw.toString());
            } catch (Exception e) {
                return null;
            }
        }
        String nome = str(item.get("nome")).strip();
        return nomeToId.get(nome);
    }

    /** Persiste os operadores da junction table (FRM_CHECKLIST_OPERADOR) com o papel informado. */
    private void salvarOperadoresChecklist(long checklistId, List<String> operadores, String papel) {
        for (String opId : operadores) {
            ChecklistOperador co = new ChecklistOperador();
            co.setChecklistId(checklistId);
            co.setOperadorId(opId);
            co.setPapel(papel);
            checklistOperadorRepo.save(co);
        }
    }

    // ── Registrar checklist ──────────────────────

    /**
     * POST /api/forms/checklist/registro
     * Equivale a registrar_checklist() do Python.
     */
    @Transactional
    public Map<String, Object> registrar(Map<String, Object> body, String userId) {
        // Campos obrigatórios
        List<String> reqFields = List.of("data_operacao", "sala_id", "hora_inicio_testes", "hora_termino_testes");
        List<String> missing = reqFields.stream().filter(k -> str(body.get(k)).strip().isEmpty()).toList();
        if (!missing.isEmpty())
            throw new ServiceValidationException("Campos obrigatórios ausentes.");

        String dataOperacao = str(body.get("data_operacao")).strip();
        int salaId;
        try {
            salaId = Integer.parseInt(str(body.get("sala_id")).strip());
        } catch (Exception e) {
            throw new ServiceValidationException("Local inválido.");
        }

        String horaInicio = str(body.get("hora_inicio_testes")).strip();
        String horaTermino = str(body.get("hora_termino_testes")).strip();
        String observacoes = blankToNull(str(body.get("observacoes")));
        String usb01 = blankToNull(str(body.get("usb_01")));
        String usb02 = blankToNull(str(body.get("usb_02")));

        // Turno: infere pela hora se não informado
        Turno turno = inferirTurno(str(body.get("turno")).strip(), horaInicio);

        // Itens
        List<Map<String, Object>> itens = itensDoBody(body);
        validarItens(itens);

        // Verificar duplicata (mesmo operador + mesma sala nos últimos 5 minutos)
        verificarDuplicataRecente(salaId, userId);

        // Mapa nome → id para resolução de itens legados
        Map<String, Integer> nomeToId = new HashMap<>();
        itemTipoRepo.findAllOrdered().forEach(t -> nomeToId.put(t.getNome(), t.getId()));

        // Inserir cabeçalho
        Checklist checklist = new Checklist();
        checklist.setDataOperacao(LocalDate.parse(dataOperacao));
        checklist.setSalaId(salaId);
        checklist.setTurno(turno);
        checklist.setHoraInicioTestes(horaInicio);
        checklist.setHoraTerminoTestes(horaTermino);
        checklist.setObservacoes(observacoes);
        checklist.setUsb01(usb01);
        checklist.setUsb02(usb02);
        checklist.setCriadoPor(userId);
        checklist.setAtualizadoPor(userId);
        checklist = checklistRepo.save(checklist);

        // Inserir respostas
        int totalRespostas = inserirRespostas(checklist.getId(), itens, nomeToId, userId);

        // Salvar operadores da junction table (Plenário Principal)
        Sala sala = salaRepo.findById(salaId).orElse(null);
        if (sala != null && Boolean.TRUE.equals(sala.getMultiOperador())) {
            @SuppressWarnings("unchecked")
            List<String> cabineOps = body.get("operadores_cabine") instanceof List
                    ? (List<String>) body.get("operadores_cabine")
                    : List.of();
            @SuppressWarnings("unchecked")
            List<String> plenarioOps = body.get("operadores_plenario") instanceof List
                    ? (List<String>) body.get("operadores_plenario")
                    : List.of();

            salvarOperadoresChecklist(checklist.getId(), cabineOps, "CABINE");
            salvarOperadoresChecklist(checklist.getId(), plenarioOps, "PLENARIO");
        }

        return Map.of("checklist_id", checklist.getId(), "total_respostas", totalRespostas);
    }

    /** Turno informado, ou inferido pela hora de início (Matutino &lt; 13h &le; Vespertino). */
    private Turno inferirTurno(String turnoRaw, String horaInicio) {
        if (turnoRaw.isEmpty()) {
            try {
                int hora = Integer.parseInt(horaInicio.split(":")[0]);
                turnoRaw = hora < 13 ? "Matutino" : "Vespertino";
            } catch (Exception e) {
                turnoRaw = "Matutino";
            }
        }
        return Turno.fromValor(turnoRaw);
    }

    /** Bloqueia reenvio do mesmo operador para a mesma sala em menos de 5 minutos. */
    private void verificarDuplicataRecente(int salaId, String userId) {
        Number dupCheck = (Number) entityManager.createNativeQuery("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM FRM_CHECKLIST
                    WHERE SALA_ID = ?1 AND CRIADO_POR = ?2
                    AND CRIADO_EM >= SYSTIMESTAMP - INTERVAL '5' MINUTE
                ) THEN 1 ELSE 0 END FROM DUAL
                """).setParameter(1, salaId).setParameter(2, userId).getSingleResult();
        if (dupCheck.intValue() == 1) {
            throw new ServiceValidationException(
                    "Já existe uma verificação sua para este local enviada há menos de 5 minutos. Aguarde antes de enviar novamente.");
        }
    }

    /** Insere as respostas do checklist recém-criado e retorna o total inserido. */
    private int inserirRespostas(long checklistId, List<Map<String, Object>> itens,
            Map<String, Integer> nomeToId, String userId) {
        int totalRespostas = 0;
        for (Map<String, Object> item : itens) {
            Integer tipoId = resolveItemTipoId(item, nomeToId);
            if (tipoId == null)
                continue;
            String status = str(item.get("status")).strip();
            String valorTexto = str(item.get("valor_texto")).strip();
            if (status.isEmpty() && !valorTexto.isEmpty())
                status = "Ok";
            if (status.isEmpty())
                continue;

            ChecklistResposta resp = new ChecklistResposta();
            resp.setChecklistId(checklistId);
            resp.setItemTipoId(tipoId);
            resp.setStatus(StatusResposta.fromValor(status));
            resp.setDescricaoFalha(blankToNull(str(item.get("descricao_falha"))));
            resp.setValorTexto(blankToNull(valorTexto));
            resp.setCriadoPor(userId);
            resp.setAtualizadoPor(userId);
            respostaRepo.save(resp);
            totalRespostas++;
        }
        return totalRespostas;
    }

    // ── Editar checklist ─────────────────────────

    /**
     * PUT /api/forms/checklist/editar
     * Equivale a editar_checklist() do Python.
     * Salva snapshot no histórico antes de aplicar alterações.
     */
    @Transactional
    public Map<String, Object> editar(long checklistId, Map<String, Object> body, String userId) {
        // Ownership do titular (contrato do endpoint): 404 antes de 403, para não vazar IDs
        String owner = checklistRepo.findCriadoPorById(checklistId).orElse(null);
        if (owner == null) throw new ServiceValidationException("not_found", HttpStatus.NOT_FOUND);
        if (!owner.equals(userId)) throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);

        if (userId == null || userId.isBlank()) throw new ServiceValidationException("Usuário não autenticado.");
        validarPermissaoEdicao(checklistId, userId);

        // Campos obrigatórios
        if (str(body.get("data_operacao")).strip().isEmpty() || str(body.get("sala_id")).strip().isEmpty())
            throw new ServiceValidationException("Campos obrigatórios ausentes.");

        String dataOperacao = str(body.get("data_operacao")).strip();
        int salaId;
        try {
            salaId = Integer.parseInt(str(body.get("sala_id")).strip());
        } catch (Exception e) {
            throw new ServiceValidationException("Local inválido.");
        }
        String observacoes = blankToNull(str(body.get("observacoes")));

        List<Map<String, Object>> itens = itensDoBody(body);
        validarItens(itens);

        Checklist cl = checklistRepo.findById(checklistId)
                .orElseThrow(() -> new ServiceValidationException("Checklist não encontrado."));

        Map<String, Object> snapshot = montarSnapshot(cl, checklistId);
        salvarHistorico(checklistId, snapshot, userId);
        atualizarCabecalho(cl, dataOperacao, salaId, observacoes, userId);
        int totalAtualizado = sincronizarRespostas(checklistId, itens, userId);
        sincronizarOperadores(checklistId, salaId, body);

        return new LinkedHashMap<>(Map.of("checklist_id", checklistId, "total_respostas_atualizadas", totalAtualizado));
    }

    /** Monta o snapshot (header + itens + operadores) do estado atual, para o histórico. */
    private Map<String, Object> montarSnapshot(Checklist cl, long checklistId) {
        List<Object[]> respostasRows = respostaRepo.findByChecklistIdNative(checklistId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("data_operacao", cl.getDataOperacao() != null ? cl.getDataOperacao().toString() : null);
        header.put("sala_id", cl.getSalaId());
        header.put("turno", cl.getTurno() != null ? cl.getTurno().getValor() : null);
        header.put("hora_inicio_testes", cl.getHoraInicioTestes());
        header.put("hora_termino_testes", cl.getHoraTerminoTestes());
        header.put("observacoes", cl.getObservacoes());
        header.put("usb_01", cl.getUsb01());
        header.put("usb_02", cl.getUsb02());
        snapshot.put("header", header);
        List<Map<String, Object>> snapItens = new ArrayList<>();
        for (Object[] r : respostasRows) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("resposta_id", ((Number) r[0]).longValue());
            si.put("item_tipo_id", ((Number) r[1]).intValue());
            si.put("status", NativeQueryUtils.str(r[2]));
            si.put("descricao_falha", NativeQueryUtils.str(r[3]));
            si.put("valor_texto", NativeQueryUtils.str(r[4]));
            snapItens.add(si);
        }
        snapshot.put("itens", snapItens);

        // Snapshot dos operadores (se multi-operador)
        List<ChecklistOperador> opSnap = checklistOperadorRepo.findByChecklistId(checklistId);
        if (!opSnap.isEmpty()) {
            List<String> snapCabine = new ArrayList<>(), snapPlenario = new ArrayList<>();
            for (ChecklistOperador co : opSnap) {
                if ("CABINE".equals(co.getPapel()))
                    snapCabine.add(co.getOperadorId());
                else
                    snapPlenario.add(co.getOperadorId());
            }
            snapshot.put("operadores_cabine", snapCabine);
            snapshot.put("operadores_plenario", snapPlenario);
        }
        return snapshot;
    }

    /** Serializa o snapshot em JSON e persiste o registro de histórico. */
    private void salvarHistorico(long checklistId, Map<String, Object> snapshot, String userId) {
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        ChecklistHistorico hist = new ChecklistHistorico();
        hist.setChecklistId(checklistId);
        hist.setSnapshot(snapshotJson);
        hist.setEditadoPor(userId);
        entityManager.persist(hist);
    }

    /** Atualiza o cabeçalho do checklist, marcando observacoes_editado se mudou. */
    private void atualizarCabecalho(Checklist cl, String dataOperacao, int salaId, String observacoes, String userId) {
        String obsAnterior = cl.getObservacoes() != null ? cl.getObservacoes() : "";
        String obsNova = observacoes != null ? observacoes : "";
        boolean obsMudou = !obsAnterior.equals(obsNova);

        cl.setDataOperacao(LocalDate.parse(dataOperacao));
        cl.setSalaId(salaId);
        cl.setObservacoes(observacoes);
        cl.setEditado(true);
        if (obsMudou)
            cl.setObservacoesEditado(true);
        cl.setAtualizadoPor(userId);
        checklistRepo.save(cl);
    }

    /** Sincroniza as respostas (remove órfãs + upsert) e retorna o total atualizado/criado. */
    private int sincronizarRespostas(long checklistId, List<Map<String, Object>> itens, String userId) {
        // Coletar IDs dos itens enviados pelo frontend (itens da nova sala)
        Set<Integer> itensTipoEnviados = new HashSet<>();
        for (Map<String, Object> item : itens) {
            Integer tid = resolveItemTipoId(item, Map.of());
            if (tid != null)
                itensTipoEnviados.add(tid);
        }

        // Deletar respostas de itens que não pertencem à nova sala
        if (!itensTipoEnviados.isEmpty()) {
            entityManager.createNativeQuery("""
                    DELETE FROM FRM_CHECKLIST_RESPOSTA
                    WHERE CHECKLIST_ID = :cid AND ITEM_TIPO_ID NOT IN (:ids)
                    """).setParameter("cid", checklistId)
                    .setParameter("ids", itensTipoEnviados)
                    .executeUpdate();
        }

        // Carregar as respostas restantes 1× (DEPOIS do DELETE) e indexar por item_tipo_id, em vez
        // de um SELECT por item (Q17a). A UQ (CHECKLIST_ID, ITEM_TIPO_ID) garante no máx. 1 resposta
        // por item → chave única no mapa; as entidades vêm gerenciadas (dirty check no commit).
        Map<Integer, ChecklistResposta> respostasPorTipo = new HashMap<>();
        for (ChecklistResposta r : respostaRepo.findByChecklistId(checklistId)) {
            respostasPorTipo.put(r.getItemTipoId(), r);
        }

        // Atualizar ou criar respostas
        int totalAtualizado = 0;
        for (Map<String, Object> item : itens) {
            Integer tipoId = resolveItemTipoId(item, Map.of());
            if (tipoId == null)
                continue;

            String status = str(item.get("status")).strip();
            String valorTexto = str(item.get("valor_texto")).strip();
            if (status.isEmpty() && !valorTexto.isEmpty())
                status = "Ok";
            if (status.isEmpty())
                continue;

            String descFalha = blankToNull(str(item.get("descricao_falha")));
            String valorTextoNull = blankToNull(valorTexto);

            ChecklistResposta resp = respostasPorTipo.get(tipoId);
            if (resp != null) {
                // Detectar mudanças
                boolean mudou = !status.equals(resp.getStatus() != null ? resp.getStatus().getValor() : "")
                        || !Objects.equals(descFalha, resp.getDescricaoFalha())
                        || !Objects.equals(valorTextoNull, resp.getValorTexto());

                resp.setStatus(StatusResposta.fromValor(status));
                resp.setDescricaoFalha(descFalha);
                resp.setValorTexto(valorTextoNull);
                if (mudou)
                    resp.setEditado(true);
                resp.setAtualizadoPor(userId);
                respostaRepo.save(resp);
                totalAtualizado++;
            } else {
                // Item novo (sala mudou) — criar resposta
                ChecklistResposta novo = new ChecklistResposta();
                novo.setChecklistId(checklistId);
                novo.setItemTipoId(tipoId);
                novo.setStatus(StatusResposta.fromValor(status));
                novo.setDescricaoFalha(descFalha);
                novo.setValorTexto(valorTextoNull);
                novo.setEditado(true);
                novo.setCriadoPor(userId);
                novo.setAtualizadoPor(userId);
                respostaRepo.save(novo);
                // Indexa a nova resposta: se o mesmo item_tipo_id reaparecer entre os itens enviados,
                // a 2ª ocorrência vira UPDATE (como o findByChecklistAndItem reencontrava a linha via
                // autoflush) — preserva o comportamento e evita violar a UQ.
                respostasPorTipo.put(tipoId, novo);
                totalAtualizado++;
            }
        }
        return totalAtualizado;
    }

    /** Sincroniza a junction table de operadores (Plenário Principal) conforme a sala. */
    private void sincronizarOperadores(long checklistId, int salaId, Map<String, Object> body) {
        Sala sala = salaRepo.findById(salaId).orElse(null);
        boolean isMultiOp = sala != null && Boolean.TRUE.equals(sala.getMultiOperador());
        if (!isMultiOp) {
            // Sala não é multi-operador: limpar operadores antigos
            checklistOperadorRepo.deleteByChecklistId(checklistId);
        } else {
            @SuppressWarnings("unchecked")
            List<String> cabineOps = body.get("operadores_cabine") instanceof List
                    ? (List<String>) body.get("operadores_cabine")
                    : null;
            @SuppressWarnings("unchecked")
            List<String> plenarioOps = body.get("operadores_plenario") instanceof List
                    ? (List<String>) body.get("operadores_plenario")
                    : null;

            // Só atualiza se o frontend enviou os dados
            if (cabineOps != null || plenarioOps != null) {
                checklistOperadorRepo.deleteByChecklistId(checklistId);
                if (cabineOps != null)
                    salvarOperadoresChecklist(checklistId, cabineOps, "CABINE");
                if (plenarioOps != null)
                    salvarOperadoresChecklist(checklistId, plenarioOps, "PLENARIO");
            }
        }
    }

    // ── Itens tipo por sala ──────────────────────

    /**
     * GET /api/forms/checklist/itens-tipo?sala_id=...
     * Equivale a checklist_itens_tipo_view() + list_checklist_itens_por_sala() do
     * Python.
     */
    public List<Map<String, Object>> itensTipoPorSala(int salaId) {
        List<Object[]> rows = itemTipoRepo.findItensPorSala(salaId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r[0]).intValue());
            m.put("nome", r[1] != null ? r[1].toString() : "");
            m.put("ordem", r[2] != null ? ((Number) r[2]).intValue() : null);
            m.put("tipo_widget", r[3] != null ? r[3].toString() : "radio");
            result.add(m);
        }
        return result;
    }
}

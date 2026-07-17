package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.RegistroAnormalidadeRepository;
import br.leg.senado.nusp.repository.RegistroOperacaoOperadorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

import static br.leg.senado.nusp.service.NativeQueryUtils.blankToNull;
import static br.leg.senado.nusp.service.NativeQueryUtils.boolVal;
import static br.leg.senado.nusp.service.NativeQueryUtils.clean;
import static br.leg.senado.nusp.service.NativeQueryUtils.num;
import static br.leg.senado.nusp.service.NativeQueryUtils.str;

/**
 * Equivale ao anormalidade_service.py do Python (207 linhas).
 *
 * PONTO CRÍTICO: contém syncHouveAnormalidade() que substitui a trigger
 * operacao.sync_houve_anormalidade() do PostgreSQL. Esta é a única lógica
 * de negócio que estava no banco e agora é 100% responsabilidade do Java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnormalidadeService {

    private final RegistroAnormalidadeRepository anormalidadeRepo;
    private final RegistroOperacaoOperadorRepository entradaRepo;

    // ══════════════════════════════════════════════════════════
    //  syncHouveAnormalidade — SUBSTITUI TRIGGER DO POSTGRESQL
    // ══════════════════════════════════════════════════════════

    /**
     * Recalcula o campo houve_anormalidade na tabela OPR_REGISTRO_ENTRADA
     * com base na existência de registros na tabela OPR_ANORMALIDADE.
     *
     * Equivale à trigger function operacao.sync_houve_anormalidade() do PostgreSQL.
     *
     * DEVE ser chamado após qualquer INSERT, UPDATE ou DELETE em OPR_ANORMALIDADE
     * que envolva o campo entrada_id. Se o entrada_id mudou (UPDATE), chamar
     * para AMBOS os valores (antigo e novo).
     *
     * @param entradaId ID da entrada (OPR_REGISTRO_ENTRADA) a recalcular
     */
    public void syncHouveAnormalidade(Long entradaId) {
        if (entradaId == null) return;
        boolean existe = anormalidadeRepo.existsByEntradaId(entradaId);
        anormalidadeRepo.updateHouveAnormalidade(entradaId, existe ? 1 : 0);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private static boolean parseBool(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase();
        return "true".equals(lower) || "1".equals(lower) || "sim".equals(lower)
                || "yes".equals(lower) || "t".equals(lower);
    }

    // ══════════════════════════════════════════════════════════
    //  Registrar / Editar Anormalidade
    // ══════════════════════════════════════════════════════════

    /**
     * POST /api/operacao/anormalidade/registro
     * Equivale a registrar_anormalidade() do Python.
     *
     * Substitui CHECK constraints do PostgreSQL:
     *   - ck_prejuizo_desc → se houve_prejuizo, descricao_prejuizo obrigatória
     *   - ck_reclamacao_desc → se houve_reclamacao, autores obrigatórios
     *   - ck_manutencao_hora → se acionou_manutencao, hora obrigatória
     *   - ck_datas_coerentes → data_solucao >= data, hora_solucao >= hora_inicio
     */
    @Transactional
    public Map<String, Object> registrar(Map<String, Object> body, String userId) {
        CamposAnormalidade d = lerCampos(body);

        // Validações — substituem CHECK constraints do banco
        Map<String, String> errors = new LinkedHashMap<>();
        Long entradaId = validarCampos(d, errors);
        if (!errors.isEmpty())
            throw new ServiceValidationException("Erros de validação nos campos.");

        // Conversões numéricas (ordem registro → sala → anomId é contrato)
        IdsAnormalidade ids = converterIds(d);
        long registroId = ids.registroId();
        int salaId = ids.salaId();
        Long anomId = ids.anomId();

        long resultId;
        if (anomId != null) {
            // ── EDIÇÃO ──
            RegistroAnormalidade anom = anormalidadeRepo.findById(anomId)
                    .orElseThrow(() -> new ServiceValidationException("Anormalidade não encontrada."));

            Long entradaIdAnterior = anom.getEntradaId();

            aplicarCampos(anom, d, salaId, userId);

            anormalidadeRepo.save(anom);
            resultId = anomId;

            // ── syncHouveAnormalidade (UPDATE) — se entrada_id mudou, sincronizar AMBAS ──
            syncHouveAnormalidade(anom.getEntradaId());
            if (entradaIdAnterior != null && !entradaIdAnterior.equals(anom.getEntradaId())) {
                syncHouveAnormalidade(entradaIdAnterior);
            }

        } else {
            // ── CRIAÇÃO ──
            RegistroAnormalidade anom = new RegistroAnormalidade();
            anom.setRegistroId(registroId);
            anom.setEntradaId(entradaId);
            aplicarCampos(anom, d, salaId, userId);
            anom.setCriadoPor(userId);

            anom = anormalidadeRepo.save(anom);
            resultId = anom.getId();

            // ── syncHouveAnormalidade (INSERT) ──
            syncHouveAnormalidade(entradaId);
        }

        return new java.util.LinkedHashMap<>(Map.of("registro_anormalidade_id", resultId, "registro_id", registroId));
    }

    /** Record de STRINGS CRUAS (sem parse) — o parse acontece só nas fases posteriores. */
    private record CamposAnormalidade(
            String registroIdRaw, String dataStr, String salaIdRaw, String nomeEvento,
            String horaInicioAnormalidade, String descricaoAnormalidade,
            boolean houvePrejuizo, String descricaoPrejuizo,
            boolean houveReclamacao, String autoresConteudoReclamacao,
            boolean acionouManutencao, String horaAcionamentoManutencao,
            boolean resolvidaPeloOperador, String procedimentosAdotados,
            String dataSolucao, String horaSolucao, String responsavelEvento,
            String entradaIdRaw, String anomIdRaw) {}

    private record IdsAnormalidade(long registroId, int salaId, Long anomId) {}

    /** Fase 1: leitura/normalização dos campos do body (nenhum parse que lança). */
    private CamposAnormalidade lerCampos(Map<String, Object> body) {
        String registroIdRaw = clean(body, "registro_id");
        String dataStr = clean(body, "data");
        if (dataStr.length() > 10) dataStr = dataStr.substring(0, 10); // "2026-03-19 00:00:00.0" → "2026-03-19"
        String salaIdRaw = clean(body, "sala_id");
        String nomeEvento = clean(body, "nome_evento");
        String horaInicioAnormalidade = clean(body, "hora_inicio_anormalidade");
        String descricaoAnormalidade = clean(body, "descricao_anormalidade");
        boolean houvePrejuizo = parseBool(clean(body, "houve_prejuizo"));
        String descricaoPrejuizo = clean(body, "descricao_prejuizo");
        boolean houveReclamacao = parseBool(clean(body, "houve_reclamacao"));
        String autoresConteudoReclamacao = clean(body, "autores_conteudo_reclamacao");
        boolean acionouManutencao = parseBool(clean(body, "acionou_manutencao"));
        String horaAcionamentoManutencao = clean(body, "hora_acionamento_manutencao");
        boolean resolvidaPeloOperador = parseBool(clean(body, "resolvida_pelo_operador"));
        String procedimentosAdotados = clean(body, "procedimentos_adotados");
        String dataSolucao = clean(body, "data_solucao");
        String horaSolucao = clean(body, "hora_solucao");
        String responsavelEvento = clean(body, "responsavel_evento");
        String entradaIdRaw = clean(body, "entrada_id");
        String anomIdRaw = clean(body, "id");
        if (anomIdRaw.isEmpty()) anomIdRaw = clean(body, "registro_anormalidade_id");
        return new CamposAnormalidade(registroIdRaw, dataStr, salaIdRaw, nomeEvento,
                horaInicioAnormalidade, descricaoAnormalidade, houvePrejuizo, descricaoPrejuizo,
                houveReclamacao, autoresConteudoReclamacao, acionouManutencao, horaAcionamentoManutencao,
                resolvidaPeloOperador, procedimentosAdotados, dataSolucao, horaSolucao, responsavelEvento,
                entradaIdRaw, anomIdRaw);
    }

    /** Fase 2: validações (preenche errors na mesma ordem) e parse protegido de entrada_id. */
    private Long validarCampos(CamposAnormalidade d, Map<String, String> errors) {
        if (d.registroIdRaw().isEmpty()) errors.put("registro_id", "Campo obrigatório.");
        if (d.dataStr().isEmpty()) errors.put("data", "Campo obrigatório.");
        if (d.salaIdRaw().isEmpty()) errors.put("sala_id", "Campo obrigatório.");
        if (d.nomeEvento().isEmpty()) errors.put("nome_evento", "Campo obrigatório.");
        if (d.horaInicioAnormalidade().isEmpty()) errors.put("hora_inicio_anormalidade", "Campo obrigatório.");
        if (d.descricaoAnormalidade().isEmpty()) errors.put("descricao_anormalidade", "Campo obrigatório.");
        if (d.responsavelEvento().isEmpty()) errors.put("responsavel_evento", "Campo obrigatório.");

        // ck_prejuizo_desc
        if (d.houvePrejuizo() && d.descricaoPrejuizo().isEmpty())
            errors.put("descricao_prejuizo", "Campo obrigatório quando houve prejuízo.");

        // ck_reclamacao_desc
        if (d.houveReclamacao() && d.autoresConteudoReclamacao().isEmpty())
            errors.put("autores_conteudo_reclamacao", "Campo obrigatório quando houve reclamação.");

        // ck_manutencao_hora
        if (d.acionouManutencao() && d.horaAcionamentoManutencao().isEmpty())
            errors.put("hora_acionamento_manutencao", "Campo obrigatório quando houve acionamento de manutenção.");

        // Validação extra: resolvida exige procedimentos
        if (d.resolvidaPeloOperador() && d.procedimentosAdotados().isEmpty())
            errors.put("procedimentos_adotados", "Campo obrigatório quando a anormalidade foi resolvida pelo operador.");

        // Régua de horário (F73/C19): recusa hora torta fail-fast, ANTES da coerência
        // lexicográfica abaixo — uma torta não pode virar mensagem de ordem sem sentido.
        // Anormalidade grava como chega (formato vivo do banco: HH:MM do <input type="time">,
        // medido em 16/07), por isso a régua aqui só valida, sem completar segundos.
        HoraValidator.normalizar(d.horaInicioAnormalidade(), "Horário do início da anormalidade", false);
        HoraValidator.normalizar(d.horaAcionamentoManutencao(), "Horário do acionamento da manutenção", false);
        HoraValidator.normalizar(d.horaSolucao(), "Hora da solução", false);

        // ck_datas_coerentes
        if (!d.dataSolucao().isEmpty()) {
            if (d.dataSolucao().compareTo(d.dataStr()) < 0)
                errors.put("data_solucao", "Data da solução da anormalidade não pode ser anterior à data da ocorrência.");
            else if (d.dataSolucao().equals(d.dataStr()) && !d.horaSolucao().isEmpty() && !d.horaInicioAnormalidade().isEmpty()
                    && d.horaSolucao().compareTo(d.horaInicioAnormalidade()) < 0)
                errors.put("hora_solucao", "Hora da solução não pode ser anterior ao início da anormalidade.");
        }

        // entrada_id — opcional, mas validado se presente
        Long entradaId = null;
        if (!d.entradaIdRaw().isEmpty()) {
            try {
                long val = Long.parseLong(d.entradaIdRaw());
                if (val <= 0) throw new NumberFormatException();
                entradaId = val;
            } catch (NumberFormatException e) {
                errors.put("entrada_id", "Entrada inválida.");
            }
        }
        return entradaId;
    }

    /** Fase 3: conversões numéricas na ordem registro → sala → anomId (a 1ª exceção é a que sai). */
    private IdsAnormalidade converterIds(CamposAnormalidade d) {
        long registroId;
        try { registroId = Long.parseLong(d.registroIdRaw()); }
        catch (NumberFormatException e) { throw new ServiceValidationException("Registro inválido."); }

        int salaId;
        try { salaId = Integer.parseInt(d.salaIdRaw()); }
        catch (NumberFormatException e) { throw new ServiceValidationException("Local inválido."); }

        Long anomId = null;
        if (!d.anomIdRaw().isEmpty()) {
            try {
                long val = Long.parseLong(d.anomIdRaw());
                if (val <= 0) throw new NumberFormatException();
                anomId = val;
            } catch (NumberFormatException e) {
                throw new ServiceValidationException("Registro de anormalidade inválido.");
            }
        }
        return new IdsAnormalidade(registroId, salaId, anomId);
    }

    /**
     * Fase 4: os 17 setters compartilhados entre edição e criação, com LocalDate.parse/blankToNull
     * INLINE como no código original. Chamado DEPOIS do findById (edição) e DEPOIS do new (criação),
     * o que preserva "id inexistente + data malformada → 400 Anormalidade não encontrada.".
     * NÃO seta registroId/entradaId/criadoPor (exclusivos da criação — a edição NÃO seta entradaId).
     */
    private void aplicarCampos(RegistroAnormalidade anom, CamposAnormalidade d, int salaId, String userId) {
        anom.setData(LocalDate.parse(d.dataStr()));
        anom.setSalaId(salaId);
        anom.setNomeEvento(d.nomeEvento());
        anom.setHoraInicioAnormalidade(d.horaInicioAnormalidade());
        anom.setDescricaoAnormalidade(d.descricaoAnormalidade());
        anom.setHouvePrejuizo(d.houvePrejuizo());
        anom.setDescricaoPrejuizo(blankToNull(d.descricaoPrejuizo()));
        anom.setHouveReclamacao(d.houveReclamacao());
        anom.setAutoresConteudoReclamacao(blankToNull(d.autoresConteudoReclamacao()));
        anom.setAcionouManutencao(d.acionouManutencao());
        anom.setHoraAcionamentoManutencao(blankToNull(d.horaAcionamentoManutencao()));
        anom.setResolvidaPeloOperador(d.resolvidaPeloOperador());
        anom.setProcedimentosAdotados(blankToNull(d.procedimentosAdotados()));
        anom.setDataSolucao(d.dataSolucao().isEmpty() ? null : LocalDate.parse(d.dataSolucao()));
        anom.setHoraSolucao(blankToNull(d.horaSolucao()));
        anom.setResponsavelEvento(d.responsavelEvento());
        anom.setAtualizadoPor(userId);
    }

    // ══════════════════════════════════════════════════════════
    //  Buscar anormalidade por entrada (para edição)
    // ══════════════════════════════════════════════════════════

    /**
     * Puts dos 14 campos comuns do payload de detalhe de anormalidade
     * (nome_evento → responsavel_evento), com índices relativos a base.
     * Compartilhado pelo form de edição (base=5), pelo detalhe do admin (base=6)
     * e pelo detalhe do operador (base=3) — a ordem de inserção é contrato JSON.
     */
    public static void putCamposAnormalidade(Map<String, Object> result, Object[] r, int base) {
        result.put("nome_evento", str(r[base]));
        result.put("hora_inicio_anormalidade", str(r[base + 1]));
        result.put("descricao_anormalidade", str(r[base + 2]));
        result.put("houve_prejuizo", boolVal(r[base + 3]));
        result.put("descricao_prejuizo", str(r[base + 4]));
        result.put("houve_reclamacao", boolVal(r[base + 5]));
        result.put("autores_conteudo_reclamacao", str(r[base + 6]));
        result.put("acionou_manutencao", boolVal(r[base + 7]));
        result.put("hora_acionamento_manutencao", str(r[base + 8]));
        result.put("resolvida_pelo_operador", boolVal(r[base + 9]));
        result.put("procedimentos_adotados", str(r[base + 10]));
        result.put("data_solucao", str(r[base + 11]));
        result.put("hora_solucao", str(r[base + 12]));
        result.put("responsavel_evento", str(r[base + 13]));
    }

    /**
     * Acesso do usuário a uma entrada para consulta do RAOA: dono principal OU
     * co-operador em Plenário Principal (OPR_ENTRADA_OPERADOR) — semântica
     * própria deste endpoint. Verifica existência antes do acesso (404 antes
     * de 403, para não vazar IDs).
     */
    public void validarAcessoEntrada(long entradaId, String userId) {
        String entradaOwner = entradaRepo.findOperadorIdByEntradaId(entradaId).orElse(null);
        if (entradaOwner == null)
            throw new ServiceValidationException("not_found", HttpStatus.NOT_FOUND);

        if (entradaRepo.countOperadorAcessoEntrada(entradaId, userId) == 0)
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
    }

    /**
     * GET /api/operacao/anormalidade/registro?entrada_id=...
     * Equivale a get_registro_anormalidade_por_entrada() do Python.
     */
    public Map<String, Object> buscarPorEntrada(long entradaId) {
        List<Object[]> rows = anormalidadeRepo.findByEntradaIdNative(entradaId);
        if (rows.isEmpty()) return null;

        Object[] r = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", num(r[0]));
        result.put("registro_id", num(r[1]));
        result.put("entrada_id", num(r[2]));
        result.put("data", str(r[3]));
        result.put("sala_id", r[4] != null ? ((Number) r[4]).intValue() : null);
        putCamposAnormalidade(result, r, 5);

        // Campo derivado para o frontend
        result.put("anormalidade_solucionada", str(r[16]) != null || str(r[17]) != null);

        return result;
    }
}

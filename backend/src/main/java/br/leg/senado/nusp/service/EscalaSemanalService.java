package br.leg.senado.nusp.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalaSemanalService {

    private final EscalaSemanalRepository escalaRepo;
    private final EscalaOperadorRepository escalaOpRepo;
    private final EscalaFuncaoRepository escalaFuncaoRepo;
    private final SalaRepository salaRepo;
    private final OperadorRepository operadorRepo;
    private final AdministradorRepository adminRepo;
    private final AvisoService avisoService;

    /** Tipos de função aceitos em OPR_ESCALA_FUNCAO. */
    private static final Set<String> TIPOS_FUNCAO = Set.of("APOIO_COMISSOES", "FECHAMENTO");

    // ══ Listar escalas ══════════════════════════════════════════

    public List<Map<String, Object>> listarEscalas() {
        var escalas = escalaRepo.findAllOrderByDataInicioDesc();
        return escalas.stream().map(this::toMap).collect(Collectors.toList());
    }

    /** Paginado — retorna {data, meta} compatível com o PaginationComponent do frontend. */
    public Map<String, Object> listarEscalasPaginado(int page, int limit) {
        if (page < 1) page = 1;
        if (limit < 1) limit = 10;
        var todas = listarEscalas();
        int total = todas.size();
        int pages = total == 0 ? 1 : (int) Math.ceil((double) total / limit);
        int fromIdx = Math.min((page - 1) * limit, total);
        int toIdx = Math.min(fromIdx + limit, total);
        return Map.of(
                "data", todas.subList(fromIdx, toIdx),
                "meta", Map.of("page", page, "limit", limit, "total", total, "pages", pages)
        );
    }

    // ══ Obter escala com operadores ═════════════════════════════

    public Map<String, Object> obterEscala(Long id) {
        var escala = escalaRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        var result = toMap(escala);

        var vinculos = escalaOpRepo.findByEscalaId(id); // já ordenado por sala_id, turno (M antes de V)
        // Agrupar por sala_id (IDs dos operadores — para edição)
        Map<Integer, List<String>> porSala = new LinkedHashMap<>();
        // Mesmo agrupamento preservando o turno de cada operador (para rótulo M/V no detalhe)
        Map<Integer, List<String[]>> turnoPorSala = new LinkedHashMap<>();
        for (var v : vinculos) {
            porSala.computeIfAbsent(v.getSalaId(), k -> new ArrayList<>()).add(v.getOperadorId());
            turnoPorSala.computeIfAbsent(v.getSalaId(), k -> new ArrayList<>())
                    .add(new String[]{v.getOperadorId(), v.getTurno()});
        }
        result.put("salas", porSala);

        // Funções (Apoio às Comissões, Fechamento dos Plenários, ...)
        Map<String, List<String>> porFuncao = new LinkedHashMap<>();
        for (var f : escalaFuncaoRepo.findByEscalaId(id)) {
            porFuncao.computeIfAbsent(f.getTipo(), k -> new ArrayList<>()).add(f.getOperadorId());
        }
        result.put("funcoes", porFuncao);

        // Resumo com nomes — para visualização expandida.
        // Mapa de operadores carregado 1× (PES_OPERADOR ~22 linhas) no lugar de findById por operador (Q18).
        var operadores = operadorRepo.findAll().stream().collect(Collectors.toMap(o -> o.getId(), o -> o));
        List<Map<String, Object>> resumo = new ArrayList<>();
        var salasOrdenadas = salaRepo.findAtivasOrdenadas();
        for (var sala : salasOrdenadas) {
            var ops = turnoPorSala.get(sala.getId());
            if (ops == null || ops.isEmpty()) continue;
            resumo.add(montarItemResumo(sala.getId(), sala.getNome(), ops, true, operadores));
        }
        // Anexar funções ao resumo na ordem fixa: Apoio antes, Fechamento depois
        adicionarFuncaoNoResumo(resumo, porFuncao, "APOIO_COMISSOES", "Apoio às Comissões", operadores);
        adicionarFuncaoNoResumo(resumo, porFuncao, "FECHAMENTO", "Fechamento dos Plenários", operadores);

        result.put("resumo", resumo);
        return result;
    }

    private void adicionarFuncaoNoResumo(List<Map<String, Object>> resumo,
                                         Map<String, List<String>> porFuncao,
                                         String tipo, String label,
                                         Map<String, Operador> operadores) {
        var ops = porFuncao.get(tipo);
        if (ops == null || ops.isEmpty()) return;
        List<String[]> pares = ops.stream().map(id -> new String[]{id}).collect(Collectors.toList());
        resumo.add(montarItemResumo(null, label, pares, false, operadores));
    }

    private Map<String, Object> montarItemResumo(Integer salaId, String salaNome,
                                                 List<String[]> pares, boolean comDetalhe,
                                                 Map<String, Operador> operadores) {
        List<String> nomes = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> detalhe = new ArrayList<>();
        for (String[] par : pares) {
            String opId = par[0];
            Operador op = operadores.get(opId);
            if (op != null) {
                nomes.add(op.getNomeExibicao());
                ids.add(op.getId());
                if (comDetalhe) {
                    Map<String, Object> od = new LinkedHashMap<>();
                    od.put("id", op.getId());
                    od.put("nome", op.getNomeExibicao());
                    od.put("turno", par[1]);
                    detalhe.add(od);
                }
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        if (salaId != null) item.put("sala_id", salaId);
        item.put("sala_nome", salaNome);
        item.put("operadores", String.join(", ", nomes));
        item.put("operadores_ids", ids);
        item.put("operadores_detalhe", detalhe);
        return item;
    }

    // ══ Criar/Atualizar escala ══════════════════════════════════

    @Transactional
    public Map<String, Object> salvarEscala(Long id, LocalDate dataInicio, LocalDate dataFim,
                                            Map<Integer, List<String>> salasOperadores,
                                            Map<Integer, Map<String, String>> turnosPorSala,
                                            Map<String, List<String>> funcoes,
                                            String criadoPor) {
        validarPeriodo(dataInicio, dataFim);

        EscalaSemanal escala;
        if (id != null) {
            escala = escalaRepo.findById(id)
                    .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        } else {
            escala = new EscalaSemanal();
            escala.setCriadoPor(criadoPor);
        }

        escala.setDataInicio(dataInicio);
        escala.setDataFim(dataFim);
        escala = escalaRepo.save(escala);

        // Recriar vínculos de sala. O turno vem do payload (definido/invertido no editor);
        // se não vier (ex.: geração por rodízio), usa o turno padrão do operador (PES_OPERADOR).
        escalaOpRepo.deleteByEscalaId(escala.getId());
        if (salasOperadores != null) {
            for (var entry : salasOperadores.entrySet()) {
                int salaId = entry.getKey();
                Map<String, String> turnosSala = (turnosPorSala != null) ? turnosPorSala.get(salaId) : null;
                for (String operadorId : entry.getValue()) {
                    var eo = new EscalaOperador();
                    eo.setEscalaId(escala.getId());
                    eo.setSalaId(salaId);
                    eo.setOperadorId(operadorId);
                    String turnoPayload = (turnosSala != null) ? turnosSala.get(operadorId) : null;
                    if ("M".equals(turnoPayload) || "V".equals(turnoPayload)) {
                        eo.setTurno(turnoPayload);
                    } else {
                        eo.setTurno(operadorRepo.findById(operadorId)
                                .map(o -> o.getTurno() != null ? o.getTurno() : "M").orElse("M"));
                    }
                    escalaOpRepo.save(eo);
                }
            }
        }

        // Recriar vínculos de função (Apoio, Fechamento, etc.)
        escalaFuncaoRepo.deleteByEscalaId(escala.getId());
        if (funcoes != null) {
            for (var entry : funcoes.entrySet()) {
                String tipo = entry.getKey();
                if (!TIPOS_FUNCAO.contains(tipo)) {
                    throw new ServiceValidationException("Tipo de função inválido: " + tipo);
                }
                for (String operadorId : entry.getValue()) {
                    var ef = new EscalaFuncao();
                    ef.setEscalaId(escala.getId());
                    ef.setTipo(tipo);
                    ef.setOperadorId(operadorId);
                    escalaFuncaoRepo.save(ef);
                }
            }
        }

        log.info("Escala #{} salva: {} a {} por {}", escala.getId(), dataInicio, dataFim, criadoPor);
        return obterEscala(escala.getId());
    }

    // ══ Excluir escala ══════════════════════════════════════════

    @Transactional
    public void excluirEscala(Long id) {
        var escala = escalaRepo.findById(id)
                .orElseThrow(() -> new ServiceValidationException("Escala não encontrada.", HttpStatus.NOT_FOUND));
        // F59: o aviso de ESCALA aponta para esta escala (FK ESCALA_ID) — apaga-o ANTES (com
        // mensagens/alvos/ciências), senão a FK barra o delete da escala.
        avisoService.excluirPorEscala(id);
        escalaRepo.delete(escala); // CASCADE deleta os vínculos de operador/função
        log.info("Escala #{} excluída", id);
    }

    // ══ Minha escala (operador) ═════════════════════════════════

    /**
     * Retorna as salas em que o operador está escalado hoje.
     * Retorna lista de nomes de salas.
     */
    public List<Map<String, Object>> minhaEscalaHoje(String operadorId) {
        var hoje = LocalDate.now();
        var escalas = escalaRepo.findVigentesPorData(hoje);
        if (escalas.isEmpty()) return Collections.emptyList();

        // Mapa de salas carregado no máx. 1× (CAD_SALA ~12 linhas), lazy no 1º vínculo, no lugar de
        // findById por vínculo (Q18). Lazy p/ não somar 1 query quando o operador não está escalado.
        Map<Integer, Sala> salas = null;
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (var escala : escalas) {
            var vinculos = escalaOpRepo.findByEscalaIdAndOperadorId(escala.getId(), operadorId);
            for (var v : vinculos) {
                if (salas == null)
                    salas = salaRepo.findAll().stream().collect(Collectors.toMap(s -> s.getId(), s -> s));
                var sala = salas.get(v.getSalaId());
                if (sala != null) {
                    resultado.add(Map.of(
                            "sala_id", v.getSalaId(),
                            "sala_nome", sala.getNome(),
                            "escala_id", escala.getId(),
                            "data_inicio", escala.getDataInicio().toString(),
                            "data_fim", escala.getDataFim().toString()
                    ));
                }
            }
        }
        return resultado;
    }

    // ══ Gerar escala automática (rodízio por vagas) ══════════════

    /** Ordem do ciclo: cada plenário rotaciona para o seguinte da lista (último volta ao primeiro). */
    private static final String[] CICLO_NOMES = {
            "Plenário 19", "Plenário 15", "Plenário 13", "Plenário 09",
            "Plenário 07", "Plenário 03", "Plenário 02", "Plenário 06"
    };

    /**
     * Gera escala por rodízio cíclico das vagas.
     * Cada vaga (sala + slot M/V) na escala anterior rotaciona para a próxima sala do ciclo,
     * mantendo seu ocupante. A reconstrução usa o turno ATUAL do operador (PES_OPERADOR), não
     * o turno gravado na escala — então inversões manuais de turno por plenário NÃO afetam o
     * rodízio, e operadores duplicados na escala anterior entram uma única vez. Quem saiu da
     * escala (ou cuja vaga colidiu) libera vaga, preenchida por entrantes via sorteio.
     * Slots sem ninguém continuam vazios após a rotação.
     */
    @Transactional
    public Map<String, Object> gerarEscalaRodizio(LocalDate dataInicio, LocalDate dataFim, String criadoPor) {
        Map<Integer, List<String>> salasOperadores = gerarMapaRodizio(dataInicio, dataFim);
        return salvarEscala(null, dataInicio, dataFim, salasOperadores, null, null, criadoPor);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> gerarPreviaEscalaRodizio(LocalDate dataInicio, LocalDate dataFim) {
        return Map.of("salas", gerarMapaRodizio(dataInicio, dataFim));
    }

    private Map<Integer, List<String>> gerarMapaRodizio(LocalDate dataInicio, LocalDate dataFim) {
        validarPeriodo(dataInicio, dataFim);

        // 1. Validar contagem por turno
        var participantes = operadorRepo.findParticipantesEscala();
        List<String> partM = new ArrayList<>();
        List<String> partV = new ArrayList<>();
        Map<String, String> turnoAtual = new HashMap<>();
        for (var op : participantes) {
            turnoAtual.put(op.getId(), op.getTurno());
            if ("M".equals(op.getTurno())) partM.add(op.getId());
            else if ("V".equals(op.getTurno())) partV.add(op.getId());
        }
        if (partM.size() > 8) {
            throw new ServiceValidationException("Há " + partM.size() +
                    " operadores no turno Matutino, máximo 8. Ajuste antes de gerar a escala.");
        }
        if (partV.size() > 8) {
            throw new ServiceValidationException("Há " + partV.size() +
                    " operadores no turno Vespertino, máximo 8. Ajuste antes de gerar a escala.");
        }

        // 2. Resolver IDs das salas do ciclo
        List<Integer> ciclo = resolverCicloSalas();

        // 3. Buscar a escala imediatamente anterior ao novo período
        EscalaSemanal escalaAnterior = escalaRepo
                .findFirstByDataFimBeforeOrderByDataFimDescDataInicioDescIdDesc(dataInicio)
                .orElse(null);

        // Estrutura: Map<salaId, Map<"M"/"V", operadorId|null>>
        Map<Integer, Map<String, String>> base = inicializarSlots(ciclo);

        if (escalaAnterior == null) {
            // INICIALIZAÇÃO ALEATÓRIA — sem histórico, distribui aleatoriamente
            // respeitando 1M+1V por sala (slots ficam vazios se faltar gente em algum turno)
            distribuicaoInicialAleatoria(ciclo, partM, partV, base);
        } else {
            // MODO ROTAÇÃO — pega vagas da anterior, ajusta, depois rotaciona
            Map<Integer, Map<String, String>> ant = reconstruirEscalaAnterior(escalaAnterior, ciclo, turnoAtual);

            // Copiar as vagas reconstruídas para a base. 'ant' já foi montada com o turno ATUAL
            // de cada operador (não o snapshot da escala), então toda vaga já reflete o turno
            // vigente — inversões manuais não chegam aqui, e quem mudou de turno já entrou no
            // slot novo. Quem ficou de fora (colisão/saída) é realocado adiante via dispM/dispV.
            for (int sId : ciclo) {
                for (String slot : List.of("M", "V")) {
                    String opId = ant.get(sId).get(slot);
                    if (opId != null) base.get(sId).put(slot, opId);
                }
            }

            // Operadores ainda não alocados (entrantes + os que mudaram de turno)
            Set<String> jaAlocados = new HashSet<>();
            for (var slots : base.values())
                for (String op : slots.values()) if (op != null) jaAlocados.add(op);

            Random rnd = new Random();
            List<String> dispM = new ArrayList<>(partM); dispM.removeAll(jaAlocados); Collections.shuffle(dispM, rnd);
            List<String> dispV = new ArrayList<>(partV); dispV.removeAll(jaAlocados); Collections.shuffle(dispV, rnd);

            // Preencher vagas órfãs (na escala anterior virtual) com os disponíveis
            for (int sId : ciclo) {
                if (base.get(sId).get("M") == null && !dispM.isEmpty()) {
                    base.get(sId).put("M", dispM.remove(0));
                }
                if (base.get(sId).get("V") == null && !dispV.isEmpty()) {
                    base.get(sId).put("V", dispV.remove(0));
                }
            }

            // Aplicar rotação: vaga (sala_i, slot) vai para (sala_i+1, slot)
            base = rotacionar(ciclo, base);
        }

        // 4. Converter para o formato Map<salaId, List<operadorId>> esperado por salvarEscala
        // (turno é gravado pelo salvarEscala consultando o operador)
        return converterParaSalasOperadores(base);
    }

    private List<Integer> resolverCicloSalas() {
        Map<String, Integer> nomeParaId = new HashMap<>();
        for (var s : salaRepo.findAtivasOrdenadas()) {
            if (s.getNome() != null) nomeParaId.put(s.getNome(), s.getId());
        }
        List<Integer> ciclo = new ArrayList<>();
        for (String nome : CICLO_NOMES) {
            Integer id = nomeParaId.get(nome);
            if (id == null) throw new ServiceValidationException("Sala '" + nome + "' não encontrada.");
            ciclo.add(id);
        }
        return ciclo;
    }

    private void distribuicaoInicialAleatoria(List<Integer> ciclo, List<String> partM, List<String> partV,
                                              Map<Integer, Map<String, String>> base) {
        Random rnd = new Random();
        List<String> shM = new ArrayList<>(partM); Collections.shuffle(shM, rnd);
        List<String> shV = new ArrayList<>(partV); Collections.shuffle(shV, rnd);
        Iterator<String> itM = shM.iterator();
        Iterator<String> itV = shV.iterator();
        for (int sId : ciclo) {
            if (itM.hasNext()) base.get(sId).put("M", itM.next());
            if (itV.hasNext()) base.get(sId).put("V", itV.next());
        }
    }

    private Map<Integer, Map<String, String>> reconstruirEscalaAnterior(EscalaSemanal anterior, List<Integer> ciclo,
                                                                        Map<String, String> turnoAtual) {
        Map<Integer, Map<String, String>> ant = inicializarSlots(ciclo);
        Set<String> jaNaAnterior = new HashSet<>();
        for (var v : escalaOpRepo.findByEscalaId(anterior.getId())) {
            var slots = ant.get(v.getSalaId());
            if (slots == null) continue;
            String opId = v.getOperadorId();
            // Usa o turno ORIGINAL do operador (PES_OPERADOR), não o snapshot da escala:
            // inversões manuais de turno na escala anterior NÃO afetam o rodízio.
            String turnoOriginal = turnoAtual.get(opId);
            if (turnoOriginal == null) continue;        // não participa mais → não carrega
            if (jaNaAnterior.contains(opId)) continue;   // de-dup: ignora cópia extra do mesmo operador
            if (slots.get(turnoOriginal) != null) {      // vaga do turno já ocupada (ex.: colega mudou de turno)
                log.info("Rodízio: operador {} não mantido na sala {} (slot {} já ocupado por {}); será realocado.",
                        opId, v.getSalaId(), turnoOriginal, slots.get(turnoOriginal));
                continue;
            }
            slots.put(turnoOriginal, opId);
            jaNaAnterior.add(opId);
        }
        return ant;
    }

    private Map<Integer, Map<String, String>> rotacionar(List<Integer> ciclo, Map<Integer, Map<String, String>> base) {
        Map<Integer, Map<String, String>> rotacionado = inicializarSlots(ciclo);
        for (int i = 0; i < ciclo.size(); i++) {
            int origem = ciclo.get(i);
            int destino = ciclo.get((i + 1) % ciclo.size());
            rotacionado.get(destino).put("M", base.get(origem).get("M"));
            rotacionado.get(destino).put("V", base.get(origem).get("V"));
        }
        return rotacionado;
    }

    private Map<Integer, List<String>> converterParaSalasOperadores(Map<Integer, Map<String, String>> base) {
        Map<Integer, List<String>> salasOperadores = new LinkedHashMap<>();
        for (var entry : base.entrySet()) {
            List<String> ops = new ArrayList<>();
            String m = entry.getValue().get("M");
            String v = entry.getValue().get("V");
            if (m != null) ops.add(m);
            if (v != null) ops.add(v);
            salasOperadores.put(entry.getKey(), ops);
        }
        return salasOperadores;
    }

    private Map<Integer, Map<String, String>> inicializarSlots(List<Integer> ciclo) {
        Map<Integer, Map<String, String>> r = new LinkedHashMap<>();
        for (int sId : ciclo) {
            Map<String, String> slots = new LinkedHashMap<>();
            slots.put("M", null);
            slots.put("V", null);
            r.put(sId, slots);
        }
        return r;
    }

    // ══ Operadores escalados hoje (por sala) ══════════════════

    /**
     * Retorna mapa sala_id → lista de nome_exibicao dos operadores escalados hoje.
     */
    public Map<Integer, List<String>> operadoresEscaladosHoje() {
        var hoje = LocalDate.now();
        var escalas = escalaRepo.findVigentesPorData(hoje);
        Map<Integer, List<String>> resultado = new LinkedHashMap<>();
        // Mapa de operadores carregado no máx. 1× (PES_OPERADOR ~22 linhas), lazy no 1º vínculo,
        // no lugar de findById por vínculo (Q18). Lazy p/ não somar 1 query quando não há vínculos.
        Map<String, Operador> operadores = null;
        for (var escala : escalas) {
            var vinculos = escalaOpRepo.findByEscalaId(escala.getId());
            for (var v : vinculos) {
                if (operadores == null)
                    operadores = operadorRepo.findAll().stream().collect(Collectors.toMap(o -> o.getId(), o -> o));
                var op = operadores.get(v.getOperadorId());
                if (op != null)
                    resultado.computeIfAbsent(v.getSalaId(), k -> new ArrayList<>())
                            .add(op.getNomeExibicao());
            }
        }
        return resultado;
    }

    // ══ Helpers ═════════════════════════════════════════════════

    private void validarPeriodo(LocalDate dataInicio, LocalDate dataFim) {
        if (dataInicio == null || dataFim == null) {
            throw new ServiceValidationException("Data início e data fim são obrigatórias.");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new ServiceValidationException("Data fim não pode ser anterior à data início.");
        }
    }

    private Map<String, Object> toMap(EscalaSemanal e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("data_inicio", e.getDataInicio().toString());
        map.put("data_fim", e.getDataFim().toString());
        // Buscar nome completo do admin pelo username
        String nomeCriador = e.getCriadoPor();
        if (nomeCriador != null) {
            nomeCriador = adminRepo.findByUsername(nomeCriador)
                    .map(a -> a.getNomeCompleto())
                    .orElse(e.getCriadoPor());
        }
        map.put("criado_por", nomeCriador);
        map.put("criado_em", e.getCriadoEm() != null ? e.getCriadoEm().toString() : null);
        return map;
    }
}

package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static br.leg.senado.nusp.service.NativeQueryUtils.asItem;
import static br.leg.senado.nusp.service.NativeQueryUtils.asList;
import static br.leg.senado.nusp.service.NativeQueryUtils.asMap;

/**
 * Marcações de ocorrência do ponto (backend do modal "Ocorrências"): dias
 * globais, que valem para todos, e marcações por pessoa-dia. O tipo de cada
 * marcação vem do catálogo ({@link TipoMarcacaoService}) e precisa ser do escopo
 * certo — um tipo GLOBAL não marca um funcionário, um INDIVIDUAL não marca o dia
 * de todos. Qualquer admin marca; cadastrar tipos é só do master.
 *
 * <p>Trocar tipo = update; remover = delete físico (é configuração, não fato
 * auditável além do CRIADO_POR_ID). Fim de semana é aceito para qualquer tipo —
 * o cinza de fds na grade é apenas visual.
 */
@Service
@RequiredArgsConstructor
public class MarcacaoService {

    private static final Set<String> PESSOA_TIPOS = Set.of("OPERADOR", "TECNICO", "ADMINISTRADOR");

    private final PontoDiaMarcacaoRepository diaRepo;
    private final PontoPessoaMarcacaoRepository pessoaRepo;
    private final PontoTipoMarcacaoRepository tipoRepo;
    private final PessoaCadastroLookup pessoaCadastro;

    /**
     * Marcações globais + pessoais do mês, cada uma já com o nome e a badge do
     * tipo — a tela não guarda mapa de rótulo nenhum. Range sargável:
     * DATA >= 1º dia AND < 1º do mês seguinte.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listar(int ano, int mes) {
        LocalDate ini = inicioMes(ano, mes);
        LocalDate fim = ini.plusMonths(1);
        Map<String, PontoTipoMarcacao> catalogo = catalogoPorId();

        List<Map<String, Object>> globais = new ArrayList<>();
        for (PontoDiaMarcacao m : diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim)) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("data", m.getData().toString());          // YYYY-MM-DD
            descreverTipo(g, catalogo.get(m.getTipoId()), m.getTipoId());
            globais.add(g);
        }

        List<Map<String, Object>> pessoais = new ArrayList<>();
        for (PontoPessoaMarcacao m : pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pessoa_id", m.getPessoaId());
            p.put("pessoa_tipo", m.getPessoaTipo());
            p.put("data", m.getData().toString());
            descreverTipo(p, catalogo.get(m.getTipoId()), m.getTipoId());
            pessoais.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("globais", globais);
        out.put("pessoais", pessoais);
        return out;
    }

    /**
     * Aplica o lote do modal de ocorrências: upsert/remoção de marcações globais
     * e/ou de UMA pessoa (o modal envia globais quando "Todos os Funcionários",
     * pessoais quando um funcionário). Transacional (tudo ou nada).
     */
    @Transactional
    public void aplicarLote(Map<String, Object> body, String adminId) {
        if (body == null) return;

        Map<String, Object> globais = asMap(body.get("globais"), "globais");
        if (globais != null) {
            for (Object it : asList(globais.get("aplicar"), "globais.aplicar")) {
                Map<String, Object> item = asItem(it, "globais.aplicar");
                LocalDate data = parseData(item.get("data"));
                PontoTipoMarcacao tipo = tipoDoEscopo(item.get("tipo_id"),
                        PontoTipoMarcacao.ESCOPO_GLOBAL, "global");
                PontoDiaMarcacao m = diaRepo.findByData(data).orElseGet(PontoDiaMarcacao::new);
                m.setData(data);
                m.setTipoId(tipo.getId());
                m.setCriadoPorId(adminId);
                gravar(() -> diaRepo.saveAndFlush(m), tipo);
            }
            for (Object d : asList(globais.get("remover"), "globais.remover")) {
                diaRepo.deleteByData(parseData(d));
            }
        }

        Map<String, Object> pessoais = asMap(body.get("pessoais"), "pessoais");
        if (pessoais != null) {
            String pessoaId = str(pessoais.get("pessoa_id"));
            String pessoaTipo = PessoaCadastroLookup.normalizarTipo(str(pessoais.get("pessoa_tipo")));
            if (pessoaId.isBlank() || !PESSOA_TIPOS.contains(pessoaTipo)) {
                throw new ServiceValidationException("Funcionário (pessoa_id / pessoa_tipo) inválido.");
            }
            // F34: PNT_PESSOA_MARCACAO é polimórfica e SEM FK — sem esta guarda, um id inexistente (ou o
            // par trocado: id de OPERADOR com tipo TECNICO) grava uma linha órfã, que some da grade/XLSX
            // e dos dias bloqueados do banco (todos cruzam pelo par REAL) e que o modal não remove: o
            // admin marca "Férias" e nada acontece, sem erro. Vale para os dois ramos abaixo — aplicar e
            // remover —, por isso a checagem fica no topo.
            if (!pessoaCadastro.existe(pessoaId, pessoaTipo)) {
                throw new ServiceValidationException("Funcionário não encontrado (pessoa_id / pessoa_tipo).");
            }
            for (Object it : asList(pessoais.get("aplicar"), "pessoais.aplicar")) {
                Map<String, Object> item = asItem(it, "pessoais.aplicar");
                LocalDate data = parseData(item.get("data"));
                exigirDiaSemMarcacaoGlobal(data);
                PontoTipoMarcacao tipo = tipoDoEscopo(item.get("tipo_id"),
                        PontoTipoMarcacao.ESCOPO_INDIVIDUAL, "pessoal");
                PontoPessoaMarcacao m = pessoaRepo
                        .findByPessoaIdAndPessoaTipoAndData(pessoaId, pessoaTipo, data)
                        .orElseGet(PontoPessoaMarcacao::new);
                m.setPessoaId(pessoaId);
                m.setPessoaTipo(pessoaTipo);
                m.setData(data);
                m.setTipoId(tipo.getId());
                m.setCriadoPorId(adminId);
                gravar(() -> pessoaRepo.saveAndFlush(m), tipo);
            }
            for (Object d : asList(pessoais.get("remover"), "pessoais.remover")) {
                pessoaRepo.deleteByPessoaIdAndPessoaTipoAndData(pessoaId, pessoaTipo, parseData(d));
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    /** Valida o par {ano, mes} do seletor e devolve o 1º dia do mês — compartilhado com o BancoHorasService (E7). */
    static LocalDate inicioMes(int ano, int mes) {
        if (mes < 1 || mes > 12) throw new ServiceValidationException("Mês inválido: " + mes + ".");
        if (ano < 2000 || ano > 2100) throw new ServiceValidationException("Ano inválido: " + ano + ".");
        return LocalDate.of(ano, mes, 1);
    }

    private LocalDate parseData(Object v) {
        String s = str(v);
        if (s.isBlank()) throw new ServiceValidationException("Data obrigatória.");
        try {
            return LocalDate.parse(s);   // ISO YYYY-MM-DD (gotcha 4)
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida (use AAAA-MM-DD): " + s);
        }
    }

    /**
     * A marcação geral do dia vale para todos e prevalece sobre a individual: com ela no dia, marcar
     * (ou trocar o tipo de) um funcionário gravaria uma ocorrência que a grade não mostraria de
     * volta. A remoção segue livre — é o que limpa o que ficou escondido sob a geral.
     *
     * <p>A tela não oferece o clique nesses dias; a recusa aqui é para quando as duas pontas
     * discordam — a geral entrou depois que o admin carregou a grade —, e por isso a mensagem manda
     * recarregar.
     */
    private void exigirDiaSemMarcacaoGlobal(LocalDate data) {
        if (diaRepo.findByData(data).isPresent()) {
            throw new ServiceValidationException("O dia " + ReportConfig.fmtDate(data)
                    + " possui ocorrência geral. Recarregue a página e tente novamente.");
        }
    }

    /**
     * Grava a marcação traduzindo a corrida com a exclusão do tipo: entre a leitura do catálogo e o
     * INSERT, o master pode ter excluído aquele tipo — a FK recusa, e o admin que só queria marcar um
     * dia merece saber o que houve, não um erro interno.
     */
    private void gravar(Runnable escrita, PontoTipoMarcacao tipo) {
        try {
            escrita.run();
        } catch (DataIntegrityViolationException e) {
            throw new ServiceValidationException("O tipo \"" + tipo.getNome()
                    + "\" não está mais disponível.");
        }
    }

    /** O catálogo inteiro indexado por id — são poucas linhas, e a listagem do mês precisa de todas. */
    private Map<String, PontoTipoMarcacao> catalogoPorId() {
        Map<String, PontoTipoMarcacao> porId = new HashMap<>();
        for (PontoTipoMarcacao t : tipoRepo.findAll()) porId.put(t.getId(), t);
        return porId;
    }

    /** Nome e badge da marcação; sem o tipo no catálogo, o id cru — a tela nunca fica muda. */
    private static void descreverTipo(Map<String, Object> destino, PontoTipoMarcacao tipo, String tipoId) {
        destino.put("tipo_id", tipoId);
        destino.put("nome", tipo != null ? tipo.getNome() : tipoId);
        destino.put("badge", tipo != null ? tipo.getBadge() : "");
    }

    /**
     * O tipo pedido, exigindo o escopo do lado que está sendo marcado: marcar um
     * funcionário com um tipo de todos (ou o contrário) grava uma marcação que a
     * tela nunca mostraria de volta.
     */
    private PontoTipoMarcacao tipoDoEscopo(Object v, String escopo, String rotuloDoLado) {
        String id = str(v);
        if (id.isBlank()) {
            throw new ServiceValidationException("Tipo de marcação " + rotuloDoLado + " obrigatório.");
        }
        PontoTipoMarcacao tipo = tipoRepo.findById(id).orElseThrow(
                () -> new ServiceValidationException("Tipo de ocorrência não encontrado: " + id));
        // O tipo que o funcionário declara na própria folha é dele: usá-lo como marcação faria a
        // planilha contar como folga um dia que ninguém declarou nem aprovou.
        if (!escopo.equals(tipo.getEscopo()) || Boolean.TRUE.equals(tipo.getVisivelFuncionario())) {
            throw new ServiceValidationException("O tipo \"" + tipo.getNome()
                    + "\" não pode ser usado como marcação " + rotuloDoLado + ".");
        }
        return tipo;
    }

    private static String str(Object v) { return v == null ? "" : v.toString().strip(); }
}

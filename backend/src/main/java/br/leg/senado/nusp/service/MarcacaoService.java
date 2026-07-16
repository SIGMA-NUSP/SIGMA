package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static br.leg.senado.nusp.service.NativeQueryUtils.asItem;
import static br.leg.senado.nusp.service.NativeQueryUtils.asList;
import static br.leg.senado.nusp.service.NativeQueryUtils.asMap;

/**
 * Configuração de marcações do ponto (E6, backend do "Configurar"): dias globais
 * (FERIADO/PONTO_FACULTATIVO — Q6) e marcações por pessoa-dia (à disposição/
 * atestado/férias/recesso/lic. médica — Q7/F#4). Qualquer admin configura (Q36).
 * Trocar tipo = update; remover = delete físico (é configuração, não fato
 * auditável além do CRIADO_POR_ID). Fim de semana é aceito para qualquer tipo —
 * o cinza de fds na grade é apenas visual.
 */
@Service
@RequiredArgsConstructor
public class MarcacaoService {

    private static final Set<String> PESSOA_TIPOS = Set.of("OPERADOR", "TECNICO", "ADMINISTRADOR");

    private final PontoDiaMarcacaoRepository diaRepo;
    private final PontoPessoaMarcacaoRepository pessoaRepo;
    private final PessoaCadastroLookup pessoaCadastro;

    /** Marcações globais + pessoais do mês. Range sargável: DATA >= 1º dia AND < 1º do mês seguinte (gotcha 4). */
    @Transactional(readOnly = true)
    public Map<String, Object> listar(int ano, int mes) {
        LocalDate ini = inicioMes(ano, mes);
        LocalDate fim = ini.plusMonths(1);

        List<Map<String, Object>> globais = new ArrayList<>();
        for (PontoDiaMarcacao m : diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim)) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("data", m.getData().toString());          // YYYY-MM-DD
            g.put("tipo", m.getTipo().getValor());
            globais.add(g);
        }

        List<Map<String, Object>> pessoais = new ArrayList<>();
        for (PontoPessoaMarcacao m : pessoaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pessoa_id", m.getPessoaId());
            p.put("pessoa_tipo", m.getPessoaTipo());
            p.put("data", m.getData().toString());
            p.put("tipo", m.getTipo().getValor());
            pessoais.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("globais", globais);
        out.put("pessoais", pessoais);
        return out;
    }

    /**
     * Aplica o lote do modal Configurar: upsert/remoção de marcações globais e/ou
     * de UMA pessoa (o modal envia globais quando "Todos", pessoais quando um
     * funcionário). Transacional (tudo ou nada).
     */
    @Transactional
    public void aplicarLote(Map<String, Object> body, String adminId) {
        if (body == null) return;

        Map<String, Object> globais = asMap(body.get("globais"), "globais");
        if (globais != null) {
            for (Object it : asList(globais.get("aplicar"), "globais.aplicar")) {
                Map<String, Object> item = asItem(it, "globais.aplicar");
                LocalDate data = parseData(item.get("data"));
                TipoDiaMarcacao tipo = tipoGlobal(item.get("tipo"));
                PontoDiaMarcacao m = diaRepo.findByData(data).orElseGet(PontoDiaMarcacao::new);
                m.setData(data);
                m.setTipo(tipo);
                m.setCriadoPorId(adminId);
                diaRepo.save(m);
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
                TipoPessoaMarcacao tipo = tipoPessoa(item.get("tipo"));
                PontoPessoaMarcacao m = pessoaRepo
                        .findByPessoaIdAndPessoaTipoAndData(pessoaId, pessoaTipo, data)
                        .orElseGet(PontoPessoaMarcacao::new);
                m.setPessoaId(pessoaId);
                m.setPessoaTipo(pessoaTipo);
                m.setData(data);
                m.setTipo(tipo);
                m.setCriadoPorId(adminId);
                pessoaRepo.save(m);
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

    private TipoDiaMarcacao tipoGlobal(Object v) {
        try {
            TipoDiaMarcacao t = TipoDiaMarcacao.fromValor(str(v));
            if (t == null) throw new ServiceValidationException("Tipo de marcação global obrigatório.");
            return t;
        } catch (IllegalArgumentException e) {
            throw new ServiceValidationException(e.getMessage());
        }
    }

    private TipoPessoaMarcacao tipoPessoa(Object v) {
        try {
            TipoPessoaMarcacao t = TipoPessoaMarcacao.fromValor(str(v));
            if (t == null) throw new ServiceValidationException("Tipo de marcação pessoal obrigatório.");
            return t;
        } catch (IllegalArgumentException e) {
            throw new ServiceValidationException(e.getMessage());
        }
    }

    private static String str(Object v) { return v == null ? "" : v.toString().strip(); }
}

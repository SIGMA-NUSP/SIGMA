package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Grade mensal de retificações (E10, card admin "Retificações"): matriz
 * funcionários da categoria × dias do mês, com o conteúdo de cada célula já
 * resolvido pela precedência do §1 do plano — horários da retificação →
 * "Banco de horas" (folga APROVADA — Q13) → marcação pessoa-dia → marcação
 * global → vazia. Administradores lista só SERVIDOR_PUBLICO=0 (com folha —
 * Q26/Q38). Tudo em 4 consultas agregadas por range de mês (retificações,
 * folgas, marcações globais, marcações pessoais) + a lista de funcionários;
 * a precedência e a contagem "Folgas" são resolvidas em memória. Qualquer
 * admin acessa (a rota /api/admin/** já cobre o papel). Somente leitura.
 *
 * {@link #montarGrade} devolve a estrutura tipada (fonte única) consumida por
 * {@link #montar} (payload JSON da grade, E10) e pela exportação XLSX (E11) —
 * a precedência do §1 vive num só lugar.
 */
@Service
@RequiredArgsConstructor
public class GradeRetificacaoService {

    /** Combobox da barra (B-3.1) → PESSOA_TIPO polimórfico das tabelas PNT_*. */
    private static final Map<String, String> CATEGORIAS = Map.of(
            "operadores", "OPERADOR",
            "tecnicos", "TECNICO",
            "administradores", "ADMINISTRADOR");

    private final PontoRetificacaoRepository retificacaoRepo;
    private final PontoSolicitacaoFolgaRepository folgaRepo;
    private final PontoDiaMarcacaoRepository diaRepo;
    private final PontoPessoaMarcacaoRepository pessoaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;

    // ── estrutura tipada (fonte única grade + XLSX) ──

    /** Conteúdo de uma célula, já resolvido pela precedência do §1. */
    public record Celula(String tipo, String texto, boolean temObs, String obs) {}
    /** Um dia do mês (rótulo/fim de semana + marcação global do dia). */
    public record Dia(int dia, LocalDate data, int dow, boolean fimDeSemana, String marcacaoGlobal) {}
    /** Um funcionário da categoria + a contagem de folgas APROVADAS do mês. */
    public record Funcionario(String id, String nome, int folgas) {}
    /** Grade completa do mês para uma categoria. `celulas` = pessoaId → dia → célula (só as preenchidas). */
    public record Grade(String categoria, int ano, int mes, int diasNoMes,
                        List<Funcionario> funcionarios, List<Dia> dias,
                        Map<String, Map<Integer, Celula>> celulas) {}

    /** Payload JSON da grade (E10). */
    @Transactional(readOnly = true)
    public Map<String, Object> montar(String categoria, int ano, int mes) {
        return serializar(montarGrade(categoria, ano, mes));
    }

    /** Estrutura tipada da grade — fonte única do payload (E10) e do XLSX (E11). */
    @Transactional(readOnly = true)
    public Grade montarGrade(String categoria, int ano, int mes) {
        String cat = categoria == null ? "" : categoria.strip().toLowerCase(Locale.ROOT);
        String pessoaTipo = CATEGORIAS.get(cat);
        if (pessoaTipo == null) {
            throw new ServiceValidationException(
                    "Categoria inválida: " + categoria + ". Use operadores, tecnicos ou administradores.");
        }

        LocalDate ini = MarcacaoService.inicioMes(ano, mes);   // valida {ano, mes} — DRY com o E7
        LocalDate fim = ini.plusMonths(1);
        int diasNoMes = ini.lengthOfMonth();

        List<Func> funcs = funcionariosDaCategoria(cat);

        // ── 4 consultas agregadas por range de mês (DATE sargável >= / <, sem TRUNC) ──
        List<PontoRetificacao> retifs =
                retificacaoRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan(pessoaTipo, ini, fim);
        List<PontoSolicitacaoFolga> folgas =
                folgaRepo.findPorStatusECategoriaNoRange(StatusSolicitacaoFolga.APROVADO, pessoaTipo, ini, fim);
        List<PontoDiaMarcacao> globais =
                diaRepo.findByDataGreaterThanEqualAndDataLessThanOrderByData(ini, fim);
        List<PontoPessoaMarcacao> pessoais =
                pessoaRepo.findByPessoaTipoAndDataGreaterThanEqualAndDataLessThan(pessoaTipo, ini, fim);

        // ── índices em memória (chave "pessoaId|dia" para o cruzamento por célula) ──
        Map<String, PontoRetificacao> retifIdx = new HashMap<>();
        for (PontoRetificacao r : retifs) retifIdx.put(chave(r.getPessoaId(), r.getData().getDayOfMonth()), r);

        Set<String> folgaIdx = new HashSet<>();
        Map<String, Integer> folgasPorPessoa = new HashMap<>();
        for (PontoSolicitacaoFolga s : folgas) {
            folgaIdx.add(chave(s.getPessoaId(), s.getDataFolga().getDayOfMonth()));
            folgasPorPessoa.merge(s.getPessoaId(), 1, Integer::sum);
        }

        Map<String, TipoPessoaMarcacao> pessoaIdx = new HashMap<>();
        for (PontoPessoaMarcacao m : pessoais) pessoaIdx.put(chave(m.getPessoaId(), m.getData().getDayOfMonth()), m.getTipo());

        Map<Integer, TipoDiaMarcacao> globalIdx = new HashMap<>();
        for (PontoDiaMarcacao g : globais) globalIdx.put(g.getData().getDayOfMonth(), g.getTipo());

        // ── dias do mês (rótulo/fim de semana + marcação global do dia) ──
        List<Dia> dias = new ArrayList<>();
        for (int d = 1; d <= diasNoMes; d++) {
            LocalDate data = ini.withDayOfMonth(d);
            int dow = data.getDayOfWeek().getValue();   // 1=segunda … 7=domingo (ISO)
            TipoDiaMarcacao g = globalIdx.get(d);
            dias.add(new Dia(d, data, dow, dow >= 6, g == null ? null : g.getRotulo()));
        }

        // ── funcionários (com contagem de folgas) + células resolvidas por precedência ──
        List<Funcionario> funcionarios = new ArrayList<>();
        Map<String, Map<Integer, Celula>> celulas = new LinkedHashMap<>();
        for (Func f : funcs) {
            funcionarios.add(new Funcionario(f.id(), f.nome(), folgasPorPessoa.getOrDefault(f.id(), 0)));  // Q13

            Map<Integer, Celula> linha = new LinkedHashMap<>();
            for (int d = 1; d <= diasNoMes; d++) {
                Celula cel = resolverCelula(f.id(), d, retifIdx, folgaIdx, pessoaIdx, globalIdx);
                if (cel != null) linha.put(d, cel);   // vazias são omitidas
            }
            if (!linha.isEmpty()) celulas.put(f.id(), linha);
        }

        return new Grade(cat, ano, mes, diasNoMes, funcionarios, dias, celulas);
    }

    /** Precedência do §1: retificação → banco → marcação pessoa → marcação global → vazia (null). */
    private Celula resolverCelula(String pessoaId, int dia,
                                  Map<String, PontoRetificacao> retifIdx,
                                  Set<String> folgaIdx,
                                  Map<String, TipoPessoaMarcacao> pessoaIdx,
                                  Map<Integer, TipoDiaMarcacao> globalIdx) {
        String k = chave(pessoaId, dia);

        PontoRetificacao r = retifIdx.get(k);
        if (r != null) {
            String texto = Stream.of(r.getEnt1(), r.getSai1(), r.getEnt2(), r.getSai2())
                    .filter(h -> h != null && !h.isBlank())
                    .map(String::strip)
                    .collect(Collectors.joining(" "));   // horários separados por espaço simples (7.2.6)
            boolean temObs = r.getObservacoes() != null && !r.getObservacoes().isBlank();
            return new Celula("horarios", texto, temObs, temObs ? r.getObservacoes().strip() : null);
        }
        if (folgaIdx.contains(k)) return new Celula("banco", "Banco de horas", false, null);
        TipoPessoaMarcacao mp = pessoaIdx.get(k);
        if (mp != null) return new Celula("marcacao_pessoa", mp.getRotulo(), false, null);
        TipoDiaMarcacao mg = globalIdx.get(dia);
        if (mg != null) return new Celula("marcacao_global", mg.getRotulo(), false, null);
        return null;
    }

    /** Estrutura tipada → payload JSON (contrato do E10; ordem de chaves estável — gotcha 2). */
    private Map<String, Object> serializar(Grade g) {
        List<Map<String, Object>> funcionarios = new ArrayList<>();
        for (Funcionario f : g.funcionarios()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id());
            fm.put("nome", f.nome());
            fm.put("folgas", f.folgas());
            funcionarios.add(fm);
        }

        List<Map<String, Object>> dias = new ArrayList<>();
        for (Dia d : g.dias()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("dia", d.dia());
            dm.put("data", d.data().toString());        // YYYY-MM-DD
            dm.put("dow", d.dow());
            dm.put("fim_semana", d.fimDeSemana());
            dm.put("marcacao_global", d.marcacaoGlobal());
            dias.add(dm);
        }

        Map<String, Object> celulas = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, Celula>> e : g.celulas().entrySet()) {
            Map<String, Object> linha = new LinkedHashMap<>();
            for (Map.Entry<Integer, Celula> ce : e.getValue().entrySet()) {
                Celula c = ce.getValue();
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("tipo", c.tipo());
                cm.put("texto", c.texto());
                cm.put("tem_obs", c.temObs());
                if (c.temObs()) cm.put("obs", c.obs());
                linha.put(String.valueOf(ce.getKey()), cm);
            }
            celulas.put(e.getKey(), linha);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoria", g.categoria());
        out.put("ano", g.ano());
        out.put("mes", g.mes());
        out.put("funcionarios", funcionarios);
        out.put("dias", dias);
        out.put("celulas", celulas);
        return out;
    }

    private static String chave(String pessoaId, int dia) {
        return pessoaId + "|" + dia;
    }

    /**
     * Funcionários da categoria (id + NOME_COMPLETO), ordenados alfabeticamente
     * (case-insensitive, como o lookup de pessoas do app). Administradores =
     * só quem tem folha (SERVIDOR_PUBLICO=0 — Q26/Q38).
     */
    private List<Func> funcionariosDaCategoria(String cat) {
        List<Func> out = new ArrayList<>();
        switch (cat) {
            case "operadores" ->
                    operadorRepo.findAll().forEach(o -> out.add(new Func(o.getId(), o.getNomeCompleto())));
            case "tecnicos" ->
                    tecnicoRepo.findAll().forEach(t -> out.add(new Func(t.getId(), t.getNomeCompleto())));
            case "administradores" ->
                    administradorRepo.findAll().stream()
                            .filter(a -> !Boolean.TRUE.equals(a.getServidorPublico()))
                            .forEach(a -> out.add(new Func(a.getId(), a.getNomeCompleto())));
            default -> throw new ServiceValidationException("Categoria inválida: " + cat + ".");
        }
        // F30: a MESMA ordenação pt-BR das listagens (que vêm ordenadas pelo banco). Com um
        // toUpperCase() binário aqui, a grade (e o XLSX, que sai desta lista) mostraria "Katiane,
        // Kátia" enquanto a tela de pessoas mostra "Kátia, Katiane" — a mesma equipe em duas ordens.
        out.sort(Comparator.comparing(f -> f.nome() == null ? "" : f.nome(), NativeQueryUtils.ORDEM_TEXTO_PT_BR));
        return out;
    }

    private record Func(String id, String nome) {}
}

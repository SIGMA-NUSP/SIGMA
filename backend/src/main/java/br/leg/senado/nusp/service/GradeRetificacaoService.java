package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoRetificacao;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoRetificacaoRepository;
import br.leg.senado.nusp.repository.PontoSolicitacaoFolgaRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
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
 * Grade mensal de retificações (card admin "Retificações"): matriz
 * funcionários da categoria × dias do mês, com o conteúdo de cada célula já
 * resolvido pela precedência de exibição — retificação → "Banco de horas"
 * (folga APROVADA) → ocorrência geral do dia → ocorrência do funcionário →
 * vazia. Administradores lista só SERVIDOR_PUBLICO=0 (quem tem folha).
 * Consultas agregadas por range de mês (retificações, folgas, marcações
 * globais, marcações pessoais e as linhas das folhas publicadas) + a lista de
 * funcionários; a precedência e a contagem "Folgas" são resolvidas em memória.
 * Qualquer admin acessa (a rota /api/admin/** já cobre o papel). Somente leitura.
 *
 * <p>A retificação chega de duas formas. Em horários, o funcionário corrige as
 * células que quiser, e a grade mostra o dia EFETIVO — o que ele digitou sobre o
 * que a folha imprimiu, sem distinguir a origem. Em ocorrência declarada, o dia
 * inteiro vira o nome do tipo; o tipo que vale como folga do banco fica idêntico
 * à folga aprovada, no texto e na contagem.
 *
 * {@link #montarGrade} devolve a estrutura tipada (fonte única) consumida por
 * {@link #montar} (payload JSON da grade) e pela exportação XLSX — a
 * precedência vive num só lugar.
 */
@Service
@RequiredArgsConstructor
public class GradeRetificacaoService {

    /** Texto da célula de folga aprovada — o XLSX conta as folgas do mês por ele. */
    public static final String TEXTO_BANCO_DE_HORAS = "Banco de horas";

    /** Célula do dia que veio como folga do banco — é o que a contagem "Folgas" soma. */
    private static final String CELULA_BANCO = "banco";

    /**
     * O lugar do horário que ninguém preencheu. A célula lista as quatro batidas na ordem impressa,
     * e quem lê conta a posição para saber o que é entrada e o que é saída: sem a marca, um dia que
     * só tem a saída da tarde exibiria esse horário no lugar da entrada.
     */
    private static final String SEM_HORARIO = "--";

    /** Combobox da barra (B-3.1) → PESSOA_TIPO polimórfico das tabelas PNT_*. */
    private static final Map<String, String> CATEGORIAS = Map.of(
            "operadores", "OPERADOR",
            "tecnicos", "TECNICO",
            "administradores", "ADMINISTRADOR");

    private final PontoRetificacaoRepository retificacaoRepo;
    private final PontoSolicitacaoFolgaRepository folgaRepo;
    private final PontoDiaMarcacaoRepository diaRepo;
    private final PontoPessoaMarcacaoRepository pessoaRepo;
    private final PontoTipoMarcacaoRepository tipoRepo;
    private final PontoLotePaginaRepository paginaRepo;
    private final PontoFolhaLinhaRepository folhaLinhaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;

    // ── estrutura tipada (fonte única grade + XLSX) ──

    /**
     * Conteúdo de uma célula, já resolvido pela precedência. {@code tipoId} é o tipo
     * do catálogo por trás da ocorrência exibida — nulo quando a célula vem de uma
     * retificação ou de uma folga aprovada.
     */
    public record Celula(String tipo, String texto, boolean temObs, String obs, String tipoId) {}
    /** Um dia do mês (rótulo/fim de semana + a ocorrência geral do dia: nome e tipo do catálogo). */
    public record Dia(int dia, LocalDate data, int dow, boolean fimDeSemana,
                      String marcacaoGlobal, String marcacaoGlobalId) {}
    /** Um funcionário da categoria + os dias de folga do mês: as aprovadas e as que ele declarou. */
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

        // ── consultas agregadas por range de mês (DATE sargável >= / <, sem TRUNC) ──
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

        // O tipo do catálogo por trás de cada marcação e de cada declaração — nome exibido e se
        // aquele tipo vale como folga do banco.
        Map<String, PontoTipoMarcacao> catalogo = new HashMap<>();
        for (PontoTipoMarcacao t : tipoRepo.findAll()) catalogo.put(t.getId(), t);

        Set<String> folgaIdx = new HashSet<>();
        for (PontoSolicitacaoFolga s : folgas) {
            folgaIdx.add(chave(s.getPessoaId(), s.getDataFolga().getDayOfMonth()));
        }

        // O que a folha publicada imprimiu em cada dia — a base sobre a qual a retificação de
        // horários é lida.
        Map<String, HorariosDoDia> folhaIdx = horariosDasFolhas(pessoaTipo, ini, fim);

        // Marcação cujo tipo não está mais no catálogo não vira célula: sem nome, não há o que
        // exibir — a célula segue para o próximo nível da precedência, como se a marcação não existisse.
        Map<String, Marcacao> pessoaIdx = new HashMap<>();
        for (PontoPessoaMarcacao m : pessoais) {
            Marcacao ocorrencia = ocorrencia(m.getTipoId(), catalogo);
            if (ocorrencia != null) pessoaIdx.put(chave(m.getPessoaId(), m.getData().getDayOfMonth()), ocorrencia);
        }

        Map<Integer, Marcacao> globalIdx = new HashMap<>();
        for (PontoDiaMarcacao g : globais) {
            Marcacao ocorrencia = ocorrencia(g.getTipoId(), catalogo);
            if (ocorrencia != null) globalIdx.put(g.getData().getDayOfMonth(), ocorrencia);
        }

        // ── dias do mês (rótulo/fim de semana + marcação global do dia) ──
        List<Dia> dias = new ArrayList<>();
        for (int d = 1; d <= diasNoMes; d++) {
            LocalDate data = ini.withDayOfMonth(d);
            int dow = data.getDayOfWeek().getValue();   // 1=segunda … 7=domingo (ISO)
            Marcacao geral = globalIdx.get(d);
            dias.add(new Dia(d, data, dow, dow >= 6,
                    geral == null ? null : geral.nome(),
                    geral == null ? null : geral.tipoId()));
        }

        // ── funcionários (com contagem de folgas) + células resolvidas por precedência ──
        //
        // Folga aprovada e "Banco de horas" declarado são a mesma coisa na planilha: o dia sai da
        // escala, e o dia que tem os dois conta uma vez. A conta é a das CÉLULAS de banco, a mesma
        // que a planilha faz procurando o texto na coluna — assim a grade e o arquivo exportado
        // nunca dizem números diferentes. Dia em que a folga aprovada foi coberta por horários
        // corrigidos não conta: a pessoa trabalhou.
        List<Funcionario> funcionarios = new ArrayList<>();
        Map<String, Map<Integer, Celula>> celulas = new LinkedHashMap<>();
        for (Func f : funcs) {
            Map<Integer, Celula> linha = new LinkedHashMap<>();
            int folgasDoMes = 0;
            for (int d = 1; d <= diasNoMes; d++) {
                Celula cel = resolverCelula(f.id(), d, retifIdx, catalogo, folhaIdx,
                        folgaIdx, pessoaIdx, globalIdx);
                if (cel == null) continue;   // vazias são omitidas
                linha.put(d, cel);
                if (CELULA_BANCO.equals(cel.tipo())) folgasDoMes++;
            }
            funcionarios.add(new Funcionario(f.id(), f.nome(), folgasDoMes));
            if (!linha.isEmpty()) celulas.put(f.id(), linha);
        }

        return new Grade(cat, ano, mes, diasNoMes, funcionarios, dias, celulas);
    }

    /**
     * Precedência de exibição: retificação → banco → ocorrência geral do dia →
     * ocorrência do funcionário → vazia (null).
     *
     * <p>A ocorrência geral vale para todos, então esconde a individual do mesmo dia —
     * que continua gravada e reaparece se a geral for removida. Retificação e folga
     * aprovada vencem as duas: são fatos do ponto, não uma marcação de calendário.
     */
    private Celula resolverCelula(String pessoaId, int dia,
                                  Map<String, PontoRetificacao> retifIdx,
                                  Map<String, PontoTipoMarcacao> catalogo,
                                  Map<String, HorariosDoDia> folhaIdx,
                                  Set<String> folgaIdx,
                                  Map<String, Marcacao> pessoaIdx,
                                  Map<Integer, Marcacao> globalIdx) {
        String k = chave(pessoaId, dia);

        Celula retificada = celulaDaRetificacao(retifIdx.get(k), folhaIdx.get(k), catalogo);
        if (retificada != null) return retificada;
        if (folgaIdx.contains(k)) return new Celula("banco", TEXTO_BANCO_DE_HORAS, false, null, null);
        Marcacao mg = globalIdx.get(dia);
        if (mg != null) return new Celula("marcacao_global", mg.nome(), false, null, mg.tipoId());
        Marcacao mp = pessoaIdx.get(k);
        if (mp != null) return new Celula("marcacao_pessoa", mp.nome(), false, null, mp.tipoId());
        return null;
    }

    /**
     * A célula de um dia retificado. A ocorrência declarada pelo funcionário aparece pelo nome, e
     * a que vale como folga do banco fica igual à folga aprovada — mesmo texto, mesma contagem. Os
     * horários aparecem como o dia ficou: o que ele corrigiu por cima do que a folha imprimiu, em
     * texto liso, sem dizer de onde veio cada um.
     *
     * <p>Nenhuma delas leva o tipo do catálogo ao payload: retificação é do funcionário, e o
     * administrador não marca ocorrência por cima dela.
     *
     * <p>Devolve {@code null} quando não há retificação, ou quando o tipo declarado saiu do
     * catálogo — aí a célula segue a precedência como se ela não existisse.
     */
    private static Celula celulaDaRetificacao(PontoRetificacao r, HorariosDoDia daFolha,
                                              Map<String, PontoTipoMarcacao> catalogo) {
        if (r == null) return null;
        boolean temObs = r.getObservacoes() != null && !r.getObservacoes().isBlank();
        String obs = temObs ? r.getObservacoes().strip() : null;

        if (r.getTipoId() != null) {
            PontoTipoMarcacao tipo = catalogo.get(r.getTipoId());
            if (tipo == null) return null;
            return Boolean.TRUE.equals(tipo.getContaFolga())
                    ? new Celula(CELULA_BANCO, TEXTO_BANCO_DE_HORAS, temObs, obs, null)
                    : new Celula("ocorrencia", tipo.getNome(), temObs, obs, null);
        }

        String texto = horariosEmOrdem(HorariosDoDia.daRetificacao(r).sobre(daFolha));
        return texto.isEmpty() ? null : new Celula("horarios", texto, temObs, obs, null);
    }

    /**
     * As batidas do dia em texto, separadas por espaço e na ordem impressa: entrada e saída da
     * manhã, entrada e saída da tarde. A batida que ninguém tem fica marcada, para que a posição de
     * cada horário continue legível; as do fim, que ninguém precisaria contar, são dispensadas.
     */
    private static String horariosEmOrdem(HorariosDoDia horarios) {
        String[] v = horarios.valores();
        int ultimo = -1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] != null && !v[i].isBlank()) ultimo = i;
        }
        if (ultimo < 0) return "";

        StringBuilder texto = new StringBuilder();
        for (int i = 0; i <= ultimo; i++) {
            if (i > 0) texto.append(' ');
            texto.append(v[i] == null || v[i].isBlank() ? SEM_HORARIO : v[i].strip());
        }
        return texto.toString();
    }

    /**
     * O que as folhas publicadas imprimiram em cada dia do mês, por pessoa — chave "pessoaId|dia".
     *
     * <p>Quando duas folhas ainda válidas cobrem o mesmo dia (as semanais são cumulativas), vale a
     * publicada por último: é a versão que o funcionário tem à mão e a mesma que a retificação
     * conferiu quando ele digitou. Folha substituída, folha de lote oculto e linha sem data legível
     * ficam de fora.
     */
    private Map<String, HorariosDoDia> horariosDasFolhas(String pessoaTipo, LocalDate ini, LocalDate fim) {
        List<Object[]> publicadas = paginaRepo.findFolhasPublicadasDaCategoria(pessoaTipo, ini, fim);
        if (publicadas.isEmpty()) return Map.of();
        Set<String> substituidas = FolhaSubstituida.paginasSubstituidas(publicadas);

        List<String> paginas = new ArrayList<>();
        Map<String, String> pessoaDaPagina = new HashMap<>();
        Map<String, PontoLote> loteDaPagina = new HashMap<>();
        for (Object[] row : publicadas) {
            PontoLotePagina pg = (PontoLotePagina) row[0];
            if (substituidas.contains(pg.getId())) continue;
            paginas.add(pg.getId());
            pessoaDaPagina.put(pg.getId(), pg.getPessoaId());
            loteDaPagina.put(pg.getId(), (PontoLote) row[1]);
        }
        if (paginas.isEmpty()) return Map.of();

        Map<String, HorariosDoDia> horarios = new HashMap<>();
        Map<String, PontoLote> folhaDaCelula = new HashMap<>();
        for (Object[] linha : folhaLinhaRepo.findCelulasDasFolhas(paginas)) {
            LocalDate data = (LocalDate) linha[2];
            if (data == null || data.isBefore(ini) || !data.isBefore(fim)) continue;
            String paginaId = (String) linha[0];
            PontoLote lote = loteDaPagina.get(paginaId);
            String k = chave(pessoaDaPagina.get(paginaId), data.getDayOfMonth());
            PontoLote vigente = folhaDaCelula.get(k);
            if (vigente != null && FolhaSubstituida.CHEGOU_DEPOIS.compare(lote, vigente) <= 0) continue;
            folhaDaCelula.put(k, lote);
            horarios.put(k, HorariosDoDia.daFolha((String) linha[4], (String) linha[5],
                    (String) linha[6], (String) linha[7]));
        }
        return horarios;
    }

    /** Estrutura tipada → payload JSON da grade (ordem de chaves estável). */
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
            dm.put("marcacao_global_id", d.marcacaoGlobalId());
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
                // só as células de ocorrência têm tipo do catálogo — a tela abre a lista já no valor atual
                if (c.tipoId() != null) cm.put("tipo_id", c.tipoId());
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

    /** A ocorrência exibível de um tipo do catálogo — nula quando o tipo não está mais lá. */
    private static Marcacao ocorrencia(String tipoId, Map<String, PontoTipoMarcacao> catalogo) {
        PontoTipoMarcacao tipo = catalogo.get(tipoId);
        return tipo == null ? null : new Marcacao(tipoId, tipo.getNome());
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

    /** Ocorrência já pronta para exibição: o tipo do catálogo e o nome que vai à tela. */
    private record Marcacao(String tipoId, String nome) {}
}

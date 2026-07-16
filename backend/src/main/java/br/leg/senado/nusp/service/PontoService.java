package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoLotePaginaRepository;
import br.leg.senado.nusp.repository.PontoLoteRepository;
import br.leg.senado.nusp.repository.TecnicoRepository;
import br.leg.senado.nusp.service.SaldoAberturaService.ChavePessoa;
import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ponto e Banco de Horas — Passo 1: recebe o cartão-ponto (PDF multi-página)
 * do admin, separa página a página (OpenPDF), casa cada página a um
 * operador/técnico pelo nome e disponibiliza a folha individual para download.
 */
@Service
@RequiredArgsConstructor
public class PontoService {

    private static final Logger log = LoggerFactory.getLogger(PontoService.class);

    private final PontoLoteRepository loteRepo;
    private final PontoLotePaginaRepository paginaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;
    private final AvisoService avisoService;
    private final SaldoAberturaService saldoAberturaService;
    private final RetificacaoService retificacaoService;
    /** "A pessoa existe?" do par (id, tipo) — compartilhado com o MarcacaoService desde o F34. */
    private final PessoaCadastroLookup pessoaCadastro;

    @Value("${app.files.dir}")
    private String filesDir;

    /** Subpasta (sob app.files.dir) dos arquivos de ponto. NÃO é servida em /files/ (ver SecurityConfig). */
    private static final String PONTO_DIR = "ponto";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String STATUS_REVISAO   = "REVISAO";
    private static final String STATUS_PUBLICADO = "PUBLICADO";
    private static final String TIPO_MENSAL      = "MENSAL";
    private static final String MATCH_AUTO       = "AUTO";
    private static final String MATCH_MANUAL     = "MANUAL";
    private static final String MATCH_PENDENTE   = "PENDENTE";

    /** Rótulo da competência nas recusas de publicação (F32). */
    private static final DateTimeFormatter COMPETENCIA = DateTimeFormatter.ofPattern("MM/yyyy");

    // ══════════════════════════════════════════════════════════════
    // Upload + separação + vínculo automático (lote em REVISÃO)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> upload(MultipartFile arquivo, String tipoRaw,
                                      String dataInicioRaw, String dataFimRaw, String adminId) {
        String tipo = normalizeTipo(tipoRaw);
        LocalDate dataInicio = parseData(dataInicioRaw, "data_inicio");
        LocalDate dataFim = parseData(dataFimRaw, "data_fim");
        if (dataFim.isBefore(dataInicio)) {
            throw new ServiceValidationException("A data final não pode ser anterior à inicial.");
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ServiceValidationException("Arquivo PDF é obrigatório.");
        }

        final byte[] pdfBytes;
        try {
            pdfBytes = arquivo.getBytes();
        } catch (IOException e) {
            throw new ServiceValidationException("Não foi possível ler o arquivo enviado.");
        }

        PdfReader reader = abrirPdfValido(pdfBytes);
        int totalPaginas = reader.getNumberOfPages();
        PontoLote lote = criarLote(tipo, dataInicio, dataFim, totalPaginas, adminId, pdfBytes);
        List<Pessoa> pessoas = carregarPessoas();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        List<PontoLotePagina> paginas = new ArrayList<>();
        try {
            for (int i = 1; i <= totalPaginas; i++) {
                paginas.add(processarPagina(reader, extractor, i, lote, pessoas));
            }
        } finally {
            reader.close();
        }
        paginaRepo.saveAll(paginas);
        return detalheLote(lote, paginas, pessoas);
    }

    private PdfReader abrirPdfValido(byte[] pdfBytes) {
        PdfReader reader;
        try {
            reader = new PdfReader(pdfBytes);
        } catch (IOException | RuntimeException e) {
            throw new ServiceValidationException("O arquivo enviado não é um PDF válido.");
        }
        if (reader.getNumberOfPages() == 0) {
            reader.close();
            throw new ServiceValidationException("O PDF não tem páginas.");
        }
        return reader;
    }

    private PontoLote criarLote(String tipo, LocalDate dataInicio, LocalDate dataFim, int totalPaginas, String adminId, byte[] pdfBytes) {
        PontoLote lote = new PontoLote();
        lote.setTipo(tipo);
        lote.setDataInicio(dataInicio);
        lote.setDataFim(dataFim);
        lote.setTotalPaginas(totalPaginas);
        lote.setStatus(STATUS_REVISAO);
        lote.setCriadoPorId(adminId);
        String originalRel = PONTO_DIR + "/originais/" + UUID.randomUUID() + ".pdf";
        lote.setArquivoOriginal(originalRel);
        salvarArquivo(originalRel, pdfBytes);
        loteRepo.save(lote);
        return lote;
    }

    private PontoLotePagina processarPagina(PdfReader reader, PdfTextExtractor extractor, int i, PontoLote lote, List<Pessoa> pessoas) {
        byte[] pageBytes = extrairPagina(reader, i);
        String texto = "";
        try {
            texto = extractor.getTextFromPage(i);
        } catch (Exception e) {
            log.warn("Falha ao extrair texto da página {} do lote {}: {}", i, lote.getId(), e.getMessage());
        }
        String paginaRel = PONTO_DIR + "/paginas/" + UUID.randomUUID() + ".pdf";
        salvarArquivo(paginaRel, pageBytes);
        PontoLotePagina pg = new PontoLotePagina();
        pg.setLoteId(lote.getId());
        pg.setNumeroPagina(i);
        pg.setArquivoPagina(paginaRel);
        Pessoa match = casar(texto, pessoas);
        if (match != null) {
            pg.setPessoaId(match.id());
            pg.setPessoaTipo(match.tipo());
            pg.setStatusMatch(MATCH_AUTO);
            pg.setNomeExtraido(match.nome());
        } else {
            pg.setStatusMatch(MATCH_PENDENTE);
            pg.setNomeExtraido(extrairNomeHeuristica(texto));
        }
        return pg;
    }

    // ══════════════════════════════════════════════════════════════
    // Consulta (admin)
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarLotes() {
        // Pendentes por lote em 1 agregação (COUNT GROUP BY), no lugar de carregar todas as
        // páginas de cada lote só para contar (Q18). Lote sem página pendente não aparece → 0.
        Map<String, Long> pendentesPorLote = new HashMap<>();
        for (Object[] r : paginaRepo.contarPorLoteEStatusMatch(MATCH_PENDENTE)) {
            pendentesPorLote.put((String) r[0], ((Number) r[1]).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PontoLote l : loteRepo.findAllByOrderByCriadoEmDesc()) {
            out.add(cabecalho(l, pendentesPorLote.getOrDefault(l.getId(), 0L)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obterLote(String loteId) {
        PontoLote lote = buscarLote(loteId);
        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        return detalheLote(lote, paginas, carregarPessoas());
    }

    /** Lista plana de operadores + técnicos para o dropdown de vínculo manual. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPessoas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pessoa p : carregarPessoas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("nome", p.nome());
            m.put("tipo", p.tipo());
            out.add(m);
        }
        // F30: ordenação pt-BR única do sistema (a lista é montada em memória, não vem do banco).
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("nome")), NativeQueryUtils.ORDEM_TEXTO_PT_BR));
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Revisão + publicação (admin)
    // ══════════════════════════════════════════════════════════════

    /**
     * Vincula (ou desvincula, se pessoaId vazio) uma página a um operador/técnico.
     *
     * <p>O lote é lido com o MESMO lock pessimista da publicação (F58): sem ele, o vínculo feito com
     * uma publicação em voo lia o status ainda REVISAO (o commit da publicação não saiu), passava pela
     * checagem abaixo e sobrevivia — a pessoa ficava com folha publicada sem aviso, sem BANCO_FINAL_MIN
     * e sem re-âncora, e a guarda da mensal (F32) nunca a avaliava. Pior: como a página não tem
     * {@code @Version} nem {@code @DynamicUpdate}, o UPDATE de todas as colunas emitido pela publicação
     * podia sobrescrever o PESSOA_ID recém-gravado — o admin recebia 200 e a mudança sumia em silêncio.
     *
     * <p>Com o lock, os dois caminhos SERIALIZAM: ou a alteração entra antes e a publicação a enxerga
     * (guarda + aviso + âncora), ou ela espera o commit da publicação, relê o status já PUBLICADO e é
     * recusada aqui. Não há mais escritor concorrente da mesma página.
     */
    @Transactional
    public Map<String, Object> atualizarVinculo(String loteId, String paginaId,
                                                 String pessoaId, String pessoaTipo) {
        PontoLote lote = buscarLoteComLock(loteId);
        if (STATUS_PUBLICADO.equals(lote.getStatus())) {
            throw new ServiceValidationException("Lote já publicado não pode ser alterado.");
        }
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        if (!pg.getLoteId().equals(loteId)) {
            throw new ServiceValidationException("Página não pertence ao lote informado.");
        }

        if (pessoaId == null || pessoaId.isBlank()) {
            pg.setPessoaId(null);
            pg.setPessoaTipo(null);
            pg.setStatusMatch(MATCH_PENDENTE);
        } else {
            String tipo = PessoaCadastroLookup.normalizarTipo(pessoaTipo);
            if (!pessoaCadastro.existe(pessoaId, tipo)) {
                throw new ServiceValidationException("Operador/técnico/administrador inválido.");
            }
            pg.setPessoaId(pessoaId);
            pg.setPessoaTipo(tipo);
            pg.setStatusMatch(MATCH_MANUAL);
        }
        paginaRepo.save(pg);

        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        return detalheLote(lote, paginas, carregarPessoas());
    }

    /**
     * Publica o lote: as folhas ficam visíveis para os donos, o BANCO de cada página é extraído, as
     * pessoas são re-ancoradas e (opcionalmente) avisadas.
     *
     * <p>O lote é lido com LOCK PESSIMISTA de linha (F49): duas publicações concorrentes do MESMO
     * lote — o admin reclicando "Publicar" — serializam aqui. A segunda só entra depois do commit da
     * primeira, relê o status já PUBLICADO e é recusada, em vez de duplicar avisos e re-âncoras.
     *
     * <p>A guarda da folha mensal (F32) roda depois do lock e ANTES de qualquer escrita: recusa é
     * lote inteiro recusado, nada gravado.
     */
    @Transactional
    public Map<String, Object> publicar(String loteId, boolean emitirAviso) {
        PontoLote lote = buscarLoteComLock(loteId);
        if (STATUS_PUBLICADO.equals(lote.getStatus())) {
            // É esta a recusa que a 2ª transação concorrente recebe depois de esperar o lock — e ela
            // precisa chegar à TELA do admin, não só ao corpo do erro (daí o `message`; ver F57).
            throw recusa("Lote já está publicado.");
        }
        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        List<Pessoa> pessoas = carregarPessoas();
        exigirMensalUnica(lote, paginas, nomePorId(pessoas));

        lote.setStatus(STATUS_PUBLICADO);
        lote.setPublicadoEm(LocalDateTime.now());
        loteRepo.save(lote);

        extrairBancoFinal(paginas);
        paginaRepo.saveAll(paginas);
        reancorarPessoas(paginas);
        if (emitirAviso) criarAvisosPessoais(lote, paginas);
        return detalheLote(lote, paginas, pessoas);
    }

    // ══════════════════════════════════════════════════════════════
    // Guarda da folha MENSAL (F32) — unicidade por pessoa+competência
    // ══════════════════════════════════════════════════════════════

    /**
     * Recusa o lote INTEIRO se alguma pessoa dele colide com a regra da folha mensal: a MENSAL é
     * única por pessoa+competência e FECHA o mês — publicada, nem uma segunda mensal da pessoa nem
     * uma SEMANAL atrasada daquele mês podem ser publicadas depois. O conflito é procurado no banco
     * (mensais já publicadas) e, para um lote mensal, também DENTRO do próprio lote — duas páginas
     * da mesma pessoa. O admin remove a(s) página(s) nomeada(s) e republica.
     *
     * <p>SEMANAIS entre si continuam livres para se sobrepor: elas são CUMULATIVAS por desenho
     * (01–05, 01–12, 01–19…), e sobreposição de período é o funcionamento normal — não existe, e não
     * deve existir, validação genérica de sobreposição.
     */
    private void exigirMensalUnica(PontoLote lote, List<PontoLotePagina> paginas, Map<String, String> nomePorId) {
        List<PontoLotePagina> vinculadas = paginas.stream().filter(p -> p.getPessoaId() != null).toList();
        if (vinculadas.isEmpty()) return;

        boolean mensal = TIPO_MENSAL.equals(lote.getTipo());
        if (mensal) {
            List<String> repetidas = pessoasRepetidas(vinculadas);
            if (!repetidas.isEmpty()) {
                throw recusarPublicacao("o lote traz mais de uma folha mensal de "
                        + nomes(repetidas, nomePorId) + " na competência " + competencia(lote)
                        + ". Remova a página duplicada e publique novamente.");
            }
        }

        List<ConflitoMensal> conflitos = conflitosComMensalPublicada(lote, vinculadas);
        if (conflitos.isEmpty()) return;
        // A competência citada é a da MENSAL que já está publicada — não a janela consultada, que pode
        // abranger dois meses (lote que cruza a virada) e faria a frase mentir sobre o mês ainda aberto.
        String quem = nomesComCompetencia(conflitos, nomePorId);
        throw recusarPublicacao(mensal
                ? "já existe folha mensal publicada de " + quem
                        + ". Remova a página dessa(s) pessoa(s) do lote e publique novamente."
                : "o mês de " + quem + " já foi fechado por folha mensal publicada."
                        + " Remova a página dessa(s) pessoa(s) do lote e publique novamente.");
    }

    /** Pessoa do lote que já tem MENSAL publicada, com a competência (mês) dessa mensal. */
    private record ConflitoMensal(String pessoaId, YearMonth competencia) {}

    /** Pessoas do lote que já têm MENSAL publicada (em outro lote) tocando a competência dele. */
    private List<ConflitoMensal> conflitosComMensalPublicada(PontoLote lote, List<PontoLotePagina> vinculadas) {
        Set<String> pares = new HashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (PontoLotePagina p : vinculadas) {
            pares.add(chavePessoa(p.getPessoaId(), p.getPessoaTipo()));
            ids.add(p.getPessoaId());
        }
        List<ConflitoMensal> conflitos = new ArrayList<>();
        for (Object[] r : paginaRepo.findPessoasComMensalPublicadaNoPeriodo(
                lote.getId(), ids, inicioCompetencia(lote), fimCompetencia(lote))) {
            // A query casa por PESSOA_ID; a chave real do vínculo polimórfico é o par com PESSOA_TIPO.
            if (pares.contains(chavePessoa((String) r[0], (String) r[1]))) {
                conflitos.add(new ConflitoMensal((String) r[0], YearMonth.from((LocalDate) r[2])));
            }
        }
        return conflitos;
    }

    /** Pessoas presentes em mais de uma página vinculada do MESMO lote (conflito intra-lote). */
    private static List<String> pessoasRepetidas(List<PontoLotePagina> vinculadas) {
        Set<String> vistas = new HashSet<>();
        Set<String> repetidas = new LinkedHashSet<>();
        for (PontoLotePagina p : vinculadas) {
            if (!vistas.add(chavePessoa(p.getPessoaId(), p.getPessoaTipo()))) repetidas.add(p.getPessoaId());
        }
        return List.copyOf(repetidas);
    }

    private static String chavePessoa(String pessoaId, String pessoaTipo) {
        return pessoaId + "|" + pessoaTipo;
    }

    /**
     * Competência = os meses-calendário que o período do lote toca. Nos dados reais a MENSAL é o mês
     * cheio (dia 01 ao último) e a SEMANAL cabe dentro de um mês, mas o upload não valida nada disso
     * (o admin digita tipo e datas à mão) — por isso a janela vai do 1º dia do mês de DATA_INICIO ao
     * último dia do mês de DATA_FIM, e um período que cruzasse a virada fecharia os dois meses.
     */
    private static LocalDate inicioCompetencia(PontoLote lote) {
        return YearMonth.from(lote.getDataInicio()).atDay(1);
    }

    private static LocalDate fimCompetencia(PontoLote lote) {
        return YearMonth.from(lote.getDataFim()).atEndOfMonth();
    }

    private static String competencia(PontoLote lote) {
        String ini = YearMonth.from(lote.getDataInicio()).format(COMPETENCIA);
        String fim = YearMonth.from(lote.getDataFim()).format(COMPETENCIA);
        return ini.equals(fim) ? ini : ini + " a " + fim;
    }

    /** Nomes das pessoas em conflito — é por eles que o admin acha a página a remover do lote. */
    private static String nomes(List<String> pessoaIds, Map<String, String> nomePorId) {
        return pessoaIds.stream()
                .map(id -> nomePorId.getOrDefault(id, id))
                .sorted(Comparator.comparing(n -> n.toUpperCase(Locale.ROOT)))
                .collect(Collectors.joining(", "));
    }

    /** "Maria Silva (06/2026)" — o nome e o mês que a folha mensal dela já ocupa. */
    private static String nomesComCompetencia(List<ConflitoMensal> conflitos, Map<String, String> nomePorId) {
        return conflitos.stream()
                .map(c -> nomePorId.getOrDefault(c.pessoaId(), c.pessoaId())
                        + " (" + c.competencia().format(COMPETENCIA) + ")")
                .sorted(Comparator.comparing(n -> n.toUpperCase(Locale.ROOT)))
                .collect(Collectors.joining(", "));
    }

    /**
     * Recusa de publicação: 400 com a frase em {@code error} (o contrato de erro da API) E em
     * {@code message}. A duplicação não é decorativa: o botão "Publicar" do admin lê SÓ {@code message}
     * do corpo do erro (F57) — sem essa chave, a tela mostraria o genérico "Erro ao publicar." e os
     * nomes se perderiam justamente no erro que existe para nomeá-los.
     */
    private static ServiceValidationException recusarPublicacao(String detalhe) {
        return recusa("Publicação recusada: " + detalhe);
    }

    /** Recusa 400 visível nos dois campos do corpo de erro ({@code error} e {@code message}) — ver F57. */
    private static ServiceValidationException recusa(String frase) {
        return new ServiceValidationException(frase, HttpStatus.BAD_REQUEST, Map.of("message", frase));
    }

    // ══════════════════════════════════════════════════════════════
    // Banco de horas — BANCO_FINAL_MIN + âncora (E2)
    // ══════════════════════════════════════════════════════════════

    /** Extrai e grava o BANCO_FINAL_MIN de cada página vinculada (Q5). Nunca aborta a publicação. */
    private void extrairBancoFinal(List<PontoLotePagina> paginas) {
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() == null) continue;
            p.setBancoFinalMin(bancoFinalDaPagina(p));
        }
    }

    /** BANCO acumulado da folha em minutos; {@code null} (com WARN) em falha ou folha sem banco. */
    private Integer bancoFinalDaPagina(PontoLotePagina p) {
        try {
            String texto = extrairTextoFolha(lerArquivo(p.getArquivoPagina()));
            Integer min = SecullumFolhaParser.bancoFinalMin(SecullumFolhaParser.parse(texto));
            if (min == null) {
                log.warn("BANCO nao encontrado na pagina {} ({}) — BANCO_FINAL_MIN fica NULL.",
                        p.getId(), p.getArquivoPagina());
            }
            return min;
        } catch (Exception e) {
            log.warn("Falha ao extrair BANCO da pagina {} ({}): {} — BANCO_FINAL_MIN fica NULL.",
                    p.getId(), p.getArquivoPagina(), e.getMessage());
            return null;
        }
    }

    /** Re-ancora cada pessoa distinta com página vinculada (na transação corrente — gotcha 3). */
    private void reancorarPessoas(List<PontoLotePagina> paginas) {
        Set<ChavePessoa> pessoas = new LinkedHashSet<>();
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() != null) pessoas.add(new ChavePessoa(p.getPessoaId(), p.getPessoaTipo()));
        }
        reancorarEmOrdem(pessoas);
    }

    /**
     * Re-ancora um conjunto de pessoas SEMPRE na {@link SaldoAberturaService#ORDEM_DO_LOCK} (F60).
     * Cada re-âncora trava a linha de saldo da pessoa; percorrer o conjunto na ordem de chegada das
     * páginas — que difere de lote para lote — deixava duas publicações concorrentes com as mesmas
     * pessoas travarem em ordem inversa e morrerem em deadlock. Ordenar é o que elimina o ciclo: quem
     * chegar depois espera, e ninguém segura o que o outro quer.
     *
     * <p>O laço vive aqui (e não no {@code SaldoAberturaService}) porque {@code reancorar} tem de ser
     * chamado no BEAN injetado: dentro da própria classe seria self-invocation, e não atravessaria o
     * proxy transacional. A ORDEM, que é a invariante, é única e mora com o lock que ela protege.
     */
    private void reancorarEmOrdem(Collection<ChavePessoa> pessoas) {
        pessoas.stream().sorted(SaldoAberturaService.ORDEM_DO_LOCK)
                .forEach(p -> saldoAberturaService.reancorar(p.pessoaId(), p.pessoaTipo()));
    }

    /**
     * Backfill/manutenção do banco (E2): parseia as páginas publicadas
     * vinculadas ainda sem BANCO_FINAL_MIN e re-ancora todas as pessoas com
     * folha. Idempotente e re-executável — página com falha fica NULL (WARN),
     * é listada no retorno e será re-tentada na próxima execução (gotcha 10).
     */
    @Transactional
    public Map<String, Object> reprocessarBanco() {
        List<PontoLotePagina> pendentes = paginaRepo.findPublicadasSemBancoFinal();
        List<Map<String, Object>> semBanco = new ArrayList<>();
        int gravadas = 0;
        for (PontoLotePagina p : pendentes) {
            Integer min = bancoFinalDaPagina(p);
            p.setBancoFinalMin(min);
            if (min != null) gravadas++;
            else semBanco.add(resumoPaginaSemBanco(p));
        }
        paginaRepo.saveAll(pendentes);

        // Mesma ordem determinística da publicação (F60): o backfill re-ancora as pessoas TODAS, e sem
        // ordem comum ele e uma publicação simultânea travariam as mesmas linhas em sentidos opostos.
        List<ChavePessoa> pessoas = new ArrayList<>();
        for (Object[] par : paginaRepo.findPessoasComFolhaPublicada()) {
            pessoas.add(new ChavePessoa((String) par[0], (String) par[1]));
        }
        reancorarEmOrdem(pessoas);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paginas_processadas", pendentes.size());
        out.put("gravadas", gravadas);
        out.put("sem_banco", semBanco);
        out.put("pessoas_reancoradas", pessoas.size());
        return out;
    }

    private Map<String, Object> resumoPaginaSemBanco(PontoLotePagina p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pagina_id", p.getId());
        m.put("lote_id", p.getLoteId());
        m.put("numero_pagina", p.getNumeroPagina());
        m.put("nome_extraido", p.getNomeExtraido());
        return m;
    }

    /**
     * Ao publicar, dispara UM aviso PESSOAL (multi-alvo) avisando cada pessoa
     * com folha no lote — operador, técnico ou administrador. Some quando a
     * pessoa marca ciência. Páginas pendentes (sem pessoa) são ignoradas.
     */
    private void criarAvisosPessoais(PontoLote lote, List<PontoLotePagina> paginas) {
        List<AvisoService.DestinatarioAviso> destinatarios = new ArrayList<>();
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() == null) continue;
            PapelPessoa papel = PapelPessoa.dePessoaTipo(p.getPessoaTipo());
            if (papel != null) destinatarios.add(new AvisoService.DestinatarioAviso(p.getPessoaId(), papel));
        }
        if (destinatarios.isEmpty()) return;
        // O lote vai marcado no cadastro (ORIGEM_LOTE_ID): é por essa proveniência, e só por ela, que a
        // exclusão do lote sabe QUAIS avisos são dele (F59) — nunca por autor, tipo ou texto.
        avisoService.criarPessoalIndividual(destinatarios, mensagemFolhaPublicada(lote),
                lote.getCriadoPorId(), lote.getId());
    }

    private String mensagemFolhaPublicada(PontoLote lote) {
        String periodo = "SEMANAL".equals(lote.getTipo()) ? "semanal" : "mensal";
        LocalDate limite = retificacaoService.limiteRetificacao(lote);
        return "Sua folha de ponto " + periodo + " (" + ReportConfig.fmtDate(lote.getDataInicio())
                + " a " + ReportConfig.fmtDate(lote.getDataFim()) + ") foi publicada. "
                + "Acesse \"Minhas Folhas\" para visualizá-la."
                + (limite != null ? " Retificações até " + ReportConfig.fmtDate(limite) + "." : "");
    }

    // ══════════════════════════════════════════════════════════════
    // Operador / técnico
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> minhasFolhas(String pessoaId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : paginaRepo.findFolhasPublicadasByPessoa(pessoaId)) {
            PontoLotePagina p = (PontoLotePagina) row[0];
            PontoLote l = (PontoLote) row[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("tipo", l.getTipo());
            m.put("data_inicio", l.getDataInicio().toString());
            m.put("data_fim", l.getDataFim().toString());
            m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
            out.add(m);
        }
        return out;
    }

    /** Download da folha por um operador/técnico (só a própria) ou admin (qualquer). */
    @Transactional(readOnly = true)
    public ArquivoPonto baixarFolha(String paginaId, String solicitanteId, String role) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role);
        PontoLotePagina pg = fa.pagina();
        PontoLote lote = fa.lote();
        String nome = "ponto-" + lote.getTipo().toLowerCase(Locale.ROOT)
                + "-" + lote.getDataInicio() + "_a_" + lote.getDataFim() + ".pdf";
        return new ArquivoPonto(lerArquivo(pg.getArquivoPagina()), nome);
    }

    /**
     * Dados da folha (parse do PDF Secullum) para a tela de retificação: uma linha
     * por dia, com o texto VERBATIM em cada uma das 7 colunas. Mesma checagem de
     * dono do download.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dadosFolha(String paginaId, String solicitanteId, String role) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role);
        PontoLotePagina pg = fa.pagina();
        PontoLote lote = fa.lote();

        byte[] bytes = lerArquivo(pg.getArquivoPagina());
        String texto = extrairTextoFolha(bytes);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecullumFolhaParser.LinhaPonto l : SecullumFolhaParser.parse(texto)) rows.add(linhaToMap(l));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pg.getId());
        out.put("tipo", lote.getTipo());
        out.put("data_inicio", lote.getDataInicio().toString());
        out.put("data_fim", lote.getDataFim().toString());
        out.put("linhas", rows);
        return out;
    }

    private String extrairTextoFolha(byte[] bytes) {
        PdfReader reader;
        try {
            reader = new PdfReader(bytes);
        } catch (IOException | RuntimeException e) {
            throw new ServiceValidationException("Não foi possível ler o PDF da folha.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } catch (Exception e) {
            throw new ServiceValidationException("Não foi possível extrair os dados da folha.", HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            reader.close();
        }
    }

    private Map<String, Object> linhaToMap(SecullumFolhaParser.LinhaPonto l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dia", l.dia());
        m.put("ent1", l.ent1());
        m.put("sai1", l.sai1());
        m.put("ent2", l.ent2());
        m.put("sai2", l.sai2());
        m.put("total_dia", l.totalDia());
        m.put("banco", l.banco());
        return m;
    }

    private record FolhaAcesso(PontoLotePagina pagina, PontoLote lote) {}

    /** Localiza página + lote garantindo o acesso do solicitante (dono de lote publicado, ou admin). */
    private FolhaAcesso checarAcessoFolha(String paginaId, String solicitanteId, String role) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND));
        PontoLote lote = buscarLote(pg.getLoteId());
        if (!"administrador".equals(role)) {
            if (pg.getPessoaId() == null || !pg.getPessoaId().equals(solicitanteId)) {
                throw new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN);
            }
            if (!STATUS_PUBLICADO.equals(lote.getStatus())) {
                throw new ServiceValidationException("Folha indisponível.", HttpStatus.NOT_FOUND);
            }
        }
        return new FolhaAcesso(pg, lote);
    }

    /** Preview de uma página (admin, durante a revisão). */
    @Transactional(readOnly = true)
    public ArquivoPonto previewPagina(String paginaId) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        return new ArquivoPonto(lerArquivo(pg.getArquivoPagina()), "pagina-" + pg.getNumeroPagina() + ".pdf");
    }

    public record ArquivoPonto(byte[] conteudo, String nomeArquivo) {}

    // ══════════════════════════════════════════════════════════════
    // PDF — separação e texto (OpenPDF)
    // ══════════════════════════════════════════════════════════════

    private byte[] extrairPagina(PdfReader reader, int pageNum) {
        Document doc = new Document();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            PdfCopy copy = new PdfCopy(doc, baos);
            doc.open();
            copy.addPage(copy.getImportedPage(reader, pageNum));
            doc.close();
        } catch (Exception e) {
            throw new ServiceValidationException("Falha ao separar a página " + pageNum + " do PDF.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return baos.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════
    // Pareamento por nome
    // ══════════════════════════════════════════════════════════════

    private record Pessoa(String id, String tipo, String nome, String nomeNorm) {}

    private List<Pessoa> carregarPessoas() {
        List<Pessoa> out = new ArrayList<>();
        addPessoas(out, operadorRepo.findAll(), "OPERADOR", Operador::getId, Operador::getNomeCompleto);
        addPessoas(out, tecnicoRepo.findAll(), "TECNICO", Tecnico::getId, Tecnico::getNomeCompleto);
        // Alguns terceirizados (supervisor técnico, controlador) são admins do sistema.
        addPessoas(out, administradorRepo.findAll(), "ADMINISTRADOR", Administrador::getId, Administrador::getNomeCompleto);
        // Do nome mais longo para o mais curto: evita um nome curto casar dentro de um maior.
        out.sort((a, b) -> Integer.compare(b.nomeNorm().length(), a.nomeNorm().length()));
        return out;
    }

    private <T> void addPessoas(List<Pessoa> out, Iterable<T> entidades, String tipo,
                                java.util.function.Function<T, String> id, java.util.function.Function<T, String> nome) {
        for (T e : entidades) {
            String n = nome.apply(e);
            String norm = normalizar(n);
            if (!norm.isBlank()) out.add(new Pessoa(id.apply(e), tipo, n, norm));
        }
    }

    /**
     * Casa o texto da página contra a lista de pessoas: vínculo automático quando
     * o nome completo (normalizado) aparece como sequência de tokens no texto.
     */
    private Pessoa casar(String textoPagina, List<Pessoa> pessoas) {
        String alvo = " " + normalizar(textoPagina) + " ";
        for (Pessoa p : pessoas) { // já ordenado do nome mais longo para o mais curto
            if (alvo.contains(" " + p.nomeNorm() + " ")) return p;
        }
        return null;
    }

    /** Uppercase sem acentos, só [A-Z0-9], espaços colapsados. */
    private String normalizar(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /**
     * Melhor esforço para exibir o nome de uma página NÃO casada (apenas uma dica
     * na tela de revisão — o admin confere abrindo o preview). No cartão Secullum,
     * a assinatura traz "&lt;NOME&gt; PLANSUL PLANEJAMENTO ...".
     */
    private String extrairNomeHeuristica(String texto) {
        if (texto == null || texto.isBlank()) return null;
        int idx = texto.toUpperCase(Locale.ROOT).lastIndexOf("PLANSUL");
        if (idx <= 0) return null;
        String antes = texto.substring(0, idx).replaceAll("[_\\r\\n]+", " ").trim();
        String[] toks = antes.split("\\s+");
        // Na assinatura o nome vem em MAIÚSCULAS logo antes de "PLANSUL".
        // Pegamos a sequência final de tokens só-letras e em caixa-alta (isola o nome
        // do texto legal em caixa mista que o antecede).
        Deque<String> nome = new ArrayDeque<>();
        for (int k = toks.length - 1; k >= 0 && nome.size() < 8; k--) {
            String tk = toks[k];
            boolean nomeLike = tk.matches("[A-Za-zÀ-ÿ]{2,}") && tk.equals(tk.toUpperCase(Locale.ROOT));
            if (nomeLike) nome.addFirst(tk);
            else if (!nome.isEmpty()) break;
        }
        if (nome.size() < 2) return null;
        String r = String.join(" ", nome);
        return r.length() > 200 ? r.substring(0, 200) : r;
    }

    // ══════════════════════════════════════════════════════════════
    // Arquivos
    // ══════════════════════════════════════════════════════════════

    private void salvarArquivo(String relPath, byte[] bytes) {
        try {
            Path dest = Paths.get(filesDir).resolve(relPath);
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        } catch (IOException e) {
            log.error("Erro ao salvar arquivo de ponto {}: {}", relPath, e.getMessage());
            throw new ServiceValidationException("Erro ao salvar arquivo no servidor.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private byte[] lerArquivo(String relPath) {
        try {
            return Files.readAllBytes(Paths.get(filesDir).resolve(relPath));
        } catch (IOException e) {
            log.error("Erro ao ler arquivo de ponto {}: {}", relPath, e.getMessage());
            throw new ServiceValidationException("Arquivo não disponível no servidor.", HttpStatus.NOT_FOUND);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Montagem de resposta + helpers
    // ══════════════════════════════════════════════════════════════

    private Map<String, Object> detalheLote(PontoLote lote, List<PontoLotePagina> paginas, List<Pessoa> pessoas) {
        Map<String, String> nomePorId = nomePorId(pessoas);

        long pendentes = contarPendentes(paginas);
        Map<String, Object> m = cabecalho(lote, pendentes);

        List<Map<String, Object>> pgs = new ArrayList<>();
        for (PontoLotePagina p : paginas) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("numero_pagina", p.getNumeroPagina());
            pm.put("nome_extraido", p.getNomeExtraido());
            pm.put("pessoa_id", p.getPessoaId());
            pm.put("pessoa_tipo", p.getPessoaTipo());
            pm.put("pessoa_nome", p.getPessoaId() != null ? nomePorId.get(p.getPessoaId()) : null);
            pm.put("status_match", p.getStatusMatch());
            pgs.add(pm);
        }
        m.put("paginas", pgs);
        return m;
    }

    private Map<String, Object> cabecalho(PontoLote l, long pendentes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("tipo", l.getTipo());
        m.put("data_inicio", l.getDataInicio().toString());
        m.put("data_fim", l.getDataFim().toString());
        m.put("status", l.getStatus());
        m.put("total_paginas", l.getTotalPaginas());
        m.put("pendentes", pendentes);
        m.put("criado_em", l.getCriadoEm() != null ? l.getCriadoEm().toString() : null);
        m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
        return m;
    }

    private long contarPendentes(List<PontoLotePagina> paginas) {
        return paginas.stream().filter(p -> MATCH_PENDENTE.equals(p.getStatusMatch())).count();
    }

    private static Map<String, String> nomePorId(List<Pessoa> pessoas) {
        Map<String, String> m = new HashMap<>();
        for (Pessoa p : pessoas) m.put(p.id(), p.nome());
        return m;
    }

    private PontoLote buscarLote(String loteId) {
        return loteRepo.findById(loteId)
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
    }

    /**
     * Como o {@link #buscarLote}, mas segurando a linha (SELECT ... FOR UPDATE). É o portão único dos
     * dois caminhos que ESCREVEM o lote e suas páginas: a publicação (F49) e a alteração de vínculo
     * (F58). Quem lê sem escrever (detalhe, download, preview) continua no {@link #buscarLote}.
     */
    private PontoLote buscarLoteComLock(String loteId) {
        return loteRepo.lockPorId(loteId)
                .orElseThrow(() -> new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND));
    }

    private String normalizeTipo(String tipo) {
        String t = tipo == null ? "" : tipo.trim().toUpperCase(Locale.ROOT);
        if (!t.equals("SEMANAL") && !t.equals("MENSAL")) {
            throw new ServiceValidationException("Tipo inválido (use SEMANAL ou MENSAL).");
        }
        return t;
    }

    private LocalDate parseData(String raw, String campo) {
        try {
            return LocalDate.parse(raw.trim(), ISO);
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida em " + campo + " (use AAAA-MM-DD).");
        }
    }
}

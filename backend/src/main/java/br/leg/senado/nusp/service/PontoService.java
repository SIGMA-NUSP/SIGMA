package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoFolhaLinha;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.PapelPessoa;
import br.leg.senado.nusp.enums.SubtipoAviso;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.AdministradorRepository;
import br.leg.senado.nusp.repository.OperadorRepository;
import br.leg.senado.nusp.repository.PontoFolhaLinhaRepository;
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
import java.util.regex.Pattern;
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
    private final PontoFolhaLinhaRepository folhaLinhaRepo;
    private final OperadorRepository operadorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final AdministradorRepository administradorRepo;
    private final AvisoService avisoService;
    private final SaldoAberturaService saldoAberturaService;
    /** "A pessoa existe?" do par (id, tipo) — compartilhado com o MarcacaoService desde o F34. */
    private final PessoaCadastroLookup pessoaCadastro;

    @Value("${app.files.dir}")
    private String filesDir;

    /** Só o master envia e enxerga lotes ocultos — mesma propriedade que autoriza gerir administradores. */
    @Value("${app.admin.master-username}")
    private String masterUsername;

    /** Subpasta (sob app.files.dir) dos arquivos de ponto. NÃO é servida em /files/ (ver SecurityConfig). */
    private static final String PONTO_DIR = "ponto";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String STATUS_REVISAO   = "REVISAO";
    private static final String STATUS_PUBLICADO = "PUBLICADO";
    private static final String TIPO_MENSAL      = "MENSAL";
    private static final String TIPO_SEMANAL     = "SEMANAL";
    private static final String MATCH_AUTO       = "AUTO";
    private static final String MATCH_MANUAL     = "MANUAL";
    private static final String MATCH_PENDENTE   = "PENDENTE";

    // ══════════════════════════════════════════════════════════════
    // Upload + separação + vínculo automático (lote em REVISÃO)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> upload(MultipartFile arquivo, String tipoRaw, String categoriaRaw,
                                      String dataInicioRaw, String dataFimRaw, String adminId,
                                      boolean oculto, String username) {
        // Quem não vê lote oculto também não o cria: um admin comum enviaria algo que ele mesmo
        // nunca mais encontraria na lista.
        if (oculto && !ehMaster(username)) {
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
        }
        String tipo = normalizeTipo(tipoRaw);
        String categoria = normalizeCategoria(categoriaRaw, tipo);
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
        PontoLote lote = criarLote(tipo, categoria, dataInicio, dataFim, totalPaginas, adminId, pdfBytes, oculto);
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

    private PontoLote criarLote(String tipo, String categoria, LocalDate dataInicio, LocalDate dataFim,
                                int totalPaginas, String adminId, byte[] pdfBytes, boolean oculto) {
        PontoLote lote = new PontoLote();
        lote.setTipo(tipo);
        lote.setCategoria(categoria);
        lote.setDataInicio(dataInicio);
        lote.setDataFim(dataFim);
        lote.setTotalPaginas(totalPaginas);
        lote.setStatus(STATUS_REVISAO);
        lote.setOculto(oculto);
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
    public List<Map<String, Object>> listarLotes(String username) {
        // Pendentes por lote em 1 agregação (COUNT GROUP BY), no lugar de carregar todas as
        // páginas de cada lote só para contar (Q18). Lote sem página pendente não aparece → 0.
        Map<String, Long> pendentesPorLote = new HashMap<>();
        for (Object[] r : paginaRepo.contarPorLoteEStatusMatch(MATCH_PENDENTE)) {
            pendentesPorLote.put((String) r[0], ((Number) r[1]).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PontoLote l : loteRepo.findAllByOrderByCriadoEmDesc()) {
            // O lote oculto só existe na lista do master; para os demais admins é como se não houvesse.
            if (Boolean.TRUE.equals(l.getOculto()) && !ehMaster(username)) continue;
            out.add(cabecalho(l, pendentesPorLote.getOrDefault(l.getId(), 0L)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obterLote(String loteId, String username) {
        PontoLote lote = buscarLote(loteId);
        exigirLoteVisivel(lote, username);
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
                                                 String pessoaId, String pessoaTipo, String username) {
        PontoLote lote = buscarLoteComLock(loteId);
        exigirLoteVisivel(lote, username);
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
     * <p><b>Publicar pode SUBSTITUIR folhas já publicadas</b> — é o caminho da correção que a Plansul
     * reenvia. Quando o lote atinge folhas de alguém (a matriz do {@link #levantarSubstituicoes}), a
     * primeira chamada não publica nada: devolve quantas PESSOAS seriam substituídas, para o admin
     * confirmar sabendo o tamanho do gesto. É a segunda chamada, com {@code confirmarSubstituicao},
     * que publica. Lote sem nada a substituir publica direto, como sempre.
     *
     * <p>O lote é lido com LOCK PESSIMISTA de linha (F49): duas publicações concorrentes do MESMO
     * lote — o admin reclicando "Publicar" — serializam aqui. A segunda só entra depois do commit da
     * primeira, relê o status já PUBLICADO e é recusada, em vez de duplicar avisos e re-âncoras.
     *
     * <p>O levantamento roda SOB O LOCK e ANTES de qualquer escrita — a contagem que o admin
     * confirmou é cortesia, a verdade é esta. Recusa é lote inteiro recusado, nada gravado.
     */
    @Transactional
    public Map<String, Object> publicar(String loteId, boolean emitirAviso, boolean confirmarSubstituicao,
                                        String username) {
        PontoLote lote = buscarLoteComLock(loteId);
        exigirLoteVisivel(lote, username);
        if (STATUS_PUBLICADO.equals(lote.getStatus())) {
            // É esta a recusa que a 2ª transação concorrente recebe depois de esperar o lock — e ela
            // precisa chegar à TELA do admin, não só ao corpo do erro (daí o `message`; ver F57).
            throw recusa("Lote já está publicado.");
        }
        List<PontoLotePagina> paginas = paginaRepo.findByLoteIdOrderByNumeroPagina(loteId);
        List<Pessoa> pessoas = carregarPessoas();
        List<PontoLotePagina> vinculadas = paginas.stream().filter(p -> p.getPessoaId() != null).toList();

        exigirUmaFolhaMensalPorPessoa(lote, vinculadas, nomePorId(pessoas));
        Substituicoes substituicoes = levantarSubstituicoes(lote, vinculadas);
        if (!substituicoes.recusas().isEmpty()) throw recusarPublicacao(substituicoes.recusas());
        if (substituicoes.substitui() && !confirmarSubstituicao) return pedidoDeConfirmacao(substituicoes);

        lote.setStatus(STATUS_PUBLICADO);
        lote.setPublicadoEm(LocalDateTime.now());
        loteRepo.save(lote);

        extrairDadosDasFolhas(paginas);
        paginaRepo.saveAll(paginas);
        reancorarPessoas(paginas);
        encerrarAvisosDasFolhasSubstituidas(vinculadas, substituicoes.pessoas());
        // O lote oculto publica em silêncio absoluto: folha que ninguém vê não anuncia nada — nem o
        // aviso da publicação, nem o alerta de dia incompleto (não há retificação a provocar).
        if (!Boolean.TRUE.equals(lote.getOculto())) {
            criarAvisosPessoais(lote, paginas, emitirAviso);
        }
        return detalheLote(lote, paginas, pessoas);
    }

    /**
     * A folha nova tomou o lugar da antiga: as comunicações daquela publicação antiga apontam para
     * um documento que o dono não alcança mais, e por isso são encerradas — quem não foi substituído
     * segue recebendo a dele.
     *
     * <p>Quais folhas saíram de cena é a MESMA pergunta que a lista do dono responde ({@link
     * FolhaSubstituida}), feita depois das escritas: a folha recém-publicada já está no conjunto. Um
     * lote novo pode atingir folhas de vários lotes antigos, e cada um deles é limpo por inteiro.
     */
    private void encerrarAvisosDasFolhasSubstituidas(List<PontoLotePagina> vinculadas, Set<String> pessoasSubstituidas) {
        if (pessoasSubstituidas.isEmpty()) return;
        Map<String, Set<String>> pessoasPorLoteAntigo = new LinkedHashMap<>();
        Set<String> jaVistas = new HashSet<>();
        for (PontoLotePagina p : vinculadas) {
            if (!pessoasSubstituidas.contains(chavePessoa(p.getPessoaId(), p.getPessoaTipo()))) continue;
            if (!jaVistas.add(p.getPessoaId())) continue;   // a pessoa pode ocupar mais de uma página do lote
            List<Object[]> publicadas = paginaRepo.findFolhasPublicadasByPessoa(p.getPessoaId());
            Set<String> substituidas = FolhaSubstituida.paginasSubstituidas(publicadas);
            for (Object[] row : publicadas) {
                PontoLotePagina antiga = (PontoLotePagina) row[0];
                if (!substituidas.contains(antiga.getId())) continue;
                pessoasPorLoteAntigo
                        .computeIfAbsent(((PontoLote) row[1]).getId(), k -> new LinkedHashSet<>())
                        .add(p.getPessoaId());
            }
        }
        avisoService.encerrarComunicacoesDeFolhasSubstituidas(pessoasPorLoteAntigo);
    }

    // ══════════════════════════════════════════════════════════════
    // Guarda da publicação (F32) — o que SUBSTITUI e o que recusa
    // ══════════════════════════════════════════════════════════════

    /**
     * O que a publicação deste lote encontra pela frente, folha por folha das pessoas dele:
     *
     * <ul>
     *   <li><b>substituídas</b> — a folha nova ocupa o lugar da publicada: mensal sobre mensal da
     *       mesma competência (prévia sobre prévia, definitiva sobre prévia, definitiva sobre
     *       definitiva) e semanal sobre semanal do período <b>exatamente</b> igual. Cada pessoa
     *       conta uma vez; é esse número que o admin confirma;</li>
     *   <li><b>recusas</b> — o que não se substitui de jeito nenhum: uma PRÉVIA por cima da
     *       DEFINITIVA que já fechou o mês, e uma SEMANAL atrasada de mês que uma mensal publicada
     *       já ocupa. Uma recusa derruba o lote INTEIRO.</li>
     * </ul>
     *
     * <p>O que <b>não</b> é conflito, e por isso não aparece aqui nem gera aviso: a mensal que chega
     * onde só há semanais do mês (as folhas convivem — a hierarquia do sumário resolve o mês) e
     * semanais de períodos diferentes entre si, que são CUMULATIVAS por desenho (01–05, 01–12,
     * 01–19…). Não existe, e não deve existir, validação genérica de sobreposição de período.
     *
     * <p>Folha de lote OCULTO conta aqui como qualquer outra, nas duas direções: retirá-la deixaria
     * duas folhas do mesmo mês convivendo sem regra de qual vale. O preço é a mensagem citar uma
     * competência que o admin comum não encontra em lote nenhum — o acervo cobre meses anteriores à
     * operação, então a colisão só existe se alguém publicar retroativo por cima dele.
     */
    private Substituicoes levantarSubstituicoes(PontoLote lote, List<PontoLotePagina> vinculadas) {
        if (vinculadas.isEmpty()) return Substituicoes.NENHUMA;

        Set<String> pares = new HashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (PontoLotePagina p : vinculadas) {
            pares.add(chavePessoa(p.getPessoaId(), p.getPessoaTipo()));
            ids.add(p.getPessoaId());
        }

        boolean mensal = TIPO_MENSAL.equals(lote.getTipo());
        boolean definitiva = PontoLote.CATEGORIA_DEFINITIVA.equals(lote.getCategoria());
        Set<String> substituidas = new LinkedHashSet<>();
        List<FolhaPublicada> recusas = new ArrayList<>();

        for (Object[] r : paginaRepo.findPessoasComMensalPublicadaNoPeriodo(
                lote.getId(), ids, inicioCompetencia(lote), fimCompetencia(lote))) {
            // A query casa por PESSOA_ID; a chave real do vínculo polimórfico é o par com PESSOA_TIPO.
            String chave = chavePessoa((String) r[0], (String) r[1]);
            if (!pares.contains(chave)) continue;
            String categoriaPublicada = (String) r[3];
            // A competência é a da MENSAL publicada — não a janela consultada, que pode abranger dois
            // meses (lote que cruza a virada) e faria a frase mentir sobre o mês ainda aberto.
            FolhaPublicada publicada = new FolhaPublicada(YearMonth.from((LocalDate) r[2]), categoriaPublicada);
            if (!mensal) {
                // A mensal ocupa o mês inteiro: uma semanal atrasada dele não tem mais onde entrar.
                recusas.add(publicada);
            } else if (!definitiva && PontoLote.CATEGORIA_DEFINITIVA.equals(categoriaPublicada)) {
                recusas.add(publicada);   // prévia por cima da definitiva: o mês já está fechado
            } else {
                substituidas.add(chave);
            }
        }

        if (!mensal) {
            for (Object[] r : paginaRepo.findPessoasComSemanalPublicadaNoPeriodoExato(
                    lote.getId(), ids, lote.getDataInicio(), lote.getDataFim())) {
                String chave = chavePessoa((String) r[0], (String) r[1]);
                if (pares.contains(chave)) substituidas.add(chave);
            }
        }
        return new Substituicoes(substituidas, recusas);
    }

    /** Uma folha já publicada que barra este lote: de que mês ela é e de que natureza. */
    private record FolhaPublicada(YearMonth competencia, String categoria) {}

    /** O resultado do levantamento: quem será substituído e o que impede a publicação. */
    private record Substituicoes(Set<String> pessoas, List<FolhaPublicada> recusas) {

        static final Substituicoes NENHUMA = new Substituicoes(Set.of(), List.of());

        boolean substitui() {
            return !pessoas.isEmpty();
        }
    }

    /**
     * A resposta do 1º passo: o lote NÃO foi publicado, e o admin precisa dizer se aceita substituir
     * as folhas de tantas pessoas. Só isso — a lista de nomes está na tela de revisão, na frente dele.
     */
    private static Map<String, Object> pedidoDeConfirmacao(Substituicoes substituicoes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requer_confirmacao", true);
        out.put("substituicoes", substituicoes.pessoas().size());
        return out;
    }

    /**
     * Duas folhas MENSAIS da mesma pessoa no MESMO lote: não há como saber qual delas vale, e nenhuma
     * pode substituir a outra. O admin desfaz o vínculo de uma das páginas (ou exclui o lote). Num
     * lote SEMANAL isso é legítimo — a folha da pessoa pode ocupar duas páginas do PDF.
     */
    private static void exigirUmaFolhaMensalPorPessoa(PontoLote lote, List<PontoLotePagina> vinculadas,
                                                      Map<String, String> nomePorId) {
        if (!TIPO_MENSAL.equals(lote.getTipo())) return;
        Set<String> vistas = new HashSet<>();
        Set<String> repetidas = new LinkedHashSet<>();
        for (PontoLotePagina p : vinculadas) {
            if (!vistas.add(chavePessoa(p.getPessoaId(), p.getPessoaTipo()))) repetidas.add(p.getPessoaId());
        }
        if (repetidas.isEmpty()) return;
        throw recusarPublicacao("Há mais de uma folha de " + nomes(repetidas, nomePorId) + " no lote.");
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

    /** Nomes das pessoas em conflito — é por eles que o admin acha a página a desvincular. */
    private static String nomes(Collection<String> pessoaIds, Map<String, String> nomePorId) {
        return pessoaIds.stream()
                .map(id -> nomePorId.getOrDefault(id, id))
                .sorted(Comparator.comparing(n -> n.toUpperCase(Locale.ROOT)))
                .collect(Collectors.joining(", "));
    }

    /**
     * A recusa nomeia UMA folha publicada: a que mais pesa contra este lote — a definitiva antes da
     * prévia e, entre iguais, o mês mais antigo. Citar todas seria a lista quilométrica que esta
     * mensagem existe para eliminar; o admin não precisa dela para agir (a ação é desfazer o lote).
     */
    private static ServiceValidationException recusarPublicacao(List<FolhaPublicada> recusas) {
        FolhaPublicada folha = recusas.stream().min(RECUSA_MAIS_FORTE).orElseThrow();
        return recusarPublicacao("Já existe folha " + naturezaPorExtenso(folha.categoria())
                + " de " + ReportConfig.fmtCompetencia(folha.competencia().atDay(1)) + " publicada.");
    }

    private static final Comparator<FolhaPublicada> RECUSA_MAIS_FORTE = Comparator
            .comparingInt((FolhaPublicada f) -> PontoLote.CATEGORIA_DEFINITIVA.equals(f.categoria()) ? 0 : 1)
            .thenComparing(FolhaPublicada::competencia);

    /** A natureza como o admin a lê. Folha mensal antiga, sem natureza gravada, é só "mensal". */
    private static String naturezaPorExtenso(String categoria) {
        if (PontoLote.CATEGORIA_PREVIA.equals(categoria)) return "prévia";
        if (PontoLote.CATEGORIA_DEFINITIVA.equals(categoria)) return "definitiva";
        return "mensal";
    }

    /**
     * Recusa de publicação: 400 com a frase em {@code error} (o contrato de erro da API) E em
     * {@code message}. A duplicação não é decorativa: o botão "Publicar" do admin lê SÓ {@code message}
     * do corpo do erro (F57) — sem essa chave, a tela mostraria o genérico "Erro ao publicar." e o
     * motivo se perderia justamente no erro que existe para explicá-lo.
     */
    private static ServiceValidationException recusarPublicacao(String fato) {
        return recusa("Não foi possível publicar o lote. " + fato);
    }

    /** Recusa 400 visível nos dois campos do corpo de erro ({@code error} e {@code message}) — ver F57. */
    private static ServiceValidationException recusa(String frase) {
        return new ServiceValidationException(frase, HttpStatus.BAD_REQUEST, Map.of("message", frase));
    }

    // ══════════════════════════════════════════════════════════════
    // Banco de horas — BANCO_FINAL_MIN + âncora (E2)
    // ══════════════════════════════════════════════════════════════

    /**
     * Lê o PDF de cada página vinculada UMA vez e dele tira as duas coisas que a publicação
     * persiste: o BANCO acumulado da pessoa e a tabela da folha, linha a linha.
     *
     * <p>Folha ilegível nunca aborta a publicação: a página fica sem banco (NULL) e sem linhas
     * gravadas, com WARN — o admin republica ou reprocessa depois.
     */
    private void extrairDadosDasFolhas(List<PontoLotePagina> paginas) {
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() == null) continue;
            List<SecullumFolhaParser.LinhaPonto> linhas = linhasDaPagina(p);
            p.setBancoFinalMin(bancoFinalDaPagina(p, linhas));
            gravarLinhasDaFolha(p, linhas);
        }
    }

    /** Linhas-dia da folha; lista vazia (com WARN) quando o PDF não pode ser lido ou parseado. */
    private List<SecullumFolhaParser.LinhaPonto> linhasDaPagina(PontoLotePagina p) {
        try {
            return SecullumFolhaParser.parse(extrairTextoFolha(lerArquivo(p.getArquivoPagina())));
        } catch (Exception e) {
            log.warn("Falha ao ler a folha da pagina {} ({}): {} — sem BANCO e sem linhas gravadas.",
                    p.getId(), p.getArquivoPagina(), e.getMessage());
            return List.of();
        }
    }

    /** BANCO acumulado da folha em minutos; {@code null} (com WARN) na folha sem banco. */
    private Integer bancoFinalDaPagina(PontoLotePagina p, List<SecullumFolhaParser.LinhaPonto> linhas) {
        Integer min = SecullumFolhaParser.bancoFinalMin(linhas);
        if (min == null && !linhas.isEmpty()) {
            log.warn("BANCO nao encontrado na pagina {} ({}) — BANCO_FINAL_MIN fica NULL.",
                    p.getId(), p.getArquivoPagina());
        }
        return min;
    }

    /**
     * Regrava a tabela da folha. As linhas anteriores saem antes: reprocessar a mesma folha grava
     * tudo de novo, e a unicidade (página, ordem) não admite duas gerações convivendo.
     */
    private void gravarLinhasDaFolha(PontoLotePagina p, List<SecullumFolhaParser.LinhaPonto> linhas) {
        folhaLinhaRepo.deleteByPaginaId(p.getId());
        folhaLinhaRepo.flush();
        if (linhas.isEmpty()) return;
        List<PontoFolhaLinha> novas = new ArrayList<>(linhas.size());
        int ordem = 1;
        for (SecullumFolhaParser.LinhaPonto l : linhas) {
            novas.add(linhaDaFolha(p.getId(), ordem++, l));
        }
        folhaLinhaRepo.saveAll(novas);
    }

    /** Linha gravada: as 7 colunas verbatim + a data e a ocorrência derivadas delas. */
    private static PontoFolhaLinha linhaDaFolha(String paginaId, int ordem, SecullumFolhaParser.LinhaPonto l) {
        PontoFolhaLinha e = new PontoFolhaLinha();
        e.setPaginaId(paginaId);
        e.setOrdem(ordem);
        e.setDiaVerbatim(vazioParaNulo(l.dia()));
        e.setEnt1(vazioParaNulo(l.ent1()));
        e.setSai1(vazioParaNulo(l.sai1()));
        e.setEnt2(vazioParaNulo(l.ent2()));
        e.setSai2(vazioParaNulo(l.sai2()));
        e.setTotalDia(vazioParaNulo(l.totalDia()));
        e.setBanco(vazioParaNulo(l.banco()));
        e.setData(SecullumFolhaParser.dataDe(l));
        e.setOcorrencia(SecullumFolhaParser.ocorrenciaDe(l));
        return e;
    }

    /** Célula em branco na folha vira nulo — no Oracle, string vazia e nulo são a mesma coisa. */
    private static String vazioParaNulo(String s) {
        return s == null || s.isEmpty() ? null : s;
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
            List<SecullumFolhaParser.LinhaPonto> linhas = linhasDaPagina(p);
            Integer min = bancoFinalDaPagina(p, linhas);
            p.setBancoFinalMin(min);
            gravarLinhasDaFolha(p, linhas);
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

    /** Batida: {@code hh:mm} sem sinal — o total do dia vem com sinal, e o status do dia é texto. */
    private static final Pattern BATIDA = Pattern.compile("^\\d{1,2}:\\d{2}$");

    /**
     * Onde começam as quatro células de horário na linha lida da folha, que vem como
     * {@code [paginaId, ocorrencia, data, diaVerbatim, ENT. 1, SAÍ. 1, ENT. 2, SAÍ. 2]}.
     */
    private static final int PRIMEIRA_CELULA = 4;

    /** Como o dia é nomeado à pessoa: dia e mês bastam — o ano é o da folha que ela tem diante de si. */
    private static final DateTimeFormatter DIA_E_MES = DateTimeFormatter.ofPattern("dd/MM");

    /** O que a pessoa lê quando a folha tem dia pela metade e nem sequer foi possível nomear o dia. */
    private static final String MENSAGEM_REGISTRO_INCOMPLETO =
            "Sua folha tem dias com registro de entrada ou saída incompleto.";

    /** Um dia sem o par de batidas fechado: a data ordena, o rótulo é como a pessoa lê o dia. */
    private record DiaIncompleto(LocalDate data, String rotulo) {}

    /**
     * Ao publicar, avisa cada pessoa com folha no lote — operador, técnico ou administrador. Some
     * quando a pessoa marca ciência. Páginas pendentes (sem pessoa) são ignoradas.
     *
     * <p>Quem tem dia com registro de entrada ou saída faltando é avisado <b>à parte</b>: recebe o
     * aviso da folha SOMADO à relação dos dias dele que ficaram pela metade, num cadastro próprio e
     * com subtipo próprio — é o que separa as duas linhas na tabela do administrador. Os dias são
     * dirigidos a cada destinatário, e ninguém lê o dia do outro. Os dois grupos são disjuntos, e
     * cada pessoa recebe um aviso só.
     *
     * <p>O alerta do dia incompleto <b>não depende do "Emitir aviso"</b> do administrador: ele existe
     * para provocar a correção enquanto o prazo de retificação corre, e por isso sai mesmo na
     * publicação feita em silêncio. Publicação silenciosa e nenhum dia incompleto: ninguém é avisado.
     *
     * <p>Os dois cadastros nascem marcados com o lote (ORIGEM_LOTE_ID): é por essa proveniência, e só
     * por ela, que a exclusão do lote sabe QUAIS avisos são dele — nunca por autor, tipo ou texto.
     */
    private void criarAvisosPessoais(PontoLote lote, List<PontoLotePagina> paginas, boolean emitirAviso) {
        Map<String, List<DiaIncompleto>> diasPorFolha = folhasComDiaIncompleto(lote, paginas);

        // A chave é o destinatário SEM complemento: quem pertence a um grupo ou ao outro é a PESSOA, e
        // quem tem duas folhas no lote soma os dias das duas num aviso só.
        Map<AvisoService.DestinatarioAviso, List<DiaIncompleto>> comDiaIncompleto = new LinkedHashMap<>();
        Set<AvisoService.DestinatarioAviso> demais = new LinkedHashSet<>();
        for (PontoLotePagina p : paginas) {
            if (p.getPessoaId() == null) continue;
            PapelPessoa papel = PapelPessoa.dePessoaTipo(p.getPessoaTipo());
            if (papel == null) continue;
            AvisoService.DestinatarioAviso pessoa = new AvisoService.DestinatarioAviso(p.getPessoaId(), papel);
            List<DiaIncompleto> dias = diasPorFolha.get(p.getId());
            if (dias == null) demais.add(pessoa);
            else comDiaIncompleto.computeIfAbsent(pessoa, k -> new ArrayList<>()).addAll(dias);
        }
        // Uma folha incompleta basta: quem tem duas folhas no lote, uma delas com dia pela metade,
        // pertence ao grupo do alerta e a nenhum outro.
        demais.removeAll(comDiaIncompleto.keySet());

        boolean avisarDemais = emitirAviso && !demais.isEmpty();
        if (!avisarDemais && comDiaIncompleto.isEmpty()) return;

        String folhaPublicada = mensagemFolhaPublicada(lote);
        if (avisarDemais) {
            avisoService.criarPessoalIndividual(new ArrayList<>(demais), folhaPublicada,
                    lote.getCriadoPorId(), subtipoDaFolha(lote), lote.getId());
        }
        if (!comDiaIncompleto.isEmpty()) {
            List<AvisoService.DestinatarioAviso> alertados = comDiaIncompleto.entrySet().stream()
                    .map(e -> new AvisoService.DestinatarioAviso(e.getKey().pessoaId(), e.getKey().papel(),
                            textoDosDiasIncompletos(e.getValue())))
                    .toList();
            avisoService.criarPessoalIndividual(alertados, folhaPublicada,
                    lote.getCriadoPorId(), SubtipoAviso.FOLHA_REGISTRO_INCOMPLETO, lote.getId());
        }
    }

    /**
     * O que a pessoa lê depois do aviso da folha: os dias DELA que ficaram sem entrada ou sem saída,
     * em ordem cronológica e sem repetição. Um dia só é anunciado no singular — a lista de vários
     * separa com vírgulas e fecha com "e".
     *
     * <p>Dia que não pôde ser nomeado não aparece na relação; se nenhum puder, a pessoa ainda é
     * avisada de que a folha tem dia pela metade, só que sem poder apontar qual.
     */
    private static String textoDosDiasIncompletos(List<DiaIncompleto> dias) {
        List<String> rotulos = dias.stream()
                .sorted(Comparator.comparing(DiaIncompleto::data,
                        Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())))
                .map(DiaIncompleto::rotulo)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rotulos.isEmpty()) return MENSAGEM_REGISTRO_INCOMPLETO;
        if (rotulos.size() == 1)
            return "O dia " + rotulos.get(0) + " da sua folha está sem registro de entrada ou de saída.";
        String lista = String.join(", ", rotulos.subList(0, rotulos.size() - 1))
                + " e " + rotulos.get(rotulos.size() - 1);
        return "Os dias " + lista + " da sua folha estão sem registro de entrada ou de saída.";
    }

    /** A folha semanal e a mensal se anunciam com subtipos diferentes — é o rótulo que o admin lê. */
    private SubtipoAviso subtipoDaFolha(PontoLote lote) {
        return TIPO_SEMANAL.equals(lote.getTipo()) ? SubtipoAviso.FOLHA_SEMANAL : SubtipoAviso.FOLHA_MENSAL;
    }

    /**
     * Os dias com registro pela metade de cada folha do lote — dia de número ÍMPAR de batidas (1 ou
     * 3): uma entrada ou uma saída não foi registrada, e o dia inteiro vira débito no banco de horas
     * se ninguém corrigir. Dia sem batida nenhuma e dia com o par completo não são cobrados, e a
     * regra vale igual para qualquer jornada. Folha sem nenhum dia assim fica fora do mapa.
     *
     * <p>A conferência é sobre a folha COMO ELA FOI PUBLICADA, lida da tabela que a publicação
     * acabou de gravar — nunca sobre o PDF de novo, nem sobre as retificações: o aviso existe
     * justamente para provocá-las.
     *
     * <p>A folha mensal DEFINITIVA fica de fora: ela fecha o mês e não aceita mais retificação, e
     * avisar seria pedir o que já não pode ser feito.
     */
    private Map<String, List<DiaIncompleto>> folhasComDiaIncompleto(PontoLote lote, List<PontoLotePagina> paginas) {
        if (PontoLote.CATEGORIA_DEFINITIVA.equals(lote.getCategoria())) return Map.of();
        List<String> paginaIds = paginas.stream()
                .filter(p -> p.getPessoaId() != null)
                .map(PontoLotePagina::getId)
                .toList();
        if (paginaIds.isEmpty()) return Map.of();

        Map<String, List<DiaIncompleto>> incompletas = new LinkedHashMap<>();
        for (Object[] linha : folhaLinhaRepo.findCelulasDasFolhas(paginaIds)) {
            String paginaId = (String) linha[0];
            String ocorrencia = (String) linha[1];
            // Dia de status (Feriado, Falta, FERNC…) não tem batida a contar.
            if (ocorrencia != null) continue;
            if (batidasDoDia(linha) % 2 == 0) continue;
            incompletas.computeIfAbsent(paginaId, k -> new ArrayList<>())
                    .add(new DiaIncompleto((LocalDate) linha[2], rotuloDoDia((LocalDate) linha[2], (String) linha[3])));
        }
        return incompletas;
    }

    /**
     * Como o dia é nomeado no aviso: "dd/MM" da data do dia; sem ela, os cinco primeiros caracteres
     * do dia como a folha o imprimiu ("14/07/26 - ter" → "14/07"). Nulo quando nem isso existe — o
     * dia continua contando para o alerta, só não há como citá-lo.
     */
    private static String rotuloDoDia(LocalDate data, String diaVerbatim) {
        if (data != null) return data.format(DIA_E_MES);
        String impresso = diaVerbatim == null ? "" : diaVerbatim.trim();
        return impresso.length() >= 5 ? impresso.substring(0, 5) : null;
    }

    /** Quantas das quatro células de horário do dia trazem batida de fato. */
    private static int batidasDoDia(Object[] linha) {
        int batidas = 0;
        for (int i = PRIMEIRA_CELULA; i < linha.length; i++) {
            String celula = (String) linha[i];
            if (celula != null && BATIDA.matcher(celula.trim()).matches()) batidas++;
        }
        return batidas;
    }

    /**
     * Texto do aviso de publicação. A definitiva é a única que não convida a conferir: ela fecha o
     * mês — o aviso dela é só o registro de que o mês foi encerrado.
     */
    private String mensagemFolhaPublicada(PontoLote lote) {
        String competencia = ReportConfig.fmtCompetencia(lote.getDataInicio());
        if (TIPO_MENSAL.equals(lote.getTipo()) && PontoLote.CATEGORIA_DEFINITIVA.equals(lote.getCategoria())) {
            return "Folha de ponto definitiva do mês " + competencia + " publicada.";
        }
        // Mensal é identificada pela competência ("Junho/2026"); semanal, pelo intervalo de datas.
        boolean semanal = TIPO_SEMANAL.equals(lote.getTipo());
        String folhaTxt = semanal ? "semanal" : "mensal — prévia";
        String periodoTxt = semanal
                ? ReportConfig.fmtDate(lote.getDataInicio()) + " a " + ReportConfig.fmtDate(lote.getDataFim())
                : competencia;
        return "Folha de ponto " + folhaTxt + " (" + periodoTxt
                + ") publicada. "
                + "Acesse \"Minhas Folhas\" para visualizá-la.";
    }

    // ══════════════════════════════════════════════════════════════
    // Operador / técnico
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> minhasFolhas(String pessoaId) {
        List<Object[]> publicadas = paginaRepo.findFolhasPublicadasByPessoa(pessoaId);
        Set<String> substituidas = FolhaSubstituida.paginasSubstituidas(publicadas);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : publicadas) {
            PontoLotePagina p = (PontoLotePagina) row[0];
            PontoLote l = (PontoLote) row[1];
            if (substituidas.contains(p.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("tipo", l.getTipo());
            m.put("categoria", l.getCategoria());
            m.put("data_inicio", l.getDataInicio().toString());
            m.put("data_fim", l.getDataFim().toString());
            m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
            out.add(m);
        }
        return out;
    }

    /**
     * A folha foi substituída por outra publicada depois (a regra do {@link FolhaSubstituida})?
     *
     * <p>Substituída, ela deixa de ser a folha daquele período: sai da lista do dono e o acesso
     * direto a ela acompanha. Para o admin nada some — o histórico de lotes continua inteiro.
     */
    private boolean substituidaParaODono(PontoLotePagina pagina) {
        return FolhaSubstituida.substituida(pagina.getId(),
                paginaRepo.findFolhasPublicadasByPessoa(pagina.getPessoaId()));
    }

    /** Download da folha por um operador/técnico (só a própria) ou admin (qualquer). */
    @Transactional(readOnly = true)
    public ArquivoPonto baixarFolha(String paginaId, String solicitanteId, String role, String username) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role, username);
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
    public Map<String, Object> dadosFolha(String paginaId, String solicitanteId, String role, String username) {
        FolhaAcesso fa = checarAcessoFolha(paginaId, solicitanteId, role, username);
        PontoLotePagina pg = fa.pagina();
        PontoLote lote = fa.lote();

        byte[] bytes = lerArquivo(pg.getArquivoPagina());
        String texto = extrairTextoFolha(bytes);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecullumFolhaParser.LinhaPonto l : SecullumFolhaParser.parse(texto)) rows.add(linhaToMap(l));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pg.getId());
        out.put("tipo", lote.getTipo());
        out.put("categoria", lote.getCategoria());
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
    private FolhaAcesso checarAcessoFolha(String paginaId, String solicitanteId, String role, String username) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND));
        PontoLote lote = buscarLote(pg.getLoteId());
        if (!"administrador".equals(role)) {
            if (pg.getPessoaId() == null || !pg.getPessoaId().equals(solicitanteId)) {
                throw new ServiceValidationException("Acesso negado a esta folha.", HttpStatus.FORBIDDEN);
            }
            // A folha de lote oculto não existe para o dono — mesma resposta da substituída.
            if (!STATUS_PUBLICADO.equals(lote.getStatus()) || Boolean.TRUE.equals(lote.getOculto())
                    || substituidaParaODono(pg)) {
                throw new ServiceValidationException("Folha indisponível.", HttpStatus.NOT_FOUND);
            }
        } else if (Boolean.TRUE.equals(lote.getOculto()) && !ehMaster(username)) {
            // Para o admin comum a folha oculta também não existe — mesma resposta da inexistente.
            throw new ServiceValidationException("Folha não encontrada.", HttpStatus.NOT_FOUND);
        }
        return new FolhaAcesso(pg, lote);
    }

    /** Preview de uma página (admin, durante a revisão). */
    @Transactional(readOnly = true)
    public ArquivoPonto previewPagina(String paginaId, String username) {
        PontoLotePagina pg = paginaRepo.findById(paginaId)
                .orElseThrow(() -> new ServiceValidationException("Página não encontrada.", HttpStatus.NOT_FOUND));
        exigirLoteVisivel(buscarLote(pg.getLoteId()), username);
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
        m.put("substituicoes", substituicoesPrevistas(lote, paginas));

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
        m.put("categoria", l.getCategoria());
        m.put("data_inicio", l.getDataInicio().toString());
        m.put("data_fim", l.getDataFim().toString());
        m.put("status", l.getStatus());
        m.put("oculto", Boolean.TRUE.equals(l.getOculto()));
        m.put("total_paginas", l.getTotalPaginas());
        m.put("pendentes", pendentes);
        m.put("criado_em", l.getCriadoEm() != null ? l.getCriadoEm().toString() : null);
        m.put("publicado_em", l.getPublicadoEm() != null ? l.getPublicadoEm().toString() : null);
        return m;
    }

    private long contarPendentes(List<PontoLotePagina> paginas) {
        return paginas.stream().filter(p -> MATCH_PENDENTE.equals(p.getStatusMatch())).count();
    }

    /**
     * Quantas pessoas este lote substituiria se fosse publicado AGORA — o aviso que a tela de revisão
     * mostra antes do gesto. É consultivo, e por isso acompanha o detalhe do lote: cada vínculo que o
     * admin muda devolve o lote inteiro, e o número anda junto. A palavra final continua sendo a da
     * publicação, que refaz a conta sob o lock.
     *
     * <p>Lote já publicado substituiu o que tinha de substituir: a pergunta não se aplica a ele.
     */
    private int substituicoesPrevistas(PontoLote lote, List<PontoLotePagina> paginas) {
        if (!STATUS_REVISAO.equals(lote.getStatus())) return 0;
        List<PontoLotePagina> vinculadas = paginas.stream().filter(p -> p.getPessoaId() != null).toList();
        return levantarSubstituicoes(lote, vinculadas).pessoas().size();
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

    /** Lote oculto só existe para o admin master; para qualquer outro é como se não existisse. */
    private void exigirLoteVisivel(PontoLote lote, String username) {
        if (Boolean.TRUE.equals(lote.getOculto()) && !ehMaster(username)) {
            throw new ServiceValidationException("Lote não encontrado.", HttpStatus.NOT_FOUND);
        }
    }

    private boolean ehMaster(String username) {
        return masterUsername != null && masterUsername.equalsIgnoreCase(username);
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
        if (!t.equals(TIPO_SEMANAL) && !t.equals(TIPO_MENSAL)) {
            throw new ServiceValidationException("Tipo inválido (use SEMANAL ou MENSAL).");
        }
        return t;
    }

    /**
     * Natureza da folha mensal, escolhida no envio: a prévia abre o mês para conferência, a
     * definitiva o fecha. A semanal não tem essa distinção — é cumulativa — e por isso recusa a
     * categoria em vez de ignorá-la em silêncio.
     */
    private String normalizeCategoria(String categoria, String tipo) {
        String c = categoria == null ? "" : categoria.trim().toUpperCase(Locale.ROOT);
        if (!TIPO_MENSAL.equals(tipo)) {
            if (!c.isEmpty()) {
                throw new ServiceValidationException("Folha semanal não é prévia nem definitiva.");
            }
            return null;
        }
        if (c.isEmpty()) {
            throw new ServiceValidationException("Informe se a folha mensal é prévia ou definitiva.");
        }
        if (!c.equals(PontoLote.CATEGORIA_PREVIA) && !c.equals(PontoLote.CATEGORIA_DEFINITIVA)) {
            throw new ServiceValidationException("Categoria inválida (use PREVIA ou DEFINITIVA).");
        }
        return c;
    }

    private LocalDate parseData(String raw, String campo) {
        try {
            return LocalDate.parse(raw.trim(), ISO);
        } catch (Exception e) {
            throw new ServiceValidationException("Data inválida em " + campo + " (use AAAA-MM-DD).");
        }
    }
}

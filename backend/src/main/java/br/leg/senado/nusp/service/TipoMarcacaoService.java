package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.PontoDiaMarcacao;
import br.leg.senado.nusp.entity.PontoPessoaMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.entity.PontoTipoMarcacaoExclusaoLog;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.PontoDiaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoPessoaMarcacaoRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoExclusaoLogRepository;
import br.leg.senado.nusp.repository.PontoTipoMarcacaoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static br.leg.senado.nusp.service.NativeQueryUtils.asItem;
import static br.leg.senado.nusp.service.NativeQueryUtils.asList;

/**
 * Catálogo de tipos de ocorrência do ponto — Feriado, Férias, Atestado e o que
 * mais o serviço precisar nomear.
 *
 * <p><b>Quem mexe.</b> Ler o catálogo é de qualquer admin (é o que alimenta os
 * chips do modal de ocorrências). Criar e excluir é <b>só do admin master</b>, a
 * mesma permissão que exclui publicações de folha; esconder o botão não é
 * segurança — quem chamar o endpoint mesmo assim leva 403.
 *
 * <p><b>O que um tipo faz.</b> Nada no cálculo do saldo. O efeito é uniforme:
 * a célula daquele dia passa a exibir o nome do tipo na grade e no XLSX, e o dia
 * deixa de aceitar solicitação de folga, com o nome do tipo como motivo.
 *
 * <p><b>Unicidade.</b> Nome e badge são únicos em TODO o catálogo — entre
 * escopos, inclusive — pela forma normalizada (maiúsculas, sem acentos, espaços
 * colapsados): "Férias", "ferias" e "  FERIAS " são o mesmo tipo. O texto
 * original é preservado e exibido como foi digitado.
 *
 * <p><b>Excluir é irreversível.</b> O tipo leva junto todas as marcações feitas
 * com ele — o preview conta antes, a transação reconta e apaga, e a trilha
 * ({@link PontoTipoMarcacaoExclusaoLog}) guarda o snapshot. Recriar depois um
 * tipo de mesmo nome não religa nada: as marcações já não existem.
 */
@Service
@RequiredArgsConstructor
public class TipoMarcacaoService {

    private static final Logger log = LoggerFactory.getLogger(TipoMarcacaoService.class);

    /** Tetos de exibição: o nome cabe na célula da grade; a badge, sob o número do dia. */
    private static final int MAX_NOME = 20;
    private static final int MAX_BADGE = 3;

    /**
     * O texto que a grade usa para a folga aprovada do banco de horas. A planilha conta as folgas do
     * mês procurando esse texto exato nas células, então um tipo com o mesmo nome seria contado como
     * folga de quem só tinha uma ocorrência marcada.
     */
    private static final String NOME_RESERVADO = normalizar(GradeRetificacaoService.TEXTO_BANCO_DE_HORAS);

    private final PontoTipoMarcacaoRepository tipoRepo;
    private final PontoDiaMarcacaoRepository diaRepo;
    private final PontoPessoaMarcacaoRepository pessoaRepo;
    private final PontoTipoMarcacaoExclusaoLogRepository trilhaRepo;
    private final PessoaCadastroLookup pessoaCadastro;
    private final ObjectMapper objectMapper;

    /** O ÚNICO admin que cadastra e exclui tipos — a mesma propriedade que autoriza excluir folhas. */
    @Value("${app.admin.master-username}")
    private String masterUsername;

    // ══════════════════════════════════════════════════════════════
    // Permissão
    // ══════════════════════════════════════════════════════════════

    /** O usuário é o master? Ler o catálogo não passa por aqui — só escrever. */
    public boolean podeConfigurar(String username) {
        return masterUsername != null && masterUsername.equalsIgnoreCase(username);
    }

    /** 403 "forbidden" para todo não-master, antes de ler ou escrever qualquer coisa. */
    private void requireMaster(String callerUsername) {
        if (!podeConfigurar(callerUsername)) {
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Leitura — qualquer admin
    // ══════════════════════════════════════════════════════════════

    /**
     * Tipos do catálogo, ordenados pelo nome em pt-BR. Sem {@code escopo}, vêm
     * todos — o modal de ocorrências carrega o catálogo inteiro uma vez e troca
     * de chips ao trocar de funcionário sem voltar ao servidor.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listar(String escopo) {
        List<PontoTipoMarcacao> tipos = escopo == null || escopo.isBlank()
                ? tipoRepo.findAll()
                : tipoRepo.findByEscopo(escopoValido(escopo));

        List<Map<String, Object>> itens = new ArrayList<>();
        tipos.stream()
                .sorted((a, b) -> NativeQueryUtils.ORDEM_TEXTO_PT_BR.compare(a.getNome(), b.getNome()))
                .forEach(t -> itens.add(resumoDoTipo(t)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tipos", itens);
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Cadastro — master, tudo ou nada
    // ══════════════════════════════════════════════════════════════

    /**
     * Cadastra os tipos de {@code {"tipos": [{nome, badge, escopo}, ...]}} numa
     * transação só: um nome ou uma badge já em uso derruba o lote inteiro,
     * nomeando o conflito — o admin não fica com metade do que pediu.
     */
    @Transactional
    public Map<String, Object> criar(Map<String, Object> body, String callerUsername, String callerId) {
        requireMaster(callerUsername);

        List<Novo> novos = new ArrayList<>();
        Set<String> nomesDoLote = new LinkedHashSet<>();
        Set<String> badgesDoLote = new LinkedHashSet<>();
        for (Object it : asList(body == null ? null : body.get("tipos"), "tipos")) {
            Novo novo = validar(asItem(it, "tipos"));
            if (!nomesDoLote.add(novo.nomeNorm())) {
                throw new ServiceValidationException("O nome \"" + novo.nome() + "\" está repetido na lista.");
            }
            if (!badgesDoLote.add(novo.badgeNorm())) {
                throw new ServiceValidationException("A badge \"" + novo.badge() + "\" está repetida na lista.");
            }
            novos.add(novo);
        }
        if (novos.isEmpty()) {
            throw new ServiceValidationException("Informe ao menos um tipo em 'tipos'.");
        }

        // Conflito com o que já existe, apontando o campo e o texto exatos.
        for (PontoTipoMarcacao existente : tipoRepo.findByNomeNormInOrBadgeNormIn(nomesDoLote, badgesDoLote)) {
            if (nomesDoLote.contains(existente.getNomeNorm())) {
                throw new ServiceValidationException(
                        "Já existe um tipo com o nome \"" + existente.getNome() + "\".");
            }
            throw new ServiceValidationException(
                    "Já existe um tipo com a badge \"" + existente.getBadge() + "\".");
        }

        List<PontoTipoMarcacao> entidades = new ArrayList<>();
        for (Novo novo : novos) {
            PontoTipoMarcacao t = new PontoTipoMarcacao();
            t.setNome(novo.nome());
            t.setNomeNorm(novo.nomeNorm());
            t.setBadge(novo.badge());
            t.setBadgeNorm(novo.badgeNorm());
            t.setEscopo(novo.escopo());
            t.setCriadoPorId(callerId);
            entidades.add(t);
        }
        try {
            tipoRepo.saveAllAndFlush(entidades);   // o flush força a unicidade a decidir agora
        } catch (DataIntegrityViolationException e) {
            // Corrida entre dois cadastros do mesmo nome/badge: a constraint é a autoridade final.
            throw new ServiceValidationException(
                    "Nome ou badge já cadastrado por outra operação. Confira o catálogo e tente novamente.");
        }

        List<Map<String, Object>> criados = new ArrayList<>();
        for (PontoTipoMarcacao t : entidades) criados.add(resumoDoTipo(t));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("criados", criados);
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // Exclusão — master, com preview e trilha
    // ══════════════════════════════════════════════════════════════

    /**
     * O que a exclusão do tipo vai apagar, contado no banco. É CONSULTIVO: entre
     * o preview e o DELETE alguém pode marcar mais dias — a verdade é a da
     * transação de exclusão, que refaz o levantamento sob o lock.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewExclusao(String tipoId, String callerUsername) {
        requireMaster(callerUsername);
        PontoTipoMarcacao tipo = buscar(tipoId);
        return relatorio(tipo, levantar(tipo));
    }

    /**
     * Exclui o tipo e as marcações feitas com ele, numa transação só: o tipo vem
     * TRAVADO, o levantamento é refeito (o preview não decide nada), as
     * marcações saem antes do tipo — a FK não tem cascade — e a trilha é gravada
     * com as contagens reais antes do commit.
     */
    @Transactional
    public Map<String, Object> excluir(String tipoId, String callerUsername, String callerId) {
        requireMaster(callerUsername);
        PontoTipoMarcacao tipo = tipoRepo.lockPorId(tipoId)
                .orElseThrow(() -> new ServiceValidationException("Tipo de ocorrência não encontrado.",
                        HttpStatus.NOT_FOUND));

        Marcacoes marcacoes = levantar(tipo);
        // ⚠️ As marcações saem ANTES do tipo, e com flush: a FK não tem cascade, e apagar o tipo
        // primeiro morreria em ORA-02292.
        if (!marcacoes.globais().isEmpty()) {
            diaRepo.deleteAll(marcacoes.globais());
            diaRepo.flush();
        }
        if (!marcacoes.pessoais().isEmpty()) {
            pessoaRepo.deleteAll(marcacoes.pessoais());
            pessoaRepo.flush();
        }
        tipoRepo.delete(tipo);

        Map<String, Object> resumo = relatorio(tipo, marcacoes);
        gravarTrilha(tipo, callerId, resumo);

        log.info("Exclusão de tipo de ocorrência {} ({}) por {}: {} marcação(ões)",
                tipo.getNome(), tipo.getEscopo(), callerId, marcacoes.total());
        return resumo;
    }

    private void gravarTrilha(PontoTipoMarcacao tipo, String callerId, Map<String, Object> resumo) {
        PontoTipoMarcacaoExclusaoLog trilha = new PontoTipoMarcacaoExclusaoLog();
        trilha.setTipoId(tipo.getId());
        trilha.setNome(tipo.getNome());
        trilha.setBadge(tipo.getBadge());
        trilha.setEscopo(tipo.getEscopo());
        trilha.setExcluidoPorId(callerId);
        trilha.setExcluidoEm(LocalDateTime.now());
        trilha.setResumo(json(resumo));
        trilhaRepo.save(trilha);
    }

    /**
     * Falhar aqui derruba a transação inteira, de propósito: exclusão sem trilha
     * é exatamente o estado que a trilha existe para nunca produzir.
     */
    private String json(Map<String, Object> resumo) {
        try {
            return objectMapper.writeValueAsString(resumo);
        } catch (JsonProcessingException e) {
            throw new ServiceValidationException("Não foi possível registrar a trilha da exclusão.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Levantamento e relatório (o mesmo para o preview e para a exclusão)
    // ══════════════════════════════════════════════════════════════

    /** As marcações daquele tipo — só a tabela do escopo dele pode tê-las. */
    private Marcacoes levantar(PontoTipoMarcacao tipo) {
        boolean global = PontoTipoMarcacao.ESCOPO_GLOBAL.equals(tipo.getEscopo());
        return new Marcacoes(
                global ? diaRepo.findByTipoIdOrderByData(tipo.getId()) : List.of(),
                global ? List.of() : pessoaRepo.findByTipoIdOrderByData(tipo.getId()));
    }

    /** O que morre: o tipo, quantas marcações e — quando individual — quem é afetado. */
    private Map<String, Object> relatorio(PontoTipoMarcacao tipo, Marcacoes marcacoes) {
        List<String> pessoas = marcacoes.pessoais().stream()
                .map(m -> pessoaCadastro.nome(m.getPessoaId(), m.getPessoaTipo()))
                .distinct()
                .sorted(NativeQueryUtils.ORDEM_TEXTO_PT_BR)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tipo", resumoDoTipo(tipo));
        out.put("marcacoes", marcacoes.total());
        out.put("pessoas_afetadas", pessoas.size());
        out.put("pessoas", pessoas);
        return out;
    }

    private Map<String, Object> resumoDoTipo(PontoTipoMarcacao t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("nome", t.getNome());
        m.put("badge", t.getBadge());
        m.put("escopo", t.getEscopo());
        return m;
    }

    private PontoTipoMarcacao buscar(String tipoId) {
        return tipoRepo.findById(tipoId == null ? "" : tipoId)
                .orElseThrow(() -> new ServiceValidationException("Tipo de ocorrência não encontrado.",
                        HttpStatus.NOT_FOUND));
    }

    // ══════════════════════════════════════════════════════════════
    // Validação e normalização
    // ══════════════════════════════════════════════════════════════

    private Novo validar(Map<String, Object> item) {
        String nome = texto(item.get("nome"));
        if (nome.isEmpty()) throw new ServiceValidationException("Informe o nome do tipo.");
        if (nome.length() > MAX_NOME) {
            throw new ServiceValidationException("O nome do tipo deve ter no máximo "
                    + MAX_NOME + " caracteres.");
        }
        String badge = texto(item.get("badge"));
        if (badge.isEmpty()) throw new ServiceValidationException("Informe a badge do tipo.");
        if (badge.length() > MAX_BADGE) {
            throw new ServiceValidationException("A badge deve ter no máximo "
                    + MAX_BADGE + " caracteres.");
        }
        String nomeNorm = normalizar(nome);
        String badgeNorm = normalizar(badge);
        if (nomeNorm.isEmpty() || badgeNorm.isEmpty()) {
            throw new ServiceValidationException("Nome e badge precisam ter ao menos uma letra ou número.");
        }
        // Há caracteres que crescem ao virar maiúscula ("ß" → "SS"), e é a forma normalizada que o
        // banco guarda: sem esta guarda, um nome dentro do teto estouraria a coluna já no INSERT.
        if (nomeNorm.length() > MAX_NOME || badgeNorm.length() > MAX_BADGE) {
            throw new ServiceValidationException("Nome ou badge com caracteres que não cabem no limite "
                    + "(" + MAX_NOME + " e " + MAX_BADGE + " caracteres). Use uma grafia mais curta.");
        }
        if (NOME_RESERVADO.equals(nomeNorm)) {
            throw new ServiceValidationException("O nome \"" + nome + "\" é usado pelo sistema para marcar "
                    + "as folgas do banco de horas e não pode nomear um tipo de ocorrência.");
        }
        return new Novo(nome, nomeNorm, badge, badgeNorm, escopoValido(texto(item.get("escopo"))));
    }

    /** Espaços colapsados no texto exibido — "P.  Facultativo" e "P. Facultativo" são o mesmo nome. */
    private static String texto(Object v) {
        return v == null ? "" : v.toString().strip().replaceAll("\\s+", " ");
    }

    private static String escopoValido(String escopo) {
        String e = escopo == null ? "" : escopo.strip().toUpperCase(Locale.ROOT);
        if (!PontoTipoMarcacao.ESCOPO_GLOBAL.equals(e) && !PontoTipoMarcacao.ESCOPO_INDIVIDUAL.equals(e)) {
            throw new ServiceValidationException("Escopo inválido: " + escopo + ". Use GLOBAL ou INDIVIDUAL.");
        }
        return e;
    }

    /**
     * A forma que decide se dois nomes (ou duas badges) são o mesmo: sem
     * acentos, em maiúsculas, com os espaços das pontas fora e os do meio
     * colapsados.
     */
    public static String normalizar(String texto) {
        if (texto == null) return "";
        String semAcentos = Normalizer.normalize(texto.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcentos.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Um tipo pedido no cadastro, já validado e normalizado. */
    private record Novo(String nome, String nomeNorm, String badge, String badgeNorm, String escopo) {}

    /** As marcações de um tipo — um dos dois lados está sempre vazio, conforme o escopo. */
    private record Marcacoes(List<PontoDiaMarcacao> globais, List<PontoPessoaMarcacao> pessoais) {

        int total() {
            return globais.size() + pessoais.size();
        }
    }
}

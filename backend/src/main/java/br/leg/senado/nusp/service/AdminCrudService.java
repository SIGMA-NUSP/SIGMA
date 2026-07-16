package br.leg.senado.nusp.service;

import br.leg.senado.nusp.entity.*;
import br.leg.senado.nusp.enums.TipoWidget;
import br.leg.senado.nusp.exception.ServiceValidationException;
import br.leg.senado.nusp.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Supplier;

/**
 * Lógica de negócio do Admin CRUD — equivale a api/views/admin.py +
 * api/db/pessoa.py + api/db/form_edit.py do Python.
 */
@Service
@RequiredArgsConstructor
public class AdminCrudService {

    private static final Logger log = LoggerFactory.getLogger(AdminCrudService.class);

    private final OperadorRepository operadorRepo;
    private final AdministradorRepository administradorRepo;
    private final TecnicoRepository tecnicoRepo;
    private final SalaRepository salaRepo;
    private final ComissaoRepository comissaoRepo;
    private final ChecklistItemTipoRepository itemTipoRepo;
    private final ChecklistSalaConfigRepository salaConfigRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.files.dir}")
    private String filesDir;

    @Value("${app.files.url-prefix}")
    private String filesUrlPrefix;

    @Value("${app.files.operadores-dirname}")
    private String operadoresDirname;

    @Value("${app.files.tecnicos-dirname}")
    private String tecnicosDirname;

    @Value("${app.files.administradores-dirname}")
    private String administradoresDirname;

    @Value("${app.admin.master-username}")
    private String masterUsername;

    // ══ Criação de Operador ═════════════════════════════════════

    @Transactional
    public Map<String, Object> criarOperador(String nomeCompleto, String nomeExibicao,
                                              String email, String username, String senha,
                                              MultipartFile foto, boolean plenarioPrincipal,
                                              boolean plenarioPrincipalFixo) {
        exigirCampos("nome_completo", nomeCompleto, "nome_exibicao", nomeExibicao,
                "email", email, "username", username, "senha", senha);

        // Mesma invariante de atualizarOperador/togglePlenarioPrincipalFixo: o operador não pode
        // NASCER no estado incoerente que os outros dois caminhos proíbem (a guarda precede a
        // gravação da foto e o INSERT — nada é criado).
        validarFixoExigeApto(plenarioPrincipal, plenarioPrincipalFixo);

        Credenciais cred = normalizarEValidarNovoUsuario(email, username);

        String fotoUrl = "";
        if (foto != null && !foto.isEmpty()) {
            fotoUrl = salvarFoto(cred.username(), foto, operadoresDirname);
            // Arquivo no disco antes do INSERT: se a transação reverter, ele é removido (F12).
            sincronizarFotosComTransacao(fotoUrl, null);
        }

        Operador novo = new Operador();
        novo.setNomeCompleto(nomeCompleto.strip());
        novo.setNomeExibicao(nomeExibicao.strip());
        novo.setEmail(cred.email());
        novo.setUsername(cred.username());
        novo.setPasswordHash(passwordEncoder.encode(senha));
        novo.setFotoUrl(fotoUrl.isEmpty() ? null : fotoUrl);
        novo.setPlenarioPrincipal(plenarioPrincipal);
        novo.setPlenarioPrincipalFixo(plenarioPrincipalFixo);
        Operador op = salvarNovoComFoto(() -> operadorRepo.save(novo), fotoUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", op.getId());
        result.put("nome_completo", op.getNomeCompleto());
        result.put("nome_exibicao", op.getNomeExibicao());
        result.put("email", op.getEmail());
        result.put("username", op.getUsername());
        result.put("foto_url", op.getFotoUrl() != null ? op.getFotoUrl() : "");
        return result;
    }

    private String salvarFoto(String username, MultipartFile foto, String dirname) {
        String ext = extractExtension(foto);
        long ts = System.currentTimeMillis();
        String filename = username + "_" + ts + "." + ext;

        Path base = Paths.get(filesDir).toAbsolutePath().normalize();
        Path saveDir = base.resolve(dirname).normalize();
        Path destino = saveDir.resolve(filename).normalize();
        // Paridade com apagarFotoFisica: o WRITE também confere contenção. A extensão já vem da
        // whitelist; o username vem do banco (e só é validado pelo USERNAME_PATTERN na criação).
        // A conferência é contra o diretório de destino (⊂ base): um "../x" escaparia da pasta de
        // fotos e ainda assim continuaria dentro de filesDir.
        if (!destino.startsWith(saveDir)) {
            log.error("Destino de foto fora do diretório de arquivos: {}", destino);
            throw new ServiceValidationException("Erro ao salvar foto",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        try {
            Files.createDirectories(saveDir);
            foto.transferTo(destino.toFile());
        } catch (IOException e) {
            log.error("Erro ao salvar foto: {}", e.getMessage());
            throw new ServiceValidationException("Erro ao salvar foto",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return filesUrlPrefix.replaceAll("/$", "") + "/" + dirname + "/" + filename;
    }

    /**
     * Apaga o arquivo físico correspondente a uma fotoUrl (ex.: "/files/operadores/x.jpg").
     * Best-effort: nunca lança — apenas loga em caso de erro. Protegido contra path traversal.
     */
    private void apagarFotoFisica(String fotoUrl) {
        if (isBlank(fotoUrl)) return;
        try {
            String prefix = filesUrlPrefix.replaceAll("/$", "");
            String rel = fotoUrl.startsWith(prefix) ? fotoUrl.substring(prefix.length()) : fotoUrl;
            rel = rel.replaceFirst("^/+", "");
            Path base = Paths.get(filesDir).toAbsolutePath().normalize();
            Path alvo = base.resolve(rel).normalize();
            if (!alvo.startsWith(base)) {
                log.warn("Ignorando remoção de foto fora do diretório de arquivos: {}", fotoUrl);
                return;
            }
            Files.deleteIfExists(alvo);
        } catch (Exception e) {
            log.warn("Não foi possível apagar a foto antiga ({}): {}", fotoUrl, e.getMessage());
        }
    }

    /** Únicas extensões que podem ser gravadas (o diretório é servido publicamente em /files/**). */
    private static final Set<String> EXTENSOES_IMAGEM = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /**
     * Extensão do arquivo a gravar — sempre dentro da whitelist. A extensão do nome enviado pelo
     * cliente só vale se for de imagem; caso contrário decide o {@code contentType}; se nenhum dos
     * dois mapear para um dos 5 formatos, o upload é rejeitado.
     *
     * <p>Sem a whitelist, um {@code .html}/{@code .svg} disfarçado de foto era gravado com a
     * extensão do cliente e servido no domínio da aplicação ({@code /files/**} é {@code permitAll}
     * no SecurityConfig e isento do filtro JWT) — XSS armazenado.
     */
    private String extractExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.') + 1).strip().toLowerCase();
            if (EXTENSOES_IMAGEM.contains(ext)) return ext;
        }
        String ct = file.getContentType();
        if (ct != null) {
            String tipo = ct.toLowerCase();
            if (tipo.contains("jpeg") || tipo.contains("jpg")) return "jpg";
            if (tipo.contains("png")) return "png";
            if (tipo.contains("gif")) return "gif";
            if (tipo.contains("webp")) return "webp";
        }
        throw new ServiceValidationException("FORMATO_INVALIDO", HttpStatus.BAD_REQUEST,
                Map.of("message", "Formato de imagem não suportado. Envie JPG, PNG, GIF ou WEBP."));
    }

    // Rejeita caracteres que permitem path traversal ou confundem storage/URL.
    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9._-]{3,64}$");

    private static void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ServiceValidationException("invalid_username", HttpStatus.BAD_REQUEST,
                    Map.of("message",
                            "Username deve conter apenas letras minúsculas, números, ponto, traço ou underscore (3 a 64 caracteres)."));
        }
    }

    /**
     * Garante unicidade global de username e email entre as três tabelas de
     * usuários (PES_OPERADOR, PES_ADMINISTRADOR, PES_TECNICO). A unicidade
     * dentro de cada tabela é garantida pelo schema; entre tabelas, é validada
     * aqui na aplicação.
     */
    private void verificarConflitoUsernameEmail(String email, String username) {
        boolean emailExists = operadorRepo.findByEmail(email).isPresent()
                || administradorRepo.findByEmail(email).isPresent()
                || tecnicoRepo.findByEmail(email).isPresent();
        boolean usernameExists = operadorRepo.findByUsername(username).isPresent()
                || administradorRepo.findByUsername(username).isPresent()
                || tecnicoRepo.findByUsername(username).isPresent();
        if (emailExists || usernameExists) {
            String msg;
            if (emailExists && usernameExists) msg = "E-mail e usuário já cadastrados";
            else if (emailExists) msg = "E-mail já cadastrado";
            else msg = "Nome de usuário já cadastrado";
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", msg));
        }
    }

    /** Coleta os campos obrigatórios ausentes (pares nome→valor) e falha com invalid_payload. */
    private void exigirCampos(String... nomeValor) {
        List<String> faltantes = new ArrayList<>();
        for (int i = 0; i < nomeValor.length; i += 2)
            if (isBlank(nomeValor[i + 1])) faltantes.add(nomeValor[i]);
        if (!faltantes.isEmpty())
            throw new ServiceValidationException("invalid_payload", HttpStatus.BAD_REQUEST,
                    Map.of("missing", String.join(", ", faltantes)));
    }

    private record Credenciais(String email, String username) {}

    /** Normaliza (trim+lowercase) e-mail e username, valida o formato do username e o conflito global. */
    private Credenciais normalizarEValidarNovoUsuario(String email, String username) {
        email = email.strip().toLowerCase();
        username = username.strip().toLowerCase();
        validateUsername(username);
        verificarConflitoUsernameEmail(email, username);
        return new Credenciais(email, username);
    }

    // ══ Criação de Administrador ════════════════════════════════

    @Transactional
    public Map<String, Object> criarAdministrador(String nomeCompleto, String email,
                                                   String username, String senha,
                                                   String callerUsername) {
        requireMaster(callerUsername);

        exigirCampos("nome_completo", nomeCompleto, "email", email,
                "username", username, "senha", senha);

        Credenciais cred = normalizarEValidarNovoUsuario(email, username);

        Administrador admin = new Administrador();
        admin.setNomeCompleto(nomeCompleto.strip());
        admin.setEmail(cred.email());
        admin.setUsername(cred.username());
        admin.setPasswordHash(passwordEncoder.encode(senha));
        admin.setSenhaProvisoria(true);
        admin = administradorRepo.save(admin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", admin.getId());
        result.put("nome_completo", admin.getNomeCompleto());
        result.put("email", admin.getEmail());
        result.put("username", admin.getUsername());
        return result;
    }

    // ══ Criação de Técnico ══════════════════════════════════════

    @Transactional
    public Map<String, Object> criarTecnico(String nomeCompleto, String email,
                                             String username, String senha,
                                             MultipartFile foto) {
        exigirCampos("nome_completo", nomeCompleto, "email", email,
                "username", username, "senha", senha);

        Credenciais cred = normalizarEValidarNovoUsuario(email, username);

        String fotoUrl = "";
        if (foto != null && !foto.isEmpty()) {
            fotoUrl = salvarFoto(cred.username(), foto, tecnicosDirname);
            sincronizarFotosComTransacao(fotoUrl, null);   // idem criarOperador (F12)
        }

        Tecnico novo = new Tecnico();
        novo.setNomeCompleto(nomeCompleto.strip());
        novo.setEmail(cred.email());
        novo.setUsername(cred.username());
        novo.setPasswordHash(passwordEncoder.encode(senha));
        novo.setFotoUrl(fotoUrl.isEmpty() ? null : fotoUrl);
        Tecnico tec = salvarNovoComFoto(() -> tecnicoRepo.save(novo), fotoUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", tec.getId());
        result.put("nome_completo", tec.getNomeCompleto());
        result.put("email", tec.getEmail());
        result.put("username", tec.getUsername());
        result.put("foto_url", tec.getFotoUrl() != null ? tec.getFotoUrl() : "");
        return result;
    }

    // ══ Form Edit — Listar ══════════════════════════════════════

    public Map<String, Object> listFormEditItems(String entidade) {
        List<Map<String, Object>> items = switch (entidade) {
            case "salas" -> listSalas();
            case "comissoes" -> listComissoes();
            default -> throw new ServiceValidationException(
                    "ENTIDADE_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Entidade inválida: '" + entidade + "'"));
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", entidade);
        result.put("items", items);
        return result;
    }

    private List<Map<String, Object>> listSalas() {
        return salaRepo.findAllOrdered().stream()
                .map(s -> formEditItemToMap(s.getId(), s.getNome(), s.getAtivo(), s.getOrdem()))
                .toList();
    }

    private List<Map<String, Object>> listComissoes() {
        return comissaoRepo.findAllOrdered().stream()
                .map(c -> formEditItemToMap(c.getId(), c.getNome(), c.getAtivo(), c.getOrdem()))
                .toList();
    }

    private Map<String, Object> formEditItemToMap(Object id, String nome, Boolean ativo, Integer ordem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("nome", nome);
        m.put("ordem", Boolean.TRUE.equals(ativo) ? ordem : null);
        m.put("ativo", ativo);
        return m;
    }

    // ══ Form Edit — Salvar ══════════════════════════════════════

    @Transactional
    public Map<String, Object> saveFormEditItems(String entidade, List<Map<String, Object>> items,
                                                  String userId) {
        if (!"salas".equals(entidade) && !"comissoes".equals(entidade)) {
            throw new ServiceValidationException("ENTIDADE_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Entidade inválida: '" + entidade + "'"));
        }

        int ordemCounter = 1;
        int created = 0, updated = 0;

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            Map<String, Object> item = items.get(idx);
            Object rawId = item.get("id");
            String nome = item.get("nome") != null ? item.get("nome").toString().strip() : "";
            boolean ativo = Boolean.TRUE.equals(item.get("ativo"));

            if (nome.isEmpty()) {
                throw new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                        Map.of("message", "Nome não pode ser vazio (item na posição " + idx + ")."));
            }

            Integer ordem = ativo ? ordemCounter++ : null;

            boolean criou = "salas".equals(entidade)
                    ? upsertSala(rawId, nome, ativo, ordem, idx)
                    : upsertComissao(rawId, nome, ativo, ordem, userId, idx);
            if (criou) created++;
            else updated++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", entidade);
        result.put("created", created);
        result.put("updated", updated);
        return result;
    }

    private boolean upsertSala(Object rawId, String nome, boolean ativo, Integer ordem, int idx) {
        if (rawId == null) {
            Sala s = new Sala();
            s.setNome(nome);
            s.setAtivo(ativo);
            s.setOrdem(ordem);
            salaRepo.save(s);
            return true;
        }
        int id = toInt(rawId);
        Sala s = salaRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                        Map.of("message", "Registro com id " + id + " não encontrado (posição " + idx + ").")));
        s.setNome(nome);
        s.setAtivo(ativo);
        s.setOrdem(ordem);
        salaRepo.save(s);
        return false;
    }

    private boolean upsertComissao(Object rawId, String nome, boolean ativo, Integer ordem, String userId, int idx) {
        if (rawId == null) {
            Comissao c = new Comissao();
            c.setNome(nome);
            c.setAtivo(ativo);
            c.setOrdem(ordem);
            c.setCriadoPor(userId);
            c.setAtualizadoPor(userId);
            comissaoRepo.save(c);
            return true;
        }
        long id = toLong(rawId);
        Comissao c = comissaoRepo.findById(id).orElseThrow(() ->
                new ServiceValidationException("VALIDACAO", HttpStatus.BAD_REQUEST,
                        Map.of("message", "Registro com id " + id + " não encontrado (posição " + idx + ").")));
        c.setNome(nome);
        c.setAtivo(ativo);
        c.setOrdem(ordem);
        c.setAtualizadoPor(userId);
        comissaoRepo.save(c);
        return false;
    }

    // ══ Sala Config — Listar ════════════════════════════════════

    public Map<String, Object> listSalaConfigItems(int salaId) {
        validateSalaId(salaId);

        List<Object[]> rows = salaConfigRepo.findConfigItemsBySalaId(salaId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) row[0]).intValue());
            m.put("item_tipo_id", ((Number) row[1]).intValue());
            m.put("nome", row[2] != null ? row[2].toString() : "");
            m.put("tipo_widget", row[3] != null ? row[3].toString() : "radio");
            m.put("ordem", row[4] != null ? ((Number) row[4]).intValue() : null);
            m.put("ativo", row[5] != null && ((Number) row[5]).intValue() == 1);
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sala_id", salaId);
        result.put("items", items);
        return result;
    }

    // ══ Sala Config — Salvar ════════════════════════════════════

    @Transactional
    public Map<String, Object> saveSalaConfigItems(int salaId, List<Map<String, Object>> items) {
        validateSalaId(salaId);
        int[] counts = doSaveSalaConfig(salaId, items);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sala_id", salaId);
        result.put("created", counts[0]);
        result.put("updated", counts[1]);
        return result;
    }

    // ══ Sala Config — Aplicar a Todas ═══════════════════════════

    @Transactional
    public Map<String, Object> applySalaConfigToAll(int sourceSalaId, List<Map<String, Object>> items) {
        if (sourceSalaId <= 0) {
            throw new ServiceValidationException("LOCAL_ID_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "O ID do local de origem deve ser um número válido."));
        }

        // Tudo-ou-nada honesto: a primeira falha PROPAGA. O catch que só logava e seguia o laço
        // escondia a falha de dois jeitos, ambos ruins: se a exceção vinha de um método CRUD do
        // repositório (transacional), ela já marcava a transação como rollback-only e o commit —
        // feito por este mesmo método — estourava UnexpectedRollbackException, dando ao cliente um
        // 500 genérico e descartando TUDO, inclusive as salas que tinham dado certo; se vinha de um
        // query method derivado (sem atributo transacional), o commit passava e o cliente recebia
        // 200 com uma contagem parcial, sem saber quais salas ficaram de fora.
        List<Sala> salasAtivas = salaRepo.findAtivasOrdenadas();
        int count = 0;
        for (Sala sala : salasAtivas) {
            // Aplicar somente nos plenários numerados (ex: "Plenário 02")
            if (sala.getNome() == null || !sala.getNome().matches("(?i)plenário \\d+")) continue;
            doSaveSalaConfig(sala.getId(), items);
            count++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_sala_id", sourceSalaId);
        result.put("salas_atualizadas", count);
        return result;
    }

    // ══ Lógica compartilhada de sala config ═════════════════════

    /**
     * Lógica interna de save sala config, usada por saveSalaConfigItems e applySalaConfigToAll.
     * Retorna [created, updated].
     */
    private int[] doSaveSalaConfig(int salaId, List<Map<String, Object>> items) {
        // 1. Filtrar apenas itens ativos com nome
        List<ItemConfigLimpo> cleaned = limparItensConfig(items);

        // 2. Desativar todos os itens existentes desta sala
        salaConfigRepo.deactivateAllBySalaId(salaId);

        int created = 0, updated = 0;

        // 3. Para cada item ativo: find_or_create item_tipo + upsert config
        for (ItemConfigLimpo item : cleaned) {
            if (upsertItemConfig(salaId, item)) created++;
            else updated++;
        }

        return new int[]{created, updated};
    }

    private record ItemConfigLimpo(String nome, TipoWidget widget, int ordem) {}

    /** Fase 1: normaliza e filtra (nome não-vazio + ativo), fixando widget e ordem sequencial. */
    private List<ItemConfigLimpo> limparItensConfig(List<Map<String, Object>> items) {
        int ordemCounter = 1;
        List<ItemConfigLimpo> cleaned = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String nome = item.get("nome") != null ? item.get("nome").toString().strip() : "";
            String tipoWidget = item.get("tipo_widget") != null ? item.get("tipo_widget").toString() : "radio";
            boolean ativo = item.get("ativo") == null || Boolean.TRUE.equals(item.get("ativo"));

            if (nome.isEmpty() || !ativo) continue;

            if (!"radio".equals(tipoWidget) && !"text".equals(tipoWidget)) {
                tipoWidget = "radio";
            }

            TipoWidget widget = "text".equals(tipoWidget) ? TipoWidget.TEXT : TipoWidget.RADIO;
            cleaned.add(new ItemConfigLimpo(nome, widget, ordemCounter++));
        }
        return cleaned;
    }

    /** Upsert de um item de config: find-or-create do tipo + upsert da config. true se CRIOU. */
    private boolean upsertItemConfig(int salaId, ItemConfigLimpo item) {
        String nome = item.nome();
        TipoWidget tw = item.widget();
        int ordem = item.ordem();

        // Find or create item tipo
        ChecklistItemTipo itemTipo = itemTipoRepo.findByNomeAndTipoWidget(nome, tw)
                .orElseGet(() -> {
                    ChecklistItemTipo novo = new ChecklistItemTipo();
                    novo.setNome(nome);
                    novo.setTipoWidget(tw);
                    return itemTipoRepo.save(novo);
                });

        // Upsert config
        Optional<ChecklistSalaConfig> existing =
                salaConfigRepo.findBySalaIdAndItemTipoId(salaId, itemTipo.getId());

        if (existing.isPresent()) {
            ChecklistSalaConfig config = existing.get();
            config.setAtivo(true);
            config.setOrdem(ordem);
            salaConfigRepo.save(config);
            return false;
        } else {
            ChecklistSalaConfig config = new ChecklistSalaConfig();
            config.setSalaId(salaId);
            config.setItemTipoId(itemTipo.getId());
            config.setOrdem(ordem);
            config.setAtivo(true);
            salaConfigRepo.save(config);
            return true;
        }
    }

    private Operador findOperadorOr404(String id) {
        return operadorRepo.findById(id).orElseThrow(() -> new ServiceValidationException(
                "NOT_FOUND", HttpStatus.NOT_FOUND, Map.of("message", "Operador não encontrado.")));
    }

    private Tecnico findTecnicoOr404(String id) {
        return tecnicoRepo.findById(id).orElseThrow(() -> new ServiceValidationException(
                "NOT_FOUND", HttpStatus.NOT_FOUND, Map.of("message", "Técnico não encontrado.")));
    }

    private Administrador findAdministradorOr404(String id) {
        return administradorRepo.findById(id).orElseThrow(() -> new ServiceValidationException(
                "NOT_FOUND", HttpStatus.NOT_FOUND, Map.of("message", "Administrador não encontrado.")));
    }

    // ══ Toggle Plenário Principal ═════════════════════════════════

    @Transactional
    public boolean togglePlenarioPrincipal(String operadorId) {
        Operador op = findOperadorOr404(operadorId);
        boolean novo = !Boolean.TRUE.equals(op.getPlenarioPrincipal());
        op.setPlenarioPrincipal(novo);
        // Ao desmarcar "apto", deixa de fazer sentido manter como "fixo" do PP
        if (!novo) op.setPlenarioPrincipalFixo(false);
        operadorRepo.save(op);
        return novo;
    }

    @Transactional
    public boolean togglePlenarioPrincipalFixo(String operadorId) {
        Operador op = findOperadorOr404(operadorId);
        boolean novo = !Boolean.TRUE.equals(op.getPlenarioPrincipalFixo());
        validarFixoExigeApto(Boolean.TRUE.equals(op.getPlenarioPrincipal()), novo);
        op.setPlenarioPrincipalFixo(novo);
        operadorRepo.save(op);
        return novo;
    }

    /**
     * "Fixo do Plenário Principal" só faz sentido para quem está apto ao PP — invariante única
     * dos três caminhos que escrevem os dois flags (criar, atualizar e o toggle do fixo).
     */
    private void validarFixoExigeApto(boolean plenarioPrincipal, boolean plenarioPrincipalFixo) {
        if (plenarioPrincipalFixo && !plenarioPrincipal) {
            throw new ServiceValidationException("INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Operador precisa estar apto a Plenário Principal antes de ser marcado como fixo."));
        }
    }

    // ══ Atualizar Turno ═══════════════════════════════════════════

    @Transactional
    public String setTurnoOperador(String operadorId, String turno) {
        validarTurnoObrigatorioMV(turno);
        Operador op = findOperadorOr404(operadorId);
        op.setTurno(turno);
        operadorRepo.save(op);
        return turno;
    }

    // ══ Toggle Participa Escala ═══════════════════════════════════

    @Transactional
    public boolean toggleParticipaEscala(String operadorId) {
        Operador op = findOperadorOr404(operadorId);
        boolean novo = !Boolean.TRUE.equals(op.getParticipaEscala());
        op.setParticipaEscala(novo);
        operadorRepo.save(op);
        return novo;
    }

    // ══ Alterar Senha de Operador ════════════════════════════════

    @Transactional
    public void changeOperadorPassword(String operadorId, String novaSenha) {
        Operador op = findOperadorOr404(operadorId);
        op.setPasswordHash(passwordEncoder.encode(novaSenha));
        operadorRepo.save(op);
    }

    // ══ Perfil de Operador — Buscar ══════════════════════════════

    public Map<String, Object> getOperadorPerfil(String id) {
        Operador op = findOperadorOr404(id);
        return operadorToMap(op);
    }

    // ══ Perfil de Operador — Atualizar ═══════════════════════════

    @Transactional
    public Map<String, Object> atualizarOperador(
            String id, String nomeCompleto, String nomeExibicao, String email, String turno,
            String cargaHorariaRaw, String horarioInicio, String horarioFim,
            boolean plenarioPrincipal, boolean plenarioPrincipalFixo, boolean participaEscala,
            MultipartFile foto) {

        Operador op = findOperadorOr404(id);

        exigirCampos("nome_completo", nomeCompleto, "nome_exibicao", nomeExibicao, "email", email);

        validarTurnoObrigatorioMV(turno);

        Integer cargaHoraria = parseCargaHoraria(cargaHorariaRaw);
        String horaInicio = normalizarHora(horarioInicio);
        String horaFim = normalizarHora(horarioFim);

        validarFixoExigeApto(plenarioPrincipal, plenarioPrincipalFixo);

        // E-mail: normaliza e, se mudou, valida conflito global (operador/admin/técnico)
        String novoEmail = normalizarEmailValidandoConflito(email, op.getEmail(), "operador", id);

        // Foto: só substitui se uma nova for enviada. Salva a nova primeiro
        // (se falhar, mantém a antiga) e só então apaga o arquivo anterior.
        op.setFotoUrl(substituirFoto(foto, op.getFotoUrl(), op.getUsername(), operadoresDirname));

        op.setNomeCompleto(nomeCompleto.strip());
        op.setNomeExibicao(nomeExibicao.strip());
        op.setEmail(novoEmail);
        op.setTurno(turno);
        op.setCargaHoraria(cargaHoraria);
        op.setHorarioTrabalhoInicio(horaInicio);
        op.setHorarioTrabalhoFim(horaFim);
        op.setPlenarioPrincipal(plenarioPrincipal);
        op.setPlenarioPrincipalFixo(plenarioPrincipal && plenarioPrincipalFixo);
        op.setParticipaEscala(participaEscala);
        op = operadorRepo.save(op);

        return operadorToMap(op);
    }

    private Map<String, Object> operadorToMap(Operador op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", op.getId());
        m.put("nome_completo", op.getNomeCompleto());
        m.put("nome_exibicao", op.getNomeExibicao());
        m.put("email", op.getEmail());
        m.put("username", op.getUsername());
        m.put("foto_url", op.getFotoUrl() != null ? op.getFotoUrl() : "");
        m.put("turno", op.getTurno());
        m.put("carga_horaria", op.getCargaHoraria());
        m.put("horario_trabalho_inicio", op.getHorarioTrabalhoInicio());
        m.put("horario_trabalho_fim", op.getHorarioTrabalhoFim());
        m.put("plenario_principal", Boolean.TRUE.equals(op.getPlenarioPrincipal()));
        m.put("plenario_principal_fixo", Boolean.TRUE.equals(op.getPlenarioPrincipalFixo()));
        m.put("participa_escala", Boolean.TRUE.equals(op.getParticipaEscala()));
        return m;
    }

    // ══ Perfil de Técnico — Buscar ═══════════════════════════════

    public Map<String, Object> getTecnicoPerfil(String id) {
        Tecnico tec = findTecnicoOr404(id);
        return tecnicoToMap(tec);
    }

    // ══ Perfil de Técnico — Atualizar ════════════════════════════

    @Transactional
    public Map<String, Object> atualizarTecnico(
            String id, String nomeCompleto, String email, String turno,
            String cargaHorariaRaw, String horarioInicio, String horarioFim,
            MultipartFile foto) {

        Tecnico tec = findTecnicoOr404(id);

        exigirCampos("nome_completo", nomeCompleto, "email", email);

        // Técnico: turno é OPCIONAL (pode ficar NULL)
        String turnoNorm = normalizarTurnoOpcional(turno);
        Integer cargaHoraria = parseCargaHoraria(cargaHorariaRaw);
        String horaInicio = normalizarHora(horarioInicio);
        String horaFim = normalizarHora(horarioFim);

        String novoEmail = normalizarEmailValidandoConflito(email, tec.getEmail(), "tecnico", id);

        // Foto: salva a nova primeiro e só então apaga a anterior (mesma lógica do operador)
        tec.setFotoUrl(substituirFoto(foto, tec.getFotoUrl(), tec.getUsername(), tecnicosDirname));

        tec.setNomeCompleto(nomeCompleto.strip());
        tec.setEmail(novoEmail);
        tec.setTurno(turnoNorm);
        tec.setCargaHoraria(cargaHoraria);
        tec.setHorarioTrabalhoInicio(horaInicio);
        tec.setHorarioTrabalhoFim(horaFim);
        tec = tecnicoRepo.save(tec);

        return tecnicoToMap(tec);
    }

    private Map<String, Object> tecnicoToMap(Tecnico tec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tec.getId());
        m.put("nome_completo", tec.getNomeCompleto());
        m.put("email", tec.getEmail());
        m.put("username", tec.getUsername());
        m.put("foto_url", tec.getFotoUrl() != null ? tec.getFotoUrl() : "");
        m.put("turno", tec.getTurno());
        m.put("carga_horaria", tec.getCargaHoraria());
        m.put("horario_trabalho_inicio", tec.getHorarioTrabalhoInicio());
        m.put("horario_trabalho_fim", tec.getHorarioTrabalhoFim());
        return m;
    }

    // ══ Perfil de Administrador — Buscar (somente master) ════════

    public Map<String, Object> getAdministradorPerfil(String id, String callerUsername) {
        requireMaster(callerUsername);
        Administrador adm = findAdministradorOr404(id);
        return administradorToMap(adm);
    }

    // ══ Perfil de Administrador — Atualizar (somente master) ═════

    @Transactional
    public Map<String, Object> atualizarAdministrador(
            String id, String nomeCompleto, String email, boolean servidorPublico,
            String turno, String cargaHorariaRaw, String horarioInicio, String horarioFim,
            MultipartFile foto, String callerUsername) {

        requireMaster(callerUsername);

        Administrador adm = findAdministradorOr404(id);

        exigirCampos("nome_completo", nomeCompleto, "email", email);

        // Servidor público: turno/carga/horário não se aplicam → gravados como NULL.
        // Caso contrário, valida e normaliza os três (turno do admin aceita M/V/I).
        String turnoNorm = null;
        Integer cargaHoraria = null;
        String horaInicio = null, horaFim = null;
        if (!servidorPublico) {
            turnoNorm = normalizarTurnoAdmin(turno);
            cargaHoraria = parseCargaHoraria(cargaHorariaRaw);
            horaInicio = normalizarHora(horarioInicio);
            horaFim = normalizarHora(horarioFim);
        }

        String novoEmail = normalizarEmailValidandoConflito(email, adm.getEmail(), "admin", id);

        // Foto: salva a nova primeiro e só então apaga a anterior (mesma lógica do operador/técnico)
        adm.setFotoUrl(substituirFoto(foto, adm.getFotoUrl(), adm.getUsername(), administradoresDirname));

        adm.setNomeCompleto(nomeCompleto.strip());
        adm.setEmail(novoEmail);
        adm.setServidorPublico(servidorPublico);
        adm.setTurno(turnoNorm);
        adm.setCargaHoraria(cargaHoraria);
        adm.setHorarioTrabalhoInicio(horaInicio);
        adm.setHorarioTrabalhoFim(horaFim);
        adm = administradorRepo.save(adm);

        return administradorToMap(adm);
    }

    private Map<String, Object> administradorToMap(Administrador adm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", adm.getId());
        m.put("nome_completo", adm.getNomeCompleto());
        m.put("email", adm.getEmail());
        m.put("username", adm.getUsername());
        m.put("foto_url", adm.getFotoUrl() != null ? adm.getFotoUrl() : "");
        m.put("servidor_publico", Boolean.TRUE.equals(adm.getServidorPublico()));
        m.put("turno", adm.getTurno());
        m.put("carga_horaria", adm.getCargaHoraria());
        m.put("horario_trabalho_inicio", adm.getHorarioTrabalhoInicio());
        m.put("horario_trabalho_fim", adm.getHorarioTrabalhoFim());
        return m;
    }

    /**
     * Valida unicidade de e-mail entre as três tabelas (operador/admin/técnico),
     * ignorando o próprio registro (tipo + id) que está sendo editado.
     */
    private void verificarConflitoEmail(String email, String tipoIgnorar, String idIgnorar) {
        boolean emOperador = operadorRepo.findByEmail(email)
                .filter(o -> !("operador".equals(tipoIgnorar) && o.getId().equals(idIgnorar))).isPresent();
        boolean emAdmin = administradorRepo.findByEmail(email)
                .filter(a -> !("admin".equals(tipoIgnorar) && a.getId().equals(idIgnorar))).isPresent();
        boolean emTecnico = tecnicoRepo.findByEmail(email)
                .filter(t -> !("tecnico".equals(tipoIgnorar) && t.getId().equals(idIgnorar))).isPresent();
        if (emOperador || emAdmin || emTecnico) {
            throw new ServiceValidationException("conflict", HttpStatus.CONFLICT,
                    Map.of("message", "E-mail já cadastrado para outro usuário."));
        }
    }

    /** Normaliza o e-mail (trim+lowercase) e, se mudou em relação ao atual, valida conflito global. */
    private String normalizarEmailValidandoConflito(String email, String emailAtual, String tipoIgnorar, String id) {
        String novoEmail = email.strip().toLowerCase();
        if (!novoEmail.equals(emailAtual)) verificarConflitoEmail(novoEmail, tipoIgnorar, id);
        return novoEmail;
    }

    /**
     * Se uma nova foto foi enviada, salva-a PRIMEIRO (se a gravação falhar, a antiga fica intacta)
     * e deixa a remoção da antiga para o DESFECHO da transação; senão mantém a atual.
     */
    private String substituirFoto(MultipartFile foto, String fotoUrlAtual, String username, String dirname) {
        if (foto == null || foto.isEmpty()) return fotoUrlAtual;
        String nova = salvarFoto(username, foto, dirname);  // salvar a nova PRIMEIRO
        sincronizarFotosComTransacao(nova, fotoUrlAtual);   // e só então decidir o que apagar
        return nova;
    }

    /**
     * Faz os arquivos acompanharem a transação (F12): no COMMIT some a foto ANTIGA (de fato
     * substituída); no ROLLBACK some a foto NOVA (que o banco não chegou a referenciar). Antes, a
     * antiga era apagada antes do {@code save} — um rollback restaurava no banco a URL de um arquivo
     * que já não existia, e a nova ficava órfã.
     *
     * <p>Sem transação ativa (chamada fora do proxy transacional), aplica o desfecho de commit —
     * o comportamento anterior. {@code fotoAntigaUrl} nula/vazia (caso da criação) é no-op.
     */
    private void sincronizarFotosComTransacao(String fotoNovaUrl, String fotoAntigaUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            apagarFotoFisica(fotoAntigaUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // STATUS_UNKNOWN (desfecho ambíguo) não apaga NADA: um arquivo órfão é menos danoso
                // que apagar a foto para a qual o banco talvez ainda aponte.
                if (status == STATUS_COMMITTED) apagarFotoFisica(fotoAntigaUrl);
                else if (status == STATUS_ROLLED_BACK) apagarFotoFisica(fotoNovaUrl);
            }
        });
    }

    /**
     * Persiste a entidade recém-criada; se o INSERT falhar de imediato, apaga a foto que acabou de
     * ser gravada e relança. O rollback tardio (violação de constraint só no flush/commit) é coberto
     * pela sincronização registrada em {@link #sincronizarFotosComTransacao} — as duas remoções são
     * idempotentes ({@code deleteIfExists}).
     */
    private <T> T salvarNovoComFoto(Supplier<T> save, String fotoUrl) {
        try {
            return save.get();
        } catch (RuntimeException e) {
            apagarFotoFisica(fotoUrl);
            throw e;
        }
    }

    /** null/vazio → null; senão exige formato HH:MM (00:00–23:59). */
    private String normalizarHora(String raw) {
        if (isBlank(raw)) return null;
        String h = raw.strip();
        if (!HORA_PATTERN.matcher(h).matches()) {
            throw new ServiceValidationException("HORA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Horário inválido: '" + h + "'. Use o formato HH:MM."));
        }
        return h;
    }

    /** null/vazio → null; senão exige 30 ou 40. */
    private Integer parseCargaHoraria(String raw) {
        if (isBlank(raw)) return null;
        int v;
        try {
            v = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ServiceValidationException("CARGA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Carga horária inválida."));
        }
        if (v != 30 && v != 40) {
            throw new ServiceValidationException("CARGA_INVALIDA", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Carga horária deve ser 30 ou 40."));
        }
        return v;
    }

    /** Turno obrigatório do operador: exige 'M' ou 'V' (não aceita null). */
    private String validarTurnoObrigatorioMV(String turno) {
        if (!"M".equals(turno) && !"V".equals(turno)) {
            throw new ServiceValidationException("TURNO_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "Turno deve ser 'M' (Matutino) ou 'V' (Vespertino)."));
        }
        return turno;
    }

    /** null/vazio → null; senão exige que o turno esteja em {permitidos}, com a mensagem dada. */
    private String normalizarTurno(String raw, Set<String> permitidos, String mensagemErro) {
        if (isBlank(raw)) return null;
        String t = raw.strip();
        if (!permitidos.contains(t)) {
            throw new ServiceValidationException("TURNO_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", mensagemErro));
        }
        return t;
    }

    /** null/vazio → null; senão exige 'M' ou 'V' (turno opcional — usado no técnico). */
    private String normalizarTurnoOpcional(String raw) {
        return normalizarTurno(raw, Set.of("M", "V"),
                "Turno deve ser 'M' (Matutino) ou 'V' (Vespertino).");
    }

    /** null/vazio → null; senão exige 'M', 'V' ou 'I' (turno do admin: inclui Integral). */
    private String normalizarTurnoAdmin(String raw) {
        return normalizarTurno(raw, Set.of("M", "V", "I"),
                "Turno deve ser 'M' (Matutino), 'V' (Vespertino) ou 'I' (Integral).");
    }

    /** Garante que o solicitante é o administrador master (criar/ver/editar admins). */
    private void requireMaster(String callerUsername) {
        if (!masterUsername.equalsIgnoreCase(callerUsername)) {
            throw new ServiceValidationException("forbidden", HttpStatus.FORBIDDEN);
        }
    }

    // ══ Helpers ═════════════════════════════════════════════════

    private static final java.util.regex.Pattern HORA_PATTERN =
            java.util.regex.Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private void validateSalaId(int salaId) {
        if (salaId <= 0) {
            throw new ServiceValidationException("LOCAL_ID_INVALIDO", HttpStatus.BAD_REQUEST,
                    Map.of("message", "O ID do local deve ser um número válido."));
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.strip().isEmpty();
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}

package br.leg.senado.nusp.it.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.leg.senado.nusp.entity.Administrador;
import br.leg.senado.nusp.entity.AuthSession;
import br.leg.senado.nusp.entity.Checklist;
import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.ChecklistOperador;
import br.leg.senado.nusp.entity.ChecklistResposta;
import br.leg.senado.nusp.entity.ChecklistSalaConfig;
import br.leg.senado.nusp.entity.Comissao;
import br.leg.senado.nusp.entity.EntradaOperador;
import br.leg.senado.nusp.entity.Operador;
import br.leg.senado.nusp.entity.PontoBancoSaldo;
import br.leg.senado.nusp.entity.PontoLote;
import br.leg.senado.nusp.entity.PontoLotePagina;
import br.leg.senado.nusp.entity.PontoSolicitacaoFolga;
import br.leg.senado.nusp.entity.PontoTipoMarcacao;
import br.leg.senado.nusp.entity.RegistroAnormalidade;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.entity.Tecnico;
import br.leg.senado.nusp.enums.StatusResposta;
import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import br.leg.senado.nusp.enums.TipoEvento;
import br.leg.senado.nusp.enums.Turno;
import br.leg.senado.nusp.service.NativeQueryUtils;
import br.leg.senado.nusp.service.OperacaoService;
import br.leg.senado.nusp.service.TipoMarcacaoService;
import jakarta.persistence.EntityManager;

/**
 * Fixtures mínimas reutilizáveis dos testes de integração.
 *
 * O clone NUSP_TEST vem vazio: cada teste semeia o próprio grafo e confia no
 * rollback do {@code @DataJpaTest}. Todo helper termina com {@code em.flush()}
 * — SQL nativo e {@code @Modifying} não disparam auto-flush do Hibernate, e um
 * seed pendente no persistence context daria falso verde nos casos negativos.
 */
public final class CenarioFactory {

    /**
     * Sufixo único por instância — várias colunas do schema são UNIQUE
     * (CAD_SALA.NOME, PES_OPERADOR.EMAIL/USERNAME, FRM_CHECKLIST_ITEM_TIPO
     * (NOME, TIPO_WIDGET)). O RUN_ID (pid) discrimina JVMs: se o gotcha 18
     * (uma suíte IT por vez) for violado, a colisão UNIQUE entre transações
     * abertas viraria row-lock silencioso, não falha diagnóstica.
     */
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String RUN_ID = Long.toString(ProcessHandle.current().pid(), 36);

    /** PASSWORD_HASH é NOT NULL nas 3 tabelas de pessoa; nenhum teste faz login de verdade. */
    private static final String HASH_SINTETICO = "$2b$12$hash.sintetico.nunca.usado.em.login.0123456789012345678";

    /** Único tipo de lote com natureza (prévia/definitiva); a semanal é cumulativa e não tem. */
    private static final String TIPO_LOTE_MENSAL = "MENSAL";

    /** Tetos de PNT_TIPO_MARCACAO.NOME e .BADGE — o sufixo de unicidade tem de caber neles. */
    private static final int TETO_NOME_TIPO = 20;
    private static final int TETO_BADGE_TIPO = 3;

    private CenarioFactory() {
    }

    private static String next() {
        return RUN_ID + "_" + proximoNumero();
    }

    /** O contador cru — para as colunas curtas demais para o sufixo inteiro do {@link #next()}. */
    private static int proximoNumero() {
        return SEQ.incrementAndGet();
    }

    public static Sala novaSala(EntityManager em) {
        return novaSala(em, "SALA_IT", false);
    }

    public static Sala novaSala(EntityManager em, boolean multiOperador) {
        return novaSala(em, "SALA_IT", multiOperador);
    }

    /** Sala com prefixo de nome controlado: o prefixo decide a ORDEM alfabética (ORDER BY s.NOME) sem abrir mão do UNIQUE. */
    public static Sala novaSala(EntityManager em, String nomeBase) {
        return novaSala(em, nomeBase, false);
    }

    /**
     * Sala COMUM: o id gerado nunca é o reservado à "Demais Salas"
     * ({@link OperacaoService#SALA_DEMAIS_SALAS_ID}), que o domínio trata de forma especial —
     * uma sessão nessa sala exige o nome livre no corpo da edição. Como o gerador IDENTITY de
     * CAD_SALA percorre os ids em sequência, e no clone de testes ele começa do zero, uma sala de
     * cenário acabaria recebendo o id reservado e deixaria de ser comum. Quando isso acontece, a
     * sala é descartada e o cenário fica com o id seguinte; o gerador não recua no rollback, então
     * o desvio ocorre no máximo uma vez por schema.
     */
    public static Sala novaSala(EntityManager em, String nomeBase, boolean multiOperador) {
        Sala sala = persistirSala(em, nomeBase, multiOperador);
        if (sala.getId() != null && sala.getId() == OperacaoService.SALA_DEMAIS_SALAS_ID) {
            em.remove(sala);
            em.flush();
            sala = persistirSala(em, nomeBase, multiOperador);
        }
        return sala;
    }

    private static Sala persistirSala(EntityManager em, String nomeBase, boolean multiOperador) {
        Sala sala = new Sala();
        sala.setNome(nomeBase + "_" + next());
        sala.setMultiOperador(multiOperador);
        em.persist(sala);
        em.flush();
        return sala;
    }

    public static Operador novoOperador(EntityManager em) {
        return novoOperador(em, "Operador de Integracao", "op.it");
    }

    /** Operador com nome controlado (ordenação/busca por NOME_COMPLETO); login/e-mail derivados do nome. */
    public static Operador novoOperador(EntityManager em, String nomeBase) {
        return novoOperador(em, nomeBase, slug(nomeBase));
    }

    /**
     * Operador com nome e login controlados INDEPENDENTEMENTE. Necessário quando o teste precisa de
     * um termo que exista só no e-mail (ou só no nome) — a busca do motor varre as duas colunas.
     */
    public static Operador novoOperador(EntityManager em, String nomeBase, String loginBase) {
        String n = next();
        Operador op = new Operador();
        op.setNomeCompleto(nomeBase + " " + n);
        op.setNomeExibicao("OpIT" + n);
        op.setEmail(loginBase + "." + n + "@nusp-test.invalid");
        op.setUsername(loginBase + "." + n);
        op.setPasswordHash(HASH_SINTETICO);
        em.persist(op);
        em.flush();
        return op;
    }

    /**
     * Administrador com os defaults da entidade: SERVIDOR_PUBLICO = 1 (não tem folha de
     * ponto) e SENHA_PROVISORIA = 0. Quem depende do contrário sobrescreve e dá flush.
     */
    public static Administrador novoAdministrador(EntityManager em) {
        return novoAdministrador(em, "Administrador de Integracao");
    }

    /** Administrador com nome controlado (mesma razão do {@link #novoOperador(EntityManager, String)}). */
    public static Administrador novoAdministrador(EntityManager em, String nomeBase) {
        String n = next();
        Administrador admin = new Administrador();
        admin.setNomeCompleto(nomeBase + " " + n);
        admin.setEmail("adm.it." + n + "@nusp-test.invalid");
        admin.setUsername("adm.it." + n);
        admin.setPasswordHash(HASH_SINTETICO);
        em.persist(admin);
        em.flush();
        return admin;
    }

    public static Tecnico novoTecnico(EntityManager em) {
        return novoTecnico(em, "Tecnico de Integracao");
    }

    /** Técnico com nome controlado — busca/ordenação por NOME_COMPLETO precisam de dado previsível. */
    public static Tecnico novoTecnico(EntityManager em, String nomeBase) {
        String n = next();
        Tecnico tecnico = new Tecnico();
        tecnico.setNomeCompleto(nomeBase + " " + n);
        tecnico.setEmail("tec.it." + n + "@nusp-test.invalid");
        tecnico.setUsername("tec.it." + n);
        tecnico.setPasswordHash(HASH_SINTETICO);
        em.persist(tecnico);
        em.flush();
        return tecnico;
    }

    /** "José Antônio" → "jose.antonio" (username/e-mail são UNIQUE e não aceitam acento/espaço). */
    private static String slug(String nomeBase) {
        String semAcento = NativeQueryUtils.semAcento(nomeBase).toLowerCase(Locale.ROOT);
        return semAcento.replaceAll("[^a-z0-9]+", ".");
    }

    public static ChecklistItemTipo novoItemTipo(EntityManager em) {
        ChecklistItemTipo tipo = new ChecklistItemTipo();
        tipo.setNome("ITEM_IT_" + next());
        em.persist(tipo);
        em.flush();
        return tipo;
    }

    public static ChecklistSalaConfig novaConfig(EntityManager em, Sala sala, ChecklistItemTipo tipo,
            int ordem, boolean ativo) {
        ChecklistSalaConfig config = new ChecklistSalaConfig();
        config.setSalaId(sala.getId());
        config.setItemTipoId(tipo.getId());
        config.setOrdem(ordem);
        config.setAtivo(ativo);
        em.persist(config);
        em.flush();
        return config;
    }

    public static Checklist novoChecklist(EntityManager em, Sala sala, Operador criador) {
        return novoChecklist(em, sala, criador, LocalDate.of(2026, 7, 1), Turno.MATUTINO);
    }

    /**
     * Checklist com data e turno controlados (filtros de período/coluna e facetas do motor de
     * listagens). {@code criador} nulo deixa CRIADO_POR NULL — o LEFT JOIN com PES_OPERADOR passa a
     * render NOME_COMPLETO nulo, cenário das facetas que pulam NULL.
     */
    public static Checklist novoChecklist(EntityManager em, Sala sala, Operador criador,
            LocalDate dataOperacao, Turno turno) {
        Checklist checklist = new Checklist();
        checklist.setDataOperacao(dataOperacao);
        checklist.setSalaId(sala.getId());
        checklist.setTurno(turno);
        checklist.setHoraInicioTestes("08:00:00");
        checklist.setHoraTerminoTestes("08:30:00");
        checklist.setCriadoPor(criador != null ? criador.getId() : null);
        em.persist(checklist);
        em.flush();
        return checklist;
    }

    public static ChecklistResposta novaResposta(EntityManager em, Checklist checklist,
            ChecklistItemTipo tipo, StatusResposta status, String descricaoFalha) {
        ChecklistResposta resposta = new ChecklistResposta();
        resposta.setChecklistId(checklist.getId());
        resposta.setItemTipoId(tipo.getId());
        resposta.setStatus(status);
        resposta.setDescricaoFalha(descricaoFalha);
        resposta.setCriadoPor(checklist.getCriadoPor());
        em.persist(resposta);
        em.flush();
        return resposta;
    }

    public static RegistroOperacaoAudio novoRegistroAudio(EntityManager em, Sala sala) {
        return novoRegistroAudio(em, sala, true);
    }

    /**
     * Registro de áudio aberto ou fechado. A FBI única UQ_OPR_REGAUDIO_SALA_ABERTA
     * (CASE WHEN EM_ABERTO = 1 THEN SALA_ID END) admite no máximo 1 registro ABERTO
     * por sala — fechados são livres (o CASE resulta NULL, não indexado).
     */
    public static RegistroOperacaoAudio novoRegistroAudio(EntityManager em, Sala sala, boolean emAberto) {
        RegistroOperacaoAudio registro = new RegistroOperacaoAudio();
        registro.setData(LocalDate.of(2026, 7, 1));
        registro.setSalaId(sala.getId());
        registro.setEmAberto(emAberto);
        em.persist(registro);
        em.flush();
        return registro;
    }

    public static Comissao novaComissao(EntityManager em) {
        Comissao comissao = new Comissao();
        comissao.setNome("COMISSAO_IT_" + next());
        em.persist(comissao);
        em.flush();
        return comissao;
    }

    /**
     * Entrada de operador da sessão. UNIQUEs reais da tabela: (REGISTRO_ID, ORDEM)
     * e (REGISTRO_ID, OPERADOR_ID, SEQ) — para 2 entradas no mesmo registro, use
     * ordens E operadores distintos.
     */
    public static RegistroOperacaoOperador novaEntrada(EntityManager em, RegistroOperacaoAudio registro,
            Operador operador, int ordem) {
        RegistroOperacaoOperador entrada = new RegistroOperacaoOperador();
        entrada.setRegistroId(registro.getId());
        entrada.setOperadorId(operador.getId());
        entrada.setOrdem(ordem);
        em.persist(entrada);
        em.flush();
        return entrada;
    }

    /** Grafo mínimo sala → registro de áudio → entrada, para quem só precisa de uma entrada válida. */
    public static RegistroOperacaoOperador novaEntradaComRegistro(EntityManager em, Sala sala, Operador operador) {
        RegistroOperacaoAudio registro = novoRegistroAudio(em, sala);
        return novaEntrada(em, registro, operador, 1);
    }

    /**
     * Entrada com TODAS as colunas de conteúdo preenchidas (para asserções coluna a
     * coluna de listarEntradasDaSessao/getSnapshot/updateEntradaBasica). A coluna
     * física HOUVE_ANORMALIDADE fica NULL — quem precisar dela seta na entidade.
     */
    public static RegistroOperacaoOperador novaEntradaCompleta(EntityManager em, RegistroOperacaoAudio registro,
            Operador operador, int ordem, Comissao comissao) {
        RegistroOperacaoOperador entrada = new RegistroOperacaoOperador();
        entrada.setRegistroId(registro.getId());
        entrada.setOperadorId(operador.getId());
        entrada.setOrdem(ordem);
        entrada.setNomeEvento("Sessao Deliberativa IT");
        entrada.setHorarioPauta("09:00:00");
        entrada.setHorarioInicio("09:15:00");
        entrada.setHorarioTermino("12:00:00");
        entrada.setTipoEvento(TipoEvento.OPERACAO);
        entrada.setUsb01("USB-A");
        entrada.setUsb02("USB-B");
        entrada.setObservacoes("Observacoes da entrada IT");
        entrada.setComissaoId(comissao != null ? comissao.getId() : null);
        entrada.setResponsavelEvento("Senador Responsavel IT");
        entrada.setHoraEntrada("08:30:00");
        entrada.setHoraSaida("12:30:00");
        entrada.setCriadoPor(operador.getId());
        entrada.setAtualizadoPor(operador.getId());
        em.persist(entrada);
        em.flush();
        return entrada;
    }

    /** Vínculo de co-operador na junction OPR_ENTRADA_OPERADOR (Plenário Principal). */
    public static EntradaOperador novoVinculoOperador(EntityManager em, RegistroOperacaoOperador entrada,
            Operador operador) {
        EntradaOperador vinculo = new EntradaOperador();
        vinculo.setEntradaId(entrada.getId());
        vinculo.setOperadorId(operador.getId());
        em.persist(vinculo);
        em.flush();
        return vinculo;
    }

    /**
     * Vínculo de operador adicional na junction FRM_CHECKLIST_OPERADOR. PAPEL é NOT NULL
     * e sem CHECK de domínio; o ChecklistService grava "CABINE" ou "PLENARIO".
     */
    public static ChecklistOperador novoVinculoChecklist(EntityManager em, Checklist checklist, Operador operador) {
        ChecklistOperador vinculo = new ChecklistOperador();
        vinculo.setChecklistId(checklist.getId());
        vinculo.setOperadorId(operador.getId());
        vinculo.setPapel("CABINE");
        em.persist(vinculo);
        em.flush();
        return vinculo;
    }

    /**
     * Ancoragem de relógio no BANCO: reposiciona uma coluna TIMESTAMP da linha em
     * (SYSTIMESTAMP - segundos) via UPDATE nativo e limpa o cache de 1º nível.
     * Falha ruidosamente se a linha não existir (INSERT não materializado).
     * Tabela/coluna vêm de literais dos testes — nunca de entrada externa.
     */
    public static void fixarTimestamp(EntityManager em, String tabela, String coluna,
            Object id, int segundosAtras) {
        ancorar(em, tabela, coluna, "SYSTIMESTAMP - NUMTODSINTERVAL(:valor, 'SECOND')", id, segundosAtras);
    }

    /**
     * Ancoragem de DATA no relógio do BANCO: reposiciona uma coluna DATE em
     * (TRUNC(SYSDATE) - dias), meia-noite exata. Usar sempre que o SUT comparar a
     * coluna com {@code TRUNC(SYSDATE)} — assim o teste passa em qualquer dia do ano
     * e não depende do relógio (nem do fuso) da JVM. {@code diasAtras = 0} = hoje.
     */
    public static void fixarDataRelativa(EntityManager em, String tabela, String coluna,
            Object id, int diasAtras) {
        ancorar(em, tabela, coluna, "TRUNC(SYSDATE) - :valor", id, diasAtras);
    }

    /** Tabela/coluna vêm de literais dos testes — nunca de entrada externa. */
    private static void ancorar(EntityManager em, String tabela, String coluna, String expressao,
            Object id, int valor) {
        int linhas = em.createNativeQuery(
                "UPDATE " + tabela + " SET " + coluna + " = " + expressao + " WHERE ID = :id")
                .setParameter("valor", valor)
                .setParameter("id", id)
                .executeUpdate();
        if (linhas != 1) {
            throw new IllegalStateException("ancoragem de " + tabela + "." + coluna
                    + " esperava 1 linha, afetou " + linhas + " — INSERT não materializado?");
        }
        em.clear();
    }

    public static RegistroAnormalidade novaAnormalidade(EntityManager em, RegistroOperacaoAudio registro,
            Sala sala, Long entradaId, String criadoPor) {
        RegistroAnormalidade anormalidade = new RegistroAnormalidade();
        anormalidade.setRegistroId(registro.getId());
        anormalidade.setEntradaId(entradaId);
        anormalidade.setData(LocalDate.of(2026, 7, 1));
        anormalidade.setSalaId(sala.getId());
        anormalidade.setNomeEvento("Evento IT");
        anormalidade.setHoraInicioAnormalidade("10:00:00");
        anormalidade.setDescricaoAnormalidade("Anormalidade sintetica dos testes de integracao");
        anormalidade.setHouvePrejuizo(false);
        anormalidade.setHouveReclamacao(false);
        anormalidade.setAcionouManutencao(false);
        anormalidade.setResolvidaPeloOperador(true);
        anormalidade.setResponsavelEvento("Responsavel IT");
        anormalidade.setCriadoPor(criadoPor);
        em.persist(anormalidade);
        em.flush();
        return anormalidade;
    }

    /**
     * Tipo de ocorrência do ponto (Feriado, Férias, Atestado…). As marcações têm FK para esta
     * tabela e o clone NUSP_TEST copia só a ESTRUTURA — nenhum tipo nasce nele: quem for marcar
     * um dia cria antes o tipo que vai usar.
     *
     * <p>NOME_NORM e BADGE_NORM são UNIQUE em todo o catálogo (mesmo entre escopos), então nome e
     * badge recebem o sufixo único da instância. Como a badge só tem 3 caracteres, é a BASE que
     * encolhe para o sufixo caber — o teste não deve depender do texto exato que sai daqui.
     *
     * @param escopo {@link PontoTipoMarcacao#ESCOPO_GLOBAL} (vale para todos, PNT_DIA_MARCACAO) ou
     *               {@link PontoTipoMarcacao#ESCOPO_INDIVIDUAL} (pessoa-dia, PNT_PESSOA_MARCACAO)
     */
    public static PontoTipoMarcacao novoTipoMarcacao(EntityManager em, String nome, String badge, String escopo) {
        int n = proximoNumero();
        String nomeUnico = comSufixo(nome, " " + RUN_ID + "_" + n, TETO_NOME_TIPO);
        String badgeUnica = comSufixo(badge, Integer.toString(n, 36), TETO_BADGE_TIPO);

        PontoTipoMarcacao tipo = new PontoTipoMarcacao();
        tipo.setNome(nomeUnico);
        // A forma normalizada é a que o banco compara: gerá-la aqui com a MESMA regra do cadastro
        // impede que a fixture aceite um par que o serviço recusaria como duplicado.
        tipo.setNomeNorm(TipoMarcacaoService.normalizar(nomeUnico));
        tipo.setBadge(badgeUnica);
        tipo.setBadgeNorm(TipoMarcacaoService.normalizar(badgeUnica));
        tipo.setEscopo(escopo);
        em.persist(tipo);
        em.flush();
        return tipo;
    }

    /** Base + sufixo dentro do teto da coluna: quem é truncado é a BASE — o sufixo é o que garante a unicidade. */
    private static String comSufixo(String base, String sufixo, int teto) {
        String texto = base == null ? "" : base.strip();
        int espaco = Math.max(0, teto - sufixo.length());
        return (texto.length() > espaco ? texto.substring(0, espaco) : texto) + sufixo;
    }

    /**
     * Solicitação de folga PENDENTE/CANCELADA (as que o CHECK CK_PNT_SOLF_DELIB exige SEM
     * deliberador). MINUTOS_DEBITADOS é NOT NULL — vai o débito de uma jornada de 40h (Q3).
     * ⚠️ A FBI UQ_PNT_SOLF_VIVA admite UM pedido vivo (PENDENTE/APROVADO) por (pessoa, dia):
     * duas linhas vivas no mesmo dia da mesma pessoa estouram unicidade.
     */
    public static PontoSolicitacaoFolga novaSolicitacaoFolga(EntityManager em, String pessoaId, String pessoaTipo,
            LocalDate dataFolga, StatusSolicitacaoFolga status) {
        return novaSolicitacaoFolga(em, pessoaId, pessoaTipo, dataFolga, status, null, null);
    }

    /**
     * Solicitação DELIBERADA: APROVADO/REJEITADO exigem DELIBERADO_POR_ID (CK_PNT_SOLF_DELIB),
     * e ele tem FK para PES_ADMINISTRADOR — daí o deliberador ser um {@link Administrador} real,
     * não um id sintético. É esse admin que a listagem devolve em {@code deliberado_por} (LEFT JOIN).
     */
    public static PontoSolicitacaoFolga novaSolicitacaoFolga(EntityManager em, String pessoaId, String pessoaTipo,
            LocalDate dataFolga, StatusSolicitacaoFolga status, Administrador deliberadoPor, String motivoRejeicao) {
        // Falha ruidosa ANTES do flush: o CK_PNT_SOLF_DELIB devolveria só um ORA-02290 opaco.
        boolean deliberada = status == StatusSolicitacaoFolga.APROVADO || status == StatusSolicitacaoFolga.REJEITADO;
        if (deliberada != (deliberadoPor != null)) {
            throw new IllegalArgumentException("CK_PNT_SOLF_DELIB: " + status
                    + (deliberada ? " EXIGE deliberador" : " NÃO admite deliberador"));
        }
        PontoSolicitacaoFolga solicitacao = new PontoSolicitacaoFolga();
        solicitacao.setPessoaId(pessoaId);
        solicitacao.setPessoaTipo(pessoaTipo);
        solicitacao.setDataFolga(dataFolga);
        solicitacao.setMinutosDebitados(480);
        solicitacao.setStatus(status);
        if (deliberadoPor != null) {
            solicitacao.setDeliberadoPorId(deliberadoPor.getId());
            // Deliberação é anterior ao dia da folga (a linha do domínio não delibera o próprio futuro).
            solicitacao.setDeliberadoEm(dataFolga.minusDays(1).atTime(9, 0));
        }
        solicitacao.setMotivoRejeicao(motivoRejeicao);
        em.persist(solicitacao);
        em.flush();
        return solicitacao;
    }

    /**
     * Linha do cache de saldo da pessoa (UK UQ_PNT_SALDO_PESSOA: uma por pessoa+tipo). Abertura e
     * banco recebem o MESMO valor — as listagens só leem SALDO_BANCO_MIN. Pessoa sem esta linha é
     * cenário legítimo (LEFT JOIN devolve {@code saldo_min} nulo), não erro.
     */
    public static PontoBancoSaldo novoSaldoBanco(EntityManager em, String pessoaId, String pessoaTipo, int saldoMin) {
        PontoBancoSaldo saldo = new PontoBancoSaldo();
        saldo.setPessoaId(pessoaId);
        saldo.setPessoaTipo(pessoaTipo);
        saldo.setSaldoAberturaMin(saldoMin);
        saldo.setSaldoBancoMin(saldoMin);
        em.persist(saldo);
        em.flush();
        return saldo;
    }

    /**
     * Lote de cartão-ponto em REVISÃO (o estado de onde a publicação parte). ARQUIVO_ORIGINAL e
     * TOTAL_PAGINAS são NOT NULL, e CRIADO_POR_ID tem FK para PES_ADMINISTRADOR — daí o admin real.
     * O arquivo NÃO precisa existir em disco: a extração do BANCO engole a falha de leitura com um
     * WARN e deixa BANCO_FINAL_MIN nulo (a publicação nunca aborta por causa do PDF).
     *
     * <p>A folha mensal nasce PRÉVIA — a natureza retificável e substituível, que é o que uma folha
     * mensal sempre foi antes de a escolha existir; a semanal fica sem categoria. Quem precisa da
     * DEFINITIVA usa a sobrecarga que recebe a natureza.
     */
    public static PontoLote novoLotePonto(EntityManager em, String tipo, LocalDate dataInicio, LocalDate dataFim,
            Administrador criadoPor) {
        return novoLotePonto(em, tipo, categoriaPadrao(tipo), dataInicio, dataFim, criadoPor);
    }

    /**
     * Lote em revisão com a natureza escolhida: PREVIA ou DEFINITIVA na folha mensal, {@code null}
     * na semanal (que não tem essa distinção).
     */
    public static PontoLote novoLotePonto(EntityManager em, String tipo, String categoria, LocalDate dataInicio,
            LocalDate dataFim, Administrador criadoPor) {
        exigirCategoriaCoerente(tipo, categoria);
        PontoLote lote = new PontoLote();
        lote.setTipo(tipo);
        lote.setCategoria(categoria);
        lote.setDataInicio(dataInicio);
        lote.setDataFim(dataFim);
        lote.setArquivoOriginal("ponto/originais/" + UUID.randomUUID() + ".pdf");
        lote.setTotalPaginas(1);
        lote.setCriadoPorId(criadoPor.getId());
        em.persist(lote);
        em.flush();
        return lote;
    }

    /** Lote de cartão-ponto JÁ PUBLICADO — a folha oficial que ancora o banco de horas da pessoa. */
    public static PontoLote novoLotePontoPublicado(EntityManager em, String tipo, LocalDate dataInicio,
            LocalDate dataFim, Administrador criadoPor) {
        return novoLotePontoPublicado(em, tipo, categoriaPadrao(tipo), dataInicio, dataFim, criadoPor);
    }

    /** Folha publicada com a natureza escolhida — a DEFINITIVA é a que fecha o mês da pessoa. */
    public static PontoLote novoLotePontoPublicado(EntityManager em, String tipo, String categoria,
            LocalDate dataInicio, LocalDate dataFim, Administrador criadoPor) {
        PontoLote lote = novoLotePonto(em, tipo, categoria, dataInicio, dataFim, criadoPor);
        lote.setStatus("PUBLICADO");
        lote.setPublicadoEm(LocalDateTime.now());
        em.merge(lote);
        em.flush();
        return lote;
    }

    /** Natureza que o lote assume quando o teste não escolhe: mensal prévia, semanal nenhuma. */
    private static String categoriaPadrao(String tipo) {
        return TIPO_LOTE_MENSAL.equals(tipo) ? PontoLote.CATEGORIA_PREVIA : null;
    }

    /**
     * Falha ruidosa ANTES do flush: o CK_PNT_LOTE_CATEGORIA devolveria só um ORA-02290 opaco.
     * Mensal sem natureza (ou com natureza fora do domínio) e semanal com natureza não existem.
     */
    private static void exigirCategoriaCoerente(String tipo, String categoria) {
        boolean mensal = TIPO_LOTE_MENSAL.equals(tipo);
        boolean coerente = mensal
                ? PontoLote.CATEGORIA_PREVIA.equals(categoria) || PontoLote.CATEGORIA_DEFINITIVA.equals(categoria)
                : categoria == null;
        if (!coerente) {
            throw new IllegalArgumentException("CK_PNT_LOTE_CATEGORIA: lote " + tipo + " não aceita categoria "
                    + categoria + (mensal ? " (use PREVIA ou DEFINITIVA)" : " (só a folha mensal tem natureza)"));
        }
    }

    /**
     * Página VINCULADA a uma pessoa (STATUS_MATCH = MANUAL). O CK_PNT_PAGINA_VINCULO exige
     * PESSOA_ID e PESSOA_TIPO juntos quando o match é AUTO/MANUAL (e ambos nulos quando PENDENTE) —
     * por isso não há variante "meio vinculada". ⚠️ UK_PNT_PAGINA_NUM: (LOTE_ID, NUMERO_PAGINA) é
     * único, então o número é do chamador.
     */
    public static PontoLotePagina novaPaginaLote(EntityManager em, PontoLote lote, int numeroPagina,
            String pessoaId, String pessoaTipo) {
        return novaPaginaLote(em, lote, numeroPagina, pessoaId, pessoaTipo, null);
    }

    /**
     * Como a anterior, com o BANCO já extraído (BANCO_FINAL_MIN): é o que torna a página elegível a
     * ÂNCORA do banco de horas (findCandidatasAncora exige lote PUBLICADO e banco não nulo) sem
     * depender de um PDF real em disco.
     */
    public static PontoLotePagina novaPaginaLote(EntityManager em, PontoLote lote, int numeroPagina,
            String pessoaId, String pessoaTipo, Integer bancoFinalMin) {
        PontoLotePagina pagina = novaPagina(em, lote, numeroPagina, "MANUAL");
        pagina.setPessoaId(pessoaId);
        pagina.setPessoaTipo(pessoaTipo);
        pagina.setBancoFinalMin(bancoFinalMin);
        return persistirPagina(em, lote, pagina);
    }

    /** Página PENDENTE: sem pessoa nenhuma (o CK exige os dois campos nulos) — a que o admin ainda vai vincular. */
    public static PontoLotePagina novaPaginaPendente(EntityManager em, PontoLote lote, int numeroPagina) {
        return persistirPagina(em, lote, novaPagina(em, lote, numeroPagina, "PENDENTE"));
    }

    private static PontoLotePagina novaPagina(EntityManager em, PontoLote lote, int numeroPagina, String statusMatch) {
        PontoLotePagina pagina = new PontoLotePagina();
        pagina.setLoteId(lote.getId());
        pagina.setNumeroPagina(numeroPagina);
        pagina.setArquivoPagina("ponto/paginas/" + UUID.randomUUID() + ".pdf");
        pagina.setStatusMatch(statusMatch);
        return pagina;
    }

    private static PontoLotePagina persistirPagina(EntityManager em, PontoLote lote, PontoLotePagina pagina) {
        em.persist(pagina);
        // TOTAL_PAGINAS acompanha as páginas criadas: no upload real ele é a contagem do PDF, e um lote
        // que dissesse "1 página" com duas páginas persistidas falsificaria `total_paginas` no detalhe.
        if (pagina.getNumeroPagina() > lote.getTotalPaginas()) {
            lote.setTotalPaginas(pagina.getNumeroPagina());
            em.merge(lote);
        }
        em.flush();
        return pagina;
    }

    public static AuthSession novaSessao(EntityManager em, String userId) {
        AuthSession sessao = new AuthSession();
        sessao.setUserId(userId);
        // REFRESH_TOKEN_HASH é UNIQUE — um por sessão criada
        sessao.setRefreshTokenHash(UUID.randomUUID().toString());
        em.persist(sessao);
        em.flush();
        return sessao;
    }
}

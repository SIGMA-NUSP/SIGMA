package br.leg.senado.nusp.service;

import br.leg.senado.nusp.exception.ServiceValidationException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Utilitários compartilhados para converter valores de queries nativas Oracle/JPA,
 * ler campos de bodies/rows Map&lt;String,Object&gt;, montar/executar padrões
 * recorrentes de query nativa (IN dinâmico, ownership, flags de sala) e
 * normalizar texto pt-BR (meses, acentos).
 * Evita duplicação dos helpers str(), num(), boolVal() etc. em cada service.
 */
public final class NativeQueryUtils {

    private NativeQueryUtils() {}

    /** Converte Object para String, tratando CLOB do Oracle. */
    public static String str(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Clob clob) {
            try { return clob.getSubString(1, (int) clob.length()); }
            catch (java.sql.SQLException e) { return ""; }
        }
        return o.toString();
    }

    /** Converte Object numérico para Long. */
    public static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    /** Converte NUMBER(1) do Oracle para boolean (0=false, 1=true). */
    public static boolean boolVal(Object o) {
        return o != null && ((Number) o).intValue() == 1;
    }

    /** Lê uma chave do body como String aparada (null → ""). */
    public static String clean(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? "" : val.toString().strip();
    }

    /** Normaliza String em branco para null (senão, apara). */
    public static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    /**
     * Guarda de tamanho de campo de texto livre (F33/F47): texto acima do limite → 400 nomeando o
     * campo, em vez de ORA-12899 lá no fundo. Devolve o próprio valor (encadeável na atribuição).
     *
     * <p><b>Conta em {@code length()} — e isso é uma decisão, não descuido.</b> As colunas são
     * dimensionadas em BYTES (o Oracle aqui é {@code CHAR_USED = B}: {@code OBSERVACOES}
     * VARCHAR2(2000), {@code MOTIVO_REJEICAO} VARCHAR2(1000)), mas o usuário conta CARACTERES — e
     * {@code length()} (code units UTF-16) é exatamente o que o {@code maxlength} do HTML conta, o
     * que mantém as duas pontas dizendo a mesma coisa. Ele ainda TETA os bytes: um code unit custa
     * no máximo 3 bytes em UTF-8 (BMP), e o que passa disso (emoji, 4 bytes) ocupa DOIS code units
     * — ou seja, ≤ 3 bytes por unidade contada. Com o limite de 300, o pior caso é 900 bytes, que
     * cabe até no menor budget (1000). {@code codePointCount} NÃO teria essa propriedade: 300
     * emojis = 1.200 bytes, e o ORA-12899 do motivo de rejeição continuaria alcançável.
     */
    public static String textoLimitado(String valor, int maxCaracteres, String campo) {
        if (valor != null && valor.length() > maxCaracteres) {
            throw new ServiceValidationException(campo + " excede o máximo de "
                    + maxCaracteres + " caracteres (foram " + valor.length() + ").");
        }
        return valor;
    }

    // ── Casts guardados do body não-tipado: shape errado → 400 (não deixa ClassCastException virar 500) ──
    // Compartilhados pelos endpoints que recebem estruturas aninhadas no corpo (marcações e o lote de
    // retificação); a disciplina é a do F27: o cliente errou o shape → 400 nomeando o campo.

    /** Objeto aninhado do body ({@code null} quando a chave está ausente ou vem JSON null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v, String campo) {
        if (v == null) return null;
        if (!(v instanceof Map)) throw new ServiceValidationException("Campo '" + campo + "' deve ser um objeto.");
        return (Map<String, Object>) v;
    }

    /** Lista do body (ausente OU JSON null → lista vazia; quem exige conteúdo decide a mensagem). */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v, String campo) {
        if (v == null) return List.of();
        if (!(v instanceof List)) throw new ServiceValidationException("Campo '" + campo + "' deve ser uma lista.");
        return (List<Object>) v;
    }

    /** Item (objeto) de uma lista do body. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asItem(Object v, String campo) {
        if (!(v instanceof Map)) throw new ServiceValidationException("Item de '" + campo + "' deve ser um objeto.");
        return (Map<String, Object>) v;
    }

    /** "HH:MM:SS" → "HH:MM" (null/curto → retorna o original). */
    public static String hhmm(String t) {
        if (t == null || t.length() < 5) return t;
        return t.substring(0, 5);
    }

    /** Primeiro valor não-vazio entre as chaves, como String ("--" se nenhum). */
    public static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return "--";
    }

    public static String strOrDefault(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    public static String nonEmpty(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) return def;
        String s = v.toString().trim();
        return s.isEmpty() ? def : s;
    }

    /** Concatena "Demais Salas: <nome livre>" quando a row tem nome_demais_salas preenchido. */
    public static String localDisplay(Map<String, Object> r, String... salaKeys) {
        Object ndsObj = r.get("nome_demais_salas");
        String nomeDemais = ndsObj != null ? ndsObj.toString().trim() : "";
        String salaNome = str(r, salaKeys);
        return nomeDemais.isEmpty() ? salaNome : (salaNome + ": " + nomeDemais);
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s) || "sim".equalsIgnoreCase(s);
        return false;
    }

    public static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    /** Nomes dos meses em português, minúsculos (índice 0 = janeiro). */
    public static final String[] PT_BR_MONTHS = {
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    };

    /** Nome de mês pt-BR (tolerante a caixa/espaços; aceita "marco" sem cedilha) → 1-12, ou 0 se não reconhecido. */
    public static int mesPtParaNumero(String mes) {
        String m = mes.toLowerCase().trim();
        if ("marco".equals(m)) return 3;
        for (int i = 0; i < PT_BR_MONTHS.length; i++) {
            if (PT_BR_MONTHS[i].equals(m)) return i + 1;
        }
        return 0;
    }

    /**
     * Minúsculas sem diacríticos, para comparação tolerante a acentos (null → "").
     *
     * <p>É a regra "acento e caixa não contam" do lado Java — a mesma que o Oracle aplica com
     * {@code NLS_SORT=BINARY_AI} (remove acento, remove caixa, compara o resto em binário). Por
     * isso serve de chave a {@link #ORDEM_TEXTO_PT_BR}, que precisa dar a MESMA ordem que o banco
     * (F30).
     *
     * <p><b>NFKD, não NFD</b> — e a diferença é concreta, não acadêmica. O NFD decompõe acentos mas
     * NÃO toca os caracteres de <i>compatibilidade</i>: o ordinal {@code ª} (U+00AA) sobreviveria
     * inteiro e ordenaria DEPOIS do "z", enquanto o Oracle o trata como um {@code a} — e o sistema
     * tem centenas de eventos chamados "1ª Reunião" (`NOME_EVENTO`), que é faceta E coluna de busca.
     * Com NFD, a faceta (ordenada em Java) e a coluna (ordenada no banco) discordariam justamente
     * nessas linhas — o F30 de volta, pela porta dos fundos. O NFKD também resolve ligaduras
     * ({@code ﬁ} → {@code fi}). Resíduo conhecido e aceito: letras com traço/barra ({@code ø},
     * {@code đ}, {@code ł}) e o {@code ß} — o Oracle as reduz à letra-base, o Unicode não; nenhuma
     * ocorre em pt-BR (zero linhas no banco).
     *
     * <p>{@code Locale.ROOT} não é adorno: {@code toLowerCase()} sem locale usa o default da JVM, e
     * era exatamente essa dependência de locale — a mesma que fazia dev e produção ordenarem
     * diferente — que o F30 veio arrancar. Em tr/az, o default mapearia 'I' para 'ı' e a ordem
     * dependeria de novo de quem chamou.
     */
    public static String semAcento(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * A ordenação de texto pt-BR do sistema, do lado Java (F30) — <b>use esta, nunca
     * {@code compareTo} nem {@code toUpperCase()}</b>, sempre que uma lista de nomes for ordenada em
     * memória em vez de vir ordenada do banco.
     *
     * <p>Reproduz o {@code ORDER BY} que o Oracle faz sob {@code NLS_SORT=BINARY_AI} (fixado no
     * {@code connection-init-sql}, application.yml): chave primária sem acento e sem caixa
     * ({@link #semAcento}), desempate binário pelo valor cru — o mesmo par
     * {@code NLSSORT(V)}, {@code NLSSORT(V,'NLS_SORT=BINARY')}. O desempate não é enfeite: "Jose" e
     * "José" são valores distintos que COLIDEM na chave sem acento, e sem ele a ordem entre os dois
     * ficaria arbitrária de um lado e indefinida do outro.
     *
     * <p>Sem isto, uma tela ordenada em Java e outra ordenada pelo banco mostram a MESMA lista de
     * pessoas em ordens diferentes (a grade do Ponto dizia "Katiane, Kátia" enquanto a listagem de
     * pessoas dizia "Kátia, Katiane").
     */
    public static final Comparator<String> ORDEM_TEXTO_PT_BR =
            Comparator.comparing(NativeQueryUtils::semAcento).thenComparing(Comparator.naturalOrder());

    /** Máximo de itens numa lista IN do Oracle (ORA-01795). */
    public static final int ORACLE_IN_LIMIT = 1000;

    /**
     * Particiona os valores em blocos de até {@link #ORACLE_IN_LIMIT} itens, preservando a
     * ordem de iteração, para montar cláusulas IN em lote sem estourar o ORA-01795.
     * Coleção vazia → nenhum bloco (nenhuma query a executar).
     */
    public static <T> List<List<T>> partitionForIn(Collection<T> valores) {
        List<List<T>> blocos = new ArrayList<>();
        List<T> atual = new ArrayList<>(Math.min(valores.size(), ORACLE_IN_LIMIT));
        for (T v : valores) {
            atual.add(v);
            if (atual.size() == ORACLE_IN_LIMIT) {
                blocos.add(atual);
                atual = new ArrayList<>(ORACLE_IN_LIMIT);
            }
        }
        if (!atual.isEmpty()) blocos.add(atual);
        return blocos;
    }

    /** "?,?,..." com n placeholders posicionais para cláusula IN (native query não expande coleções). */
    public static String inPlaceholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    /** "?1,?2,..." com n placeholders posicionais numerados para cláusula IN. */
    public static String inPlaceholdersNumbered(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("?").append(i + 1);
        }
        return sb.toString();
    }

    /** Binda os valores da coleção em índices posicionais consecutivos a partir de primeiroIndice. */
    public static void bindList(Query q, Collection<?> valores, int primeiroIndice) {
        int i = primeiroIndice;
        for (Object v : valores) q.setParameter(i++, v);
    }

    /**
     * Usuário é dono do registro OU operador adicional na junction?
     * Os identificadores SQL vêm de constantes dos services (nunca de input de usuário).
     */
    public static boolean isDonoOuAdicional(EntityManager em, String tabela, String colDono,
                                            String junction, String colFk, long id, String userId) {
        Number ok = (Number) em.createNativeQuery(String.format("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM %s WHERE ID = ?1 AND %s = ?2
                    UNION ALL
                    SELECT 1 FROM %s WHERE %s = ?1 AND OPERADOR_ID = ?2
                ) THEN 1 ELSE 0 END FROM DUAL
                """, tabela, colDono, junction, colFk))
                .setParameter(1, id).setParameter(2, userId).getSingleResult();
        return ok.intValue() == 1;
    }

    /** Flag MULTI_OPERADOR da sala (sala inexistente → false). */
    public static boolean salaMultiOperador(EntityManager em, long salaId) {
        List<?> rows = em.createNativeQuery("SELECT MULTI_OPERADOR FROM CAD_SALA WHERE ID = ?1")
                .setParameter(1, salaId).getResultList();
        return !rows.isEmpty() && boolVal(rows.get(0));
    }

    /**
     * Mapeia a row de lookup de usuário (PERFIL, ID, NOME_COMPLETO, USERNAME, EMAIL) para Map.
     * Preserva String.valueOf (null → "null") — não trocar por str(), que devolveria null.
     */
    public static Map<String, String> userRowToMap(Object[] row) {
        Map<String, String> user = new HashMap<>();
        user.put("perfil",        String.valueOf(row[0]));
        user.put("id",            String.valueOf(row[1]));
        user.put("nome_completo", String.valueOf(row[2]));
        user.put("username",      String.valueOf(row[3]));
        user.put("email",         String.valueOf(row[4]));
        return user;
    }

    /**
     * Mapeia o papel (perfil) para a tabela PES_* correspondente — fonte única do mapeamento,
     * usada onde o nome da tabela é interpolado em query nativa.
     *
     * @return null se o papel for desconhecido (cada chamador decide o que fazer com isso).
     */
    public static String tableForRole(String role) {
        if (role == null) return null;
        return switch (role) {
            case "administrador" -> "PES_ADMINISTRADOR";
            case "operador"      -> "PES_OPERADOR";
            case "tecnico"       -> "PES_TECNICO";
            default -> null;
        };
    }
}

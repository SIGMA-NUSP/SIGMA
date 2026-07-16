package br.leg.senado.nusp.enums;

/**
 * Papel da pessoa que interage com um aviso. Não é persistido diretamente:
 * o AvisoService o usa para decidir qual coluna preencher/consultar em
 * FRM_AVISO_CIENCIA (OPERADOR_ID, TECNICO_ID ou ADMIN_ID).
 */
public enum PapelPessoa {
    OPERADOR,
    TECNICO,
    ADMIN;

    /** Converte o role do UserPrincipal ("operador"/"tecnico"/"administrador") no papel; null se desconhecido. */
    public static PapelPessoa fromRole(String role) {
        if (role == null) return null;
        return switch (role.trim().toLowerCase()) {
            case "operador" -> OPERADOR;
            case "tecnico" -> TECNICO;
            case "administrador" -> ADMIN;
            default -> null;
        };
    }

    /**
     * Converte o PESSOA_TIPO polimórfico das tabelas de ponto ("OPERADOR"/"TECNICO"/"ADMINISTRADOR")
     * no papel; null se desconhecido. ⚠️ O tipo do vínculo é {@code ADMINISTRADOR}, mas o papel (e a
     * coluna do alvo/ciência do aviso) é {@code ADMIN} — a tradução vive aqui, e não copiada em cada
     * caminho que publica ou exclui folha.
     */
    public static PapelPessoa dePessoaTipo(String pessoaTipo) {
        if (pessoaTipo == null) return null;
        return switch (pessoaTipo.trim().toUpperCase()) {
            case "OPERADOR" -> OPERADOR;
            case "TECNICO" -> TECNICO;
            case "ADMINISTRADOR" -> ADMIN;
            default -> null;
        };
    }
}

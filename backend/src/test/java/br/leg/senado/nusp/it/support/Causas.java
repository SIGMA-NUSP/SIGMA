package br.leg.senado.nusp.it.support;

/**
 * Inspeção da cadeia de causas de uma exceção nos testes de integração —
 * tipicamente para confirmar que uma PersistenceException veio da constraint
 * Oracle esperada (ex.: ORA-00001 num índice único nomeado), não de outro erro.
 */
public final class Causas {

    private Causas() {
    }

    public static boolean contem(Throwable ex, String trecho) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(trecho)) {
                return true;
            }
        }
        return false;
    }
}

-- ============================================================
-- 013 — VW_OPERADOR_PUBLICO (Metabase / NUSP_REPORTS)
--
-- View pública com apenas ID + nomes do operador, para uso pelo
-- usuário read-only NUSP_REPORTS no Metabase. Evita expor
-- email, username, password_hash e foto_url de PES_OPERADOR.
--
-- O sinônimo NUSP_REPORTS.VW_OPERADOR_PUBLICO precisa ser criado
-- manualmente como SYS (Liquibase roda como NUSP e não tem
-- privilégio CREATE ANY SYNONYM).
-- ============================================================

CREATE OR REPLACE VIEW VW_OPERADOR_PUBLICO AS
SELECT
    ID,
    NOME_COMPLETO,
    NOME_EXIBICAO
FROM PES_OPERADOR;

GRANT SELECT ON VW_OPERADOR_PUBLICO TO NUSP_REPORTS;

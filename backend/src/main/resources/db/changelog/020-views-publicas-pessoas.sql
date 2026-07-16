-- ============================================================
-- 020 — Views públicas de pessoas (Metabase / NUSP_REPORTS)
--
-- Padroniza as 3 views de pessoas com NOME_COMPLETO + EMAIL, para o
-- usuário read-only NUSP_REPORTS poder listá-las e fazer JOIN nas
-- queries do Metabase sem SELECT direto nas tabelas PES_* (que têm
-- username/password_hash/foto).
--
-- EMAIL é dado público (o Senado divulga os contatos institucionais),
-- portanto pode ser exposto ao BI. VW_OPERADOR_PUBLICO (criada na 013)
-- ganha a coluna EMAIL; VW_TECNICO_PUBLICO e VW_ADMIN_PUBLICO são novas.
-- ============================================================

CREATE OR REPLACE VIEW VW_OPERADOR_PUBLICO AS
SELECT
    ID,
    NOME_COMPLETO,
    NOME_EXIBICAO,
    EMAIL
FROM PES_OPERADOR;

CREATE OR REPLACE VIEW VW_TECNICO_PUBLICO AS
SELECT
    ID,
    NOME_COMPLETO,
    EMAIL
FROM PES_TECNICO;

CREATE OR REPLACE VIEW VW_ADMIN_PUBLICO AS
SELECT
    ID,
    NOME_COMPLETO,
    EMAIL
FROM PES_ADMINISTRADOR;

GRANT SELECT ON VW_OPERADOR_PUBLICO TO NUSP_REPORTS;
GRANT SELECT ON VW_TECNICO_PUBLICO TO NUSP_REPORTS;
GRANT SELECT ON VW_ADMIN_PUBLICO TO NUSP_REPORTS;

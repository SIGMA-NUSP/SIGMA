-- ============================================================
-- 015 — Ajustes no catálogo de dashboards
--
-- - Remove a descrição temporária do "Dashboard Teste" (id 3)
-- - Adiciona "Dashboard Teste 2" (id 4) como segundo card
-- ============================================================

UPDATE INT_METABASE_DASHBOARD
   SET DESCRICAO = NULL,
       ATUALIZADO_EM = SYSTIMESTAMP
 WHERE METABASE_DASHBOARD_ID = 3;

INSERT INTO INT_METABASE_DASHBOARD (
    ID, METABASE_DASHBOARD_ID, TITULO, DESCRICAO, ICONE, ORDEM, ATIVO, CRIADO_EM, ATUALIZADO_EM
) VALUES (
    'c2e4f9b3-5d7a-4b8c-9e1f-3a6b8c9d2e4f',
    4,
    'Dashboard Teste 2',
    NULL,
    NULL,
    2,
    1,
    SYSTIMESTAMP,
    SYSTIMESTAMP
);

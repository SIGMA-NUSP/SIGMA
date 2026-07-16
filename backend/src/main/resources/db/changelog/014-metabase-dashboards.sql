-- ============================================================
-- 014 — INT_METABASE_DASHBOARD + remoção de INT_METABASE_USER
--
-- Substitui o SSO caseiro (clique no card -> redireciona para
-- bi.senado-nusp.cloud) por embed assinado (static embedding)
-- de dashboards Metabase dentro do próprio sistema NUSP, na
-- página /admin/analise.
--
-- - DROP INT_METABASE_USER: SSO removido, a tabela perde uso.
--   O backend correspondente (MetabaseSsoService/Controller,
--   entity MetabaseUser e MetabaseUserRepository) também será
--   removido no Passo 2 do plano.
--
-- - CREATE INT_METABASE_DASHBOARD: catálogo de dashboards que
--   o admin verá como cards na sidebar da página de Análise.
--   Cada linha aponta para um dashboard pré-montado no
--   Metabase via METABASE_DASHBOARD_ID.
-- ============================================================

DROP TABLE INT_METABASE_USER CASCADE CONSTRAINTS;

CREATE TABLE INT_METABASE_DASHBOARD (
    ID                      VARCHAR2(36)   PRIMARY KEY,
    METABASE_DASHBOARD_ID   NUMBER(10)     NOT NULL,
    TITULO                  VARCHAR2(120)  NOT NULL,
    DESCRICAO               VARCHAR2(500),
    ICONE                   VARCHAR2(60),
    ORDEM                   NUMBER(3)      DEFAULT 0 NOT NULL,
    ATIVO                   NUMBER(1)      DEFAULT 1 NOT NULL,
    PARAMS_LOCKED           CLOB,
    CRIADO_EM               TIMESTAMP      NOT NULL,
    ATUALIZADO_EM           TIMESTAMP      NOT NULL,
    CONSTRAINT UK_INT_MB_DASH_METABASE_ID UNIQUE (METABASE_DASHBOARD_ID),
    CONSTRAINT CK_INT_MB_DASH_ATIVO CHECK (ATIVO IN (0, 1))
);

CREATE INDEX IDX_INT_MB_DASH_ATIVO_ORD ON INT_METABASE_DASHBOARD (ATIVO, ORDEM);

-- Seed inicial: o único dashboard que existe hoje no Metabase
-- (id 3, "dashboart-teste1"). Mais dashboards serão adicionados
-- conforme forem montados pelo administrador master.
INSERT INTO INT_METABASE_DASHBOARD (
    ID, METABASE_DASHBOARD_ID, TITULO, DESCRICAO, ICONE, ORDEM, ATIVO, CRIADO_EM, ATUALIZADO_EM
) VALUES (
    'b1f3d8a2-4e6c-4f9b-8a7d-2c5e9f1a3b6d',
    3,
    'Dashboard Teste',
    'Dashboard temporário usado durante a implementação do embed.',
    'bi-bar-chart',
    1,
    1,
    SYSTIMESTAMP,
    SYSTIMESTAMP
);

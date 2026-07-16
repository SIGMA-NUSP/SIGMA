-- ============================================================
-- 012 — INT_METABASE_USER
--
-- Tabela de integração para o SSO "caseiro" com o Metabase.
--
-- Cada administrador do sistema NUSP (PES_ADMINISTRADOR) que
-- acessa o Metabase ganha um usuário correspondente lá, com
-- senha aleatória gerada pelo backend (não vinculada à senha
-- de login do NUSP).
--
-- Fluxo no primeiro clique em "Análise":
--   1) Backend verifica se há registro em INT_METABASE_USER
--      para o admin
--   2) Se não há: cria user via API admin do Metabase,
--      gera senha aleatória, salva aqui
--   3) Faz login programático no Metabase com email+senha
--   4) Retorna cookie metabase.SESSION com Domain=.senado-nusp.cloud
--   5) Frontend abre nova aba em bi.senado-nusp.cloud
-- ============================================================

CREATE TABLE INT_METABASE_USER (
    ID                 VARCHAR2(36)   PRIMARY KEY,
    ADMIN_ID           VARCHAR2(36)   NOT NULL,
    EMAIL              VARCHAR2(255)  NOT NULL,
    SENHA_CIFRADA      VARCHAR2(255)  NOT NULL,
    METABASE_USER_ID   NUMBER(10)     NOT NULL,
    CRIADO_EM          TIMESTAMP      NOT NULL,
    ATUALIZADO_EM      TIMESTAMP      NOT NULL,
    CONSTRAINT UK_INT_MB_ADMIN UNIQUE (ADMIN_ID),
    CONSTRAINT UK_INT_MB_EMAIL UNIQUE (EMAIL),
    CONSTRAINT FK_INT_MB_ADMIN FOREIGN KEY (ADMIN_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- (EMAIL é UNIQUE, então o Oracle já cria índice automático — não precisa CREATE INDEX manual)

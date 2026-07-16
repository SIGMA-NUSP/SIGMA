-- ============================================================
-- 008 — PES_TECNICO
--
-- Terceiro tipo de usuário do sistema, além de operador e
-- administrador. Mesma estrutura de PES_OPERADOR, sem as
-- colunas de escala/plenário (PLENARIO_PRINCIPAL,
-- PARTICIPA_ESCALA, TURNO) e sem NOME_EXIBICAO.
--
-- Login compartilhado com as demais tabelas via UNION ALL em
-- AuthService.findUserForLogin — username e email seguem a
-- convenção de unicidade global entre as três tabelas
-- (validada na aplicação, não no banco).
-- ============================================================
CREATE TABLE PES_TECNICO (
    ID             VARCHAR2(36)  DEFAULT SYS_GUID() PRIMARY KEY,
    NOME_COMPLETO  VARCHAR2(500) NOT NULL,
    EMAIL          VARCHAR2(255) NOT NULL,
    USERNAME       VARCHAR2(100) NOT NULL,
    PASSWORD_HASH  VARCHAR2(255) NOT NULL,
    FOTO_URL       VARCHAR2(500),
    CRIADO_EM      TIMESTAMP     NOT NULL,
    ATUALIZADO_EM  TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX UQ_PES_TECNICO_EMAIL    ON PES_TECNICO (EMAIL);
CREATE UNIQUE INDEX UQ_PES_TECNICO_USERNAME ON PES_TECNICO (USERNAME);

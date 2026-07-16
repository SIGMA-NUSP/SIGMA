-- ============================================================
-- 037 — Ponto: PNT_DIA_MARCACAO (marcação global de dia)
--
-- Feriado / Ponto Facultativo em escopo global (Q6 — todos os
-- funcionários, todas as categorias), como na planilha real.
-- Um dia tem no máximo 1 marcação (UK DATA): trocar tipo = update;
-- desmarcar = delete físico (marcação é configuração, não fato
-- auditável além do CRIADO_POR_ID).
-- ============================================================

CREATE TABLE PNT_DIA_MARCACAO (
    ID             VARCHAR2(36)  PRIMARY KEY,
    DATA           DATE          NOT NULL,
    TIPO           VARCHAR2(20)  NOT NULL,
    CRIADO_POR_ID  VARCHAR2(36)  NOT NULL,
    CRIADO_EM      TIMESTAMP     NOT NULL,
    ATUALIZADO_EM  TIMESTAMP     NOT NULL,
    CONSTRAINT UK_PNT_DIAMARC_DATA UNIQUE (DATA),
    CONSTRAINT CK_PNT_DIAMARC_TIPO CHECK (TIPO IN ('FERIADO','PONTO_FACULTATIVO')),
    CONSTRAINT FK_PNT_DIAMARC_ADMIN FOREIGN KEY (CRIADO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

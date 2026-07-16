-- ============================================================
-- 018 — Ponto e Banco de Horas (Passo 1: upload / separação / vínculo / download)
--
-- O cartão-ponto (Secullum) é enviado pelo admin como 1 PDF multi-página
-- (1 página por operador/técnico). O sistema separa página a página, casa
-- cada página a uma pessoa (por nome) e disponibiliza a folha individual
-- para download. Nada é substituído: cada upload vira um lote permanente.
--
-- 2 tabelas:
--   PNT_LOTE         — 1 linha por upload (tipo, período, status, autor)
--   PNT_LOTE_PAGINA  — 1 linha por página (vínculo à pessoa + arquivo)
-- ============================================================

-- ── PNT_LOTE ──
CREATE TABLE PNT_LOTE (
    ID                VARCHAR2(36)  PRIMARY KEY,
    TIPO              VARCHAR2(20)  NOT NULL,
    DATA_INICIO       DATE          NOT NULL,
    DATA_FIM          DATE          NOT NULL,
    ARQUIVO_ORIGINAL  VARCHAR2(255) NOT NULL,
    TOTAL_PAGINAS     NUMBER(4)     NOT NULL,
    STATUS            VARCHAR2(20)  DEFAULT 'REVISAO' NOT NULL,
    CRIADO_POR_ID     VARCHAR2(36)  NOT NULL,
    PUBLICADO_EM      TIMESTAMP,
    CRIADO_EM         TIMESTAMP     NOT NULL,
    ATUALIZADO_EM     TIMESTAMP     NOT NULL,
    CONSTRAINT CK_PNT_LOTE_TIPO    CHECK (TIPO IN ('SEMANAL','MENSAL')),
    CONSTRAINT CK_PNT_LOTE_STATUS  CHECK (STATUS IN ('REVISAO','PUBLICADO')),
    CONSTRAINT CK_PNT_LOTE_PERIODO CHECK (DATA_FIM >= DATA_INICIO),
    CONSTRAINT FK_PNT_LOTE_ADMIN FOREIGN KEY (CRIADO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

CREATE INDEX IDX_PNT_LOTE_STATUS  ON PNT_LOTE (STATUS);
CREATE INDEX IDX_PNT_LOTE_PERIODO ON PNT_LOTE (DATA_INICIO, DATA_FIM);


-- ── PNT_LOTE_PAGINA ──
CREATE TABLE PNT_LOTE_PAGINA (
    ID              VARCHAR2(36)  PRIMARY KEY,
    LOTE_ID         VARCHAR2(36)  NOT NULL,
    NUMERO_PAGINA   NUMBER(4)     NOT NULL,
    NOME_EXTRAIDO   VARCHAR2(255),
    PESSOA_ID       VARCHAR2(36),
    PESSOA_TIPO     VARCHAR2(20),
    STATUS_MATCH    VARCHAR2(20)  DEFAULT 'PENDENTE' NOT NULL,
    ARQUIVO_PAGINA  VARCHAR2(255) NOT NULL,
    CRIADO_EM       TIMESTAMP     NOT NULL,
    ATUALIZADO_EM   TIMESTAMP     NOT NULL,
    CONSTRAINT UK_PNT_PAGINA_NUM   UNIQUE (LOTE_ID, NUMERO_PAGINA),
    CONSTRAINT CK_PNT_PAGINA_TIPO  CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO')),
    CONSTRAINT CK_PNT_PAGINA_MATCH CHECK (STATUS_MATCH IN ('AUTO','MANUAL','PENDENTE')),
    -- Vínculo coerente: ou totalmente pendente, ou totalmente preenchido.
    CONSTRAINT CK_PNT_PAGINA_VINCULO CHECK (
        (PESSOA_ID IS NULL     AND PESSOA_TIPO IS NULL     AND STATUS_MATCH = 'PENDENTE')
        OR (PESSOA_ID IS NOT NULL AND PESSOA_TIPO IS NOT NULL AND STATUS_MATCH IN ('AUTO','MANUAL'))
    ),
    CONSTRAINT FK_PNT_PAGINA_LOTE FOREIGN KEY (LOTE_ID) REFERENCES PNT_LOTE(ID) ON DELETE CASCADE
);

CREATE INDEX IDX_PNT_PAGINA_LOTE   ON PNT_LOTE_PAGINA (LOTE_ID);
CREATE INDEX IDX_PNT_PAGINA_PESSOA ON PNT_LOTE_PAGINA (PESSOA_ID);

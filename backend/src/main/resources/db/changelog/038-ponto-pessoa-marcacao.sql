-- ============================================================
-- 038 — Ponto: PNT_PESSOA_MARCACAO (marcação por pessoa-dia)
--
-- Marcações por funcionário-dia da planilha real (Q7/F#4):
-- À disposição, Atestado, Férias, Recesso, Licença médica.
-- 1 marcação por (pessoa, dia) — trocar tipo = update; desmarcar =
-- delete físico, como na marcação global (037).
-- Pessoa polimórfica PESSOA_ID+PESSOA_TIPO sem FK (padrão
-- PNT_LOTE_PAGINA); autoria em CRIADO_POR_ID (admin).
-- ============================================================

CREATE TABLE PNT_PESSOA_MARCACAO (
    ID             VARCHAR2(36)  PRIMARY KEY,
    PESSOA_ID      VARCHAR2(36)  NOT NULL,
    PESSOA_TIPO    VARCHAR2(20)  NOT NULL,
    DATA           DATE          NOT NULL,
    TIPO           VARCHAR2(20)  NOT NULL,
    CRIADO_POR_ID  VARCHAR2(36)  NOT NULL,
    CRIADO_EM      TIMESTAMP     NOT NULL,
    ATUALIZADO_EM  TIMESTAMP     NOT NULL,
    CONSTRAINT UK_PNT_PESMARC_PESSOA_DIA UNIQUE (PESSOA_ID, PESSOA_TIPO, DATA),
    CONSTRAINT CK_PNT_PESMARC_PESSOA_TIPO CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR')),
    CONSTRAINT CK_PNT_PESMARC_TIPO CHECK (TIPO IN ('A_DISPOSICAO','ATESTADO','FERIAS','RECESSO','LICENCA_MEDICA')),
    CONSTRAINT FK_PNT_PESMARC_ADMIN FOREIGN KEY (CRIADO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- Consulta da grade admin: categoria × range de mês.
CREATE INDEX IDX_PNT_PESMARC_TIPO_DATA ON PNT_PESSOA_MARCACAO (PESSOA_TIPO, DATA);

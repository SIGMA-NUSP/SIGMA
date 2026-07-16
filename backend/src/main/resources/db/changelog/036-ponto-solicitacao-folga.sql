-- ============================================================
-- 036 — Ponto: PNT_SOLICITACAO_FOLGA (banco de horas — livro-razão)
--
-- 1 linha por dia solicitado (C-5.3). A tabela É o livro-razão dos
-- débitos do banco de horas: MINUTOS_DEBITADOS congela 360/480 na
-- criação (Q3) e o saldo é sempre derivável da soma das linhas vivas
-- (PENDENTE/APROVADO) posteriores à âncora oficial (Q4). Rejeição e
-- cancelamento não são estorno: a linha apenas sai da soma.
-- Status com CANCELADO (Q19 — cancelamento pelo funcionário, sem
-- delete físico). Deliberação por admin (DELIBERADO_POR_ID/EM +
-- MOTIVO_REJEICAO na rejeição).
--
-- FBI única de "pedido vivo": impede 2 pedidos PENDENTE/APROVADO para
-- o mesmo (pessoa, dia); REJEITADO e CANCELADO liberam o dia para novo
-- pedido (padrão FBI do projeto: UQ_OPR_REGAUDIO_SALA_ABERTA).
-- ============================================================

CREATE TABLE PNT_SOLICITACAO_FOLGA (
    ID                 VARCHAR2(36)   PRIMARY KEY,
    PESSOA_ID          VARCHAR2(36)   NOT NULL,
    PESSOA_TIPO        VARCHAR2(20)   NOT NULL,
    DATA_FOLGA         DATE           NOT NULL,
    MINUTOS_DEBITADOS  NUMBER(4)      NOT NULL,
    STATUS             VARCHAR2(20)   DEFAULT 'PENDENTE' NOT NULL,
    DELIBERADO_POR_ID  VARCHAR2(36),
    DELIBERADO_EM      TIMESTAMP,
    MOTIVO_REJEICAO    VARCHAR2(1000),
    CRIADO_EM          TIMESTAMP      NOT NULL,
    ATUALIZADO_EM      TIMESTAMP      NOT NULL,
    CONSTRAINT CK_PNT_SOLF_PESSOA_TIPO CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR')),
    CONSTRAINT CK_PNT_SOLF_STATUS CHECK (STATUS IN ('PENDENTE','APROVADO','REJEITADO','CANCELADO')),
    -- Coerência status × deliberação: só APROVADO/REJEITADO têm deliberador.
    CONSTRAINT CK_PNT_SOLF_DELIB CHECK (
        (STATUS IN ('PENDENTE','CANCELADO') AND DELIBERADO_POR_ID IS NULL)
        OR (STATUS IN ('APROVADO','REJEITADO') AND DELIBERADO_POR_ID IS NOT NULL)
    ),
    CONSTRAINT FK_PNT_SOLF_ADMIN FOREIGN KEY (DELIBERADO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- Pedido vivo único por (pessoa, dia) — última linha de defesa contra corrida.
CREATE UNIQUE INDEX UQ_PNT_SOLF_VIVA ON PNT_SOLICITACAO_FOLGA (
    CASE WHEN STATUS IN ('PENDENTE','APROVADO')
         THEN PESSOA_ID || '|' || PESSOA_TIPO || '|' || TO_CHAR(DATA_FOLGA, 'YYYYMMDD') END
);

-- Ordenação default do admin (D-4.1: pendentes primeiro, por dia).
CREATE INDEX IDX_PNT_SOLF_STATUS_DATA ON PNT_SOLICITACAO_FOLGA (STATUS, DATA_FOLGA);
-- "Minhas Solicitações" + soma do saldo + linha Folgas da grade.
CREATE INDEX IDX_PNT_SOLF_PESSOA ON PNT_SOLICITACAO_FOLGA (PESSOA_ID, PESSOA_TIPO, DATA_FOLGA);

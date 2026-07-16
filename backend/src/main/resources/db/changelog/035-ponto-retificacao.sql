-- ============================================================
-- 035 — Ponto: PNT_RETIFICACAO (retificação de folha de ponto)
--
-- 1 linha por (pessoa, dia): horários corrigidos que o funcionário
-- declara para um dia de uma folha oficial PUBLICADA (Q2 — sem folha
-- publicada não há retificação; PAGINA_ID obrigatório = proveniência).
-- Sem edição nem exclusão na v1 (Q1) — a UK garante 1 por dia.
-- Horários 'HH:MM' VARCHAR2(5), padrão de PES_*.HORARIO_TRABALHO_* (Q8).
-- Pares completos 0/2/4 — 1 ou 3 horários são rejeitados (Q32),
-- e o 2º par exige o 1º (sem "buraco").
-- ============================================================

CREATE TABLE PNT_RETIFICACAO (
    ID             VARCHAR2(36)   PRIMARY KEY,
    PESSOA_ID      VARCHAR2(36)   NOT NULL,
    PESSOA_TIPO    VARCHAR2(20)   NOT NULL,
    PAGINA_ID      VARCHAR2(36)   NOT NULL,
    DATA           DATE           NOT NULL,
    ENT1           VARCHAR2(5),
    SAI1           VARCHAR2(5),
    ENT2           VARCHAR2(5),
    SAI2           VARCHAR2(5),
    OBSERVACOES    VARCHAR2(2000),
    CRIADO_EM      TIMESTAMP      NOT NULL,
    ATUALIZADO_EM  TIMESTAMP      NOT NULL,
    CONSTRAINT UK_PNT_RETIF_PESSOA_DIA UNIQUE (PESSOA_ID, PESSOA_TIPO, DATA),
    CONSTRAINT CK_PNT_RETIF_PESSOA_TIPO CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR')),
    -- Pares coerentes (Q32): cada par Ent./Saí. completo ou vazio; par 2 só com par 1.
    CONSTRAINT CK_PNT_RETIF_PARES CHECK (
        ((ENT1 IS NULL AND SAI1 IS NULL) OR (ENT1 IS NOT NULL AND SAI1 IS NOT NULL))
        AND ((ENT2 IS NULL AND SAI2 IS NULL) OR (ENT2 IS NOT NULL AND SAI2 IS NOT NULL))
        AND NOT (ENT1 IS NULL AND ENT2 IS NOT NULL)
    ),
    CONSTRAINT FK_PNT_RETIF_PAGINA FOREIGN KEY (PAGINA_ID) REFERENCES PNT_LOTE_PAGINA(ID)
);

-- Consulta da grade admin: categoria × range de mês (P1-1).
CREATE INDEX IDX_PNT_RETIF_TIPO_DATA ON PNT_RETIFICACAO (PESSOA_TIPO, DATA);

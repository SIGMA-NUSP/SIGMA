-- ============================================================
-- 040 — Ponto: PNT_REGISTRO_MANUAL (batidas manuais + comprovantes)
--
-- 1 linha por (pessoa, dia) (handoff 2026-06-30 §3.2). Feature
-- desativada na v1 (card oculto — P3/T-2.1): schema e entidades já
-- entram (Q9) para a reativação futura não exigir migration.
-- Horas 'HH:MM' VARCHAR2(5) (Q8 — sobrepõe o VARCHAR2(8) do handoff).
-- TOTAL_DIA_MIN derivado pelo motor de cálculo (penhasco ±5/±9,
-- ×2/×1; dia sem par completo = 0). Fotos = caminho relativo
-- 'ponto/comprovantes/<uuid>' (padrão ARQUIVO_PAGINA), 1 por batida.
-- Jornada NÃO fica aqui: vem de PES_*.CARGA_HORARIA.
-- ============================================================

CREATE TABLE PNT_REGISTRO_MANUAL (
    ID             VARCHAR2(36)   PRIMARY KEY,
    PESSOA_ID      VARCHAR2(36)   NOT NULL,
    PESSOA_TIPO    VARCHAR2(20)   NOT NULL,
    DATA           DATE           NOT NULL,
    ENT1           VARCHAR2(5),
    SAI1           VARCHAR2(5),
    ENT2           VARCHAR2(5),
    SAI2           VARCHAR2(5),
    TOTAL_DIA_MIN  NUMBER(6)      DEFAULT 0 NOT NULL,
    FOTO_ENT1      VARCHAR2(255),
    FOTO_SAI1      VARCHAR2(255),
    FOTO_ENT2      VARCHAR2(255),
    FOTO_SAI2      VARCHAR2(255),
    CRIADO_EM      TIMESTAMP      NOT NULL,
    ATUALIZADO_EM  TIMESTAMP      NOT NULL,
    CONSTRAINT UK_PNT_REGMAN_PESSOA_DIA UNIQUE (PESSOA_ID, PESSOA_TIPO, DATA),
    CONSTRAINT CK_PNT_REGMAN_PESSOA_TIPO CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR'))
);

-- Sem índice extra (PESSOA_ID, PESSOA_TIPO): seria prefixo-redundante com a
-- UK acima (regra Q10 do db-query-optimization-plan) — o extrato mensal usa
-- o próprio índice da UK.

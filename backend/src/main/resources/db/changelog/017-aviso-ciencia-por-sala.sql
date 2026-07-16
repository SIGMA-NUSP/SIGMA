-- ============================================================
-- 017 — Ciência de aviso por SALA
--
-- Correção: a ciência era registrada por (cadastro, pessoa). Como um
-- cadastro pode cobrir várias salas, marcar ciência numa sala "quitava"
-- o aviso em todas. Agora a ciência é por (cadastro, sala, pessoa):
-- o destinatário precisa dar ciência em CADA sala que verifica.
--
-- Para tipos futuros sem sala (ex.: PESSOAL), SALA_ID fica nulo e a
-- unicidade volta a ser por (cadastro, pessoa) — o NVL no índice cobre.
-- ============================================================

ALTER TABLE FRM_AVISO_CIENCIA ADD (SALA_ID NUMBER(5));

-- O modelo da ciência mudou de significado (passou a incluir a sala).
-- Registros antigos (por cadastro, sem sala) ficam inválidos — limpa.
-- Em produção a tabela está vazia (modelo novo nunca foi usado lá).
DELETE FROM FRM_AVISO_CIENCIA;

-- Substitui os índices únicos parciais para incluir a sala.
DROP INDEX UK_FRM_AVISO_CIE_OP;
DROP INDEX UK_FRM_AVISO_CIE_TEC;

CREATE UNIQUE INDEX UK_FRM_AVISO_CIE_OP
    ON FRM_AVISO_CIENCIA (CASE WHEN OPERADOR_ID IS NOT NULL
        THEN CADASTRO_ID || '|' || NVL(TO_CHAR(SALA_ID), '-') || '|' || OPERADOR_ID END);
CREATE UNIQUE INDEX UK_FRM_AVISO_CIE_TEC
    ON FRM_AVISO_CIENCIA (CASE WHEN TECNICO_ID IS NOT NULL
        THEN CADASTRO_ID || '|' || NVL(TO_CHAR(SALA_ID), '-') || '|' || TECNICO_ID END);

ALTER TABLE FRM_AVISO_CIENCIA
    ADD CONSTRAINT FK_FRM_AVISO_CIE_SALA FOREIGN KEY (SALA_ID) REFERENCES CAD_SALA(ID);

CREATE INDEX IDX_FRM_AVISO_CIE_SALA ON FRM_AVISO_CIENCIA (SALA_ID);

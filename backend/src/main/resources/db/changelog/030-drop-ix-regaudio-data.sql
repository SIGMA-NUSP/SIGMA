-- ============================================================
-- 030 — Drop de IX_OPR_REGAUDIO_DATA (DATA) em OPR_REGISTRO_AUDIO
--
-- Prefixo-redundante: IX_OPR_REGAUDIO_DATA_SALA (DATA, SALA_ID) tem DATA como
-- coluna-prefixo, servindo qualquer predicado só por DATA (fetchRdsRows
-- `ra.DATA >= ? AND ra.DATA < ?` via RANGE SCAN; facetas de data; RDS anos/meses).
-- Saldo −1 índice.
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 234).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q10 (Bloco A / Etapa A3).
-- ============================================================

DROP INDEX IX_OPR_REGAUDIO_DATA;

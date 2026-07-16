-- ============================================================
-- 029 — Drop de IX_FRM_CHECKLIST_DATA (DATA_OPERACAO) em FRM_CHECKLIST
--
-- Prefixo-redundante: IX_FRM_CHECKLIST_DATA_SALA (DATA_OPERACAO, SALA_ID) tem
-- DATA_OPERACAO como coluna-prefixo, servindo qualquer predicado só por
-- DATA_OPERACAO (período de listChecklists via RANGE SCAN; faceta de data e
-- findChecklistDoDia). Saldo −1 índice.
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 148).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q10 (Bloco A / Etapa A3).
-- ============================================================

DROP INDEX IX_FRM_CHECKLIST_DATA;

-- ============================================================
-- 027 — Drop de IX_FRM_RESP_STATUS (STATUS) em FRM_CHECKLIST_RESPOSTA
--
-- Redundante após o composto (CHECKLIST_ID, STATUS) do changelog 026: STATUS
-- tem apenas 3 valores e nenhuma query da app filtra STATUS sem CHECKLIST_ID.
-- Saldo −1 índice na tabela mais quente de escrita (~12k linhas).
-- Rollback: recria o índice antigo, idêntico ao baseline (linha 209).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q8 (Bloco A / Etapa A2).
-- ============================================================

DROP INDEX IX_FRM_RESP_STATUS;

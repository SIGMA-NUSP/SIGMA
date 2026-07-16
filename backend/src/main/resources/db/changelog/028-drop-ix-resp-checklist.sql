-- ============================================================
-- 028 — Drop de IX_FRM_RESP_CHECKLIST (CHECKLIST_ID) em FRM_CHECKLIST_RESPOSTA
--
-- Prefixo-redundante: o acesso por CHECKLIST_ID passa a ser servido pelo
-- composto (CHECKLIST_ID, STATUS) do changelog 026 e/ou pelo UNIQUE já
-- existente UQ_FRM_RESP_CHECKLIST_ITEM (CHECKLIST_ID, ITEM_TIPO_ID) — ambos
-- têm CHECKLIST_ID como coluna-prefixo. Saldo −1 índice na tabela mais quente
-- de escrita (~12k linhas).
-- Rollback: recria o índice antigo, idêntico ao baseline (linha 207).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q8 (Bloco A / Etapa A2).
-- ============================================================

DROP INDEX IX_FRM_RESP_CHECKLIST;

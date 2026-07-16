-- ============================================================
-- 032 — Drop de IX_CHKOP_CHECKLIST (CHECKLIST_ID) em FRM_CHECKLIST_OPERADOR
--
-- Prefixo-redundante com o composto (CHECKLIST_ID, OPERADOR_ID) do changelog 031,
-- que serve o mesmo acesso por CHECKLIST_ID e ainda cobre os EXISTS de ownership
-- index-only. Neutro em contagem de índices (troca 1 por 1).
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 410).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q11 (Bloco A / Etapa A4).
-- ============================================================

DROP INDEX IX_CHKOP_CHECKLIST;

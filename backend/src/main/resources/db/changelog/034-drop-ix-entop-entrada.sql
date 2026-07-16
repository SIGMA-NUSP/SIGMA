-- ============================================================
-- 034 — Drop de IX_ENTOP_ENTRADA (ENTRADA_ID) em OPR_ENTRADA_OPERADOR
--
-- Prefixo-redundante com o composto (ENTRADA_ID, OPERADOR_ID) do changelog 033,
-- que serve o mesmo acesso por ENTRADA_ID e ainda cobre os EXISTS de ownership
-- index-only. Neutro em contagem de índices (troca 1 por 1); IX_ENTOP_OPERADOR
-- permanece.
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 427).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q11 (Bloco A / Etapa A4).
-- ============================================================

DROP INDEX IX_ENTOP_ENTRADA;

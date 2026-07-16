-- ============================================================
-- 033 — Índice composto NÃO-ÚNICO (ENTRADA_ID, OPERADOR_ID) em OPR_ENTRADA_OPERADOR
--
-- Substitui IX_ENTOP_ENTRADA (ENTRADA_ID): torna os EXISTS de ownership de
-- operação (listMinhasOperacoes OD-4, validarPermissaoEdicaoEntrada OP-4)
-- index-only e serve, pelo prefixo ENTRADA_ID, o acesso hoje coberto pelo índice
-- antigo (opRows, COUNT, facetas). Criado ANTES do drop 034.
-- IX_ENTOP_OPERADOR (OPERADOR_ID) é mantido (cobre a outra FK).
--
-- ⚠️ NÃO-ÚNICO de propósito: editarEntrada recria a junction a partir do payload
-- sem deduplicar → (ENTRADA_ID, OPERADOR_ID) pode ter duplicata transitória;
-- UNIQUE mudaria comportamento (gotcha 12 / Q11).
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 427).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q11 (Bloco A / Etapa A4).
-- ============================================================

CREATE INDEX IX_ENTOP_ENTRADA_OP ON OPR_ENTRADA_OPERADOR (ENTRADA_ID, OPERADOR_ID);

-- ============================================================
-- 025 — Unique index (SALA_ID, ITEM_TIPO_ID) em FRM_CHECKLIST_SALA_CONFIG
--
-- Formaliza um invariante que o código JÁ assume:
--   ChecklistSalaConfigRepository.findBySalaIdAndItemTipoId retorna Optional
--   (uma config por par sala×item) e os LEFT JOIN de detalhe de checklist
--   (admin e operador) contam com no máximo uma linha por (sala, item) — uma
--   duplicata multiplicaria linhas no JOIN / lançaria
--   IncorrectResultSizeDataAccessException.
-- Bônus: primeiro índice de acesso por SALA_ID da tabela (antes: só a PK em ID).
--
-- Pré-verificação de duplicatas (SALA_ID, ITEM_TIPO_ID) executada em produção
-- e homolog em 2026-07-07: 0 duplicatas em ambos.
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q9 (Bloco A / Etapa A1).
-- ============================================================

CREATE UNIQUE INDEX UQ_FRM_SC_SALA_ITEM ON FRM_CHECKLIST_SALA_CONFIG (SALA_ID, ITEM_TIPO_ID);

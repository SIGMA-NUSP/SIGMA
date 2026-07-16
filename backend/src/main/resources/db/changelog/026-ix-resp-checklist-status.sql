-- ============================================================
-- 026 — Índice composto (CHECKLIST_ID, STATUS) em FRM_CHECKLIST_RESPOSTA
--
-- Substitui o acesso hoje coberto por IX_FRM_RESP_STATUS (STATUS puro, 3
-- valores): nenhuma query da app filtra STATUS sem CHECKLIST_ID — CL_STATUS_EXPR
-- (duplo EXISTS de listChecklists), QTDE_OK/QTDE_FALHA e findChecklistDoDia são
-- todos EXISTS/COUNT correlacionados por (CHECKLIST_ID, STATUS). Com o composto,
-- esses viram index-only. Pelo prefixo CHECKLIST_ID também cobre o acesso hoje
-- servido por IX_FRM_RESP_CHECKLIST (dropado no changelog 028 como redundante).
--
-- Criado ANTES dos drops 027/028 — janela sem cobertura zero.
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q8 (Bloco A / Etapa A2).
-- ============================================================

CREATE INDEX IX_FRM_RESP_CHECKLIST_STATUS ON FRM_CHECKLIST_RESPOSTA (CHECKLIST_ID, STATUS);

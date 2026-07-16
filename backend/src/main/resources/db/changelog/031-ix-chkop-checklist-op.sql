-- ============================================================
-- 031 — Índice composto NÃO-ÚNICO (CHECKLIST_ID, OPERADOR_ID) em FRM_CHECKLIST_OPERADOR
--
-- Substitui IX_CHKOP_CHECKLIST (CHECKLIST_ID): torna os EXISTS de ownership de
-- checklist (listMeusChecklists OD-2, validarPermissaoEdicao CK-1) index-only e
-- serve, pelo prefixo CHECKLIST_ID, o acesso hoje coberto pelo índice antigo
-- (opRows do detalhe, COUNT, facetas). Criado ANTES do drop 032.
--
-- ⚠️ NÃO-ÚNICO de propósito: o mesmo operador pode figurar em CABINE e PLENARIO
-- (papéis distintos) no mesmo checklist → (CHECKLIST_ID, OPERADOR_ID) admite
-- duplicata; UNIQUE mudaria comportamento (gotcha 12 / Q11).
-- Rollback: recria o índice antigo, idêntico ao baseline (001-baseline linha 410).
-- Ref.: db-query-optimization-plan-2026-07.md — achado Q11 (Bloco A / Etapa A4).
-- ============================================================

CREATE INDEX IX_CHKOP_CHECKLIST_OP ON FRM_CHECKLIST_OPERADOR (CHECKLIST_ID, OPERADOR_ID);

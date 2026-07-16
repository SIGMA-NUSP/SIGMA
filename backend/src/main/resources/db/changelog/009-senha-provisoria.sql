-- ============================================================
-- 009 — SENHA_PROVISORIA
--
-- Adiciona coluna SENHA_PROVISORIA (NUMBER(1) — 0/1) nas
-- tabelas de usuário. Quando 1, o usuário é forçado a trocar
-- a senha no próximo login antes de acessar o sistema.
--
-- Default 0: usuários existentes não são afetados.
-- Admins recém-cadastrados pelo /api/admin/crud/admins
-- entram com SENHA_PROVISORIA=1.
-- ============================================================
ALTER TABLE PES_ADMINISTRADOR ADD (SENHA_PROVISORIA NUMBER(1) DEFAULT 0 NOT NULL);
ALTER TABLE PES_OPERADOR      ADD (SENHA_PROVISORIA NUMBER(1) DEFAULT 0 NOT NULL);
ALTER TABLE PES_TECNICO       ADD (SENHA_PROVISORIA NUMBER(1) DEFAULT 0 NOT NULL);

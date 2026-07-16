-- ============================================================
-- 019 — Ponto: permitir vincular página a um ADMINISTRADOR
--
-- Parte dos terceirizados do cartão-ponto ocupa cargos especiais
-- (Supervisor Técnico, Controlador de Operações) e está cadastrada em
-- PES_ADMINISTRADOR, não em operador/técnico. Amplia o CHECK de
-- PNT_LOTE_PAGINA.PESSOA_TIPO para aceitar 'ADMINISTRADOR'.
-- ============================================================

ALTER TABLE PNT_LOTE_PAGINA DROP CONSTRAINT CK_PNT_PAGINA_TIPO;

ALTER TABLE PNT_LOTE_PAGINA ADD CONSTRAINT CK_PNT_PAGINA_TIPO
    CHECK (PESSOA_TIPO IN ('OPERADOR','TECNICO','ADMINISTRADOR'));

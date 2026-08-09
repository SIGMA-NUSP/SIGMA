-- ============================================================
-- 053 — Ponto: lote oculto (acervo histórico)
--
-- Um lote pode ser marcado como OCULTO no envio: as folhas dele alimentam o
-- sumário de ocorrências e o BI como as de qualquer lote publicado, mas não
-- aparecem para os funcionários nem para os administradores comuns — só o
-- admin master o vê (com um selo). A folha oculta também nunca ancora o
-- banco de horas: o saldo continua vindo da folha visível mais recente.
-- ============================================================

ALTER TABLE PNT_LOTE ADD (OCULTO NUMBER(1) DEFAULT 0 NOT NULL);

ALTER TABLE PNT_LOTE ADD CONSTRAINT CK_PNT_LOTE_OCULTO CHECK (OCULTO IN (0, 1));

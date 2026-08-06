-- ============================================================
-- 049 — Ponto: CATEGORIA da folha mensal em PNT_LOTE
--
-- A folha MENSAL passa a ter duas naturezas:
--
--   PREVIA     — abre o mês para conferência: pode ser retificada e é
--                substituída pela definitiva quando ela chega.
--   DEFINITIVA — fecha o mês de vez: não se retifica, entra por cima da
--                prévia e não admite outra folha da pessoa naquele mês.
--
-- A categoria só existe para folha mensal. A SEMANAL é cumulativa (cada uma
-- contém as anteriores) e não tem essa distinção — fica com a coluna vazia,
-- amarração garantida pelo CHECK.
--
-- A coluna nasce vazia e os lotes mensais já enviados recebem PREVIA, que é a
-- natureza retificável — o mesmo comportamento que tinham antes de a escolha
-- existir.
-- ============================================================

ALTER TABLE PNT_LOTE ADD (CATEGORIA VARCHAR2(10));

-- Preserva o comportamento vigente dos lotes mensais já enviados.
UPDATE PNT_LOTE SET CATEGORIA = 'PREVIA' WHERE TIPO = 'MENSAL' AND CATEGORIA IS NULL;

-- Domínio da categoria e coerência com o tipo: mensal sempre classificada, semanal nunca.
ALTER TABLE PNT_LOTE ADD CONSTRAINT CK_PNT_LOTE_CATEGORIA CHECK (
    (TIPO = 'MENSAL'  AND CATEGORIA IN ('PREVIA','DEFINITIVA'))
 OR (TIPO = 'SEMANAL' AND CATEGORIA IS NULL)
);

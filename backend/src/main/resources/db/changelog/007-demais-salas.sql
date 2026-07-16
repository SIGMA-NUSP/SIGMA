-- ============================================================
-- 007 — "Demais Salas"
--
-- Cria sala genérica para reuniões avulsas em locais fora do
-- catálogo fixo. Quando esta sala é selecionada no ROA, o
-- operador deve informar o nome real da sala.
--
-- O nome livre fica na sessão (OPR_REGISTRO_AUDIO), pois é
-- único por sessão mesmo com múltiplos operadores.
-- ============================================================
ALTER TABLE OPR_REGISTRO_AUDIO ADD (NOME_DEMAIS_SALAS VARCHAR2(255));

INSERT INTO CAD_SALA (ID, NOME, ATIVO, CRIADO_EM, ATUALIZADO_EM, ORDEM, MULTI_OPERADOR)
VALUES (11, 'Demais Salas', 1, SYSTIMESTAMP, SYSTIMESTAMP, 11, 0);

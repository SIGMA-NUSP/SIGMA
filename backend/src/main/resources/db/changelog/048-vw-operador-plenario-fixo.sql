-- ============================================================
-- 048 — View pública de operadores: marca de fixo do Plenário Principal
--
-- Os rankings por operador comparam quem disputa as mesmas escalas. Os
-- operadores fixos do Plenário Principal trabalham em regime próprio, com
-- vários deles simultaneamente na mesma sessão, e entram nos rankings gerais
-- com um volume que não é comparável ao dos demais — por isso são separados
-- num bloco só deles.
--
-- A view é a única porta do usuário de leitura do BI para os dados de pessoa
-- (a tabela de operadores guarda credenciais e nunca é exposta). Sem a marca
-- aqui, o recorte não tem como existir do lado do BI.
--
-- Substituição preserva os privilégios já concedidos sobre a view; o GRANT
-- abaixo é reafirmado para que um banco criado do zero nasça completo.
-- ============================================================

CREATE OR REPLACE VIEW VW_OPERADOR_PUBLICO AS
SELECT
    ID,
    NOME_COMPLETO,
    NOME_EXIBICAO,
    EMAIL,
    PLENARIO_PRINCIPAL_FIXO
FROM PES_OPERADOR;

GRANT SELECT ON VW_OPERADOR_PUBLICO TO NUSP_REPORTS;

-- ============================================================
-- 048 — Leitura do BI sobre as tabelas do dashboard de operadores
--
-- O usuário de leitura do BI consulta as tabelas pelo nome qualificado, sem
-- sinônimos: sem o privilégio explícito a consulta falha como se a tabela não
-- existisse. As permissões destas tabelas foram concedidas à mão ao longo do
-- tempo e nunca ficaram versionadas — reafirmá-las aqui é o que garante que um
-- banco criado do zero, e os demais ambientes, atendam o dashboard por igual.
--
-- Conceder um privilégio que já existe não altera nada e não falha.
-- ============================================================

GRANT SELECT ON OPR_REGISTRO_ENTRADA TO NUSP_REPORTS;
GRANT SELECT ON OPR_REGISTRO_AUDIO TO NUSP_REPORTS;
GRANT SELECT ON OPR_ENTRADA_OPERADOR TO NUSP_REPORTS;
GRANT SELECT ON OPR_SUSPENSAO TO NUSP_REPORTS;
GRANT SELECT ON OPR_ESCALA_SEMANAL TO NUSP_REPORTS;
GRANT SELECT ON OPR_ESCALA_FUNCAO TO NUSP_REPORTS;
GRANT SELECT ON FRM_CHECKLIST TO NUSP_REPORTS;
GRANT SELECT ON FRM_CHECKLIST_OPERADOR TO NUSP_REPORTS;
GRANT SELECT ON CAD_SALA TO NUSP_REPORTS;
GRANT SELECT ON CAD_COMISSAO TO NUSP_REPORTS;

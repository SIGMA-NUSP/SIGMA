-- ============================================================
-- 010 — PLENARIO_PRINCIPAL_FIXO em PES_OPERADOR
--
-- Flag NUMBER(1) que identifica os operadores fixos do
-- Plenário Principal. Distinta da flag existente
-- PLENARIO_PRINCIPAL, que indica apenas aptidão a operar
-- naquele plenário.
--
-- Operadores fixos enxergam, na home, todos os ROAs e
-- Verificações de Sala do Plenário Principal em modo de
-- leitura, além dos que criaram ou nos quais participam
-- como operador adicional.
-- ============================================================
ALTER TABLE PES_OPERADOR ADD (PLENARIO_PRINCIPAL_FIXO NUMBER(1) DEFAULT 0 NOT NULL);

UPDATE PES_OPERADOR
   SET PLENARIO_PRINCIPAL_FIXO = 1
 WHERE USERNAME IN ('antonioc', '05171568146', 'siqdantas', 'marcio',
                    '04625551196', 'sandersom', 'williamsouz');

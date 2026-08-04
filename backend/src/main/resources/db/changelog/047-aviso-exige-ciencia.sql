-- ============================================================
-- 047 — Avisos: EXIGE_CIENCIA em FRM_AVISO_CADASTRO
--
-- A exigência de ciência do destinatário deixa de ser fixa por tipo e passa a
-- ser escolha do admin, gravada no cadastro. Dois tipos não têm escolha e por
-- isso ficam amarrados a um único valor:
--
--   VERIFICACAO — sempre exige (1): a ciência é por sala e acontece dentro do
--     fluxo de entrada na sala, junto do checklist.
--   AGENDA — nunca exige (0): usa a mesma tabela de ciência com a semântica de
--     "visto na exibição", registrado sem clique do destinatário.
--
-- ESCALA, PESSOAL e GERAL são configuráveis: qualquer um dos dois valores é
-- válido, e o form escolhe.
--
-- A coluna nasce vazia, recebe de cada aviso já gravado o comportamento que ele
-- tinha quando a exigência vinha do tipo, e só então vira obrigatória.
-- ============================================================

ALTER TABLE FRM_AVISO_CADASTRO ADD (EXIGE_CIENCIA NUMBER(1));

-- Preserva o comportamento vigente de cada aviso existente.
UPDATE FRM_AVISO_CADASTRO
   SET EXIGE_CIENCIA = CASE WHEN TIPO IN ('VERIFICACAO','ESCALA','PESSOAL') THEN 1 ELSE 0 END;

ALTER TABLE FRM_AVISO_CADASTRO MODIFY (EXIGE_CIENCIA NUMBER(1) NOT NULL);

-- Domínio 0/1 + coerência tipo↔ciência dos dois tipos sem escolha.
ALTER TABLE FRM_AVISO_CADASTRO ADD CONSTRAINT CK_FRM_AVISO_CAD_CIENCIA CHECK (
    EXIGE_CIENCIA IN (0,1)
    AND ((TIPO = 'VERIFICACAO' AND EXIGE_CIENCIA = 1)
      OR (TIPO = 'AGENDA' AND EXIGE_CIENCIA = 0)
      OR TIPO IN ('ESCALA','PESSOAL','GERAL'))
);

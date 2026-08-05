-- ============================================================
-- 048 — Metabase: CONTEXTO no catálogo de dashboards
--
-- O catálogo passa a dizer em QUE PÁGINA cada dashboard é embutido:
--
--   PAINEL — o dashboard do Painel Administrativo, página inicial dos admins.
--   GESTAO_PESSOAS — os indicadores por operador da página de Gestão de Pessoas.
--
-- Sem esse recorte as duas páginas leem o mesmo catálogo e acabam embutindo a
-- mesma primeira linha. Todo dashboard já cadastrado é do Painel, e o valor
-- padrão da coluna os mantém exatamente onde estão — a migração não muda o que
-- nenhuma página exibe hoje.
-- ============================================================

ALTER TABLE INT_METABASE_DASHBOARD ADD (CONTEXTO VARCHAR2(30) DEFAULT 'PAINEL' NOT NULL);

-- Domínio fechado de propósito: o catálogo é semeado por INSERT manual em cada
-- ambiente, e um valor digitado errado faria o dashboard sumir da página sem
-- erro nenhum. Página nova exige changeset novo.
ALTER TABLE INT_METABASE_DASHBOARD ADD CONSTRAINT CK_INT_MB_DASH_CONTEXTO CHECK (
    CONTEXTO IN ('PAINEL','GESTAO_PESSOAS')
);

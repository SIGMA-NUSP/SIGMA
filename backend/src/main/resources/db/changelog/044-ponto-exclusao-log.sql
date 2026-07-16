-- ============================================================
-- 044 — Ponto: PNT_EXCLUSAO_LOG (trilha da exclusão de publicações)
--
-- O admin master pode EXCLUIR um lote de folhas de ponto (ou uma folha
-- individual dele) — e a exclusão é PROFUNDA: leva as retificações
-- ancoradas naquelas páginas, os avisos pessoais que a publicação criou,
-- os PDFs e a âncora do banco de horas (F59). Nada disso volta.
--
-- Esta tabela é a única memória do que houve. Por isso o registro é um
-- SNAPSHOT (RESUMO: JSON com tipo, período, pessoas e contagens REAIS do
-- que morreu), não uma referência: as linhas apontadas já não existem
-- quando alguém for ler a trilha. Pela mesma razão LOTE_ID e PAGINA_ID
-- ficam SEM FK — a FK exigiria justamente a linha que a exclusão apagou
-- (o mesmo idioma de PNT_BANCO_SALDO.ANCORA_PAGINA_ID, mera proveniência).
--
-- Sem UI de leitura nesta entrega: a consulta é por SQL.
-- ============================================================

CREATE TABLE PNT_EXCLUSAO_LOG (
    ID               VARCHAR2(36)  PRIMARY KEY,
    -- LOTE: o lote inteiro (com todas as páginas). PAGINA: uma folha do lote.
    ESCOPO           VARCHAR2(10)  NOT NULL,
    LOTE_ID          VARCHAR2(36)  NOT NULL,
    PAGINA_ID        VARCHAR2(36),
    EXCLUIDO_POR_ID  VARCHAR2(36)  NOT NULL,
    EXCLUIDO_EM      TIMESTAMP     NOT NULL,
    -- Snapshot do que morreu (JSON) — a trilha não pode depender do que apagou.
    RESUMO           CLOB          NOT NULL,
    CRIADO_EM        TIMESTAMP     NOT NULL,
    ATUALIZADO_EM    TIMESTAMP     NOT NULL,
    CONSTRAINT CK_PNT_EXCLUSAO_ESCOPO CHECK (ESCOPO IN ('LOTE','PAGINA')),
    -- Coerência escopo ↔ alvo: exclusão de lote não nomeia página; a de página, sim.
    CONSTRAINT CK_PNT_EXCLUSAO_PAGINA CHECK (
        (ESCOPO = 'LOTE'   AND PAGINA_ID IS NULL)
        OR (ESCOPO = 'PAGINA' AND PAGINA_ID IS NOT NULL)
    ),
    CONSTRAINT FK_PNT_EXCLUSAO_ADMIN FOREIGN KEY (EXCLUIDO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- Leitura natural da trilha: "o que foi excluído, do mais recente para o mais antigo".
CREATE INDEX IDX_PNT_EXCLUSAO_EM ON PNT_EXCLUSAO_LOG (EXCLUIDO_EM);

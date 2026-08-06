-- ============================================================
-- 050 — Ponto: PNT_FOLHA_LINHA (a tabela da folha, como o PDF a imprime)
--
-- Cada linha-dia da folha publicada passa a existir no banco, com as 7 colunas
-- do cartão Secullum (DIA, ENT.1, SAÍ.1, ENT.2, SAÍ.2, TOTALDIA, BANCO) copiadas
-- VERBATIM: nada é traduzido, expandido ou convertido — é o texto que o
-- funcionário lê na folha dele.
--
-- Duas colunas DERIVADAS acompanham o verbatim, para que as consultas não
-- precisem reinterpretar texto:
--
--   DATA       — a data-calendário do dia (o verbatim traz "dd/mm/aa - seg").
--   OCORRENCIA — o texto de status do dia (FERNC, DISPOSI, BancN, Falta…),
--                nulo no dia de batidas normais.
--
-- As derivadas nunca substituem o verbatim: uma folha malformada grava a linha
-- com as derivadas vazias em vez de perder o registro.
--
-- ORDEM é a posição física da linha na página — é ela, e não a data, que
-- identifica a linha (a mesma data pode reaparecer numa folha torta).
-- ============================================================

CREATE TABLE PNT_FOLHA_LINHA (
    ID             VARCHAR2(36)      PRIMARY KEY,
    PAGINA_ID      VARCHAR2(36)      NOT NULL,
    ORDEM          NUMBER(4)         NOT NULL,
    DIA_VERBATIM   VARCHAR2(30 CHAR),
    ENT1           VARCHAR2(20 CHAR),
    SAI1           VARCHAR2(20 CHAR),
    ENT2           VARCHAR2(20 CHAR),
    SAI2           VARCHAR2(20 CHAR),
    TOTAL_DIA      VARCHAR2(12),
    BANCO          VARCHAR2(12),
    DATA           DATE,
    OCORRENCIA     VARCHAR2(30 CHAR),
    CRIADO_EM      TIMESTAMP         NOT NULL,
    ATUALIZADO_EM  TIMESTAMP         NOT NULL,
    CONSTRAINT UK_PNT_FOLHALIN_ORDEM UNIQUE (PAGINA_ID, ORDEM),
    -- Morta a folha, morrem as linhas dela: o mesmo cascade que liga a página ao lote.
    CONSTRAINT FK_PNT_FOLHALIN_PAGINA FOREIGN KEY (PAGINA_ID)
        REFERENCES PNT_LOTE_PAGINA(ID) ON DELETE CASCADE
);

-- Busca por período (sumário de ocorrências por mês). A busca por página já é servida
-- pelo índice da UK, que começa em PAGINA_ID.
CREATE INDEX IDX_PNT_FOLHALIN_DATA ON PNT_FOLHA_LINHA (DATA);

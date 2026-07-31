-- ============================================================
-- 046 — Ponto: PNT_TIPO_MARC_EXCLUSAO_LOG (trilha da exclusão de tipo)
--
-- Excluir um tipo de ocorrência apaga, em cascata explícita, TODAS as marcações
-- feitas com ele — dias globais e dias de pessoa. Nada disso volta, e recriar um
-- tipo de mesmo nome depois não religa marcação nenhuma.
--
-- Esta tabela é a única memória do que houve. Por isso o registro é um SNAPSHOT
-- (NOME/BADGE/ESCOPO copiados + RESUMO em JSON com as contagens REAIS da
-- transação) e TIPO_ID fica SEM FK: a linha que ele aponta acabou de morrer.
--
-- Sem UI de leitura: a consulta é por SQL.
-- ============================================================

CREATE TABLE PNT_TIPO_MARC_EXCLUSAO_LOG (
    ID               VARCHAR2(36)      PRIMARY KEY,
    TIPO_ID          VARCHAR2(36)      NOT NULL,
    NOME             VARCHAR2(20 CHAR) NOT NULL,
    BADGE            VARCHAR2(3 CHAR)  NOT NULL,
    ESCOPO           VARCHAR2(10)      NOT NULL,
    EXCLUIDO_POR_ID  VARCHAR2(36)      NOT NULL,
    EXCLUIDO_EM      TIMESTAMP         NOT NULL,
    -- Snapshot do que morreu (JSON): a trilha não pode depender do que apagou.
    RESUMO           CLOB              NOT NULL,
    CRIADO_EM        TIMESTAMP         NOT NULL,
    ATUALIZADO_EM    TIMESTAMP         NOT NULL,
    CONSTRAINT CK_PNT_TIPOMARC_EXC_ESCOPO CHECK (ESCOPO IN ('GLOBAL','INDIVIDUAL')),
    CONSTRAINT FK_PNT_TIPOMARC_EXC_ADMIN FOREIGN KEY (EXCLUIDO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- Leitura natural da trilha: do mais recente para o mais antigo.
CREATE INDEX IDX_PNT_TIPOMARC_EXC_EM ON PNT_TIPO_MARC_EXCLUSAO_LOG (EXCLUIDO_EM);

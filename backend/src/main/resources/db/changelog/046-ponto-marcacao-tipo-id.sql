-- ============================================================
-- 046 — Ponto: marcações passam a apontar para o catálogo de tipos
--
-- PNT_DIA_MARCACAO.TIPO e PNT_PESSOA_MARCACAO.TIPO guardavam o literal de um
-- conjunto fechado, preso a um CHECK. Agora guardam TIPO_ID, a chave da linha
-- do catálogo (PNT_TIPO_MARCACAO) — é ela que diz o nome exibido e a badge.
--
-- A conversão preserva as marcações existentes: cada literal antigo tem um tipo
-- correspondente entre os que o catálogo já traz. O NOT NULL só entra depois do
-- preenchimento, de modo que um literal desconhecido faz o changeset FALHAR em
-- vez de deixar marcação sem tipo.
--
-- A FK é deliberadamente SEM cascade: apagar um tipo apaga as marcações dele
-- por decisão explícita do serviço, que conta o que morreu e registra a trilha.
-- ============================================================

-- ── PNT_DIA_MARCACAO (marcação global de dia) ────────────────

ALTER TABLE PNT_DIA_MARCACAO ADD (TIPO_ID VARCHAR2(36));

UPDATE PNT_DIA_MARCACAO SET TIPO_ID = CASE TIPO
    WHEN 'FERIADO'           THEN '8c92e456-1e96-4d7a-a297-6c1eb48b87cb'
    WHEN 'PONTO_FACULTATIVO' THEN 'cf0eee15-1315-4890-a152-30c7d72b62c5'
END;

ALTER TABLE PNT_DIA_MARCACAO MODIFY (TIPO_ID NOT NULL);

ALTER TABLE PNT_DIA_MARCACAO ADD CONSTRAINT FK_PNT_DIAMARC_TIPO
    FOREIGN KEY (TIPO_ID) REFERENCES PNT_TIPO_MARCACAO(ID);

ALTER TABLE PNT_DIA_MARCACAO DROP CONSTRAINT CK_PNT_DIAMARC_TIPO;

ALTER TABLE PNT_DIA_MARCACAO DROP COLUMN TIPO;

-- ── PNT_PESSOA_MARCACAO (marcação por pessoa-dia) ────────────

ALTER TABLE PNT_PESSOA_MARCACAO ADD (TIPO_ID VARCHAR2(36));

UPDATE PNT_PESSOA_MARCACAO SET TIPO_ID = CASE TIPO
    WHEN 'A_DISPOSICAO'   THEN 'ee6c8e18-b069-4f9a-93c7-bf87b6042a5b'
    WHEN 'ATESTADO'       THEN '6fde6137-b3a2-4f14-9274-997d1998568e'
    WHEN 'FERIAS'         THEN 'fed26b8b-35ae-4401-83f1-799767f10edd'
    WHEN 'RECESSO'        THEN '14cbee66-6695-40dd-bfd7-4c5d026d6d9c'
    WHEN 'LICENCA_MEDICA' THEN '74456f05-cb5a-474a-b49f-1a938bdafed7'
END;

ALTER TABLE PNT_PESSOA_MARCACAO MODIFY (TIPO_ID NOT NULL);

ALTER TABLE PNT_PESSOA_MARCACAO ADD CONSTRAINT FK_PNT_PESMARC_TIPO
    FOREIGN KEY (TIPO_ID) REFERENCES PNT_TIPO_MARCACAO(ID);

ALTER TABLE PNT_PESSOA_MARCACAO DROP CONSTRAINT CK_PNT_PESMARC_TIPO;

ALTER TABLE PNT_PESSOA_MARCACAO DROP COLUMN TIPO;

-- A exclusão de um tipo varre as marcações daquele tipo nas duas tabelas.
CREATE INDEX IDX_PNT_DIAMARC_TIPO ON PNT_DIA_MARCACAO (TIPO_ID);
CREATE INDEX IDX_PNT_PESMARC_TIPO ON PNT_PESSOA_MARCACAO (TIPO_ID);

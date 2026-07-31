-- ============================================================
-- 046 — Ponto: PNT_TIPO_MARCACAO (catálogo de tipos de ocorrência)
--
-- Os tipos de marcação do ponto (Feriado, Férias, Atestado…) deixam de ser
-- literais fixos no código e passam a ser LINHAS: o admin master cadastra e
-- remove tipos pela tela, e a grade, o XLSX e o bloqueio de folga apenas leem
-- o nome daqui. Um tipo NÃO altera o cálculo do saldo — seu efeito é rotular a
-- célula do dia e impedir a solicitação de folga naquele dia.
--
-- ESCOPO separa quem o tipo alcança: GLOBAL vale para todos os funcionários
-- (PNT_DIA_MARCACAO, 1 marcação por dia); INDIVIDUAL vale para um funcionário
-- num dia (PNT_PESSOA_MARCACAO).
--
-- NOME e BADGE são únicos entre TODOS os escopos, pela forma normalizada
-- (maiúsculas, sem acentos, espaços colapsados) gravada em NOME_NORM/BADGE_NORM:
-- "Férias", "ferias" e "  FERIAS " são o mesmo tipo. O texto original é o que
-- aparece na tela, exatamente como foi digitado.
--
-- ⚠ Tamanhos em CHAR (e não no default BYTE do banco): num charset AL32UTF8 a
-- badge "Fér" ocupa 4 bytes e não caberia em VARCHAR2(3). O limite de negócio é
-- de caracteres — 20 no nome, 3 na badge.
-- ============================================================

CREATE TABLE PNT_TIPO_MARCACAO (
    ID             VARCHAR2(36)      PRIMARY KEY,
    NOME           VARCHAR2(20 CHAR) NOT NULL,
    -- Forma normalizada do nome/da badge: é ELA que garante a unicidade global.
    NOME_NORM      VARCHAR2(20 CHAR) NOT NULL,
    BADGE          VARCHAR2(3 CHAR)  NOT NULL,
    BADGE_NORM     VARCHAR2(3 CHAR)  NOT NULL,
    ESCOPO         VARCHAR2(10)      NOT NULL,
    -- Nulo nos tipos que nascem com o sistema: não houve admin que os criasse.
    CRIADO_POR_ID  VARCHAR2(36),
    CRIADO_EM      TIMESTAMP         NOT NULL,
    ATUALIZADO_EM  TIMESTAMP         NOT NULL,
    CONSTRAINT UK_PNT_TIPOMARC_NOME UNIQUE (NOME_NORM),
    CONSTRAINT UK_PNT_TIPOMARC_BADGE UNIQUE (BADGE_NORM),
    CONSTRAINT CK_PNT_TIPOMARC_ESCOPO CHECK (ESCOPO IN ('GLOBAL','INDIVIDUAL')),
    CONSTRAINT FK_PNT_TIPOMARC_ADMIN FOREIGN KEY (CRIADO_POR_ID) REFERENCES PES_ADMINISTRADOR(ID)
);

-- Os chips do modal de ocorrências pedem os tipos de um escopo, em ordem de nome.
CREATE INDEX IDX_PNT_TIPOMARC_ESCOPO ON PNT_TIPO_MARCACAO (ESCOPO, NOME);

-- ── Tipos iniciais ───────────────────────────────────────────
-- São registros comuns: o master pode removê-los como a qualquer outro.
-- A badge de Férias é "Fé" porque "Fér" e o "Fer" de Feriado normalizam para o
-- mesmo texto, e a badge é única em todo o catálogo.

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('8c92e456-1e96-4d7a-a297-6c1eb48b87cb', 'Feriado', 'FERIADO', 'Fer', 'FER', 'GLOBAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('cf0eee15-1315-4890-a152-30c7d72b62c5', 'P. Facultativo', 'P. FACULTATIVO', 'PF', 'PF', 'GLOBAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('ee6c8e18-b069-4f9a-93c7-bf87b6042a5b', 'À disposição', 'A DISPOSICAO', 'Dis', 'DIS', 'INDIVIDUAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('6fde6137-b3a2-4f14-9274-997d1998568e', 'Atestado', 'ATESTADO', 'Ate', 'ATE', 'INDIVIDUAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('fed26b8b-35ae-4401-83f1-799767f10edd', 'Férias', 'FERIAS', 'Fé', 'FE', 'INDIVIDUAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('14cbee66-6695-40dd-bfd7-4c5d026d6d9c', 'Recesso', 'RECESSO', 'Rec', 'REC', 'INDIVIDUAL', SYSTIMESTAMP, SYSTIMESTAMP);

INSERT INTO PNT_TIPO_MARCACAO (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, CRIADO_EM, ATUALIZADO_EM)
VALUES ('74456f05-cb5a-474a-b49f-1a938bdafed7', 'Lic. médica', 'LIC. MEDICA', 'Lic', 'LIC', 'INDIVIDUAL', SYSTIMESTAMP, SYSTIMESTAMP);

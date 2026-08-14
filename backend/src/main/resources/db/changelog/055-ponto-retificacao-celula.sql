-- ============================================================
-- 055 — Ponto: retificação por célula e tipo declarado pelo funcionário
--
-- A retificação deixa de ser um formulário de pares completos e passa a ser a
-- célula da planilha: o funcionário corrige UM horário de um dia e os demais
-- continuam valendo o que veio na folha oficial. Por isso o CHECK de pares cai
-- — um horário sozinho é agora o caso normal, não um envio pela metade.
--
-- No lugar dos horários, o dia pode receber um TIPO do catálogo de ocorrências
-- ("Banco de horas" é o primeiro): é a declaração que antes o funcionário
-- escrevia à mão na planilha. Tipo e horários são excludentes — a célula é uma
-- só, ou o texto ou os horários —, e a retificação sem nenhum dos dois não
-- existe: apagar tudo é apagar a linha.
--
-- O catálogo ganha duas propriedades por tipo: quais aparecem para o
-- funcionário escolher e quais, ao serem escolhidos, valem como folga do banco
-- de horas na grade — o mesmo efeito visual e a mesma contagem da folga
-- aprovada, sem tocar no saldo.
-- ============================================================

-- ── PNT_RETIFICACAO ──────────────────────────────────────────

ALTER TABLE PNT_RETIFICACAO DROP CONSTRAINT CK_PNT_RETIF_PARES;

ALTER TABLE PNT_RETIFICACAO ADD (TIPO_ID VARCHAR2(36));

COMMENT ON COLUMN PNT_RETIFICACAO.TIPO_ID IS
    'Tipo de ocorrencia declarado para o dia inteiro; nulo na retificacao de horarios';

-- A FK é sem cascade, como nas marcações: apagar um tipo do catálogo é decisão
-- explícita do serviço, que conta o que morre e registra a trilha.
ALTER TABLE PNT_RETIFICACAO ADD CONSTRAINT FK_PNT_RETIF_TIPO
    FOREIGN KEY (TIPO_ID) REFERENCES PNT_TIPO_MARCACAO(ID);

-- A exclusão de um tipo varre as retificações feitas com ele.
CREATE INDEX IDX_PNT_RETIF_TIPO ON PNT_RETIFICACAO (TIPO_ID);

-- A retificação sem horário nenhum não corrige coisa alguma: ela nascia quando o
-- par de nulos ainda passava por "par completo", e é o que a regra abaixo proíbe.
-- Sem esta limpeza uma única linha dessas recusaria a constraint, e a tabela
-- ficaria sem nenhuma — o DROP acima já commitou.
DELETE FROM PNT_RETIFICACAO
 WHERE TIPO_ID IS NULL AND ENT1 IS NULL AND SAI1 IS NULL AND ENT2 IS NULL AND SAI2 IS NULL;

-- Tipo OU ao menos um horário — nunca os dois, nunca nenhum.
ALTER TABLE PNT_RETIFICACAO ADD CONSTRAINT CK_PNT_RETIF_CONTEUDO CHECK (
    (TIPO_ID IS NOT NULL AND ENT1 IS NULL AND SAI1 IS NULL AND ENT2 IS NULL AND SAI2 IS NULL)
    OR (TIPO_ID IS NULL AND (ENT1 IS NOT NULL OR SAI1 IS NOT NULL
                             OR ENT2 IS NOT NULL OR SAI2 IS NOT NULL))
);

-- ── PNT_TIPO_MARCACAO ────────────────────────────────────────

ALTER TABLE PNT_TIPO_MARCACAO ADD (VISIVEL_FUNCIONARIO NUMBER(1) DEFAULT 0 NOT NULL);

COMMENT ON COLUMN PNT_TIPO_MARCACAO.VISIVEL_FUNCIONARIO IS
    'O tipo aparece para o funcionario declarar na retificacao do dia';

ALTER TABLE PNT_TIPO_MARCACAO ADD CONSTRAINT CK_PNT_TIPOMARC_VIS_FUNC
    CHECK (VISIVEL_FUNCIONARIO IN (0, 1));

ALTER TABLE PNT_TIPO_MARCACAO ADD (CONTA_FOLGA NUMBER(1) DEFAULT 0 NOT NULL);

COMMENT ON COLUMN PNT_TIPO_MARCACAO.CONTA_FOLGA IS
    'A retificacao com este tipo vale como folga do banco de horas na grade';

ALTER TABLE PNT_TIPO_MARCACAO ADD CONSTRAINT CK_PNT_TIPOMARC_CONTA_FOLGA
    CHECK (CONTA_FOLGA IN (0, 1));

-- ── O tipo que o funcionário declara ─────────────────────────
-- Nasce aqui e não pela tela: o nome é o mesmo texto que a grade usa na célula
-- da folga aprovada, e o cadastro do master o recusa justamente para que só
-- exista este. Onde o nome já existir, o UPDATE garante as propriedades; onde
-- não existir, o INSERT o cria com a primeira badge livre — a preferida pode
-- estar ocupada por um tipo que o administrador cadastrou, e a badge é única em
-- todo o catálogo.

UPDATE PNT_TIPO_MARCACAO
SET VISIVEL_FUNCIONARIO = 1, CONTA_FOLGA = 1, ATUALIZADO_EM = SYSTIMESTAMP
WHERE NOME_NORM = 'BANCO DE HORAS'
  AND (VISIVEL_FUNCIONARIO = 0 OR CONTA_FOLGA = 0);

INSERT INTO PNT_TIPO_MARCACAO
    (ID, NOME, NOME_NORM, BADGE, BADGE_NORM, ESCOPO, VISIVEL_FUNCIONARIO, CONTA_FOLGA,
     CRIADO_EM, ATUALIZADO_EM)
SELECT 'dff4317b-f2d0-4632-8f53-fd1b5ee5dfdf', 'Banco de horas', 'BANCO DE HORAS',
       LIVRE.BADGE, LIVRE.BADGE_NORM, 'INDIVIDUAL', 1, 1, SYSTIMESTAMP, SYSTIMESTAMP
FROM (SELECT BADGE, BADGE_NORM
      FROM (SELECT 'Ban' AS BADGE, 'BAN' AS BADGE_NORM, 1 AS ORDEM FROM DUAL
            UNION ALL SELECT 'BcH', 'BCH', 2 FROM DUAL
            UNION ALL SELECT 'BdH', 'BDH', 3 FROM DUAL
            UNION ALL SELECT 'Bnc', 'BNC', 4 FROM DUAL)
      WHERE BADGE_NORM NOT IN (SELECT BADGE_NORM FROM PNT_TIPO_MARCACAO)
      ORDER BY ORDEM
      FETCH FIRST 1 ROW ONLY) LIVRE
WHERE NOT EXISTS (SELECT 1 FROM PNT_TIPO_MARCACAO WHERE NOME_NORM = 'BANCO DE HORAS');

-- ============================================================
-- 054 — Banco de horas: a solicitação como unidade
--
-- O funcionário pede vários dias de uma vez, mas cada dia virava uma linha solta
-- em PNT_SOLICITACAO_FOLGA: nada dizia que aqueles dias vieram do mesmo envio, e
-- o administrador deliberava dia a dia o que a pessoa pediu junto. A coluna nova
-- guarda o envio a que cada dia pertence; linha antiga, sem grupo, é uma
-- solicitação de um dia só — a chave da solicitação é o grupo quando existe, e o
-- próprio id quando não.
--
-- A trilha do desfecho passa a ter um resultado a mais: parte dos dias aprovada e
-- parte rejeitada. O domínio do SUBTIPO da comunicação é fechado por CHECK e não
-- admite valor novo sem recriar a constraint: ela é derrubada e reposta com o
-- domínio inteiro, o antigo mais o valor novo.
-- ============================================================

ALTER TABLE PNT_SOLICITACAO_FOLGA ADD (GRUPO_ID VARCHAR2(36));

COMMENT ON COLUMN PNT_SOLICITACAO_FOLGA.GRUPO_ID IS
    'Envio a que este dia pertence; nulo nas solicitacoes de um dia anteriores a esta coluna';

-- As listagens agrupam e deliberam por solicitação: a leitura é sempre por este id.
CREATE INDEX IX_PNT_SOLF_GRUPO ON PNT_SOLICITACAO_FOLGA (GRUPO_ID);

ALTER TABLE FRM_AVISO_CADASTRO DROP CONSTRAINT CK_FRM_AVISO_CAD_SUBTIPO;

-- Domínio do subtipo (nulo permitido: legado e Verificação).
ALTER TABLE FRM_AVISO_CADASTRO ADD CONSTRAINT CK_FRM_AVISO_CAD_SUBTIPO CHECK (SUBTIPO IS NULL OR
    SUBTIPO IN ('FOLHA_SEMANAL','FOLHA_MENSAL','FOLHA_REGISTRO_INCOMPLETO','SOLICITACAO_APROVADA',
                'SOLICITACAO_REJEITADA','SOLICITACAO_MISTA','ESCALA','AGENDA','PESSOAL',
                'GRUPO_OPERADORES','GRUPO_TECNICOS','GRUPO_TODOS','GRUPO_ADMINISTRADORES')
);

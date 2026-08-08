-- ============================================================
-- 051 — Avisos: subtipo FOLHA_REGISTRO_INCOMPLETO
--
-- A publicação de uma folha semanal ou de uma prévia mensal passa a separar em
-- duas comunicações as pessoas cuja folha tem dia com registro de entrada ou de
-- saída faltando: elas recebem, além do aviso de folha publicada, o alerta de
-- que há dia incompleto a corrigir. O subtipo novo é o que distingue as duas
-- linhas na tabela do administrador sem precisar abrir cada uma.
--
-- O domínio do SUBTIPO é fechado por CHECK e não admite valor novo sem recriar a
-- constraint: ela é derrubada e reposta com o domínio inteiro, o antigo mais o
-- valor novo. Nada é preenchido nem migrado — os cadastros já gravados
-- continuam válidos, porque o domínio só cresce.
-- ============================================================

ALTER TABLE FRM_AVISO_CADASTRO DROP CONSTRAINT CK_FRM_AVISO_CAD_SUBTIPO;

-- Domínio do subtipo (nulo permitido: legado e Verificação).
ALTER TABLE FRM_AVISO_CADASTRO ADD CONSTRAINT CK_FRM_AVISO_CAD_SUBTIPO CHECK (SUBTIPO IS NULL OR
    SUBTIPO IN ('FOLHA_SEMANAL','FOLHA_MENSAL','FOLHA_REGISTRO_INCOMPLETO','SOLICITACAO_APROVADA',
                'SOLICITACAO_REJEITADA','ESCALA','AGENDA','PESSOAL','GRUPO_OPERADORES',
                'GRUPO_TECNICOS','GRUPO_TODOS','GRUPO_ADMINISTRADORES')
);

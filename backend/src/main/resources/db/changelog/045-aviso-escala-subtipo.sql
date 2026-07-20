-- ============================================================
-- 045 — Avisos: vínculo com escala (ESCALA_ID) + SUBTIPO
--
-- Prepara os avisos de Escala/Agenda/Pessoal-Grupo. Duas colunas novas em
-- FRM_AVISO_CADASTRO:
--
--   ESCALA_ID — só o aviso de ESCALA aponta para uma escala (OPR_ESCALA_SEMANAL).
--     A vigência (Pendente/Ativo/Expirado) e o destinatário (os operadores da
--     escala) do aviso de escala são DERIVADOS da escala em tempo de consulta —
--     por isso o vínculo é uma FK no cabeçalho, e não datas/linhas de alvo
--     próprias. O CHECK de coerência amarra ESCALA_ID ao tipo ESCALA.
--
--   SUBTIPO — código estável do qual o backend deriva as DUAS leituras do mesmo
--     aviso, propositalmente diferentes: o título do popup ("Aviso - Folha
--     semanal disponível") e o rótulo da tabela do admin ("Folha Semanal").
--     Nulo é permitido: avisos de Verificação e todo o legado (PESSOAL já em
--     produção) ficam sem subtipo e caem nos fallbacks (label do TIPO).
--
-- Migração ADITIVA e benigna: o módulo de avisos está EM PRODUÇÃO, mas não há
-- nenhum aviso de ESCALA gravado (o CHECK de coerência aceita todo o legado,
-- que é TIPO<>'ESCALA' com ESCALA_ID nulo) nem subtipo algum (todos nulos).
-- Nada a preencher. As constraints de STATUS/DURACAO/EXPIRA e as tabelas de
-- alvo/ciência ficam INTOCADAS (ver plano §9).
-- ============================================================

ALTER TABLE FRM_AVISO_CADASTRO ADD (ESCALA_ID NUMBER(10), SUBTIPO VARCHAR2(30));

ALTER TABLE FRM_AVISO_CADASTRO
    ADD CONSTRAINT FK_FRM_AVISO_CAD_ESCALA FOREIGN KEY (ESCALA_ID) REFERENCES OPR_ESCALA_SEMANAL(ID);

-- Coerência: só aviso de ESCALA aponta para escala.
ALTER TABLE FRM_AVISO_CADASTRO ADD CONSTRAINT CK_FRM_AVISO_CAD_ESCALA CHECK (
    (TIPO = 'ESCALA' AND ESCALA_ID IS NOT NULL) OR (TIPO <> 'ESCALA' AND ESCALA_ID IS NULL)
);

-- Domínio do subtipo (nulo permitido: legado e Verificação).
ALTER TABLE FRM_AVISO_CADASTRO ADD CONSTRAINT CK_FRM_AVISO_CAD_SUBTIPO CHECK (SUBTIPO IS NULL OR
    SUBTIPO IN ('FOLHA_SEMANAL','FOLHA_MENSAL','SOLICITACAO_APROVADA','SOLICITACAO_REJEITADA',
                'ESCALA','AGENDA','PESSOAL','GRUPO_OPERADORES','GRUPO_TECNICOS','GRUPO_TODOS',
                'GRUPO_ADMINISTRADORES')
);

CREATE INDEX IDX_FRM_AVISO_CAD_ESCALA ON FRM_AVISO_CADASTRO (ESCALA_ID);

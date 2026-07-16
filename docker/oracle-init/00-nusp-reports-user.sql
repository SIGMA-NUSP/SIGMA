-- ============================================================
-- NUSP — Usuário NUSP_REPORTS (destinatário dos GRANTs do BI)
--
-- Roda ANTES do schema (ordem alfabética do initdb) e só na
-- primeira subida do container (volume vazio).
--
-- POR QUE EXISTE: o changelog 013 faz
--   GRANT SELECT ON VW_OPERADOR_PUBLICO TO NUSP_REPORTS
-- (idem 020, para as demais views públicas). Sem o usuário
-- criado ANTES do Liquibase, o boot do backend morre no 013 e
-- nenhum ambiente novo sobe a partir do repositório (achado F5).
--
-- MECANISMO: a imagem gvenzl/oracle-xe executa cada .sql deste
-- diretório com `sqlplus -s / as sysdba`, ou seja como SYS e no
-- CDB$ROOT — daí o ALTER SESSION abaixo, sem o qual o usuário
-- nasceria no container errado (o app vive no PDB XEPDB1).
--
-- SEM SENHA (NO AUTHENTICATION): a conta é apenas destinatária
-- de GRANTs e não precisa logar para o Liquibase funcionar — o
-- boot fica verde assim. Para habilitar o Metabase (leitura
-- read-only), dê-lhe credencial e sessão manualmente, como SYS
-- no PDB:
--   ALTER USER NUSP_REPORTS IDENTIFIED BY <senha>;
--   GRANT CREATE SESSION TO NUSP_REPORTS;
-- Sem esses dois comandos o BI não conecta (o backend não sofre).
-- Sinônimos NÃO são necessários: as queries do Metabase
-- qualificam o schema (NUSP.TABELA) — o comentário em contrário
-- no changelog 013 está obsoleto (não há nenhum sinônimo em
-- NUSP_REPORTS, nem em homologação nem em produção).
-- ============================================================

ALTER SESSION SET CONTAINER = XEPDB1;

CREATE USER NUSP_REPORTS NO AUTHENTICATION;

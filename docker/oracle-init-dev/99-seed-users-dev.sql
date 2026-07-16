-- ============================================================
-- NUSP — Usuários de TESTE (homologação / ambiente local)
--
-- ⚠️ NUNCA MONTAR EM PRODUÇÃO. Este arquivo vive fora de
-- «docker/oracle-init/» de propósito: aquela pasta é montada
-- pelos DOIS composes, e as contas abaixo têm senha pública
-- (os hashes bcrypt estão versionados num repositório público)
-- e nascem sem SENHA_PROVISORIA — reprovisionar a VPS do zero
-- com elas deixaria o sistema no ar com admin de senha conhecida
-- (achado F22). Só o docker-compose.homolog.yml monta este
-- arquivo, por bind avulso sobre a pasta comum.
--
-- Roda depois do schema e do seed de referência: o entrypoint da
-- imagem executa os scripts de /container-entrypoint-initdb.d em
-- ordem alfabética, e o prefixo 99- garante a última posição.
-- Como todo .sql do init, roda em sessão sqlplus NOVA, como SYS
-- e no CDB$ROOT — daí o preâmbulo (lição do achado F20).
--
-- PROVISIONAMENTO DE PRODUÇÃO / DR — como nasce o 1º admin:
-- não há conta alguma no banco novo. Crie o primeiro
-- administrador deliberadamente, com um hash bcrypt gerado na
-- hora (nunca versionado), conectado como NUSP no XEPDB1:
--
--   INSERT INTO PES_ADMINISTRADOR (ID, NOME_COMPLETO, EMAIL,
--          USERNAME, PASSWORD_HASH, SENHA_PROVISORIA,
--          CRIADO_EM, ATUALIZADO_EM)
--   VALUES (SYS_GUID(), '<nome>', '<email>', '<usuario>',
--          '<hash bcrypt $2b$12$…>', 1, SYSTIMESTAMP, SYSTIMESTAMP);
--
-- SENHA_PROVISORIA = 1 força a troca no primeiro login. O
-- username precisa bater com ADMIN_MASTER_USERNAME do .env para
-- que essa conta possa cadastrar os demais administradores.
-- ============================================================

ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = NUSP;

-- PES_ADMINISTRADOR (usuário de teste — login: admin / senha: admin)
INSERT INTO PES_ADMINISTRADOR (ID, NOME_COMPLETO, EMAIL, USERNAME, PASSWORD_HASH, CRIADO_EM, ATUALIZADO_EM)
VALUES (SYS_GUID(), 'Administrador Teste', 'admin@teste.com', 'admin', '$2b$10$qwt5UpvFa40Yp0pQRd/PlOgB4oyHUIaix545mKTaDI5m3Nkcwsbbm', SYSTIMESTAMP, SYSTIMESTAMP);

-- PES_OPERADOR (usuário de teste — login: operador / senha: 1234)
INSERT INTO PES_OPERADOR (ID, NOME_COMPLETO, NOME_EXIBICAO, EMAIL, USERNAME, PASSWORD_HASH, CRIADO_EM, ATUALIZADO_EM)
VALUES (SYS_GUID(), 'Operador Teste', 'Operador', 'operador@teste.com', 'operador', '$2b$10$WpfoU53C4Ha0Ra1/jQIAz.wV47s0NNrteJnp6DsGany5lytatF64C', SYSTIMESTAMP, SYSTIMESTAMP);

COMMIT;

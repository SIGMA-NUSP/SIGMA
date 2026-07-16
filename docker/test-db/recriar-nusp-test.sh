#!/usr/bin/env bash
#
# Recria o schema de TESTES «NUSP_TEST» no Oracle de homolog local
# (container nusp-oracle-homolog), como clone de METADADOS do schema NUSP.
#
# Uso:    bash docker/test-db/recriar-nusp-test.sh
# Quando: na primeira montagem do andaime, após aplicar changelog novo no
#         homolog, ou após um `down -v` (o ddl-auto=validate dos *IT acusa
#         a defasagem com falha ruidosa).
#
# Passos: (1) drop/create do usuário NUSP_TEST; (2) expdp METADATA_ONLY do
# NUSP; (3) impdp com REMAP_SCHEMA=NUSP:NUSP_TEST (EXCLUDE=GRANT); (4)
# sanidade: compila o schema e ABORTA se restar objeto INVALID (pega views/
# triggers quebradas no clone e, pior, objeto cujo DDL qualifique «NUSP.»,
# que apontaria para os DADOS do homolog) OU se a contagem de objetos por
# tipo divergir do NUSP (pega objeto AUSENTE — falha ORA-39083 do impdp que
# termina com exit 5 e não deixa rastro INVALID).
#
# ⚠ Não rodar em paralelo com uma execução de `mvn verify -DskipITs=false`
#   (o drop derruba as sessões do NUSP_TEST — ver gotcha 18 do plano).

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ENV_FILE="${SCRIPT_DIR}/../.env"          # docker/.env (gitignored)

CONTAINER=nusp-oracle-homolog
SERVICE=XEPDB1
NUSP_USER=NUSP
TEST_USER=NUSP_TEST
TEST_PASS=NuspTest2026          # senha fixa de teste (a mesma do application-test.yml)
DUMPFILE=nusp_meta.dmp

# Senhas reais NUNCA hardcoded aqui (o repo é público): vêm do docker/.env,
# as mesmas variáveis que o docker-compose.homolog.yml injeta no container.
if [[ ! -f $ENV_FILE ]]; then
    echo "ERRO: $ENV_FILE não encontrado (esperado o docker/.env do homolog)." >&2
    exit 1
fi
SYS_PASS=$(grep -m1 '^ORACLE_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)
NUSP_PASS=$(grep -m1 '^ORACLE_APP_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)
if [[ -z $SYS_PASS || -z $NUSP_PASS ]]; then
    echo "ERRO: ORACLE_PASSWORD/ORACLE_APP_PASSWORD ausentes em $ENV_FILE." >&2
    exit 1
fi

# docker sem sudo se o usuário estiver no grupo docker; senão, sudo
DOCKER=docker
docker info >/dev/null 2>&1 || DOCKER="sudo docker"

if ! $DOCKER ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER não está no ar." >&2
    exit 1
fi

# Tolera o exit 5 do Data Pump (EX_SUCC_ERR: sucesso com warnings — ex.
# ORA-31684 "user already exists" no impdp); a sanidade final decide.
run_datapump() {
    local rc=0
    "$@" || rc=$?
    if [[ $rc -ne 0 && $rc -ne 5 ]]; then
        echo "ERRO: '$*' terminou com exit $rc." >&2
        exit "$rc"
    fi
}

echo "== [1/4] Recriando usuário $TEST_USER =="
$DOCKER exec -i "$CONTAINER" sqlplus -s "sys/${SYS_PASS}@${SERVICE} as sysdba" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET SERVEROUTPUT ON
-- derruba sessões ativas do usuário de teste (senão o DROP falha com ORA-01940)
BEGIN
  FOR s IN (SELECT sid, serial# sn FROM v\$session WHERE username = '${TEST_USER}') LOOP
    EXECUTE IMMEDIATE 'ALTER SYSTEM KILL SESSION ''' || s.sid || ',' || s.sn || ''' IMMEDIATE';
  END LOOP;
END;
/
BEGIN
  EXECUTE IMMEDIATE 'DROP USER ${TEST_USER} CASCADE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE != -1918 THEN RAISE; END IF;   -- ORA-01918: o usuário não existia
END;
/
CREATE USER ${TEST_USER} IDENTIFIED BY "${TEST_PASS}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE,
      CREATE PROCEDURE, CREATE TRIGGER TO ${TEST_USER};
-- robustez a containers recriados: o expdp abaixo roda como NUSP
GRANT READ, WRITE ON DIRECTORY DATA_PUMP_DIR TO ${NUSP_USER};
EXIT
SQL

echo "== [2/4] expdp METADATA_ONLY do schema ${NUSP_USER} =="
run_datapump $DOCKER exec "$CONTAINER" expdp "${NUSP_USER}/${NUSP_PASS}@${SERVICE}" \
    SCHEMAS="$NUSP_USER" CONTENT=METADATA_ONLY DIRECTORY=DATA_PUMP_DIR \
    DUMPFILE="$DUMPFILE" LOGFILE=nusp_meta_exp.log REUSE_DUMPFILES=YES

echo "== [3/4] impdp REMAP_SCHEMA ${NUSP_USER} -> ${TEST_USER} =="
# como usuário privilegiado: REMAP_SCHEMA p/ outro schema exige IMP_FULL_DATABASE.
# sys as sysdba (não system): a conta comum SYSTEM está LOCKED no CDB$ROOT
# deste container, o que bloqueia o login mesmo via serviço do PDB.
# as aspas embutidas (\") são exigidas pelo parser do impdp p/ userid com espaços
run_datapump $DOCKER exec "$CONTAINER" impdp "userid=\"sys/${SYS_PASS}@${SERVICE} as sysdba\"" \
    REMAP_SCHEMA="${NUSP_USER}:${TEST_USER}" DIRECTORY=DATA_PUMP_DIR \
    DUMPFILE="$DUMPFILE" LOGFILE=nusp_meta_imp.log EXCLUDE=GRANT

echo "== [4/4] Sanidade do clone (INVALID ou contagem divergente abortam) =="
$DOCKER exec -i "$CONTAINER" sqlplus -s "sys/${SYS_PASS}@${SERVICE} as sysdba" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET SERVEROUTPUT ON
EXEC DBMS_UTILITY.COMPILE_SCHEMA('${TEST_USER}', compile_all => FALSE)
DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count
    FROM all_objects WHERE owner = '${TEST_USER}' AND status = 'INVALID';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('OBJETOS INVALID NO CLONE ${TEST_USER}:');
    FOR o IN (SELECT object_type, object_name FROM all_objects
               WHERE owner = '${TEST_USER}' AND status = 'INVALID'
               ORDER BY object_type, object_name) LOOP
      DBMS_OUTPUT.PUT_LINE('  ' || o.object_type || ' ' || o.object_name);
    END LOOP;
    RAISE_APPLICATION_ERROR(-20001,
      v_count || ' objeto(s) INVALID em ${TEST_USER} — clone abortado');
  END IF;
END;
/
-- Completude: falha do impdp em criar um objeto (ex. ORA-39083) termina com
-- exit 5 e NÃO deixa objeto INVALID — só a comparação com a origem a detecta.
-- Compara por CONTAGEM por tipo (nomes de sistema ISEQ\$\$/SYS_C/SYS_IL diferem
-- entre schemas); exclui recyclebin (BIN\$) e LOB (segment derivado 1:1 da
-- coluna da tabela — não pode faltar se a TABLE veio; e o NUSP de homolog tem
-- um SYS_LOB órfão em dba_objects, resíduo da migração de 20/06/26, que
-- geraria falso positivo eterno). Divergência aqui também pode indicar lixo
-- no NUSP (ex. master table órfã de expdp abortado) — abortar e olhar é o
-- comportamento desejado.
DECLARE
  v_diff NUMBER := 0;
BEGIN
  FOR d IN (
    SELECT object_type,
           SUM(CASE WHEN owner = '${NUSP_USER}' THEN 1 ELSE 0 END) qtd_origem,
           SUM(CASE WHEN owner = '${TEST_USER}' THEN 1 ELSE 0 END) qtd_clone
      FROM dba_objects
     WHERE owner IN ('${NUSP_USER}', '${TEST_USER}')
       AND object_name NOT LIKE 'BIN\$%'
       AND object_type <> 'LOB'
     GROUP BY object_type
    HAVING SUM(CASE WHEN owner = '${NUSP_USER}' THEN 1 ELSE 0 END)
        != SUM(CASE WHEN owner = '${TEST_USER}' THEN 1 ELSE 0 END)
  ) LOOP
    v_diff := v_diff + 1;
    DBMS_OUTPUT.PUT_LINE('  DIVERGENCIA ' || d.object_type || ': ${NUSP_USER}='
      || d.qtd_origem || ' vs ${TEST_USER}=' || d.qtd_clone);
  END LOOP;
  IF v_diff > 0 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'clone incompleto: ' || v_diff || ' tipo(s) de objeto com contagem divergente');
  END IF;
END;
/
SET FEEDBACK OFF
SELECT 'Tabelas no clone: ' || COUNT(*) FROM all_tables WHERE owner = '${TEST_USER}';
EXIT
SQL

echo "OK: schema ${TEST_USER} recriado com sucesso."

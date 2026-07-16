#!/bin/sh
# Gera /config.json com as feature flags a partir de FEATURES_ENABLED — lista separada
# por vírgula das features LIGADAS (ex.: FEATURES_ENABLED=pontoBanco,inserirAvisos).
# Copiado para /docker-entrypoint.d/ — o entrypoint padrão da imagem nginx executa os
# scripts dessa pasta ANTES de subir o nginx, e a cada start do container (trocar a lista
# no .env + `up -d frontend` regenera o config, sem rebuild da imagem).
#
# GENÉRICO: flag nova NÃO exige mexer aqui nem nos composes — basta (a) declarar o nome
# na union `FeatureFlag` do frontend e (b) acrescentá-lo à lista no .env do ambiente.
# Fail-safe: só entra no JSON o que estiver na lista (nomes saneados p/ [A-Za-z0-9_]);
# no frontend, flag ausente = desligada. Lista vazia/ausente → nenhuma feature ligada.
set -e

json=""
IFS=','
for f in ${FEATURES_ENABLED:-}; do
  f=$(printf '%s' "$f" | tr -cd 'A-Za-z0-9_')
  [ -n "$f" ] && json="${json}\"${f}\":true,"
done

printf '{"features":{%s}}\n' "${json%,}" > /usr/share/nginx/html/config.json

# Deploy com Docker

## Pré-requisitos

- Docker 20+
- Docker Compose v2+

## Configuração

```bash
cd docker/
cp .env.example .env
# Editar .env com as credenciais desejadas
```

## Subir o sistema

```bash
cd docker/
docker compose up -d
```

Na **primeira execução**, o Oracle inicializa automaticamente:
1. Cria o usuário NUSP (via `APP_USER` do `.env`)
2. Cria as 19 tabelas (via `oracle-init/01-schema.sql`)
3. Insere dados iniciais (via `oracle-init/02-seed-data.sql`): salas, comissões, itens de checklist, configurações de sala e usuários de teste

O backend pode reiniciar algumas vezes enquanto o Oracle inicializa (~1-2 minutos). Isso é normal.

## Containers

| Container | Serviço | Porta |
|---|---|---|
| nusp-oracle | Oracle 21c XE | 1521 (interna) |
| nusp-backend | Spring Boot (Java 17) | 8003 (interna) |
| nusp-frontend | Angular + Nginx | 80 |

## Usuários de teste

| Perfil | Login | Senha |
|---|---|---|
| Administrador | admin | admin |
| Operador | operador | 1234 |

## Acessar

- **Sistema:** http://localhost
- **Health check:** http://localhost:8003/api/health
- **Oracle:** `docker exec -it nusp-oracle sqlplus NUSP/<senha>@XEPDB1`

## Atualizar após mudanças no código

```bash
cd docker/
docker compose build
docker compose up -d
```

## Parar

```bash
docker compose down           # para os containers (mantém dados)
docker compose down -v        # para e remove volumes (apaga banco)
```

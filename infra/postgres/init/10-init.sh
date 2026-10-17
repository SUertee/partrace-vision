#!/usr/bin/env bash
set -euo pipefail

# Executed by the official PostgreSQL entrypoint only for an empty data directory.
# Set up infrastructure only; business tables require a reviewed migration later.
for variable in POSTGRES_DB POSTGRES_USER PARTRACE_DATABASE_USER; do
  value="${!variable:?Missing database identifier}"
  if [[ ! "$value" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
    printf '%s must be a lowercase PostgreSQL identifier (1-63 characters).\n' "$variable" >&2
    exit 1
  fi
done

if [[ "$PARTRACE_DATABASE_USER" == "$POSTGRES_USER" ]]; then
  printf 'Application and administrator database users must be different.\n' >&2
  exit 1
fi

if [[ ! "${PARTRACE_DATABASE_PASSWORD:-}" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
  printf 'PARTRACE_DATABASE_PASSWORD must contain at least 32 URL-safe characters.\n' >&2
  exit 1
fi

# psql reads the password from its environment rather than a command argument.
# format() quotes identifiers/literals before executing generated SQL.
psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user PARTRACE_DATABASE_USER
\getenv app_password PARTRACE_DATABASE_PASSWORD
\getenv app_database POSTGRES_DB

CREATE EXTENSION IF NOT EXISTS vector;

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', :'app_user', :'app_password')
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_database', :'app_user')
\gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'app_user')
\gexec
SQL

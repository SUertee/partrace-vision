"""Explicit development-only administration; never called during app startup."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
from typing import Sequence

from pydantic import ValidationError
import psycopg

from backend.core.settings import Settings


# Stable application-specific signed bigint; transaction locks release on rollback.
_INITIALIZATION_LOCK = 0x5061725472616365
_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")
_PUBLIC_RELATION_EXISTS = """
    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class AS relation
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
    )
"""
_PUBLIC_TABLE_COUNT = """
    SELECT count(*)
    FROM pg_catalog.pg_class AS relation
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p')
"""


class ManagementError(RuntimeError):
    """An actionable management failure whose message contains no credentials."""


def _require_development(settings: Settings) -> None:
    if settings.environment != "development":
        raise ManagementError("bootstrap commands are only allowed in development")


def _schema_body(path: Path) -> str:
    """Remove only the checked outer transaction, retaining PL/pgSQL BEGIN blocks."""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        raise ManagementError("cannot read the configured schema file") from None
    statements = [
        index
        for index, line in enumerate(lines)
        if line.strip() and not line.lstrip().startswith("--")
    ]
    if (
        len(statements) < 3
        or lines[statements[0]].strip().upper() != "BEGIN;"
        or lines[statements[-1]].strip().upper() != "COMMIT;"
    ):
        raise ManagementError("schema must have the expected outer BEGIN/COMMIT wrapper")
    return "\n".join(lines[statements[0] + 1 : statements[-1]])


def _connect(settings: Settings) -> psycopg.Connection:
    timeout_ms = settings.database_timeout_seconds * 1000
    return psycopg.connect(
        settings.database_url.get_secret_value(),
        autocommit=True,
        connect_timeout=settings.database_timeout_seconds,
        application_name="partrace-manage",
        options=f"-c statement_timeout={timeout_ms} -c lock_timeout={timeout_ms}",
    )


def init_database(settings: Settings) -> int:
    """Initialize an empty public schema atomically; refuse any existing relations."""
    _require_development(settings)
    schema = _schema_body(Path(settings.schema_path))
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT pg_catalog.pg_advisory_xact_lock(%s)", (_INITIALIZATION_LOCK,)
                    )
                    cursor.execute(_PUBLIC_RELATION_EXISTS)
                    existing = cursor.fetchone()
                    if existing is None:
                        raise ManagementError("cannot inspect the target database")
                    if existing[0]:
                        raise ManagementError(
                            "target public schema is not empty; existing database was not changed"
                        )
                    cursor.execute("SET LOCAL search_path TO public, pg_catalog")
                    # No request data or parameters enter this repository-owned DDL.
                    cursor.execute(schema, prepare=False)
                    cursor.execute(_PUBLIC_TABLE_COUNT)
                    result = cursor.fetchone()
                    if result is None:
                        raise ManagementError("cannot verify the initialized database")
                    table_count = int(result[0])
    except psycopg.Error:
        raise ManagementError(
            "database initialization failed and was rolled back; check PostgreSQL, "
            "extension permissions and the configured schema"
        ) from None
    return table_count


def seed_user(settings: Settings, user_id: str, display_name: str, role: str) -> None:
    """Insert a first active user; duplicate IDs never update a user's role/history."""
    _require_development(settings)
    if not isinstance(user_id, str) or _ID_PATTERN.fullmatch(user_id) is None:
        raise ManagementError("user_id must match the public API Id format")
    if not isinstance(display_name, str) or not display_name.strip():
        raise ManagementError("display_name must contain non-whitespace text")
    if role not in {"admin", "viewer"}:
        raise ManagementError("role must be admin or viewer")
    created_at = datetime.now(timezone.utc)
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET LOCAL search_path TO public, pg_catalog")
                    cursor.execute(
                        "INSERT INTO users (user_id, display_name, role, status, created_at) "
                        "VALUES (%s, %s, %s, %s, %s)",
                        (user_id, display_name, role, "active", created_at),
                    )
    except psycopg.errors.UniqueViolation:
        raise ManagementError("user_id already exists; the existing user was not changed") from None
    except psycopg.Error:
        raise ManagementError(
            "user creation failed; check PostgreSQL, permissions and initialized schema"
        ) from None


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init-db", help="initialize only an empty development database")
    seed = commands.add_parser("seed-user", help="insert a first development user")
    seed.add_argument("--user-id", required=True)
    seed.add_argument("--display-name", required=True)
    seed.add_argument("--role", choices=("admin", "viewer"), required=True)
    args = parser.parse_args(argv)
    try:
        settings = Settings()
        if args.command == "init-db":
            count = init_database(settings)
            print(f"Initialized development schema: {count} tables.")
        else:
            seed_user(settings, args.user_id, args.display_name, args.role)
            print("Development user created.")
    except ValidationError:
        print("Invalid backend configuration; check the PARTRACE settings.", file=sys.stderr)
        return 1
    except ManagementError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

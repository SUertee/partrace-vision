"""Explicit development-only administration; never called during app startup."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
from typing import Sequence

from pydantic import ValidationError
import psycopg
from psycopg import sql

from backend.core.settings import Settings
from backend.storage.credentials import connection_class


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
    return connection_class(settings).connect(
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


def migrate_recording_jobs(settings: Settings) -> None:
    """Explicit additive migration; never run full empty-schema DDL on an existing DB."""
    _require_development(settings)
    migration = _schema_body(Path(settings.schema_path).parent / "migrations/001-recording-jobs.sql")
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                connection.execute("SELECT pg_catalog.pg_advisory_xact_lock(%s)", (_INITIALIZATION_LOCK,))
                connection.execute(sql.SQL("SET LOCAL search_path TO {},public,pg_catalog").format(sql.Identifier(settings.database_schema)))
                for table in ("users", "recordings"):
                    exists = connection.execute("SELECT to_regclass(%s)", (f"{settings.database_schema}.{table}",)).fetchone()
                    if not exists or exists[0] is None:
                        raise ManagementError("existing users/recordings schema is required; nothing was changed")
                existing = connection.execute("SELECT to_regclass(%s)", (f"{settings.database_schema}.recording_jobs",)).fetchone()
                if existing and existing[0] is not None:
                    raise ManagementError("recording_jobs already exists; existing structure and data were not changed")
                connection.execute(migration, prepare=False)
    except psycopg.Error:
        raise ManagementError("recording-jobs migration failed and was rolled back; existing data was preserved") from None


def migrate_source_recordings(settings: Settings) -> None:
    """Add source publication metadata and backfill unfinished durable jobs atomically."""
    _require_development(settings)
    migration = _schema_body(Path(settings.schema_path).parent / "migrations/002-source-recordings.sql")
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                connection.execute("SELECT pg_catalog.pg_advisory_xact_lock(%s)", (_INITIALIZATION_LOCK,))
                connection.execute(sql.SQL("SET LOCAL search_path TO {},public,pg_catalog").format(sql.Identifier(settings.database_schema)))
                for table in ("recordings", "recording_jobs"):
                    existing = connection.execute("SELECT to_regclass(%s)", (f"{settings.database_schema}.{table}",)).fetchone()
                    if not existing or existing[0] is None:
                        raise ManagementError("existing recordings/recording_jobs schema is required; apply recording-jobs migration first")
                existing = connection.execute(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema=%s AND table_name='recordings' AND column_name='publication_status'",
                    (settings.database_schema,),
                ).fetchone()
                if existing:
                    raise ManagementError("publication_status already exists; existing structure and data were not changed")
                connection.execute(migration, prepare=False)
    except psycopg.Error:
        raise ManagementError("source-recordings migration failed and was rolled back; existing data was preserved") from None


def migrate_event_person_id(settings: Settings) -> None:
    """Add anonymous A-gallery references; existing immutable facts remain null."""
    _require_development(settings)
    migration = _schema_body(Path(settings.schema_path).parent / "migrations/004-event-person-id.sql")
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                connection.execute("SELECT pg_catalog.pg_advisory_xact_lock(%s)", (_INITIALIZATION_LOCK,))
                connection.execute(sql.SQL("SET LOCAL search_path TO {},public,pg_catalog").format(sql.Identifier(settings.database_schema)))
                if connection.execute("SELECT to_regclass(%s)", (f"{settings.database_schema}.events",)).fetchone()[0] is None:
                    raise ManagementError("existing events schema is required; nothing was changed")
                if connection.execute("SELECT 1 FROM information_schema.columns WHERE table_schema=%s AND table_name='events' AND column_name='person_id'", (settings.database_schema,)).fetchone():
                    raise ManagementError("person_id already exists; existing structure and data were not changed")
                connection.execute(migration, prepare=False)
    except psycopg.Error:
        raise ManagementError("event-person-id migration failed and was rolled back; existing data was preserved") from None


def migrate_agent_cards(settings: Settings) -> None:
    """Add verified Agent cards without fabricating cards for completed legacy runs."""
    _require_development(settings)
    migration = _schema_body(Path(settings.schema_path).parent / "migrations/003-agent-cards.sql")
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                connection.execute("SELECT pg_catalog.pg_advisory_xact_lock(%s)", (_INITIALIZATION_LOCK,))
                connection.execute(sql.SQL("SET LOCAL search_path TO {},public,pg_catalog").format(sql.Identifier(settings.database_schema)))
                existing = connection.execute("SELECT to_regclass(%s)", (f"{settings.database_schema}.runs",)).fetchone()
                if not existing or existing[0] is None:
                    raise ManagementError("existing runs schema is required; nothing was changed")
                existing = connection.execute(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema=%s AND table_name='runs' AND column_name='cards_json'",
                    (settings.database_schema,),
                ).fetchone()
                if existing:
                    raise ManagementError("cards_json already exists; existing structure and data were not changed")
                # Hold the same table lock as the SQL guard before inspecting legacy rows.
                connection.execute("LOCK TABLE runs IN ACCESS EXCLUSIVE MODE")
                existing = connection.execute("SELECT 1 FROM runs WHERE status='completed' LIMIT 1").fetchone()
                if existing:
                    raise ManagementError(
                        "existing completed runs have no verified cards; automatic card backfill is forbidden; "
                        "existing structure and data were not changed"
                    )
                connection.execute(migration, prepare=False)
    except psycopg.Error:
        raise ManagementError("agent-cards migration failed and was rolled back; existing data was preserved") from None


def retry_recording_job(settings: Settings, job_id: str) -> None:
    """A repaired failed job is retried only by an explicit development operator."""
    _require_development(settings)
    if not isinstance(job_id, str) or _ID_PATTERN.fullmatch(job_id) is None:
        raise ManagementError("job_id must match the public API Id format")
    try:
        with _connect(settings) as connection:
            with connection.transaction():
                connection.execute(sql.SQL("SET LOCAL search_path TO {},public,pg_catalog").format(sql.Identifier(settings.database_schema)))
                row = connection.execute(
                    "UPDATE recording_jobs SET status='queued',worker_id=NULL,worker_user_id=NULL,lease_token_sha256=NULL,lease_expires_at=NULL,"
                    "result_sha256=NULL,error_code=NULL,event_count=NULL,evidence_count=NULL,started_at=NULL,finished_at=NULL,updated_at=clock_timestamp() "
                    "WHERE job_id=%s AND status='failed' RETURNING job_id", (job_id,),
                ).fetchone()
                if row is None:
                    raise ManagementError("only an existing failed job can be retried; no completed or active job was changed")
    except psycopg.Error:
        raise ManagementError("recording-job retry failed and was rolled back") from None


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init-db", help="initialize only an empty development database")
    commands.add_parser("migrate-recording-jobs", help="add recording_jobs to an existing development schema without replacing data")
    commands.add_parser("migrate-source-recordings", help="add publication state and pending metadata for existing raw jobs without replacing data")
    commands.add_parser("migrate-event-person-id", help="add nullable A gallery identity without rewriting event facts")
    commands.add_parser("migrate-agent-cards", help="add verified Agent cards to a development schema without completed legacy runs")
    retry = commands.add_parser("retry-recording-job", help="explicitly requeue one repaired failed job; completed jobs cannot change")
    retry.add_argument("job_id")
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
        elif args.command == "migrate-recording-jobs":
            migrate_recording_jobs(settings)
            print("Recording-jobs migration applied; existing data preserved.")
        elif args.command == "migrate-source-recordings":
            migrate_source_recordings(settings)
            print("Source-recordings migration applied; existing facts and job state preserved.")
        elif args.command == "migrate-event-person-id":
            migrate_event_person_id(settings)
            print("Event person_id migration applied; existing facts preserved.")
        elif args.command == "migrate-agent-cards":
            migrate_agent_cards(settings)
            print("Agent-cards migration applied; existing data preserved.")
        elif args.command == "retry-recording-job":
            retry_recording_job(settings, args.job_id)
            print("Failed recording job requeued; old worker leases were invalidated.")
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

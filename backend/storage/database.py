"""Read-only database readiness probe; never initializes or changes a schema."""

import asyncio
from dataclasses import dataclass

import psycopg

from backend.core.settings import Settings
from backend.storage.credentials import connection_class


@dataclass(frozen=True)
class DatabaseReadiness:
    postgresql: bool
    pgvector: bool

    @property
    def ready(self) -> bool:
        return self.postgresql and self.pgvector

    def checks(self) -> dict[str, str]:
        return {
            "postgresql": "ok" if self.postgresql else "unavailable",
            "pgvector": (
                "ok" if self.pgvector else "missing" if self.postgresql else "unavailable"
            ),
        }


async def check_database(settings: Settings) -> DatabaseReadiness:
    """Bound both connection and statement time; don't return DSNs or driver errors."""
    try:
        # IAM tokens are signed per connection; a missing instance role is an outage.
        factory = connection_class(settings, asynchronous=True)
        async with asyncio.timeout(settings.database_timeout_seconds):
            async with await factory.connect(
                settings.database_url.get_secret_value(),
                connect_timeout=settings.database_timeout_seconds,
                application_name="partrace-health",
                options="-c statement_timeout=2000 -c default_transaction_read_only=on",
            ) as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        "SELECT 1, EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')"
                    )
                    row = await cursor.fetchone()
        return DatabaseReadiness(postgresql=row is not None, pgvector=bool(row and row[1]))
    except (psycopg.Error, OSError, TimeoutError):
        return DatabaseReadiness(postgresql=False, pgvector=False)

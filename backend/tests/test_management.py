from contextlib import contextmanager
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace

import psycopg
import pytest

from backend.core.settings import Settings
from backend import manage


@pytest.fixture
def settings(tmp_path):
    schema = tmp_path / "schema.sql"
    schema.write_text(
        "-- empty-database fixture\nBEGIN;\nCREATE TABLE example (id TEXT);\nCOMMIT;\n",
        encoding="utf-8",
    )
    return Settings(
        _env_file=None,
        database_url="postgresql://test:management-secret@localhost/test",
        schema_path=schema,
    )


class DatabaseDouble:
    """Observe transaction/order/parameter boundaries, not a PostgreSQL emulator."""

    def __init__(self, *, nonempty=False, ddl_error=False, duplicate=False):
        self.nonempty = nonempty
        self.ddl_error = ddl_error
        self.duplicate = duplicate
        self.calls = []
        self.result = None
        self.in_transaction = False
        self.committed = False
        self.rolled_back = False

    def connect(self, *args, **kwargs):
        self.connection_args = (args, kwargs)
        return self

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    @contextmanager
    def transaction(self):
        self.in_transaction = True
        try:
            yield
        except BaseException:
            self.rolled_back = True
            raise
        else:
            self.committed = True
        finally:
            self.in_transaction = False

    def cursor(self):
        return self

    def execute(self, sql, params=None, **kwargs):
        assert self.in_transaction
        self.calls.append((sql, params, kwargs))
        if sql == manage._PUBLIC_RELATION_EXISTS:
            self.result = (self.nonempty,)
        elif sql == manage._PUBLIC_TABLE_COUNT:
            self.result = (11,)
        elif sql.startswith("INSERT INTO users") and self.duplicate:
            raise psycopg.errors.UniqueViolation("management-secret internal details")
        elif "CREATE TABLE example" in sql and self.ddl_error:
            raise psycopg.ProgrammingError("management-secret invalid DDL")

    def fetchone(self):
        return self.result


def test_init_locks_checks_and_executes_ddl_in_one_transaction(settings, monkeypatch):
    database = DatabaseDouble()
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    assert manage.init_database(settings) == 11
    assert database.committed and not database.rolled_back
    assert database.calls[0][0] == "SELECT pg_catalog.pg_advisory_xact_lock(%s)"
    assert database.calls[0][1] == (manage._INITIALIZATION_LOCK,)
    assert database.calls[1][0] == manage._PUBLIC_RELATION_EXISTS
    ddl = next(call for call in database.calls if "CREATE TABLE example" in call[0])
    assert ddl == ("CREATE TABLE example (id TEXT);", None, {"prepare": False})
    args, kwargs = database.connection_args
    assert args == (settings.database_url.get_secret_value(),)
    assert kwargs["autocommit"] is True
    assert kwargs["connect_timeout"] == settings.database_timeout_seconds
    assert "lock_timeout=" in kwargs["options"]


def test_existing_schema_is_preserved_after_acquiring_lock(settings, monkeypatch):
    database = DatabaseDouble(nonempty=True)
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    with pytest.raises(manage.ManagementError, match="not empty"):
        manage.init_database(settings)
    assert database.rolled_back and not database.committed
    assert len(database.calls) == 2
    assert database.calls[0][0].startswith("SELECT pg_catalog.pg_advisory_xact_lock")
    assert all("CREATE TABLE" not in sql for sql, *_ in database.calls)


def test_ddl_failure_rolls_back_and_redacts_driver_details(settings, monkeypatch):
    database = DatabaseDouble(ddl_error=True)
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    with pytest.raises(manage.ManagementError, match="rolled back") as error:
        manage.init_database(settings)
    assert database.rolled_back and not database.committed
    assert "management-secret" not in str(error.value)


def test_schema_wrapper_removal_preserves_nested_function_blocks():
    path = Path(__file__).resolve().parents[2] / "docs/database/schema.sql"
    body = manage._schema_body(path)
    assert body.startswith("SET LOCAL TIME ZONE 'UTC';")
    assert "LANGUAGE plpgsql AS $body$\nBEGIN" in body
    assert "CREATE TABLE users" in body
    assert not body.rstrip().endswith("COMMIT;")


@pytest.mark.parametrize("source", ["", "CREATE TABLE unsafe (id TEXT);", "BEGIN;\nCOMMIT;"])
def test_unexpected_schema_wrapper_is_rejected_before_connecting(
    settings, source, monkeypatch
):
    settings.schema_path.write_text(source, encoding="utf-8")

    def forbidden(*args, **kwargs):
        pytest.fail("must reject an unexpected schema before connecting")

    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=forbidden))
    with pytest.raises(manage.ManagementError, match="wrapper"):
        manage.init_database(settings)


@pytest.mark.parametrize("environment", ["staging", "production"])
@pytest.mark.parametrize("command", ["init-db", "seed-user", "migrate-event-person-id"])
def test_bootstrap_is_development_only(settings, environment, command, monkeypatch):
    settings.environment = environment

    def forbidden(*args, **kwargs):
        pytest.fail("non-development bootstrap must not connect")

    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=forbidden))
    with pytest.raises(manage.ManagementError, match="only allowed in development"):
        if command == "init-db":
            manage.init_database(settings)
        elif command == "migrate-event-person-id":
            manage.migrate_event_person_id(settings)
        else:
            manage.seed_user(settings, "viewer_1", "Viewer", "viewer")


def test_seed_parameterizes_values_and_uses_server_utc(settings, monkeypatch):
    database = DatabaseDouble()
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    display_name = "Viewer'); DROP TABLE users; --"
    manage.seed_user(settings, "viewer_1", display_name, "viewer")
    sql, params, _ = database.calls[-1]
    assert display_name not in sql
    assert "ON CONFLICT" not in sql
    assert params[:4] == ("viewer_1", display_name, "viewer", "active")
    assert params[4].utcoffset() == timedelta(0)
    assert database.committed and not database.rolled_back


def test_duplicate_user_does_not_upsert_or_leak_driver_error(settings, monkeypatch):
    database = DatabaseDouble(duplicate=True)
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    with pytest.raises(manage.ManagementError, match="already exists") as error:
        manage.seed_user(settings, "viewer_1", "New admin", "admin")
    assert database.rolled_back and not database.committed
    assert "management-secret" not in str(error.value)
    assert all("UPDATE" not in sql for sql, *_ in database.calls)


@pytest.mark.parametrize(
    "user_id,display_name,role",
    [
        ("bad id", "Viewer", "viewer"),
        ("v" * 101, "Viewer", "viewer"),
        ("viewer_1", "  ", "viewer"),
        ("viewer_1", "Viewer", "owner"),
    ],
)
def test_invalid_user_input_is_rejected_before_connecting(
    settings, user_id, display_name, role, monkeypatch
):
    def forbidden(*args, **kwargs):
        pytest.fail("invalid user parameters must not connect")

    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=forbidden))
    with pytest.raises(manage.ManagementError):
        manage.seed_user(settings, user_id, display_name, role)


def test_cli_reports_errors_without_a_traceback(settings, monkeypatch, capsys):
    monkeypatch.setattr(manage, "Settings", lambda: settings)

    def failure(*args, **kwargs):
        raise psycopg.OperationalError("management-secret private DSN")

    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=failure))
    assert manage.main(["init-db"]) == 1
    output = capsys.readouterr()
    assert not output.out
    assert "initialization failed" in output.err
    assert "management-secret" not in output.err
    assert "Traceback" not in output.err


def test_cli_success_does_not_print_user_details(settings, monkeypatch, capsys):
    monkeypatch.setattr(manage, "Settings", lambda: settings)
    database = DatabaseDouble()
    monkeypatch.setattr(manage, "connection_class", lambda _: SimpleNamespace(connect=database.connect))
    assert manage.main(
        [
            "seed-user", "--user-id", "viewer_1", "--display-name", "Private Name",
            "--role", "viewer",
        ]
    ) == 0
    output = capsys.readouterr()
    assert output.out == "Development user created.\n"
    assert not output.err

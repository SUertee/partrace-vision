"""IAM database credentials: signing must stay byte-identical to the AWS CLI.

The expected values below were produced by botocore's own
``SigV4QueryAuth``/``create_request_object`` path at the frozen timestamp, so a
signing regression fails here instead of only at connection time.
"""

import asyncio
import datetime

import psycopg
import pytest
from pydantic import ValidationError

from backend.core.settings import Settings
from backend.storage.credentials import (
    CredentialUnavailable,
    InstanceCredentials,
    connection_class,
    generate_auth_token,
)

HOST = "database-1.cluster-c74o0swyw1y4.us-west-2.rds.amazonaws.com"
MOMENT = datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)
CREDENTIALS = InstanceCredentials(
    access_key="AKIAIOSFODNN7EXAMPLE",
    secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    session_token="",
    expiration=datetime.datetime(2030, 1, 1, tzinfo=datetime.timezone.utc),
)
PREFIX = (
    f"{HOST}:5432/?Action=connect&DBUser=postgres"
    "&X-Amz-Algorithm=AWS4-HMAC-SHA256"
    "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20240102%2Fus-west-2%2Frds-db%2Faws4_request"
    "&X-Amz-Date=20240102T030405Z&X-Amz-Expires=900"
)


def test_token_without_session_token_matches_reference_signature():
    assert generate_auth_token(HOST, 5432, "postgres", "us-west-2", CREDENTIALS, now=MOMENT) == (
        PREFIX + "&X-Amz-SignedHeaders=host"
        "&X-Amz-Signature=018267f1f6dbc08208893aa77f238bdfbd0ada392004607267e2c1202b2ca25f"
    )


def test_token_with_session_token_matches_reference_signature():
    credentials = CREDENTIALS._replace(session_token="SESSIONTOKENEXAMPLE")
    assert generate_auth_token(HOST, 5432, "postgres", "us-west-2", credentials, now=MOMENT) == (
        PREFIX + "&X-Amz-Security-Token=SESSIONTOKENEXAMPLE&X-Amz-SignedHeaders=host"
        "&X-Amz-Signature=a439262ac543c9425b5c5edffc5a6500ef688882c4e278388dbf1819824d06c0"
    )


def test_credential_failure_is_reported_as_a_database_outage():
    # Every existing handler catches psycopg.OperationalError, so a metadata
    # outage must surface through the same path instead of a 500.
    assert issubclass(CredentialUnavailable, psycopg.OperationalError)


def test_iam_authentication_requires_a_signing_region():
    with pytest.raises(ValidationError):
        Settings(
            _env_file=None,
            database_url=f"postgresql://postgres@{HOST}:5432/partrace",
            database_iam_auth=True,
        )


def test_iam_authentication_requires_a_database_user():
    with pytest.raises(ValidationError):
        Settings(
            _env_file=None,
            database_url=f"postgresql://{HOST}:5432/partrace",
            database_iam_auth=True,
            database_iam_region="us-west-2",
        )


def test_stock_connection_class_is_used_when_iam_authentication_is_off():
    settings = Settings(_env_file=None, database_url="postgresql://u:p@example.invalid:5432/partrace")
    assert connection_class(settings) is psycopg.Connection
    assert connection_class(settings, asynchronous=True) is psycopg.AsyncConnection


def _iam_settings():
    return Settings(
        _env_file=None,
        database_url=f"postgresql://postgres@{HOST}:5432/partrace",
        database_iam_auth=True,
        database_iam_region="us-west-2",
    )


def test_connection_class_injects_a_fresh_token(monkeypatch):
    monkeypatch.setattr(
        "backend.storage.credentials._fetch_instance_credentials", lambda: CREDENTIALS
    )
    settings = _iam_settings()
    factory = connection_class(settings)
    assert issubclass(factory, psycopg.Connection)
    parameters = factory._get_connection_params(settings.database_url.get_secret_value())
    assert parameters["password"].startswith(f"{HOST}:5432/?Action=connect&DBUser=postgres")
    assert "X-Amz-Signature=" in parameters["password"]
    # The URL parameters must survive the injection untouched.
    assert parameters["host"] == HOST
    assert parameters["dbname"] == "partrace"


def test_async_connection_class_injects_a_fresh_token(monkeypatch):
    monkeypatch.setattr(
        "backend.storage.credentials._fetch_instance_credentials", lambda: CREDENTIALS
    )
    settings = _iam_settings()
    factory = connection_class(settings, asynchronous=True)
    assert issubclass(factory, psycopg.AsyncConnection)
    parameters = asyncio.run(
        factory._get_connection_params(settings.database_url.get_secret_value())
    )
    assert parameters["password"].startswith(f"{HOST}:5432/?Action=connect&DBUser=postgres")

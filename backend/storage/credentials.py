"""Short-lived IAM database credentials for clusters that require IAM authentication.

An Aurora cluster behind an Internet Access Gateway rejects password logins for
every role, including the master user, so each new connection must present a
freshly signed token. Tokens are signed locally from the EC2 instance role's
temporary credentials, which are read from the instance metadata service and
kept in memory only. Nothing is written to disk and no static password is
configured anywhere.

The signature follows the same rules as ``aws rds generate-db-auth-token``: a
SigV4 presigned ``GET https://<host>:<port>/?Action=connect&DBUser=<user>`` URL,
signed over the empty-body digest with the ``host`` header. It is reimplemented
here with the standard library so the container image keeps its hash-pinned
dependency set unchanged.
"""

from __future__ import annotations

import datetime
import hashlib
import hmac
import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import NamedTuple

import psycopg
from psycopg.conninfo import conninfo_to_dict

_METADATA_ENDPOINT = "http://169.254.169.254"
_METADATA_TIMEOUT_SECONDS = 3
_METADATA_TOKEN_TTL_SECONDS = "300"
_ALGORITHM = "AWS4-HMAC-SHA256"
_SERVICE = "rds-db"
_TOKEN_TTL_SECONDS = 900
_REFRESH_MARGIN = datetime.timedelta(minutes=5)
_EMPTY_BODY_SHA256 = hashlib.sha256(b"").hexdigest()


class CredentialUnavailable(psycopg.OperationalError):
    """Credentials could not be obtained.

    Subclasses ``psycopg.OperationalError`` so every existing database outage
    path (readiness probe, request transactions, executor lease) already treats
    it as "database temporarily unavailable" without special casing.
    """


class InstanceCredentials(NamedTuple):
    access_key: str
    secret_key: str
    session_token: str
    expiration: datetime.datetime


def _sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def _signing_key(secret_key: str, datestamp: str, region: str, service: str) -> bytes:
    key = f"AWS4{secret_key}".encode("utf-8")
    for part in (datestamp, region, service, "aws4_request"):
        key = _sign(key, part)
    return key


def _metadata(path: str, token: str | None = None) -> str:
    headers = {"X-aws-ec2-metadata-token": token} if token else {}
    request = urllib.request.Request(f"{_METADATA_ENDPOINT}{path}", headers=headers)
    with urllib.request.urlopen(request, timeout=_METADATA_TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8")


def _metadata_token() -> str:
    request = urllib.request.Request(
        f"{_METADATA_ENDPOINT}/latest/api/token",
        method="PUT",
        headers={"X-aws-ec2-metadata-token-ttl-seconds": _METADATA_TOKEN_TTL_SECONDS},
    )
    with urllib.request.urlopen(request, timeout=_METADATA_TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8")


def _fetch_instance_credentials() -> InstanceCredentials:
    """Read the instance role's temporary credentials from IMDSv2."""
    try:
        token = _metadata_token()
        role = _metadata("/latest/meta-data/iam/security-credentials/", token).strip().splitlines()[0]
        payload = json.loads(_metadata(f"/latest/meta-data/iam/security-credentials/{role}", token))
        expiration = datetime.datetime.fromisoformat(
            payload["Expiration"].replace("Z", "+00:00")
        )
        return InstanceCredentials(
            access_key=payload["AccessKeyId"],
            secret_key=payload["SecretAccessKey"],
            session_token=payload["Token"],
            expiration=expiration,
        )
    except (OSError, ValueError, KeyError, IndexError, urllib.error.URLError):
        # Deliberately opaque: never surface metadata responses or credential bytes.
        raise CredentialUnavailable(
            "IAM database credentials are unavailable; the instance role was not reachable"
        ) from None


class _CredentialCache:
    """Reuse the instance role credentials until shortly before they expire."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._value: InstanceCredentials | None = None

    def get(self) -> InstanceCredentials:
        with self._lock:
            now = datetime.datetime.now(datetime.timezone.utc)
            if self._value is not None and self._value.expiration - _REFRESH_MARGIN > now:
                return self._value
            self._value = _fetch_instance_credentials()
            return self._value


def generate_auth_token(
    host: str,
    port: int,
    username: str,
    region: str,
    credentials: InstanceCredentials,
    *,
    now: datetime.datetime | None = None,
    expires: int = _TOKEN_TTL_SECONDS,
) -> str:
    """Sign a token equivalent to ``aws rds generate-db-auth-token``."""
    moment = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = moment.strftime("%Y%m%dT%H%M%SZ")
    datestamp = moment.strftime("%Y%m%d")
    scope = f"{datestamp}/{region}/{_SERVICE}/aws4_request"

    parameters = {
        "Action": "connect",
        "DBUser": username,
        "X-Amz-Algorithm": _ALGORITHM,
        "X-Amz-Credential": f"{credentials.access_key}/{scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": "host",
    }
    if credentials.session_token:
        parameters["X-Amz-Security-Token"] = credentials.session_token

    quote = lambda value: urllib.parse.quote(value, safe="-_.~")  # noqa: E731
    query = "&".join(f"{quote(k)}={quote(v)}" for k, v in sorted(parameters.items()))
    canonical_request = "\n".join(
        ["GET", "/", query, f"host:{host}:{port}", "", "host", _EMPTY_BODY_SHA256]
    )
    string_to_sign = "\n".join(
        [_ALGORITHM, amz_date, scope, hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()]
    )
    signature = hmac.new(
        _signing_key(credentials.secret_key, datestamp, region, _SERVICE),
        string_to_sign.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return f"{host}:{port}/?{query}&X-Amz-Signature={signature}"


def _signing_connection_class(base, provider: Callable[[], str], *, asynchronous: bool):
    """Build a Connection subclass that signs a token before every connect.

    psycopg resolves connection parameters once per physical connection, so a
    pooled connection never reuses an expired token and no refresh timer is
    needed. (psycopg does not accept a callable for ``password`` itself.)
    ``AsyncConnection`` resolves parameters in a coroutine, so it needs its own
    shape rather than one shared implementation.
    """
    if asynchronous:

        class SigningConnection(base):  # type: ignore[misc, valid-type]
            @classmethod
            async def _get_connection_params(cls, conninfo, **kwargs):
                parameters = await super()._get_connection_params(conninfo, **kwargs)
                parameters["password"] = provider()
                return parameters

    else:

        class SigningConnection(base):  # type: ignore[misc, valid-type]
            @classmethod
            def _get_connection_params(cls, conninfo, **kwargs):
                parameters = super()._get_connection_params(conninfo, **kwargs)
                parameters["password"] = provider()
                return parameters

    return SigningConnection


def connection_class(settings, *, asynchronous: bool = False):
    """Return the psycopg Connection class to use for this configuration.

    Without IAM authentication this is the stock class, so the default
    password-based deployment is unchanged.
    """
    base = psycopg.AsyncConnection if asynchronous else psycopg.Connection
    if not settings.database_iam_auth:
        return base
    try:
        connection = conninfo_to_dict(settings.database_url.get_secret_value())
    except psycopg.Error:
        raise CredentialUnavailable("IAM database authentication has no usable URL") from None
    host = connection.get("host")
    username = connection.get("user")
    region = settings.database_iam_region
    if not host or not username or not region:
        raise CredentialUnavailable(
            "IAM database authentication requires a host, a user and a signing region"
        )
    port = int(connection.get("port") or 5432)
    cache = _CredentialCache()

    def provider() -> str:
        return generate_auth_token(host, port, username, region, cache.get())

    return _signing_connection_class(base, provider, asynchronous=asynchronous)

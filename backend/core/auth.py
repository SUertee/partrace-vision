"""Local, revocable Bearer credential registry containing only SHA-256 digests.

The registry supplies an identity and resource grants, never a database role.
The application still verifies the current user row and operation permissions.
"""

import hashlib
import hmac
import json
import os
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated, Self

from pydantic import Field, StringConstraints, ValidationError, model_validator

from backend.core.errors import ApiError
from backend.core.models import Id, StrictModel


@dataclass(frozen=True)
class Principal:
    user_id: str
    scopes: frozenset[str]
    camera_ids: frozenset[str]
    recording_ids: frozenset[str] | None


class _Token(StrictModel):
    token_sha256: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    user_id: Id
    scopes: list[Annotated[str, StringConstraints(min_length=1, max_length=100)]]
    camera_ids: list[Id]
    recording_ids: list[Id] | None = None

    @model_validator(mode="after")
    def unique_grants(self) -> Self:
        for grants in (self.scopes, self.camera_ids, self.recording_ids):
            if grants is not None and len(grants) != len(set(grants)):
                raise ValueError("credential grant entries must not be duplicated")
        return self


class _CredentialFile(StrictModel):
    tokens: Annotated[list[_Token], Field(min_length=1, max_length=10000)]

    @model_validator(mode="after")
    def unique_credentials(self) -> Self:
        hashes = [entry.token_sha256 for entry in self.tokens]
        if len(hashes) != len(set(hashes)):
            raise ValueError("credential digests must not be duplicated")
        return self


def _json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON member")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ValueError("non-finite JSON value")


class AuthRegistry:
    def __init__(self, path: str | Path):
        self._path = Path(path)
        self._read()

    def _read(self) -> _CredentialFile:
        descriptor = None
        try:
            descriptor = os.open(self._path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            metadata = os.fstat(descriptor)
            if (
                not stat.S_ISREG(metadata.st_mode)
                or metadata.st_mode & 0o077
                or metadata.st_uid not in {0, os.geteuid()}
                or metadata.st_size > 2 * 1024 * 1024
            ):
                raise ValueError("credential file is not protected")
            with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
                descriptor = None
                payload = json.load(
                    stream, object_pairs_hook=_json_object, parse_constant=_invalid_constant,
                )
            return _CredentialFile.model_validate(payload)
        except (OSError, UnicodeError, ValueError, ValidationError):
            raise ApiError(503, "auth_unavailable", "Authentication configuration is unavailable") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)

    def authenticate(self, token: str) -> Principal:
        # Re-read on every request so removing a grant or token takes effect.
        registry = self._read()
        if not isinstance(token, str) or not token or len(token) > 4096 or any(
            character.isspace() for character in token
        ):
            raise ApiError(401, "unauthorized", "A valid Bearer token is required")
        try:
            digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        except UnicodeError:
            raise ApiError(401, "unauthorized", "A valid Bearer token is required") from None
        matched = None
        for entry in registry.tokens:
            if hmac.compare_digest(digest, entry.token_sha256):
                matched = entry
        if matched is None:
            raise ApiError(401, "unauthorized", "A valid Bearer token is required")
        return Principal(
            user_id=matched.user_id,
            scopes=frozenset(matched.scopes),
            camera_ids=frozenset(matched.camera_ids),
            recording_ids=(frozenset(matched.recording_ids) if matched.recording_ids is not None else None),
        )

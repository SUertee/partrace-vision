"""Startup-loaded, pinned snapshots of immutable camera/ROI configurations."""

import copy
import hashlib
import json
import os
import re
import stat
from pathlib import Path

from pydantic import TypeAdapter, ValidationError

from backend.core.errors import ApiError
from backend.core.models import Config, Id


def _json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON member")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ValueError("non-finite JSON value")


def config_signature(snapshot: dict) -> str:
    """Digest a validated snapshot identically when preparing administrator pins."""
    canonical = json.dumps(
        snapshot, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


class ConfigRegistry:
    def __init__(self, directory: str | Path, pins: dict[str, str] | None = None):
        self._snapshots: dict[str, dict] = {}
        self._signatures: dict[str, str] = {}
        try:
            root = Path(directory)
            if not root.is_dir() or root.is_symlink():
                raise ValueError("configuration directory is unavailable")
            for path in sorted(root.glob("*.json")):
                descriptor = None
                try:
                    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                    metadata = os.fstat(descriptor)
                    if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 2 * 1024 * 1024:
                        raise ValueError("invalid configuration file")
                    with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
                        descriptor = None
                        payload = json.load(
                            stream, object_pairs_hook=_json_object, parse_constant=_invalid_constant,
                        )
                finally:
                    if descriptor is not None:
                        os.close(descriptor)
                snapshot = Config.model_validate(payload).model_dump(mode="json")
                version = snapshot["config_version"]
                if version != path.stem or version in self._snapshots:
                    raise ValueError("configuration version or file name conflicts")
                self._snapshots[version] = snapshot
                self._signatures[version] = config_signature(snapshot)
            if not self._snapshots:
                raise ValueError("at least one configuration snapshot is required")
            if pins is not None:
                if not isinstance(pins, dict) or set(pins) != set(self._snapshots):
                    raise ValueError("configuration versions do not match pinned versions")
                for version, digest in pins.items():
                    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
                        raise ValueError("invalid configuration signature")
                    if digest != self._signatures[version]:
                        raise ValueError("configuration snapshot conflicts with its pinned signature")
        except (OSError, UnicodeError, ValueError, ValidationError):
            raise ApiError(503, "config_unavailable", "Configuration snapshots are unavailable or conflict") from None

    def _version(self, version: str) -> str:
        try:
            version = TypeAdapter(Id).validate_python(version)
        except ValidationError:
            raise ApiError(422, "validation_error", "Invalid configuration version") from None
        if version not in self._snapshots:
            raise ApiError(404, "config_not_found", "Configuration version was not found")
        return version

    def load(self, version: str) -> dict:
        return copy.deepcopy(self._snapshots[self._version(version)])

    def signature(self, version: str) -> str:
        return self._signatures[self._version(version)]

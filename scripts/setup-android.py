#!/usr/bin/env python3
"""Prepare ignored Android configuration files and optionally install the SDK."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import sys
import tempfile


SDK_SHA256 = "27916001d9f973361910e2a59da3a870f540f3038816dfc6141b3d33c633f87a"
SDK_NAME = "ESIotKit-2.2.7.aar"
REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID_ROOT = REPO_ROOT / "android"
SAMPLE_ROOT = (
    ANDROID_ROOT
    / "core"
    / "src"
    / "commonMain"
    / "kotlin"
    / "com"
    / "anker"
    / "esiotkit"
    / "sample"
)
CONFIGURATIONS = (
    (
        "BusinessLoginConfig.kt",
        Path("main/mega/login/business/BusinessLoginConfig.kt"),
        {"CLIENT_SECRET": 2, "USER_ID": 2, "TOKEN": 2},
    ),
    ("RouterConfig.kt", Path("demo/RouterConfig.kt"), {"API_KEY": 1}),
    (
        "AwsConfig.kt",
        Path("demo/AwsConfig.kt"),
        {
            "BEDROCK_ACCESS_KEY": 1,
            "BEDROCK_SECRET_KEY": 1,
            "TRANSCRIBE_ACCESS_KEY": 1,
            "TRANSCRIBE_SECRET_KEY": 1,
        },
    ),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_templates() -> list[tuple[Path, str]]:
    """Reject a template containing a credential without printing its value."""
    templates = []
    for name, destination, fields in CONFIGURATIONS:
        path = ANDROID_ROOT / "config-templates" / (name + ".template")
        content = path.read_text(encoding="utf-8")
        for field, count in fields.items():
            pattern = (
                r"^\s*const\s+val\s+"
                + re.escape(field)
                + r'\s*=\s*"((?:[^"\\]|\\.)*)"'
            )
            values = re.findall(pattern, content, flags=re.MULTILINE)
            if len(values) != count or any(value != "" for value in values):
                raise ValueError(f"Template must contain empty {field}: {path.name}")
        templates.append((SAMPLE_ROOT / destination, content))
    return templates


def prepare_configurations(templates: list[tuple[Path, str]]) -> None:
    for destination, content in templates:
        relative = destination.relative_to(REPO_ROOT)
        if destination.exists():
            print(f"Config: kept existing {relative}")
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation also prevents overwriting a file created concurrently.
        with destination.open("x", encoding="utf-8") as output:
            output.write(content)
        print(f"Config: created {relative}")


def install_sdk(source: Path, destination: Path) -> None:
    if not source.is_file():
        raise ValueError("SDK source must be an existing .aar file")
    if source.suffix.lower() != ".aar":
        raise ValueError("SDK source must have the .aar extension")
    if sha256(source) != SDK_SHA256:
        raise ValueError("SDK source SHA-256 does not match the official 2.2.7 package")
    if destination.is_file() and sha256(destination) == SDK_SHA256:
        print("SDK: verified existing ESIotKit-2.2.7.aar")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=".esiotkit-", suffix=".tmp", dir=destination.parent, delete=False
        ) as output:
            temporary = Path(output.name)
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, output)
        if sha256(temporary) != SDK_SHA256:
            raise ValueError("SDK changed during copying; installation cancelled")
        temporary.replace(destination)
        print("SDK: installed and verified ESIotKit-2.2.7.aar")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--sdk", type=Path, metavar="PATH.aar", help="local official ESIotKit 2.2.7 AAR"
    )
    args = parser.parse_args()
    sdk = ANDROID_ROOT / "libs" / SDK_NAME
    try:
        templates = read_templates()
        # Validate the optional SDK before creating any local configuration files.
        if args.sdk is not None:
            install_sdk(args.sdk.expanduser(), sdk)
        prepare_configurations(templates)
        if not sdk.is_file():
            print("SDK: missing; rerun with --sdk /path/to/ESIotKit-2.2.7.aar")
        elif sha256(sdk) == SDK_SHA256:
            print("SDK: present; SHA-256 verified")
        else:
            raise ValueError("Existing SDK SHA-256 is invalid; rerun with --sdk")
    except (OSError, ValueError) as error:
        print(f"Setup error: {error}", file=sys.stderr)
        return 1
    print("Local setup checked. No Gradle build or device login was performed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

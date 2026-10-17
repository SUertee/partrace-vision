#!/usr/bin/env python3
"""Prepare an ignored Android development API credential; never copy model/DB secrets."""

import argparse
import json
import os
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "android/core/src/androidDebug/assets/partrace-agent.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://10.0.2.2:8000",
                        help="Backend origin, without /api/v1; defaults to the emulator host")
    parser.add_argument("--credentials", type=Path, default=ROOT / ".local-state/local/secrets/bootstrap-credentials.json")
    parser.add_argument("--role", choices=("viewer", "admin"), default="viewer")
    parser.add_argument("--force", action="store_true", help="Replace an existing local Android API configuration")
    args = parser.parse_args()
    try:
        url = urlsplit(args.base_url.strip())
        local = url.hostname in {"localhost", "127.0.0.1", "::1", "10.0.2.2"}
        if (not url.hostname or url.username or url.password or url.query or url.fragment
                or url.path not in ("", "/") or url.port == 0
                or not (url.scheme == "https" or (url.scheme == "http" and local))):
            raise ValueError("Use an HTTPS origin or a development loopback HTTP origin without /api/v1.")
        if TARGET.exists() and not args.force:
            raise ValueError("Local Android API configuration already exists; use --force to replace it.")
        credentials = json.loads(args.credentials.read_text())
        token = credentials[args.role]["token"]
        if not isinstance(token, str) or not token.strip() or any(c.isspace() for c in token):
            raise ValueError("The selected backend API credential is invalid.")
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        payload = {"base_url": args.base_url.strip().rstrip("/"), "bearer_token": token}
        # Atomic, owner-only temporary file; never expose token in shell arguments or output.
        temporary = TARGET.with_suffix(".tmp")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as output:
                output.write(json.dumps(payload) + "\n")
            temporary.replace(TARGET)
        finally:
            temporary.unlink(missing_ok=True)
        print("Prepared ignored local Android API configuration:", TARGET.relative_to(ROOT))
        print("Only the backend API credential is included. Rebuild the development APK to apply it.")
    except (OSError, ValueError, KeyError, TypeError):
        parser.exit(1, "Could not prepare Android API configuration. Check the origin, private credential file, role, and existing target.\n")


if __name__ == "__main__":
    main()

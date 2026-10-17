#!/usr/bin/env python3
"""Prepare a private, explicitly marked local demo; never overwrite existing state."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
sys.path.insert(0, str(ROOT))


def private_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def run(*args, check=True):
    return subprocess.run(args, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-db", action="store_true", help="Create a new isolated local pgvector Docker container")
    args = parser.parse_args()
    state = ROOT / ".local-state"
    env_path = BACKEND / ".env"
    if env_path.exists() or (state / "bootstrap-credentials.json").exists():
        parser.error("Local configuration already exists; refusing to replace credentials or data.")
    state.mkdir(mode=0o700, exist_ok=True)
    database_url = os.environ.get("PARTRACE_DATABASE_URL")
    if args.start_db:
        name = "partrace-mvp-dev"
        if run("docker", "container", "inspect", name, check=False).returncode == 0:
            parser.error("The local container already exists; inspect its configuration before retrying.")
        if run("docker", "volume", "inspect", "partrace-mvp-dev-data", check=False).returncode == 0:
            parser.error("A local data volume already exists; refusing to reuse it with different credentials.")
        admin_password, app_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        db_env = state / "postgres-dev.env"
        with db_env.open("x") as stream:
            os.chmod(db_env, 0o600)
            stream.write(f"POSTGRES_DB=partrace\nPOSTGRES_USER=partrace_admin\nPOSTGRES_PASSWORD={admin_password}\n")
            stream.write(f"PARTRACE_DATABASE_USER=partrace_app\nPARTRACE_DATABASE_PASSWORD={app_password}\n")
        try:
            run("docker", "run", "--detach", "--name", name, "--label", "partrace.local-mvp=true",
                "--restart", "unless-stopped", "--publish", "127.0.0.1:54391:5432", "--env-file", str(db_env),
                "--volume", "partrace-mvp-dev-data:/var/lib/postgresql/data",
                "--mount", f"type=bind,src={ROOT / 'infra/postgres/init/10-init.sh'},dst=/docker-entrypoint-initdb.d/10-init.sh,readonly",
                "pgvector/pgvector:0.8.7-pg17")
            for _ in range(40):
                if run("docker", "exec", name, "pg_isready", "-U", "partrace_admin", "-d", "partrace", check=False).returncode == 0:
                    break
                time.sleep(0.5)
            else:
                parser.error("PostgreSQL did not become ready; inspect the isolated container. Credentials were retained privately.")
        except (OSError, subprocess.CalledProcessError):
            parser.error("Cannot start local Docker PostgreSQL. Existing container/volume state was preserved for inspection.")
        database_url = f"postgresql://partrace_app:{app_password}@127.0.0.1:54391/partrace"
    if not database_url:
        parser.error("Provide PARTRACE_DATABASE_URL in the environment, or use --start-db for an isolated local container.")

    from backend.core.configuration import config_signature
    from backend.core.models import Config

    configs = state / "configs"
    configs.mkdir(mode=0o700, exist_ok=True)
    snapshot = Config.model_validate(json.loads((BACKEND / "examples/config-demo.example.json").read_text())).model_dump(mode="json")
    private_json(configs / (snapshot["config_version"] + ".json"), snapshot)
    private_json(state / "config-pins.json", {snapshot["config_version"]: config_signature(snapshot)})
    admin_token, viewer_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    credentials = {"admin": {"user_id": "local_admin", "token": admin_token}, "viewer": {"user_id": "local_viewer", "token": viewer_token}}
    private_json(state / "bootstrap-credentials.json", credentials)
    private_json(state / "auth.json", {"tokens": [
        {"token_sha256": hashlib.sha256(admin_token.encode()).hexdigest(), "user_id": "local_admin", "scopes": ["data:read", "data:ingest", "reviews:write"], "camera_ids": ["cam_demo_01"]},
        {"token_sha256": hashlib.sha256(viewer_token.encode()).hexdigest(), "user_id": "local_viewer", "scopes": ["data:read"], "camera_ids": ["cam_demo_01"]},
    ]})
    (state / "media").mkdir(mode=0o700, exist_ok=True)
    with env_path.open("x") as stream:
        os.chmod(env_path, 0o600)
        stream.write("PARTRACE_ENVIRONMENT=development\n")
        stream.write(f"PARTRACE_DATABASE_URL={database_url}\n")
        for key, path in {"AUTH_FILE": state / "auth.json", "CONFIGS_DIR": configs, "CONFIG_PINS_FILE": state / "config-pins.json", "MEDIA_ROOT": state / "media", "SCHEMA_PATH": ROOT / "docs/database/schema.sql"}.items():
            stream.write(f"PARTRACE_{key}='{path}'\n")
    print("Local development configuration prepared. No business tables or users were created.")
    print(f"Environment: {env_path}")
    print(f"Private demo credentials: {state / 'bootstrap-credentials.json'} (not printed)")
    print("Next: uv run python -m backend.manage init-db")
    print("Then seed local_admin and local_viewer using the documented commands.")


if __name__ == "__main__":
    main()

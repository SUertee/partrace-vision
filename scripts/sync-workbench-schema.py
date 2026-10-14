#!/usr/bin/env python3
"""Refresh only the workbench's offline schema payload from its source HTML."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_PATH = REPO_ROOT / "docs/database/schema-canvas.html"
WORKBENCH_PATH = REPO_ROOT / "docs/workbench/index.html"
CATALOG_PATH = REPO_ROOT / "docs/database/schema-catalog.json"
PAYLOAD = re.compile(r"window\.WORKBENCH_OFFLINE_DOCUMENTS=(\{[^\n]*\});")
META = re.compile(r"window\.WORKBENCH_SCHEMA_META=Object\.freeze\((\{[^\n]*\})\);")


def script_json(value: object) -> str:
    """Escape HTML-significant characters so payload text cannot end a script."""
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def embedded_document(source: str) -> str:
    opening = re.search(r"<html\b[^>]*>", source, re.IGNORECASE)
    if not opening:
        raise ValueError("Schema source must be a complete HTML document.")
    tag = opening.group()
    attribute = re.search(r"\bclass\s*=\s*([\"'])(.*?)\1", tag, re.IGNORECASE)
    if attribute:
        classes = attribute.group(2).split()
        if "embedded" not in classes:
            classes.append("embedded")
        replacement = 'class="' + " ".join(classes) + '"'
        tag = tag[: attribute.start()] + replacement + tag[attribute.end() :]
    else:
        tag = tag[:-1] + ' class="embedded">'
    return source[: opening.start()] + tag + source[opening.end() :]


def updated_workbench(workbench: str, source: str) -> tuple[str, dict[str, object]]:
    matches = list(PAYLOAD.finditer(workbench))
    if len(matches) != 1:
        raise ValueError("Expected exactly one WORKBENCH_OFFLINE_DOCUMENTS payload.")
    payload = matches[0]
    documents = json.loads(payload.group(1))
    if not isinstance(documents, dict):
        raise ValueError("The offline documents payload must be an object.")
    if "database" in documents and not isinstance(documents["database"], str):
        raise ValueError("The database payload must be an HTML string.")
    embedded = embedded_document(source)
    # Keep every unrelated prototype/architecture payload byte-for-byte intact.
    # JSONDecoder locates each top-level value, avoiding matches inside strings.
    decoder = json.JSONDecoder()
    encoded = payload.group(1)
    position = 1
    schema_spans = []
    while position < len(encoded):
        while encoded[position].isspace() or encoded[position] == ",":
            position += 1
        if encoded[position] == "}":
            break
        key, position = decoder.raw_decode(encoded, position)
        while encoded[position].isspace():
            position += 1
        if encoded[position] != ":":
            raise ValueError("Malformed offline documents JSON.")
        position += 1
        while encoded[position].isspace():
            position += 1
        start = position
        _, position = decoder.raw_decode(encoded, position)
        if key == "database":
            schema_spans.append((start, position))
    if len(schema_spans) > 1:
        raise ValueError("Expected only one top-level database payload.")
    if schema_spans:
        start, end = schema_spans[0]
        refreshed = encoded[:start] + script_json(embedded) + encoded[end:]
    else:
        comma = "," if documents else ""
        refreshed = encoded[:-1] + comma + '"database":' + script_json(embedded) + "}"
    replacement = "window.WORKBENCH_OFFLINE_DOCUMENTS=" + refreshed + ";"
    result = workbench[: payload.start()] + replacement + workbench[payload.end() :]
    metadata = {
        "version": 1,
        "generator": "scripts/sync-workbench-schema.py",
        "source": "docs/database/schema-canvas.html",
        "sourceSha256": hashlib.sha256(source.encode("utf-8")).hexdigest(),
        "embeddedSha256": hashlib.sha256(embedded.encode("utf-8")).hexdigest(),
    }
    statement = "window.WORKBENCH_SCHEMA_META=Object.freeze(" + script_json(metadata) + ");"
    metadata_matches = list(META.finditer(result))
    if len(metadata_matches) > 1:
        raise ValueError("Expected at most one schema provenance marker.")
    if metadata_matches:
        marker = metadata_matches[0]
        result = result[: marker.start()] + statement + result[marker.end() :]
    else:
        marker = PAYLOAD.search(result)
        result = result[: marker.end()] + "\n" + statement + result[marker.end() :]
    return result, metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check freshness without writing files.")
    args = parser.parse_args()
    try:
        source = SCHEMA_PATH.read_text(encoding="utf-8")
        schema_data = re.search(r'<script id="schema-data" type="application/json">(.*?)</script>', source, re.DOTALL)
        if not schema_data:
            raise ValueError("Schema HTML has no catalog payload; regenerate it before synchronization.")
        embedded_catalog = json.loads(schema_data.group(1))
        api_mapping = embedded_catalog.pop("api_mapping", {})
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        sql_hash = hashlib.sha256((REPO_ROOT / "docs/database/schema.sql").read_bytes()).hexdigest()
        if embedded_catalog != catalog or catalog.get("metadata", {}).get("schema_sha256") != sql_hash:
            raise ValueError("Schema HTML/catalog is stale; regenerate the catalog and HTML before synchronization.")
        mapping_hash = hashlib.sha256((REPO_ROOT / "docs/api/feature-data-api-map.json").read_bytes()).hexdigest()
        openapi_hash = hashlib.sha256((REPO_ROOT / "docs/api/openapi.json").read_bytes()).hexdigest()
        if api_mapping.get("source_sha256") != mapping_hash or api_mapping.get("openapi_sha256") != openapi_hash:
            raise ValueError("Schema API annotations are stale; regenerate the HTML before synchronization.")
        original = WORKBENCH_PATH.read_text(encoding="utf-8")
        updated, metadata = updated_workbench(original, source)
    except (OSError, ValueError, IndexError) as error:
        parser.exit(2, f"Schema synchronization failed: {error}\n")
    changed = updated != original
    if args.check and changed:
        print("Workbench schema is stale; run python3 scripts/sync-workbench-schema.py.")
        return 1
    if changed:
        WORKBENCH_PATH.write_text(updated, encoding="utf-8")
    status = "already current" if not changed else "updated"
    print(f"Workbench schema {status}; source SHA-256 {metadata['sourceSha256']}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

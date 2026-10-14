#!/usr/bin/env python3
"""Embed only the generated API page and its provenance in the offline workbench."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs/api/api-canvas.html"
WORKBENCH = ROOT / "docs/workbench/index.html"
PAYLOAD = re.compile(r"window\.WORKBENCH_OFFLINE_DOCUMENTS=(\{[^\n]*\});")
META = re.compile(r"window\.WORKBENCH_API_META=Object\.freeze\((\{[^\n]*\})\);")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def script_json(value: object) -> str:
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
        raise ValueError("API source must be a complete HTML document.")
    tag = opening.group()
    attribute = re.search(r"\bclass\s*=\s*([\"'])(.*?)\1", tag, re.IGNORECASE)
    if attribute:
        classes = attribute.group(2).split()
        if "embedded" not in classes:
            classes.append("embedded")
        tag = tag[: attribute.start()] + 'class="' + " ".join(classes) + '"' + tag[attribute.end() :]
    else:
        tag = tag[:-1] + ' class="embedded">'
    return source[: opening.start()] + tag + source[opening.end() :]


def updated_workbench(original: str, source: str) -> tuple[str, dict[str, object]]:
    matches = list(PAYLOAD.finditer(original))
    if len(matches) != 1:
        raise ValueError("Expected exactly one WORKBENCH_OFFLINE_DOCUMENTS payload.")
    match = matches[0]
    encoded = match.group(1)
    documents = json.loads(encoded)
    if not isinstance(documents, dict):
        raise ValueError("Offline documents must be an object.")
    decoder = json.JSONDecoder()
    position, spans = 1, []
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
        if key == "api":
            spans.append((start, position))
    if len(spans) > 1:
        raise ValueError("Duplicate API document.")
    embedded = embedded_document(source)
    if spans:
        start, end = spans[0]
        refreshed = encoded[:start] + script_json(embedded) + encoded[end:]
    else:
        refreshed = encoded[:-1] + ("," if documents else "") + '"api":' + script_json(embedded) + "}"
    result = original[: match.start()] + "window.WORKBENCH_OFFLINE_DOCUMENTS=" + refreshed + ";" + original[match.end() :]
    metadata = {
        "version": 1,
        "generator": "scripts/sync-workbench-api.py",
        "source": "docs/api/api-canvas.html",
        "sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
        "embeddedSha256": hashlib.sha256(embedded.encode()).hexdigest(),
    }
    statement = "window.WORKBENCH_API_META=Object.freeze(" + script_json(metadata) + ");"
    markers = list(META.finditer(result))
    if len(markers) > 1:
        raise ValueError("Duplicate API provenance marker.")
    if markers:
        marker = markers[0]
        result = result[: marker.start()] + statement + result[marker.end() :]
    else:
        marker = PAYLOAD.search(result)
        result = result[: marker.end()] + "\n" + statement + result[marker.end() :]
    return result, metadata


def validate_source(source: str) -> None:
    match = re.search(r'<script id="api-data" type="application/json">(.*?)</script>', source, re.DOTALL)
    if not match:
        raise ValueError("API source has no generated data; run build-api-canvas.py first.")
    data = json.loads(match.group(1))
    metadata = data["metadata"]
    inputs = {
        "source_sha256": "docs/api/openapi.json",
        "mapping_sha256": "docs/api/api-module-map.json",
        "feature_map_sha256": "docs/api/feature-data-api-map.json",
        "schema_sha256": "docs/database/schema.sql",
        "catalog_sha256": "docs/database/schema-catalog.json",
    }
    for key, path in inputs.items():
        if metadata.get(key) != sha(ROOT / path):
            raise ValueError(f"Stale API source: {path}; regenerate API HTML.")
    for path, digest in metadata.get("fixture_sha256", {}).items():
        resolved = (ROOT / path).resolve()
        if not resolved.is_relative_to(ROOT) or sha(resolved) != digest:
            raise ValueError(f"Stale or invalid fixture source: {path}.")
    api = json.loads((ROOT / "docs/api/openapi.json").read_text())
    expected = {
        (method.upper(), path)
        for path, item in api["paths"].items()
        for method in item
        if method in {"get", "post", "put", "patch", "delete", "head", "options"}
    }
    actual = [(item["method"].upper(), item["path"]) for item in data["operations"]]
    if len(actual) != len(set(actual)) or set(actual) != expected or metadata.get("operation_count") != len(expected):
        raise ValueError("Generated API page does not match current OpenAPI operations.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check freshness without modifying files.")
    args = parser.parse_args()
    try:
        source = SOURCE.read_text(encoding="utf-8")
        validate_source(source)
        original = WORKBENCH.read_text(encoding="utf-8")
        updated, metadata = updated_workbench(original, source)
    except (OSError, ValueError, KeyError, IndexError) as error:
        parser.exit(2, f"API synchronization failed: {error}\n")
    changed = updated != original
    if args.check and changed:
        print("Workbench API is stale; run python3 scripts/sync-workbench-api.py.")
        return 1
    if changed:
        WORKBENCH.write_text(updated, encoding="utf-8")
    status = "updated" if changed else "already current"
    print(f"Workbench API {status}; source SHA-256 {metadata['sourceSha256']}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

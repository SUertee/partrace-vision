#!/usr/bin/env python3
"""Export loaded PostgreSQL+pgvector catalog definitions without reading records.

Live export: --container NAME [--database DB --user USER] or --database-url DSN.
Offline --check validates the saved verified snapshot, never reruns PostgreSQL.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLE_META = {
    "users": ("用户", "review", "App 用户的显示名称、角色与启用状态；核查操作人用真实外键关联，不表示监控中的人物。"),
    "recordings": ("视频", "media", "一段固定摄像头连续视频与一份完整分析；保留来源、时间起点、配置版本及媒体状态。"),
    "events": ("事件", "retrieval", "视频里的区域事件、匿名局部 track_key 与可空 A 图库 G 编号 person_id；未知和冲突为 null，不推断自然人身份。"),
    "evidence": ("关键帧", "retrieval", "一个事件的真实关键帧、视频偏移和可选检测框；画面缺失仍保留引用。"),
    "event_embeddings": ("事件向量", "retrieval", "事件或关键帧文字描述的语义向量，关联同一事件；保存来源描述、模型/编码版本和维数，候选不升级为事实。使用 pgvector 原生 vector 列；dimensions 与 vector_dims 保持一致，模型空间筛选与检索服务已在development实现，真实编码配置与效果待验收。"),
    "review_records": ("核查记录", "review", "一次人工保存针对一个事件，包含查询条件、结论和说明；记录只追加。"),
    "conversations": ("AI 会话", "agent", "一个用户的聊天会话，不固定录像；历史消息与分析通过外键关联，每次运行单独保存查询范围。"),
    "messages": ("聊天消息", "agent", "不可覆盖的用户问题或完整助手答复；助手答复只关联同会话的 completed 运行，每次运行至多一条。"),
    "runs": ("Agent 分析", "agent", "确认后的计划、模型配置版本、运行状态及事件/关键帧引用；引用 JSON 由服务验证，终态不可覆盖，不能替代人工核查。"),
    "parcels": ("包裹", "parcel", "包裹内部 ID、唯一文本单号与建档时间；二维码由单号生成，保留前导零。状态和当前位置由最后一条入出库记录派生，身份与建档时间不可覆盖。"),
    "parcel_records": ("入出库记录", "parcel", "管理员确认的一次入库或出库，关联包裹与 App 操作人；入库须有区域，记录只追加。SQL 保证正序号及同包裹序号唯一；首次入库、交替顺序、序号分配、出库位置和幂等比较由服务锁定包裹后的事务验证。"),
}
DOMAINS = [
    {"id": "media", "label": "视频素材", "description": "视频来源、时间与文件状态"},
    {"id": "retrieval", "label": "事件与检索", "description": "区域事件、关键帧与语义向量"},
    {"id": "agent", "label": "AI 会话与运行", "description": "对话、执行计划与证据引用"},
    {"id": "review", "label": "账号与核查", "description": "App 账号与人工复核记录"},
    {"id": "parcel", "label": "包裹与入出库", "description": "唯一单号、扫码查询与管理员确认流水"},
]



ENGINE = "PostgreSQL+pgvector"
QUERY_PATH = ROOT / "scripts/postgres-catalog.sql"
GENERATED_DATE = "2026-10-17"
ACTIONS = {"a": "NO ACTION", "r": "RESTRICT", "c": "CASCADE", "n": "SET NULL", "d": "SET DEFAULT"}
MATCHES = {"s": "SIMPLE", "f": "FULL", "p": "PARTIAL"}
LIVE_HINT = "Load the current schema.sql in PostgreSQL, then rerun with --container NAME or --database-url."


class CatalogError(ValueError):
    """A safe, actionable message; connection credentials are never included."""


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative_source(path):
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        raise CatalogError("Schema and output sources must be inside the repository.") from None


def split_sql(text, delimiter=";"):
    """Split outside strings, nested comments, dollar bodies and parentheses."""
    result, start, cursor, depth = [], 0, 0, 0
    quote, dollar, block, line = None, None, 0, False
    while cursor < len(text):
        ch, pair = text[cursor], text[cursor:cursor + 2]
        if line:
            if ch == "\n":
                line = False
            cursor += 1
            continue
        if block:
            if pair == "/*":
                block += 1
                cursor += 2
            elif pair == "*/":
                block -= 1
                cursor += 2
            else:
                cursor += 1
            continue
        if dollar:
            if text.startswith(dollar, cursor):
                cursor += len(dollar)
                dollar = None
            else:
                cursor += 1
            continue
        if quote:
            if ch == quote:
                if cursor + 1 < len(text) and text[cursor + 1] == quote:
                    cursor += 2
                    continue
                quote = None
            cursor += 1
            continue
        if pair == "--":
            line = True
            cursor += 2
        elif pair == "/*":
            block = 1
            cursor += 2
        elif ch in ("'", '"'):
            quote = ch
            cursor += 1
        elif ch == "$" and (match := re.match(r"\$(?:[A-Za-z_][A-Za-z_0-9]*)?\$", text[cursor:])):
            dollar = match.group()
            cursor += len(dollar)
        elif ch == "(":
            depth += 1
            cursor += 1
        elif ch == ")":
            depth -= 1
            if depth < 0:
                raise CatalogError("Unbalanced parentheses in source SQL.")
            cursor += 1
        elif ch == delimiter and depth == 0:
            result.append(text[start:cursor].strip())
            start = cursor + 1
            cursor += 1
        else:
            cursor += 1
    if quote or dollar or block or depth:
        raise CatalogError("Unterminated quote, dollar body, comment or parentheses in source SQL.")
    if text[start:].strip():
        result.append(text[start:].strip())
    return result


def strip_leading_comments(text):
    while True:
        text = text.lstrip()
        if text.startswith("--"):
            newline = text.find("\n")
            text = "" if newline < 0 else text[newline + 1:]
        elif text.startswith("/*"):
            depth, cursor = 1, 2
            while cursor < len(text) and depth:
                pair = text[cursor:cursor + 2]
                if pair == "/*":
                    depth += 1
                    cursor += 2
                elif pair == "*/":
                    depth -= 1
                    cursor += 2
                else:
                    cursor += 1
            if depth:
                raise CatalogError("Unterminated SQL comment.")
            text = text[cursor:]
        else:
            return text


def source_tables(schema_path):
    """Only source CREATE statements are attached; structure is read live."""
    result = {}
    ident = r'(?:"(?:[^"]|"")*"|[A-Za-z_][A-Za-z_0-9]*)'
    pattern = re.compile(rf"^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(?P<schema>{ident})\s*\.\s*)?(?P<name>{ident})\s*\(", re.I)
    for statement in split_sql(schema_path.read_text(encoding="utf-8")):
        statement = strip_leading_comments(statement)
        match = pattern.match(statement)
        if not match:
            continue
        name = match.group("name").strip('"').replace('""', '"').lower()
        namespace = (match.group("schema") or "public").strip('"').lower()
        if namespace != "public" or name in result:
            raise CatalogError("Source tables must be unique and belong to public.")
        end = statement.rfind(")")
        if end < match.end():
            raise CatalogError("Malformed source CREATE TABLE statement.")
        columns = []
        for part in split_sql(statement[match.end():end], ","):
            part = strip_leading_comments(part)
            if re.match(r"^(CONSTRAINT|PRIMARY|FOREIGN|UNIQUE|CHECK|EXCLUDE)\b", part, re.I):
                continue
            column = re.match(rf"^({ident})\s+(.+)$", part, re.S)
            if not column:
                raise CatalogError("Cannot identify a source column; live export cannot silently guess.")
            cname = column.group(1).strip('"').replace('""', '"').lower()
            declared = re.match(r"(?:timestamp\s+with\s+time\s+zone|timestamp\s+without\s+time\s+zone|double\s+precision|character\s+varying|(?:public\.)?[a-z_]+)(?:\s*\(\s*\d+(?:\s*,\s*\d+)?\s*\))?", column.group(2), re.I)
            if not declared:
                raise CatalogError("Cannot identify a declared PostgreSQL column type.")
            columns.append({"name": cname, "type": canonical_type(declared.group())})
        result[name] = {"sql": statement.rstrip(";") + ";", "columns": columns}
    if set(result) != set(TABLE_META) or sum(len(t["columns"]) for t in result.values()) != 80:
        raise CatalogError("Source DDL must contain the agreed eleven public tables and 80 columns.")
    return result


def canonical_type(value):
    value = re.sub(r"\s+", " ", value.strip()).lower().removeprefix("public.")
    aliases = {"timestamptz": "timestamp with time zone", "timestamp": "timestamp without time zone",
               "int": "integer", "int4": "integer", "int8": "bigint", "int2": "smallint",
               "float8": "double precision", "bool": "boolean", "varchar": "character varying"}
    return aliases.get(value, value)


def display_type(value):
    return {"timestamp with time zone": "TIMESTAMPTZ", "timestamp without time zone": "TIMESTAMP",
            "character varying": "VARCHAR"}.get(value, value.upper())


def read_live_catalog(container=None, database_url=None, database="postgres", user="postgres"):
    query = QUERY_PATH.read_text(encoding="utf-8")
    if container:
        try:
            completed = subprocess.run(
                ["docker", "exec", "-i", container, "psql", "-X", "-q", "-t", "-A",
                 "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database],
                input="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL search_path = pg_catalog, public;\nSET LOCAL statement_timeout = '30s';\n" + query + "\nCOMMIT;\n",
                text=True, capture_output=True, timeout=45, check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            raise CatalogError("Cannot run docker exec psql; verify the local container and psql availability.") from None
        if completed.returncode:
            raise CatalogError(f"PostgreSQL catalog query failed (psql exit {completed.returncode}); verify loaded schema and PostgreSQL 15+.") from None
        try:
            raw = json.loads(completed.stdout)
        except json.JSONDecodeError:
            raise CatalogError("psql did not return the expected single catalog JSON value.") from None
        return raw, "docker_exec_psql"
    try:
        import psycopg
    except ImportError:
        raise CatalogError("--database-url requires psycopg 3; alternatively use --container NAME.") from None
    try:
        with psycopg.connect(database_url, connect_timeout=10) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
                cursor.execute("SET LOCAL search_path = pg_catalog, public")
                cursor.execute("SET LOCAL statement_timeout = '30s'")
                cursor.execute(query)
                raw = cursor.fetchone()[0]
    except Exception:
        # Driver diagnostics may contain the connection string. Keep them local.
        raise CatalogError("PostgreSQL connection or catalog query failed; verify credentials, loaded schema and PostgreSQL 15+.") from None
    return (json.loads(raw) if isinstance(raw, str) else raw), "psycopg"


def logical_relationships():
    return [
        {"id": "logical_run_event_reference", "from_table": "runs", "from_columns": ["references_json"],
         "to_table": "events", "to_columns": ["event_id"], "kind": "logical_snapshot",
         "description": "references_json[*].event_id 是分析引用；服务验证事件存在、属于本次运行查询的录像以及引用完整形状，SQL 不检查 JSONB 外键。"},
        {"id": "logical_run_evidence_reference", "from_table": "runs", "from_columns": ["references_json"],
         "to_table": "evidence", "to_columns": ["evidence_id"], "kind": "logical_snapshot",
         "description": "references_json[*].evidence_id 是分析引用；服务验证关键帧属于所引用事件及其当前媒体状态，SQL 不检查 JSONB 外键。"},
    ]


def structural_projection(catalog):
    return {"tables": [{key: table[key] for key in ("name", "columns", "foreign_keys", "indices", "checks", "triggers")}
                       for table in catalog["tables"]], "relationships": catalog["relationships"]}


def structural_totals(catalog):
    return {"tables": len(catalog["tables"]), "columns": sum(len(t["columns"]) for t in catalog["tables"]),
            "foreign_keys": len(catalog["relationships"]), "logical_relationships": len(catalog["logical_relationships"]),
            **{key: sum(len(t[key]) for t in catalog["tables"]) for key in ("indices", "checks", "triggers")}}


def annotations(catalog, schema_path):
    result = copy.deepcopy(catalog)
    source = relative_source(schema_path)
    for table in result["tables"]:
        label, domain, description = TABLE_META[table["name"]]
        table.update(label=label, domain=domain, description=description, source_file=source)
    result["domains"] = copy.deepcopy(DOMAINS)
    result["logical_relationships"] = logical_relationships()
    result["totals"] = structural_totals(result)
    result["metadata"].update({
        "title": "ParTrace Vision · 最小数据库设计", "schema_version": "0.3-mvp-draft", "status": "draft",
        "generated_date": GENERATED_DATE, "engine": ENGINE, "production_engine": ENGINE,
        "source_files": [source, "scripts/export-schema-catalog.py", "scripts/postgres-catalog.sql"],
        "sources": [{"title": "技术方案与开发规划", "revision": "r51"}, {"title": "产品 PRD", "revision": "r230"},
                    {"title": "用户要求最小聊天及 Agent 分析历史", "revision": "2026-10-13 · 9 表、68 字段"},
                    {"title": "用户确认包裹单号与入出库两表", "revision": "2026-10-13 · 11 表、78 字段"},
                    {"title": "用户取消会话固定录像", "revision": "2026-10-13 · 11 表、77 字段；每次运行仍查单段录像"},
                    {"title": "events 增加可空 shelf_level", "revision": "2026-10-16 · 11 表、78 字段；货架层级自下而上 1–20，未知为 NULL"},
                    {"title": "计入 Agent 运行卡片", "revision": "2026-10-17 · 当时 dev 11 表、79 字段；runs.cards_json 保存已完成卡片"},
                    {"title": "事件匿名档案编号", "revision": "2026-10-17 · 当前 dev 11 表、80 字段；events.person_id 为可空 A 图库 G 编号"},
                    {"title": "用户选择 PostgreSQL+pgvector", "revision": "2026-10-13"}],
        "parity": "Loaded public schema read from PostgreSQL pg_catalog in a read-only transaction; constraint/index/trigger/function definitions use pg_get_*; source CREATE TABLE statements attached separately.",
        "disclaimer": "设计草案；真实 PostgreSQL+pgvector 本地结构验证，不代表已部署业务服务或完成数据/检索验收。目录不含业务记录。",
        "annotations_sha256": digest({"tables": TABLE_META, "domains": DOMAINS, "logical_relationships": logical_relationships()}),
    })
    return result


def assert_source_alignment(tables, parsed):
    if {t["name"] for t in tables} != set(TABLE_META) or sum(len(t["columns"]) for t in tables) != 80:
        raise CatalogError("Loaded/saved catalog differs from the agreed eleven tables / 80 columns.")
    for table in tables:
        actual = [{"name": c["name"], "type": canonical_type(c.get("database_type", c["type"]))} for c in table["columns"]]
        if actual != parsed[table["name"]]["columns"]:
            raise CatalogError(f"Loaded/saved columns differ from source DDL for {table['name']}. {LIVE_HINT}")


def build_catalog(raw, schema_path, transport):
    if raw.get("schema") != "public" or not raw.get("pgvector_version") or int(raw.get("postgres_version_num", 0)) < 150000:
        raise CatalogError("Live source must be public in PostgreSQL 15+ with the vector extension installed.")
    parsed = source_tables(schema_path)
    assert_source_alignment(raw["tables"], parsed)
    raw_by_name = {t["name"]: t for t in raw["tables"]}
    tables, relationships = [], []
    for name in TABLE_META:
        raw_table = raw_by_name[name]
        constraints = raw_table["constraints"]
        pks = [c for c in constraints if c["type"] == "p"]
        if len(pks) != 1:
            raise CatalogError(f"Expected one real primary key for {name}.")
        pk = pks[0]["columns"]
        foreign_keys = []
        for con in constraints:
            if con["type"] != "f":
                continue
            if con["to_schema"] != "public" or con["to_table"] not in TABLE_META:
                raise CatalogError("Foreign keys must point to the documented public tables.")
            fk = {"id": con["name"], "constraint_name": con["name"], "from_table": name, "from_columns": con["columns"],
                  "to_table": con["to_table"], "to_columns": con["to_columns"],
                  "on_update": ACTIONS[con["on_update_code"]], "on_delete": ACTIONS[con["on_delete_code"]],
                  "match": MATCHES[con["match_code"]], "kind": "sql_fk", "declared_in_draft": True,
                  "deferrable": con["deferrable"], "initially_deferred": con["initially_deferred"],
                  "validated": con["validated"], "sql": con["definition"]}
            foreign_keys.append(fk)
            relationships.append(fk)
        indices = raw_table["indices"]
        columns = []
        for col in raw_table["columns"]:
            column_name = col["name"]
            references = [{"relationship_id": fk["id"], "table": fk["to_table"],
                           "column": fk["to_columns"][fk["from_columns"].index(column_name)],
                           "composite": len(fk["from_columns"]) > 1}
                          for fk in foreign_keys if column_name in fk["from_columns"]]
            valid_unique = [i for i in indices if i["unique"] and i["valid"]]
            columns.append({"name": column_name, "type": display_type(col["type"]), "database_type": col["type"],
                            "primary_key": column_name in pk, "pk_position": pk.index(column_name) + 1 if column_name in pk else 0,
                            "foreign_key": bool(references), "unique": any(not i["partial"] and i["columns"] == [column_name] for i in valid_unique),
                            "unique_groups": [i["name"] for i in valid_unique if column_name in i["columns"]],
                            "nullable": col["nullable"], "default": col["default"], "references": references,
                            "identity": col["identity"], "generated": col["generated"]})
        checks = [{"name": c["name"], "expression": c["expression"], "sql": c["definition"], "validated": c["validated"]}
                  for c in constraints if c["type"] == "c"]
        triggers = copy.deepcopy(raw_table["triggers"])
        for trigger in triggers:
            trigger["trigger_definition"] = trigger["sql"]
            trigger["sql"] = trigger["sql"].rstrip(";") + ";\n\n" + trigger["function_definition"].rstrip(";") + ";"
        tables.append({"id": name, "name": name, "columns": columns, "foreign_keys": foreign_keys,
                       "indices": indices, "checks": checks, "triggers": triggers, "row_count": None,
                       "create_sql": parsed[name]["sql"]})
    if len({r["id"] for r in relationships}) != len(relationships):
        raise CatalogError("Constraint names must be unique across documented FK groups so IDs remain unambiguous.")
    schema_sha = file_hash(schema_path)
    catalog = {"metadata": {
        "schema_sha256": schema_sha, "postgres_version": raw["postgres_version"],
        "postgres_version_num": raw["postgres_version_num"], "pgvector_version": raw["pgvector_version"],
        "verification": {
            "method": "live_postgresql_system_catalog", "transport": transport, "schema": "public",
            "verified_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "schema_sha256": schema_sha, "catalog_query_sha256": file_hash(QUERY_PATH),
            "structure_sha256": "", "includes_records": False,
            "source_alignment": "Source table/column order and declared types checked against loaded catalog; DDL loading is a separate operation.",
        },
    }, "tables": tables, "relationships": relationships, "logical_relationships": logical_relationships(), "domains": DOMAINS}
    catalog["metadata"]["verification"]["structure_sha256"] = digest(structural_projection(catalog))
    return annotations(catalog, schema_path)


def validate_snapshot(catalog, schema_path):
    meta, sha = catalog.get("metadata", {}), file_hash(schema_path)
    verification = meta.get("verification", {})
    if meta.get("schema_sha256") != sha or verification.get("schema_sha256") != sha:
        raise CatalogError(f"Saved source hash is stale; cannot relabel old structure with the new hash. {LIVE_HINT}")
    if meta.get("engine") != ENGINE or meta.get("production_engine") != ENGINE or verification.get("method") != "live_postgresql_system_catalog":
        raise CatalogError(f"A real PostgreSQL+pgvector verified snapshot is required. {LIVE_HINT}")
    if not meta.get("postgres_version") or not meta.get("pgvector_version") or int(meta.get("postgres_version_num", 0)) < 150000:
        raise CatalogError(f"Saved PostgreSQL/pgvector version provenance is missing. {LIVE_HINT}")
    if verification.get("schema") != "public" or verification.get("includes_records") is not False or not verification.get("verified_at"):
        raise CatalogError(f"Saved read-only verification provenance is incomplete. {LIVE_HINT}")
    if verification.get("catalog_query_sha256") != file_hash(QUERY_PATH):
        raise CatalogError(f"Catalog introspection query changed; a fresh live export is required. {LIVE_HINT}")
    if verification.get("structure_sha256") != digest(structural_projection(catalog)):
        raise CatalogError(f"Saved structural definitions differ from the verified fingerprint. {LIVE_HINT}")
    parsed = source_tables(schema_path)
    assert_source_alignment(catalog["tables"], parsed)
    names = {t["name"] for t in catalog["tables"]}
    table_fks = [fk for t in catalog["tables"] for fk in t["foreign_keys"]]
    if len(names) != len(catalog["tables"]) or table_fks != catalog["relationships"] or len({r["id"] for r in table_fks}) != len(table_fks):
        raise CatalogError("Saved tables or global/table FK definitions are inconsistent.")
    for table in catalog["tables"]:
        if table.get("row_count") is not None or table.get("create_sql") != parsed[table["name"]]["sql"]:
            raise CatalogError("Saved records/source CREATE statements must match the verified source.")
    by_name = {t["name"]: {c["name"] for c in t["columns"]} for t in catalog["tables"]}
    for fk in table_fks:
        if fk.get("kind") != "sql_fk" or fk["from_table"] not in names or fk["to_table"] not in names or len(fk["from_columns"]) != len(fk["to_columns"]):
            raise CatalogError("Saved FK definition is invalid.")
        if any(c not in by_name[fk["from_table"]] for c in fk["from_columns"]) or any(c not in by_name[fk["to_table"]] for c in fk["to_columns"]):
            raise CatalogError("Saved FK references an unknown column.")
    if catalog.get("totals") != structural_totals(catalog):
        raise CatalogError("Saved structural totals do not match actual definitions.")
    return catalog


def load_snapshot(path):
    if not path.exists():
        raise CatalogError(f"No verified saved catalog exists. {LIVE_HINT}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise CatalogError("Saved catalog cannot be read as JSON.") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", type=Path, default=ROOT / "docs/database/schema.sql")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/database/schema-catalog.json")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--container", help="Local container name; use docker exec psql without opening a port.")
    source.add_argument("--database-url", help="PostgreSQL DSN for psycopg 3; never written to metadata or diagnostics.")
    parser.add_argument("--database", default="postgres", help="Database inside --container (default: postgres).")
    parser.add_argument("--user", default="postgres", help="psql role inside --container (default: postgres).")
    parser.add_argument("--check", action="store_true", help="Without a connection: freshness of saved verified snapshot only. With a connection: compare live definitions.")
    args = parser.parse_args()
    try:
        relative_source(args.schema)
        relative_source(args.output)
        live = bool(args.container or args.database_url)
        if live:
            raw, transport = read_live_catalog(args.container, args.database_url, args.database, args.user)
            catalog = build_catalog(raw, args.schema, transport)
            validate_snapshot(catalog, args.schema)
        else:
            catalog = validate_snapshot(load_snapshot(args.output), args.schema)
            catalog = annotations(catalog, args.schema)
        if args.check:
            saved = validate_snapshot(load_snapshot(args.output), args.schema)
            if saved != annotations(saved, args.schema):
                raise CatalogError("Catalog annotation metadata is stale; rerun export-schema-catalog.py (offline annotation refresh is permitted when source hash is unchanged).")
            if live:
                if structural_projection(saved) != structural_projection(catalog):
                    raise CatalogError("Loaded PostgreSQL structure differs from saved catalog; rerun live export.")
                if any(saved["metadata"].get(k) != catalog["metadata"].get(k) for k in ("postgres_version", "postgres_version_num", "pgvector_version")):
                    raise CatalogError("Loaded PostgreSQL/pgvector versions differ; rerun live export.")
            status = "checked_live" if live else "checked_offline_snapshot"
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            status = "exported_live" if live else "refreshed_annotations_from_verified_snapshot"
        print(json.dumps({"status": status, "engine": ENGINE, "database_verification_repeated": live,
                          "verification_scope": "loaded PostgreSQL pg_catalog" if live else "saved snapshot/source hash/definitions/annotations only",
                          **catalog["totals"]}, ensure_ascii=False))
    except (CatalogError, KeyError, TypeError, OSError) as exc:
        message = str(exc) if isinstance(exc, CatalogError) else "Catalog input or structure is incomplete; verify source files and rerun a live export."
        parser.exit(1, message + "\n")


if __name__ == "__main__":
    main()

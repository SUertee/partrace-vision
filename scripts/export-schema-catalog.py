#!/usr/bin/env python3
"""Execute the draft DDL in memory and export actual SQLite structure, no records."""

import argparse
import hashlib
import json
import re
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TABLE_META = {
    "cameras": ("摄像头", "capture", "设备来源与标识；状态未知不代表设备已接入。"),
    "zone_configs": ("区域配置", "capture", "摄像头内业务区域、ROI、示意位置与不可覆盖的配置版本。"),
    "recordings": ("输入录制", "capture", "稳定输入 ID、原始时间基准、对齐结果和独立媒体保留状态。"),
    "index_imports": ("索引批次", "index", "同一录像的独立索引版本、来源、幂等键及内容哈希；完整批次封存。"),
    "track_segments": ("匿名片段", "index", "有限单镜片段与离散/连续观测；不形成长期人物身份。"),
    "events": ("区域事件", "index", "观测或候选事实；SDK 原生事件不生成匿名轨迹。"),
    "evidence": ("证据媒体", "evidence", "关键帧或短片的定位、服务端存储键、可用性与到期状态。"),
    "event_evidence": ("事件证据引用", "evidence", "事件与媒体的真实多对多关系，复合 FK 保证同录像同摄像头。"),
    "coverage_intervals": ("采集与索引覆盖", "capture", "连续录像、离散帧样本或缺口；采集和索引状态分开。"),
    "journeys": ("保存的核查", "review", "一次查询的服务端复现快照与当前复核版本；不作为人物档案。"),
    "journey_items": ("当前人工选择", "review", "Event/TrackSegment/Evidence 的真实 FK；当前选择版本与 Journey 一致。"),
    "review_actions": ("人工复核历史", "review", "带原因的追加式复核、前后快照、幂等与乐观并发版本。"),
    "retention_cleanup_logs": ("媒体清理日志", "evidence", "版本化策略下的实际删除尝试、结果与失败原因；不删除索引。"),
}
DOMAINS = [
    {"id": "capture", "label": "设备与采集", "description": "摄像头、区域、输入和覆盖"},
    {"id": "index", "label": "视觉索引", "description": "导入版本、匿名片段和事件"},
    {"id": "evidence", "label": "证据与保留", "description": "媒体引用、可用性和清理"},
    {"id": "review", "label": "核查与复核", "description": "查询快照、当前选择和历史动作"},
]


def quote_identifier(value):
    return '"' + value.replace('"', '""') + '"'


def check_constraints(sql):
    """Read balanced CHECK expressions from SQLite's CREATE TABLE SQL."""
    result = []
    pattern = re.compile(r"(?:(?:CONSTRAINT)\s+(\w+)\s+)?CHECK\s*\(", re.I)
    for match in pattern.finditer(sql):
        depth = 1
        quote = None
        cursor = match.end()
        start = cursor
        while cursor < len(sql) and depth:
            char = sql[cursor]
            if quote:
                if char == quote:
                    if cursor + 1 < len(sql) and sql[cursor + 1] == quote:
                        cursor += 2
                        continue
                    quote = None
            elif char in ("'", '"'):
                quote = char
            elif char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
            cursor += 1
        if depth:
            raise ValueError("Unbalanced CHECK expression")
        result.append({
            "name": match.group(1) or f"check_{len(result) + 1}",
            "expression": " ".join(sql[start:cursor - 1].split()),
        })
    return result


def export_catalog(schema_path):
    schema_text = schema_path.read_text(encoding="utf-8")
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.executescript(schema_text)
    if connection.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
        raise ValueError("DDL must enable SQLite foreign keys")
    if list(connection.execute("PRAGMA foreign_key_check")):
        raise ValueError("Draft schema has a foreign key violation")
    if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise ValueError("SQLite integrity_check failed")

    table_rows = list(connection.execute(
        "SELECT name, sql FROM sqlite_master WHERE type = 'table' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY rowid"
    ))
    if set(row["name"] for row in table_rows) != set(TABLE_META):
        raise ValueError("Table metadata and executed DDL differ")
    source_file = schema_path.resolve().relative_to(ROOT).as_posix()
    tables = []
    relationships = []
    for row in table_rows:
        name, sql = row["name"], row["sql"]
        ident = quote_identifier(name)
        grouped_fks = {}
        for fk_row in connection.execute(f"PRAGMA foreign_key_list({ident})"):
            grouped_fks.setdefault(fk_row["id"], []).append(dict(fk_row))
        foreign_keys = []
        for pragma_id, group in sorted(grouped_fks.items()):
            group.sort(key=lambda item: item["seq"])
            fk = {
                "id": f"fk_{name}_{pragma_id}",
                "pragma_id": pragma_id,
                "from_table": name,
                "from_columns": [item["from"] for item in group],
                "to_table": group[0]["table"],
                "to_columns": [item["to"] for item in group],
                "on_update": group[0]["on_update"],
                "on_delete": group[0]["on_delete"],
                "kind": "sql_fk",
                "declared_in_draft": True,
            }
            from_pattern = r"\s*,\s*".join(re.escape(column) for column in fk["from_columns"])
            clause = re.search(
                rf"FOREIGN\s+KEY\s*\(\s*{from_pattern}\s*\)\s*REFERENCES\s+{re.escape(fk['to_table'])}\s*\([^)]*\)([^,\n]*)",
                sql, re.I,
            )
            actions = clause.group(1).upper() if clause else ""
            fk["deferrable"] = "DEFERRABLE" in actions and "NOT DEFERRABLE" not in actions
            fk["initially_deferred"] = "INITIALLY DEFERRED" in actions
            foreign_keys.append(fk)
            relationships.append(fk)

        indices = []
        for index_row in connection.execute(f"PRAGMA index_list({ident})"):
            index_row = dict(index_row)
            index_name = index_row["name"]
            index_sql_row = connection.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", (index_name,)
            ).fetchone()
            index_columns = sorted(
                (dict(item) for item in connection.execute(
                    f"PRAGMA index_info({quote_identifier(index_name)})"
                )), key=lambda item: item["seqno"]
            )
            index_details = [dict(item) for item in connection.execute(
                f"PRAGMA index_xinfo({quote_identifier(index_name)})"
            ) if item["key"] == 1]
            indices.append({
                "name": index_name,
                "unique": bool(index_row["unique"]),
                "origin": index_row["origin"],
                "partial": bool(index_row["partial"]),
                "columns": [item["name"] for item in index_columns],
                "column_details": [{"name": item["name"], "descending": bool(item["desc"]), "collation": item["coll"]} for item in index_details],
                "sql": index_sql_row["sql"] if index_sql_row else None,
            })
        indices.sort(key=lambda item: item["name"])
        columns = []
        for column_row in connection.execute(f"PRAGMA table_info({ident})"):
            column_row = dict(column_row)
            column_name = column_row["name"]
            unique_groups = [item["name"] for item in indices if item["unique"] and column_name in item["columns"]]
            references = []
            for fk in foreign_keys:
                if column_name in fk["from_columns"]:
                    position = fk["from_columns"].index(column_name)
                    references.append({"relationship_id": fk["id"], "table": fk["to_table"], "column": fk["to_columns"][position], "composite": len(fk["from_columns"]) > 1})
            columns.append({
                "name": column_name,
                "type": column_row["type"],
                "primary_key": bool(column_row["pk"]),
                "pk_position": column_row["pk"],
                "foreign_key": bool(references),
                "unique": any(item["unique"] and item["columns"] == [column_name] for item in indices),
                "unique_groups": unique_groups,
                "nullable": not bool(column_row["notnull"] or column_row["pk"]),
                "default": column_row["dflt_value"],
                "references": references,
            })
        triggers = []
        for trigger_row in connection.execute(
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ? ORDER BY name", (name,)
        ):
            match = re.search(r"\b(BEFORE|AFTER|INSTEAD\s+OF)\s+(INSERT|UPDATE|DELETE)\b", trigger_row["sql"], re.I)
            triggers.append({"name": trigger_row["name"], "event": match.group(0).upper() if match else "unknown", "sql": trigger_row["sql"]})
        label, domain, description = TABLE_META[name]
        tables.append({
            "id": name, "name": name, "label": label, "domain": domain,
            "description": description, "columns": columns,
            "foreign_keys": foreign_keys, "indices": indices,
            "checks": check_constraints(sql), "triggers": triggers,
            "row_count": None, "source_file": source_file, "create_sql": sql,
        })

    logical_relationships = []
    for from_table, from_columns in (("journeys", ["server_snapshot_json"]), ("review_actions", ["before_snapshot_json", "after_snapshot_json"])):
        for to_table, to_column in (("index_imports", "import_id"), ("events", "event_id"), ("track_segments", "track_segment_id"), ("evidence", "evidence_id")):
            logical_relationships.append({
                "id": f"snapshot_{from_table}_{to_table}", "from_table": from_table, "to_table": to_table,
                "from_columns": from_columns, "to_columns": [to_column], "kind": "logical_snapshot",
                "description": "服务端快照保存固定批次及原始引用元数据；JSON 内部引用需应用校验，不算数据库 FK。",
            })
    catalog = {
        "metadata": {
            "title": "ParTrace Vision · 数据库关系草案", "schema_version": "0.2-draft", "status": "draft",
            "generated_date": "2026-10-12", "engine": "SQLite (local DDL verification only)",
            "sqlite_version": sqlite3.sqlite_version, "production_engine": "undecided",
            "source_files": [source_file, "scripts/export-schema-catalog.py"],
            "schema_sha256": hashlib.sha256(schema_text.encode("utf-8")).hexdigest(),
            "sources": [{"title": "技术方案与开发规划", "revision": "r51"}, {"title": "产品 PRD", "revision": "r230"}],
            "parity": "Tables, columns, FK groups, indices, defaults and triggers derived from executed in-memory SQLite DDL; CHECK expressions derived from sqlite_master SQL.",
            "disclaimer": "设计草案；无现有数据库、迁移、ORM或业务数据。结构验证不代表后端、视觉、Agent或SDK已经实现。",
        },
        "domains": DOMAINS, "tables": tables, "relationships": relationships,
        "logical_relationships": logical_relationships,
        "totals": {
            "tables": len(tables), "columns": sum(len(table["columns"]) for table in tables),
            "foreign_keys": len(relationships), "logical_relationships": len(logical_relationships),
            "indices": sum(len(table["indices"]) for table in tables),
            "checks": sum(len(table["checks"]) for table in tables),
            "triggers": sum(len(table["triggers"]) for table in tables),
        },
    }
    connection.close()
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", type=Path, default=ROOT / "docs/database/schema.sql")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/database/schema-catalog.json")
    parser.add_argument("--check", action="store_true", help="Check catalog parity without writing output")
    args = parser.parse_args()
    catalog = export_catalog(args.schema)
    serialized = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != serialized:
            raise SystemExit("Catalog differs from executed schema; rerun export-schema-catalog.py")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    print(json.dumps({"status": "checked" if args.check else "exported", **catalog["totals"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()

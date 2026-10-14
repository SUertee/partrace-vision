#!/usr/bin/env python3
"""Build a self-contained offline API handoff view from authoritative OpenAPI."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
MODULES = {"android", "backend", "agent", "vision"}
SOURCES = {
    "openapi": "docs/api/openapi.json",
    "mapping": "docs/api/api-module-map.json",
    "feature_map": "docs/api/feature-data-api-map.json",
    "schema": "docs/database/schema.sql",
    "catalog": "docs/database/schema-catalog.json",
}


def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(relative):
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def operation_key(operation):
    return operation["method"].upper(), operation["path"]


def refs_in(value):
    result = set()
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            if not ref.startswith("#/components/schemas/"):
                raise ValueError("API canvas only supports repository-local component schema references")
            result.add(ref.rsplit("/", 1)[-1])
        for child in value.values():
            result.update(refs_in(child))
    elif isinstance(value, list):
        for child in value:
            result.update(refs_in(child))
    return result


def sample_schema_names(content):
    # Only direct request/success response schemas determine sample association.
    return {ref.rsplit("/", 1)[-1] for media in content.values()
            for ref in [media.get("schema", {}).get("$ref")] if ref}


def inline_samples(content, source):
    """Show authored OpenAPI examples when an operation has no file fixture."""
    result = []
    for media_type, media in content.items():
        schema = media.get("schema", {}).get("$ref", "内联结构").rsplit("/", 1)[-1]
        if "example" in media:
            result.append({"source": source + " · " + media_type, "schema": schema, "value": media["example"]})
        for name, example in media.get("examples", {}).items():
            if "value" in example:
                result.append({"source": source + " · " + name, "schema": schema, "value": example["value"]})
    return result


def build_data():
    spec, mapping, feature_map, catalog = [read_json(SOURCES[key]) for key in ("openapi", "mapping", "feature_map", "catalog")]
    if catalog["metadata"]["schema_sha256"] != hash_file(ROOT / SOURCES["schema"]):
        raise ValueError("Database catalog SQL hash is stale; refresh it before building the API view")
    table_names = {table["name"] for table in catalog["tables"]}
    if len(table_names) != catalog["totals"]["tables"]:
        raise ValueError("API handoff catalog totals do not match the table definitions")
    schemas = spec["components"]["schemas"]
    definitions = {(method.upper(), path): operation for path, methods in spec["paths"].items()
                   for method, operation in methods.items() if method in METHODS}
    approved = {operation_key(operation): operation for operation in mapping["operations"]}
    current_features = {operation_key(operation): operation for operation in feature_map["operations"]
                        if operation["status"] == "current_draft"}
    proposed_features = {operation_key(operation): operation for operation in feature_map["operations"]
                         if operation["status"] == "proposed"}
    if not definitions or set(definitions) != set(approved) or set(definitions) != set(current_features):
        raise ValueError("OpenAPI, module map and current feature map must cover exactly the same operations")
    if len(approved) != len(mapping["operations"]) or {m["module"] for m in mapping["modules"]} != MODULES:
        raise ValueError("Duplicate operations or incomplete work modules")
    stages = {stage["id"]: stage for stage in mapping["stages"]}
    if len(stages) != len(mapping["stages"]):
        raise ValueError("Stage IDs must be unique")
    stage_counts = {stage_id: 0 for stage_id in stages}
    fixtures = {}
    fixture_hashes = {}
    for relative, schema_name in spec.get("x-fixture-schemas", {}).items():
        file = (ROOT / "docs/api" / relative).resolve()
        source = file.relative_to(ROOT).as_posix()
        if schema_name not in schemas:
            raise ValueError("Fixture refers to an unknown OpenAPI schema")
        fixtures[source] = {"source": source, "schema": schema_name, "value": json.loads(file.read_text(encoding="utf-8"))}
        fixture_hashes[source] = hash_file(file)
    operations = []
    seen_ids = set()
    for order, annotation in enumerate(mapping["operations"]):
        key = operation_key(annotation)
        contract, feature = definitions[key], current_features[key]
        provider = contract.get("x-service")
        if provider not in {"backend", "agent"} or annotation["provider"] != provider:
            raise ValueError("HTTP provider must match OpenAPI x-service backend/agent")
        if annotation["status"] != "current_draft" or annotation["stage"] not in stages:
            raise ValueError("Only current_draft operations in known stages enter the API table")
        if not annotation["callers"] or not set(annotation["callers"]).issubset(MODULES):
            raise ValueError("Unknown API caller module")
        if annotation["id"] in seen_ids:
            raise ValueError("Operation IDs must be unique")
        seen_ids.add(annotation["id"])
        for field in ("read_tables", "write_tables"):
            if annotation[field] != feature[field] or not set(annotation[field]).issubset(table_names):
                raise ValueError("Table mapping differs from the current feature review or authoritative catalog")
        for ref in refs_in(contract):
            if ref not in schemas:
                raise ValueError("Operation has an unknown schema reference")
        request_content = contract.get("requestBody", {}).get("content", {})
        response_content = {}
        response_inline = []
        for status, response in contract["responses"].items():
            if status.startswith("2"):
                response_content.update(response.get("content", {}))
                response_inline.extend(inline_samples(response.get("content", {}), f"OpenAPI · {key[0]} {key[1]} · {status}"))
        request_names = sample_schema_names(request_content)
        response_names = sample_schema_names(response_content)
        request_samples = [item for item in fixtures.values() if item["schema"] in request_names]
        response_samples = [item for item in fixtures.values() if item["schema"] in response_names]
        # Query has an existing named fixture and is explicitly the GET /events input.
        if key == ("GET", "/api/v1/events"):
            request_samples = [item for item in fixtures.values() if item["schema"] == "Query"]
        if not request_samples:
            request_samples = inline_samples(request_content, f"OpenAPI · {key[0]} {key[1]} · 请求")
        if not request_samples and contract.get("parameters"):
            values = {p["name"]: p["example"] for p in contract["parameters"] if "example" in p}
            if values:
                request_samples = [{"source": f"OpenAPI · {key[0]} {key[1]} · 参数", "schema": "参数对象", "value": values}]
        if not response_samples:
            response_samples = response_inline
        operation = {**annotation, "order": order, "contract": contract,
                     "samples": {"request": request_samples, "response": response_samples}}
        operations.append(operation)
        stage_counts[annotation["stage"]] += 1
    for stage in mapping["stages"]:
        if stage_counts[stage["id"]] != stage["current_operation_count"] or not set(stage["depends_on"]).issubset(stages):
            raise ValueError("Stage counts or dependencies are inconsistent")
    proposals = mapping["proposed"]
    if len(proposals) != len(proposed_features) or {operation_key(o) for o in proposals} != set(proposed_features):
        raise ValueError("Proposed entrances must match the feature map and remain separate from current operations")
    for operation in proposals:
        if operation["status"] != "proposed" or operation_key(operation) in definitions:
            raise ValueError("A proposed interface cannot be presented as an OpenAPI operation")
    service_counts = {name: sum(o["provider"] == name for o in operations) for name in ("backend", "agent")}
    if sum(service_counts.values()) != len(operations):
        raise ValueError("Every operation must have one known HTTP provider")
    metadata = {
        "title": "ParTrace Vision · API 模块交接",
        "api_version": spec["info"]["version"], "generated_date": "2026-10-13",
        "source_sha256": hash_file(ROOT / SOURCES["openapi"]),
        "mapping_sha256": hash_file(ROOT / SOURCES["mapping"]),
        "feature_map_sha256": hash_file(ROOT / SOURCES["feature_map"]),
        "schema_sha256": hash_file(ROOT / SOURCES["schema"]),
        "catalog_sha256": hash_file(ROOT / SOURCES["catalog"]),
        "fixture_sha256": fixture_hashes, "source_files": list(SOURCES.values()),
        "operation_count": len(operations), "service_counts": service_counts,
        "proposed_count": len(proposals),
        "scope": "接口均为未实现草案；模块归属与阶段为初步交接建议，参数和响应以 OpenAPI 为准。",
    }
    return {"metadata": metadata, "modules": mapping["modules"], "stages": mapping["stages"],
            "operations": operations, "proposed": proposals, "schemas": schemas,
            "parallel_work": "样例先接通，四路并行；阶段用于真实联调顺序。",
            "sdk_branch": mapping["sdk_branch"], "implementation_boundaries": mapping["implementation_boundaries"]}


TEMPLATE = r'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ParTrace Vision · API 模块交接</title>
<style>
/* finesse-ui: product · inherited-poster-blue · compact-table+inline-detail · feedback-only */
:root{--page:#f4f7fb;--card:#fff;--ink:#102a43;--muted:#596c80;--faint:#8794a4;--line:#d8e1eb;--accent:#006ddd;--soft:#e8f2ff;--mono:ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;--sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
*{box-sizing:border-box}body{margin:0;color:var(--ink);background:var(--page);font:13px/1.55 var(--sans)}button,input,select,textarea{font:inherit}button{cursor:pointer;touch-action:manipulation;color:inherit}button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible,a:focus-visible,[tabindex]:focus-visible{outline:3px solid var(--accent);outline-offset:3px}[hidden]{display:none!important}a{color:var(--accent)}h1,h2,h3,p{margin-top:0}.page{max-width:1380px;padding:22px 28px;margin:auto}.back{font-size:12px;margin:0 0 12px}.back a{text-decoration:none}.header{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:15px}h1{font-size:24px;line-height:1.25;letter-spacing:-.6px;margin:0 0 6px}.intro{color:var(--muted);font-size:12px;margin:0}.draft{flex:none;background:var(--soft);color:var(--accent);border:1px solid #c4d5e9;border-radius:7px;padding:5px 9px;font-size:11px}.surface{border:1px solid var(--line);border-radius:11px;background:#fff;overflow:hidden}.handoff{padding:10px 16px 9px;background:#f8fafd;border-bottom:1px solid var(--line)}.diagram-scroll{overflow:auto}.handoff svg{display:block;width:100%;min-width:740px;height:auto;max-height:180px}.flow-node rect{fill:#fff;stroke:#c8d5e4;stroke-width:1}.flow-node text{fill:var(--ink);font:600 13px var(--sans)}.flow-node .caption{fill:var(--muted);font:11px var(--sans)}.flow-node{cursor:pointer}.flow-node.active rect{fill:var(--soft);stroke:var(--accent);stroke-width:2}.flow-node.dim{opacity:.45}.flow{fill:none;stroke:#8098b4;stroke-width:1.3}.flow-label{fill:var(--muted);font:11px var(--sans);paint-order:stroke;stroke:#f8fafd;stroke-width:5px;stroke-linejoin:round}.handoff-note{font-size:11px;color:var(--faint);margin:3px 0 0}.toolbar{display:flex;gap:9px;align-items:center;flex-wrap:wrap;padding:12px 14px;border-bottom:1px solid var(--line)}.search{display:flex;flex:1 1 210px;max-width:310px;border:1px solid #c5d1df;border-radius:8px}.search input{border:0;flex:1;min-width:0;padding:8px 10px;background:transparent;font-size:12px}.search button{border:0;background:none;color:var(--muted);padding:0 10px;font-size:16px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}.filters{display:flex;gap:5px;flex-wrap:wrap}.filter{padding:7px 9px;font-size:12px;border:1px solid var(--line);border-radius:8px;background:#fff}.filter[aria-pressed="true"]{color:var(--accent);border-color:#82b3ec;background:var(--soft);font-weight:600}.reset{border:0;background:transparent;font-size:12px;color:var(--muted);padding:6px}.module-note{padding:8px 14px;color:var(--muted);font-size:11px;border-bottom:1px solid var(--line)}.module-note p{margin:0}.table-wrap{overflow:auto}table{width:100%;min-width:760px;border-collapse:collapse;text-align:left;table-layout:fixed;font-size:12px}th{color:var(--muted);font-weight:500;background:#f8fafd;padding:10px 12px}td{padding:10px 12px;border-top:1px solid #e8eef5;vertical-align:top}th:nth-child(1){width:68px}th:nth-child(2){width:38%}th:nth-child(3){width:24%}th:nth-child(4){width:23%}th:nth-child(5){width:62px}.group-row th{padding:11px 12px 8px;font-weight:600;font-size:12px;background:#f2f6fb;border-top:1px solid var(--line)}.group-row small{font-size:11px;font-weight:400;color:var(--faint);margin-left:8px}.operation-row{cursor:pointer}.operation-row:hover{background:#f8fbff}.operation-row.selected{background:#eef6ff}.method{font:600 11px/1.4 var(--mono);color:var(--accent)}.method.post{color:#245789}.path{padding:0;border:0;background:transparent;text-align:left;color:var(--ink);width:100%;font:12px/1.6 var(--mono);overflow-wrap:anywhere}.path[aria-expanded="true"]{color:var(--accent)}.direction{font-size:11px;color:var(--muted);line-height:1.6}.order{font:11px var(--mono);color:var(--faint)}.detail-row>td{padding:0;border-top:0}.operation-detail{padding:14px 18px 17px;background:#f7faff;border-top:1px solid #dae7f6;border-bottom:1px solid #dae7f6}.detail-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:9px}.detail-heading h2{font:600 13px/1.5 var(--mono);margin:0;overflow-wrap:anywhere}.close{border:1px solid var(--line);border-radius:6px;background:#fff;font-size:11px;padding:4px 7px;flex:none}.detail-note{font-size:12px;color:var(--muted);margin:0 0 8px}.detail-facts{display:flex;gap:12px;flex-wrap:wrap;font-size:11px;color:var(--muted);margin:0 0 9px}.detail-facts code{font:11px var(--mono)}.fold{margin-top:8px;border-top:1px solid #dfe8f3}.fold>summary{padding:9px 0;cursor:pointer;font-size:12px;color:var(--muted)}.fold-content{padding:2px 0 7px;max-width:1060px}.fold-content p{color:var(--muted);font-size:12px;margin:0 0 8px}.fold-content h3{font-size:12px;margin:12px 0 7px}.samples-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px}.samples-controls select{max-width:min(100%,680px);padding:5px 7px;font-size:11px;color:var(--muted);border:1px solid var(--line);border-radius:6px;background:#fff}.copy{font-size:11px;padding:5px 8px;border:1px solid #c5d7eb;border-radius:6px;background:#fff;color:var(--accent)}pre{font:11px/1.6 var(--mono);white-space:pre;overflow:auto;max-height:360px;padding:12px;border:1px solid #dfe7f1;border-radius:7px;background:#fff;margin:0}.sample-source{font:10px var(--mono);color:var(--faint)!important;overflow-wrap:anywhere;margin-top:7px!important}.copy-fallback{display:block;width:100%;max-height:180px;min-height:100px;margin-top:8px;font:11px var(--mono);border:1px solid var(--line);padding:8px;background:#fff}.compact-definitions{display:flex;flex-wrap:wrap;gap:5px 12px;font-size:11px;color:var(--muted);margin-bottom:9px}.compact-definitions code{font:11px var(--mono);color:var(--ink)}.empty{padding:30px 16px;font-size:12px;color:var(--muted);text-align:center}.footer{margin-top:10px;font-size:11px;color:var(--muted)}.footer>details{margin-top:7px}.footer summary{cursor:pointer;color:var(--faint)}.footer-content{padding:10px 0 4px;line-height:1.7}.footer-content p{margin:0 0 7px}.sources{font:10px/1.8 var(--mono);overflow-wrap:anywhere}.proposal{margin-bottom:8px}.proposal code{font:11px var(--mono)}.proposal p{margin:2px 0 0}.links{display:flex;gap:14px;flex-wrap:wrap;margin-top:9px}.copy-status{font-size:11px;color:var(--accent);min-height:17px;margin:5px 0 0}.embedded .page{max-width:none;padding:0}.embedded .back,.embedded .header{display:none}.embedded .surface{border-radius:0}.embedded .footer{padding:0 14px 10px}.embedded .handoff svg{max-height:168px}
@media(max-width:700px){.page{padding:16px 12px}h1{font-size:21px}.header{gap:10px}.intro{font-size:11px}.toolbar{padding:10px}.search{flex-basis:100%;max-width:none}.filters{width:100%;gap:5px}.filter{padding:6px 8px}.handoff{padding:9px 10px}.handoff-note{font-size:10px}.operation-detail{padding:13px 12px}.module-note{padding:8px 10px}}
@media print{@page{size:A3 landscape;margin:12mm}body{background:#fff}.page,.embedded .page{padding:0;max-width:none}.back,.toolbar,.close,.copy,.copy-status,.copy-fallback{display:none!important}.header,.embedded .header{display:flex}.handoff svg{max-height:none}.diagram-scroll,.table-wrap{overflow:visible}table{min-width:0}.surface{border-radius:0}.operation-detail{break-inside:avoid}pre{max-height:none;white-space:pre-wrap}.footer-content{break-inside:avoid}.operation-row.selected{background:#fff}}
</style>
</head>
<body>
<main class="page">
<nav class="back" aria-label="返回项目工作台"><a href="../workbench/index.html#api" data-offline-document="api">← 返回工作台</a></nav>
<header class="header"><div><h1>API 模块交接</h1><p class="intro">后端 __BACKEND_COUNT__ 个操作 · Agent __AGENT_COUNT__ 个操作；按提供方与调用方查看。</p></div><span class="draft">设计草案 · 未实现</span></header>
<section class="surface" aria-label="四个工作模块与API交接">
<div class="handoff"><div class="diagram-scroll">
<svg viewBox="0 0 930 182" role="group" aria-label="视觉产出包交后端，Android调用后端和Agent，Agent只读调用后端">
<defs><marker id="handoff-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M1 1 L9 5 L1 9" fill="none" stroke="#8098b4" stroke-width="1.5"/></marker></defs>
<path class="flow" d="M176 55 H250" marker-end="url(#handoff-arrow)"/><text class="flow-label" x="213" y="41" text-anchor="middle">结果包导入</text>
<path class="flow" d="M706 54 H440" marker-end="url(#handoff-arrow)"/><text class="flow-label" x="573" y="40" text-anchor="middle">查事件 / 显式人工保存</text>
<path class="flow" d="M440 80 H706" marker-end="url(#handoff-arrow)"/><text class="flow-label" x="573" y="96" text-anchor="middle">返回事件与真实证据</text>
<path class="flow" d="M809 98 V148 H675" marker-end="url(#handoff-arrow)"/><text class="flow-label" x="745" y="136" text-anchor="middle">会话 / 计划 / 运行</text>
<path class="flow" d="M495 148 H345 V98" marker-end="url(#handoff-arrow)"/><text class="flow-label" x="417" y="136" text-anchor="middle">只读监控事实</text>
<g class="flow-node" data-module="vision" role="button" tabindex="0" aria-label="筛选视觉算法相关API"><rect x="16" y="28" width="160" height="70" rx="8"/><text x="32" y="52">视觉算法 · vision</text><text class="caption" x="32" y="72">最终事件包、关键帧</text><text class="caption" x="32" y="87">数据交接输入方</text></g>
<g class="flow-node" data-module="backend" role="button" tabindex="0" aria-label="筛选后端相关API"><rect x="250" y="28" width="190" height="70" rx="8"/><text x="266" y="52">后端 · backend</text><text class="caption" x="266" y="72">事件、包裹与人工记录</text><text class="caption" x="266" y="87">HTTP 提供方 · __BACKEND_COUNT__ 操作</text></g>
<g class="flow-node" data-module="android" role="button" tabindex="0" aria-label="筛选Android相关API"><rect x="706" y="28" width="206" height="70" rx="8"/><text x="722" y="52">Android 前端</text><text class="caption" x="722" y="72">查看画面、扫码与确认</text><text class="caption" x="722" y="87">后端与 Agent 调用方</text></g>
<g class="flow-node" data-module="agent" role="button" tabindex="0" aria-label="筛选Agent相关API"><rect x="495" y="119" width="180" height="58" rx="8"/><text x="511" y="143">AI Agent</text><text class="caption" x="511" y="163">HTTP 提供方 · __AGENT_COUNT__ 操作</text></g>
</svg></div><p class="handoff-note" id="stage-sequence"></p><p class="handoff-note">样例先接通，四路并行；阶段用于真实联调顺序。</p></div>
<div class="toolbar"><label for="search" class="sr-only">搜索方法、路径、用途或关联表</label><div class="search"><input id="search" type="search" placeholder="搜索路径、用途或表名…" autocomplete="off" spellcheck="false"><button id="clear-search" aria-label="清空搜索">×</button></div><nav class="filters" id="module-filters" aria-label="按工作模块筛选"></nav><button id="reset" class="reset">复位</button></div>
<div class="module-note"><p id="view-status" role="status" aria-live="polite"></p><p id="module-responsibility" hidden></p></div>
<div class="table-wrap"><table aria-label="__OPERATION_COUNT__个已定义API草案操作"><thead><tr><th scope="col">方法</th><th scope="col">路径</th><th scope="col">用途</th><th scope="col">提供 → 调用</th><th scope="col">顺序</th></tr></thead><tbody id="operation-list"></tbody></table></div>
<div id="empty" class="empty" hidden>没有匹配接口，清空搜索或选择「全部」。</div>
</section>
<footer class="footer">
<details><summary>待讨论的建议入口 · __PROPOSED_COUNT__ 个</summary><div class="footer-content"><p>参数与响应尚未定义；建议单独评审，不计入上方 __OPERATION_COUNT__ 个草案操作。</p><div id="proposals"></div></div></details>
<details><summary>交接边界与来源</summary><div class="footer-content"><div id="boundaries"></div><p id="sdk-note"></p><p id="source-summary"></p><p id="source-files" class="sources"></p><p>请求与响应来自 OpenAPI；JSON 样例为仓库模拟交接数据，不代表真实文件或 AI 事实。这里只展示契约，不发起 API 请求。</p></div></details>
<p class="links"><a href="../database/schema-canvas.html" data-offline-document="database">查看数据库模块 →</a></p>
</footer>
</main>
<script id="api-data" type="application/json">__API_JSON__</script>
<script>
(function () {
  'use strict';
  if(new URLSearchParams(globalThis.location && location.search || '').get('embed')==='1')document.documentElement.classList.add('embedded');
  var D=JSON.parse(document.getElementById('api-data').textContent),state={module:'all',query:'',selected:null},rowButtons=new Map();
  var modules=new Map(D.modules.map(function(m){return [m.module,m];})),stages=new Map(D.stages.map(function(s){return [s.id,s];}));
  var $=function(id){return document.getElementById(id);};
  function el(tag,text,cls){var node=document.createElement(tag);if(text!==undefined)node.textContent=String(text);if(cls)node.className=cls;return node;}
  function label(id){return id==='all'?'全部':({android:'Android',backend:'后端',agent:'Agent',vision:'视觉'})[id]||id;}
  function fold(title){var node=el('details',undefined,'fold'),content=el('div',undefined,'fold-content');node.append(el('summary',title),content);return {node:node,content:content};}
  function matches(op){if(state.module!=='all'&&op.provider!==state.module&&op.callers.indexOf(state.module)<0)return false;if(!state.query)return true;var haystack=[op.method,op.path,op.contract.summary,op.note||'',op.read_tables.join(' '),op.write_tables.join(' '),stages.get(op.stage).label].join(' ').toLowerCase();return haystack.indexOf(state.query.toLowerCase())>=0;}
  function current(){return D.operations.filter(matches);}
  function schemaNames(value,set){set=set||new Set();if(Array.isArray(value))value.forEach(function(v){schemaNames(v,set);});else if(value&&typeof value==='object'){if(value.$ref)set.add(value.$ref.split('/').pop());Object.values(value).forEach(function(v){schemaNames(v,set);});}return set;}
  function contractWithDefinitions(value){var needed=schemaNames(value),queue=Array.from(needed),definitions={};while(queue.length){var name=queue.shift();if(definitions[name]||!D.schemas[name])continue;definitions[name]=D.schemas[name];schemaNames(D.schemas[name]).forEach(function(ref){if(!definitions[ref])queue.push(ref);});}return {contract:value,definitions:definitions};}
  function jsonView(value){return el('pre',JSON.stringify(value,null,2));}
  function restoreFocus(id){if(rowButtons.has(id))rowButtons.get(id).focus({preventScroll:true});}
  function closeDetail(){var former=state.selected;state.selected=null;render();restoreFocus(former);}
  function toggle(id){state.selected=state.selected===id?null:id;render();restoreFocus(id);}
  function samplesFold(title,samples){
    var f=fold(title+' · 模拟样例'),controls=el('div',undefined,'samples-controls'),select=el('select'),copy=el('button','复制 JSON','copy'),pre=jsonView(samples[0].value),source=el('p',samples[0].source,'sample-source'),status=el('p','','copy-status'),fallback;
    copy.type='button';select.setAttribute('aria-label',title+'样例');
    samples.forEach(function(sample,i){var option=el('option',sample.source.split('/').pop()+' · '+sample.schema);option.value=String(i);select.append(option);});
    function chosen(){return samples[Number(select.value)||0];}
    select.addEventListener('change',function(){pre.textContent=JSON.stringify(chosen().value,null,2);source.textContent=chosen().source;status.textContent='';if(fallback){fallback.remove();fallback=null;}});
    copy.addEventListener('click',async function(){var text=JSON.stringify(chosen().value,null,2);try{if(!navigator.clipboard||!globalThis.isSecureContext)throw new Error('manual-copy');await navigator.clipboard.writeText(text);status.textContent='已复制模拟样例。';}catch(_){if(!fallback){fallback=el('textarea',undefined,'copy-fallback');fallback.readOnly=true;fallback.setAttribute('aria-label','手动复制模拟JSON样例');f.content.append(fallback);}fallback.value=text;fallback.focus({preventScroll:true});fallback.select();status.textContent='已选中样例，按 Ctrl/Cmd+C 复制。';}});
    status.setAttribute('role','status');controls.append(select,copy);f.content.append(controls,pre,source,status);return f.node;
  }
  function inputSummary(op){var parameters=op.contract.parameters||[],names=parameters.map(function(p){return p.name+(p.required?' *':'');}),bodies=Object.keys(op.contract.requestBody&&op.contract.requestBody.content||{});return (names.length?'参数：'+names.join('、'):'')+(names.length&&bodies.length?'；':'')+(bodies.length?'请求体：'+bodies.join(' / '):'')||'无请求体；身份从已认证主体取得。';}
  function detail(op){
    var article=el('article',undefined,'operation-detail'),heading=el('div',undefined,'detail-heading'),close=el('button','关闭 · Esc','close'),stage=stages.get(op.stage),owner=modules.get(op.provider);
    close.type='button';close.addEventListener('click',closeDetail);heading.append(el('h2',op.method+' '+op.path),close);article.append(heading);
    article.append(el('p',owner.label+' · '+owner.owner+' 提供；'+op.callers.map(function(c){var m=modules.get(c);return m.label+' · '+m.owner;}).join(' / ')+' 调用。','detail-note'));
    if(op.note)article.append(el('p',op.note,'detail-note'));
    var facts=el('div',undefined,'detail-facts');facts.append(el('span','允许角色：'+(op.contract['x-allowed-roles']||[]).join(' / ')),el('code',op.contract['x-required-scope']==='none'?'Bearer + 本人账号':('scope: '+(op.contract['x-required-scope']||'以 OpenAPI 为准'))),el('span','成功：'+Object.keys(op.contract.responses).filter(function(c){return c.charAt(0)==='2';}).join(' / ')));article.append(facts);
    var conditional=op.contract['x-conditional-required-scope'];if(conditional)article.append(el('p',conditional.when+'时，另需 '+conditional.scope+' 及该次分析的视频授权。','detail-note'));
    var input=fold('输入契约'),request={parameters:op.contract.parameters||[],requestBody:op.contract.requestBody||null},response=fold('响应状态与结构');input.content.append(el('p',inputSummary(op)),jsonView(contractWithDefinitions(request)));response.content.append(jsonView(contractWithDefinitions(op.contract.responses)));article.append(input.node,response.node);
    if(op.samples.request.length){var requestSamples=samplesFold('输入',op.samples.request);if(op.method==='GET'){var hint=el('p','此 JSON 是参数交接样例；GET 使用 path／query 参数，详见输入契约。');requestSamples.children[1].insertBefore(hint,requestSamples.children[1].firstChild);}article.append(requestSamples);}
    if(op.samples.response.length)article.append(samplesFold('输出',op.samples.response));
    var links=fold('关联表与联调交付');links.content.append(el('p','读取：'+(op.read_tables.join('、')||'配置文件，无关联表')+'；写入：'+(op.write_tables.join('、')||'无')),el('p',stage.order+'. '+stage.label+' · '+stage.deliverable),el('p','前置：'+(stage.depends_on.length?stage.depends_on.map(function(id){return stages.get(id).label;}).join('、'):'范围与共享样例')));if(stage.id==='human_review'||stage.id==='semantic_search')links.content.append(el('p','事件证据就绪后，人工留档与语义检索可并行推进。'));links.content.append(el('p','表映射描述服务端业务读写与归属校验；调用方通过 API 访问。'));article.append(links.node);
    var description=fold('完整接口说明');description.content.append(el('p',op.contract.description||op.contract.summary));article.append(description.node);return article;
  }
  function render(){
    var visible=current(),body=$('operation-list');if(!visible.some(function(op){return op.id===state.selected;}))state.selected=null;body.replaceChildren();rowButtons.clear();
    D.stages.filter(function(s){return s.current_operation_count>0;}).sort(function(a,b){return a.order-b.order;}).forEach(function(stage){
      var ops=visible.filter(function(op){return op.stage===stage.id;});if(!ops.length)return;
      var group=el('tr',undefined,'group-row'),cell=el('th',stage.order+'. '+stage.label);cell.colSpan=5;cell.setAttribute('scope','rowgroup');cell.append(el('small',ops.length+' / '+stage.current_operation_count+' 个操作'));group.append(cell);body.append(group);
      ops.forEach(function(op){
        var row=el('tr',undefined,'operation-row'+(state.selected===op.id?' selected':'')),method=el('td'),path=el('td'),button=el('button',op.path,'path');button.type='button';button.dataset.operation=op.id;button.setAttribute('aria-expanded',String(state.selected===op.id));button.setAttribute('aria-controls','detail-'+op.id);button.setAttribute('aria-label',op.method+' '+op.path+'，'+op.contract.summary);
        button.addEventListener('click',function(event){event.stopPropagation();toggle(op.id);});row.addEventListener('click',function(){toggle(op.id);});rowButtons.set(op.id,button);
        method.append(el('span',op.method,'method'+(op.method==='POST'?' post':'')));path.append(button);row.append(method,path,el('td',op.contract.summary),el('td',label(op.provider)+' → '+op.callers.map(label).join(' / '),'direction'),el('td',String(stage.order),'order'));body.append(row);
        if(state.selected===op.id){var expanded=el('tr',undefined,'detail-row'),content=el('td');content.colSpan=5;var article=detail(op);article.id='detail-'+op.id;content.append(article);expanded.append(content);body.append(expanded);}
      });
    });
    $('empty').hidden=visible.length>0;
    document.querySelectorAll('[data-filter]').forEach(function(b){b.setAttribute('aria-pressed',String(b.dataset.filter===state.module));});
    document.querySelectorAll('.flow-node').forEach(function(node){node.classList.toggle('active',node.dataset.module===state.module);node.classList.toggle('dim',state.module!=='all'&&node.dataset.module!==state.module);node.setAttribute('aria-pressed',String(node.dataset.module===state.module));});
    var scope=state.module==='all'?'全部模块':modules.get(state.module).label+' · '+modules.get(state.module).owner;
    var roleCounts=state.module==='all'?'':' · 提供 '+visible.filter(function(op){return op.provider===state.module;}).length+' · 调用 '+visible.filter(function(op){return op.provider!==state.module&&op.callers.indexOf(state.module)>=0;}).length;
    $('view-status').textContent='当前草案 · 未实现 · '+visible.length+' / '+D.metadata.operation_count+' 个操作 · '+scope+roleCounts+(state.query?' · 搜索结果':'');
    $('module-responsibility').hidden=state.module==='all';$('module-responsibility').textContent=state.module==='all'?'':modules.get(state.module).responsibility;
  }
  function selectModule(id){if(id!=='all'&&!modules.has(id))return false;state.module=id;state.query='';state.selected=null;$('search').value='';render();return true;}
  ['all','android','backend','agent','vision'].forEach(function(id){var button=el('button',label(id),'filter');button.type='button';button.dataset.filter=id;button.addEventListener('click',function(){selectModule(id);});$('module-filters').append(button);});
  document.querySelectorAll('.flow-node').forEach(function(node){node.addEventListener('click',function(){selectModule(node.dataset.module);});node.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();selectModule(node.dataset.module);}});});
  $('search').addEventListener('input',function(e){state.query=e.target.value.trim();render();});$('clear-search').addEventListener('click',function(){state.query='';$('search').value='';render();$('search').focus();});$('reset').addEventListener('click',function(){selectModule('all');});
  document.addEventListener('keydown',function(e){if(e.key==='Escape'&&state.selected)closeDetail();});
  D.proposed.forEach(function(op){var node=el('div',undefined,'proposal');node.append(el('code',op.method+' '+op.path+' · 建议'),el('p',op.summary));$('proposals').append(node);});
  D.implementation_boundaries.forEach(function(note){$('boundaries').append(el('p',note));});
  $('stage-sequence').textContent=D.stages.filter(function(stage){return stage.current_operation_count>0;}).sort(function(a,b){return a.order-b.order;}).map(function(stage){return String.fromCharCode(0x2460+stage.order-1)+' '+stage.label;}).join(' → ')+' · 首页待补';
  $('sdk-note').textContent='SDK 独立支线 · '+D.sdk_branch.owner+'：'+D.sdk_branch.note;
  $('source-summary').textContent=D.metadata.api_version+' · '+D.metadata.generated_date+' · OpenAPI '+D.metadata.source_sha256.slice(0,12)+'… · 模块映射 '+D.metadata.mapping_sha256.slice(0,12)+'…';
  $('source-files').textContent=D.metadata.source_files.join(' · ');
  window.PartraceApi=Object.freeze({selectModule:selectModule});
  render();
})();
</script>
</body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/api/api-canvas.html")
    parser.add_argument("--check", action="store_true", help="Check generated HTML and source/annotation/fixture parity without writing.")
    args = parser.parse_args()
    if not args.output.resolve().is_relative_to(ROOT):
        raise ValueError("API output must stay inside this repository")
    data = build_data()
    serialized = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    for character, escaped in (("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e")):
        serialized = serialized.replace(character, escaped)
    html = TEMPLATE.replace("__API_JSON__", serialized)
    html = html.replace("__OPERATION_COUNT__", str(data["metadata"]["operation_count"]))
    html = html.replace("__BACKEND_COUNT__", str(data["metadata"]["service_counts"]["backend"]))
    html = html.replace("__AGENT_COUNT__", str(data["metadata"]["service_counts"]["agent"]))
    html = html.replace("__PROPOSED_COUNT__", str(data["metadata"]["proposed_count"]))
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != html:
            parser.exit(1, "API canvas is stale; run python3 scripts/build-api-canvas.py.\n")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(html, encoding="utf-8")
    print(json.dumps({"status": "checked" if args.check else "generated", "operations": data["metadata"]["operation_count"],
                      "providers": data["metadata"]["service_counts"], "proposed": data["metadata"]["proposed_count"],
                      "output": args.output.resolve().relative_to(ROOT).as_posix(), "offline": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()

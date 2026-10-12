#!/usr/bin/env python3
"""Build the offline schema explorer from the SQL-derived catalog, without records."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


TEMPLATE = r'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ParTrace Vision · 数据结构草案</title>
<style>
:root{--page:#f4f7fb;--card:#fff;--ink:#102a43;--muted:#596c80;--faint:#8794a4;--line:#d8e1eb;--accent:#006ddd;--accent-soft:#e8f2ff;--warning:#98620d;--warning-soft:#fff4db;--radius:12px;--mono:ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;--sans:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
.edge:focus-visible{stroke:var(--accent);stroke-width:3;outline:2px solid var(--accent);outline-offset:4px}
*{box-sizing:border-box}body{margin:0;background:var(--page);color:var(--ink);font:14px/1.5 var(--sans)}button,input{font:inherit}button{cursor:pointer;color:inherit}button:disabled{cursor:default;opacity:.45}button:focus-visible,input:focus-visible,a:focus-visible{outline:3px solid var(--accent);outline-offset:3px}button{touch-action:manipulation}a{color:var(--accent)}.skip{position:absolute;top:-100px;left:24px;z-index:20;background:white;padding:12px}.skip:focus{top:12px}.page{max-width:1600px;margin:auto;padding:26px 32px 30px}.header{display:flex;justify-content:space-between;align-items:flex-start;gap:24px;margin-bottom:18px}.eyebrow{font-size:12px;color:var(--muted);margin:0 0 4px}h1{font-size:25px;line-height:1.25;letter-spacing:-.7px;margin:0 0 7px}h2,h3,p{margin-top:0}.intro{max-width:840px;margin:0;color:var(--muted);font-size:13px}.draft{flex:none;border:1px solid #b9cde5;border-radius:8px;padding:6px 10px;font-size:12px;color:var(--accent);background:var(--accent-soft)}.facts{display:flex;gap:14px;flex-wrap:wrap;color:var(--muted);font-size:12px;margin:10px 0 0}.facts b{color:var(--ink)}.surface{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden}.toolbar{padding:14px 16px 12px;border-bottom:1px solid var(--line);display:flex;flex-wrap:wrap;gap:10px 18px;align-items:center}.search-wrap{position:relative;flex:1 1 250px;max-width:450px}.search-wrap label{display:block;font-size:12px;color:var(--muted);margin-bottom:5px}.search-box{display:flex;border:1px solid #bdcad9;border-radius:8px;overflow:visible;background:#fff}.search-box input{border:0;min-width:0;flex:1;padding:9px 11px;background:transparent;outline-offset:2px}.clear{border:0;background:transparent;padding:0 11px;color:var(--muted)}.filters{display:flex;gap:6px;flex-wrap:wrap;align-items:center}.filter{border:1px solid var(--line);border-radius:8px;background:white;padding:7px 10px;font-size:12px}.filter[aria-pressed="true"]{color:var(--accent);border-color:var(--accent);background:var(--accent-soft);font-weight:650}.display-options{display:flex;flex-wrap:wrap;gap:10px 16px;align-items:center;font-size:12px;color:var(--muted)}.display-options label{display:flex;align-items:center;gap:5px}.display-options input{accent-color:var(--accent)}.display-options .related{color:var(--ink)}.reset{border:1px solid var(--line);background:white;border-radius:8px;padding:7px 11px;font-size:12px}.status-bar{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:8px;padding:9px 16px;font-size:12px;color:var(--muted);border-bottom:1px solid var(--line)}.status-bar p{margin:0}.legend{display:flex;gap:16px;flex-wrap:wrap;align-items:center}.legend span{display:flex;align-items:center;gap:6px}.line-key{display:inline-block;width:21px;border-top:2px solid #9baabd}.line-key.logical{border-top:2px dashed var(--accent)}.search-results{padding:12px 16px;background:#f8fbff;border-bottom:1px solid var(--line)}.search-results p{font-size:12px;color:var(--muted);margin:0 0 8px}.result-list{display:flex;gap:7px;flex-wrap:wrap}.result{display:flex;gap:8px;align-items:center;border:1px solid #c8d9ef;background:#fff;border-radius:8px;padding:7px 10px;text-align:left;font-size:12px}.result code{font:12px var(--mono)}.result small{color:var(--muted);max-width:260px;overflow-wrap:anywhere}.empty{padding:30px 18px;text-align:center;color:var(--muted);font-size:13px}.diagram-scroll{overflow:auto;min-height:480px;max-height:900px;position:relative;overscroll-behavior-x:contain;scrollbar-gutter:stable;background:#fbfdff}.canvas-stage{margin:12px auto 16px;position:relative}.canvas{position:absolute;left:0;top:0;transform-origin:0 0}.domain-region{position:absolute;border:1px solid #e2e9f2;border-radius:12px;background:#f8fafd;pointer-events:none}.domain-head{position:absolute;left:20px;top:12px;display:flex;gap:10px;align-items:center;font-size:12px;line-height:1.5;color:var(--muted)}.domain-head b{color:var(--ink);font-weight:650;font-size:13px}.domain-head span{font:11px var(--mono)}.edges{position:absolute;left:0;top:0;overflow:visible;pointer-events:none}.edge{stroke:#9aaac0;stroke-width:1.4;fill:none;transition:opacity .15s;pointer-events:stroke;cursor:pointer}.edge.logical{stroke:var(--accent);stroke-width:1.3;stroke-dasharray:5 5}.edge.active{stroke:var(--accent);stroke-width:2.5}.edge.dim{opacity:.1}.edge.selected-edge{stroke-width:3.5}.edge-label{font:10px var(--mono);fill:var(--muted);paint-order:stroke;stroke:#fbfdff;stroke-width:5px;stroke-linejoin:round;pointer-events:none}.edge-label.logical{fill:var(--accent);font-family:var(--sans)}.table-node{position:absolute;display:block;border:1px solid #cdd9e7;border-radius:10px;background:white;width:220px;min-height:112px;padding:12px 13px;text-align:left;box-shadow:0 2px 4px #17385e08;transition:border-color .15s,opacity .15s}.table-node:hover{border-color:var(--accent)}.table-node.selected{border:2px solid var(--accent);padding:11px 12px;box-shadow:0 0 0 4px #006ddd12}.table-node.selected:before{content:"已选";position:absolute;top:-11px;right:12px;border-radius:4px;background:var(--accent);color:white;padding:1px 6px;font-size:10px}.table-node.dim{opacity:.25}.table-node.match{border-color:#6ea4e2}.table-name{display:block;font:600 13px/1.35 var(--mono);overflow-wrap:anywhere;color:var(--ink)}.table-label{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:12px;color:var(--muted);margin:3px 0 9px}.key-lines{display:flex;flex-direction:column;gap:3px;padding-right:32px}.key-field{display:flex;align-items:center;gap:6px;min-width:0;font:11px/1.35 var(--mono);color:var(--muted)}.key-mark{flex:none;font-size:9px;font-weight:650;letter-spacing:.2px;color:var(--accent);min-width:17px}.key-field span:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-count{position:absolute;right:11px;bottom:10px;color:var(--faint);font:10px var(--mono)}.zoom-tools{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:9px 16px;border-top:1px solid var(--line);font-size:12px;color:var(--muted)}.zoom-buttons{display:flex;gap:4px;align-items:center}.zoom-buttons button{border:1px solid var(--line);border-radius:6px;background:white;min-width:31px;height:29px;font-size:16px}.zoom-buttons output{padding:0 7px;min-width:52px;text-align:center}.detail{margin-top:18px;background:#fff;border:1px solid var(--line);border-radius:12px;scroll-margin-top:16px}.detail-header{display:flex;gap:20px;align-items:flex-start;justify-content:space-between;padding:18px 20px 14px;border-bottom:1px solid var(--line)}.detail-header h2{font:650 20px/1.3 var(--mono);margin:0 0 6px;overflow-wrap:anywhere}.detail-header p{font-size:13px;color:var(--muted);margin:0;max-width:950px}.close{border:1px solid var(--line);background:white;border-radius:7px;padding:5px 10px;flex:none;font-size:12px}.detail-body{padding:16px 20px 20px;max-height:620px;overflow:auto}.detail-facts{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:14px;color:var(--muted);font-size:12px}.table-wrap{overflow:auto;margin-bottom:20px}table{border-collapse:collapse;width:100%;font-size:12px;text-align:left}caption{text-align:left;font-size:14px;font-weight:650;margin-bottom:9px}th,td{padding:10px 11px;border-bottom:1px solid #e6edf5;vertical-align:top}thead{background:#f5f8fc}th{font-weight:600;color:var(--muted)}td code,th code{font:12px var(--mono);white-space:nowrap}td small{font:10px var(--mono);color:var(--accent);display:inline-block;padding:1px 4px;background:var(--accent-soft);border-radius:3px;margin-right:3px}.default-value{white-space:pre-wrap;word-break:break-word;font:12px var(--mono)}.detail-columns{display:grid;grid-template-columns:1fr 1fr;gap:22px}.detail-section{margin-bottom:20px;min-width:0}.detail-section h3{font-size:14px;line-height:1.4;margin-bottom:9px}.detail-section ul{padding-left:18px;margin:0;font-size:12px;color:var(--muted)}.detail-section li{margin-bottom:7px}.constraint-name{font:11px var(--mono);color:var(--ink)}.constraint-expression{font:11px/1.55 var(--mono);overflow-wrap:anywhere;white-space:pre-wrap;color:var(--muted);margin:3px 0 0}.constraint-item{padding-bottom:10px;border-bottom:1px solid #e8eef5;margin-bottom:10px}.relation-button{display:block;width:100%;text-align:left;margin-bottom:7px;padding:9px 10px;border-radius:8px;border:1px solid var(--line);background:white;font-size:12px}.relation-button:hover{border-color:var(--accent);background:#f8fbff}.relation-button b{font:600 12px var(--mono);color:var(--accent)}.relation-button span{display:block;color:var(--muted);margin-top:4px;font-size:11px;overflow-wrap:anywhere}.logical-label{color:var(--accent);font-weight:600}.relation-note{font-size:12px;color:var(--muted)}.footer{margin-top:18px;color:var(--muted);font-size:12px;line-height:1.65}.footer p{margin:0 0 5px}.source-path{font:11px var(--mono);overflow-wrap:anywhere}.footer .scope{color:var(--ink)}[hidden]{display:none!important}
@media(max-width:1050px){.page{padding:22px 20px}.header{gap:14px}.toolbar{gap:12px}.detail-columns{grid-template-columns:1fr}.diagram-scroll{min-height:450px;max-height:760px}.canvas-stage{margin-left:12px;margin-right:12px}}
@media(max-width:700px){.page{padding:18px 12px}h1{font-size:22px}.header{display:block}.draft{display:inline-block;margin-top:10px}.toolbar{padding:12px}.search-wrap{max-width:none;flex-basis:100%}.filters{width:100%;gap:6px}.filter{padding:7px 8px}.status-bar{padding:10px 12px;align-items:flex-start}.legend{gap:10px}.diagram-scroll{min-height:440px;max-height:600px}.zoom-tools{padding:9px 12px;align-items:flex-start}.zoom-tools p{margin:0;max-width:230px}.detail-header,.detail-body{padding:14px}.detail-header h2{font-size:18px}.detail-header{gap:10px}.facts{gap:9px}th,td{padding:9px 8px}.detail-body{max-height:680px}}
@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}
@media print{@page{size:A3 landscape;margin:12mm}body{background:white}.page{max-width:none;padding:0}.skip,.toolbar,.search-results,.zoom-tools,.close{display:none!important}.header{margin-bottom:10px}.surface,.detail{border-color:#bac7d6;break-inside:avoid}.diagram-scroll{overflow:visible;max-height:none;min-height:0;scrollbar-gutter:auto;background:white}.canvas-stage{margin:0;width:1240px!important;zoom:.83}.canvas{transform:none!important}.edge.dim,.table-node.dim{opacity:1}.domain-region{background:white}.detail-body{max-height:none;overflow:visible}.table-wrap{overflow:visible}.detail-columns{grid-template-columns:1fr 1fr}.footer{font-size:10px}.table-node,.edge{transition:none}.table-node{box-shadow:none}}
</style>
</head>
<body>
<a class="skip" href="#canvas-main">跳到数据关系图</a>
<main class="page">
<header class="header"><div><p class="eyebrow">ParTrace Vision · 驿站追迹 / D01 共享数据约定</p><h1>数据结构草案</h1><p class="intro">先看四个数据域，再选一张表查看字段与约束。实线表示 SQL 草案声明的外键，虚线表示历史 JSON 快照里的逻辑引用。</p><p class="facts" id="schema-facts"></p></div><span class="draft">待评审 · 尚未部署</span></header>
<section class="surface" aria-label="数据库结构交互图">
<div class="toolbar"><div class="search-wrap"><label for="search">搜索表或字段</label><div class="search-box"><input id="search" type="search" placeholder="例如 events、camera_id" autocomplete="off" spellcheck="false"><button id="clear-search" class="clear" aria-label="清空搜索" title="清空搜索">×</button></div></div><nav id="domain-filters" class="filters" aria-label="数据域筛选"></nav><div class="display-options"><label class="related"><input type="checkbox" id="related-only">只看关联表</label><label><input type="checkbox" id="show-fk" checked>SQL 外键</label><label><input type="checkbox" id="show-logical" checked>逻辑引用</label><button id="reset" class="reset">复位</button></div></div>
<div id="search-results" class="search-results" hidden><p id="search-explanation"></p><div id="result-list" class="result-list"></div></div>
<div class="status-bar"><p id="view-status" role="status" aria-live="polite"></p><div class="legend"><span><i class="line-key" aria-hidden="true"></i>外键：引用表 → 被引用表</span><span><i class="line-key logical" aria-hidden="true"></i>逻辑快照引用</span></div></div>
<div id="canvas-main" class="diagram-scroll" tabindex="0" aria-label="可横向滚动的关系图"><div id="canvas-stage" class="canvas-stage"><div id="canvas" class="canvas"><svg id="edges" class="edges" role="group" aria-label="表之间可点选的外键与逻辑引用"><defs><marker id="fk-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 1 L 9 5 L 0 9" fill="none" stroke="#9aaac0" stroke-width="1.5"/></marker><marker id="active-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 1 L 9 5 L 0 9" fill="none" stroke="#006ddd" stroke-width="1.6"/></marker></defs></svg></div></div></div>
<div id="no-view" class="empty" hidden></div><div class="zoom-tools"><p id="selection-hint" style="margin:0">选表查看字段；关联路径显示方向与基数。</p><div class="zoom-buttons"><button id="zoom-out" aria-label="缩小关系图，最小为原字号">−</button><output id="zoom-value" aria-label="图形缩放">100%</output><button id="zoom-in" aria-label="放大关系图">+</button></div></div>
</section>
<section id="detail" class="detail" aria-label="所选表的完整定义" hidden><header class="detail-header"><div><h2 id="detail-title"></h2><p id="detail-description"></p></div><button id="close-detail" class="close">关闭 · Esc</button></header><div id="detail-body" class="detail-body"></div></section>
<footer class="footer"><p id="source-line"></p><p id="parity-line"></p><p class="scope">范围：首版用版本化策略配置与清理日志；CandidateAssociation、向量检索、独立 RetentionPolicy 表后置。</p><p>这是接口与数据库讨论草案，SQLite 本地验证只证明定义可执行；生产引擎尚未确定，未部署真实服务。未读取业务记录。</p></footer>
</main>
<script id="schema-data" type="application/json">__SCHEMA_JSON__</script>
<script>
(() => {
  'use strict';
  const S = JSON.parse(document.getElementById('schema-data').textContent);
  const $ = id => document.getElementById(id);
  const NS = 'http://www.w3.org/2000/svg';
  const tables = S.tables;
  const byName = new Map(tables.map(t => [t.name, t]));
  const domains = Array.isArray(S.domains) ? S.domains : Object.entries(S.domains).map(([id, d]) => typeof d === 'string' ? {id, label:d} : {id, ...d});
  const domainById = new Map(domains.map(d => [d.id || d.name, d]));
  const relationships = [...(S.relationships || []).map(r => ({...r, kind:'sql_fk'})), ...(S.logical_relationships || []).map(r => ({...r, kind:'logical_snapshot'}))];
  const positions = new Map();
  const nodes = new Map();
  const edgeObjects = [];
  const edgePorts = new Map();
  const usedSegments = new Map();
  const state = {domain:'all',query:'',selected:null,related:false,showFk:true,showLogical:true,zoom:1,searchBase:'all'};
  let canvasHeight = 0;
  const canvasWidth = 1240;

  function el(tag, text, cls) { const e = document.createElement(tag); if (text !== undefined) e.textContent = String(text); if (cls) e.className = cls; return e; }
  function svg(tag, attrs) { const e = document.createElementNS(NS, tag); for (const [k,v] of Object.entries(attrs || {})) e.setAttribute(k, String(v)); return e; }
  function list(v) { return Array.isArray(v) ? v : v == null ? [] : [v]; }
  function columnList(r, key) { return list(r[key]).map(String); }
  function display(v) { return v == null ? '无默认' : typeof v === 'object' ? JSON.stringify(v) : String(v); }
  function domainLabel(id) { const d = domainById.get(id); return d?.label || d?.title || id; }
  function isLogical(r) { return r.kind !== 'sql_fk'; }
  function fromName(r) { return r.from_table || r.from; }
  function toName(r) { return r.to_table || r.to; }
  function otherName(r, name) { return fromName(r) === name ? toName(r) : fromName(r); }
  function relationId(r, index) { return r.id || `${r.kind}-${fromName(r)}-${toName(r)}-${index}`; }
  function activeKind(r) { return isLogical(r) ? state.showLogical : state.showFk; }
  function relatedSet() { const result = new Set(state.selected ? [state.selected] : []); if (state.selected) relationships.filter(activeKind).forEach(r => { if (fromName(r) === state.selected || toName(r) === state.selected) result.add(otherName(r, state.selected)); }); return result; }
  function searchMatches() {
    const q = state.query.toLowerCase();
    if (!q) return [];
    return tables.map(t => {
      const name = t.name.toLowerCase();
      const fields = t.columns.filter(c => c.name.toLowerCase().includes(q)).map(c => c.name);
      const rank = name === q ? 0 : name.startsWith(q) ? 1 : name.includes(q) ? 2 : fields.length ? 3 : (t.label || '').toLowerCase().includes(q) ? 4 : 99;
      return {table:t,rank,fields};
    }).filter(x => x.rank < 99).sort((a,b) => a.rank-b.rank || a.table.name.localeCompare(b.table.name));
  }
  function visibleNames() {
    const related = relatedSet();
    return new Set(tables.filter(t => (state.query || state.domain === 'all' || t.domain === state.domain) && (!state.related || !state.selected || related.has(t.name))).map(t => t.name));
  }
  function cardinality(r) {
    if (isLogical(r)) return '逻辑引用 · 无数据库基数保证';
    const table = byName.get(fromName(r));
    const cols = columnList(r, 'from_columns');
    const pk = table.columns.filter(c => c.primary_key).map(c => c.name);
    const unique = cols.length > 0 && (pk.length === cols.length && pk.every(c => cols.includes(c)) || cols.length === 1 && table.columns.find(c => c.name === cols[0])?.unique || (table.indices || []).some(i => i.unique && !i.partial && i.columns?.length === cols.length && i.columns.every(c => cols.includes(typeof c === 'string' ? c : c.name))));
    const optional = cols.some(name => table.columns.find(c => c.name === name)?.nullable);
    return `${unique ? '0..1' : '0..N'} → ${optional ? '0..1' : '1'}`;
  }
  function relationText(r) {
    const from = fromName(r), to = toName(r);
    if (isLogical(r)) return r.label || r.description || `${from} 的 JSON 历史引用 → ${to}`;
    return `${from}.${columnList(r,'from_columns').join('+')} → ${to}.${columnList(r,'to_columns').join('+')}`;
  }
  function prepareLayout() {
    const ids = domains.map(d => d.id || d.name);
    for (const t of tables) if (!ids.includes(t.domain)) ids.push(t.domain);
    const rowHeights = [];
    ids.forEach((id, index) => { const count = tables.filter(t=>t.domain===id).length; const h = 60 + Math.ceil(count/2)*158 + 8; rowHeights[Math.floor(index/2)] = Math.max(rowHeights[Math.floor(index/2)] || 0,h); });
    const rowTops = []; let offset = 10;
    rowHeights.forEach(h=>{rowTops.push(offset);offset+=h+28;});
    canvasHeight = offset;
    ids.forEach((id,index)=>{
      const ts = tables.filter(t=>t.domain===id), x = 10+(index%2)*620, y = rowTops[Math.floor(index/2)], h = rowHeights[Math.floor(index/2)];
      const region = el('div',undefined,'domain-region'); Object.assign(region.style,{left:`${x}px`,top:`${y}px`,width:'600px',height:`${h}px`});
      const heading = el('div',undefined,'domain-head'); heading.append(el('b',domainLabel(id)),el('span',`${id} / ${ts.length} 张表`)); region.append(heading); $('canvas').insertBefore(region,$('edges'));
      ts.forEach((t,i)=>positions.set(t.name,{x:x+28+(i%2)*304,y:y+58+Math.floor(i/2)*158,w:220,h:112}));
    });
    $('canvas').style.width = `${canvasWidth}px`; $('canvas').style.height = `${canvasHeight}px`;
    $('edges').setAttribute('width',canvasWidth); $('edges').setAttribute('height',canvasHeight); $('edges').setAttribute('viewBox',`0 0 ${canvasWidth} ${canvasHeight}`);
  }
  function buildNodes() {
    tables.forEach(t=>{
      const p=positions.get(t.name), b=el('button',undefined,'table-node'); b.type='button'; b.dataset.table=t.name; b.setAttribute('aria-pressed','false'); b.setAttribute('aria-label',`查看 ${t.name}，${t.label}，${t.columns.length} 个字段`); Object.assign(b.style,{left:`${p.x}px`,top:`${p.y}px`});
      b.append(el('span',t.name,'table-name'),el('span',t.label || t.description,'table-label'));
      const keys=el('span',undefined,'key-lines'); const candidates=[...t.columns.filter(c=>c.primary_key),...t.columns.filter(c=>!c.primary_key && c.foreign_key)];
      const shown=(candidates.length ? candidates : t.columns).slice(0,2);
      shown.forEach(c=>{const line=el('span',undefined,'key-field');line.append(el('span',c.primary_key?'PK':c.foreign_key?'FK':'字段','key-mark'),el('span',c.name));keys.append(line);});
      b.append(keys,el('span',`${t.columns.length} 列`,'node-count'));b.addEventListener('click',()=>selectTable(t.name));nodes.set(t.name,b);$('canvas').append(b);
    });
  }
  function preparePorts() {
    const groups = new Map();
    relationships.forEach((r,index) => {
      const a=positions.get(fromName(r)),b=positions.get(toName(r));if(!a||!b)return;
      const dx=(b.x+b.w/2)-(a.x+a.w/2),dy=(b.y+b.h/2)-(a.y+a.h/2),horizontal=Math.abs(dx)>Math.abs(dy);
      const sides=horizontal?[dx>0?'right':'left',dx>0?'left':'right']:[dy>0?'bottom':'top',dy>0?'top':'bottom'];
      [fromName(r),toName(r)].forEach((name,endpoint)=>{const key=`${name}:${sides[endpoint]}`;if(!groups.has(key))groups.set(key,[]);groups.get(key).push({index,endpoint,name,side:sides[endpoint]});});
    });
    groups.forEach(group=>group.forEach((item,index)=>{const p=positions.get(item.name),offset=Math.max(-40,Math.min(40,(index-(group.length-1)/2)*10)),horizontal=['left','right'].includes(item.side);const point={x:horizontal?(item.side==='right'?p.x+p.w:p.x):p.x+p.w/2+offset,y:horizontal?p.y+p.h/2+offset:(item.side==='bottom'?p.y+p.h:p.y)};edgePorts.set(`${item.index}:${item.endpoint}`,{...point,side:item.side});}));
  }
  function route(source,target,index) {
    const a=positions.get(source), b=positions.get(target);
    const dx=(b.x+b.w/2)-(a.x+a.w/2),dy=(b.y+b.h/2)-(a.y+a.h/2), horizontal=Math.abs(dx)>Math.abs(dy);
    const start=edgePorts.get(`${index}:0`) || (horizontal ? {x:dx>0?a.x+a.w:a.x,y:a.y+a.h/2} : {x:a.x+a.w/2,y:dy>0?a.y+a.h:a.y});
    const end=edgePorts.get(`${index}:1`) || (horizontal ? {x:dx>0?b.x:b.x+b.w,y:b.y+b.h/2} : {x:b.x+b.w/2,y:dy>0?b.y:b.y+b.h});
    const startOut={x:start.x+(horizontal?(dx>0?16:-16):0),y:start.y+(!horizontal?(dy>0?16:-16):0)};
    const endOut={x:end.x+(horizontal?(dx>0?-16:16):0),y:end.y+(!horizontal?(dy>0?-16:16):0)};
    const boxes=[...positions.values()].map(p=>({l:p.x-10,r:p.x+p.w+10,t:p.y-10,b:p.y+p.h+10}));
    const xs=new Set([6,canvasWidth-6,startOut.x,endOut.x]),ys=new Set([6,canvasHeight-6,startOut.y,endOut.y]);
    boxes.forEach(p=>{xs.add(p.l-6);xs.add(p.r+6);ys.add(p.t-6);ys.add(p.b+6);});
    const xx=[...xs].sort((x,y)=>x-y),yy=[...ys].sort((x,y)=>x-y);
    const inside=(x,y)=>boxes.some(p=>x>p.l&&x<p.r&&y>p.t&&y<p.b);
    const clear=(x1,y1,x2,y2)=>!boxes.some(p=> x1===x2 ? x1>p.l&&x1<p.r&&Math.max(y1,y2)>p.t&&Math.min(y1,y2)<p.b : y1>p.t&&y1<p.b&&Math.max(x1,x2)>p.l&&Math.min(x1,x2)<p.r);
    const si=xx.indexOf(startOut.x),sj=yy.indexOf(startOut.y),ei=xx.indexOf(endOut.x),ej=yy.indexOf(endOut.y);
    const segmentKey=(x1,y1,x2,y2)=>[[x1,y1].join(','),[x2,y2].join(',')].sort().join('|');
    const key=(i,j,d)=>`${i},${j},${d}`,first={i:si,j:sj,d:0,g:0,f:0,prev:null};const queue=[first],best=new Map([[key(si,sj,0),0]]);let reached=null,iterations=0;
    while(queue.length&&iterations++<20000){queue.sort((a,b)=>a.f-b.f);const cur=queue.shift();if(cur.i===ei&&cur.j===ej){reached=cur;break;}for(const [di,dj,d] of [[1,0,1],[-1,0,1],[0,1,2],[0,-1,2]]){const i=cur.i+di,j=cur.j+dj;if(i<0||j<0||i>=xx.length||j>=yy.length||inside(xx[i],yy[j])||!clear(xx[cur.i],yy[cur.j],xx[i],yy[j]))continue;const usage=usedSegments.get(segmentKey(xx[cur.i],yy[cur.j],xx[i],yy[j]))||0;const g=cur.g+Math.abs(xx[i]-xx[cur.i])+Math.abs(yy[j]-yy[cur.j])+(cur.d&&cur.d!==d?24:0)+usage*32,k=key(i,j,d);if(best.has(k)&&best.get(k)<=g)continue;best.set(k,g);queue.push({i,j,d,g,f:g+Math.abs(xx[i]-endOut.x)+Math.abs(yy[j]-endOut.y),prev:cur});}}
    let points=[];if(reached){for(let c=reached;c;c=c.prev)points.unshift([xx[c.i],yy[c.j]]);}else{points=[[startOut.x,startOut.y],[startOut.x,endOut.y],[endOut.x,endOut.y]];}
    for(let i=1;i<points.length;i++){const key=segmentKey(...points[i-1],...points[i]);usedSegments.set(key,(usedSegments.get(key)||0)+1);}
    points=[[start.x,start.y],...points,[end.x,end.y]];
    const compact=[];points.forEach(p=>{if(compact.length>1){const q=compact[compact.length-1],r=compact[compact.length-2];if(q[0]===r[0]&&q[0]===p[0]||q[1]===r[1]&&q[1]===p[1])compact.pop();}compact.push(p);});
    let longest={length:0,x:0,y:0};for(let i=1;i<compact.length;i++){const a=compact[i-1],b=compact[i],l=Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1]);if(l>longest.length)longest={length:l,x:(a[0]+b[0])/2,y:(a[1]+b[1])/2};}
    return {d:compact.map((p,i)=>`${i?'L':'M'} ${p[0]} ${p[1]}`).join(' '),label:longest};
  }
  function buildEdges() {
    relationships.forEach((r,index)=>{
      const from=fromName(r),to=toName(r);if(!positions.has(from)||!positions.has(to))return;
      const routed=route(from,to,index),g=svg('g'),path=svg('path',{d:routed.d,class:`edge${isLogical(r)?' logical':''}`,'marker-end':isLogical(r)?'url(#active-arrow)':'url(#fk-arrow)',tabindex:'0',role:'button','aria-label':`${relationText(r)}，${cardinality(r)}，点击查看引用表 ${from}`});
      path.append(svg('title'));path.firstChild.textContent=`${relationText(r)} · ${cardinality(r)}${!isLogical(r)?` · 删除 ${r.on_delete} / 更新 ${r.on_update}${r.initially_deferred?' · 事务提交时校验':''}`:''}`;
      const label=svg('text',{x:routed.label.x,y:routed.label.y-6,'text-anchor':'middle',class:`edge-label${isLogical(r)?' logical':''}`});label.textContent=isLogical(r)?'逻辑引用':cardinality(r);
      const activate=()=>selectTable(state.selected===from?to:from);path.addEventListener('click',activate);path.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();activate();}});
      path.addEventListener('pointerenter',()=>{label.style.display='';});path.addEventListener('pointerleave',()=>{if(state.selected!==from&&state.selected!==to)label.style.display='none';});path.addEventListener('focus',()=>{label.style.display='';});path.addEventListener('blur',()=>{if(state.selected!==from&&state.selected!==to)label.style.display='none';});
      g.append(path,label);$('edges').append(g);edgeObjects.push({r,id:relationId(r,index),g,path,label});
    });
  }
  function renderSearch() {
    $('search-results').hidden=!state.query;$('result-list').replaceChildren();if(!state.query)return;
    const matches=searchMatches();$('search-explanation').textContent=matches.length?`匹配 ${matches.length} 张表；保留关系图上下文。清空搜索回到「${state.searchBase==='all'?'全部':domainLabel(state.searchBase)}」。`:'没有匹配的表或字段，请换一个关键词。';
    matches.forEach(item=>{const b=el('button',undefined,'result');b.type='button';b.append(el('code',item.table.name),el('small',item.rank===3?`字段：${item.fields.join('、')}`:item.table.label));b.addEventListener('click',()=>{selectTable(item.table.name);const node=nodes.get(item.table.name);node.focus({preventScroll:true});node.scrollIntoView({block:'nearest',inline:'center'});});$('result-list').append(b);});
  }
  function renderView() {
    const visible=visibleNames(),related=relatedSet(),matches=new Set(searchMatches().map(m=>m.table.name));
    nodes.forEach((node,name)=>{node.hidden=!visible.has(name);node.classList.toggle('selected',state.selected===name);node.classList.toggle('dim',state.selected ? !related.has(name) : !!state.query&&!matches.has(name));node.classList.toggle('match',!!state.query&&matches.has(name));node.setAttribute('aria-pressed',String(state.selected===name));});
    edgeObjects.forEach(e=>{const from=fromName(e.r),to=toName(e.r),active=state.selected===from||state.selected===to;e.g.style.display=activeKind(e.r)&&visible.has(from)&&visible.has(to)?'':'none';e.path.classList.toggle('active',active);e.path.classList.toggle('dim',!!state.selected&&!active);e.path.setAttribute('marker-end',active||isLogical(e.r)?'url(#active-arrow)':'url(#fk-arrow)');e.label.style.display=active?'':'none';});
    document.querySelectorAll('[data-domain]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.domain===state.domain)));
    let note=`当前 ${visible.size} / 全部 ${tables.length} 张表`;
    if(state.related&&!state.selected)note+=' · 先选一张表，再显示它的直接关联表。';else if(state.related)note+=' · 仅选中表与当前启用关系的直接邻居。';else if(state.domain!=='all'&&!state.query)note+=` · ${domainLabel(state.domain)}`;
    $('view-status').textContent=note;$('no-view').hidden=visible.size!==0;$('no-view').textContent='这个数据域没有可显示的表，选择「全部」恢复概览。';
    $('selection-hint').textContent=state.selected?`已选 ${state.selected} · 下方查看完整定义；Esc 关闭详情。`:'选表查看字段；关联路径显示方向与基数。';
    renderSearch();
  }
  function makeSection(title) {const section=el('section',undefined,'detail-section');section.append(el('h3',title));return section;}
  function renderDetails() {
    if(!state.selected){$('detail').hidden=true;$('detail-body').replaceChildren();return;}
    const t=byName.get(state.selected),body=$('detail-body');$('detail').hidden=false;$('detail-title').textContent=t.name;$('detail-description').textContent=`${t.label} · ${t.description || ''}`;body.replaceChildren();
    const facts=el('div',undefined,'detail-facts');[domainLabel(t.domain),`${t.columns.length} 个字段`,`${t.foreign_keys.length} 个声明外键`,`${t.indices.length} 个索引`,t.row_count===null?'未读取业务记录':`记录数：${t.row_count}`].forEach(text=>facts.append(el('span',text)));body.append(facts);
    const wrap=el('div',undefined,'table-wrap'),table=el('table'),caption=el('caption','字段与约束');table.append(caption);const thead=el('thead'),head=el('tr');['字段','类型','约束','可为空','默认值'].forEach(s=>head.append(el('th',s)));thead.append(head);table.append(thead);const tbody=el('tbody');
    t.columns.forEach(c=>{const tr=el('tr'),name=el('td'),type=el('td'),markers=el('td'),nullable=el('td',c.nullable?'是':'否'),def=el('td',display(c.default),'default-value');name.append(el('code',c.name));type.append(el('code',c.type));if(c.primary_key)markers.append(el('small',`PK${c.pk_position>1?` #${c.pk_position}`:''}`));if(c.foreign_key)markers.append(el('small','FK'));if(c.unique)markers.append(el('small','UQ'));if(t.indices.some(i=>(i.columns||[]).some(v=>(typeof v==='string'?v:v.name)===c.name)))markers.append(el('small','IDX'));const composite=t.indices.filter(i=>i.unique&&!i.partial&&i.columns.length>1&&list(c.unique_groups).includes(i.name));if(composite.length)markers.append(el('span',`组合唯一：${composite.map(i=>i.name).join('；')}`));if(!markers.childNodes.length)markers.textContent='—';tr.append(name,type,markers,nullable,def);tbody.append(tr);});table.append(tbody);wrap.append(table);body.append(wrap);
    const columns=el('div',undefined,'detail-columns');
    const relations=makeSection('直接关系与删除行为');const touching=relationships.filter(r=>fromName(r)===t.name||toName(r)===t.name);if(!touching.length)relations.append(el('p','此表没有目录声明的外键或逻辑引用。','relation-note'));
    touching.forEach(r=>{const other=otherName(r,t.name),button=el('button',undefined,'relation-button');button.type='button';button.append(el('b',`${fromName(r)===t.name?'→ 引用':'← 被引用'} ${other}`));button.append(el('span',relationText(r)));button.append(el('span',cardinality(r),isLogical(r)?'logical-label':undefined));if(!isLogical(r)){button.append(el('span',`ON DELETE ${r.on_delete || 'NO ACTION'} · ON UPDATE ${r.on_update || 'NO ACTION'}`));if(r.initially_deferred)button.append(el('span','DEFERRABLE INITIALLY DEFERRED · 事务提交时校验'));else if(r.deferrable)button.append(el('span','DEFERRABLE · 可延迟校验'));}else button.append(el('span',r.description || 'JSON 历史快照引用，不受数据库外键保证。'));button.addEventListener('click',()=>{state.query='';$('search').value='';state.domain='all';state.searchBase='all';selectTable(other);nodes.get(other)?.focus({preventScroll:true});nodes.get(other)?.scrollIntoView({block:'nearest',inline:'nearest'});});relations.append(button);});columns.append(relations);
    const constraints=el('div');
    const indices=makeSection('索引与组合唯一约束');if(!t.indices.length)indices.append(el('p','没有额外索引。','relation-note'));t.indices.forEach(i=>{const item=el('div',undefined,'constraint-item');const cols=list(i.column_details).length?i.column_details.map(c=>`${c.name}${c.descending?' DESC':' ASC'}${c.collation&&c.collation!=='BINARY'?` COLLATE ${c.collation}`:''}`).join(' + '):(i.columns||[]).map(v=>typeof v==='string'?v:v.name).join(' + ');item.append(el('b',i.name,'constraint-name'),el('p',`${i.unique?'唯一':'普通'}索引 · ${cols}${i.partial?' · 部分索引':''}${i.origin==='pk'?' · 主键自动索引':i.origin==='u'?' · 唯一约束自动索引':''}`,'relation-note'));if(i.sql)item.append(el('pre',i.sql,'constraint-expression'));indices.append(item);});constraints.append(indices);
    const checks=makeSection('CHECK 约束');if(!t.checks.length)checks.append(el('p','目录没有记录 CHECK 约束。','relation-note'));t.checks.forEach(c=>{const item=el('div',undefined,'constraint-item');item.append(el('b',c.name || 'CHECK','constraint-name'),el('pre',c.expression || display(c),'constraint-expression'));checks.append(item);});constraints.append(checks);
    const triggers=makeSection('触发器');if(!t.triggers.length)triggers.append(el('p','没有触发器。','relation-note'));t.triggers.forEach(tr=>{const item=el('div',undefined,'constraint-item');item.append(el('b',tr.name,'constraint-name'));if(tr.event)item.append(el('p',tr.event,'relation-note'));item.append(el('pre',tr.sql || display(tr),'constraint-expression'));triggers.append(item);});constraints.append(triggers);columns.append(constraints);body.append(columns);
    if(t.source_file)body.append(el('p',`定义来源：${t.source_file}`,'source-path'));
  }
  function selectTable(name) {if(!byName.has(name))return;state.selected=name;if(!state.query&&state.domain!=='all'&&byName.get(name).domain!==state.domain)state.domain='all';renderDetails();renderView();}
  function closeDetails() {state.selected=null;renderDetails();renderView();}
  function zoom(value) {state.zoom=Math.max(1,Math.min(1.4,value));$('canvas').style.transform=`scale(${state.zoom})`;$('canvas-stage').style.width=`${canvasWidth*state.zoom}px`;$('canvas-stage').style.height=`${canvasHeight*state.zoom}px`;$('zoom-value').value=`${Math.round(state.zoom*100)}%`;$('zoom-value').textContent=`${Math.round(state.zoom*100)}%`;$('zoom-out').disabled=state.zoom<=1;$('zoom-in').disabled=state.zoom>=1.4;}
  function clearSearch() {state.query='';$('search').value='';state.domain=state.searchBase;if(state.selected&&state.domain!=='all'&&byName.get(state.selected).domain!==state.domain)state.selected=null;renderDetails();renderView();}
  function reset() {Object.assign(state,{domain:'all',query:'',selected:null,related:false,showFk:true,showLogical:true,searchBase:'all'});$('search').value='';$('related-only').checked=false;$('show-fk').checked=true;$('show-logical').checked=true;renderDetails();renderView();zoom(1);$('canvas-main').scrollTo({left:0,top:0});}
  const all=el('button','全部','filter');all.type='button';all.dataset.domain='all';$('domain-filters').append(all);domains.forEach(d=>{const id=d.id||d.name,b=el('button',d.label||d.title||id,'filter');b.type='button';b.dataset.domain=id;$('domain-filters').append(b);});
  $('domain-filters').addEventListener('click',e=>{const b=e.target.closest('[data-domain]');if(!b)return;state.domain=b.dataset.domain;state.searchBase=state.domain;state.query='';$('search').value='';if(state.selected&&state.domain!=='all'&&byName.get(state.selected).domain!==state.domain)state.selected=null;renderDetails();renderView();});
  $('search').addEventListener('input',e=>{const next=e.target.value.trim();if(next&&!state.query)state.searchBase=state.domain;if(!next){clearSearch();return;}state.query=next;renderView();});$('clear-search').addEventListener('click',()=>{clearSearch();$('search').focus();});
  $('related-only').addEventListener('change',e=>{state.related=e.target.checked;renderView();});$('show-fk').addEventListener('change',e=>{state.showFk=e.target.checked;renderView();});$('show-logical').addEventListener('change',e=>{state.showLogical=e.target.checked;renderView();});
  $('close-detail').addEventListener('click',closeDetails);document.addEventListener('keydown',e=>{if(e.key==='Escape'&&state.selected){const former=state.selected;closeDetails();nodes.get(former)?.focus({preventScroll:true});}});$('reset').addEventListener('click',reset);$('zoom-out').addEventListener('click',()=>zoom(state.zoom-.2));$('zoom-in').addEventListener('click',()=>zoom(state.zoom+.2));
  const columnCount=tables.reduce((sum,t)=>sum+t.columns.length,0),fkCount=relationships.filter(r=>!isLogical(r)).length,logicalCount=relationships.filter(isLogical).length;
  [['表',tables.length],['字段',columnCount],['SQL 外键',fkCount],['逻辑引用',logicalCount]].forEach(([label,count])=>{const span=el('span');span.append(el('b',count),document.createTextNode(` ${label}`));$('schema-facts').append(span);});
  const meta=S.metadata||{},sources=list(meta.source_files).join('、')||'docs/database/schema.sql';
  $('source-line').textContent=`依据：技术方案 r51 / PRD r230 · 生成日期 ${meta.generated_date||'2026-10-12'} · 定义来源 ${sources} · 目录 docs/database/schema-catalog.json`;
  $('parity-line').textContent=`目录校验：${tables.length} 张表 / ${columnCount} 列 / ${fkCount} 组 SQL 草案外键（复合外键按一组计），逻辑快照引用 ${logicalCount} 条，分别计数。SQL 定义校验与真实业务验收分开。`;
  if(meta.schema_sha256)$('parity-line').append(document.createTextNode(` SQL SHA-256 ${meta.schema_sha256.slice(0,12)}…`));
  prepareLayout();preparePorts();buildNodes();buildEdges();renderView();zoom(1);
})();
</script>
</body></html>
'''


def validate_catalog(catalog: dict) -> dict:
    """Check semantic references before embedding any source-derived data."""
    if not isinstance(catalog.get("tables"), list) or not catalog["tables"]:
        raise ValueError("catalog.tables must be a non-empty list")
    names = {table["name"] for table in catalog["tables"]}
    if len(names) != len(catalog["tables"]):
        raise ValueError("duplicate table names in catalog")
    columns = {table["name"]: {column["name"] for column in table["columns"]} for table in catalog["tables"]}
    for table in catalog["tables"]:
        for field in ("foreign_keys", "indices", "checks", "triggers"):
            if field not in table:
                raise ValueError(f"{table['name']} is missing {field}")
        if table.get("row_count") is not None:
            raise ValueError("the explorer must not embed business record counts")
        if len(columns[table["name"]]) != len(table["columns"]):
            raise ValueError(f"duplicate columns in {table['name']}")
    for relation in catalog.get("relationships", []):
        if relation.get("kind") != "sql_fk":
            raise ValueError("catalog.relationships may only contain sql_fk entries")
        source, target = relation["from_table"], relation["to_table"]
        if source not in names or target not in names:
            raise ValueError(f"unknown table in FK {relation['id']}")
        if len(relation["from_columns"]) != len(relation["to_columns"]):
            raise ValueError(f"mismatched composite FK {relation['id']}")
        for name in relation["from_columns"]:
            if name not in columns[source]:
                raise ValueError(f"unknown source column {source}.{name}")
        for name in relation["to_columns"]:
            if name not in columns[target]:
                raise ValueError(f"unknown target column {target}.{name}")
    for relation in catalog.get("logical_relationships", []):
        if relation.get("kind") != "logical_snapshot":
            raise ValueError("logical_relationships must be marked logical_snapshot")
        if relation["from_table"] not in names or relation["to_table"] not in names:
            raise ValueError("unknown table in a logical relationship")
    actual_fk = sum(len(table["foreign_keys"]) for table in catalog["tables"])
    if actual_fk != len(catalog.get("relationships", [])):
        raise ValueError("table and global FK counts disagree")
    table_fks = {fk["id"]: fk for table in catalog["tables"] for fk in table["foreign_keys"]}
    global_fks = {fk["id"]: fk for fk in catalog.get("relationships", [])}
    if table_fks != global_fks or len(global_fks) != actual_fk:
        raise ValueError("table and global FK definitions disagree")
    totals = {
        "tables": len(names),
        "columns": sum(len(table["columns"]) for table in catalog["tables"]),
        "foreign_keys": actual_fk,
        "logical_relationships": len(catalog.get("logical_relationships", [])),
        "indices": sum(len(table["indices"]) for table in catalog["tables"]),
        "checks": sum(len(table["checks"]) for table in catalog["tables"]),
        "triggers": sum(len(table["triggers"]) for table in catalog["tables"]),
    }
    for name, count in totals.items():
        if catalog.get("totals", {}).get(name) != count:
            raise ValueError(f"catalog.totals.{name} does not match the definitions")
    sources = list(catalog.get("metadata", {}).get("source_files", []))
    sources.extend(table.get("source_file", "") for table in catalog["tables"])
    for source in sources:
        if Path(source).is_absolute() or ".." in Path(source).parts:
            raise ValueError("schema source paths must be repository-relative")
    return totals


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=root / "docs/database/schema-catalog.json")
    parser.add_argument("--output", type=Path, default=root / "docs/database/schema-canvas.html")
    args = parser.parse_args()
    catalog_path, output_path = args.catalog.resolve(), args.output.resolve()
    catalog_relative, output_relative = catalog_path.relative_to(root), output_path.relative_to(root)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    source_sql = root / "docs/database/schema.sql"
    declared_sha = catalog.get("metadata", {}).get("schema_sha256")
    if not declared_sha or hashlib.sha256(source_sql.read_bytes()).hexdigest() != declared_sha:
        raise ValueError("catalog SQL hash is missing or stale; regenerate schema-catalog.json first")
    totals = validate_catalog(catalog)
    data = json.dumps(catalog, ensure_ascii=False, separators=(",", ":"))
    for char, escaped in (("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e")):
        data = data.replace(char, escaped)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(TEMPLATE.replace("__SCHEMA_JSON__", data), encoding="utf-8")
    print(json.dumps({"output": output_relative.as_posix(), "source": catalog_relative.as_posix(), "validation": totals, "offline": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()

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
<title>ParTrace Vision · 最小数据库设计</title>
<style>
:root{--page:#f4f7fb;--card:#fff;--ink:#102a43;--muted:#596c80;--faint:#8794a4;--line:#d8e1eb;--accent:#006ddd;--accent-soft:#e8f2ff;--radius:11px;--mono:ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;--sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
*{box-sizing:border-box}body{margin:0;background:var(--page);color:var(--ink);font:14px/1.5 var(--sans)}button,input{font:inherit}button{cursor:pointer;color:inherit;touch-action:manipulation}button:disabled{cursor:default;opacity:.45}button:focus-visible,input:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid var(--accent);outline-offset:3px}a{color:var(--accent)}h1,h2,h3,p{margin-top:0}[hidden]{display:none!important}.page{max-width:1600px;margin:auto;padding:22px 28px}.skip{position:absolute;top:-100px;left:24px;z-index:20;background:#fff;padding:12px}.skip:focus{top:12px}.back-nav{margin:0 0 12px;font-size:12px}.back-nav a{text-decoration:none}.back-nav a:hover{text-decoration:underline}.header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:16px}h1{font-size:24px;line-height:1.25;letter-spacing:-.6px;margin:0 0 6px}.intro{font-size:13px;color:var(--muted);margin:0}.draft{flex:none;border:1px solid #c4d5e9;border-radius:7px;padding:5px 9px;font-size:11px;color:var(--accent);background:var(--accent-soft)}.surface{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden}.toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:12px 14px;border-bottom:1px solid var(--line)}.search-wrap{flex:1 1 220px;max-width:310px}.search-wrap>label{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}.search-box{display:flex;border:1px solid #c5d1df;border-radius:8px;background:#fff}.search-box input{border:0;min-width:0;flex:1;padding:8px 10px;background:transparent;font-size:12px}.clear{border:0;background:transparent;padding:0 10px;color:var(--muted);font-size:15px}.filters{display:flex;gap:5px;flex-wrap:wrap}.filter{border:1px solid var(--line);border-radius:8px;background:#fff;padding:7px 9px;font-size:12px}.filter[aria-pressed="true"]{color:var(--accent);border-color:#82b3ec;background:var(--accent-soft);font-weight:600}.display-options{display:flex;gap:8px;align-items:center;font-size:12px;color:var(--muted)}.display-options label{display:flex;gap:5px;align-items:center}.display-options input{accent-color:var(--accent)}.reset{border:0;border-radius:7px;background:transparent;padding:7px 6px;font-size:12px;color:var(--muted)}.reset:hover{background:#f2f6fa}.display-disclosure{position:relative}.display-disclosure>summary{list-style:none;cursor:pointer;padding:7px 9px;border:1px solid var(--line);border-radius:8px;background:#fff;font-size:12px}.display-disclosure>summary::-webkit-details-marker{display:none}.display-disclosure>summary:after{content:"⌄";margin-left:7px;color:var(--faint)}.display-menu{position:absolute;top:40px;right:0;width:235px;padding:13px;border:1px solid var(--line);border-radius:9px;background:#fff;box-shadow:0 8px 25px #17385e12;z-index:10}.display-menu label{margin-bottom:9px}.display-menu p{margin:7px 0 0;font-size:11px;color:var(--muted);line-height:1.7}.line-key{display:inline-block;width:19px;border-top:1px solid #9baabd;vertical-align:middle;margin-right:5px}.line-key.logical{border-top:1px dashed var(--accent)}.status-bar{padding:7px 14px;border-bottom:1px solid var(--line);font-size:11px;color:var(--faint)}.status-bar p{margin:0}.search-results{padding:10px 14px;background:#f8fbff;border-bottom:1px solid var(--line)}.search-results p{font-size:12px;color:var(--muted);margin:0 0 8px}.result-list{display:flex;gap:6px;flex-wrap:wrap}.result{display:flex;gap:7px;align-items:center;border:1px solid #c8d9ef;background:#fff;border-radius:7px;padding:6px 9px;text-align:left;font-size:12px}.result code{font:12px var(--mono)}.result small{color:var(--muted);max-width:250px;overflow-wrap:anywhere}.explorer-grid{display:grid;grid-template-columns:minmax(0,1fr) 390px;height:min(800px,calc(100vh - 232px));min-height:500px}.diagram-area{min-width:0;min-height:0;display:flex;flex-direction:column}.diagram-scroll{flex:1;overflow:auto;min-height:0;position:relative;overscroll-behavior-x:contain;scrollbar-gutter:stable;background:#f8fafd}.canvas-stage{margin:12px auto;position:relative}.canvas{position:absolute;left:0;top:0;transform-origin:0 0}.domain-region{position:absolute;border:1px solid #e0e7ef;border-radius:10px;background:#f0f4f8;pointer-events:none}.domain-head{position:absolute;left:16px;top:12px;display:flex;gap:9px;align-items:center;font-size:11px;color:var(--faint)}.domain-head b{font-size:12px;color:var(--muted);font-weight:600}.edges{position:absolute;left:0;top:0;overflow:visible;pointer-events:none}.edge{stroke:#92a4b9;stroke-width:1;fill:none;opacity:.35;pointer-events:stroke;cursor:pointer;transition:opacity .15s}.edge.logical{stroke:var(--accent);stroke-dasharray:4 5}.edge.active{stroke:var(--accent);stroke-width:2.2;opacity:1}.edge.dim{opacity:.06}.edge.selected-edge{stroke-width:3}.edge:focus-visible{stroke:var(--accent);stroke-width:3;opacity:1;outline:2px solid var(--accent);outline-offset:4px}.edge-label{font:10px var(--mono);fill:var(--muted);paint-order:stroke;stroke:#f8fafd;stroke-width:5px;stroke-linejoin:round;pointer-events:none}.edge-label.logical{fill:var(--accent);font-family:var(--sans)}.table-node{position:absolute;display:block;border:1px solid #c8d5e4;border-radius:8px;background:#fff;padding:9px 10px;text-align:left;box-shadow:0 2px 5px #17385e05;transition:border-color .15s,opacity .15s}.table-node:hover{border-color:var(--accent)}.table-node.selected{border:2px solid var(--accent);padding:8px 9px;background:#eef6ff;box-shadow:0 0 0 3px #006ddd0b}.table-node.selected:before{content:"已选";position:absolute;top:-10px;right:9px;border-radius:3px;background:var(--accent);color:#fff;padding:1px 5px;font-size:9px}.table-node.dim{opacity:.23}.table-node.match{border-color:#6ea4e2}.table-name{display:block;font:600 12px/1.3 var(--mono);overflow-wrap:anywhere;color:var(--ink)}.table-label{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:11px;color:var(--muted);margin-top:5px;line-height:1.3}.empty{padding:22px;color:var(--muted);font-size:12px;text-align:center}.zoom-tools{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:8px 14px;border-top:1px solid var(--line);flex:none;font-size:11px;color:var(--muted)}.zoom-tools p{margin:0}.zoom-buttons{display:flex;gap:4px;align-items:center}.zoom-buttons button{border:1px solid var(--line);border-radius:6px;background:#fff;min-width:28px;height:28px;font-size:15px}.zoom-buttons .fit-width{font-size:11px;width:auto;padding:0 8px;margin-right:4px}.zoom-buttons output{padding:0 5px;min-width:46px;text-align:center}.detail-panel{min-width:0;min-height:0;border-left:1px solid var(--line);background:#fff;overflow:hidden}.detail-empty{height:100%;display:flex;flex-direction:column;justify-content:center;padding:24px;text-align:center}.detail-empty h2{font-size:15px;color:var(--ink);margin-bottom:7px}.detail-empty p{font-size:12px;color:var(--muted);margin:0;line-height:1.7}.detail{height:100%;display:flex;flex-direction:column}.detail-header{display:flex;justify-content:space-between;gap:10px;align-items:flex-start;padding:16px 16px 12px;border-bottom:1px solid var(--line);flex:none}.detail-header h2{font:600 17px/1.3 var(--mono);margin:0 0 5px;overflow-wrap:anywhere}.detail-header p{font-size:12px;color:var(--muted);margin:0}.close{border:1px solid var(--line);background:#fff;border-radius:6px;padding:4px 7px;flex:none;font-size:11px}.detail-body{padding:12px 16px 16px;min-height:0;flex:1;overflow:auto;overscroll-behavior:contain}.table-wrap{overflow:auto;margin-bottom:12px}table{border-collapse:collapse;width:100%;font-size:12px;text-align:left}caption{text-align:left;font-size:12px;font-weight:600;margin-bottom:7px}th,td{padding:8px 6px;border-bottom:1px solid #e8eef5;vertical-align:top}thead{background:#f6f8fb}th{font-weight:500;color:var(--muted)}td code,th code{font:12px var(--mono);white-space:normal;overflow-wrap:anywhere}.compact-fields{table-layout:fixed;min-width:0}.compact-fields th:first-child{width:50%}.compact-fields th:nth-child(2){width:23%}.compact-fields th:nth-child(3){width:27%}.compact-fields td{padding:8px 5px}.compact-fields td:first-child{padding-left:0}.compact-fields th:first-child{padding-left:0}.complete-fields{min-width:520px;table-layout:fixed}.complete-fields th:first-child{width:180px}.complete-fields th:nth-child(2){width:80px}.complete-fields th:nth-child(3){width:92px}.complete-fields th:nth-child(4){width:60px}.complete-fields th:nth-child(5){width:108px}.complete-fields td:nth-child(3){overflow-wrap:anywhere}.field-key{display:inline-block;padding:1px 4px;background:var(--accent-soft);color:var(--accent);border-radius:3px;margin:0 3px 3px 0;font:10px/1.4 var(--mono)}.nullable{display:block;font-size:10px;color:var(--faint);white-space:nowrap}.marker-legend{font-size:10px;color:var(--faint);margin:0 0 17px;line-height:1.7}.default-value{white-space:pre-wrap;word-break:break-word;font:12px var(--mono)}.detail-section{margin:16px 0;min-width:0}.detail-section h3{font-size:12px;font-weight:600;line-height:1.4;margin-bottom:9px}.relation-button{display:block;width:100%;text-align:left;margin-bottom:6px;padding:8px 9px;border-radius:7px;border:0;background:#f4f7fb;font-size:12px}.relation-button:hover{background:var(--accent-soft)}.relation-button b{font:500 12px var(--mono);color:var(--ink);overflow-wrap:anywhere}.relation-button span{display:block;color:var(--muted);margin-top:3px;font-size:11px;overflow-wrap:anywhere}.relation-note{font-size:11px;color:var(--muted);margin:0 0 10px}.inspection{margin-top:9px;border-top:1px solid #e6edf5}.inspection>summary{cursor:pointer;padding:11px 0;font-size:12px;color:var(--muted)}.inspection>summary small{font-size:10px;color:var(--faint);margin-left:6px}.fold-content{padding:3px 0 10px}.fold-content>p{font-size:12px;color:var(--muted);line-height:1.7}.constraint-item{padding-bottom:9px;border-bottom:1px solid #edf1f6;margin-bottom:9px}.constraint-name{font:11px var(--mono);color:var(--ink);overflow-wrap:anywhere}.constraint-expression{font:11px/1.55 var(--mono);overflow-wrap:anywhere;white-space:pre-wrap;color:var(--muted);margin:4px 0 0}.full-relation{padding:8px 0;border-bottom:1px solid #edf1f6}.full-relation p{font-size:11px;color:var(--muted);margin:3px 0;overflow-wrap:anywhere}.full-relation b{font:11px var(--mono);overflow-wrap:anywhere}.logical-label{color:var(--accent)!important}.footer{margin-top:10px;color:var(--muted);font-size:11px}.source-info>summary{cursor:pointer;color:var(--faint);font-size:11px}.source-content{padding:11px 0 0;line-height:1.75}.source-content p{margin:0 0 5px}.facts{display:flex;gap:12px;flex-wrap:wrap}.facts b{font-weight:600;color:var(--ink)}.source-path{font:11px var(--mono);overflow-wrap:anywhere}.embedded .page{max-width:none;padding:0}.embedded .back-nav,.embedded .header,.embedded .skip{display:none}.embedded .surface{border-radius:0}.embedded .explorer-grid{height:calc(100vh - 122px);min-height:480px}.embedded .footer{padding:0 14px 9px}
.domain-head{left:12px;right:12px;top:12px;display:block}.domain-title{display:flex;align-items:center;justify-content:space-between;gap:6px}.domain-title>span{flex:none;font-size:10px}.domain-description{display:block;margin-top:5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:10px;color:var(--faint)}.table-name{font:600 12px/1.35 var(--sans)}.table-label{display:flex;gap:4px;align-items:flex-start;white-space:normal;overflow:visible;text-overflow:clip}.table-identifier{min-width:0;flex:1;font:11px/1.4 var(--mono);overflow-wrap:anywhere}.table-count{flex:none;white-space:nowrap;font-size:10px;line-height:1.5;color:var(--faint)}
@media(max-width:1050px){.page{padding:20px}.canvas-stage{margin-left:12px;margin-right:12px}}
/* The graph uses the full width until a table is selected. */
.explorer-grid{grid-template-columns:minmax(0,1fr)}
@media(min-width:481px){.surface.has-selection .explorer-grid{grid-template-columns:minmax(0,1fr) clamp(280px,34vw,390px)}}
@media(min-width:481px) and (max-width:980px){.surface.has-selection .explorer-grid{grid-template-columns:minmax(0,1fr) clamp(280px,40vw,340px)}}
.detail-header>div{min-width:0;flex:1}
.compact-fields th:first-child{width:45%}.compact-fields th:nth-child(2){width:30%}.compact-fields th:nth-child(3){width:25%}
.filter,.reset,.clear,.search-box input,.display-disclosure>summary,.close{min-height:44px}
.filter,.reset,.close,.display-disclosure>summary{white-space:nowrap}
.clear{min-width:44px}.close{min-width:62px;padding:4px 8px}
.zoom-buttons button{min-width:44px;height:44px}.zoom-buttons .fit-width{min-width:78px}
.relation-button{min-height:44px}.inspection>summary{min-height:44px}
.embedded .page{display:flex;flex-direction:column;height:100vh;height:100dvh;min-height:0}
.embedded .surface{display:flex;flex-direction:column;flex:1 1 auto;min-height:420px}
.embedded .toolbar-scroll,.embedded .status-bar,.embedded .search-results,.embedded .footer{flex:none}
.embedded .explorer-grid{flex:1 1 auto;height:auto;min-height:0}
/* finesse · component: schema-toolbar · register=product · tokens: inherited */
.toolbar-scroll{width:100%;min-width:0;overflow-x:auto;border-bottom:1px solid var(--line);overscroll-behavior-x:contain;scrollbar-width:thin;scrollbar-color:var(--line) transparent}
.toolbar-scroll:focus-visible{outline:3px solid var(--accent);outline-offset:-3px}
.toolbar-scroll::-webkit-scrollbar{height:6px}.toolbar-scroll::-webkit-scrollbar-thumb{background:var(--line);border-radius:3px}
.toolbar{display:flex;flex-wrap:nowrap;align-items:center;width:max-content;min-width:100%;gap:8px;padding:10px 12px;border-bottom:0}
.search-wrap{flex:0 0 clamp(180px,18vw,220px);width:clamp(180px,18vw,220px);max-width:none;min-width:0}
.search-box{height:44px;border:0;box-shadow:inset 0 0 0 1px var(--line)}
.search-box:focus-within{box-shadow:inset 0 0 0 1px var(--accent)}
.search-box input,.clear{height:44px}
.filters{display:flex;flex:0 0 auto;flex-wrap:nowrap;gap:5px;min-width:0;max-width:none;overflow:visible;padding:0}
.filter{flex:none;height:44px}
.display-options{flex:0 0 auto;margin-left:auto;gap:8px;white-space:nowrap;font-size:12px}
.display-options>.related{min-height:44px}.display-disclosure>summary,.reset{height:44px}
.display-disclosure>summary{display:flex;align-items:center;justify-content:center}
.display-menu{position:fixed;inset:auto;z-index:20;width:235px;max-width:calc(100vw - 24px);max-height:calc(100dvh - 24px);overflow:auto}
.display-menu label{display:flex;align-items:center;gap:6px;min-height:44px;margin:0;font-size:12px}
.display-menu input{accent-color:var(--accent)}
@media(max-width:980px){
  .detail-header,.detail-body{padding:12px}
  .detail-header{gap:8px}.detail-header h2{font-size:16px}
  .compact-fields th:first-child{width:45%}.compact-fields th:nth-child(2){width:30%}.compact-fields th:nth-child(3){width:25%}
  .zoom-tools{padding:8px;gap:6px;flex-wrap:wrap}
  .zoom-tools p{font-size:10px}.zoom-buttons{gap:3px;max-width:100%;flex-wrap:wrap}
  .zoom-buttons output{min-width:38px;padding:0 2px}
}
@media(max-width:700px){
  .page{padding:16px 12px}.header{gap:12px}h1{font-size:21px}.draft{font-size:10px}.intro{font-size:12px}
  .status-bar{padding:7px 10px}
}
@media(max-width:480px){
  .explorer-grid,.embedded .explorer-grid{grid-template-columns:minmax(0,1fr);height:auto;min-height:0;flex:none}
  .diagram-area,.embedded .diagram-area{height:480px;min-height:0}
  .detail-panel{border-left:0;border-top:1px solid var(--line)}
  .detail-empty{height:auto;min-height:90px;padding:18px}
  .detail,.embedded .detail{height:540px}
  .embedded .page{display:block;height:auto;min-height:0}.embedded .surface{display:block;min-height:0}
}
@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition:none!important}}
@media print{@page{size:A3 landscape;margin:12mm}body{background:#fff}.page,.embedded .page{padding:0;max-width:none}.skip,.back-nav,.toolbar,.search-results,.zoom-tools,.close,.detail-empty{display:none!important}.header,.embedded .header{display:flex}.surface{border-color:#bac7d6;break-inside:auto}.explorer-grid,.embedded .explorer-grid{display:block;height:auto;min-height:0}.diagram-area,.embedded .diagram-area{height:auto}.diagram-scroll{overflow:visible;scrollbar-gutter:auto;background:#fff}.canvas-stage{margin:0;width:var(--canvas-width,1240px)!important;height:var(--canvas-height,800px)!important;zoom:.85}.canvas{transform:none!important}.edge.dim,.table-node.dim{opacity:1}.domain-region{background:#fff}.table-node{box-shadow:none}.detail-panel{border-left:0;border-top:1px solid #bac7d6;overflow:visible}.detail,.embedded .detail{height:auto;display:block}.detail-body{max-height:none;overflow:visible}.table-wrap{overflow:visible}.complete-fields{min-width:0}.embedded .footer{padding:0}.inspection{break-inside:auto}.constraint-item{break-inside:avoid}}
@media print{.page,.embedded .page{display:block;height:auto;min-height:0}.surface,.embedded .surface{display:block;min-height:0}}
@media print{.toolbar-scroll,.display-menu{display:none!important}}
</style>
</head>
<body>
<a class="skip" href="#canvas-main">跳到数据关系图</a>
<main class="page">
<nav class="back-nav" aria-label="返回项目工作台"><a href="../workbench/index.html#database">← 返回工作台</a></nav>
<header class="header"><div><h1>最小数据库设计</h1><p class="intro">按数据模块查看表与引用，点击表检查字段。</p></div><span class="draft">首版草案 · 未部署</span></header>
<section class="surface" id="schema-surface" aria-label="数据库结构交互图">
<div class="toolbar-scroll" id="toolbar-scroll" tabindex="0" role="region" aria-label="数据库工具栏，可左右滚动"><div class="toolbar"><div class="search-wrap"><label for="search">搜索表或字段</label><div class="search-box"><input id="search" type="search" placeholder="搜索表名或字段…" autocomplete="off" spellcheck="false"><button id="clear-search" class="clear" aria-label="清空搜索" title="清空搜索">×</button></div></div><nav id="domain-filters" class="filters" aria-label="数据域筛选"></nav><button type="button" id="open-embeddings" class="filter" hidden>查看事件向量 →</button><button type="button" id="open-agent" class="filter" hidden>查看 AI 会话 →</button><div class="display-options"><label class="related"><input type="checkbox" id="related-only">只看关联</label><details id="display-options-control" class="display-disclosure"><summary id="display-options-trigger" aria-controls="display-options-menu">显示选项</summary><div id="display-options-menu" class="display-menu" role="group" aria-label="显示选项" hidden><label><input type="checkbox" id="show-fk" checked>SQL 草案外键</label><label><input type="checkbox" id="show-logical">JSON 逻辑引用</label><p><i class="line-key" aria-hidden="true"></i>实线：引用表 → 被引用表<br><i class="line-key logical" aria-hidden="true"></i>虚线：历史快照引用，非数据库外键</p></div></details><button id="reset" class="reset">复位</button></div></div></div>
<div id="search-results" class="search-results" hidden><p id="search-explanation"></p><div id="result-list" class="result-list"></div></div>
<div class="status-bar"><p id="view-status" role="status" aria-live="polite"></p></div>
<div class="explorer-grid"><div class="diagram-area">
<div id="canvas-main" class="diagram-scroll" tabindex="0" aria-label="可横向滚动的关系图"><div id="canvas-stage" class="canvas-stage"><div id="canvas" class="canvas"><svg id="edges" class="edges" role="group" aria-label="表之间可点选的外键与逻辑引用"><defs><marker id="fk-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 1 L 9 5 L 0 9" fill="none" stroke="#9aaac0" stroke-width="1.5"/></marker><marker id="active-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 1 L 9 5 L 0 9" fill="none" stroke="#006ddd" stroke-width="1.6"/></marker></defs></svg></div></div></div>
<div id="no-view" class="empty" hidden></div><div class="zoom-tools"><p id="selection-hint">选择表查看字段</p><div class="zoom-buttons"><button id="fit-width" class="fit-width">适配宽度</button><button id="zoom-out" aria-label="缩小关系图">−</button><output id="zoom-value" aria-label="图形缩放">100%</output><button id="zoom-in" aria-label="放大关系图">+</button></div></div>
</div><aside id="detail-panel" class="detail-panel" aria-label="数据表定义检视器" hidden><div id="detail-empty" class="detail-empty"><h2>选择一张表</h2><p>在这里查看字段和直接关系。</p></div><section id="detail" class="detail" aria-label="所选表的完整定义" hidden><header class="detail-header"><div><h2 id="detail-title"></h2><p id="detail-description"></p></div><button id="close-detail" class="close">关闭 · Esc</button></header><div id="detail-body" class="detail-body"></div></section></aside></div>
</section>
<footer class="footer"><details class="source-info"><summary>还需要哪些表？</summary><div class="source-content"><p>当前 12 表覆盖视频核查、People 原片任务、AI 历史和包裹入出库设计。以下是后续候选，未加入当前 SQL。</p><table><thead><tr><th>后续功能</th><th>候选表</th><th>何时需要</th></tr></thead><tbody><tr><td>设备与区域管理</td><td><code>cameras / zones</code></td><td>后台维护摄像头、区域或 ROI；区域配置需保留历史版本。</td></tr><tr><td>多事件一起留档</td><td><code>review_record_items</code></td><td>一条核查保存多个事件；同时调整现有单事件结构与接口。</td></tr><tr><td>逐帧轨迹</td><td><code>tracks / track_points</code></td><td>保存跟踪器输出的逐帧观测；当前 track_key 与关键帧足以展示离散线索。</td></tr><tr><td>工具执行回放</td><td><code>run_steps</code></td><td>保存每次工具调用的状态与结果；仅显示实时进度时可先不加。</td></tr></tbody></table><p>首页与活动简报从现有数据聚合，无需摘要表；已导入数据不能代表全站采集覆盖。</p><p>下一轮先评审按会话查询运行历史的接口，复用 runs 找回失败或停止的分析，无需新表。</p><p><a href="../api/api-canvas.html" data-offline-document="api">查看 API 设计 →</a></p></div></details><details class="source-info"><summary>设计范围与来源</summary><div class="source-content"><p class="facts" id="schema-facts"></p><p>首版保存视频事件、文字向量、AI 历史、包裹与入出库记录。二维码编码 parcel_no；扫码先查询，管理员确认后才追加流水。AI 表为 conversations / messages / runs；人工核查按单事件保存。</p><p id="source-line"></p><p id="parity-line"></p><p id="api-source-line"></p><p>已选 PostgreSQL + pgvector，同库分表。embedding 使用原生 vector；先做限定范围的精确检索。模型与维度待定，尚未部署业务数据库或 ANN 索引。</p></div></details></footer>
</main>
<script id="schema-data" type="application/json">__SCHEMA_JSON__</script>
<script>
(() => {
  'use strict';
  if (new URLSearchParams(globalThis.location?.search || '').get('embed') === '1') document.documentElement.classList.add('embedded');
  const S = JSON.parse(document.getElementById('schema-data').textContent);
  const $ = id => document.getElementById(id);
  const displayControl=$('display-options-control'),displayMenu=$('display-options-menu'),displayTrigger=$('display-options-trigger');
  const NS = 'http://www.w3.org/2000/svg';
  const tables = S.tables;
  const byName = new Map(tables.map(t => [t.name, t]));
  const domains = Array.isArray(S.domains) ? S.domains : Object.entries(S.domains).map(([id, d]) => typeof d === 'string' ? {id, label:d} : {id, ...d});
  const withChat = ['conversations','messages','runs'].every(name => byName.has(name));
  const minimal = tables.length === (withChat ? 9 : 6) && domains.length === 1 && ['users','recordings','events','evidence','event_embeddings','review_records'].every(name => byName.has(name));
  const domainById = new Map(domains.map(d => [d.id || d.name, d]));
  const relationships = [...(S.relationships || []).map(r => ({...r, kind:'sql_fk'})), ...(S.logical_relationships || []).map(r => ({...r, kind:'logical_snapshot'}))];
  const positions = new Map();
  const nodes = new Map();
  const edgeObjects = [];
  const edgePorts = new Map();
  const usedSegments = new Map();
  const domainRegions = [];
  const state = {domain:'all',query:'',selected:null,related:false,showFk:true,showLogical:false,zoom:1,userZoom:1,searchBase:'all'};
  let canvasHeight = 0;
  let canvasWidth = 1240;
  let layoutKey = '', layoutFrame = 0;

  function closeDisplayMenu(returnFocus=false) {
    displayControl.open=false;displayMenu.hidden=true;
    if(returnFocus)displayTrigger.focus({preventScroll:true});
  }
  function positionDisplayMenu() {
    if(!displayControl.open||displayControl.hidden)return;
    const r=displayTrigger.getBoundingClientRect(),host=$('toolbar-scroll').getBoundingClientRect();
    if(r.width<=0||r.right<=host.left||r.left>=host.right||r.bottom<=0||r.top>=window.innerHeight){closeDisplayMenu();return;}
    const width=displayMenu.offsetWidth,height=displayMenu.offsetHeight,gap=6,inset=12;
    const left=Math.max(inset,Math.min(r.right-width,window.innerWidth-width-inset));
    const top=r.bottom+gap+height<=window.innerHeight-inset?r.bottom+gap:Math.max(inset,r.top-height-gap);
    displayMenu.style.left=`${left}px`;displayMenu.style.top=`${top}px`;
  }
  function setupDisplayMenu() {
    document.body.append(displayMenu);
    displayControl.addEventListener('toggle',()=>{
      displayMenu.hidden=!displayControl.open;
      if(displayControl.open){positionDisplayMenu();if(displayControl.open)$('show-fk').focus({preventScroll:true});}
    });
    document.addEventListener('pointerdown',event=>{
      if(displayControl.open&&!displayControl.contains(event.target)&&!displayMenu.contains(event.target))closeDisplayMenu();
    });
    displayMenu.addEventListener('keydown',event=>{
      if(event.key!=='Tab')return;
      if(event.shiftKey&&event.target===$('show-fk')){event.preventDefault();closeDisplayMenu(true);}
      else if(!event.shiftKey&&event.target===$('show-logical')){event.preventDefault();closeDisplayMenu();$('reset').focus({preventScroll:true});}
    });
    $('toolbar-scroll').addEventListener('scroll',()=>closeDisplayMenu(),{passive:true});
    window.addEventListener('resize',positionDisplayMenu);
    window.addEventListener('scroll',positionDisplayMenu,{passive:true});
  }

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
    if (state.query) return new Set(tables.map(t => t.name));
    if (state.related && state.selected) return relatedSet();
    return new Set(tables.filter(t => state.domain === 'all' || t.domain === state.domain).map(t => t.name));
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
  function diagramWidths() {
    const viewport=$('canvas-main'),grid=viewport.closest('.explorer-grid');
    const gutter=Math.max(0,viewport.offsetWidth-viewport.clientWidth);
    const full=Math.max(1,(grid?.clientWidth || viewport.clientWidth || canvasWidth+24)-gutter-24);
    const current=Math.max(1,(viewport.clientWidth || full+24)-24);
    return {full,current};
  }
  function measureLayout() {
    // The field panel changes the viewport scale, never the table arrangement.
    const available = Math.max(280, Math.floor(diagramWidths().full));
    const visible = visibleNames(), visibleKey = tables.filter(t=>visible.has(t.name)).map(t=>t.name).join(',');
    if(minimal && visible.size===tables.length){const width=Math.min(withChat?960:660,available),columns=withChat&&width>=740?3:width>=460?2:1,branch=columns>1,nodeWidth=branch?Math.min(210,Math.floor((width-32-(columns-1)*44)/columns)):Math.min(220,width-64);return {minimal:true,withChat,width,columns,branch,nodeWidth,key:`minimal:${width}:${columns}:${nodeWidth}:${visibleKey}`};}
    const modules = domains.map(d=>({id:d.id || d.name,tables:tables.filter(t=>t.domain===(d.id || d.name)&&visible.has(t.name))})).filter(d=>d.tables.length);
    const domainColumns = Math.min(modules.length || 1, available>=880?4:available>=480?2:1);
    const regionWidth = Math.min(domainColumns===1?320:280, Math.floor((available-20-(domainColumns-1)*20)/domainColumns));
    const nodeWidth = Math.min(260,regionWidth-24);
    return {modules,domainColumns,regionWidth,nodeWidth,key:`modules:${domainColumns}:${regionWidth}:${visibleKey}`};
  }
  function prepareLayout(plan = measureLayout()) {
    layoutKey = plan.key;
    if(plan.minimal&&plan.withChat){
      canvasWidth=plan.width;canvasHeight=plan.columns===3?438:plan.columns===2?696:1224;
      const groups=plan.columns===3?[['recordings','events','evidence'],['conversations','messages','runs'],['users','review_records','event_embeddings']]:plan.columns===2?[['recordings','events','evidence','event_embeddings','users'],['conversations','messages','runs','review_records']]:[['recordings','events','evidence','event_embeddings','users','conversations','messages','runs','review_records']];
      groups.forEach((names,column)=>names.forEach((name,row)=>positions.set(name,{x:plan.columns===1?(canvasWidth-plan.nodeWidth)/2:24+column*(canvasWidth-plan.nodeWidth-48)/(plan.columns-1),y:38+row*132,w:plan.nodeWidth,h:72})));
      $('canvas').style.width=`${canvasWidth}px`;$('canvas').style.height=`${canvasHeight}px`;
      $('canvas-stage').style.setProperty('--canvas-width',`${canvasWidth}px`);$('canvas-stage').style.setProperty('--canvas-height',`${canvasHeight}px`);
      $('edges').setAttribute('width',canvasWidth);$('edges').setAttribute('height',canvasHeight);$('edges').setAttribute('viewBox',`0 0 ${canvasWidth} ${canvasHeight}`);
      return;
    }
    if(plan.minimal){
      canvasWidth=plan.width;canvasHeight=plan.branch?438:828;
      const w=plan.nodeWidth,center=(canvasWidth-w)/2;
      positions.set('recordings',{x:plan.branch?24:center,y:38,w,h:72});
      positions.set('events',{x:plan.branch?24:center,y:170,w,h:72});
      positions.set('evidence',{x:plan.branch?24:center,y:302,w,h:72});
      positions.set('event_embeddings',{x:plan.branch?canvasWidth-w-24:center,y:plan.branch?302:434,w,h:72});
      positions.set('users',{x:plan.branch?canvasWidth-w-24:center,y:plan.branch?38:566,w,h:72});
      positions.set('review_records',{x:plan.branch?canvasWidth-w-24:center,y:plan.branch?170:698,w,h:72});
      $('canvas').style.width=`${canvasWidth}px`;$('canvas').style.height=`${canvasHeight}px`;
      $('canvas-stage').style.setProperty('--canvas-width',`${canvasWidth}px`);$('canvas-stage').style.setProperty('--canvas-height',`${canvasHeight}px`);
      $('edges').setAttribute('width',canvasWidth);$('edges').setAttribute('height',canvasHeight);$('edges').setAttribute('viewBox',`0 0 ${canvasWidth} ${canvasHeight}`);
      return;
    }
    const {modules,domainColumns,regionWidth,nodeWidth} = plan;
    canvasWidth = regionWidth * domainColumns + 20 + (domainColumns - 1) * 20;
    const columnTops = Array(domainColumns).fill(10);
    modules.forEach(({id,tables:ts},index)=>{
      const column=index%domainColumns,x=10+column*(regionWidth+20),y=columnTops[column],h=64+ts.length*72+(ts.length-1)*28+14;
      const region = el('div',undefined,'domain-region'); Object.assign(region.style,{left:`${x}px`,top:`${y}px`,width:`${regionWidth}px`,height:`${h}px`});
      const heading=el('div',undefined,'domain-head'),title=el('div',undefined,'domain-title');title.append(el('b',domainLabel(id)),el('span',`${ts.length} 表`));heading.append(title);
      const description=domainById.get(id)?.description;if(description)heading.append(el('span',description,'domain-description'));
      region.append(heading); $('canvas').insertBefore(region,$('edges')); domainRegions.push(region);
      ts.forEach((t,i)=>positions.set(t.name,{x:x+(regionWidth-nodeWidth)/2,y:y+64+i*100,w:nodeWidth,h:72}));
      columnTops[column]=y+h+24;
    });
    canvasHeight = Math.max(160,...columnTops)-14;
    $('canvas').style.width = `${canvasWidth}px`; $('canvas').style.height = `${canvasHeight}px`;
    $('canvas-stage').style.setProperty('--canvas-width',`${canvasWidth}px`);
    $('canvas-stage').style.setProperty('--canvas-height',`${canvasHeight}px`);
    $('edges').setAttribute('width',canvasWidth); $('edges').setAttribute('height',canvasHeight); $('edges').setAttribute('viewBox',`0 0 ${canvasWidth} ${canvasHeight}`);
  }
  function buildNodes() {
    tables.forEach(t=>{
      const p=positions.get(t.name);if(!p)return;const b=el('button',undefined,'table-node'); b.type='button'; b.dataset.table=t.name; b.setAttribute('aria-pressed','false'); b.setAttribute('aria-label',`查看 ${t.name}，${t.label}，${t.columns.length} 个字段`); Object.assign(b.style,{left:`${p.x}px`,top:`${p.y}px`,width:`${p.w}px`,height:`${p.h}px`});
      b.setAttribute('title',`${t.name} · ${t.label} · ${t.columns.length} 个字段`);
      const label=el('span',undefined,'table-label');label.append(el('code',t.name,'table-identifier'),el('span',`${t.columns.length} 字段`,'table-count'));b.append(el('span',t.label || t.name,'table-name'),label);
      b.addEventListener('click',()=>selectTable(t.name));nodes.set(t.name,b);$('canvas').append(b);
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
    groups.forEach(group=>group.forEach((item,index)=>{const p=positions.get(item.name),horizontal=['left','right'].includes(item.side),limit=Math.min(40,(horizontal?p.h:p.w)/2-12),offset=Math.max(-limit,Math.min(limit,(index-(group.length-1)/2)*10));const point={x:horizontal?(item.side==='right'?p.x+p.w:p.x):p.x+p.w/2+offset,y:horizontal?p.y+p.h/2+offset:(item.side==='bottom'?p.y+p.h:p.y)};edgePorts.set(`${item.index}:${item.endpoint}`,{...point,side:item.side});}));
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
      const activate=()=>{const target=state.selected===from?to:from;selectTable(target);nodes.get(target)?.focus({preventScroll:true});revealTable(target);};path.addEventListener('click',activate);path.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();activate();}});
      path.addEventListener('pointerenter',()=>{label.style.display='';});path.addEventListener('pointerleave',()=>{if(state.selected!==from&&state.selected!==to)label.style.display='none';});path.addEventListener('focus',()=>{label.style.display='';});path.addEventListener('blur',()=>{if(state.selected!==from&&state.selected!==to)label.style.display='none';});
      g.append(path,label);$('edges').append(g);edgeObjects.push({r,id:relationId(r,index),g,path,label});
    });
  }
  function renderSearch() {
    $('search-results').hidden=!state.query;$('result-list').replaceChildren();if(!state.query)return;
    const matches=searchMatches();$('search-explanation').textContent=matches.length?`匹配 ${matches.length} 张表；保留关系图上下文。清空搜索回到「${state.searchBase==='all'?'全部':domainLabel(state.searchBase)}」。`:'没有匹配的表或字段，请换一个关键词。';
    matches.forEach(item=>{const b=el('button',undefined,'result');b.type='button';b.append(el('code',item.table.name),el('small',item.rank===3?`字段：${item.fields.join('、')}`:item.table.label));b.addEventListener('click',()=>{selectTable(item.table.name);nodes.get(item.table.name).focus({preventScroll:true});revealTable(item.table.name);});$('result-list').append(b);});
  }
  function renderView() {
    rebuildVisibleLayout();
    applyZoom();
    const visible=visibleNames(),related=relatedSet(),matches=new Set(searchMatches().map(m=>m.table.name));
    nodes.forEach((node,name)=>{node.hidden=!visible.has(name);node.classList.toggle('selected',state.selected===name);node.classList.toggle('dim',state.selected ? !related.has(name) : !!state.query&&!matches.has(name));node.classList.toggle('match',!!state.query&&matches.has(name));node.setAttribute('aria-pressed',String(state.selected===name));});
    edgeObjects.forEach(e=>{const from=fromName(e.r),to=toName(e.r),active=state.selected===from||state.selected===to;e.g.style.display=activeKind(e.r)&&visible.has(from)&&visible.has(to)?'':'none';e.path.classList.toggle('active',active);e.path.classList.toggle('dim',!!state.selected&&!active);e.path.setAttribute('marker-end',active||isLogical(e.r)?'url(#active-arrow)':'url(#fk-arrow)');e.label.style.display=active?'':'none';});
    document.querySelectorAll('[data-domain]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.domain===state.domain)));
    let note=`${visible.size} / ${tables.length} 张表`;
    if(state.related&&!state.selected)note+=' · 先选一张表，再显示它的直接关联表。';else if(state.related)note+=' · 仅选中表与当前启用关系的直接邻居。';else if(state.domain!=='all'&&!state.query)note+=` · ${domainLabel(state.domain)}`;
    $('view-status').textContent=note;$('no-view').hidden=visible.size!==0;$('no-view').textContent='这个数据域没有可显示的表，选择「全部」恢复概览。';
    $('selection-hint').textContent=state.selected?`已选 ${state.selected}`:'选择表查看字段';
    renderSearch();
  }
  function makeSection(title) {const section=el('section',undefined,'detail-section');section.append(el('h3',title));return section;}
  function makeFold(title, hint, cls) {const section=el('details',undefined,`inspection${cls?` ${cls}`:''}`),summary=el('summary',title),content=el('div',undefined,'fold-content');if(hint)summary.append(el('small',hint));section.append(summary,content);return {section,content};}
  function columnMarkers(t,c,complete) {
    const markers=el('td');
    const add=(label,title)=>{const key=el('span',label,'field-key');key.setAttribute('title',title);markers.append(key);};
    if(c.primary_key)add(complete&&c.pk_position>1?`PK #${c.pk_position}`:'PK','主键');
    if(c.foreign_key)add('FK','SQL 草案外键');
    if(c.unique)add('UQ','单字段唯一约束');
    if(t.indices.some(i=>(i.columns||[]).some(v=>(typeof v==='string'?v:v.name)===c.name)))add('IDX','索引字段');
    if(!complete&&c.nullable)markers.append(el('span','○ 可空','nullable'));
    if(complete){const composite=t.indices.filter(i=>i.unique&&!i.partial&&i.columns.length>1&&list(c.unique_groups).includes(i.name));if(composite.length)markers.append(el('span',`组合唯一：${composite.map(i=>i.name).join('；')}`));}
    if(!markers.childNodes.length)markers.textContent='—';
    return markers;
  }
  function fieldsTable(t,complete) {
    const wrap=el('div',undefined,'table-wrap'),table=el('table',undefined,complete?'complete-fields':'compact-fields');
    if(complete)table.append(el('caption','完整定义 · 可横向滚动'));
    const thead=el('thead'),head=el('tr');(complete?['字段','类型','标记','可为空','默认值']:['字段','类型','标记']).forEach(s=>head.append(el('th',s)));thead.append(head);table.append(thead);
    const tbody=el('tbody');t.columns.forEach(c=>{const tr=el('tr'),name=el('td'),type=el('td');name.append(el('code',c.name));type.append(el('code',c.type));tr.append(name,type,columnMarkers(t,c,complete));if(complete)tr.append(el('td',c.nullable?'是':'否'),el('td',display(c.default),'default-value'));tbody.append(tr);});table.append(tbody);wrap.append(table);return wrap;
  }
  function navigateRelation(other) {state.query='';$('search').value='';state.domain='all';state.searchBase='all';selectTable(other);nodes.get(other)?.focus({preventScroll:true});revealTable(other);}
  function renderDetails() {
    const selected=!!state.selected;
    $('detail-panel').hidden=!selected;
    $('schema-surface').classList.toggle('has-selection',selected);
    $('detail-empty').hidden=selected;
    if(!state.selected){$('detail').hidden=true;$('detail-body').replaceChildren();return;}
    const t=byName.get(state.selected),body=$('detail-body');$('detail').hidden=false;$('detail-title').textContent=t.name;$('detail-description').textContent=`${domainLabel(t.domain)} · ${t.label} · ${t.columns.length} 字段`;body.replaceChildren();
    if(t.columns.some(c=>['created_at','started_at','finished_at'].includes(c.name)))body.append(el('p','时间字段：数据库使用 TIMESTAMPTZ；API 返回 ISO 8601 UTC 日期字符串。视频偏移和时长仍以毫秒记录。','relation-note'));
    if(t.name==='event_embeddings')body.append(el('p','source_text 保存描述，embedding 使用 pgvector 原生向量类型。通过 event_id 关联事件，可选 evidence_id 关联关键帧；按模型版本和维度检索后回查画面。模型未定，尚无运行检索服务。','relation-note'));
    if(t.name==='conversations')body.append(el('p','会话归属用户，不固定录像；同一会话的不同分析可查询不同视频。每次分析仍保留自己的单视频范围，尚无运行服务。','relation-note'));
    if(t.name==='messages')body.append(el('p','保存用户问题与已完成的助手答复；助手答复关联一次 Agent 运行。中间流式文字不另存，人工核查仍在 review_records。','relation-note'));
    if(t.name==='runs')body.append(el('p','保存真正执行的计划、状态和证据引用，答复正文保存在 messages。引用 JSON 是逻辑关联；运行重启中断须标为失败，历史保存不等于自动续跑。','relation-note'));
    body.append(fieldsTable(t,false),el('p','PK 主键 · FK 外键 · UQ 唯一 · IDX 索引 · ○ 可空','marker-legend'));
    const touching=relationships.filter(r=>fromName(r)===t.name||toName(r)===t.name),groups=new Map();
    touching.filter(activeKind).forEach(r=>{const other=otherName(r,t.name),direction=fromName(r)===t.name?'→ 引用':'← 被引用',key=`${direction}:${other}:${r.kind}`;if(!groups.has(key))groups.set(key,{other,direction,logical:isLogical(r),rows:[]});groups.get(key).rows.push(r);});
    const relations=makeSection('直接关系');if(!groups.size)relations.append(el('p','没有当前启用的直接关系。','relation-note'));
    groups.forEach(group=>{const button=el('button',undefined,'relation-button');button.type='button';button.append(el('b',`${group.direction} ${group.other}`),el('span',group.logical?`${group.rows.length} 条逻辑引用`:`${group.rows.length} 组外键`,group.logical?'logical-label':undefined));button.addEventListener('click',()=>navigateRelation(group.other));relations.append(button);});body.append(relations);
    const apiOps=(S.api_mapping?.operations||[]).filter(op=>op.read_tables.includes(t.name)||op.write_tables.includes(t.name));
    if(apiOps.length){
      const current=apiOps.filter(op=>op.status==='current_draft').length,apis=makeFold('相关 API',`${current} 当前草案 · ${apiOps.length-current} 建议`,'api-relations');
      apis.content.append(el('p','接口与表不是一一对应。这里区分已定义的草案和待补接口，均不代表服务已运行。'));
      apiOps.forEach(op=>{const item=el('div',undefined,'constraint-item'),writes=op.write_tables.includes(t.name),reads=op.read_tables.includes(t.name);item.append(el('b',`${op.method} ${op.path}`,'constraint-name'),el('p',`${op.status==='current_draft'?'当前草案':'建议新增'} · ${reads&&writes?'读取及写入':writes?'写入':'读取'} ${t.name}`,'relation-note'),el('p',op.summary,'relation-note'));apis.content.append(item);});
      body.append(apis.section);
    }
    const full=makeFold('完整字段定义','默认值与约束','full-definition');full.content.append(el('p',`${domainLabel(t.domain)} · ${t.description || ''}`),fieldsTable(t,true));if(t.source_file)full.content.append(el('p',`定义来源：${t.source_file}`,'source-path'));body.append(full.section);
    const fullRelations=makeFold('完整关系定义',`${touching.length} 条`,'full-relations');if(!touching.length)fullRelations.content.append(el('p','此表没有目录声明的外键或逻辑引用。','relation-note'));
    touching.forEach(r=>{const item=el('div',undefined,'full-relation');item.append(el('b',relationText(r)),el('p',cardinality(r),isLogical(r)?'logical-label':undefined));if(isLogical(r))item.append(el('p',r.description || 'JSON 历史快照引用，不受数据库外键保证。'));else{item.append(el('p',`ON DELETE ${r.on_delete || 'NO ACTION'} · ON UPDATE ${r.on_update || 'NO ACTION'}`));if(r.initially_deferred)item.append(el('p','DEFERRABLE INITIALLY DEFERRED · 事务提交时校验'));else if(r.deferrable)item.append(el('p','DEFERRABLE · 可延迟校验'));}fullRelations.content.append(item);});body.append(fullRelations.section);
    const indices=makeFold('索引与组合唯一',`${t.indices.length} 个`,'indices');if(!t.indices.length)indices.content.append(el('p','没有额外索引。','relation-note'));t.indices.forEach(i=>{const item=el('div',undefined,'constraint-item');const cols=list(i.column_details).length?i.column_details.map(c=>`${c.name}${c.descending?' DESC':' ASC'}${c.collation&&c.collation!=='BINARY'?` COLLATE ${c.collation}`:''}`).join(' + '):(i.columns||[]).map(v=>typeof v==='string'?v:v.name).join(' + ');item.append(el('b',i.name,'constraint-name'),el('p',`${i.unique?'唯一':'普通'}索引 · ${cols}${i.partial?' · 部分索引':''}${i.origin==='pk'?' · 主键自动索引':i.origin==='u'?' · 唯一约束自动索引':''}`,'relation-note'));if(i.sql)item.append(el('pre',i.sql,'constraint-expression'));indices.content.append(item);});body.append(indices.section);
    const checks=makeFold('CHECK 约束',`${t.checks.length} 个`,'checks');if(!t.checks.length)checks.content.append(el('p','目录没有记录 CHECK 约束。','relation-note'));t.checks.forEach(c=>{const item=el('div',undefined,'constraint-item');item.append(el('b',c.name || 'CHECK','constraint-name'),el('pre',c.expression || display(c),'constraint-expression'));checks.content.append(item);});body.append(checks.section);
    const triggers=makeFold('触发器',`${t.triggers.length} 个`,'triggers');if(!t.triggers.length)triggers.content.append(el('p','没有触发器。','relation-note'));t.triggers.forEach(tr=>{const item=el('div',undefined,'constraint-item');item.append(el('b',tr.name,'constraint-name'));if(tr.event)item.append(el('p',tr.event,'relation-note'));item.append(el('pre',tr.sql || display(tr),'constraint-expression'));triggers.content.append(item);});body.append(triggers.section);
  }
  function selectTable(name) {if(!byName.has(name))return;state.selected=name;if(!state.query&&state.domain!=='all'&&byName.get(name).domain!==state.domain)state.domain='all';renderDetails();renderView();$('detail-body').scrollTo({top:0,left:0});revealTable(name);}
  function closeDetails() {const former=state.selected;state.selected=null;renderDetails();renderView();if(former){nodes.get(former)?.focus({preventScroll:true});revealTable(former);}}
  function applyZoom() {
    const {full,current}=diagramWidths(),panelScale=Math.min(1,current/full);
    state.zoom=state.userZoom*panelScale;
    $('canvas').style.transform=`scale(${state.zoom})`;
    $('canvas-stage').style.width=`${canvasWidth*state.zoom}px`;$('canvas-stage').style.height=`${canvasHeight*state.zoom}px`;
    $('zoom-value').value=`${Math.round(state.zoom*100)}%`;$('zoom-value').textContent=`${Math.round(state.zoom*100)}%`;
    $('zoom-out').disabled=state.userZoom<=.25;$('zoom-in').disabled=state.userZoom>=1.4;
  }
  function zoom(value) {state.userZoom=Math.max(.25,Math.min(1.4,value));applyZoom();}
  function fitWidth() {const fit=diagramWidths().full/canvasWidth;zoom(minimal?Math.min(1,fit):fit);$('canvas-main').scrollTo({left:0,top:0});}
  function revealTable(name) {const p=positions.get(name);if(!p)return;const viewport=$('canvas-main'),stage=$('canvas-stage'),margin=18,x=(stage.offsetLeft||0)+p.x*state.zoom,y=(stage.offsetTop||12)+p.y*state.zoom,w=p.w*state.zoom,h=p.h*state.zoom;let left=viewport.scrollLeft||0,top=viewport.scrollTop||0;const width=viewport.clientWidth||canvasWidth,height=viewport.clientHeight||520;if(x<left+margin)left=Math.max(0,x-margin);else if(x+w>left+width-margin)left=Math.max(0,x+w-width+margin);if(y<top+margin)top=Math.max(0,y-margin);else if(y+h>top+height-margin)top=Math.max(0,y+h-height+margin);viewport.scrollTo({left,top});}
  function rebuildVisibleLayout() {if($('canvas-main').clientWidth<=0)return false;const plan=measureLayout();if(plan.key===layoutKey)return false;const focusedTable=document.activeElement?.dataset?.table;domainRegions.splice(0).forEach(e=>e.remove());nodes.forEach(e=>e.remove());nodes.clear();edgeObjects.splice(0).forEach(e=>e.g.remove());positions.clear();edgePorts.clear();usedSegments.clear();prepareLayout(plan);preparePorts();buildNodes();buildEdges();fitWidth();if(state.selected)revealTable(state.selected);if(focusedTable)nodes.get(focusedTable)?.focus({preventScroll:true});return true;}
  function refreshLayout() {if($('canvas-main').clientWidth<=0)return;renderView();if(state.selected)revealTable(state.selected);}
  function scheduleLayout() {if(layoutFrame)return;layoutFrame=1;const run=()=>{layoutFrame=0;refreshLayout();};if(typeof requestAnimationFrame==='function')requestAnimationFrame(run);else run();}
  function clearSearch() {state.query='';$('search').value='';state.domain=state.searchBase;if(state.selected&&state.domain!=='all'&&byName.get(state.selected).domain!==state.domain)state.selected=null;renderDetails();renderView();}
  function reset() {Object.assign(state,{domain:'all',query:'',selected:null,related:false,showFk:true,showLogical:false,searchBase:'all'});$('search').value='';$('related-only').checked=false;$('show-fk').checked=true;$('show-logical').checked=false;renderDetails();renderView();fitWidth();}
  const all=el('button','全部','filter');all.type='button';all.dataset.domain='all';$('domain-filters').append(all);domains.forEach(d=>{const id=d.id||d.name,b=el('button',d.label||d.title||id,'filter');b.type='button';b.dataset.domain=id;$('domain-filters').append(b);});
  if(domains.length===1)$('domain-filters').hidden=true;
  if(minimal){displayControl.hidden=!relationships.some(isLogical);$('related-only').closest('label').hidden=true;}
  $('domain-filters').addEventListener('click',e=>{const b=e.target.closest('[data-domain]');if(!b)return;state.domain=b.dataset.domain;state.searchBase=state.domain;state.query='';state.related=false;$('related-only').checked=false;$('search').value='';if(state.selected&&state.domain!=='all'&&byName.get(state.selected).domain!==state.domain)state.selected=null;renderDetails();renderView();});
  $('search').addEventListener('input',e=>{const next=e.target.value.trim();if(next&&!state.query)state.searchBase=state.domain;if(!next){clearSearch();return;}state.query=next;renderView();});$('clear-search').addEventListener('click',()=>{clearSearch();$('search').focus();});
  $('related-only').addEventListener('change',e=>{state.related=e.target.checked;renderView();});$('show-fk').addEventListener('change',e=>{state.showFk=e.target.checked;renderDetails();renderView();});$('show-logical').addEventListener('change',e=>{state.showLogical=e.target.checked;renderDetails();renderView();});
  $('close-detail').addEventListener('click',closeDetails);document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(displayControl.open){e.preventDefault();closeDisplayMenu(true);}else if(state.selected)closeDetails();}});$('reset').addEventListener('click',reset);$('fit-width').addEventListener('click',fitWidth);$('zoom-out').addEventListener('click',()=>zoom(state.userZoom-.2));$('zoom-in').addEventListener('click',()=>zoom(state.userZoom+.2));
  $('open-embeddings').hidden=!minimal || !byName.has('event_embeddings');$('open-embeddings').addEventListener('click',()=>navigateRelation('event_embeddings'));
  $('open-agent').hidden=!minimal || !withChat;$('open-agent').addEventListener('click',()=>navigateRelation('conversations'));
  const columnCount=tables.reduce((sum,t)=>sum+t.columns.length,0),fkCount=relationships.filter(r=>!isLogical(r)).length,logicalCount=relationships.filter(isLogical).length;
  [['表',tables.length],['字段',columnCount],['SQL 外键',fkCount],['逻辑引用',logicalCount]].forEach(([label,count])=>{const span=el('span');span.append(el('b',count),document.createTextNode(` ${label}`));$('schema-facts').append(span);});
  const meta=S.metadata||{},sources=list(meta.source_files).join('、')||'docs/database/schema.sql';
  $('source-line').textContent=`版本 ${meta.schema_version} · 依据技术方案 r51 / PRD r230，按 2026-10-13 用户要求采用 PostgreSQL + pgvector，包含事件向量与 AI 会话 · 定义来源 ${sources} · 目录 docs/database/schema-catalog.json`;
  $('parity-line').textContent=`目录校验：${tables.length} 张表 / ${columnCount} 列 / ${fkCount} 组 SQL 草案外键（复合外键按一组计），逻辑快照引用 ${logicalCount} 条，分别计数。PostgreSQL 结构验证与真实业务验收分开。`;
  if(meta.schema_sha256)$('parity-line').append(document.createTextNode(` SQL SHA-256 ${meta.schema_sha256.slice(0,12)}…`));
  $('api-source-line').textContent=`API 对照来源：${S.api_mapping?.source_file||'无'}；建议接口不计入当前 OpenAPI，也不计为 SQL 外键。`;
  setupDisplayMenu();
  renderDetails();renderView();
  if(typeof ResizeObserver==='function')new ResizeObserver(scheduleLayout).observe($('canvas-main'));else if(typeof window!=='undefined')window.addEventListener('resize',scheduleLayout);
})();
</script>
</body></html>
'''


def validate_catalog(catalog: dict) -> dict:
    """Check semantic references before embedding any source-derived data."""
    if not isinstance(catalog.get("tables"), list) or not catalog["tables"]:
        raise ValueError("catalog.tables must be a non-empty list")
    domains = catalog.get("domains")
    if not isinstance(domains, list) or not domains:
        raise ValueError("catalog.domains must be a non-empty list")
    domain_ids = [domain.get("id") for domain in domains]
    if any(not isinstance(domain_id, str) or not domain_id for domain_id in domain_ids):
        raise ValueError("every domain must have a non-empty string id")
    if len(set(domain_ids)) != len(domain_ids):
        raise ValueError("duplicate domain ids in catalog")
    names = {table["name"] for table in catalog["tables"]}
    if len(names) != len(catalog["tables"]):
        raise ValueError("duplicate table names in catalog")
    columns = {table["name"]: {column["name"] for column in table["columns"]} for table in catalog["tables"]}
    for table in catalog["tables"]:
        if table.get("domain") not in domain_ids:
            raise ValueError(f"undefined domain for table {table['name']}")
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


def load_api_mapping(root: Path, table_names: set[str]) -> dict:
    """Review annotations are separate from SQL relationships and current contracts."""
    path = root / "docs/api/feature-data-api-map.json"
    mapping = json.loads(path.read_text(encoding="utf-8"))
    openapi_path = root / "docs/api/openapi.json"
    openapi = json.loads(openapi_path.read_text(encoding="utf-8"))
    current_contracts = {
        (method.upper(), path)
        for path, methods in openapi["paths"].items()
        for method in methods if method in ("get", "post", "put", "patch", "delete")
    }
    seen = set()
    current = set()
    for operation in mapping["operations"]:
        key = (operation["method"], operation["path"])
        if key in seen or operation["status"] not in ("current_draft", "proposed"):
            raise ValueError(f"duplicate or invalid API mapping {key}")
        seen.add(key)
        for field in ("read_tables", "write_tables"):
            if not isinstance(operation[field], list) or not set(operation[field]) <= table_names:
                raise ValueError(f"API mapping references unknown SQL table: {key}")
        if operation["status"] == "current_draft":
            if key not in current_contracts:
                raise ValueError(f"current API mapping has no OpenAPI contract: {key}")
            current.add(key)
        elif key in current_contracts:
            raise ValueError(f"existing API incorrectly marked proposed: {key}")
    if current != current_contracts:
        raise ValueError("API mapping must cover every current OpenAPI operation")
    mapping["source_file"] = path.relative_to(root).as_posix()
    mapping["source_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    mapping["openapi_sha256"] = hashlib.sha256(openapi_path.read_bytes()).hexdigest()
    return mapping


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=root / "docs/database/schema-catalog.json")
    parser.add_argument("--output", type=Path, default=root / "docs/database/schema-canvas.html")
    parser.add_argument("--check", action="store_true", help="Check that the HTML matches the current catalog and template, without writing.")
    args = parser.parse_args()
    catalog_path, output_path = args.catalog.resolve(), args.output.resolve()
    catalog_relative, output_relative = catalog_path.relative_to(root), output_path.relative_to(root)
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    source_sql = root / "docs/database/schema.sql"
    declared_sha = catalog.get("metadata", {}).get("schema_sha256")
    if not declared_sha or hashlib.sha256(source_sql.read_bytes()).hexdigest() != declared_sha:
        raise ValueError("catalog SQL hash is missing or stale; regenerate schema-catalog.json first")
    totals = validate_catalog(catalog)
    catalog["api_mapping"] = load_api_mapping(root, {table["name"] for table in catalog["tables"]})
    data = json.dumps(catalog, ensure_ascii=False, separators=(",", ":"))
    for char, escaped in (("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e")):
        data = data.replace(char, escaped)
    generated = TEMPLATE.replace("__SCHEMA_JSON__", data)
    if args.check:
        if not output_path.exists() or output_path.read_text(encoding="utf-8") != generated:
            parser.exit(1, "Schema HTML is stale; run python3 scripts/build-schema-canvas.py.\n")
    else:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(generated, encoding="utf-8")
    print(json.dumps({"output": output_relative.as_posix(), "source": catalog_relative.as_posix(), "validation": totals, "offline": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()

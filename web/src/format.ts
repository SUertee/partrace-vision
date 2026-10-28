import { ApiError } from "./api/client";
import type { Decision, DetectionSource, ObservationStatus, RunStatus, SourceMode, ToolName } from "./api/types";

export const EVENT_TYPE_LABEL: Record<string, string> = {
  enter_zone: "进入区域",
  dwell: "区域停留",
  shelf_interaction: "货架交互候选",
  leave_zone: "离开区域",
};

export const eventTypeLabel = (type: string) => EVENT_TYPE_LABEL[type] ?? "检测事件";

export const OBSERVATION_LABEL: Record<ObservationStatus, string> = {
  candidate: "待人工核查",
  observed: "画面观测记录",
};

export const DETECTION_LABEL: Record<DetectionSource, string> = {
  automatic: "自动检测",
  manual: "人工标注",
  mock: "模拟数据",
};

export const SOURCE_LABEL: Record<SourceMode, string> = {
  sdk: "SDK 原片",
  prerecorded: "真实预录",
  mock: "模拟数据",
};

export const DECISION_LABEL: Record<Decision, string> = {
  confirmed: "已确认",
  dismissed: "已排除",
  uncertain: "待确认",
};

export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  running: "正在核查",
  completed: "已完成",
  stopped: "已停止",
  failed: "未完成",
};

export const TOOL_LABEL: Record<ToolName, string> = {
  load_skill: "加载核查流程",
  search_events: "检索区域事件",
  semantic_search_events: "匹配相关片段",
  get_event: "读取事件过程",
  get_evidence: "读取关键帧资料",
  read_recording: "核对录像来源与覆盖",
};

/** Video-relative offset as m:ss (h:mm:ss beyond an hour). */
export function offsetLabel(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = String(total % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

export function rangeLabel(start: number, end: number): string {
  return start === end ? offsetLabel(start) : `${offsetLabel(start)}–${offsetLabel(end)}`;
}

const timeFormat = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
});

export function timeLabel(iso: string | null | undefined): string {
  if (!iso) return "时间未知";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "时间未知" : timeFormat.format(date);
}

/** Friendly Chinese text for any failure; raw errors never reach the screen. */
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "操作未完成，请稍后重试。";
  const { status, code } = error;
  if (code === "not_configured") return "尚未连接后端服务，请先在服务设置中填写凭据。";
  if (code === "network_unavailable") return "网络连接失败，请检查网络或服务地址后重试。";
  if (status === 401) return "后端凭据无效或已过期，请在服务设置中更新。";
  if (status === 403) return "当前账号无权访问此会话、录像或证据，请检查后端授权。";
  if (status === 404 && code === "media_missing") return "关键帧当前缺失，原始引用仍保留。";
  if (status === 404 && code === "not_found") return "接口不存在，请确认后端已启用对应能力（如 Agent）。";
  if (status === 404) return "对应的资源暂不可访问，请刷新后重试。";
  if (status === 409 && code === "recording_not_ready") return "分析结果尚未发布，请稍后刷新。";
  if (status === 409 && code === "run_in_progress") return "当前分析仍在执行，请先停止或等待完成。";
  if (status === 409) return "稳定 ID 对应的内容发生冲突，请新建问题后再试。";
  if (code === "invalid_question") return "请输入 1 至 2000 字的问题。";
  if (code === "invalid_scope") return "请先选择已发布录像及其有效区域。";
  if (code === "real_recording_required") return "请选择已发布的真实录像；历史模拟分析仅供查看。";
  if (code === "model_unavailable") return "服务端模型尚未就绪，请检查 Agent 与 Embedding 配置。";
  if (code === "agent_busy") return "服务端分析已达并发限额，请稍后重试。";
  if (code === "service_restarted") return "服务已重启，原分析未完成，可重新执行。";
  if (code === "clock_unverified") return "录像尚无已校准的真实时间，请使用片段内相对时间。";
  if (code === "invalid_media") return "关键帧文件不可显示，原始引用仍保留。";
  if (code === "response_too_large") return "返回内容超过本次读取限制。";
  if (code === "invalid_response" || code === "invalid_plan") return "服务端返回的数据不符合接口约定，请重试。";
  if (status === 422) return "请求范围或配置不符合后端要求，请检查录像和区域。";
  if (status === 429) return "请求过于频繁，请稍后重试。";
  if (status >= 500) return "服务暂时不可用，请稍后重试。";
  return "操作未完成，请稍后重试。";
}

export function isAuthError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.code === "not_configured");
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

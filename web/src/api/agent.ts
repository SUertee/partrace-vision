import { ApiError, query, request, requireId, send } from "./client";
import { readSse } from "./sse";
import type { Conversation, Message, Page, Plan, Query, Run, RunPush, RunStatus } from "./types";

const BASE = "/api/v1/agent";

export async function createConversation(conversationId: string, signal?: AbortSignal): Promise<Conversation> {
  const body = { conversation_id: requireId(conversationId) };
  return (await request<{ conversation: Conversation }>(`${BASE}/conversations`, { body, signal })).conversation;
}

export async function listConversations(cursor?: string | null, signal?: AbortSignal): Promise<Page<Conversation>> {
  const value = await request<{ conversations: Conversation[]; next_cursor: string | null }>(
    `${BASE}/conversations` + query({ limit: 20, cursor: cursor ?? undefined }), { signal });
  return { items: value.conversations, next_cursor: value.next_cursor };
}

export async function createMessage(conversationId: string, messageId: string, content: string, signal?: AbortSignal): Promise<Message> {
  const text = content.trim();
  if (!text || text.length > 2000) throw new ApiError(0, "invalid_question");
  const body = { message_id: requireId(messageId), content: text };
  return (await request<{ message: Message }>(`${BASE}/conversations/${requireId(conversationId)}/messages`, { body, signal })).message;
}

export async function listMessages(conversationId: string, cursor?: string | null, signal?: AbortSignal): Promise<Page<Message>> {
  const value = await request<{ messages: Message[]; next_cursor: string | null }>(
    `${BASE}/conversations/${requireId(conversationId)}/messages` + query({ limit: 20, cursor: cursor ?? undefined }), { signal });
  return { items: value.messages, next_cursor: value.next_cursor };
}

/** Draft plan for a fixed scope; the returned query must echo the requested scope. */
export async function createPlan(inputMessageId: string, scope: Query, signal?: AbortSignal): Promise<Plan> {
  const value = await request<Plan>(`${BASE}/plans`, { body: { input_message_id: requireId(inputMessageId), scope }, signal });
  if (!sameQuery(value.query, scope) || !value.query_text?.trim() || !Array.isArray(value.steps) || value.steps.length === 0)
    throw new ApiError(0, "invalid_plan");
  return { query: scope, query_text: value.query_text, steps: value.steps };
}

export async function startRun(runId: string, inputMessageId: string, plan: Plan, signal?: AbortSignal): Promise<Run> {
  const body = { run_id: requireId(runId), input_message_id: requireId(inputMessageId), plan };
  return checkRun(await request<Run>(`${BASE}/runs`, { body, signal }), runId);
}

export async function getRun(runId: string, signal?: AbortSignal): Promise<Run> {
  return checkRun(await request<Run>(`${BASE}/runs/${requireId(runId)}`, { signal }), runId);
}

export async function stopRun(runId: string, signal?: AbortSignal): Promise<Run> {
  return checkRun(await request<Run>(`${BASE}/runs/${requireId(runId)}/stop`, { body: {}, signal }), runId);
}

const STATUSES: RunStatus[] = ["running", "completed", "stopped", "failed"];

function checkRun(run: Run, runId: string): Run {
  if (run.run_id !== runId || !STATUSES.includes(run.status) || !run.plan?.query) throw new ApiError(0, "invalid_response");
  if (run.cards && (run.cards.run_id !== runId || !sameQuery(run.cards.query, run.plan.query))) throw new ApiError(0, "invalid_response");
  return run;
}

export function sameQuery(a: Query | undefined, b: Query | undefined): boolean {
  return !!a && !!b && a.recording_id === b.recording_id && a.camera_id === b.camera_id && a.zone_id === b.zone_id &&
    a.start_offset_ms === b.start_offset_ms && a.end_offset_ms === b.end_offset_ms;
}

/**
 * Follows one run over SSE until a terminal state. The backend sends a running
 * snapshot (id 1) and one terminal frame (id 2); if the stream ends early the
 * run is re-read and, while still running, the stream is reopened with
 * Last-Event-ID and backoff. Resolves with the final authoritative run.
 */
export async function watchRun(runId: string, onUpdate: (push: RunPush) => void, signal: AbortSignal): Promise<Run> {
  let lastEventId: string | undefined;
  let attempt = 0;
  while (true) {
    let terminal: RunPush | null = null;
    try {
      const response = await send(`${BASE}/runs/${requireId(runId)}/events`, {
        signal,
        accept: "text/event-stream",
        headers: lastEventId ? { "Last-Event-ID": lastEventId } : undefined,
      });
      if (!response.body) throw new ApiError(response.status, "invalid_response");
      await readSse(response.body, (frame) => {
        if (frame.event !== "run") return;
        let push: RunPush;
        try {
          push = JSON.parse(frame.data) as RunPush;
        } catch {
          return;
        }
        if (push.run_id !== runId || !STATUSES.includes(push.status)) return;
        lastEventId = frame.id ?? lastEventId;
        attempt = 0;
        onUpdate(push);
        if (push.status !== "running") terminal = push;
      }, signal);
    } catch (error) {
      if (signal.aborted) throw error;
      // A rejected stream (401/403/404) is final; transient network errors fall back to re-reading.
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) throw error;
    }
    if (signal.aborted) throw new DOMException("aborted", "AbortError");
    // The pushed payload is a projection; the run envelope is the record to keep.
    const run = await getRun(runId, signal);
    if (run.status !== "running" || terminal) return run;
    attempt += 1;
    await delay(Math.min(15000, 1000 * 2 ** Math.min(attempt, 4)), signal);
  }
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    const abort = () => { clearTimeout(timer); reject(new DOMException("aborted", "AbortError")); };
    signal.addEventListener("abort", abort, { once: true });
  });
}

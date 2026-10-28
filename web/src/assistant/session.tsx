import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { createConversation, createMessage, createPlan, getRun, listMessages, startRun, stopRun, watchRun } from "../api/agent";
import { newId } from "../api/client";
import type { Plan, Query, Run, RunPush } from "../api/types";
import { isAbort } from "../format";
import { useServiceConfig } from "../hooks";

export type PlanStatus = "pending" | "draft" | "starting" | "confirmed" | "failed" | "history";

/** One question and everything derived from it: plan draft, run and live state. */
export interface Turn {
  messageId: string;
  question: string;
  scope: Query | null;
  planStatus: PlanStatus;
  plan?: Plan;
  runId?: string;
  run?: Run;
  live?: RunPush;
  answer?: string; // assistant reply text restored from history
  error?: unknown;
  loadingRun?: boolean;
}

interface Session {
  conversationId: string | null;
  turns: Turn[];
  nextCursor: string | null;
}

interface TraceSession extends Session {
  submitting: boolean;
  hasRunning: boolean;
  submit: (question: string, scope: Query) => Promise<void>;
  regeneratePlan: (messageId: string) => Promise<void>;
  confirm: (messageId: string, plan: Plan) => Promise<void>;
  cancelPlan: (messageId: string) => void;
  stop: (messageId: string) => Promise<void>;
  retry: (messageId: string) => Promise<void>;
  openConversation: (conversationId: string) => Promise<void>;
  loadMoreMessages: () => Promise<void>;
  loadRun: (messageId: string) => Promise<void>;
  reset: () => boolean;
}

const EMPTY: Session = { conversationId: null, turns: [], nextCursor: null };
const Context = createContext<TraceSession | null>(null);

/** Lives above the routes so a run keeps streaming while the user opens evidence. */
export function TraceSessionProvider({ children }: { children: ReactNode }) {
  const bearer = useServiceConfig()?.bearer;
  const [session, setSession] = useState<Session>(EMPTY);
  const [submitting, setSubmitting] = useState(false);
  const watchers = useRef(new Map<string, AbortController>());
  const sessionRef = useRef(session);
  sessionRef.current = session;

  const patch = useCallback((messageId: string, change: Partial<Turn> | ((turn: Turn) => Partial<Turn>)) => {
    setSession((value) => ({
      ...value,
      turns: value.turns.map((turn) => turn.messageId === messageId ? { ...turn, ...(typeof change === "function" ? change(turn) : change) } : turn),
    }));
  }, []);

  const stopWatching = useCallback(() => {
    watchers.current.forEach((controller) => controller.abort());
    watchers.current.clear();
  }, []);

  // A different credential must not keep reading or showing the previous one's session.
  useEffect(() => () => { stopWatching(); setSession(EMPTY); }, [bearer, stopWatching]);

  const follow = useCallback((messageId: string, run: Run) => {
    watchers.current.get(run.run_id)?.abort();
    if (run.status !== "running") return;
    const controller = new AbortController();
    watchers.current.set(run.run_id, controller);
    watchRun(run.run_id, (push) => patch(messageId, (turn) => turn.runId === run.run_id ? { live: push } : {}), controller.signal)
      .then((final) => patch(messageId, (turn) => turn.runId === final.run_id ? { run: final, live: undefined, error: undefined } : {}))
      .catch((error) => !isAbort(error) && patch(messageId, (turn) => turn.runId === run.run_id ? { error } : {}))
      .finally(() => watchers.current.get(run.run_id) === controller && watchers.current.delete(run.run_id));
  }, [patch]);

  const draftPlan = useCallback(async (messageId: string, scope: Query) => {
    patch(messageId, { planStatus: "pending", error: undefined });
    try {
      const plan = await createPlan(messageId, scope);
      patch(messageId, { planStatus: "draft", plan });
    } catch (error) {
      patch(messageId, { planStatus: "failed", error });
    }
  }, [patch]);

  const submit = useCallback(async (question: string, scope: Query) => {
    setSubmitting(true);
    const messageId = newId("msg");
    try {
      let conversationId = sessionRef.current.conversationId;
      if (!conversationId) {
        conversationId = newId("conv");
        await createConversation(conversationId);
        setSession({ ...EMPTY, conversationId });
      }
      await createMessage(conversationId, messageId, question);
      setSession((value) => ({ ...value, turns: [...value.turns, { messageId, question: question.trim(), scope, planStatus: "pending" }] }));
    } catch (error) {
      setSubmitting(false);
      throw error;
    }
    setSubmitting(false);
    await draftPlan(messageId, scope);
  }, [draftPlan]);

  const begin = useCallback(async (messageId: string, plan: Plan) => {
    const runId = newId("run");
    patch(messageId, { planStatus: "starting", plan, runId, run: undefined, live: undefined, error: undefined });
    try {
      const run = await startRun(runId, messageId, plan);
      patch(messageId, { planStatus: "confirmed", run });
      follow(messageId, run);
    } catch (error) {
      // An unknown start result is re-read by the same stable run_id before giving up.
      try {
        const run = await getRun(runId);
        patch(messageId, { planStatus: "confirmed", run });
        follow(messageId, run);
      } catch {
        patch(messageId, { planStatus: "draft", runId: undefined, error });
      }
    }
  }, [follow, patch]);

  const value = useMemo<TraceSession>(() => ({
    ...session,
    submitting,
    hasRunning: session.turns.some((turn) => turn.planStatus === "starting" || (turn.live ?? turn.run)?.status === "running"),
    submit,
    regeneratePlan: async (messageId) => {
      const turn = sessionRef.current.turns.find((item) => item.messageId === messageId);
      if (turn?.scope) await draftPlan(messageId, turn.scope);
    },
    confirm: begin,
    cancelPlan: (messageId) => patch(messageId, { planStatus: "failed", plan: undefined, error: undefined }),
    stop: async (messageId) => {
      const turn = sessionRef.current.turns.find((item) => item.messageId === messageId);
      if (!turn?.runId) return;
      try {
        const run = await stopRun(turn.runId);
        watchers.current.get(turn.runId)?.abort();
        patch(messageId, { run, live: undefined, error: undefined });
      } catch (error) {
        patch(messageId, { error });
      }
    },
    retry: async (messageId) => {
      const turn = sessionRef.current.turns.find((item) => item.messageId === messageId);
      if (turn?.plan) await begin(messageId, turn.plan);
    },
    openConversation: async (conversationId) => {
      stopWatching();
      const page = await listMessages(conversationId);
      setSession({ conversationId, turns: toTurns([], page.items), nextCursor: page.next_cursor });
    },
    loadMoreMessages: async () => {
      const { conversationId, nextCursor, turns } = sessionRef.current;
      if (!conversationId || !nextCursor) return;
      const page = await listMessages(conversationId, nextCursor);
      setSession({ conversationId, turns: toTurns(turns, page.items), nextCursor: page.next_cursor });
    },
    loadRun: async (messageId) => {
      const turn = sessionRef.current.turns.find((item) => item.messageId === messageId);
      if (!turn?.runId) return;
      patch(messageId, { loadingRun: true, error: undefined });
      try {
        const run = await getRun(turn.runId);
        patch(messageId, { run, plan: run.plan, loadingRun: false });
        follow(messageId, run);
      } catch (error) {
        patch(messageId, { error, loadingRun: false });
      }
    },
    reset: () => {
      const current = sessionRef.current;
      const running = current.turns.some((turn) => turn.planStatus === "starting" || (turn.live ?? turn.run)?.status === "running");
      if (running) return false;
      stopWatching();
      setSession(EMPTY);
      return true;
    },
  }), [session, submitting, submit, draftPlan, begin, patch, follow, stopWatching]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

/** History pages are ascending; an assistant reply attaches its run to the preceding question. */
function toTurns(existing: Turn[], messages: { message_id: string; role: string; content: string; run_id: string | null }[]): Turn[] {
  const turns = [...existing];
  for (const message of messages) {
    if (turns.some((turn) => turn.messageId === message.message_id)) continue;
    if (message.role === "user") {
      turns.push({ messageId: message.message_id, question: message.content, scope: null, planStatus: "history" });
    } else {
      const last = turns[turns.length - 1];
      if (last && !last.runId && !last.answer) turns[turns.length - 1] = { ...last, runId: message.run_id ?? undefined, answer: message.content };
      else turns.push({ messageId: message.message_id, question: "", scope: null, planStatus: "history", runId: message.run_id ?? undefined, answer: message.content });
    }
  }
  return turns;
}

export function useTraceSession(): TraceSession {
  const value = useContext(Context);
  if (!value) throw new Error("TraceSessionProvider missing");
  return value;
}

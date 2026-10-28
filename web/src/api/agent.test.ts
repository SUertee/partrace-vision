import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import running from "../../../docs/api/examples/v0.3/agent-running-response.json";
import completed from "../../../docs/api/examples/v0.3/agent-run-response.json";
import { saveServiceConfig } from "./client";
import { watchRun } from "./agent";

// Test-only fixtures from docs/api/examples; the app itself never reads them.
const runId = running.run_id;
const done = { ...completed, run_id: runId, cards: completed.cards ? { ...completed.cards, run_id: runId } : null, plan: running.plan };

function sse(text: string) {
  return new Response(new TextEncoder().encode(text), { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });

describe("watchRun", () => {
  beforeEach(() => {
    saveServiceConfig({ bearer: "test-token", remember: false });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    saveServiceConfig(null);
  });

  it("streams the running snapshot then resolves with the re-read terminal run", async () => {
    const calls: { url: string; auth: string | null }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, auth: new Headers(init.headers).get("Authorization") });
      if (url.endsWith("/events")) {
        const push = (status: string) => JSON.stringify({ run_id: runId, status, cards: null, error: null, finished_at: null });
        return sse(`: keep-alive\n\nid: 1\nevent: run\ndata: ${push("running")}\n\nid: 2\nevent: run\ndata: ${push("completed")}\n\n`);
      }
      return json(done);
    }));
    const updates: string[] = [];
    const run = await watchRun(runId, (push) => updates.push(push.status), new AbortController().signal);
    expect(updates).toEqual(["running", "completed"]);
    expect(run.status).toBe(done.status);
    expect(calls[0].auth).toBe("Bearer test-token");
    expect(calls[1].url).toBe(`/api/v1/agent/runs/${runId}`);
  });

  it("stops on a rejected stream", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { code: "forbidden" } }), { status: 403 })));
    await expect(watchRun(runId, () => undefined, new AbortController().signal)).rejects.toMatchObject({ status: 403 });
  });
});

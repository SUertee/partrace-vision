"""Server-sent events for Agent runs.

PostgreSQL remains the only authority: publishers signal after the terminal
transaction commits, and subscribers re-read the run with a freshly
authenticated principal instead of trusting any in-memory payload. The broker
is confined to the event loop thread; the single-executor lease makes this
process the only source of transitions.
"""

import asyncio
import json

from agent.cards import build_status_bundle
from agent.models import QueryScope

KEEPALIVE_SECONDS = 15

# Event ids are stable per run: 1 is the running snapshot, 2 is the terminal
# state. A reconnect with Last-Event-ID 2 already holds everything.
SNAPSHOT_EVENT_ID = 1
TERMINAL_EVENT_ID = 2

_STOPPED_DETAIL = "本次分析已被停止，未保存助手答复。"
_FAILED_DETAIL = "分析未完成，未保存助手答复。"


class RunPushBroker:
    """Loop-confined fan-out of commit signals, keyed by run."""

    def __init__(self):
        self._subscribers: dict[str, set[asyncio.Queue]] = {}

    def subscribe(self, run_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=4)
        self._subscribers.setdefault(run_id, set()).add(queue)
        return queue

    def unsubscribe(self, run_id: str, queue: asyncio.Queue) -> None:
        queues = self._subscribers.get(run_id)
        if queues is None:
            return
        queues.discard(queue)
        if not queues:
            self._subscribers.pop(run_id, None)

    def publish(self, run_id: str) -> None:
        """Signal a committed transition; any single signal means "re-read now"."""
        for queue in tuple(self._subscribers.get(run_id, ())):
            try:
                queue.put_nowait(None)
            except asyncio.QueueFull:
                pass  # Signals coalesce; the re-read observes the latest commit.

    def subscriber_count(self, run_id: str) -> int:
        return len(self._subscribers.get(run_id, ()))


def run_event(run: dict) -> dict:
    """Push payload from an authorized run envelope.

    Completed runs carry the persisted bundle; stopped/failed runs synthesize a
    status bundle on the fly because ck_run_terminal keeps their cards_json NULL.
    """
    status = run["status"]
    cards = run["cards"]
    if status in ("stopped", "failed"):
        detail = _STOPPED_DETAIL if status == "stopped" else (run.get("error") or {}).get("message") or _FAILED_DETAIL
        cards = build_status_bundle(
            run["run_id"], QueryScope.model_validate(run["plan"]["query"]), status, detail,
        ).model_dump(mode="json")
    return {
        "run_id": run["run_id"],
        "status": status,
        "cards": cards,
        "error": run["error"],
        "finished_at": run["finished_at"],
    }


def frame(event_id: int, payload: dict) -> str:
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return f"id: {event_id}\nevent: run\ndata: {data}\n\n"

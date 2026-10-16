"""Runtime regressions with history/lease doubles; no PostgreSQL or external model."""

import asyncio
from copy import deepcopy
import inspect
from types import SimpleNamespace

import pytest

from agent.engine import AgentExecutionError
from backend.ai import runtime as runtime_module
from backend.core.errors import ApiError


QUERY = {
    "recording_id": "rec_1", "camera_id": "cam_1", "zone_id": "E3",
    "start_offset_ms": 0, "end_offset_ms": 1000,
}
PLAN = {"query": QUERY, "query_text": "查看货架活动", "steps": ["检索并回查证据"]}


class HistoryDouble:
    def __init__(self):
        self.records = {}
        self.context_error = None
        self.context_calls = []
        self.available = True
        self.failure_attempts = []

    def recover_interrupted(self):
        return 0

    def plan_context(self, principal, message_id, query):
        self.context_calls.append(message_id)
        if self.context_error is not None:
            raise self.context_error
        return "查看货架上的包裹"

    def get_run(self, principal, run_id):
        if run_id not in self.records:
            raise ApiError(404, "run_not_found", "分析不存在。")
        return deepcopy(self.records[run_id])

    def start_run(self, principal, run_id, message_id, plan, model_key):
        if run_id in self.records:
            return deepcopy(self.records[run_id]), False
        self.records[run_id] = {
            "run_id": run_id, "input_message_id": message_id,
            "status": "running", "plan": deepcopy(plan), "model_key": model_key,
            "summary": None, "references": [], "error": None,
        }
        return deepcopy(self.records[run_id]), True

    def fail_run(self, run_id, code, message):
        self.failure_attempts.append(run_id)
        if not self.available:
            raise ApiError(503, "database_unavailable", "临时数据库不可用。")
        row = self.records[run_id]
        if row["status"] == "running":
            row.update(status="failed", error={"code": code, "message": message})

    def complete_run(self, *args):
        pytest.fail("a failed or unscheduled engine must not complete an assistant answer")


class LeaseDouble:
    def __init__(self, settings):
        self.acquired = False
        self.closed = False
        self.checks = 0

    def acquire(self):
        self.acquired = True

    def check(self):
        self.checks += 1

    def close(self):
        self.closed = True


class GatewayDouble:
    def __init__(self, backend, *args):
        self.backend = backend

    def principal(self):
        return self.backend.authenticate("controlled-test-token")


class FailingEngine:
    async def execute(self, *args):
        raise AgentExecutionError("provider_unavailable")


@pytest.fixture
def prepared(monkeypatch, tmp_path):
    history = HistoryDouble()
    principal = SimpleNamespace(user_id="owner")
    backend = SimpleNamespace(
        settings=SimpleNamespace(
            agent_cursor_secret_file=tmp_path / "unused-cursor.key",
            agent_model="controlled-model", agent_model_version="test",
            agent_max_concurrent_runs=1,
        ),
        authenticate=lambda token: principal,
    )
    monkeypatch.setattr(runtime_module, "cursor_secret", lambda path: b"x" * 32)
    monkeypatch.setattr(runtime_module, "AIHistory", lambda *args: history)
    monkeypatch.setattr(runtime_module, "ExecutorLease", LeaseDouble)
    monkeypatch.setattr(runtime_module, "ScopedToolGateway", GatewayDouble)

    def runtime(engine=None):
        return runtime_module.AgentRuntime(backend, semantic=None, engine=engine)

    return SimpleNamespace(history=history, backend=backend, runtime=runtime)


@pytest.mark.parametrize("operation", ["plan", "start"])
def test_missing_model_does_not_bypass_owner_or_video_authorization(prepared, operation):
    prepared.history.context_error = ApiError(403, "forbidden", "当前用户或录像无权访问。")

    async def scenario():
        runtime = prepared.runtime()
        with pytest.raises(ApiError) as failure:
            if operation == "plan":
                await runtime.plan("controlled-test-token", "question_1", QUERY)
            else:
                await runtime.start("controlled-test-token", "new_run", "question_1", PLAN)
        assert failure.value.status_code == 403
        assert failure.value.code == "forbidden"
        assert prepared.history.context_calls == ["question_1"]
        assert prepared.history.records == {}

    asyncio.run(scenario())


@pytest.mark.parametrize("operation", ["plan", "start"])
def test_authorized_new_work_without_a_model_fails_explicitly(prepared, operation):
    async def scenario():
        runtime = prepared.runtime()
        with pytest.raises(ApiError) as failure:
            if operation == "plan":
                await runtime.plan("controlled-test-token", "question_1", QUERY)
            else:
                await runtime.start("controlled-test-token", "new_run", "question_1", PLAN)
        assert failure.value.status_code == 503
        assert failure.value.code == "model_unavailable"
        assert prepared.history.context_calls == ["question_1"]
        assert prepared.history.records == {}

    asyncio.run(scenario())


def test_existing_run_can_be_replayed_without_a_model_or_executor(prepared):
    prepared.history.records["existing_run"] = {
        "run_id": "existing_run", "input_message_id": "question_1",
        "plan": deepcopy(PLAN), "status": "completed", "summary": "历史答复",
        "references": [], "error": None,
    }

    async def scenario():
        runtime = prepared.runtime()
        result, status = await runtime.start(
            "controlled-test-token", "existing_run", "question_1", PLAN
        )
        assert status == 200
        assert result["status"] == "completed"
        assert result["summary"] == "历史答复"
        assert runtime.tasks == {}
        assert runtime.lease.checks == 0

    asyncio.run(scenario())


def test_scheduling_failure_compensates_the_durable_run(prepared, monkeypatch):
    discarded = []
    real_create_task = asyncio.create_task

    def cannot_schedule(coroutine, *args, **kwargs):
        if kwargs.get("name", "").startswith("agent:"):
            discarded.append(coroutine)
            raise RuntimeError("controlled task allocation failure")
        return real_create_task(coroutine, *args, **kwargs)

    async def scenario():
        runtime = prepared.runtime(FailingEngine())
        await runtime.open()
        try:
            monkeypatch.setattr(runtime_module.asyncio, "create_task", cannot_schedule)
            with pytest.raises(ApiError) as failure:
                await runtime.start("controlled-test-token", "unscheduled", "question_1", PLAN)
            assert failure.value.status_code == 503
            row = prepared.history.records["unscheduled"]
            assert row["status"] == "failed"
            assert row["summary"] is None and row["references"] == []
            assert runtime.tasks == {}
            assert "unscheduled" not in runtime._pending_failures
            assert discarded and all(
                inspect.getcoroutinestate(coroutine) == inspect.CORO_CLOSED
                for coroutine in discarded
            )
        finally:
            for coroutine in discarded:
                coroutine.close()
            await runtime.close()
        assert runtime.lease.closed

    asyncio.run(scenario())


def test_db_recovery_flushes_pending_failure_and_restores_capacity(prepared):
    async def scenario():
        runtime = prepared.runtime(FailingEngine())
        await runtime.open()
        try:
            result, status = await runtime.start(
                "controlled-test-token", "pending_run", "question_1", PLAN
            )
            assert status == 202 and result["status"] == "running"
            # The run committed successfully; the failure write then loses DB access.
            prepared.history.available = False
            task = runtime.tasks["pending_run"]
            await asyncio.wait_for(task, timeout=2)
            await asyncio.sleep(0)
            assert runtime.tasks == {}
            assert "pending_run" in runtime._pending_failures
            assert prepared.history.records["pending_run"]["status"] == "running"

            with pytest.raises(ApiError) as failure:
                await runtime.start("controlled-test-token", "blocked_run", "question_1", PLAN)
            assert failure.value.status_code == 503
            assert failure.value.code == "agent_busy"
            assert "blocked_run" not in prepared.history.records

            prepared.history.available = True
            await runtime.flush_failures()
            row = prepared.history.records["pending_run"]
            assert row["status"] == "failed"
            assert row["error"]["code"] == "provider_unavailable"
            assert row["summary"] is None and row["references"] == []
            assert runtime._pending_failures == {}

            # A recovered queue no longer consumes the only available slot.
            _, status = await runtime.start(
                "controlled-test-token", "after_recovery", "question_1", PLAN
            )
            assert status == 202
            await asyncio.wait_for(runtime.tasks["after_recovery"], timeout=2)
        finally:
            await runtime.close()
        assert runtime.lease.closed

    asyncio.run(scenario())

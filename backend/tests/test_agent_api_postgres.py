"""Enabled Agent API against real isolated PostgreSQL and an injected engine.

The engine is a scheduling/host-boundary test double. These tests do not claim
provider, LangGraph reasoning, or camera/Vision accuracy validation.
"""

import asyncio
import copy
import hashlib
import json
import threading
import time

from fastapi.testclient import TestClient
import pytest

from backend.app import create_app
from backend.core.errors import ApiError

from test_api_postgres import check, context, private_json


pytestmark = pytest.mark.integration
PREFIX = "/api/v1/agent"


class InjectedEngineStub:
    def __init__(self, *, gated=False, late_after_cancel=False, probe_escapes=False):
        self.gated = gated
        self.late_after_cancel = late_after_cancel
        self.probe_escapes = probe_escapes
        self.release = threading.Event()
        self.entered = threading.Event()
        self.cancelled = threading.Event()
        self.returned = threading.Event()
        self.plan_calls = 0
        self.execute_calls = 0
        self.reads = {}
        self.denied = {}

    async def plan(self, question, scope):
        self.plan_calls += 1
        return {"query": copy.deepcopy(scope), "query_text": question, "steps": ["检索事件", "检查关键帧引用"]}

    async def _wait(self):
        deadline = asyncio.get_running_loop().time() + 10
        while not self.release.is_set():
            if asyncio.get_running_loop().time() >= deadline:
                raise RuntimeError("test provider gate was not released")
            await asyncio.sleep(0.005)

    async def _must_deny(self, label, operation):
        try:
            await operation
        except ApiError as error:
            self.denied[label] = error.status_code
        else:
            self.denied[label] = 200

    async def execute(self, question, plan, gateway, *, run_id):
        self.execute_calls += 1
        query = plan["query"]
        self.reads["events"] = await gateway.search_events(query)
        self.reads["recording"] = await gateway.read_recording(query)
        if self.reads["events"]["events"]:
            event_id = self.reads["events"]["events"][0]["event_id"]
            self.reads["event"] = await gateway.get_event(query, event_id)
            frames = self.reads["event"]["event"]["evidence_ids"]
            if frames:
                self.reads["evidence"] = await gateway.get_evidence(query, frames[0])
        if self.probe_escapes:
            await self._must_deny("changed_window", gateway.search_events(query | {"end_offset_ms": query["end_offset_ms"] + 1}))
            await self._must_deny("changed_recording", gateway.search_events(query | {"recording_id": "other_recording"}))
            await self._must_deny("out_of_window_event", gateway.get_event(query, "outside_event"))
            await self._must_deny("other_recording_event", gateway.get_event(query, "other_event"))
            await self._must_deny("other_recording_frame", gateway.get_evidence(query, "other_frame"))
            await self._must_deny("changed_zone", gateway.read_recording(query | {"zone_id": "another_zone"}))
        self.entered.set()
        if self.gated:
            try:
                await self._wait()
            except asyncio.CancelledError:
                self.cancelled.set()
                if not self.late_after_cancel:
                    raise
                # Deliberately simulate a provider that returns after cancellation.
                await self._wait()
        self.returned.set()
        summary = "测试候选已检索，画面缺失；此测试不作事实判断。"
        return {
            "answer": {"summary": summary, "references": []},
            "cards": {
                "version": 1, "run_id": run_id, "query": copy.deepcopy(query),
                "cards": [{"kind": "run_summary", "title": "分析结论",
                           "summary": summary, "references": []}],
            },
        }


def settings(context, **overrides):
    return context["settings"].model_copy(update={
        "agent_enabled": True, "agent_cursor_secret_file": context["tmp"] / "agent.key",
        "agent_model": None, "agent_api_key": None, **overrides,
    })


def client_for(context, engine=None, **overrides):
    application = create_app(settings=settings(context, **overrides), agent_engine=engine)
    return TestClient(application, headers={"Authorization": "Bearer admin-test-secret"})


def question(client, *, conversation="conversation_api", message="question_api"):
    created = check(client.post(PREFIX + "/conversations", json={"conversation_id": conversation}), "ConversationResponse", 201)
    assert created["conversation"]["user_id"] == "admin"
    saved = check(client.post(PREFIX + f"/conversations/{conversation}/messages", json={"message_id": message, "content": "查找货架活动"}), "MessageResponse", 201)
    assert saved["message"]["role"] == "user"
    return message


def plan_for(client, context, message="question_api", scope=None):
    response = check(client.post(PREFIX + "/plans", json={"input_message_id": message, "scope": scope or context["query"]}), "AgentPlanResponse")
    return {key: response[key] for key in ("query", "query_text", "steps")}


def wait_run(client, run_id="run_api", *, terminal=True, headers=None):
    deadline = time.monotonic() + 5
    last = None
    while time.monotonic() < deadline:
        last = check(client.get(PREFIX + f"/runs/{run_id}", headers=headers), "AgentRunResponse")
        if (last["status"] != "running") == terminal:
            return last
        time.sleep(0.01)
    pytest.fail(f"run did not reach the expected state: {last['status'] if last else 'not read'}")


def wait_tasks(client):
    deadline = time.monotonic() + 5
    while client.app.state.agent_runtime.tasks and time.monotonic() < deadline:
        time.sleep(0.01)
    assert not client.app.state.agent_runtime.tasks


def test_all_eight_agent_operations_follow_authored_contract(context):
    engine = InjectedEngineStub(gated=True)
    try:
        with client_for(context, engine) as client:
            check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
            question(client)
            replay = check(client.post(PREFIX + "/conversations", json={"conversation_id": "conversation_api"}), "ConversationResponse")
            assert replay["replayed"]
            replay = check(client.post(PREFIX + "/conversations/conversation_api/messages", json={"message_id": "question_api", "content": "  查找货架活动  "}), "MessageResponse")
            assert replay["replayed"]
            check(client.get(PREFIX + "/conversations"), "ConversationListResponse")
            check(client.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")
            plan = plan_for(client, context)
            request = {"run_id": "run_api", "input_message_id": "question_api", "plan": plan}
            first = check(client.post(PREFIX + "/runs", json=request), "AgentRunResponse", 202)
            assert first["status"] == "running"
            assert engine.entered.wait(5)
            replay = check(client.post(PREFIX + "/runs", json=request), "AgentRunResponse")
            assert replay["run_id"] == first["run_id"]
            for identity in ("other", "restricted"):
                headers = {"Authorization": f"Bearer {identity}-test-secret"}
                check(client.get(PREFIX + "/runs/run_api", headers=headers), "ErrorResponse", 403)
                check(client.post(PREFIX + "/runs/run_api/stop", json={}, headers=headers), "ErrorResponse", 403)
                check(client.post(PREFIX + "/runs", json=request, headers=headers), "ErrorResponse", 403)
            changed = copy.deepcopy(request)
            changed["plan"]["query_text"] = "different query"
            check(client.post(PREFIX + "/runs", json=changed), "ErrorResponse", 409)
            engine.release.set()
            completed = wait_run(client)
            assert completed["status"] == "completed" and completed["summary"]
            assert engine.plan_calls == engine.execute_calls == 1
            stopped = check(client.post(PREFIX + "/runs/run_api/stop", json={}), "AgentRunResponse")
            assert stopped == completed
            messages = check(client.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")
            assert [message["role"] for message in messages["messages"]].count("assistant") == 1
            spec = client.get("/openapi.json").json()
            assert sum(len(value) for path, value in spec["paths"].items() if path.startswith(PREFIX + "/")) == 8
            assert sum(len(value) for value in spec["paths"].values()) == 22
            assert set(engine.reads) == {"events", "recording", "event", "evidence"}
        # A model outage must not make an already persisted result disappear or
        # schedule the same run again after restarting the application.
        with client_for(context) as no_model:
            assert check(no_model.post(PREFIX + "/runs", json=request), "AgentRunResponse") == completed
            assert engine.execute_calls == 1
    finally:
        engine.release.set()


def test_no_configured_model_keeps_history_available_and_rejects_analysis(context):
    with client_for(context) as client:
        check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
        question(client)
        check(client.get(PREFIX + "/conversations"), "ConversationListResponse")
        check(client.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")
        failed = check(client.post(PREFIX + "/plans", json={"input_message_id": "question_api", "scope": context["query"]}), "ErrorResponse", 503)
        assert failed["error"]["code"] == "model_unavailable"
        plan = {"query": context["query"], "query_text": "查找货架活动", "steps": ["查询"]}
        failed = check(client.post(PREFIX + "/runs", json={"run_id": "not_allocated", "input_message_id": "question_api", "plan": plan}), "ErrorResponse", 503)
        assert failed["error"]["code"] == "model_unavailable"
        check(client.get(PREFIX + "/runs/not_allocated"), "ErrorResponse", 404)
        assert len(check(client.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")["messages"]) == 1


def test_stop_prevents_late_provider_result_from_creating_reply(context):
    engine = InjectedEngineStub(gated=True, late_after_cancel=True)
    try:
        with client_for(context, engine) as client:
            check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
            question(client)
            request = {"run_id": "run_api", "input_message_id": "question_api", "plan": plan_for(client, context)}
            check(client.post(PREFIX + "/runs", json=request), "AgentRunResponse", 202)
            assert engine.entered.wait(5)
            stopped = check(client.post(PREFIX + "/runs/run_api/stop", json={}), "AgentRunResponse")
            assert stopped["status"] == "stopped" and stopped["summary"] is None
            assert engine.cancelled.wait(5)
            engine.release.set()
            wait_tasks(client)
            assert engine.returned.is_set()
            current = check(client.get(PREFIX + "/runs/run_api"), "AgentRunResponse")
            assert current == stopped
            assert check(client.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")["messages"][0]["role"] == "user"
            assert len(client.get(PREFIX + "/conversations/conversation_api/messages").json()["messages"]) == 1
    finally:
        engine.release.set()


def test_token_revocation_during_execution_fails_without_assistant(context):
    engine = InjectedEngineStub(gated=True)
    try:
        with client_for(context, engine) as client:
            check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
            question(client)
            request = {"run_id": "run_api", "input_message_id": "question_api", "plan": plan_for(client, context)}
            check(client.post(PREFIX + "/runs", json=request), "AgentRunResponse", 202)
            assert engine.entered.wait(5)
            registry = json.loads(context["settings"].auth_file.read_text())
            revoked = hashlib.sha256(b"admin-test-secret").hexdigest()
            registry["tokens"] = [token for token in registry["tokens"] if token["token_sha256"] != revoked]
            private_json(context["settings"].auth_file, registry)
            engine.release.set()
            owner_headers = {"Authorization": "Bearer readonly-test-secret"}
            failed = wait_run(client, headers=owner_headers)
            assert failed["status"] == "failed" and failed["summary"] is None
            assert failed["error"]["code"] == "unauthorized"
            check(client.get(PREFIX + "/runs/run_api"), "ErrorResponse", 401)
            messages = check(client.get(PREFIX + "/conversations/conversation_api/messages", headers=owner_headers), "MessageListResponse")
            assert len(messages["messages"]) == 1 and messages["messages"][0]["role"] == "user"
    finally:
        engine.release.set()


def test_single_executor_lease_and_restart_recovery_preserve_history_cursor(context):
    engine = InjectedEngineStub()
    with client_for(context, engine) as first:
        check(first.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
        question(first)
        check(first.post(PREFIX + "/conversations", json={"conversation_id": "another_conversation"}), "ConversationResponse", 201)
        page = check(first.get(PREFIX + "/conversations", params={"limit": 1}), "ConversationListResponse")
        assert page["next_cursor"]
        plan = plan_for(first, context)
        runtime = first.app.state.agent_runtime
        principal = first.app.state.backend.authenticate("admin-test-secret")
        # Simulate a process dying after durable allocation and before scheduling.
        allocated, created = runtime.history.start_run(principal, "orphan_run", "question_api", plan, "test-model")
        assert created and allocated["status"] == "running"
        with pytest.raises(ApiError) as failure:
            with client_for(context, InjectedEngineStub()):
                pytest.fail("second executor unexpectedly acquired the same schema lease")
        assert failure.value.code == "executor_in_use"
        assert check(first.get(PREFIX + "/runs/orphan_run"), "AgentRunResponse")["status"] == "running"
    with client_for(context, InjectedEngineStub()) as restarted:
        recovered = check(restarted.get(PREFIX + "/runs/orphan_run"), "AgentRunResponse")
        assert recovered["status"] == "failed" and recovered["error"]["code"] == "service_restarted"
        assert recovered["summary"] is None
        next_page = check(restarted.get(PREFIX + "/conversations", params={"limit": 1, "cursor": page["next_cursor"]}), "ConversationListResponse")
        assert len(next_page["conversations"]) == 1 and next_page["next_cursor"] is None
        assert next_page["conversations"][0]["conversation_id"] != page["conversations"][0]["conversation_id"]
        messages = check(restarted.get(PREFIX + "/conversations/conversation_api/messages"), "MessageListResponse")
        assert len(messages["messages"]) == 1


def test_gateway_keeps_exact_scope_and_blocks_other_recordings_and_outside_events(context):
    engine = InjectedEngineStub(probe_escapes=True)
    with client_for(context, engine) as client:
        manifest = copy.deepcopy(context["manifest"])
        outside = copy.deepcopy(manifest["events"][0])
        outside.update(event_id="outside_event", start_offset_ms=80000, end_offset_ms=90000)
        manifest["events"].append(outside)
        # Context frames may be outside the event/query while the event intersects.
        manifest["evidence"][0]["offset_ms"] = 1000
        check(client.post("/api/v1/recordings", json=manifest), "ImportResponse", 201)
        another = copy.deepcopy(context["manifest"])
        another["recording"]["recording_id"] = "other_recording"
        another["events"][0]["event_id"] = "other_event"
        another["evidence"][0].update(event_id="other_event", evidence_id="other_frame")
        check(client.post("/api/v1/recordings", json=another), "ImportResponse", 201)
        question(client)
        scope = context["query"] | {"start_offset_ms": 30000, "end_offset_ms": 40000}
        plan = plan_for(client, context, scope=scope)
        check(client.post(PREFIX + "/runs", json={"run_id": "run_api", "input_message_id": "question_api", "plan": plan}), "AgentRunResponse", 202)
        completed = wait_run(client)
        assert completed["status"] == "completed"
        assert engine.denied == {
            "changed_window": 403, "changed_recording": 403, "out_of_window_event": 403,
            "other_recording_event": 403, "other_recording_frame": 403, "changed_zone": 403,
        }
        assert engine.reads["evidence"]["evidence"]["offset_ms"] == 1000
        assert [event["event_id"] for event in engine.reads["events"]["events"]] == ["evt_mock_001"]


def test_agent_api_rejects_forged_fields_and_cross_owner_access(context):
    with client_for(context, InjectedEngineStub()) as client:
        check(client.post("/api/v1/recordings", json=context["manifest"]), "ImportResponse", 201)
        question(client)
        check(client.post(PREFIX + "/conversations", json={"conversation_id": "forged", "user_id": "other"}), "ErrorResponse", 422)
        check(client.post(PREFIX + "/conversations/conversation_api/messages", json={"message_id": "forged", "content": "answer", "role": "assistant"}), "ErrorResponse", 422)
        other = {"Authorization": "Bearer other-test-secret"}
        check(client.get(PREFIX + "/conversations/conversation_api/messages", headers=other), "ErrorResponse", 403)
        check(client.post(PREFIX + "/plans", json={"input_message_id": "question_api", "scope": context["query"]}, headers=other), "ErrorResponse", 403)
        check(client.get(PREFIX + "/conversations", params={"limit": "0"}), "ErrorResponse", 422)
        check(client.get(PREFIX + "/conversations?limit=1&limit=2"), "ErrorResponse", 422)

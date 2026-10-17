"""Opt-in PostgreSQL checks for AI ownership, paging and atomic run history.

No external model is called. Generated images/videos are explicitly test fixtures;
these checks prove storage and authorization behavior, not Vision or AI accuracy.
"""

import copy
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timezone
import io
import shutil
import subprocess

from jsonschema import Draft202012Validator, FormatChecker
from PIL import Image
import pytest

from backend.ai.history import AIHistory
from backend.core.errors import ApiError
from backend.core.models import Query, RecordingManifest
from backend.services.facts import BackendService

from test_api_postgres import context, SPEC


pytestmark = pytest.mark.integration


def validates(payload, schema):
    Draft202012Validator(
        {"$ref": f"#/components/schemas/{schema}", "components": SPEC["components"]},
        format_checker=FormatChecker(),
    ).validate(payload)
    return payload


def forbidden(callback, status=403):
    with pytest.raises(ApiError) as error:
        callback()
    assert error.value.status_code == status
    return error.value


@pytest.fixture
def history(context):
    service = BackendService(context["settings"])
    principal = service.authenticate("admin-test-secret")
    service.import_recording(principal, RecordingManifest.model_validate(context["manifest"]), {})
    store = AIHistory(service, b"isolated-ai-history-cursor-secret-32-bytes")
    value = {"store": store, "p": principal, "service": service, "context": context,
             "plan": {"query": context["query"], "query_text": "查找货架活动", "steps": ["查询事件", "核对画面"]}}
    yield value
    service.close()


def question(h, conversation="conversation_1", message="message_1", content="查找货架活动"):
    h["store"].create_conversation(h["p"], conversation)
    return h["store"].create_message(h["p"], conversation, message, content)[0]


def running(h, run="run_1", message="message_1", conversation="conversation_1"):
    question(h, conversation, message)
    return h["store"].start_run(h["p"], run, message, h["plan"], "test-model/config-v1")[0]


def cards_for(h, summary, references=(), extra_cards=(), run="run_1", query=None):
    return {
        "version": 1, "run_id": run, "query": copy.deepcopy(query or h["plan"]["query"]),
        "cards": [
            {"kind": "run_summary", "title": "分析结论", "summary": summary.strip(),
             "references": list(references)},
            *extra_cards,
        ],
    }


def test_owner_metadata_and_user_question_need_no_data_scope(history):
    h, store = history, history["store"]
    no_data = replace(h["p"], scopes=frozenset(), camera_ids=frozenset(), recording_ids=frozenset())
    response, status = store.create_conversation(no_data, "conversation_1")
    assert status == 201
    assert not validates(response, "ConversationResponse")["replayed"]
    replay, status = store.create_conversation(no_data, "conversation_1")
    assert status == 200 and replay["replayed"]
    saved, status = store.create_message(no_data, "conversation_1", "question_1", "  查找活动  ")
    assert status == 201
    assert validates(saved, "MessageResponse")["message"]["content"] == "查找活动"
    replay, status = store.create_message(no_data, "conversation_1", "question_1", "查找活动")
    assert status == 200 and replay["replayed"]
    assert len(validates(store.list_conversations(no_data), "ConversationListResponse")["conversations"]) == 1
    assert len(validates(store.list_messages(no_data, "conversation_1"), "MessageListResponse")["messages"]) == 1
    forbidden(lambda: store.create_message(no_data, "conversation_1", "question_1", "different"), 409)
    forbidden(lambda: store.create_message(no_data, "conversation_1", "blank", " \n "), 422)
    other = h["service"].authenticate("other-test-secret")
    forbidden(lambda: store.create_conversation(other, "conversation_1"))
    forbidden(lambda: store.list_messages(other, "conversation_1"))
    forbidden(lambda: store.create_message(other, "conversation_1", "forged", "question"))


def test_disabled_user_and_unknown_identity_cannot_open_history(history):
    h, store = history, history["store"]
    disabled = h["service"].authenticate("disabled-test-secret")
    unknown = h["service"].authenticate("nomap-test-secret")
    forbidden(lambda: store.create_conversation(disabled, "disabled_conversation"))
    forbidden(lambda: store.list_conversations(unknown), 401)


def test_conversation_and_message_ids_are_concurrently_idempotent(history):
    h, store = history, history["store"]
    with ThreadPoolExecutor(max_workers=3) as workers:
        responses = list(workers.map(lambda _: store.create_conversation(h["p"], "shared_conversation"), range(3)))
    assert sorted(status for _, status in responses) == [200, 200, 201]
    with ThreadPoolExecutor(max_workers=3) as workers:
        responses = list(workers.map(lambda _: store.create_message(h["p"], "shared_conversation", "shared_message", "same question"), range(3)))
    assert sorted(status for _, status in responses) == [200, 200, 201]
    assert len(store.list_messages(h["p"], "shared_conversation")["messages"]) == 1


def test_keyset_pagination_binds_owner_kind_conversation_limit_and_order(history, monkeypatch):
    import backend.ai.history as module
    monkeypatch.setattr(module, "now", lambda: datetime(2026, 10, 16, tzinfo=timezone.utc))
    h, store = history, history["store"]
    for identifier in ["c_1", "c_2", "c_3"]:
        store.create_conversation(h["p"], identifier)
    first = validates(store.list_conversations(h["p"], limit=2), "ConversationListResponse")
    assert [row["conversation_id"] for row in first["conversations"]] == ["c_3", "c_2"]
    second = store.list_conversations(h["p"], limit=2, cursor=first["next_cursor"])
    assert [row["conversation_id"] for row in second["conversations"]] == ["c_1"]
    assert second["next_cursor"] is None
    forbidden(lambda: store.list_conversations(h["p"], limit=1, cursor=first["next_cursor"]), 422)
    other = h["service"].authenticate("other-test-secret")
    forbidden(lambda: store.list_conversations(other, limit=2, cursor=first["next_cursor"]), 422)
    forbidden(lambda: store.list_messages(h["p"], "c_1", limit=2, cursor=first["next_cursor"]), 422)
    for identifier in ["m_1", "m_2", "m_3"]:
        store.create_message(h["p"], "c_1", identifier, identifier)
    messages = validates(store.list_messages(h["p"], "c_1", limit=2), "MessageListResponse")
    assert [row["message_id"] for row in messages["messages"]] == ["m_1", "m_2"]
    last = store.list_messages(h["p"], "c_1", limit=2, cursor=messages["next_cursor"])
    assert [row["message_id"] for row in last["messages"]] == ["m_3"]
    forbidden(lambda: store.list_messages(h["p"], "c_2", limit=2, cursor=messages["next_cursor"]), 422)
    forbidden(lambda: store.list_messages(h["p"], "c_1", limit=2, cursor=messages["next_cursor"] + "x"), 422)
    for invalid_limit in [0, 101, True, "2"]:
        forbidden(lambda: store.list_messages(h["p"], "c_1", limit=invalid_limit), 422)


def test_plan_context_requires_owned_user_message_and_current_video_grants(history):
    h, store = history, history["store"]
    question(h)
    query = Query.model_validate(h["plan"]["query"])
    assert store.plan_context(h["p"], "message_1", query) == "查找货架活动"
    other = h["service"].authenticate("other-test-secret")
    forbidden(lambda: store.plan_context(other, "message_1", query))
    restricted = replace(h["p"], recording_ids=frozenset())
    forbidden(lambda: store.plan_context(restricted, "message_1", query))
    forbidden(lambda: store.plan_context(replace(h["p"], scopes=frozenset()), "message_1", query))


def test_run_replays_entire_confirmed_plan_but_not_server_model_key(history):
    h, store = history, history["store"]
    first = validates(running(h), "AgentRunResponse")
    assert first["status"] == "running" and first["summary"] is None
    replay, created = store.start_run(h["p"], "run_1", "message_1", h["plan"], "new-server-model/config-v2")
    assert not created and replay == first
    changed = copy.deepcopy(h["plan"])
    changed["steps"].append("another step")
    forbidden(lambda: store.start_run(h["p"], "run_1", "message_1", changed, "model"), 409)
    other = h["service"].authenticate("other-test-secret")
    forbidden(lambda: store.get_run(other, "run_1"))
    restricted = replace(h["p"], recording_ids=frozenset())
    forbidden(lambda: store.start_run(restricted, "run_1", "message_1", h["plan"], "model"))
    forbidden(lambda: store.get_run(restricted, "run_1"))
    forbidden(lambda: store.stop_run(restricted, "run_1"))
    assert store.list_conversations(restricted)["conversations"]


def test_run_creation_is_concurrently_idempotent(history):
    h, store = history, history["store"]
    question(h)
    with ThreadPoolExecutor(max_workers=3) as workers:
        results = list(workers.map(lambda _: store.start_run(h["p"], "shared_run", "message_1", h["plan"], "model"), range(3)))
    assert sum(created for _, created in results) == 1
    assert {result["run_id"] for result, _ in results} == {"shared_run"}


def test_complete_is_atomic_unique_and_terminal(history):
    h, store = history, history["store"]
    running(h)
    complete = validates(store.complete_run(h["p"], "run_1", "  暂无可引用画面，仅返回候选。  ", [], cards_for(h, "暂无可引用画面，仅返回候选。")), "AgentRunResponse")
    assert complete["status"] == "completed" and complete["summary"] == "暂无可引用画面，仅返回候选。"
    assert complete["cards"]["cards"] == cards_for(h, "暂无可引用画面，仅返回候选。")["cards"]
    assert store.complete_run(h["p"], "run_1", "late rewrite", [], cards_for(h, "late rewrite")) == complete
    assert store.stop_run(h["p"], "run_1") == complete
    store.fail_run("run_1", "late_failure", "late callback")
    assert store.get_run(h["p"], "run_1") == complete
    page = validates(store.list_messages(h["p"], "conversation_1"), "MessageListResponse")
    replies = [message for message in page["messages"] if message["role"] == "assistant"]
    assert len(replies) == 1 and replies[0]["content"] == complete["summary"]
    forbidden(lambda: store.plan_context(h["p"], replies[0]["message_id"], Query.model_validate(h["plan"]["query"])), 422)
    forbidden(lambda: store.create_message(h["p"], "conversation_1", replies[0]["message_id"], replies[0]["content"]), 409)


def test_stop_wins_over_late_completion_without_fabricating_reply(history):
    h, store = history, history["store"]
    running(h)
    stopped = validates(store.stop_run(h["p"], "run_1"), "AgentRunResponse")
    assert stopped["status"] == "stopped" and stopped["summary"] is None and stopped["cards"] is None
    assert store.complete_run(h["p"], "run_1", "late result", [], cards_for(h, "late result")) == stopped
    assert store.stop_run(h["p"], "run_1") == stopped
    assert [message["role"] for message in store.list_messages(h["p"], "conversation_1")["messages"]] == ["user"]


def test_stop_complete_race_publishes_at_most_one_terminal_reply(history):
    h, store = history, history["store"]
    running(h)
    with ThreadPoolExecutor(max_workers=2) as workers:
        completion = workers.submit(store.complete_run, h["p"], "run_1", "complete answer", [], cards_for(h, "complete answer"))
        stopping = workers.submit(store.stop_run, h["p"], "run_1")
        results = [completion.result(), stopping.result()]
    assert len({result["status"] for result in results}) == 1
    current = store.get_run(h["p"], "run_1")
    replies = [message for message in store.list_messages(h["p"], "conversation_1")["messages"] if message["role"] == "assistant"]
    assert len(replies) == (1 if current["status"] == "completed" else 0)


def test_failed_and_restart_recovered_runs_keep_history_without_assistant(history):
    h, store = history, history["store"]
    running(h)
    store.fail_run("run_1", "model_unavailable", "模型暂不可用。")
    failed = validates(store.get_run(h["p"], "run_1"), "AgentRunResponse")
    assert failed["status"] == "failed" and failed["error"]["code"] == "model_unavailable"
    assert failed["summary"] is None and failed["references"] == [] and failed["cards"] is None
    replay, created = store.start_run(h["p"], "run_1", "message_1", h["plan"], "model")
    assert not created and replay == failed
    store.start_run(h["p"], "run_retry", "message_1", h["plan"], "model")
    assert store.recover_interrupted() == 1
    recovered = validates(store.get_run(h["p"], "run_retry"), "AgentRunResponse")
    assert recovered["error"]["code"] == "service_restarted" and recovered["summary"] is None
    assert store.recover_interrupted() == 0
    assert store.get_run(h["p"], "run_1") == failed
    assert len(store.list_messages(h["p"], "conversation_1")["messages"]) == 1


def test_message_page_is_selected_before_assistant_authorization(history):
    h, store = history, history["store"]
    running(h, message="00_question")
    store.complete_run(h["p"], "run_1", "No references are available.", [], cards_for(h, "No references are available."))
    no_data = replace(h["p"], scopes=frozenset(), recording_ids=frozenset())
    forbidden(lambda: store.list_messages(no_data, "conversation_1"))
    first = store.list_messages(no_data, "conversation_1", limit=1)
    assert len(first["messages"]) == 1 and first["messages"][0]["role"] == "user"
    assert first["next_cursor"]
    forbidden(lambda: store.list_messages(no_data, "conversation_1", limit=1, cursor=first["next_cursor"]))
    assert store.list_conversations(no_data)["conversations"]


def test_current_database_user_status_is_rechecked_at_completion(history):
    h, store = history, history["store"]
    running(h)
    with h["service"].transaction(h["p"]) as (connection, _):
        connection.execute("UPDATE users SET status='disabled' WHERE user_id=%s", (h["p"].user_id,))
    forbidden(lambda: store.complete_run(h["p"], "run_1", "should not publish", [], cards_for(h, "should not publish")))
    # Internal recovery remains possible when the owner is disabled.
    store.fail_run("run_1", "forbidden", "授权已变更。")


def test_invalid_reference_prevents_completion_and_reply(history):
    h, store = history, history["store"]
    running(h)
    forbidden(lambda: store.complete_run(h["p"], "run_1", "invalid citation", [{"event_id": "evt_mock_001", "evidence_id": "evd_mock_001"}], cards_for(h, "invalid citation")), 422)
    forbidden(lambda: store.complete_run(h["p"], "run_1", "invalid shape", [{"event_id": "evt_mock_001", "evidence_id": "evd_mock_001", "extra": "forged"}], cards_for(h, "invalid shape")), 422)
    assert store.get_run(h["p"], "run_1")["status"] == "running"
    assert len(store.list_messages(h["p"], "conversation_1")["messages"]) == 1


def test_assistant_insert_failure_rolls_back_completed_state(history):
    h, store = history, history["store"]
    running(h)
    with h["service"].transaction(h["p"]) as (connection, _):
        connection.execute(
            "CREATE TRIGGER reject_test_assistant BEFORE INSERT ON messages FOR EACH ROW "
            "WHEN (NEW.role='assistant') EXECUTE FUNCTION partrace_reject_change('deliberate test failure')",
        )
    forbidden(lambda: store.complete_run(h["p"], "run_1", "will roll back", [], cards_for(h, "will roll back")), 422)
    assert store.get_run(h["p"], "run_1")["status"] == "running"
    assert len(store.list_messages(h["p"], "conversation_1")["messages"]) == 1


def test_available_context_frame_can_be_cited_and_missing_history_keeps_reference(history):
    h, store = history, history["store"]
    ffprobe = h["context"]["settings"].ffprobe_path
    if not shutil.which(ffprobe) or not shutil.which("ffmpeg"):
        pytest.skip("requires actual ffprobe and ffmpeg for generated test video")
    path = h["context"]["tmp"] / "history-test.mp4"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=10:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", str(path)],
        check=True, timeout=30, capture_output=True,
    )
    image = io.BytesIO()
    Image.new("RGB", (64, 64), "blue").save(image, format="PNG")
    manifest = copy.deepcopy(h["context"]["manifest"])
    manifest["recording"].update(recording_id="real_test", source_mode="prerecorded", duration_ms=1000, file_name="clip.mp4")
    manifest["events"][0].update(event_id="real_event", detection_source="manual", start_offset_ms=100, end_offset_ms=600)
    manifest["evidence"][0].update(event_id="real_event", evidence_id="real_frame", offset_ms=800, file_name="frame.png")
    h["service"].import_recording(h["p"], RecordingManifest.model_validate(manifest), {"clip.mp4": path.read_bytes(), "frame.png": image.getvalue()})
    h["plan"] = copy.deepcopy(h["plan"])
    h["plan"]["query"].update(recording_id="real_test", start_offset_ms=200, end_offset_ms=400)
    running(h)
    references = [{"event_id": "real_event", "evidence_id": "real_frame"}]
    frame_item = {**references[0], "offset_ms": 800, "media_status": "available"}
    frame_path = next(h["service"].settings.media_root.rglob("*.png"))
    oversized_image = io.BytesIO()
    Image.new("1", (4097, 4096)).save(oversized_image, format="PNG")
    for damaged in (b"", image.getvalue()[:-12], b"not a decodable image", oversized_image.getvalue()):
        frame_path.write_bytes(damaged)
        forbidden(lambda: store.complete_run(h["p"], "run_1", "不能引用损坏的画面。", references, cards_for(h, "不能引用损坏的画面。", [frame_item])), 422)
        assert store.get_run(h["p"], "run_1")["status"] == "running"
    frame_path.write_bytes(image.getvalue())
    event_card = {
        "kind": "event_evidence", "title": "事件 real_event 证据", "event_id": "real_event",
        "event_type": "shelf_interaction", "observation_status": "candidate", "detection_source": "manual",
        "start_offset_ms": 100, "end_offset_ms": 600, "evidence": [frame_item],
    }
    # 媒体可用时，升格 observation_status 的卡片仍被落库前回查拒绝。
    upgraded = cards_for(h, "升格候选状态。", [frame_item], [{**event_card, "observation_status": "observed"}])
    forbidden(lambda: store.complete_run(h["p"], "run_1", "升格候选状态。", references, upgraded), 422)
    assert store.get_run(h["p"], "run_1")["status"] == "running"
    completed = validates(store.complete_run(h["p"], "run_1", "引用事件后的真实上下文帧。", references, cards_for(h, "引用事件后的真实上下文帧。", [frame_item], [event_card])), "AgentRunResponse")
    assert completed["references"] == references
    assert [card["kind"] for card in completed["cards"]["cards"]] == ["run_summary", "event_evidence"]
    assert completed["cards"]["cards"][0]["references"] == [frame_item]
    assert completed["cards"]["cards"][1]["observation_status"] == "candidate"
    frame_path.write_bytes(b"later corruption must preserve historical references")
    assert store.get_run(h["p"], "run_1")["references"] == references
    assert any(row["role"] == "assistant" for row in store.list_messages(h["p"], "conversation_1")["messages"])
    for frame in h["service"].settings.media_root.rglob("*.png"):
        frame.unlink()
    assert store.get_run(h["p"], "run_1")["references"] == references
    assert any(row["role"] == "assistant" for row in store.list_messages(h["p"], "conversation_1")["messages"])


def test_commit_rechecks_card_structure_and_ownership(history):
    h, store = history, history["store"]
    running(h)
    summary = "候选事件检索完成。"
    event_card = {
        "kind": "event_evidence", "title": "事件 evt_mock_001 证据", "event_id": "evt_mock_001",
        "event_type": "shelf_interaction", "observation_status": "candidate", "detection_source": "mock",
        "start_offset_ms": 32000, "end_offset_ms": 48000,
        "evidence": [{"event_id": "evt_mock_001", "evidence_id": "evd_mock_001",
                      "offset_ms": 35000, "media_status": "missing"}],
    }
    status_card = {"kind": "run_status", "title": "状态", "status": "stopped", "detail": "不允许。"}
    uncited = [{"event_id": "evt_mock_001", "evidence_id": "evd_mock_001",
                "offset_ms": 35000, "media_status": "missing"}]
    tampered = [
        cards_for(h, summary, run="run_2"),
        cards_for(h, "与答复不一致的摘要。"),
        cards_for(h, summary, query=h["plan"]["query"] | {"end_offset_ms": 60000}),
        {**cards_for(h, summary), "cards": []},
        cards_for(h, summary, references=uncited),
        cards_for(h, summary, extra_cards=[status_card]),
        cards_for(h, summary, extra_cards=[{**event_card, "observation_status": "observed"}]),
        cards_for(h, summary, extra_cards=[{**event_card, "end_offset_ms": 99999}]),
        cards_for(h, summary, extra_cards=[{**event_card, "evidence": [
            {"event_id": "evt_mock_001", "evidence_id": "evd_mock_001",
             "offset_ms": 35000, "media_status": "available"}]}]),
        cards_for(h, summary, extra_cards=[{**event_card, "evidence": [
            {"event_id": "evt_mock_002", "evidence_id": "evd_mock_001",
             "offset_ms": 35000, "media_status": "missing"}]}]),
    ]
    for bad in tampered:
        forbidden(lambda: store.complete_run(h["p"], "run_1", summary, [], bad), 422)
        assert store.get_run(h["p"], "run_1")["status"] == "running"
    # 不存在的卡片事件按资源缺失处理，同样不完成运行。
    missing_event = cards_for(h, summary, extra_cards=[{**event_card, "event_id": "evt_missing_404"}])
    forbidden(lambda: store.complete_run(h["p"], "run_1", summary, [], missing_event), 404)
    assert store.get_run(h["p"], "run_1")["status"] == "running"
    good = cards_for(h, summary, extra_cards=[event_card])
    completed = validates(store.complete_run(h["p"], "run_1", summary, [], good), "AgentRunResponse")
    assert completed["status"] == "completed"
    assert completed["cards"]["version"] == 1 and completed["cards"]["run_id"] == "run_1"
    assert [card["kind"] for card in completed["cards"]["cards"]] == ["run_summary", "event_evidence"]
    # 幂等重放与历史回读返回同一持久化卡片，不重新计算。
    assert store.complete_run(h["p"], "run_1", summary, [], good) == completed
    assert store.get_run(h["p"], "run_1")["cards"] == completed["cards"]

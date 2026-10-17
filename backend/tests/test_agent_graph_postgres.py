"""Real create_agent → five Backend tools → PG completion with generated media.

Only the chat responses and text encoder are deterministic test doubles. The
graph, tools, authorization, media upload, SQL and history are the implementation.
This is not external-model reasoning, real surveillance or VLM validation.
"""

import copy
import io
import json
import shutil
import subprocess
import time

from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage, ToolMessage
from PIL import Image
from pydantic import Field
import pytest

from agent import AgentEngine
from backend.app import create_app

from test_api_postgres import check, context

pytestmark = pytest.mark.integration


class ScriptedChat(FakeMessagesListChatModel):
    seen: list = Field(default_factory=list)

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        self.seen.extend(message for message in messages if isinstance(message, ToolMessage))
        return super()._generate(messages, stop=stop, run_manager=run_manager, **kwargs)


class FixedEncoder:
    model_name, model_version, dimensions, mock = "graph-test", "test-v1", 3, False

    def __init__(self):
        self.texts = []

    async def encode(self, text):
        self.texts.append(text)
        return [1, 0, 0]


def call(name, args=None):
    return AIMessage(content="", tool_calls=[{"name": name, "args": args or {}, "id": name, "type": "tool_call"}])


@pytest.mark.parametrize("forged", [False, True])
def test_actual_graph_with_all_five_tools_and_persistent_citations(context, forged):
    if not shutil.which("ffmpeg") or not shutil.which(context["settings"].ffprobe_path):
        pytest.skip("requires actual ffmpeg and ffprobe")
    video = context["tmp"] / "graph.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=10:d=1",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", str(video)], check=True, capture_output=True, timeout=30)
    image = io.BytesIO()
    Image.new("RGB", (64, 64), "blue").save(image, format="PNG")
    manifest = copy.deepcopy(context["manifest"])
    manifest["recording"].update(recording_id="graph_recording", source_mode="prerecorded", duration_ms=1000, file_name="clip.mp4")
    manifest["events"][0].update(event_id="graph_event", detection_source="manual", start_offset_ms=100, end_offset_ms=600)
    manifest["evidence"][0].update(event_id="graph_event", evidence_id="graph_frame", offset_ms=800, file_name="frame.png")
    query = context["query"] | {"recording_id": "graph_recording", "start_offset_ms": 200, "end_offset_ms": 400}
    references = [{"event_id": "graph_event", "evidence_id": "invented_frame" if forged else "graph_frame"}]
    model = ScriptedChat(responses=[
        call("DraftPlan", {"query_text": "人工标注测试事件", "steps": ["只读查询并回查引用"]}),
        call("read_recording"), call("search_events"), call("semantic_search_events", {"top_k": 3}),
        call("get_event", {"event_id": "graph_event"}), call("get_evidence", {"evidence_id": "graph_frame"}),
        call("CandidateAnswer", {"summary": "此为生成测试素材的人工标注候选，仅验证引用链，不作动作判断。", "references": references}),
    ])
    encoder = FixedEncoder()
    configuration = context["settings"].model_copy(update={"agent_enabled": True,
        "agent_cursor_secret_file": context["tmp"] / "graph.key"})
    with TestClient(create_app(settings=configuration, agent_engine=AgentEngine(model), text_encoder=encoder),
                    headers={"Authorization": "Bearer admin-test-secret"}) as client:
        check(client.post("/api/v1/recordings", files=[("manifest", (None, json.dumps(manifest))),
              ("files", ("clip.mp4", video.read_bytes(), "video/mp4")),
              ("files", ("frame.png", image.getvalue(), "image/png"))]), "ImportResponse", 201)
        check(client.post("/api/v1/event-embeddings", json={"embedding_id": "graph_embedding", "event_id": "graph_event",
            "evidence_id": "graph_frame", "source_text": "人工标注测试事件", "embedding": [1, 0, 0],
            "model_name": "graph-test", "model_version": "test-v1", "dimensions": 3}), "EventEmbeddingResponse", 201)
        prefix = "/api/v1/agent"
        check(client.post(prefix + "/conversations", json={"conversation_id": "graph_conversation"}), "ConversationResponse", 201)
        check(client.post(prefix + "/conversations/graph_conversation/messages", json={"message_id": "graph_question", "content": "查找这个区域的候选"}), "MessageResponse", 201)
        draft = check(client.post(prefix + "/plans", json={"input_message_id": "graph_question", "scope": query}), "AgentPlanResponse")
        plan = {key: draft[key] for key in ("query", "query_text", "steps")}
        check(client.post(prefix + "/runs", json={"run_id": "graph_run", "input_message_id": "graph_question", "plan": plan}), "AgentRunResponse", 202)
        deadline = time.monotonic() + 5
        while True:
            result = check(client.get(prefix + "/runs/graph_run"), "AgentRunResponse")
            if result["status"] != "running":
                break
            assert time.monotonic() < deadline, "actual graph did not finish"
            time.sleep(0.01)
        assert result["status"] == ("failed" if forged else "completed")
        messages = check(client.get(prefix + "/conversations/graph_conversation/messages"), "MessageListResponse")["messages"]
        assert len(messages) == (1 if forged else 2)
        if forged:
            assert result["error"]["code"] == "invalid_references"
            assert result["summary"] is None and result["references"] == [] and result["cards"] is None
        else:
            assert result["references"] == references
            assert messages[-1]["content"] == result["summary"]
            assert result["cards"]["run_id"] == "graph_run" and result["cards"]["query"] == query
            assert [card["kind"] for card in result["cards"]["cards"]] == ["run_summary", "event_evidence"]
            assert result["cards"]["cards"][0]["references"] == [
                {**references[0], "offset_ms": 800, "media_status": "available"}]
            assert result["cards"]["cards"][1]["observation_status"] == "candidate"
            assert client.get("/api/v1/evidence/graph_frame/media").content == image.getvalue()
        assert encoder.texts == [plan["query_text"]]
        names = {message.name for message in model.seen}
        assert {"read_recording", "search_events", "semantic_search_events", "get_event", "get_evidence"} <= names

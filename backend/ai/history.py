"""Backend-owned AI history and atomic run transitions.

The Agent receives this service, never database connections. Every public method
checks the supplied, freshly authenticated principal against the current DB user;
the caller must refresh token grants before asynchronous execution callbacks.
"""

import base64
import binascii
from contextlib import contextmanager
from datetime import datetime
import hashlib
import hmac
import json
from typing import Annotated
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb
from psycopg_pool import PoolTimeout
from pydantic import StringConstraints, TypeAdapter, ValidationError, model_validator

from backend.core.auth import Principal
from backend.core.errors import ApiError, invalid
from backend.core.models import Id, Query, StrictModel, UtcTime
from backend.services.facts import BackendService, canonical, envelope, now, one, utc


class _Plan(StrictModel):
    query: Query
    query_text: Annotated[str, StringConstraints(min_length=1, max_length=2000)]
    steps: list[Annotated[str, StringConstraints(min_length=1)]]

    @model_validator(mode="after")
    def normalize_text(self):
        self.query_text = self.query_text.strip()
        self.steps = [step.strip() for step in self.steps]
        if not self.query_text or any(not step for step in self.steps):
            raise ValueError("plan text must not be blank")
        return self


class _Reference(StrictModel):
    event_id: Id
    evidence_id: Id


def _id(value):
    try:
        return TypeAdapter(Id).validate_python(value)
    except ValidationError:
        raise invalid("资源标识格式无效。") from None


def _text(value, maximum):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise invalid("正文为空或超过长度限制。")
    return value.strip()


class AIHistory:
    def __init__(self, service: BackendService, cursor_secret: bytes):
        if not isinstance(cursor_secret, bytes) or len(cursor_secret) < 32:
            raise ApiError(503, "history_unavailable", "历史分页签名尚未正确配置。")
        self.service = service
        self._cursor_secret = cursor_secret

    @staticmethod
    def _lock(connection, kind, identifier):
        connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (f"ai:{kind}:{identifier}",))

    @staticmethod
    def _conversation(connection, principal, conversation_id):
        row = one(connection, "SELECT * FROM conversations WHERE conversation_id=%s", (conversation_id,), "conversation_not_found")
        if row["user_id"] != principal.user_id:
            raise ApiError(403, "forbidden", "不能访问其他用户的 AI 会话。")
        return row

    def _input(self, connection, principal, message_id):
        row = one(connection, "SELECT * FROM messages WHERE message_id=%s", (message_id,), "message_not_found")
        self._conversation(connection, principal, row["conversation_id"])
        if row["role"] != "user" or row["run_id"] is not None:
            raise invalid("分析输入必须是本人的用户问题。")
        return row

    @staticmethod
    def _public_conversation(row):
        return {"conversation_id": row["conversation_id"], "user_id": row["user_id"], "created_at": utc(row["created_at"])}

    @staticmethod
    def _public_message(row):
        return {key: row[key] for key in ("message_id", "conversation_id", "role", "content", "run_id")} | {"created_at": utc(row["created_at"])}

    def create_conversation(self, p: Principal, id: str):
        identifier = _id(id)
        with self.service.transaction(p) as (connection, _):
            self._lock(connection, "conversation", identifier)
            old = connection.execute("SELECT * FROM conversations WHERE conversation_id=%s", (identifier,)).fetchone()
            if old is not None:
                self._conversation(connection, p, identifier)
                return envelope(conversation=self._public_conversation(old), replayed=True), 200
            row = connection.execute(
                "INSERT INTO conversations(conversation_id,user_id,created_at) VALUES(%s,%s,%s) RETURNING *",
                (identifier, p.user_id, now()),
            ).fetchone()
            return envelope(conversation=self._public_conversation(row), replayed=False), 201

    def _page_binding(self, p, kind, conversation_id, limit):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise invalid("分页大小必须为 1 到 100 的整数。")
        return {"v": 1, "kind": kind, "owner": p.user_id, "conversation_id": conversation_id,
                "limit": limit, "order": "asc" if kind == "messages" else "desc"}

    def _encode_cursor(self, binding, row, identifier_key):
        payload = binding | {"after": [utc(row["created_at"]), row[identifier_key]]}
        encoded = base64.urlsafe_b64encode(canonical(payload)).rstrip(b"=").decode("ascii")
        signature = hmac.new(self._cursor_secret, encoded.encode("ascii"), hashlib.sha256).hexdigest()
        return encoded + "." + signature

    def _decode_cursor(self, cursor, binding):
        if cursor is None:
            return None
        try:
            if not isinstance(cursor, str) or not cursor or len(cursor) > 4096:
                raise ValueError("invalid cursor")
            encoded, supplied = cursor.split(".")
            signature = hmac.new(self._cursor_secret, encoded.encode("ascii"), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, supplied):
                raise ValueError("invalid signature")
            raw = base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True)
            payload = json.loads(raw)
            if not isinstance(payload, dict) or set(payload) != set(binding) | {"after"}:
                raise ValueError("invalid cursor shape")
            if any(payload[key] != value for key, value in binding.items()):
                raise ValueError("cursor belongs to another query")
            after = payload["after"]
            if not isinstance(after, list) or len(after) != 2:
                raise ValueError("invalid cursor position")
            timestamp = TypeAdapter(UtcTime).validate_python(after[0])
            identifier = TypeAdapter(Id).validate_python(after[1])
            return datetime.fromisoformat(timestamp.replace("Z", "+00:00")), identifier
        except (ValueError, TypeError, UnicodeError, binascii.Error, ValidationError):
            raise invalid("分页游标无效或不属于本次查询。") from None

    def list_conversations(self, p: Principal, limit=20, cursor=None):
        binding = self._page_binding(p, "conversations", None, limit)
        after = self._decode_cursor(cursor, binding)
        with self.service.transaction(p) as (connection, _):
            predicate, arguments = "", [p.user_id]
            if after is not None:
                predicate = " AND (created_at,conversation_id) < (%s,%s)"
                arguments.extend(after)
            rows = connection.execute(
                "SELECT * FROM conversations WHERE user_id=%s" + predicate +
                " ORDER BY created_at DESC,conversation_id DESC LIMIT %s", (*arguments, limit + 1),
            ).fetchall()
            page = rows[:limit]
            next_cursor = self._encode_cursor(binding, page[-1], "conversation_id") if len(rows) > limit else None
            return envelope(conversations=[self._public_conversation(row) for row in page], next_cursor=next_cursor)

    def create_message(self, p: Principal, conv_id, msg_id, content):
        conv_id, msg_id, content = _id(conv_id), _id(msg_id), _text(content, 2000)
        with self.service.transaction(p) as (connection, _):
            self._conversation(connection, p, conv_id)
            self._lock(connection, "message", msg_id)
            old = connection.execute("SELECT * FROM messages WHERE message_id=%s", (msg_id,)).fetchone()
            if old is not None:
                self._conversation(connection, p, old["conversation_id"])
                if old["conversation_id"] != conv_id or old["role"] != "user" or old["run_id"] is not None or old["content"] != content:
                    raise ApiError(409, "idempotency_conflict", "此消息 ID 已保存不同内容。")
                return envelope(message=self._public_message(old), replayed=True), 200
            row = connection.execute(
                "INSERT INTO messages(message_id,conversation_id,role,content,run_id,created_at) VALUES(%s,%s,'user',%s,NULL,%s) RETURNING *",
                (msg_id, conv_id, content, now()),
            ).fetchone()
            return envelope(message=self._public_message(row), replayed=False), 201

    def _authorize_run(self, connection, p, row):
        self._conversation(connection, p, row["conversation_id"])
        if "data:read" not in p.scopes:
            raise ApiError(403, "forbidden", "当前凭据没有读取本次分析的权限。")
        try:
            query = Query.model_validate(row["plan_json"]["query"])
        except (KeyError, TypeError, ValidationError):
            raise invalid("分析运行的查询范围无效。") from None
        self.service.normalize(connection, query, p)
        message = self._input(connection, p, row["input_message_id"])
        if message["conversation_id"] != row["conversation_id"]:
            raise invalid("分析输入与会话不一致。")
        return query

    def _references(self, connection, p, query, references, require_available=False):
        try:
            parsed = TypeAdapter(list[_Reference]).validate_python(references, strict=True)
        except ValidationError:
            raise invalid("证据引用字段无效。") from None
        if len(parsed) > self.service.settings.max_result_items:
            raise invalid("引用数量超过部署限额。")
        bundles, result, seen = {}, [], set()
        for reference in parsed:
            key = reference.event_id, reference.evidence_id
            if key in seen:
                raise invalid("证据引用不能重复。")
            seen.add(key)
            if reference.event_id not in bundles:
                bundles[reference.event_id] = self.service.event_bundle(connection, reference.event_id, p)
            bundle = bundles[reference.event_id]
            event = bundle["event"]
            if event["recording_id"] != query.recording_id or event["zone_id"] != query.zone_id or not self.service.hits(event, query):
                raise invalid("引用事件不属于本次分析窗口。")
            frame = next((frame for frame in bundle["evidence"] if frame["evidence_id"] == reference.evidence_id), None)
            if frame is None:
                raise invalid("关键帧不属于引用事件。")
            # Historical references survive missing files. Only new completed
            # answers must prove the referenced image is currently readable.
            if require_available and frame["media_status"] != "available":
                raise invalid("缺失画面不能作为已完成分析的证据引用。")
            result.append(reference.model_dump(mode="json"))
        return result

    def _run_response(self, connection, row):
        summary = None
        if row["status"] == "completed":
            reply = connection.execute(
                "SELECT * FROM messages WHERE run_id=%s AND role='assistant'", (row["run_id"],),
            ).fetchone()
            if reply is None or reply["conversation_id"] != row["conversation_id"]:
                raise ApiError(503, "history_unavailable", "已完成分析的历史答复不完整。")
            summary = reply["content"]
        return envelope(run_id=row["run_id"], conversation_id=row["conversation_id"], input_message_id=row["input_message_id"],
                        status=row["status"], plan=row["plan_json"], summary=summary, references=row["references_json"],
                        model_key=row["model_key"], error=row["error_json"], created_at=utc(row["created_at"]),
                        finished_at=utc(row["finished_at"]) if row["finished_at"] is not None else None)

    def list_messages(self, p: Principal, conv_id, limit=20, cursor=None):
        conv_id = _id(conv_id)
        binding = self._page_binding(p, "messages", conv_id, limit)
        after = self._decode_cursor(cursor, binding)
        with self.service.transaction(p) as (connection, _):
            self._conversation(connection, p, conv_id)
            predicate, arguments = "", [conv_id]
            if after is not None:
                predicate = " AND (created_at,message_id) > (%s,%s)"
                arguments.extend(after)
            rows = connection.execute(
                "SELECT * FROM messages WHERE conversation_id=%s" + predicate +
                " ORDER BY created_at ASC,message_id ASC LIMIT %s", (*arguments, limit + 1),
            ).fetchall()
            page = rows[:limit]
            # Do not filter based on video grants before selecting this page.
            for message in page:
                if message["role"] == "assistant":
                    row = one(connection, "SELECT * FROM runs WHERE run_id=%s", (message["run_id"],), "run_not_found")
                    query = self._authorize_run(connection, p, row)
                    if row["status"] != "completed" or row["conversation_id"] != conv_id:
                        raise invalid("助手历史与已完成运行不一致。")
                    self._references(connection, p, query, row["references_json"])
            next_cursor = self._encode_cursor(binding, page[-1], "message_id") if len(rows) > limit else None
            return envelope(messages=[self._public_message(row) for row in page], next_cursor=next_cursor)

    def plan_context(self, p: Principal, input_message_id, query: Query) -> str:
        message_id = _id(input_message_id)
        if not isinstance(query, Query):
            raise invalid("计划须使用规范的视频偏移查询。")
        with self.service.transaction(p, "data:read") as (connection, _):
            message = self._input(connection, p, message_id)
            self.service.normalize(connection, query, p)
            return message["content"]

    def start_run(self, p: Principal, run_id, input_message_id, plan: dict, model_key: str):
        run_id, input_message_id, model_key = _id(run_id), _id(input_message_id), _text(model_key, 200)
        try:
            normalized = _Plan.model_validate(plan).model_dump(mode="json")
        except ValidationError:
            raise invalid("最终确认计划字段无效。") from None
        with self.service.transaction(p, "data:read") as (connection, _):
            self._lock(connection, "run", run_id)
            old = connection.execute("SELECT * FROM runs WHERE run_id=%s", (run_id,)).fetchone()
            if old is not None:
                query = self._authorize_run(connection, p, old)
                self._references(connection, p, query, old["references_json"])
                if old["input_message_id"] != input_message_id or old["plan_json"] != normalized:
                    raise ApiError(409, "idempotency_conflict", "此运行 ID 已绑定不同问题或计划。")
                return self._run_response(connection, old), False
            message = self._input(connection, p, input_message_id)
            self.service.normalize(connection, Query.model_validate(normalized["query"]), p)
            row = connection.execute(
                "INSERT INTO runs(run_id,conversation_id,input_message_id,status,plan_json,references_json,model_key,error_json,created_at,finished_at) "
                "VALUES(%s,%s,%s,'running',%s,%s,%s,NULL,%s,NULL) RETURNING *",
                (run_id, message["conversation_id"], input_message_id, Jsonb(normalized), Jsonb([]), model_key, now()),
            ).fetchone()
            return self._run_response(connection, row), True

    def get_run(self, p: Principal, run_id):
        with self.service.transaction(p, "data:read") as (connection, _):
            row = one(connection, "SELECT * FROM runs WHERE run_id=%s", (_id(run_id),), "run_not_found")
            query = self._authorize_run(connection, p, row)
            self._references(connection, p, query, row["references_json"])
            return self._run_response(connection, row)

    def stop_run(self, p: Principal, run_id):
        with self.service.transaction(p, "data:read") as (connection, _):
            row = one(connection, "SELECT * FROM runs WHERE run_id=%s FOR UPDATE", (_id(run_id),), "run_not_found")
            query = self._authorize_run(connection, p, row)
            self._references(connection, p, query, row["references_json"])
            if row["status"] == "running":
                row = connection.execute(
                    "UPDATE runs SET status='stopped',finished_at=%s WHERE run_id=%s AND status='running' RETURNING *",
                    (max(now(), row["created_at"]), row["run_id"]),
                ).fetchone()
            return self._run_response(connection, row)

    def complete_run(self, p: Principal, run_id, summary: str, references: list[dict]):
        with self.service.transaction(p, "data:read") as (connection, _):
            row = one(connection, "SELECT * FROM runs WHERE run_id=%s FOR UPDATE", (_id(run_id),), "run_not_found")
            query = self._authorize_run(connection, p, row)
            if row["status"] != "running":
                self._references(connection, p, query, row["references_json"])
                return self._run_response(connection, row)
            summary = _text(summary, 4000)
            references = self._references(connection, p, query, references, require_available=True)
            finished = max(now(), row["created_at"])
            row = connection.execute(
                "UPDATE runs SET status='completed',references_json=%s,finished_at=%s WHERE run_id=%s AND status='running' RETURNING *",
                (Jsonb(references), finished, row["run_id"]),
            ).fetchone()
            connection.execute(
                "INSERT INTO messages(message_id,conversation_id,role,content,run_id,created_at) VALUES(%s,%s,'assistant',%s,%s,%s)",
                ("reply_" + uuid4().hex, row["conversation_id"], summary, row["run_id"], finished),
            )
            return self._run_response(connection, row)

    @contextmanager
    def _internal_transaction(self):
        try:
            with self.service.pool.connection() as connection:
                with connection.transaction():
                    yield connection
        except (psycopg.OperationalError, psycopg.InterfaceError, PoolTimeout):
            raise ApiError(503, "database_unavailable", "数据库暂时不可用，请稍后重试。") from None
        except psycopg.IntegrityError:
            raise invalid("分析历史约束校验失败。") from None

    @staticmethod
    def _failure(code, message):
        return {"code": _text(code, 100), "message": _text(message, 1000), "request_id": uuid4().hex}

    def fail_run(self, run_id, code, message) -> None:
        error = self._failure(code, message)
        with self._internal_transaction() as connection:
            connection.execute(
                "UPDATE runs SET status='failed',references_json=%s,error_json=%s,finished_at=GREATEST(created_at,%s) WHERE run_id=%s AND status='running'",
                (Jsonb([]), Jsonb(error), now(), _id(run_id)),
            )

    def recover_interrupted(self) -> int:
        # The caller must first hold the single-executor session advisory lock.
        # Never use this scan to recover a live worker owned by another instance.
        error = self._failure("service_restarted", "服务已重启，此次未完成分析不会自动续跑。")
        with self._internal_transaction() as connection:
            rows = connection.execute(
                "UPDATE runs SET status='failed',references_json=%s,error_json=%s,finished_at=GREATEST(created_at,%s) WHERE status='running' RETURNING run_id",
                (Jsonb([]), Jsonb(error), now()),
            ).fetchall()
            return len(rows)

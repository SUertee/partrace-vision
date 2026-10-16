"""Backend-owned, scoped read facade. No DB connection escapes to the Agent."""

from starlette.concurrency import run_in_threadpool

from backend.core.errors import ApiError
from backend.core.models import Query
from backend.services.facts import envelope, one


class ScopedToolGateway:
    def __init__(self, backend, semantic, token: str, owner: str, query: dict, query_text: str | None = None):
        self.backend = backend
        self.semantic = semantic
        self._token = token
        self._owner = owner
        self._query = Query.model_validate(query)
        self._query_text = query_text

    def principal(self):
        # Refresh the credential registry, not merely the previous user snapshot.
        principal = self.backend.authenticate(self._token)
        if principal.user_id != self._owner:
            raise ApiError(403, "forbidden", "此次分析的身份已失效。")
        return principal

    def _scope(self, value):
        query = Query.model_validate(value)
        if query != self._query:
            raise ApiError(403, "forbidden", "工具不能修改已确认的查询范围。")
        return query

    def _authorize_event(self, connection, p, query, event):
        self.backend.normalize(connection, query, p)
        if (event["recording_id"] != query.recording_id or event["zone_id"] != query.zone_id
                or not self.backend.hits(event, query)):
            raise ApiError(403, "forbidden", "事件不属于此次分析范围。")

    async def search_events(self, query):
        fixed = self._scope(query)
        return await run_in_threadpool(lambda: self.backend.list_events(self.principal(), fixed))

    async def semantic_search_events(self, query, query_text, top_k, min_score=None):
        fixed = self._scope(query)
        if self._query_text is not None and query_text != self._query_text:
            raise ApiError(403, "forbidden", "语义工具不能修改已确认的检索文本。")
        result = await self.semantic.search(await run_in_threadpool(self.principal), query_text, fixed, top_k, min_score)
        # A provider call may have taken time; revoked grants must not expose results.
        await run_in_threadpool(self._validate_scope)
        return result

    def _validate_scope(self):
        p = self.principal()
        with self.backend.transaction(p, "data:read") as (connection, _):
            self.backend.normalize(connection, self._query, p)
        return p

    async def get_event(self, query, event_id):
        fixed = self._scope(query)

        def read():
            p = self.principal()
            with self.backend.transaction(p, "data:read") as (connection, _):
                event = one(connection, "SELECT * FROM events WHERE event_id=%s", (event_id,), "event_not_found")
                self._authorize_event(connection, p, fixed, event)
                return self.backend.event_bundle(connection, event_id, p)

        return await run_in_threadpool(read)

    async def get_evidence(self, query, evidence_id):
        fixed = self._scope(query)

        def read():
            p = self.principal()
            with self.backend.transaction(p, "data:read") as (connection, _):
                frame = one(connection, "SELECT * FROM evidence WHERE evidence_id=%s", (evidence_id,), "evidence_not_found")
                event = one(connection, "SELECT * FROM events WHERE event_id=%s", (frame["event_id"],), "event_not_found")
                self._authorize_event(connection, p, fixed, event)
                return envelope(evidence=self.backend.evidence(frame))

        return await run_in_threadpool(read)

    async def read_recording(self, query):
        fixed = self._scope(query)

        def read():
            p = self.principal()
            with self.backend.transaction(p, "data:read") as (connection, _):
                _, recording = self.backend.normalize(connection, fixed, p)
                return envelope(recording=self.backend.public_recording(recording))

        return await run_in_threadpool(read)

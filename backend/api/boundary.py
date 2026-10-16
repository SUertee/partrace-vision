"""ASGI request IDs and streaming upload bounds (including chunked requests)."""

from uuid import uuid4
from starlette.responses import JSONResponse
from backend.core.errors import API_VERSION, ApiError


class RequestBoundary:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        request_id = uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        settings = scope["app"].state.settings
        limit = settings.max_upload_bytes + settings.max_manifest_bytes + 1024 * 1024
        received = 0
        started = False

        async def bounded_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise ApiError(413, "payload_too_large", "上传超过部署限额。")
            return message

        async def identified_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                message.setdefault("headers", []).append((b"x-request-id", request_id.encode()))
            await send(message)

        headers = dict(scope.get("headers", []))
        try:
            length = headers.get(b"content-length")
            if length is not None and (not length.isdigit() or int(length) > limit):
                raise ApiError(413, "payload_too_large", "上传超过部署限额。")
            if settings.environment != "development" and scope["path"] in {"/docs", "/openapi.json"}:
                response = JSONResponse({"detail": "Not Found"}, status_code=404)
                return await response(scope, bounded_receive, identified_send)
            await self.app(scope, bounded_receive, identified_send)
        except ApiError as error:
            if started:
                raise
            response = JSONResponse({"api_version": API_VERSION, "error": {"code": error.code, "message": error.message, "request_id": request_id}}, status_code=error.status_code)
            await response(scope, receive, identified_send)

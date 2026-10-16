"""ASGI runtime and PostgreSQL data API; Agent remains explicitly optional."""

from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
import json

from fastapi import APIRouter, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

from backend import __version__
from backend.core.settings import Settings
from backend.storage.database import DatabaseReadiness, check_database
from backend.api import register_agent_router
from backend.api.facts import router as data_router
from backend.api.semantic import router as semantic_router
from backend.services.semantic import SemanticService
from backend.core.errors import API_VERSION, ApiError
from backend.api.boundary import RequestBoundary
from backend.services.facts import BackendService

ReadinessProbe = Callable[[], Awaitable[DatabaseReadiness]]


def create_app(
    *,
    settings: Settings | None = None,
    readiness_probe: ReadinessProbe | None = None,
    agent_router: APIRouter | None = None,
    agent_engine=None,
    text_encoder=None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        # Configuration errors stop startup; a later database outage only fails readiness.
        configuration = settings if settings is not None else Settings()
        application.state.settings = configuration

        async def database_probe() -> DatabaseReadiness:
            return await check_database(configuration)

        application.state.readiness_probe = readiness_probe or database_probe
        application.state.backend = BackendService(configuration)
        application.state.agent_runtime = None
        try:
            from backend.ai.providers import make_encoder
            application.state.semantic = SemanticService(application.state.backend, text_encoder or make_encoder(configuration))
            if configuration.agent_enabled:
                if agent_router is not None:
                    raise ValueError("choose the built-in Agent runtime or an external Agent router")
                from backend.api.agent import router as builtin_agent_router
                from backend.ai.runtime import AgentRuntime
                from backend.ai.providers import make_engine
                application.state.agent_runtime = AgentRuntime(application.state.backend, application.state.semantic,
                                                               agent_engine or make_engine(configuration))
                await application.state.agent_runtime.open()
                if not getattr(application.state, "agent_routes_mounted", False):
                    register_agent_router(application, builtin_agent_router)
                    implemented_operations.update(("/api/v1/agent" + route.path, method.lower())
                                                  for route in builtin_agent_router.routes for method in route.methods)
                    application.state.agent_routes_mounted = True
            yield
        finally:
            if application.state.agent_runtime is not None:
                await application.state.agent_runtime.close()
            application.state.backend.close()

    application = FastAPI(
        title="ParTrace Vision Backend MVP",
        version=__version__,
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )

    application.add_middleware(RequestBoundary)
    application.include_router(data_router)
    application.include_router(semantic_router)
    # FastAPI uses lazy included-router wrappers; retain the actual mounted leaf
    # operations rather than inferring them from application.routes wrappers.
    implemented_operations = {(route.path, method.lower()) for router in (data_router, semantic_router)
                              for route in router.routes for method in route.methods}

    def error_response(request, status, code, message):
        headers = {"WWW-Authenticate": "Bearer"} if status == 401 else None
        return JSONResponse({"api_version": API_VERSION, "error": {"code": code, "message": message,
                            "request_id": getattr(request.state, "request_id", "unavailable")}}, status_code=status, headers=headers)

    @application.exception_handler(ApiError)
    async def domain_error(request, error):
        return error_response(request, error.status_code, error.code, error.message)

    @application.exception_handler(ValidationError)
    @application.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        return error_response(request, 422, "validation_error", "请求字段、类型、范围或关联无效。")

    @application.exception_handler(HTTPException)
    async def http_error(request, error):
        return error_response(request, error.status_code, "invalid_request", "请求无法处理。")

    @application.exception_handler(Exception)
    async def unexpected_error(request, error):
        return error_response(request, 500, "internal_error", "服务处理失败，未确认发布成功。")

    def runtime_openapi():
        # Reuse the authored contract instead of publishing all 27 draft routes
        # or a misleading inferred dict schema. Business fields have one source.
        path = application.state.settings.schema_path.parent.parent / "api/openapi.json"
        try:
            contract = json.loads(path.read_text())
        except (OSError, ValueError):
            raise ApiError(503, "contract_unavailable", "接口文档暂不可用。") from None
        implemented = implemented_operations
        contract["paths"] = {path: {method: op for method, op in operations.items() if (path, method) in implemented}
                             for path, operations in contract["paths"].items() if any((path, method) in implemented for method in operations)}
        count = sum(len(operations) for operations in contract["paths"].values())
        contract["info"]["title"] = f"ParTrace Vision Backend MVP — {count} implemented operations"
        contract["servers"] = [{"url": "/"}]
        return contract

    application.openapi = runtime_openapi

    @application.get("/health/live")
    async def liveness() -> dict[str, str]:
        return {"status": "ok"}

    @application.get("/health/ready")
    async def readiness(request: Request, response: Response) -> dict[str, object]:
        result = await request.app.state.readiness_probe()
        response.status_code = 200 if result.ready else 503
        return {
            "status": "ready" if result.ready else "not_ready",
            "checks": result.checks(),
        }

    if agent_router is not None:
        register_agent_router(application, agent_router)

    return application


app = create_app()

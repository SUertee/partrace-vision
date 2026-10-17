"""Single-executor lifecycle. PostgreSQL is the only authority for run states."""

import asyncio
import os
from pathlib import Path
import secrets
import stat

import psycopg
from starlette.concurrency import run_in_threadpool

from agent.engine import AgentExecutionError
from agent.models import CandidateAnswer
from backend.ai.gateway import ScopedToolGateway
from backend.ai.history import AIHistory
from backend.ai.push import RunPushBroker
from backend.core.errors import ApiError
from backend.core.models import Query
from backend.storage.credentials import connection_class


def cursor_secret(path: Path) -> bytes:
    """Create once on explicit enable; stable, private, symlink-safe across restarts."""
    descriptor = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(descriptor, "wb") as stream:
                descriptor = None
                stream.write(secrets.token_bytes(32))
                stream.flush()
                os.fsync(stream.fileno())
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077
                or metadata.st_uid not in {0, os.geteuid()} or metadata.st_size != 32):
            raise ValueError("unprotected history cursor key")
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = None
            return stream.read(32)
    except (OSError, ValueError):
        raise ApiError(503, "history_unavailable", "历史分页签名文件不可用，须为受保护的 32 字节文件。") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


class ExecutorLease:
    """A dedicated session lock prevents a second process recovering live runs."""

    def __init__(self, settings):
        self.settings = settings
        self.connection = None

    def acquire(self):
        try:
            self.connection = connection_class(self.settings).connect(
                self.settings.database_url.get_secret_value(), autocommit=True,
                connect_timeout=self.settings.database_timeout_seconds,
                application_name="partrace-agent-executor",
                options="-c statement_timeout=2000",
            )
            locked = self.connection.execute(
                "SELECT pg_try_advisory_lock(hashtextextended(current_database() || %s,0))",
                (":partrace-agent:" + self.settings.database_schema,),
            ).fetchone()[0]
            if not locked:
                self.close()
                raise ApiError(503, "executor_in_use", "此数据库已有 Agent 执行进程；首版须使用单进程。")
        except (psycopg.Error, OSError):
            self.close()
            raise ApiError(503, "database_unavailable", "无法取得 Agent 执行锁。") from None

    def check(self):
        try:
            if self.connection is None:
                raise ValueError("executor not started")
            self.connection.execute("SELECT 1")
        except (psycopg.Error, OSError, ValueError):
            raise ApiError(503, "executor_unavailable", "Agent 执行连接已中断。") from None

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None


class AgentRuntime:
    def __init__(self, backend, semantic, engine=None):
        self.backend = backend
        self.semantic = semantic
        self.engine = engine
        self.history = AIHistory(backend, cursor_secret(backend.settings.agent_cursor_secret_file))
        self.lease = ExecutorLease(backend.settings)
        self.push = RunPushBroker()
        self.model_key = ((backend.settings.agent_model or "unconfigured") + "@" + backend.settings.agent_model_version)
        self.tasks: dict[str, asyncio.Task] = {}
        self._start_lock = asyncio.Lock()
        self._closing = False
        self._pending_failures: dict[str, tuple[str, str]] = {}
        self._wake = asyncio.Event()
        self._reconciler = None

    async def open(self):
        await run_in_threadpool(self.lease.acquire)
        try:
            recovered = await run_in_threadpool(self.history.recover_interrupted)
            self._reconciler = asyncio.create_task(self._reconcile_loop(), name="agent-reconciler")
            return recovered
        except BaseException:
            await run_in_threadpool(self.lease.close)
            raise

    async def close(self):
        self._closing = True
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        try:
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
        finally:
            if self._reconciler is not None:
                self._reconciler.cancel()
                await asyncio.gather(self._reconciler, return_exceptions=True)
            await self.flush_failures()
            await run_in_threadpool(self.lease.close)

    def _principal(self, token):
        return self.backend.authenticate(token)

    def _require_engine(self):
        if self.engine is None:
            raise ApiError(503, "model_unavailable", "Agent 模型未配置；请在服务端设置模型、接口和密钥。")

    async def plan(self, token, input_message_id, scope):
        query = Query.model_validate(scope)
        p = await run_in_threadpool(self._principal, token)
        question = await run_in_threadpool(self.history.plan_context, p, input_message_id, query)
        self._require_engine()
        try:
            result = await self.engine.plan(question, query.model_dump(mode="json"))
        except AgentExecutionError as error:
            raise ApiError(503, error.code, "计划生成失败，未执行检索。") from None
        fresh = await run_in_threadpool(self._principal, token)
        if fresh.user_id != p.user_id:
            raise ApiError(403, "forbidden", "计划生成期间身份已变更。")
        await run_in_threadpool(self.history.plan_context, fresh, input_message_id, query)
        if result.get("query") != query.model_dump(mode="json"):
            raise ApiError(503, "invalid_plan", "模型未遵守固定查询范围。")
        return result

    async def start(self, token, run_id, input_message_id, plan):
        # Serialize allocation and capacity check, while DB handles cross-request ID replay.
        async with self._start_lock:
            p = await run_in_threadpool(self._principal, token)
            try:
                old = await run_in_threadpool(self.history.get_run, p, run_id)
            except ApiError as error:
                if error.code != "run_not_found":
                    raise
            else:
                replay, _ = await run_in_threadpool(self.history.start_run, p, run_id, input_message_id, plan, self.model_key)
                return replay, 200
            question = await run_in_threadpool(self.history.plan_context, p, input_message_id, Query.model_validate(plan["query"]))
            self._require_engine()
            if self._closing or len(self.tasks) + len(self._pending_failures) >= self.backend.settings.agent_max_concurrent_runs:
                raise ApiError(503, "agent_busy", "当前分析已达并发限额，请稍后重试。")
            await run_in_threadpool(self.lease.check)
            run, created = await run_in_threadpool(self.history.start_run, p, run_id, input_message_id, plan, self.model_key)
            if not created:
                return run, 200
            execution = self._execute(token, p.user_id, question, run)
            try:
                task = asyncio.create_task(execution, name="agent:" + run_id)
                self.tasks[run_id] = task
            except Exception:
                execution.close()
                await self._fail(run_id, "agent_execution_failed", "分析任务无法启动，未保存助手答复。")
                raise ApiError(503, "agent_execution_failed", "分析任务无法启动。") from None
            def finished(done):
                self.tasks.pop(run_id, None)
                # Consume only background-task exceptions; no credentials or provider errors in logs.
                if not done.cancelled():
                    done.exception()
            task.add_done_callback(finished)
            return run, 202

    async def stop(self, token, run_id):
        p = await run_in_threadpool(self._principal, token)
        result = await run_in_threadpool(self.history.stop_run, p, run_id)
        if result["status"] == "stopped":
            # stop_run returned after its commit; subscribers re-read the row.
            self.push.publish(run_id)
            if run_id in self.tasks:
                self.tasks[run_id].cancel()
        return result

    async def _fail(self, run_id, code, message):
        # Internal failure never publishes an answer, even after credential revocation.
        self._pending_failures[run_id] = (code, message)
        try:
            await run_in_threadpool(self.history.fail_run, run_id, code, message)
        except ApiError:
            # Keep ownership until DB returns. A process crash is handled by the
            # next exclusive executor's recovery, never by automatically resuming.
            self._wake.set()
        else:
            self._pending_failures.pop(run_id, None)
            self.push.publish(run_id)

    async def flush_failures(self):
        for run_id, (code, message) in list(self._pending_failures.items()):
            try:
                await run_in_threadpool(self.lease.check)
                await run_in_threadpool(self.history.fail_run, run_id, code, message)
            except ApiError:
                continue
            self._pending_failures.pop(run_id, None)
            self.push.publish(run_id)

    async def _reconcile_loop(self):
        while not self._closing:
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=5)
            except TimeoutError:
                pass
            self._wake.clear()
            await self.flush_failures()

    async def _execute(self, token, owner, question, run):
        run_id = run["run_id"]
        gateway = ScopedToolGateway(self.backend, self.semantic, token, owner, run["plan"]["query"], run["plan"]["query_text"])
        try:
            result = await self.engine.execute(question, run["plan"], gateway, run_id=run_id)
            answer = CandidateAnswer.model_validate(result["answer"])
            await run_in_threadpool(self.lease.check)
            p = await run_in_threadpool(gateway.principal)
            await run_in_threadpool(self.history.complete_run, p, run_id, answer.summary,
                                    [reference.model_dump(mode="json") for reference in answer.references],
                                    result["cards"])
            self.push.publish(run_id)
        except asyncio.CancelledError:
            await self._fail(run_id, "service_stopped", "服务已停止此次分析，不会自动续跑。")
            raise
        except AgentExecutionError as error:
            await self._fail(run_id, error.code, "分析未完成，未保存助手答复；请检查模型或证据后重新发起。")
        except ApiError as error:
            await self._fail(run_id, error.code, "分析期间权限、数据或服务状态已变化，未保存答复。")
        except Exception:
            await self._fail(run_id, "agent_execution_failed", "分析处理失败，未保存助手答复。")

"""Bounded create_agent execution; no persistence, checkpoint or mock fallback."""

import asyncio
from dataclasses import dataclass
import json
import math
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, ToolCallLimitMiddleware
from langchain.agents.structured_output import ToolStrategy
from langchain_core.language_models import BaseChatModel
from langsmith import tracing_context
from pydantic import ValidationError

from agent.contracts import ToolGateway
from agent.models import CandidateAnswer, ConfirmedPlan, DraftPlan, QueryScope
from agent.prompts import EXECUTION_PROMPT as _EXECUTION_PROMPT, PLAN_PROMPT as _PLAN_PROMPT
from agent.tools import InvalidReferences, InvalidToolResult, build_tools


class AgentExecutionError(RuntimeError):
    """Stable public-safe failure code; underlying provider details stay private."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class AgentLimits:
    timeout_seconds: float = 60
    model_calls: int = 8
    tool_calls: int = 12
    recursion_limit: int = 30

    def __post_init__(self) -> None:
        if not math.isfinite(self.timeout_seconds) or self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive and finite")
        for value in (self.model_calls, self.tool_calls, self.recursion_limit):
            if type(value) is not int or value < 1:
                raise ValueError("agent budgets must be positive integers")


class AgentEngine:
    def __init__(self, model: BaseChatModel, limits: AgentLimits | None = None):
        self.model = model
        self.limits = limits or AgentLimits()

    def _middleware(self) -> list:
        return [
            ModelCallLimitMiddleware(run_limit=self.limits.model_calls, exit_behavior="error"),
            ToolCallLimitMiddleware(run_limit=self.limits.tool_calls, exit_behavior="error"),
        ]

    async def _invoke(self, agent: Any, payload: dict[str, Any]) -> Any:
        try:
            with tracing_context(enabled=False):
                async with asyncio.timeout(self.limits.timeout_seconds):
                    result = await agent.ainvoke(
                        {"messages": [{"role": "user", "content": json.dumps(payload, ensure_ascii=False)}]},
                        config={"recursion_limit": self.limits.recursion_limit},
                    )
            if "structured_response" not in result:
                raise AgentExecutionError("invalid_model_output")
            return result["structured_response"]
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            raise AgentExecutionError("agent_timeout") from None
        except AgentExecutionError:
            raise
        except (InvalidReferences, InvalidToolResult):
            raise AgentExecutionError("invalid_references") from None
        except Exception:
            raise AgentExecutionError("agent_execution_failed") from None

    async def plan(self, question: str, scope: dict[str, Any]) -> dict[str, Any]:
        try:
            fixed = QueryScope.model_validate(scope)
            if not isinstance(question, str) or not question.strip():
                raise ValueError("a question is required")
        except (ValidationError, ValueError):
            raise AgentExecutionError("invalid_plan") from None
        agent = create_agent(
            model=self.model,
            tools=[],
            system_prompt=_PLAN_PROMPT,
            response_format=ToolStrategy(DraftPlan, handle_errors=False),
            middleware=self._middleware(),
        )
        candidate = await self._invoke(
            agent, {"question": question, "fixed_scope": fixed.model_dump(mode="json")}
        )
        try:
            draft = DraftPlan.model_validate(candidate)
        except ValidationError:
            raise AgentExecutionError("invalid_model_output") from None
        return {"query": fixed.model_dump(mode="json"), **draft.model_dump(mode="json")}

    async def execute(
        self, question: str, plan: dict[str, Any], gateway: ToolGateway
    ) -> dict[str, Any]:
        try:
            confirmed = ConfirmedPlan.model_validate(plan)
            if not isinstance(question, str) or not question.strip():
                raise ValueError("a question is required")
        except (ValidationError, ValueError):
            raise AgentExecutionError("invalid_plan") from None
        tools, ledger = build_tools(gateway, confirmed.query, confirmed.query_text)
        agent = create_agent(
            model=self.model,
            tools=tools,
            system_prompt=_EXECUTION_PROMPT,
            response_format=ToolStrategy(CandidateAnswer, handle_errors=False),
            middleware=self._middleware(),
        )
        candidate = await self._invoke(agent, {
            "question": question,
            "fixed_query": confirmed.query.model_dump(mode="json"),
            "confirmed_query_text": confirmed.query_text,
            # Display-only steps never become executor commands or tool instructions.
        })
        try:
            answer = CandidateAnswer.model_validate(candidate)
            ledger.validate(answer)
        except (ValidationError, InvalidReferences):
            raise AgentExecutionError("invalid_references") from None
        return answer.model_dump(mode="json")

"""Bounded create_agent execution; no persistence, checkpoint or mock fallback."""

import asyncio
from dataclasses import dataclass
import json
import math
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, ToolCallLimitMiddleware
from langchain.agents.middleware.model_call_limit import ModelCallLimitExceededError
from langchain.agents.middleware.tool_call_limit import ToolCallLimitExceededError
from langchain.agents.structured_output import ToolStrategy
from langchain_core.language_models import BaseChatModel
from langsmith import tracing_context
from langgraph.errors import GraphRecursionError
from pydantic import ValidationError

from agent.cards import build_cards
from agent.contracts import ToolGateway
from agent.models import CandidateAnswer, ConfirmedPlan, DraftPlan, QueryScope
from agent.prompts import EXECUTION_PROMPT as _EXECUTION_PROMPT, PLAN_PROMPT as _PLAN_PROMPT
from agent.tools import InvalidReferences, InvalidToolResult, build_tools
from agent.tools.skills import skill_catalog


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
    # Middleware nodes also consume graph steps; leave room for eight model
    # calls plus skill loading and tool results within the independent budgets.
    recursion_limit: int = 60

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
        except ModelCallLimitExceededError:
            raise AgentExecutionError("agent_model_limit") from None
        except ToolCallLimitExceededError:
            raise AgentExecutionError("agent_tool_limit") from None
        except GraphRecursionError:
            raise AgentExecutionError("agent_recursion_limit") from None
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
        self, question: str, plan: dict[str, Any], gateway: ToolGateway, *, run_id: str
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
            system_prompt=_EXECUTION_PROMPT + "\n内置 skill 目录：\n" + json.dumps(skill_catalog(), ensure_ascii=False),
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
            if ledger.semantic_unavailable:
                note = "语义检索服务不可用，本次未获得语义匹配结果；结论仅依据实际回读资料。"
                answer = CandidateAnswer.model_validate({
                    "summary": answer.summary[:4000 - len(note) - 2] + "\n\n" + note,
                    "references": answer.references,
                })
            bundle = build_cards(ledger, answer, run_id)
        except (ValidationError, InvalidReferences):
            raise AgentExecutionError("invalid_references") from None
        return {"answer": answer.model_dump(mode="json"), "cards": bundle.model_dump(mode="json")}

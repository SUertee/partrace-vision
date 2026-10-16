"""Framework-independent, read-only boundary supplied by the application host."""

from typing import Any, Protocol


class ToolGateway(Protocol):
    """Host reauthenticates every call; the model never receives identity or a DB."""

    async def search_events(self, query: dict[str, Any]) -> dict[str, Any]: ...

    async def semantic_search_events(
        self,
        query: dict[str, Any],
        query_text: str,
        top_k: int,
        min_score: float | None,
    ) -> dict[str, Any]: ...

    async def get_event(
        self, query: dict[str, Any], event_id: str
    ) -> dict[str, Any]: ...

    async def get_evidence(
        self, query: dict[str, Any], evidence_id: str
    ) -> dict[str, Any]: ...

    async def read_recording(self, query: dict[str, Any]) -> dict[str, Any]: ...

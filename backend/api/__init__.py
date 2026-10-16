"""Explicit integration seam for the team's separately implemented Agent module."""

from fastapi import APIRouter, FastAPI


def register_agent_router(application: FastAPI, router: APIRouter) -> None:
    """Call only after real Agent routes and authorization are implemented."""
    application.include_router(router, prefix="/api/v1/agent", tags=["agent"])

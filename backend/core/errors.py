"""Public errors never include driver errors, paths, credentials or request bodies."""

from dataclasses import dataclass

API_VERSION = "0.3-mvp-draft"


@dataclass
class ApiError(Exception):
    status_code: int
    code: str
    message: str


def invalid(message: str = "请求字段、范围或关联无效。") -> ApiError:
    return ApiError(422, "validation_error", message)

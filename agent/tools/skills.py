"""Built-in read-only workflows, loaded by name without filesystem access."""

from dataclasses import dataclass
from types import MappingProxyType

from langchain_core.tools import StructuredTool
from pydantic import Field

from agent.models import NonBlank, StrictModel


@dataclass(frozen=True)
class Skill:
    name: str
    description: str
    tools: tuple[str, ...]
    instructions: str


_SKILLS = MappingProxyType({skill.name: skill for skill in (
    Skill(
        "event-evidence-review", "按确认范围检索事件并核对引用证据。",
        ("search_events", "get_event", "get_evidence"),
        "先调用 search_events 读取确认范围内事件，区分范围外、零事件和错误。"
        "选择与问题相关的候选，用 get_event 回读事件及固定 evidence_ids，"
        "再用 get_evidence 单独回查拟引用帧。只引用本轮回读且 available 的帧。"
        "候选、人工标注、模拟来源和缺图须如实说明；事件描述和帧元数据不代表亲自看图。"
        "未检出不证明无人经过、无丢件或无责任；不写人工核查决定。",
    ),
    Skill(
        "semantic-event-search", "按已确认描述寻找候选，并处理索引或服务缺口。",
        ("semantic_search_events", "search_events", "get_event", "get_evidence"),
        "调用 semantic_search_events，文本和范围已由确认计划固定。相似度只用于候选排序。"
        "matched 仍检查 missing_event_count；unindexed 表示索引缺口，no_matches 只表示描述未匹配。"
        "若返回 status=unavailable，说明语义服务不可用，不重复调用；"
        "可改用 search_events 读取同一范围的真实事件，不能声称获得了语义匹配。"
        "普通查询也失败时停止，不编造候选。对选中候选依次 get_event、get_evidence，"
        "只引用 available 的实际回读帧，并说明服务或覆盖限制。",
    ),
    Skill(
        "recording-provenance", "核对当前录像的来源与时长，保留数据边界。",
        ("read_recording",),
        "调用 read_recording，核对固定录像的来源、时长和可用元数据。"
        "如实区分 mock、manual、automatic 及未验证时间，不猜测缺失字段。"
        "录像元数据不能证明动作、身份、责任或关键帧内容；需要事件证据时改用"
        " event-evidence-review。不得切换录像或扩大时间范围。",
    ),
)})


class SkillArguments(StrictModel):
    skill_name: NonBlank = Field(max_length=100)


def skill_catalog() -> list[dict[str, str]]:
    """Return fresh descriptions only; full instructions stay out of the prompt."""
    return [{"name": skill.name, "description": skill.description} for skill in _SKILLS.values()]


def build_skill_tool() -> StructuredTool:
    async def load_skill(skill_name: str) -> dict:
        skill = _SKILLS.get(skill_name)
        if skill is None:
            return {"status": "unknown_skill", "available_skills": skill_catalog()}
        return {
            "kind": "builtin_skill_instructions", "status": "loaded",
            "name": skill.name, "description": skill.description,
            "tools": list(skill.tools), "instructions": skill.instructions,
        }

    return StructuredTool.from_function(
        name="load_skill", coroutine=load_skill, args_schema=SkillArguments,
        description="Load a built-in readonly workflow by exact skill_name from the catalog. "
                    "Returns instructions only; does not read evidence, run scripts or grant permissions.",
    )

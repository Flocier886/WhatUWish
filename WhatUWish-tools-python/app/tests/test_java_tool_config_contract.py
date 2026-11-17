import json
import re
from pathlib import Path

import pytest

from app.runtime.registry import TOOL_REGISTRY


PROJECT_ROOT = Path(__file__).resolve().parents[3]
JAVA_CONFIGS = [
    PROJECT_ROOT / "WhatUWish-app" / "src" / "main" / "resources" / "application-dev.yml",
    PROJECT_ROOT / "WhatUWish-app" / "src" / "main" / "resources" / "application-test.yml",
]


def read_embedded_json(config_path: Path, field: str, next_field: str) -> dict:
    content = config_path.read_text(encoding="utf-8")
    match = re.search(
        rf"^    {field}: >-\r?\n(?P<value>.*?)(?=^\s{{2,4}}{next_field}:)",
        content,
        flags=re.MULTILINE | re.DOTALL,
    )
    assert match, f"未找到 {config_path} 中的 {field} 配置"
    return json.loads(match.group("value"))


@pytest.mark.parametrize("config_path", JAVA_CONFIGS)
def test_java_tool_config_declares_the_same_web_search_contract(config_path: Path):
    tools = read_embedded_json(config_path, "tools", "defaults")
    defaults = read_embedded_json(config_path, "defaults", "skill")
    registry_names = {tool.name for tool in TOOL_REGISTRY.list_tools()}

    web_search = tools["web_search"]
    assert "web_search" in registry_names
    assert web_search["name"] == "web_search"
    assert web_search["type"] == "python"
    assert web_search["pythonEndpoint"] == "/api/v1/tools/execute"
    assert web_search["params"]["required"] == ["query"]
    assert web_search["params"]["properties"]["query"]["type"] == "string"
    assert web_search["params"]["properties"]["limit"]["type"] == "integer"
    assert web_search["params"]["properties"]["limit"]["minimum"] == 1
    assert web_search["params"]["properties"]["limit"]["maximum"] == 10
    assert "web_search" in defaults["enabledTools"]


@pytest.mark.parametrize("config_path", JAVA_CONFIGS)
def test_java_tool_config_exposes_only_public_artifact_read_contract(config_path: Path):
    tools = read_embedded_json(config_path, "tools", "defaults")
    defaults = read_embedded_json(config_path, "defaults", "skill")

    artifact_read = tools["artifact_read"]
    assert artifact_read["type"] == "artifact_read"
    assert artifact_read["pythonEndpoint"] == "/api/v1/tools/execute"
    assert artifact_read["params"]["required"] == ["artifactId"]
    assert set(artifact_read["params"]["properties"]) == {"artifactId", "offset", "limit"}
    assert artifact_read["params"]["additionalProperties"] is False
    assert "artifact_read" in defaults["enabledTools"]


@pytest.mark.parametrize("config_path", JAVA_CONFIGS)
def test_java_tool_config_declares_the_same_deep_search_contract(config_path: Path):
    tools = read_embedded_json(config_path, "tools", "defaults")
    defaults = read_embedded_json(config_path, "defaults", "skill")
    registry_names = {tool.name for tool in TOOL_REGISTRY.list_tools()}

    research = tools["deep_search_tool"]
    properties = research["params"]["properties"]
    # DeepSearch 是独立 Rich SSE 能力，不能同时暴露为普通 /tools/execute 工具。
    assert "deep_search_tool" not in registry_names
    assert "research_gather_tool" not in registry_names
    assert research["name"] == "deep_search_tool"
    assert research["type"] == "rich_python"
    assert research["pythonEndpoint"] == "/api/v1/rich-tools/deep-search/stream"
    assert research["params"]["required"] == ["topic"]

    # 通用 ReAct 只表达研究意图；检索、抓取和迭代预算属于 Python 工作流控制面。
    assert set(properties) == {"topic", "questions", "researchDepth", "language", "preferredDomains"}
    assert properties["researchDepth"]["enum"] == ["quick", "standard", "deep"]
    assert properties["preferredDomains"]["maxItems"] == 8
    assert all(properties[field].get("description") for field in properties)
    assert "不用于简单单事实查询" in research["description"]
    assert "互补查询拆分" in research["description"]
    assert "有限补查判断" in research["description"]
    assert "terminal_completed" in research["description"]
    assert "terminal_partial" in research["description"]
    assert "可直接调用 report_tool" in research["description"]
    assert "运行时会受控使用本次研究材料" in research["description"]
    assert "researchOutcome" in research["description"]
    assert "deep_search_tool" in defaults["enabledTools"]
    assert "research_gather_tool" not in defaults["enabledTools"]

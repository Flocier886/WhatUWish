from app.tools.artifact_save import ArtifactSaveTool
from app.tools.artifact_read import ArtifactReadTool
from app.tools.base import BasePythonTool
from app.tools.directory_list import DirectoryListTool
from app.tools.file_read import FileReadTool
from app.tools.file_search import FileSearchTool
from app.tools.file_write import FileWriteTool
from app.tools.json_extract import JsonExtractTool
from app.tools.skill_script_runner import SkillScriptRunnerTool
from app.tools.web_fetch import WebFetchTool
from app.tools.web_search import WebSearchTool


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, BasePythonTool] = {}

    def register(self, tool: BasePythonTool) -> None:
        self._tools[tool.name] = tool

    def get(self, tool_name: str) -> BasePythonTool | None:
        return self._tools.get(tool_name)

    def list_tools(self) -> list[BasePythonTool]:
        return list(self._tools.values())


TOOL_REGISTRY = ToolRegistry()
TOOL_REGISTRY.register(WebSearchTool())
TOOL_REGISTRY.register(WebFetchTool())
TOOL_REGISTRY.register(DirectoryListTool())
TOOL_REGISTRY.register(FileSearchTool())
TOOL_REGISTRY.register(FileReadTool())
TOOL_REGISTRY.register(FileWriteTool())
TOOL_REGISTRY.register(JsonExtractTool())
TOOL_REGISTRY.register(ArtifactSaveTool())
TOOL_REGISTRY.register(ArtifactReadTool())
TOOL_REGISTRY.register(SkillScriptRunnerTool())

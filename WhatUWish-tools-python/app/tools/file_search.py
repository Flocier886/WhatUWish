import re
import shutil
import subprocess
from pathlib import Path

from app.runtime.context import clip_text
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.observation import search_observation


class FileSearchTool(BasePythonTool):
    """在工作区内搜索文本内容，优先使用 rg。"""

    name = "file_search"
    description = "按关键词搜索工作区中的文件内容，返回命中文件、行号和摘要片段。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            query = self.require_argument(request.arguments, "query")
            raw_path = str(request.arguments.get("path", ".")).strip() or "."
            target = self.resolve_workspace_path(raw_path)
            limit = self.optional_int_argument(request.arguments, "limit", default=10, min_value=1, max_value=50)
            glob = str(request.arguments.get("glob", "")).strip()

            if not target.exists():
                return self.failure(
                    "path_not_found",
                    "目标路径不存在",
                    llm_observation="内容搜索失败：目标路径不存在，请检查搜索范围。",
                    ledger_facts=[{"factType": "file_search_failed", "failureType": "not_found"}],
                )

            engine = "python"
            if shutil.which("rg"):
                try:
                    matches = self._search_with_rg(query, target, limit, glob)
                    engine = "rg"
                except OSError:
                    matches = self._search_with_python(query, target, limit)
            else:
                matches = self._search_with_python(query, target, limit)
            output_lines = [
                f"- {item['relativePath']}:{item['line']} {clip_text(item['snippet'], 120)}"
                for item in matches
            ]
            if matches:
                return self.success(
                    output="\n".join(output_lines),
                    llm_observation=search_observation(query, str(target), matches, limit),
                    structured={
                        "query": query,
                        "path": str(target),
                        "matches": matches,
                    },
                    ledger_facts=[
                        {
                            "factType": "file_search",
                            "query": query,
                            "path": str(target),
                            "matchCount": len(matches),
                        }
                    ],
                    metadata={
                        "matchCount": len(matches),
                        "engine": engine,
                    },
                )
            return self.success(
                output=f"未找到与 `{query}` 相关的内容",
                llm_observation=search_observation(query, str(target), [], limit),
                structured={
                    "query": query,
                    "path": str(target),
                    "matches": [],
                },
                ledger_facts=[
                    {
                        "factType": "file_search_empty",
                        "query": query,
                        "path": str(target),
                        "matchCount": 0,
                    }
                ],
                metadata={
                    "matchCount": 0,
                    "engine": engine,
                },
                status="partial_success",
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)

    def _search_with_rg(self, query: str, target: Path, limit: int, glob: str) -> list[dict]:
        command = ["rg", "-n", "--no-heading", "-m", str(limit), query, str(target)]
        if glob:
            command[1:1] = ["--glob", glob]

        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="ignore", check=False)
        if result.returncode not in {0, 1}:
            raise ToolExecutionError("search_failed", result.stderr.strip() or "rg 搜索失败")
        return self._parse_rg_output(result.stdout, target)

    def _parse_rg_output(self, output: str, root: Path) -> list[dict]:
        matches: list[dict] = []
        for line in output.splitlines():
            match = re.match(r"^(.*?):(\d+):(.*)$", line)
            if not match:
                continue
            file_path, line_number, snippet = match.groups()
            file_obj = Path(file_path).resolve()
            matches.append(
                {
                    "path": str(file_obj),
                    "relativePath": self._relative_or_name(file_obj, root),
                    "line": int(line_number),
                    "snippet": snippet.strip(),
                }
            )
        return matches

    def _search_with_python(self, query: str, target: Path, limit: int) -> list[dict]:
        matches: list[dict] = []
        files = [target] if target.is_file() else [item for item in target.rglob("*") if item.is_file()]
        for file_obj in files:
            try:
                with file_obj.open("r", encoding="utf-8") as handle:
                    for line_number, line in enumerate(handle, start=1):
                        if query in line:
                            matches.append(
                                {
                                    "path": str(file_obj.resolve()),
                                    "relativePath": self._relative_or_name(file_obj.resolve(), target if target.is_dir() else target.parent),
                                    "line": line_number,
                                    "snippet": line.strip(),
                                }
                            )
                            if len(matches) >= limit:
                                return matches
            except (UnicodeDecodeError, OSError):
                continue
        return matches

    @staticmethod
    def _relative_or_name(file_obj: Path, root: Path) -> str:
        try:
            return str(file_obj.relative_to(root))
        except ValueError:
            return file_obj.name

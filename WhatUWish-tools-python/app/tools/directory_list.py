from pathlib import Path

from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.observation import directory_observation


class DirectoryListTool(BasePythonTool):
    """列出工作区目录结构，帮助 Agent 先看再读。"""

    name = "directory_list"
    description = "查看工作区内目录与文件结构，支持限制层级深度。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            raw_path = str(request.arguments.get("path", ".")).strip() or "."
            max_depth = self.optional_int_argument(request.arguments, "maxDepth", default=2, min_value=0, max_value=6)
            include_hidden = self.optional_bool_argument(request.arguments, "includeHidden", default=False)
            target = self.resolve_workspace_path(raw_path)

            if not target.exists():
                return self.failure(
                    "path_not_found",
                    "目标路径不存在",
                    llm_observation="目录查看失败：目标路径不存在，请检查路径或先从工作区根目录重新探索。",
                    ledger_facts=[{"factType": "directory_list_failed", "failureType": "not_found"}],
                )
            if not target.is_dir():
                raise ToolExecutionError("invalid_argument", "path 必须指向目录")

            entries: list[dict] = []
            lines = [f"{target.name}/"]
            self._walk_directory(
                target=target,
                root=target,
                depth=0,
                max_depth=max_depth,
                include_hidden=include_hidden,
                entries=entries,
                lines=lines,
            )

            return self.success(
                output="\n".join(lines),
                llm_observation=directory_observation(str(target), lines, len(entries)),
                structured={
                    "path": str(target),
                    "entries": entries,
                },
                ledger_facts=[
                    {
                        "factType": "directory_list",
                        "path": str(target),
                        "entryCount": len(entries),
                        "maxDepth": max_depth,
                    }
                ],
                metadata={
                    "entryCount": len(entries),
                    "maxDepth": max_depth,
                    "includeHidden": include_hidden,
                },
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)

    def _walk_directory(
        self,
        target: Path,
        root: Path,
        depth: int,
        max_depth: int,
        include_hidden: bool,
        entries: list[dict],
        lines: list[str],
    ) -> None:
        if depth >= max_depth:
            return

        children = sorted(target.iterdir(), key=lambda item: (not item.is_dir(), item.name.lower()))
        for child in children:
            if not include_hidden and child.name.startswith("."):
                continue
            relative_path = child.relative_to(root)
            indent = "  " * (depth + 1)
            suffix = "/" if child.is_dir() else ""
            lines.append(f"{indent}- {relative_path.name}{suffix}")
            entries.append(
                {
                    "name": child.name,
                    "path": str(child),
                    "relativePath": str(relative_path),
                    "type": "directory" if child.is_dir() else "file",
                    "size": None if child.is_dir() else child.stat().st_size,
                    "depth": depth + 1,
                }
            )
            if child.is_dir():
                self._walk_directory(child, root, depth + 1, max_depth, include_hidden, entries, lines)

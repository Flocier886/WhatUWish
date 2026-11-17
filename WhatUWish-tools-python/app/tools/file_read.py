from app.runtime.context import DEFAULT_CONTENT_PREVIEW_LIMIT
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.observation import content_observation


class FileReadTool(BasePythonTool):
    """读取工作区中的文本文件内容。"""

    name = "file_read"
    description = "读取工作区内的本地文本文件内容。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            raw_path = self.require_argument(request.arguments, "path")
            target = self.resolve_workspace_path(raw_path)
            content = target.read_text(encoding="utf-8")
            preview = content[:DEFAULT_CONTENT_PREVIEW_LIMIT]
            return self.success(
                output=f"已读取文件 {target.name}\n{preview}",
                llm_observation=content_observation(
                    title="已读取文件",
                    path=str(target),
                    content=content,
                    limit=DEFAULT_CONTENT_PREVIEW_LIMIT,
                    next_step="如需完整内容，请按更小范围或更具体文件继续读取。",
                ),
                structured={
                    "path": str(target),
                    "name": target.name,
                    "content": content,
                },
                ledger_facts=[
                    {
                        "factType": "file_read",
                        "path": str(target),
                        "name": target.name,
                        "contentLength": len(content),
                    }
                ],
                metadata={
                    "length": len(content),
                    "truncated": len(content) > DEFAULT_CONTENT_PREVIEW_LIMIT,
                },
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except FileNotFoundError:
            return self.failure(
                "file_not_found",
                "目标文件不存在",
                llm_observation="文件读取失败：目标文件不存在。建议先调用 directory_list 或 file_search 确认真实路径。",
                ledger_facts=[{"factType": "file_read_failed", "failureType": "not_found"}],
            )
        except UnicodeDecodeError:
            return self.failure(
                "unsupported_encoding",
                "当前仅支持 UTF-8 文本文件读取",
                llm_observation="文件读取失败：当前文件不是 UTF-8 文本，建议改用其他可处理二进制或不同编码的工具。",
                ledger_facts=[{"factType": "file_read_failed", "failureType": "unsupported_encoding"}],
            )

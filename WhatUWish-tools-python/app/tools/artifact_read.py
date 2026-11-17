from pathlib import PurePosixPath

from app.runtime.artifact_storage import (
    ArtifactStorageError,
    inspect_artifact,
    read_artifact_range,
)
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool


MAX_READ_LIMIT = 5000
DEFAULT_READ_LIMIT = MAX_READ_LIMIT


class ArtifactReadTool(BasePythonTool):
    """按 Java 已授权的私有 locator 读取供 Agent 决策使用的 UTF-8 文本片段。"""

    name = "artifact_read"
    description = "读取 Java 已授权的 Artifact 文本片段。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            storage_type, storage_key, artifact_id = self._resolve_target(request)
            offset = self.optional_int_argument(request.arguments, "offset", 0, min_value=0)
            limit = self.optional_int_argument(
                request.arguments, "limit", DEFAULT_READ_LIMIT, min_value=1, max_value=MAX_READ_LIMIT
            )
            stored = inspect_artifact(storage_key, storage_type)
            if offset > stored.size:
                raise ToolExecutionError("invalid_argument", "offset 超出 artifact 内容范围。")
            raw = read_artifact_range(storage_key, offset, min(limit + 4, max(1, stored.size - offset)), storage_type)
            returned_bytes = self._complete_utf8_prefix(raw, limit)
            returned = returned_bytes.decode("utf-8")
            next_offset = offset + len(returned_bytes)
            has_more = next_offset < stored.size
            file_name = self._display_name(storage_key, artifact_id)

            return self.success(
                output=f"已读取 artifact：{file_name}\n{returned}",
                output_limit=limit,
                llm_observation=self._observation(file_name, artifact_id, stored.size, offset, returned, has_more, next_offset),
                structured={
                    "artifactId": artifact_id,
                    "fileName": file_name,
                    "contentLength": stored.size,
                    "offset": offset,
                    "returnedLength": len(returned),
                    "returnedBytes": len(returned_bytes),
                    "hasMore": has_more,
                    "nextOffset": next_offset if has_more else None,
                },
                metadata={"contentLength": stored.size, "offset": offset, "limit": limit, "hasMore": has_more},
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except ArtifactStorageError:
            return self.failure(
                "artifact_not_found",
                "未找到指定 artifact。",
                llm_observation="产物读取失败：引用不存在、已删除、存储类型未启用，或当前存储服务不可用。",
            )
        except UnicodeDecodeError:
            return self.failure(
                "unsupported_encoding",
                "当前 artifact 不是 UTF-8 文本，无法直接返回正文预览。",
                llm_observation="产物读取失败：当前 artifact 不是 UTF-8 文本，请使用适合其类型的读取工具。",
            )

    def _resolve_target(self, request: ToolExecuteRequest) -> tuple[str, str, str | None]:
        artifact_id = self.require_argument(request.arguments, "artifactId")
        storage_type = self.require_argument(request.arguments, "storageType")
        storage_key = self.require_argument(request.arguments, "storageKey")
        return storage_type, storage_key, artifact_id

    @staticmethod
    def _complete_utf8_prefix(raw: bytes, limit: int) -> bytes:
        candidate = raw[:limit]
        for trim in range(0, min(4, len(candidate)) + 1):
            current = candidate if trim == 0 else candidate[:-trim]
            try:
                current.decode("utf-8")
                return current
            except UnicodeDecodeError as exc:
                if exc.start < max(0, len(current) - 4):
                    raise
        raise UnicodeDecodeError("utf-8", candidate, 0, len(candidate), "无法找到完整 UTF-8 边界")

    @staticmethod
    def _display_name(storage_key: str, artifact_id: str | None) -> str:
        name = PurePosixPath(storage_key).name
        if name == "content":
            return artifact_id or "artifact"
        return name

    @staticmethod
    def _observation(file_name: str, artifact_id: str | None,
                     content_length: int, offset: int, returned: str, has_more: bool, next_offset: int) -> str:
        lines = [
            f"已读取 artifact：{file_name}",
            f"artifactId: {artifact_id or 'unknown'}",
            f"内容总长度：{content_length} 字节；本次起点：{offset}；返回：{len(returned)} 字符。",
            "",
            returned,
        ]
        if has_more:
            lines.append(f"\n尚有后续内容；继续读取请使用 offset={next_offset}，并保持 limit 不超过 {MAX_READ_LIMIT}。")
        else:
            lines.append("\n已到达 artifact 末尾。")
        return "\n".join(lines)

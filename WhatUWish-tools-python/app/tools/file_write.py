from app.runtime.artifact_storage import ArtifactStorageError, persist_file
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool


class FileWriteTool(BasePythonTool):
    """向工作区写文件，并将写出的稳定副本登记为 artifact。"""

    name = "file_write"
    description = "在工作区写入文本文件，同时返回可复用的稳定 artifact 引用。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            raw_path = self.require_argument(request.arguments, "path")
            content = self.require_argument(request.arguments, "content")
            mode = str(request.arguments.get("mode", "overwrite")).strip().lower()
            if mode not in {"overwrite", "append"}:
                raise ToolExecutionError("invalid_argument", "mode 仅支持 overwrite 或 append")
            target = self.resolve_workspace_path(raw_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            if mode == "append":
                with target.open("a", encoding="utf-8") as handle:
                    handle.write(content)
            else:
                target.write_text(content, encoding="utf-8")

            stored = persist_file(target, target.name, request.context)
            artifact = self.artifact(
                artifact_id=stored.artifact_id,
                name=target.name,
                artifact_type=target.suffix.lstrip(".") or "file",
                artifact_role="output",
                visibility="visible",
                source_type="file_write",
                source_name=self.name,
                file_name=stored.file_name,
                storage_type=stored.storage_type,
                storage_ref=stored.storage_ref,
                storage_key=stored.storage_key,
                content_summary=f"由 {self.name} 写入的工作区文件",
                mime_type=stored.mime_type,
                file_size=stored.file_size,
                file_hash=stored.file_hash,
                metadata={"workspacePath": str(target), "mode": mode, "contentLength": len(content)},
            )
            structured = {
                "artifactId": stored.artifact_id,
                "name": target.name,
                "fileName": stored.file_name,
                "mode": mode,
                "storageType": stored.storage_type,
                "storageRef": stored.storage_ref,
                "storageKey": stored.storage_key,
                "artifactType": artifact.artifact_type,
                "artifactRole": "output",
                "visibility": "visible",
                "mimeType": stored.mime_type,
                "fileSize": stored.file_size,
                "fileHash": stored.file_hash,
            }
            return self.success(
                output=f"已写入文件 {target.name}，模式 {mode}。",
                llm_observation=(
                    f"已写入文件：{target.name}\n"
                    f"artifactId: {stored.artifact_id}\n"
                    f"storageType: {stored.storage_type}\n"
                    f"storageKey: {stored.storage_key}\n"
                    f"mode: {mode}\n"
                    f"fileSize: {stored.file_size}\n"
                    "后续读取请调用 artifact_read 并传入 storageKey 或 artifactId。"
                ),
                structured=structured,
                artifacts=[artifact],
                metadata={"length": len(content), "fileSize": stored.file_size, "storageKey": stored.storage_key},
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except (ArtifactStorageError, OSError, ValueError) as exc:
            return self.failure(
                "artifact_storage_error",
                f"无法写入或保存产物：{exc}",
                llm_observation="文件写入或产物保存失败：请检查工作区、artifact storage 的写权限、路径长度和存储配置后重试。",
            )

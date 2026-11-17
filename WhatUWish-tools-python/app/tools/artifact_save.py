from app.runtime.artifact_storage import ArtifactStorageError, persist_text
from app.runtime.context import clip_text
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool


class ArtifactSaveTool(BasePythonTool):
    """将文本保存为有稳定引用的可复用产物。"""

    name = "artifact_save"
    description = "将文本内容保存为可追踪产物，并返回稳定 artifactId 与 storageKey。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            artifact_name = self.require_argument(request.arguments, "name")
            content = self.require_argument(request.arguments, "content")
            artifact_type = str(request.arguments.get("artifactType", "text")).strip() or "text"
            stored = persist_text(content, artifact_name, request.context)
            artifact = self.artifact(
                artifact_id=stored.artifact_id,
                name=artifact_name,
                artifact_type=artifact_type,
                artifact_role="output",
                visibility="visible",
                source_type="artifact_save",
                source_name=self.name,
                file_name=stored.file_name,
                storage_type=stored.storage_type,
                storage_ref=stored.storage_ref,
                storage_key=stored.storage_key,
                content_summary=clip_text(content, 200),
                mime_type=stored.mime_type,
                file_size=stored.file_size,
                file_hash=stored.file_hash,
                metadata={
                    "originalName": artifact_name,
                    "contentLength": len(content),
                    "truncatedSummary": len(content) > 200,
                },
            )
            return self.success(
                output=f"已保存产物 {artifact_name}。",
                llm_observation=(
                    f"已保存产物：{artifact_name}\n"
                    f"artifactId: {stored.artifact_id}\n"
                    f"storageType: {stored.storage_type}\n"
                    f"storageKey: {stored.storage_key}\n"
                    f"fileName: {stored.file_name}\n"
                    f"artifactType: {artifact_type}\n"
                    "后续读取请调用 artifact_read 并传入 storageKey 或 artifactId。"
                ),
                structured=self._structured(artifact, stored.storage_key),
                artifacts=[artifact],
                metadata={"fileSize": stored.file_size, "storageKey": stored.storage_key},
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except (ArtifactStorageError, OSError, ValueError) as exc:
            return self.failure(
                "artifact_storage_error",
                f"无法保存产物：{exc}",
                llm_observation="产物保存失败：artifact storage 不可写、路径过长或存储配置无效。请检查存储配置后重试。",
            )

    def _structured(self, artifact, storage_key: str) -> dict:
        return {
            "artifactId": artifact.artifact_id,
            "name": artifact.name,
            "fileName": artifact.file_name,
            "storageType": artifact.storage_type,
            "storageRef": artifact.storage_ref,
            "storageKey": storage_key,
            "artifactType": artifact.artifact_type,
            "artifactRole": artifact.artifact_role,
            "visibility": artifact.visibility,
            "mimeType": artifact.mime_type,
            "fileSize": artifact.file_size,
            "fileHash": artifact.file_hash,
        }

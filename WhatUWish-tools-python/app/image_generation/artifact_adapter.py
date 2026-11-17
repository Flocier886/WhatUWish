"""Image 核心读取输入 Artifact、保存输出 Artifact 的窄适配器。"""

from __future__ import annotations

from app.image_generation.models import ImageInputArtifact
from app.image_generation.settings import ImageGenerationSettings
from app.runtime.artifact_storage import ArtifactStorageScope, persist_bytes, read_artifact_range
from app.runtime.errors import ToolExecutionError


SUPPORTED_INPUT_MIME_TYPES = {
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
}


class ImageArtifactAdapter:
    def __init__(self, settings: ImageGenerationSettings, execution_id: str) -> None:
        self._settings = settings
        self._execution_id = execution_id

    def read(self, artifact: ImageInputArtifact) -> tuple[bytes, str]:
        if artifact.mime_type.lower() not in SUPPORTED_INPUT_MIME_TYPES:
            raise ToolExecutionError("image_input_unsupported", "参考图片格式不受支持。")
        if artifact.file_size > self._settings.max_reference_bytes:
            raise ToolExecutionError("image_input_budget_exceeded", "参考图片超过单文件读取预算。")
        try:
            content = read_artifact_range(artifact.storage_key, 0, artifact.file_size, artifact.storage_type)
        except Exception as exc:
            raise ToolExecutionError("image_input_unavailable", "参考图片当前不可读取。") from exc
        if not content:
            raise ToolExecutionError("image_input_unavailable", "参考图片内容为空。")
        return content, artifact.mime_type

    def persist(self, content: bytes, file_name: str):
        return persist_bytes(content, file_name, ArtifactStorageScope(execution_id=self._execution_id, caller_scope="image-generation"))

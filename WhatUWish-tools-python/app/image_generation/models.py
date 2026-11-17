"""Image Generation Rich Tool 的 Java-Python 私有协议。"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal
from uuid import uuid4

from pydantic import Field, field_validator, model_validator

from app.runtime.models import RuntimeModel
from app.runtime.artifact_storage import StoredArtifact

IMAGE_PROGRESS_EVENT_VERSION = "image_generation_progress_event.v1"
IMAGE_RESULT_VERSION = "image_generation_result.v1"
IMAGE_ARTIFACT_VERSION = "image_generation_artifact.v1"


class ImageGenerationMode(str, Enum):
    IMAGES = "images"
    EDITS = "edits"


class ImageGenerationStatus(str, Enum):
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class ImageGenerationPhase(str, Enum):
    INPUTS_RESOLVED = "inputs_resolved"
    GENERATION_STARTED = "generation_started"
    ARTIFACT_CREATED = "artifact_created"
    COMPLETED = "completed"
    FAILED = "failed"


class ImageInputArtifact(RuntimeModel):
    """Java 已完成可见性和定位校验的图片 Artifact；不得作为公开 DTO 使用。"""

    artifact_id: str = Field(alias="artifactId", min_length=1, max_length=80)
    file_name: str = Field(alias="fileName", min_length=1, max_length=200)
    mime_type: str = Field(alias="mimeType", min_length=1, max_length=120)
    file_size: int = Field(alias="fileSize", ge=0, le=52_428_800)
    storage_type: str = Field(alias="storageType", min_length=1, max_length=80)
    storage_key: str = Field(alias="storageKey", min_length=1, max_length=320)
    role: Literal["reference", "mask"] = "reference"

    @field_validator("file_name", "mime_type", "storage_type", "storage_key")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("不能为空")
        return normalized


class ImageGenerationStartRequest(RuntimeModel):
    prompt: str = Field(min_length=1, max_length=2400)
    mode: ImageGenerationMode | None = None
    reference_artifacts: list[ImageInputArtifact] = Field(default_factory=list, alias="referenceArtifacts", max_length=10)
    mask_artifacts: list[ImageInputArtifact] = Field(default_factory=list, alias="maskArtifacts", max_length=10)
    output_file_name: str = Field(alias="outputFileName", min_length=1, max_length=160)
    size: str | None = Field(default=None, max_length=32)
    n: int = Field(default=1, ge=1, le=10)
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)

    @field_validator("prompt", "output_file_name", "execution_id")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("不能为空")
        return normalized

    @field_validator("reference_artifacts", "mask_artifacts")
    @classmethod
    def normalize_artifact_lists(cls, values: list[ImageInputArtifact]) -> list[ImageInputArtifact]:
        # 本项目输入由 Java 按角色和顺序拆分传递；同一输入重复会造成无意义的
        # 多模态上下文膨胀，且与后续 input Artifact 关联投影语义冲突。
        identifiers = [item.artifact_id for item in values]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("同一输入列表不允许重复 Artifact")
        return values

    @model_validator(mode="after")
    def validate_inputs(self):
        if len(self.mask_artifacts) > len(self.reference_artifacts):
            raise ValueError("maskArtifacts 数量不能超过 referenceArtifacts")
        if self.mode is ImageGenerationMode.EDITS and not self.reference_artifacts:
            raise ValueError("图生图模式至少需要一张参考图片")
        if any(item.role != "reference" for item in self.reference_artifacts):
            raise ValueError("referenceArtifacts 必须使用 reference 角色")
        if any(item.role != "mask" for item in self.mask_artifacts):
            raise ValueError("maskArtifacts 必须使用 mask 角色")
        return self


class ImageGenerationProgressEvent(RuntimeModel):
    schema_version: Literal[IMAGE_PROGRESS_EVENT_VERSION] = Field(default=IMAGE_PROGRESS_EVENT_VERSION, alias="schemaVersion")
    event_id: str = Field(alias="eventId", min_length=1, max_length=80)
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    sequence: int = Field(ge=1)
    phase: ImageGenerationPhase
    status: ImageGenerationStatus
    occurred_at: datetime = Field(alias="occurredAt")
    summary: str = Field(min_length=1, max_length=1200)
    data: dict[str, Any] = Field(default_factory=dict)


class ImageArtifactDescriptor(RuntimeModel):
    """Python -> Java 私有 Artifact descriptor；locator 不得向浏览器投影。"""
    schema_version: Literal[IMAGE_ARTIFACT_VERSION] = Field(default=IMAGE_ARTIFACT_VERSION, alias="schemaVersion")
    artifact_id: str = Field(alias="artifactId")
    name: str
    artifact_type: Literal["image"] = Field(default="image", alias="artifactType")
    artifact_role: Literal["output"] = Field(default="output", alias="artifactRole")
    visibility: Literal["visible"] = "visible"
    source_type: Literal["python_artifact"] = Field(default="python_artifact", alias="sourceType")
    source_name: Literal["image_generation_tool"] = Field(default="image_generation_tool", alias="sourceName")
    file_name: str = Field(alias="fileName")
    storage_type: str = Field(alias="storageType")
    storage_key: str = Field(alias="storageKey")
    storage_ref: str = Field(alias="storageRef")
    mime_type: str = Field(alias="mimeType")
    file_size: int = Field(alias="fileSize", ge=0)
    file_hash: str = Field(alias="fileHash")
    content_summary: str = Field(alias="contentSummary", max_length=1200)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @classmethod
    def from_stored(cls, artifact: StoredArtifact, summary: str, output_index: int) -> "ImageArtifactDescriptor":
        return cls(
            artifactId=artifact.artifact_id,
            name=artifact.file_name,
            fileName=artifact.file_name,
            storageType=artifact.storage_type,
            storageKey=artifact.storage_key,
            storageRef=artifact.storage_ref,
            mimeType=artifact.mime_type,
            fileSize=artifact.file_size,
            fileHash=artifact.file_hash,
            contentSummary=summary[:1200],
            metadata={
                "preview": {"kind": "image", "schemaVersion": "v1"},
                "outputIndex": output_index,
            },
        )


class ImageGenerationResult(RuntimeModel):
    schema_version: Literal[IMAGE_RESULT_VERSION] = Field(default=IMAGE_RESULT_VERSION, alias="schemaVersion")
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    image_execution_id: str = Field(alias="imageExecutionId", min_length=1, max_length=160)
    status: ImageGenerationStatus
    summary: str = Field(min_length=1, max_length=1200)
    mode: ImageGenerationMode
    size: str | None = None
    requested_count: int = Field(alias="requestedCount", ge=1)
    used_fallback: bool = Field(default=False, alias="usedFallback")
    artifacts: list[ImageArtifactDescriptor] = Field(default_factory=list)
    error_code: str | None = Field(default=None, alias="errorCode", max_length=120)


class ImageGenerationEventPublisher:
    """稳定阶段事件仅在 Python 私有协议内分配；Java 负责耐久账本。"""
    def __init__(self, execution_id: str, sink) -> None:
        self.execution_id = execution_id
        self.image_execution_id = f"image-{uuid4().hex}"
        self._sink = sink
        self._sequence = 0

    def emit(self, phase: ImageGenerationPhase, status: ImageGenerationStatus, summary: str,
             data: dict[str, Any] | None = None) -> ImageGenerationProgressEvent:
        self._sequence += 1
        event = ImageGenerationProgressEvent(
            eventId=f"evt-{self._sequence:04d}",
            executionId=self.execution_id,
            sequence=self._sequence,
            phase=phase,
            status=status,
            occurredAt=datetime.now(timezone.utc),
            summary=summary,
            data=data or {},
        )
        self._sink(event)
        return event

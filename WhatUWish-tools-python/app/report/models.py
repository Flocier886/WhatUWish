"""Report Rich Tool 的跨语言内部协议与受控输出模型。"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal
from uuid import uuid4

from pydantic import Field, field_validator

from app.runtime.models import RuntimeModel
from app.runtime.artifact_storage import StoredArtifact


REPORT_PROGRESS_EVENT_VERSION = "report_progress_event.v1"
REPORT_DELTA_EVENT_VERSION = "report_content_delta.v1"
REPORT_RESULT_VERSION = "report_result.v1"
REPORT_ARTIFACT_VERSION = "report_artifact.v1"


class ReportOutputType(str, Enum):
    MARKDOWN = "markdown"
    HTML = "html"
    PPT = "ppt"

    @property
    def preview_kind(self) -> str:
        return "markdown" if self is self.MARKDOWN else "html-slides" if self is self.PPT else "html"

    @property
    def extension(self) -> str:
        return ".md" if self is self.MARKDOWN else ".html"

    @property
    def mime_type(self) -> str:
        if self is self.MARKDOWN:
            return "text/markdown"
        if self is self.PPT:
            return "text/html+slides"
        return "text/html"


class ReportStatus(str, Enum):
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class ReportPhase(str, Enum):
    INPUTS_RESOLVED = "inputs_resolved"
    GENERATION_STARTED = "generation_started"
    ARTIFACT_CREATED = "artifact_created"
    COMPLETED = "completed"
    FAILED = "failed"


class ReportInputArtifact(RuntimeModel):
    """Java 已授权的内部 Artifact 定位，只能在 Java-Python 私有协议中出现。"""

    artifact_id: str = Field(alias="artifactId", min_length=1, max_length=80)
    file_name: str = Field(alias="fileName", min_length=1, max_length=200)
    mime_type: str = Field(alias="mimeType", min_length=1, max_length=120)
    file_size: int = Field(alias="fileSize", ge=0, le=10_485_760)
    storage_type: str = Field(alias="storageType", min_length=1, max_length=80)
    storage_key: str = Field(alias="storageKey", min_length=1, max_length=320)


class ReportStartRequest(RuntimeModel):
    task: str = Field(min_length=1, max_length=1200)
    input_artifacts: list[ReportInputArtifact] = Field(alias="inputArtifacts", min_length=1, max_length=12)
    output_file_name: str = Field(alias="outputFileName", min_length=1, max_length=160)
    output_type: ReportOutputType = Field(default=ReportOutputType.MARKDOWN, alias="outputType")
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)

    @field_validator("task", "output_file_name", "execution_id")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("不能为空")
        return normalized

    @field_validator("input_artifacts")
    @classmethod
    def distinct_artifacts(cls, values: list[ReportInputArtifact]) -> list[ReportInputArtifact]:
        identifiers = [item.artifact_id for item in values]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("inputArtifacts 不允许重复 Artifact")
        return values


class ReportProgressEvent(RuntimeModel):
    schema_version: Literal[REPORT_PROGRESS_EVENT_VERSION] = Field(default=REPORT_PROGRESS_EVENT_VERSION, alias="schemaVersion")
    event_id: str = Field(alias="eventId", min_length=1, max_length=80)
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    sequence: int = Field(ge=1)
    phase: ReportPhase
    status: ReportStatus
    occurred_at: datetime = Field(alias="occurredAt")
    summary: str = Field(min_length=1, max_length=1200)
    data: dict[str, Any] = Field(default_factory=dict)


class ReportContentDelta(RuntimeModel):
    schema_version: Literal[REPORT_DELTA_EVENT_VERSION] = Field(default=REPORT_DELTA_EVENT_VERSION, alias="schemaVersion")
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    output_type: ReportOutputType = Field(alias="outputType")
    chunk_index: int = Field(alias="chunkIndex", ge=1)
    content: str = Field(min_length=1, max_length=4000)


class ReportArtifactDescriptor(RuntimeModel):
    """终态内部 descriptor。其 storage 字段只能由 Java Artifact Runtime 消费。"""

    schema_version: Literal[REPORT_ARTIFACT_VERSION] = Field(default=REPORT_ARTIFACT_VERSION, alias="schemaVersion")
    artifact_id: str = Field(alias="artifactId")
    name: str
    artifact_type: str = Field(alias="artifactType")
    artifact_role: Literal["output"] = Field(default="output", alias="artifactRole")
    visibility: Literal["visible"] = "visible"
    source_type: Literal["python_artifact"] = Field(default="python_artifact", alias="sourceType")
    source_name: Literal["report_tool"] = Field(default="report_tool", alias="sourceName")
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
    def from_stored(cls, artifact: StoredArtifact, output_type: ReportOutputType, summary: str) -> "ReportArtifactDescriptor":
        return cls(
            artifactId=artifact.artifact_id,
            name=artifact.file_name,
            artifactType="report",
            fileName=artifact.file_name,
            storageType=artifact.storage_type,
            storageKey=artifact.storage_key,
            storageRef=artifact.storage_ref,
            # 本项目的 Artifact preview profile 以 MIME + 后端白名单共同判定。
            # Slides 需要显式类型，不能只由 .html 文件名猜测。
            mimeType=output_type.mime_type,
            fileSize=artifact.file_size,
            fileHash=artifact.file_hash,
            contentSummary=summary[:1200],
            metadata={"preview": {"kind": output_type.preview_kind, "schemaVersion": "v1"}},
        )


class ReportResult(RuntimeModel):
    schema_version: Literal[REPORT_RESULT_VERSION] = Field(default=REPORT_RESULT_VERSION, alias="schemaVersion")
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    report_execution_id: str = Field(alias="reportExecutionId", min_length=1, max_length=160)
    status: ReportStatus
    summary: str = Field(min_length=1, max_length=1200)
    output_type: ReportOutputType = Field(alias="outputType")
    limitations: list[str] = Field(default_factory=list, max_length=24)
    material_statistics: dict[str, int] = Field(default_factory=dict, alias="materialStatistics")
    artifact: ReportArtifactDescriptor | None = None
    error_code: str | None = Field(default=None, alias="errorCode", max_length=120)


class ReportEventPublisher:
    """Report 稳定阶段只在 Python 请求内分配顺序，Java 负责耐久事实。"""

    def __init__(self, execution_id: str, sink) -> None:
        self.execution_id = execution_id
        self.report_execution_id = f"report-{uuid4().hex}"
        self._sink = sink
        self._sequence = 0

    def emit(self, phase: ReportPhase, status: ReportStatus, summary: str,
             data: dict[str, Any] | None = None) -> ReportProgressEvent:
        self._sequence += 1
        event = ReportProgressEvent(
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

"""跨 Python Rich Tool 共用的基础协议模型。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class RuntimeModel(BaseModel):
    """对外 JSON 使用 camelCase，Python 代码保持 snake_case。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class ToolContext(BaseModel):
    session_id: str | None = Field(default=None, alias="sessionId")
    run_id: str | None = Field(default=None, alias="runId")
    request_id: str | None = Field(default=None, alias="requestId")
    trace_id: str | None = Field(default=None, alias="traceId")
    tool_call_id: str | None = Field(default=None, alias="toolCallId")
    step: int | None = None
    agent_id: str | None = Field(default=None, alias="agentId")

    model_config = ConfigDict(populate_by_name=True)


class ToolExecuteRequest(BaseModel):
    tool_name: str = Field(alias="toolName")
    arguments: dict[str, Any] = Field(default_factory=dict)
    context: ToolContext = Field(default_factory=ToolContext)

    model_config = ConfigDict(populate_by_name=True)


class MaterialExecuteRequest(ToolExecuteRequest):
    workspace_root: str = Field(alias="workspaceRoot")


class ToolArtifact(BaseModel):
    artifact_id: str | None = Field(default=None, alias="artifactId")
    name: str
    artifact_type: str = Field(alias="artifactType")
    artifact_role: str | None = Field(default=None, alias="artifactRole")
    visibility: str | None = None
    source_type: str | None = Field(default=None, alias="sourceType")
    source_name: str | None = Field(default=None, alias="sourceName")
    file_name: str | None = Field(default=None, alias="fileName")
    storage_type: str = Field(alias="storageType")
    storage_ref: str = Field(alias="storageRef")
    storage_key: str | None = Field(default=None, alias="storageKey")
    download_url: str | None = Field(default=None, alias="downloadUrl")
    preview_url: str | None = Field(default=None, alias="previewUrl")
    content_summary: str | None = Field(default=None, alias="contentSummary")
    mime_type: str | None = Field(default=None, alias="mimeType")
    file_size: int | None = Field(default=None, alias="fileSize")
    file_hash: str | None = Field(default=None, alias="fileHash")
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class ToolExecuteResponse(BaseModel):
    success: bool
    status: str = "success"
    output: str = ""
    llm_observation: str | None = Field(default=None, alias="llmObservation")
    structured: dict[str, Any] = Field(default_factory=dict)
    ledger_facts: list[dict[str, Any]] = Field(default_factory=list, alias="ledgerFacts")
    artifacts: list[ToolArtifact] = Field(default_factory=list)
    # 材料工具返回本轮工作区生成路径，由 Java 保存为正式 Artifact。
    material_outputs: list[dict[str, str]] = Field(default_factory=list, alias="materialOutputs")
    error_code: str | None = Field(default=None, alias="errorCode")
    error_message: str | None = Field(default=None, alias="errorMessage")
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)

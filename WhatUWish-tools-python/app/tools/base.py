from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

from app.runtime.context import clip_text, ensure_workspace_path
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolArtifact, ToolExecuteRequest, ToolExecuteResponse


class BasePythonTool(ABC):
    """所有 Python 工具的公共基类。"""

    name: str
    description: str

    @abstractmethod
    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        raise NotImplementedError

    def success(
        self,
        output: str,
        structured: dict[str, Any] | None = None,
        artifacts: list[ToolArtifact] | None = None,
        metadata: dict[str, Any] | None = None,
        output_limit: int | None = None,
        *,
        llm_observation: str | None = None,
        ledger_facts: list[dict[str, Any]] | None = None,
        status: str = "success",
    ) -> ToolExecuteResponse:
        """构造统一成功响应，但 observation 与 ledger facts 由具体工具显式提供。"""
        final_output = clip_text(output, output_limit) if output_limit else clip_text(output)
        return ToolExecuteResponse(
            success=True,
            status=status,
            output=final_output,
            llmObservation=llm_observation or final_output,
            structured=structured or {},
            ledgerFacts=ledger_facts or [],
            artifacts=artifacts or [],
            metadata=metadata or {},
        )

    def failure(
        self,
        error_code: str,
        error_message: str,
        *,
        llm_observation: str | None = None,
        ledger_facts: list[dict[str, Any]] | None = None,
    ) -> ToolExecuteResponse:
        """构造统一失败响应，但失败 observation 仍由具体工具决定。"""
        return ToolExecuteResponse(
            success=False,
            status="failed",
            output=error_message,
            llmObservation=llm_observation or error_message,
            structured={},
            ledgerFacts=ledger_facts or [],
            artifacts=[],
            errorCode=error_code,
            errorMessage=error_message,
            metadata={},
        )

    def artifact(
        self,
        name: str,
        artifact_type: str,
        storage_type: str,
        storage_ref: str,
        content_summary: str | None = None,
        *,
        artifact_id: str | None = None,
        artifact_role: str | None = None,
        visibility: str | None = None,
        source_type: str | None = None,
        source_name: str | None = None,
        file_name: str | None = None,
        storage_key: str | None = None,
        download_url: str | None = None,
        preview_url: str | None = None,
        mime_type: str | None = None,
        file_size: int | None = None,
        file_hash: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> ToolArtifact:
        """构造统一产物描述。"""
        return ToolArtifact(
            artifactId=artifact_id,
            name=name,
            artifactType=artifact_type,
            artifactRole=artifact_role,
            visibility=visibility,
            sourceType=source_type,
            sourceName=source_name,
            fileName=file_name,
            storageType=storage_type,
            storageRef=storage_ref,
            storageKey=storage_key,
            downloadUrl=download_url,
            previewUrl=preview_url,
            contentSummary=content_summary,
            mimeType=mime_type,
            fileSize=file_size,
            fileHash=file_hash,
            metadata=metadata or {},
        )

    def require_argument(self, arguments: dict[str, Any], key: str) -> str:
        """读取必填字符串参数。"""
        value = arguments.get(key)
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ToolExecutionError("invalid_argument", f"缺少必要参数: {key}")
        return str(value)

    def optional_int_argument(
        self,
        arguments: dict[str, Any],
        key: str,
        default: int,
        min_value: int | None = None,
        max_value: int | None = None,
    ) -> int:
        """读取可选整型参数并做范围校验。"""
        raw_value = arguments.get(key, default)
        try:
            value = int(raw_value)
        except (TypeError, ValueError) as exc:
            raise ToolExecutionError("invalid_argument", f"{key} 必须是整数") from exc
        if min_value is not None and value < min_value:
            raise ToolExecutionError("invalid_argument", f"{key} 不能小于 {min_value}")
        if max_value is not None and value > max_value:
            raise ToolExecutionError("invalid_argument", f"{key} 不能大于 {max_value}")
        return value

    def optional_bool_argument(self, arguments: dict[str, Any], key: str, default: bool = False) -> bool:
        """读取可选布尔参数。"""
        value = arguments.get(key, default)
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"true", "1", "yes", "y"}:
                return True
            if normalized in {"false", "0", "no", "n"}:
                return False
        raise ToolExecutionError("invalid_argument", f"{key} 必须是布尔值")

    def resolve_workspace_path(self, raw_path: str) -> Path:
        """解析并校验工作区路径。"""
        try:
            return ensure_workspace_path(raw_path)
        except ValueError as exc:
            raise ToolExecutionError("path_not_allowed", "目标路径超出允许的工作区范围") from exc

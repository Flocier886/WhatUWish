"""Report Rich Tool 外层：稳定事件、Live delta 与正式 Artifact 交付。"""

from __future__ import annotations

import logging
import re
import time
import traceback
from pathlib import Path
from typing import Callable

from app.report.artifact_adapter import ReportArtifactAdapter
from app.report.llm_adapter import ReportLlmAdapter
from app.report.llm_client import OpenAiCompatibleReportLlmClient
from app.report.models import (
    ReportArtifactDescriptor,
    ReportContentDelta,
    ReportEventPublisher,
    ReportOutputType,
    ReportPhase,
    ReportResult,
    ReportStartRequest,
    ReportStatus,
)
from app.report.report_core import report, sanitize_report_html_content
from app.report.settings import ReportRuntimeSettings
from app.runtime.artifact_storage import ArtifactStorageScope, persist_text
from app.runtime.errors import ToolExecutionError


logger = logging.getLogger(__name__)


class DeltaAggregator:
    """非耐久正文 delta 的节流；最终正文只通过 Artifact 正式交付。"""

    def __init__(self, *, batch_chars: int, interval_ms: int, max_chars: int, sink: Callable[[str], None]) -> None:
        self._batch_chars = batch_chars
        self._interval_seconds = interval_ms / 1000
        self._max_chars = max_chars
        self._sink = sink
        self._buffer = ""
        self._emitted_chars = 0
        self._last_emit = time.monotonic()

    def append(self, content: str) -> None:
        if self._emitted_chars >= self._max_chars:
            return
        remaining = self._max_chars - self._emitted_chars - len(self._buffer)
        if remaining <= 0:
            return
        self._buffer += content[:remaining]
        if len(self._buffer) >= self._batch_chars or time.monotonic() - self._last_emit >= self._interval_seconds:
            self.flush()

    def flush(self) -> None:
        if not self._buffer:
            return
        self._sink(self._buffer)
        self._emitted_chars += len(self._buffer)
        self._buffer = ""
        self._last_emit = time.monotonic()


class ReportService:
    """只编排本项目运行时边界；三种生成主链由 ``report_core`` 拥有。"""

    def __init__(self, settings: ReportRuntimeSettings, llm_client: OpenAiCompatibleReportLlmClient | None = None) -> None:
        self._settings = settings
        self._llm_client = llm_client or OpenAiCompatibleReportLlmClient(settings)

    def run(self, request: ReportStartRequest, *, emit) -> ReportResult:
        publisher = ReportEventPublisher(request.execution_id, emit)
        try:
            artifact_adapter = ReportArtifactAdapter(self._settings)
            # 先解析已授权输入并发出稳定事实；core 通过 Adapter 缓存得到同一文件集合，
            # service 不介入格式筛选、截断、Prompt 或模型调用。
            artifact_adapter.download_all_files(request.input_artifacts)
            statistics = artifact_adapter.last_statistics.as_dict()
            limitations = _input_limitations(statistics, self._settings)
            publisher.emit(ReportPhase.INPUTS_RESOLVED, ReportStatus.RUNNING, "已解析报告输入材料。", statistics)
            publisher.emit(
                ReportPhase.GENERATION_STARTED,
                ReportStatus.RUNNING,
                "正在生成报告正文。",
                {"outputType": request.output_type.value},
            )
            all_chunks: list[str] = []
            delta_index = 0

            def publish_delta(content: str) -> None:
                nonlocal delta_index
                delta_index += 1
                emit(
                    ReportContentDelta(
                        executionId=request.execution_id,
                        outputType=request.output_type,
                        chunkIndex=delta_index,
                        content=content,
                    )
                )

            aggregator = DeltaAggregator(
                batch_chars=self._settings.delta_batch_chars,
                interval_ms=self._settings.delta_interval_ms,
                max_chars=self._settings.max_delta_chars,
                sink=publish_delta,
            )
            for content in report(
                request.task,
                request.input_artifacts,
                model=self._settings.model,
                file_type=request.output_type.value,
                artifact_adapter=artifact_adapter,
                llm_adapter=ReportLlmAdapter(self._llm_client),
                context_length=self._settings.max_input_chars,
            ):
                all_chunks.append(content)
                aggregator.append(content)
            aggregator.flush()
            output = sanitize_output("".join(all_chunks), request.output_type)
            if not output:
                raise ToolExecutionError("report_generation_empty", "报告模型没有生成可交付的正文。")
            file_name = normalized_output_file_name(request.output_file_name, request.output_type)
            stored = persist_text(
                output,
                file_name,
                ArtifactStorageScope(execution_id=request.execution_id, caller_scope="report"),
            )
            summary = f"已生成 {request.output_type.value} 格式报告，基于 {statistics['acceptedArtifactCount']} 份输入材料。"
            descriptor = ReportArtifactDescriptor.from_stored(stored, request.output_type, summary)
            publisher.emit(
                ReportPhase.ARTIFACT_CREATED,
                ReportStatus.RUNNING,
                "报告文件已创建。",
                {
                    "fileName": file_name,
                    "outputType": request.output_type.value,
                    # descriptor 仅位于 Java-Python 私有 SSE；Java 登记后才投影安全 Artifact DTO。
                    "artifact": descriptor.model_dump(by_alias=True, mode="json"),
                },
            )
            publisher.emit(ReportPhase.COMPLETED, ReportStatus.COMPLETED, summary, {"outputType": request.output_type.value})
            return ReportResult(
                executionId=request.execution_id,
                reportExecutionId=publisher.report_execution_id,
                status=ReportStatus.COMPLETED,
                summary=summary,
                outputType=request.output_type,
                limitations=limitations,
                materialStatistics=statistics,
                artifact=descriptor,
            )
        except ToolExecutionError as exc:
            logger.warning(
                "Report execution failed: executionId=%s errorCode=%s",
                request.execution_id,
                exc.error_code,
            )
            publisher.emit(ReportPhase.FAILED, ReportStatus.FAILED, exc.error_message, {"errorCode": exc.error_code})
            return ReportResult(
                executionId=request.execution_id,
                reportExecutionId=publisher.report_execution_id,
                status=ReportStatus.FAILED,
                summary=exc.error_message,
                outputType=request.output_type,
                errorCode=exc.error_code,
            )
        except Exception as exc:
            logger.error(
                "Report execution failed unexpectedly: executionId=%s exceptionType=%s traceback=%s",
                request.execution_id,
                type(exc).__name__,
                _safe_traceback_locations(exc),
            )
            publisher.emit(ReportPhase.FAILED, ReportStatus.FAILED, "Report 服务出现未预期错误。")
            return ReportResult(
                executionId=request.execution_id,
                reportExecutionId=publisher.report_execution_id,
                status=ReportStatus.FAILED,
                summary="Report 服务出现未预期错误。",
                outputType=request.output_type,
                errorCode="report_internal_error",
            )


def create_report_service() -> ReportService:
    return ReportService(ReportRuntimeSettings.load())


def sanitize_output(content: str, output_type: ReportOutputType) -> str:
    if output_type is ReportOutputType.MARKDOWN:
        return (content or "").strip().removeprefix("```markdown").removeprefix("```").removesuffix("```").strip()
    normalized = sanitize_report_html_content(content)
    lower_content = normalized.lower()
    document_start = lower_content.find("<!doctype html")
    if document_start < 0:
        document_start = lower_content.find("<html")
    if document_start < 0:
        return normalized
    document_end = lower_content.rfind("</html>")
    if document_end < document_start:
        return normalized[document_start:]
    return normalized[document_start:document_end + len("</html>")]


def normalized_output_file_name(file_name: str, output_type: ReportOutputType) -> str:
    name = re.sub(r"[\\/:*?\"<>|]", "_", file_name.strip()).rstrip(".")
    if not name:
        raise ToolExecutionError("report_output_name_invalid", "报告输出文件名无效。")
    base = name.rsplit(".", 1)[0] if "." in name else name
    return f"{base}{output_type.extension}"


def _input_limitations(statistics: dict[str, int], settings: ReportRuntimeSettings) -> list[str]:
    limitations: list[str] = []
    if statistics["truncatedArtifactCount"]:
        limitations.append(f"{statistics['truncatedArtifactCount']} 份输入材料因读取上限被截断。")
    if statistics["inputChars"] > int(settings.max_input_chars * 0.8):
        limitations.append("输入材料超过报告上下文预算，已按输出格式的既定文件顺序截断。")
    return limitations


def _safe_traceback_locations(exc: Exception) -> str:
    """保留定位所需调用栈，不把异常消息或源码行写进日志。"""
    return " <- ".join(
        f"{Path(frame.filename).name}:{frame.lineno} in {frame.name}"
        for frame in traceback.extract_tb(exc.__traceback__)
    )

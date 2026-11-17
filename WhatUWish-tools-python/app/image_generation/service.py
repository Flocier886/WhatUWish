"""Image Rich Tool 外层：稳定阶段、正式 Artifact 交付与受控失败终态。"""

from __future__ import annotations

import asyncio
from typing import Callable

from app.image_generation.artifact_adapter import ImageArtifactAdapter
from app.image_generation.image_core import PersistedGeneratedImage, generate_images, resolve_generation_mode
from app.image_generation.models import (
    ImageArtifactDescriptor,
    ImageGenerationEventPublisher,
    ImageGenerationPhase,
    ImageGenerationResult,
    ImageGenerationStartRequest,
    ImageGenerationStatus,
)
from app.image_generation.settings import ImageGenerationSettings
from app.runtime.errors import ToolExecutionError


class ImageGenerationService:
    """只编排本项目运行时边界，图片调用核心由 ``image_core`` 保持 master 语义。"""

    def __init__(self, settings: ImageGenerationSettings) -> None:
        self._settings = settings

    def run(
        self,
        request: ImageGenerationStartRequest,
        *,
        emit: Callable,
    ) -> ImageGenerationResult:
        publisher = ImageGenerationEventPublisher(request.execution_id, emit)
        mode = resolve_generation_mode(request)
        try:
            input_count = len(request.reference_artifacts)
            mask_count = len(request.mask_artifacts)
            publisher.emit(
                ImageGenerationPhase.INPUTS_RESOLVED,
                ImageGenerationStatus.RUNNING,
                "已解析图片生成输入。",
                {
                    "mode": mode,
                    "referenceCount": input_count,
                    "maskCount": mask_count,
                    "requestedCount": request.n,
                },
            )
            publisher.emit(
                ImageGenerationPhase.GENERATION_STARTED,
                ImageGenerationStatus.RUNNING,
                "正在调用图片生成模型。",
                {"mode": mode, "size": request.size, "requestedCount": request.n},
            )

            core_result = asyncio.run(
                generate_images(
                    request=request,
                    settings=self._settings,
                    artifact_adapter=ImageArtifactAdapter(self._settings, request.execution_id),
                )
            )
            artifacts = [
                ImageArtifactDescriptor.from_stored(
                    item.stored,
                    f"第 {item.output_index} 张图片已生成。",
                    item.output_index,
                )
                for item in core_result["artifacts"]
            ]
            for item, descriptor in zip(core_result["artifacts"], artifacts, strict=True):
                self._emit_artifact_created(publisher, item, descriptor)

            summary = str(core_result["summary"])
            publisher.emit(
                ImageGenerationPhase.COMPLETED,
                ImageGenerationStatus.COMPLETED,
                summary,
                {
                    "mode": mode,
                    "generatedCount": len(artifacts),
                    "usedFallback": bool(core_result["usedFallback"]),
                },
            )
            return ImageGenerationResult(
                executionId=request.execution_id,
                imageExecutionId=publisher.image_execution_id,
                status=ImageGenerationStatus.COMPLETED,
                summary=summary,
                mode=mode,
                size=request.size,
                requestedCount=request.n,
                usedFallback=bool(core_result["usedFallback"]),
                artifacts=artifacts,
            )
        except ToolExecutionError as exc:
            publisher.emit(
                ImageGenerationPhase.FAILED,
                ImageGenerationStatus.FAILED,
                exc.error_message,
                {"errorCode": exc.error_code},
            )
            return _failure_result(request, publisher, mode, exc.error_code, exc.error_message)
        except Exception:
            publisher.emit(
                ImageGenerationPhase.FAILED,
                ImageGenerationStatus.FAILED,
                "图片生成服务出现未预期错误。",
                {"errorCode": "image_generation_internal_error"},
            )
            return _failure_result(
                request,
                publisher,
                mode,
                "image_generation_internal_error",
                "图片生成服务出现未预期错误。",
            )

    @staticmethod
    def _emit_artifact_created(
        publisher: ImageGenerationEventPublisher,
        item: PersistedGeneratedImage,
        descriptor: ImageArtifactDescriptor,
    ) -> None:
        publisher.emit(
            ImageGenerationPhase.ARTIFACT_CREATED,
            ImageGenerationStatus.RUNNING,
            f"第 {item.output_index} 张图片已保存为 Artifact。",
            {
                "outputIndex": item.output_index,
                "fileName": item.file_name,
                # storage locator 只在 Java-Python 私有 SSE 使用；Java 登记后向用户侧
                # 投影 runId + artifactId 和 capability，绝不直接转发此 descriptor。
                "artifact": descriptor.model_dump(by_alias=True, mode="json"),
            },
        )


def create_image_generation_service() -> ImageGenerationService:
    return ImageGenerationService(ImageGenerationSettings.load())


def _failure_result(
    request: ImageGenerationStartRequest,
    publisher: ImageGenerationEventPublisher,
    mode: str,
    error_code: str,
    summary: str,
) -> ImageGenerationResult:
    return ImageGenerationResult(
        executionId=request.execution_id,
        imageExecutionId=publisher.image_execution_id,
        status=ImageGenerationStatus.FAILED,
        summary=summary,
        mode=mode,
        size=request.size,
        requestedCount=request.n,
        errorCode=error_code,
    )

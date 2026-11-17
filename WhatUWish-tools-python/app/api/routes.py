import asyncio
import json
import logging
from queue import Queue, Empty
from threading import Thread
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse

from app.deepsearch.execution import DeepSearchCancellationToken, DeepSearchEventPublisher, DeepSearchExecutionContext
from app.deepsearch.models import (
    DeepSearchDeliveryDisposition,
    DeepSearchDeliveryMode,
    DeepSearchCallerAction,
    DeepSearchPhase,
    DeepSearchResult,
    DeepSearchStartRequest,
    DeepSearchStatus,
)
from app.runtime.errors import ToolExecutionError
from app.deepsearch.service import create_deep_search_service, failure_caller_action, failure_disposition, research_scope_fingerprint
from app.report.models import ReportContentDelta, ReportProgressEvent, ReportStartRequest
from app.report.service import create_report_service
from app.image_generation.models import ImageGenerationProgressEvent, ImageGenerationStartRequest
from app.image_generation.service import create_image_generation_service
from app.runtime.models import MaterialExecuteRequest, ToolExecuteRequest, ToolExecuteResponse
from app.runtime.registry import TOOL_REGISTRY
from app.material.service import execute_material
from app.code_execution.direct_code_execution import ExecutionCancellation, execute_code
from app.code_execution.bash_sandbox import execute_bash
from app.code_execution.code_interpreter import InterpreterCancellation, run_interpreter
from app.code_execution.desktop_session import (
    DesktopSessionError, DesktopSessionRequest, close_desktop_session, open_desktop_session,
)

router = APIRouter()
logger = logging.getLogger(__name__)


@router.post("/api/v1/material/desktop-session/open")
async def desktop_session_open(payload: DesktopSessionRequest) -> dict[str, Any]:
    try:
        return await asyncio.to_thread(open_desktop_session, payload)
    except DesktopSessionError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@router.post("/api/v1/material/desktop-session/close")
async def desktop_session_close(payload: DesktopSessionRequest) -> dict[str, bool]:
    try:
        return await asyncio.to_thread(close_desktop_session, payload)
    except DesktopSessionError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@router.post("/api/v1/material/code-interpreter/stream")
async def stream_code_interpreter(payload: MaterialExecuteRequest, request: Request) -> StreamingResponse:
    """Only the authenticated Java material bridge can start a session-bound analysis."""
    cancellation = InterpreterCancellation()
    queue: Queue[dict[str, Any] | None] = Queue(maxsize=16)

    def work() -> None:
        try:
            for event in run_interpreter(payload, cancellation):
                while not cancellation.is_cancelled():
                    try:
                        queue.put(event, timeout=0.2)
                        break
                    except Exception:
                        continue
        finally:
            while not cancellation.is_cancelled():
                try:
                    queue.put(None, timeout=0.2)
                    break
                except Exception:
                    continue

    Thread(target=work, name="code-interpreter", daemon=True).start()

    async def wait_for_disconnect() -> None:
        while True:
            message = await request.receive()
            if message["type"] == "http.disconnect":
                cancellation.cancel()
                return

    async def events():
        disconnected = asyncio.create_task(wait_for_disconnect())
        try:
            while True:
                if cancellation.is_cancelled():
                    break
                try:
                    event = await asyncio.to_thread(queue.get, True, 0.25)
                except Empty:
                    continue
                if event is None:
                    break
                event["sessionId"] = payload.context.session_id
                event["runId"] = payload.context.run_id
                event["toolCallId"] = payload.context.tool_call_id
                yield "event: " + event["type"] + "\ndata: " + json.dumps(event, ensure_ascii=False) + "\n\n"
        except asyncio.CancelledError:
            cancellation.cancel()
            raise
        finally:
            cancellation.cancel()
            disconnected.cancel()
            await asyncio.gather(disconnected, return_exceptions=True)

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/api/v1/material/execute", response_model=ToolExecuteResponse)
async def execute_material_tool(payload: MaterialExecuteRequest, request: Request) -> ToolExecuteResponse:
    """Java 注入当前 Run 工作区，Python 直接在共享目录处理文件。"""
    if payload.tool_name == "bash":
        return await asyncio.to_thread(execute_bash, payload)
    if payload.tool_name == "code_execution":
        cancellation = ExecutionCancellation()
        execution = asyncio.create_task(asyncio.to_thread(execute_code, payload, cancellation))
        async def wait_for_disconnect() -> None:
            while True:
                message = await request.receive()
                if message["type"] == "http.disconnect":
                    return

        disconnected = asyncio.create_task(wait_for_disconnect())
        try:
            done, _ = await asyncio.wait({execution, disconnected}, return_when=asyncio.FIRST_COMPLETED)
            if disconnected in done and execution not in done:
                await asyncio.to_thread(cancellation.cancel)
            return await execution
        except asyncio.CancelledError:
            await asyncio.to_thread(cancellation.cancel)
            raise
        finally:
            disconnected.cancel()
            await asyncio.gather(disconnected, return_exceptions=True)
    return await asyncio.to_thread(execute_material, payload.tool_name, payload.arguments,
                                   payload.context, payload.workspace_root)


@router.get("/api/v1/health")
def health() -> dict:
    return {"success": True, "service": "python-tool-service", "status": "UP"}


@router.get("/api/v1/tools")
def list_tools() -> dict:
    return {
        "success": True,
        "tools": [
            {
                "name": tool.name,
                "description": tool.description,
            }
            for tool in TOOL_REGISTRY.list_tools()
        ],
    }


@router.post("/api/v1/tools/execute", response_model=ToolExecuteResponse)
def execute_tool(request: ToolExecuteRequest) -> ToolExecuteResponse:
    tool = TOOL_REGISTRY.get(request.tool_name)
    if tool is None:
        raise HTTPException(status_code=404, detail=f"Tool not found: {request.tool_name}")
    return tool.execute(request)


@router.post("/api/v1/rich-tools/deep-search/stream")
async def stream_deep_search(request: Request, payload: DeepSearchStartRequest) -> StreamingResponse:
    """运行独立 DeepSearch，并将节点完成时产生的事件实时编码为 SSE。"""
    queue: Queue[tuple[str, Any] | None] = Queue()
    cancellation = DeepSearchCancellationToken()
    execution = DeepSearchExecutionContext.create(payload.execution_id)
    logger.info(
        "DeepSearch request accepted: researchExecutionId=%s executionId=%s depth=%s",
        execution.research_execution_id,
        execution.echo_execution_id,
        payload.research_depth.value,
    )

    def publish(event) -> None:
        if event.phase == DeepSearchPhase.ARTIFACT_CREATED:
            event_name = "artifact"
        elif event.phase in {DeepSearchPhase.RESEARCH_FAILED, DeepSearchPhase.RESEARCH_CANCELLED}:
            event_name = "error"
        else:
            event_name = "progress"
        queue.put((event_name, event.model_dump(by_alias=True, mode="json")))

    publisher = DeepSearchEventPublisher(execution, sink=publish)

    def run_workflow() -> None:
        try:
            result = create_deep_search_service().run_rich(payload, cancellation=cancellation, publisher=publisher)
            logger.info(
                "DeepSearch workflow completed: researchExecutionId=%s status=%s sources=%s evidence=%s artifacts=%s stopReason=%s",
                execution.research_execution_id,
                result.status.value,
                len(result.sources),
                len(result.evidence),
                len(result.artifacts),
                result.stop_reason,
            )
            queue.put(("result", result.model_dump(by_alias=True, mode="json")))
        except ToolExecutionError as exc:
            logger.warning(
                "DeepSearch workflow failed: researchExecutionId=%s errorCode=%s messageLength=%s",
                execution.research_execution_id,
                exc.error_code,
                len(exc.error_message),
            )
            event = publisher.emit(DeepSearchPhase.RESEARCH_FAILED, DeepSearchStatus.FAILED, exc.error_message, {"errorCode": exc.error_code})
            queue.put(("result", _failure_result(payload, execution, event, exc.error_code, exc.error_message,
                                                   failure_disposition(exc), failure_caller_action(exc)).model_dump(by_alias=True, mode="json")))
        except Exception:
            logger.exception("DeepSearch workflow failed unexpectedly: researchExecutionId=%s", execution.research_execution_id)
            # 防止基础设施异常让 SSE 无终止，同时保持事件版本和顺序字段完整。
            event = publisher.emit(DeepSearchPhase.RESEARCH_FAILED, DeepSearchStatus.FAILED, "DeepSearch 服务出现未预期错误。")
            queue.put(("result", _failure_result(payload, execution, event, "deep_search_internal_error", event.summary,
                                                   DeepSearchDeliveryDisposition.FAILED_UNRECOVERABLE,
                                                   DeepSearchCallerAction.USE_OTHER_CAPABILITY).model_dump(by_alias=True, mode="json")))
        finally:
            queue.put(None)

    worker = Thread(target=run_workflow, name=f"deep-search-{execution.research_execution_id[-8:]}", daemon=True)
    worker.start()

    async def event_stream():
        try:
            while True:
                if await request.is_disconnected():
                    cancellation.cancel("SSE 客户端已断开，已请求停止后续 DeepSearch 操作。")
                    return
                item = await asyncio.to_thread(queue.get)
                if item is None:
                    return
                event_name, body = item
                yield _encode_sse(event_name, body)
        finally:
            cancellation.cancel("SSE 流已关闭，已请求停止后续 DeepSearch 操作。")

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/api/v1/rich-tools/report/stream")
async def stream_report(request: Request, payload: ReportStartRequest) -> StreamingResponse:
    """运行 Report，并将稳定阶段、临时 delta 与终态结果编码为版本化 SSE。"""
    queue: Queue[tuple[str, Any] | None] = Queue()
    logger.info("Report request accepted: executionId=%s outputType=%s inputCount=%s", payload.execution_id, payload.output_type.value, len(payload.input_artifacts))

    def emit(message: ReportProgressEvent | ReportContentDelta) -> None:
        if isinstance(message, ReportContentDelta):
            queue.put(("delta", message.model_dump(by_alias=True, mode="json")))
        else:
            if message.status.value == "failed":
                event_name = "error"
            elif message.phase.value == "artifact_created":
                event_name = "artifact"
            else:
                event_name = "progress"
            queue.put((event_name, message.model_dump(by_alias=True, mode="json")))

    def run_report() -> None:
        try:
            result = create_report_service().run(payload, emit=emit)
            queue.put(("result", result.model_dump(by_alias=True, mode="json")))
        finally:
            queue.put(None)

    Thread(target=run_report, name=f"report-{payload.execution_id[-8:]}", daemon=True).start()

    async def event_stream():
        while True:
            if await request.is_disconnected():
                return
            item = await asyncio.to_thread(queue.get)
            if item is None:
                return
            event_name, body = item
            yield _encode_sse(event_name, body)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/api/v1/rich-tools/image-generation/stream")
async def stream_image_generation(request: Request, payload: ImageGenerationStartRequest) -> StreamingResponse:
    """运行 Image Rich Tool，并输出 Java 专用的版本化稳定 SSE。"""
    queue: Queue[tuple[str, Any] | None] = Queue()
    logger.info(
        "Image generation request accepted: executionId=%s references=%s masks=%s requestedCount=%s",
        payload.execution_id,
        len(payload.reference_artifacts),
        len(payload.mask_artifacts),
        payload.n,
    )

    def emit(message: ImageGenerationProgressEvent) -> None:
        if message.status.value == "failed":
            event_name = "error"
        elif message.phase.value == "artifact_created":
            event_name = "artifact"
        else:
            event_name = "progress"
        queue.put((event_name, message.model_dump(by_alias=True, mode="json")))

    def run_image_generation() -> None:
        try:
            result = create_image_generation_service().run(payload, emit=emit)
            queue.put(("result", result.model_dump(by_alias=True, mode="json")))
        finally:
            queue.put(None)

    Thread(
        target=run_image_generation,
        name=f"image-generation-{payload.execution_id[-8:]}",
        daemon=True,
    ).start()

    async def event_stream():
        while True:
            if await request.is_disconnected():
                return
            item = await asyncio.to_thread(queue.get)
            if item is None:
                return
            event_name, body = item
            yield _encode_sse(event_name, body)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _failure_result(
    payload: DeepSearchStartRequest,
    execution: DeepSearchExecutionContext,
    event,
    error_code: str,
    message: str,
    disposition: DeepSearchDeliveryDisposition,
    caller_action: DeepSearchCallerAction,
) -> DeepSearchResult:
    """所有路由级终态出口都必须满足同一 Rich Tool 最终结果契约。"""
    return DeepSearchResult(
        executionId=execution.echo_execution_id,
        researchExecutionId=execution.research_execution_id,
        status=DeepSearchStatus.FAILED,
        deliveryDisposition=disposition,
        callerAction=caller_action,
        researchScopeFingerprint=research_scope_fingerprint(payload),
        deliveryMode=DeepSearchDeliveryMode.INSUFFICIENT_EVIDENCE,
        summary=message,
        presentationSummary=message[:1200],
        stopReason=error_code,
        limitations=[message],
        stageSummaries=[{"sequence": event.sequence, "phase": event.phase.value, "status": event.status.value, "summary": event.summary}],
    )


def _encode_sse(event_name: str, body: dict[str, Any]) -> str:
    """使用单行 JSON data，避免未转义换行破坏 SSE 消费顺序。"""
    return f"event: {event_name}\ndata: {json.dumps(body, ensure_ascii=False, separators=(',', ':'))}\n\n"

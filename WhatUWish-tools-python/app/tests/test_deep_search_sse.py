"""独立 DeepSearch SSE API 的传输与终态回归。"""

from __future__ import annotations

import asyncio
import json

import httpx

from app.api import routes
from app.deepsearch.models import (
    DeepSearchDeliveryDisposition,
    DeepSearchDeliveryMode,
    DeepSearchPhase,
    DeepSearchResult,
    DeepSearchStatus,
)
from app.main import app
from app.runtime.errors import ToolExecutionError


class _StreamStubService:
    """只替代研究依赖；事件仍由真实 FastAPI 路由、线程和 SSE 编码发送。"""

    def run_rich(self, request, *, cancellation, publisher):
        publisher.emit(DeepSearchPhase.RESEARCH_STARTED, DeepSearchStatus.RUNNING, "研究已开始。")
        publisher.emit(DeepSearchPhase.ARTIFACT_CREATED, DeepSearchStatus.RUNNING, "证据包已发布。", {"artifact": {"artifactId": "artifact-1"}})
        publisher.emit(DeepSearchPhase.RESEARCH_COMPLETED, DeepSearchStatus.COMPLETED, "研究已完成。")
        return DeepSearchResult(
            executionId=publisher.context.echo_execution_id,
            researchExecutionId=publisher.context.research_execution_id,
            status=DeepSearchStatus.COMPLETED,
            deliveryDisposition=DeepSearchDeliveryDisposition.TERMINAL_COMPLETED,
            researchScopeFingerprint="sha256:sse-contract",
            deliveryMode=DeepSearchDeliveryMode.MODEL_SYNTHESIZED,
            summary="研究已完成。",
            presentationSummary="研究已完成。",
            stopReason="coverage_sufficient",
            stageSummaries=[],
        )


def test_deep_search_sse_sends_progress_artifact_then_result(monkeypatch) -> None:
    monkeypatch.setattr(routes, "create_deep_search_service", lambda: _StreamStubService())

    async def invoke() -> str:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post(
                "/api/v1/rich-tools/deep-search/stream",
                json={"topic": "SSE contract", "executionId": "caller-sse-1"},
            )
        assert response.status_code == 200
        return response.text

    payload = asyncio.run(invoke())
    events = [line.removeprefix("event: ") for line in payload.splitlines() if line.startswith("event: ")]
    bodies = [json.loads(line.removeprefix("data: ")) for line in payload.splitlines() if line.startswith("data: ")]

    assert events == ["progress", "artifact", "progress", "result"]
    assert [body["sequence"] for body in bodies[:-1]] == [1, 2, 3]
    assert bodies[0]["executionId"] == "caller-sse-1"
    assert bodies[1]["phase"] == "artifact_created"
    assert bodies[-1]["status"] == "completed"


def test_deep_search_sse_returns_single_controlled_error_terminal(monkeypatch) -> None:
    def unavailable_service():
        raise ToolExecutionError("research_llm_not_configured", "研究模型未配置。")

    monkeypatch.setattr(routes, "create_deep_search_service", unavailable_service)

    async def invoke() -> str:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/v1/rich-tools/deep-search/stream", json={"topic": "failure"})
        assert response.status_code == 200
        return response.text

    payload = asyncio.run(invoke())
    events = [line.removeprefix("event: ") for line in payload.splitlines() if line.startswith("event: ")]
    bodies = [json.loads(line.removeprefix("data: ")) for line in payload.splitlines() if line.startswith("data: ")]

    assert events == ["error", "result"]
    assert bodies[0]["phase"] == "research_failed"
    assert bodies[0]["sequence"] == 1
    assert bodies[1]["status"] == "failed"
    assert bodies[1]["deliveryDisposition"] == "caller_action_required"


def test_deep_search_sse_does_not_misclassify_internal_tool_failure_as_caller_action(monkeypatch) -> None:
    class FailingService:
        def run_rich(self, _request, *, cancellation, publisher):
            raise ToolExecutionError("research_pipeline_unavailable", "research pipeline unavailable")

    monkeypatch.setattr(routes, "create_deep_search_service", lambda: FailingService())

    async def invoke() -> str:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/v1/rich-tools/deep-search/stream", json={"topic": "failure"})
        assert response.status_code == 200
        return response.text

    payload = asyncio.run(invoke())
    bodies = [json.loads(line.removeprefix("data: ")) for line in payload.splitlines() if line.startswith("data: ")]

    assert bodies[-1]["status"] == "failed"
    assert bodies[-1]["deliveryDisposition"] == "failed_unrecoverable"


def test_normal_tool_directory_does_not_expose_rich_deep_search() -> None:
    async def invoke() -> dict:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/tools")
        assert response.status_code == 200
        return response.json()

    payload = asyncio.run(invoke())
    assert "deep_search_tool" not in {tool["name"] for tool in payload["tools"]}


def test_deep_search_validation_error_exposes_only_controlled_contract_details() -> None:
    async def invoke():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/rich-tools/deep-search/stream",
                json={
                    "topic": "private topic must not be echoed",
                    "preferredDomains": [f"domain-{index}.example" for index in range(9)],
                },
            )

    response = asyncio.run(invoke())

    assert response.status_code == 422
    body = response.json()
    assert body["errorCode"] == "deep_search_request_invalid"
    assert body["violations"] == [{"field": "preferredDomains", "rule": "too_long", "limit": 8}]
    assert "private topic" not in response.text


def test_deep_search_public_api_rejects_caller_controlled_internal_budgets() -> None:
    """模式是 Rich Tool 对外的唯一预算选择。此断言防止 Java 或其他调用方重新将搜索/抓取参数透传回 Python。
    """
    async def invoke():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/rich-tools/deep-search/stream",
                json={"topic": "budget boundary", "maxSearches": 1, "maxFetches": 1, "maxIterations": 1},
            )

    response = asyncio.run(invoke())

    assert response.status_code == 422
    assert response.json()["errorCode"] == "deep_search_request_invalid"
    assert {item["field"] for item in response.json()["violations"]} == {
        "maxSearches", "maxFetches", "maxIterations"
    }

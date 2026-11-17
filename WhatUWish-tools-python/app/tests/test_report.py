"""Report Rich Tool 的离线协议、材料预算和 Artifact 交付回归。"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from app.api import routes
from app.main import app
from app.report.llm_client import OpenAiCompatibleReportLlmClient
from app.report.models import ReportContentDelta, ReportOutputType, ReportStartRequest
from app.report.artifact_adapter import ReportArtifactAdapter
from app.report.llm_adapter import ReportLlmAdapter
from app.report.report_core import html_report, markdown_report, ppt_report, sanitize_report_html_content
from app.report.prompts import FIX_HTML_PROMPT, HTML_PROMPT, HTML_TASK_TEMPLATE, render_html_task, render_markdown_prompt, render_ppt_prompt
from app.report.service import DeltaAggregator, ReportService, sanitize_output
from app.report.settings import ReportRuntimeSettings
from app.runtime.artifact_storage import StoredArtifact
from app.runtime.errors import ToolExecutionError


def settings() -> ReportRuntimeSettings:
    return ReportRuntimeSettings(
        provider="openai-compatible",
        model="report-test-model",
        base_url="https://llm.example/v1",
        api_key="test-secret",
        timeout_seconds=30.0,
        max_retries=0,
        temperature=0.2,
        max_output_tokens=1000,
        max_input_chars=40,
        max_file_chars=30,
        max_artifact_bytes=1024,
        delta_batch_chars=5,
        delta_interval_ms=3000,
        max_delta_chars=50,
    )


def start_request(**overrides) -> ReportStartRequest:
    payload = {
        "task": "summarize inputs",
        "executionId": "report-test-1",
        "outputFileName": "research result",
        "outputType": "markdown",
        "inputArtifacts": [{
            "artifactId": "input-1",
            "fileName": "facts.md",
            "mimeType": "text/markdown",
            "fileSize": 20,
            "storageType": "local_artifact_storage",
            "storageKey": "private/path/facts.md",
        }],
    }
    payload.update(overrides)
    return ReportStartRequest.model_validate(payload)


def test_report_settings_loads_yaml_section_and_never_exposes_secret():
    configured = ReportRuntimeSettings.from_configuration({
        "report-runtime": {
            "provider": "openai-compatible",
            "model": "report-model",
            "api-base": "https://llm.example/v1",
            "api-key": "private-key",
        }
    })

    assert configured.model == "report-model"
    assert "private-key" not in json.dumps(configured.safe_metadata())


def test_artifact_adapter_preserves_descriptor_order_and_master_truncation(monkeypatch):
    received = []

    def read_range(storage_key, *_args):
        received.append(storage_key)
        return {"private/a": b"first", "private/b": b"second"}[storage_key]

    monkeypatch.setattr("app.report.artifact_adapter.read_artifact_range", read_range)
    adapter = ReportArtifactAdapter(settings())
    request = start_request(inputArtifacts=[
        {"artifactId": "one", "fileName": "one.md", "mimeType": "text/markdown", "fileSize": 5, "storageType": "local_artifact_storage", "storageKey": "private/a"},
        {"artifactId": "two", "fileName": "two.txt", "mimeType": "text/plain", "fileSize": 6, "storageType": "local_artifact_storage", "storageKey": "private/b"},
    ])

    files = adapter.download_all_files(request.input_artifacts)

    assert received == ["private/a", "private/b"]
    assert files == [{"file_name": "one.md", "content": "first"}, {"file_name": "two.txt", "content": "second"}]
    assert adapter.truncate_files(files, 7) == [
        {"file_name": "one.md", "content": "first"},
        {"file_name": "two.txt", "content": "se"},
    ]


def test_master_core_and_prompt_source_are_embedded_without_runtime_bridge():
    report_root = __import__("pathlib").Path(__file__).resolve().parents[1] / "report"
    source_text = "\n".join(path.read_text(encoding="utf-8") for path in report_root.glob("*.py"))

    assert "ai-agent-master" not in source_text
    assert "D:\\JavaStudy" not in source_text
    assert "master_report_prompts" not in source_text
    assert "def report(" in (report_root / "report_core.py").read_text(encoding="utf-8")
    assert "def markdown_report(" in (report_root / "report_core.py").read_text(encoding="utf-8")
    assert "def html_report(" in (report_root / "report_core.py").read_text(encoding="utf-8")
    assert "def ppt_report(" in (report_root / "report_core.py").read_text(encoding="utf-8")


def test_master_prompt_contents_and_html_task_template_are_present():
    markdown = render_markdown_prompt("task", [{"file_name": "facts.md", "content": "materials", "title": "facts", "link": "https://example.test"}], "2026-08-29 12:00:00")
    html_task = render_html_task("task", [], [{"content": "materials", "description": "facts.md", "type": "txt", "link": ""}], "2026年08月29日")
    slides = render_ppt_prompt("task", [{"file_name": "facts.md", "content": "materials"}], "2026-08-29")

    assert "做整理，而不是做摘要" in markdown
    assert "<title>facts</title>" in markdown
    assert "世界级的前端设计大师" in HTML_PROMPT
    assert "Echart" in HTML_PROMPT
    assert "能够按模板生成专业、美观且功能完备的数据分析报告" in FIX_HTML_PROMPT
    assert "只能生成一个 series 对象" in FIX_HTML_PROMPT
    assert "# 网页模板" in FIX_HTML_PROMPT
    assert "核心要求：" in html_task
    assert "<content>materials</content>" in html_task
    assert "每个章节内容用至少两页展示内容" in slides
    assert "resizeEcharts" in slides
    assert "{% if key_files %}" in HTML_TASK_TEMPLATE


def test_core_html_and_slides_keep_master_format_selection_and_prompt_shapes():
    class Adapter:
        def __init__(self, files):
            self.files = files

        def download_all_files(self, _):
            return self.files

        @staticmethod
        def truncate_files(files, max_tokens):
            copied = []
            size = 0
            for item in files:
                if size >= max_tokens:
                    break
                next_item = dict(item)
                next_item["content"] = next_item["content"][:max_tokens - size]
                size += len(next_item["content"])
                copied.append(next_item)
            return copied

        @staticmethod
        def flatten_search_file(item):
            return [f for values in json.loads(item["content"]).values() for f in values]

    class Llm:
        def __init__(self):
            self.calls = []

        def ask_llm(self, **kwargs):
            self.calls.append(kwargs)
            yield "generated"

    llm = Llm()
    html_adapter = Adapter([
        {"file_name": "代码输出.txt", "content": "KEY"},
        {"file_name": "notes.md", "content": "NORMAL"},
        {"file_name": "ignored.html", "content": "IGNORE"},
    ])
    assert list(html_report("task", [], artifact_adapter=html_adapter, llm_adapter=llm, context_length=100)) == ["generated"]
    messages = llm.calls[0]["messages"]
    assert messages[0] == {"role": "system", "content": HTML_PROMPT}
    assert "<key_docs>" in messages[1]["content"]
    assert "KEY" in messages[1]["content"]
    assert "NORMAL" in messages[1]["content"]
    assert "IGNORE" not in messages[1]["content"]

    slides_adapter = Adapter([
        {"file_name": "ignored.txt", "content": "IGNORE"},
        {"file_name": "kept.md", "content": "KEEP"},
    ])
    assert list(ppt_report("task", [], artifact_adapter=slides_adapter, llm_adapter=llm, context_length=100)) == ["generated"]
    assert "KEEP" in llm.calls[1]["messages"]
    assert "IGNORE" not in llm.calls[1]["messages"]


def test_core_markdown_expands_search_results_before_ordered_truncation():
    class Adapter:
        def download_all_files(self, _):
            return [
                {"file_name": "research_search_result.txt", "content": json.dumps({"a": [{"file_name": "source-a", "content": "AAAA"}]})},
                {"file_name": "notes.md", "content": "BBBB"},
            ]

        @staticmethod
        def flatten_search_file(item):
            return [f for values in json.loads(item["content"]).values() for f in values]

        @staticmethod
        def truncate_files(files, max_tokens):
            return ReportArtifactAdapter.truncate_files(files, max_tokens)

    class Llm:
        def __init__(self):
            self.prompt = ""

        def ask_llm(self, **kwargs):
            self.prompt = kwargs["messages"]
            yield "generated"

    llm = Llm()
    assert list(markdown_report("task", [], artifact_adapter=Adapter(), llm_adapter=llm, context_length=10)) == ["generated"]
    assert "AAAA" in llm.prompt
    assert "BBBB" in llm.prompt


def test_core_fix_html_selects_the_master_fix_system_prompt():
    class Adapter:
        @staticmethod
        def download_all_files(_):
            return [{"file_name": "notes.md", "content": "NORMAL"}]

        @staticmethod
        def truncate_files(files, max_tokens):
            return files

        @staticmethod
        def flatten_search_file(_):
            return []

    class Llm:
        def __init__(self):
            self.messages = None

        def ask_llm(self, **kwargs):
            self.messages = kwargs["messages"]
            yield "generated"

    llm = Llm()

    assert list(
        html_report(
            "task",
            [],
            template_type="fix",
            artifact_adapter=Adapter(),
            llm_adapter=llm,
            context_length=100,
        )
    ) == ["generated"]
    assert llm.messages[0] == {"role": "system", "content": FIX_HTML_PROMPT}
    assert llm.messages[0]["content"] != HTML_PROMPT


def test_html_cleanup_handles_master_prefix_and_code_fence():
    assert sanitize_report_html_content("html: ```html\n<html><body>ok</body></html>\n```") == "<html><body>ok</body></html>"
    assert sanitize_output("```markdown\n# Report\n```", ReportOutputType.MARKDOWN) == "# Report"
    assert sanitize_output(
        "<think>internal planning</think>\nHtml:\n```html\n<!DOCTYPE html><html><body>ok</body></html>\n```\nextra",
        ReportOutputType.HTML,
    ) == "<!DOCTYPE html><html><body>ok</body></html>"

def test_delta_aggregator_batches_and_applies_total_cap():
    emitted: list[str] = []
    aggregator = DeltaAggregator(batch_chars=4, interval_ms=3000, max_chars=6, sink=emitted.append)

    aggregator.append("abc")
    aggregator.append("def")
    aggregator.append("ghi")
    aggregator.flush()

    assert emitted == ["abcdef"]


def test_report_service_persists_final_artifact_and_keeps_locators_out_of_events(monkeypatch):
    class StubClient:
        def stream(self, **_kwargs):
            yield "# A report"
            yield "\nWith evidence."

    stored = StoredArtifact(
        artifact_id="output-1",
        storage_type="local_artifact_storage",
        storage_key="private/output/report.md",
        storage_ref="private-ref",
        file_name="research-result.md",
        file_size=26,
        mime_type="text/markdown",
        file_hash="hash-1",
    )
    monkeypatch.setattr("app.report.artifact_adapter.read_artifact_range", lambda *_args: b"evidence")
    monkeypatch.setattr("app.report.service.persist_text", lambda *_args: stored)
    messages = []

    result = ReportService(settings(), llm_client=StubClient()).run(start_request(), emit=messages.append)
    artifact_event = next(
        item
        for item in messages
        if not isinstance(item, ReportContentDelta) and item.phase.value == "artifact_created"
    )

    assert result.status.value == "completed"
    assert result.artifact is not None
    assert result.artifact.metadata["preview"] == {"kind": "markdown", "schemaVersion": "v1"}
    assert all(not isinstance(item, ReportContentDelta) or "private/" not in item.content for item in messages)
    # locator 仅存在 Java -> Python 私有协议的 artifact_created descriptor 中；路由不会再
    # 旁路发送第二份 artifact，Java 登记后才向用户端投影安全 Artifact DTO。
    assert artifact_event.data["artifact"]["storageKey"] == "private/output/report.md"
    assert "private/output" not in result.summary
    assert [item.phase.value for item in messages if not isinstance(item, ReportContentDelta)] == [
        "inputs_resolved", "generation_started", "artifact_created", "completed"
    ]


def test_report_llm_client_parses_openai_sse_without_logging_request_secret():
    def handler(request: httpx.Request) -> httpx.Response:
        body = request.content.decode()
        assert request.headers["authorization"] == "Bearer test-secret"
        assert "private system prompt" in body
        chunks = "\n".join([
            'data: {"choices":[{"delta":{"content":"hello "}}]}',
            'data: {"choices":[{"delta":{"content":"world"}}]}',
            "data: [DONE]",
            "",
        ])
        return httpx.Response(200, content=chunks, headers={"content-type": "text/event-stream"})

    client = OpenAiCompatibleReportLlmClient(settings(), transport=httpx.MockTransport(handler))

    assert "".join(client.stream(system_prompt="private system prompt", user_prompt="private user material")) == "hello world"


def test_report_llm_client_accepts_role_only_first_sse_frame():
    def handler(_request: httpx.Request) -> httpx.Response:
        chunks = "\n".join([
            'data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}',
            'data: {"choices":[{"delta":{"content":"正文"},"finish_reason":null}]}',
            'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}',
            'data: {"choices":[],"usage":{"total_tokens":12}}',
            "data: [DONE]",
            "",
        ])
        return httpx.Response(200, content=chunks, headers={"content-type": "text/event-stream"})

    client = OpenAiCompatibleReportLlmClient(settings(), transport=httpx.MockTransport(handler))

    assert "".join(client.stream(system_prompt="system", user_prompt="material")) == "正文"


@pytest.mark.parametrize("frame", [
    'data: ["not-an-object"]',
    'data: {"choices":["not-an-object"]}',
    'data: {"choices":[{"delta":"not-an-object"}]}',
    'data: {"choices":[{"delta":{"content":["not-text"]}}]}',
])
def test_report_llm_client_classifies_invalid_sse_shapes_without_echoing_payload(frame):
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=f"{frame}\ndata: [DONE]\n",
            headers={"content-type": "text/event-stream"},
        )

    client = OpenAiCompatibleReportLlmClient(settings(), transport=httpx.MockTransport(handler))

    with pytest.raises(ToolExecutionError) as raised:
        list(client.stream(system_prompt="private system prompt", user_prompt="private user material"))

    assert raised.value.error_code == "report_llm_response_invalid"
    assert "not-an-object" not in raised.value.error_message


def test_report_llm_client_classifies_empty_sse_stream_as_invalid_response():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content="data: [DONE]\n", headers={"content-type": "text/event-stream"})

    client = OpenAiCompatibleReportLlmClient(settings(), transport=httpx.MockTransport(handler))

    with pytest.raises(ToolExecutionError) as raised:
        list(client.stream(system_prompt="system", user_prompt="material"))

    assert raised.value.error_code == "report_llm_response_invalid"


def test_report_service_logs_classified_stream_error_without_internal_fallback(monkeypatch, caplog):
    class InvalidStreamClient:
        def stream(self, **_kwargs):
            raise ToolExecutionError("report_llm_response_invalid", "报告模型流式响应格式无效")
            yield "unreachable"

    monkeypatch.setattr("app.report.artifact_adapter.read_artifact_range", lambda *_args: b"evidence")
    caplog.set_level("WARNING", logger="app.report.service")

    result = ReportService(settings(), llm_client=InvalidStreamClient()).run(start_request(), emit=lambda _event: None)

    assert result.error_code == "report_llm_response_invalid"
    assert "executionId=report-test-1" in caplog.text
    assert "errorCode=report_llm_response_invalid" in caplog.text
    assert "report_internal_error" not in caplog.text


def test_report_service_logs_unexpected_failure_without_request_or_artifact_secrets(monkeypatch, caplog):
    def unexpected_failure(_self, _artifacts):
        raise RuntimeError("private-prompt private/storage/key test-secret")

    monkeypatch.setattr(ReportArtifactAdapter, "download_all_files", unexpected_failure)
    caplog.set_level("ERROR", logger="app.report.service")

    result = ReportService(settings()).run(start_request(), emit=lambda _event: None)

    assert result.error_code == "report_internal_error"
    assert "executionId=report-test-1" in caplog.text
    assert "RuntimeError" in caplog.text
    assert "private-prompt" not in caplog.text
    assert "private/storage/key" not in caplog.text
    assert "test-secret" not in caplog.text


def test_report_stream_route_emits_versioned_progress_delta_artifact_and_result(monkeypatch):
    class StreamStubService:
        def run(self, request, *, emit):
            from app.report.models import ReportArtifactDescriptor, ReportPhase, ReportProgressEvent, ReportResult, ReportStatus
            emit(ReportProgressEvent(eventId="evt-0001", executionId=request.execution_id, sequence=1, phase=ReportPhase.INPUTS_RESOLVED, status=ReportStatus.RUNNING, occurredAt="2026-01-01T00:00:00Z", summary="inputs"))
            emit(ReportContentDelta(executionId=request.execution_id, outputType=request.output_type, chunkIndex=1, content="draft"))
            artifact = ReportArtifactDescriptor(artifactId="output-1", name="report.md", artifactType="report", fileName="report.md", storageType="local_artifact_storage", storageKey="private/internal", storageRef="private-ref", mimeType="text/markdown", fileSize=10, fileHash="hash", contentSummary="done", metadata={"preview": {"kind": "markdown", "schemaVersion": "v1"}})
            emit(ReportProgressEvent(
                eventId="evt-0002",
                executionId=request.execution_id,
                sequence=2,
                phase=ReportPhase.ARTIFACT_CREATED,
                status=ReportStatus.RUNNING,
                occurredAt="2026-01-01T00:00:01Z",
                summary="artifact",
                data={"artifact": artifact.model_dump(by_alias=True, mode="json")},
            ))
            return ReportResult(executionId=request.execution_id, reportExecutionId="report-1", status=ReportStatus.COMPLETED, summary="done", outputType=request.output_type, artifact=artifact)

    monkeypatch.setattr(routes, "create_report_service", lambda: StreamStubService())

    async def invoke() -> str:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/v1/rich-tools/report/stream", json=start_request().model_dump(by_alias=True))
        assert response.status_code == 200
        return response.text

    payload = asyncio.run(invoke())
    events = [line.removeprefix("event: ") for line in payload.splitlines() if line.startswith("event: ")]
    bodies = [json.loads(line.removeprefix("data: ")) for line in payload.splitlines() if line.startswith("data: ")]

    assert events == ["progress", "delta", "artifact", "result"]
    assert bodies[0]["schemaVersion"] == "report_progress_event.v1"
    assert bodies[1]["schemaVersion"] == "report_content_delta.v1"
    assert bodies[-1]["status"] == "completed"


def test_report_validation_error_does_not_echo_internal_artifact_locator():
    async def invoke():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/rich-tools/report/stream", json={"task": "x", "inputArtifacts": [{"storageKey": "private/secret/key"}]})

    response = asyncio.run(invoke())

    assert response.status_code == 422
    assert response.json()["errorCode"] == "report_request_invalid"
    assert "private/secret/key" not in response.text

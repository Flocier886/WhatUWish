from __future__ import annotations

import httpx

from app.deepsearch.execution import DeepSearchEventPublisher, DeepSearchExecutionContext
from app.deepsearch.llm_client import OpenAiCompatibleDeepSearchWriter
from app.deepsearch.models import DeepSearchDeliveryDisposition, DeepSearchDeliveryMode, DeepSearchPhase, DeepSearchStartRequest, DeepSearchStatus, ResearchDepth
from app.deepsearch.prompts import MASTER_ANSWER_PROMPT, writer_prompt
from app.deepsearch.service import DeepSearchService, _budget
from app.deepsearch.settings import DeepSearchRuntimeSettings
from app.runtime.artifact_storage import StoredArtifact
from app.runtime.errors import ToolExecutionError
from app.tools.search_provider import SearchHit
from app.tools.web_security import FetchedResponse


class Provider:
    def search(self, _query, _limit):
        return [SearchHit("公开资料", "https://official.example/event", "official.example", "公开活动资料", "stub", 1)]


class Fetcher:
    def fetch(self, url, _timeout, _max_bytes):
        return FetchedResponse(url, 200, "text/plain", b"This is public source material about the event.", 0)


class Writer:
    def __init__(self, failure_code: str | None = None):
        self.failure_code = failure_code

    def write(self, _prompt, _materials):
        if self.failure_code:
            raise ToolExecutionError(self.failure_code, "writer unavailable")
        return "# 研究摘要\n\n活动有公开来源材料支持。[src_test]"


def settings() -> DeepSearchRuntimeSettings:
    return DeepSearchRuntimeSettings(provider="openai-compatible", model="test", base_url="https://example.invalid/v1", api_key="test")


def service(writer: Writer) -> DeepSearchService:
    return DeepSearchService(Provider(), Fetcher(), writer, settings())


def stub_artifact(monkeypatch):
    monkeypatch.setattr("app.deepsearch.service.persist_text", lambda *_args, **_kwargs: StoredArtifact(
        artifact_id="artifact-test", storage_type="local_artifact_storage", storage_key="test/source.md",
        storage_ref="test/source.md", file_name="deepsearch-source-package.md", file_size=12,
        mime_type="text/markdown", file_hash="hash-test",
    ))


def test_depth_changes_resource_budget_not_writer_rounds():
    assert _budget(ResearchDepth.QUICK, settings()).max_searches == 3
    assert _budget(ResearchDepth.STANDARD, settings()).max_fetches == 5
    assert _budget(ResearchDepth.DEEP, settings()).max_results_per_search == 8


def test_successful_search_delivers_artifact_and_minimal_stages(monkeypatch):
    stub_artifact(monkeypatch)
    publisher = DeepSearchEventPublisher(DeepSearchExecutionContext.create("caller-1"))

    result = service(Writer()).run_rich(DeepSearchStartRequest(topic="活动", questions=["公开背景"]), publisher=publisher)

    assert result.status is DeepSearchStatus.COMPLETED
    assert result.delivery_disposition is DeepSearchDeliveryDisposition.TERMINAL_COMPLETED
    assert result.artifacts and result.sources
    assert [event.phase for event in publisher.events] == [
        DeepSearchPhase.RESEARCH_STARTED, DeepSearchPhase.SEARCH_STARTED, DeepSearchPhase.SEARCH_COMPLETED,
        DeepSearchPhase.FETCH_STARTED, DeepSearchPhase.FETCH_COMPLETED, DeepSearchPhase.ARTIFACT_CREATED,
        DeepSearchPhase.RESEARCH_COMPLETED,
    ]
    assert all(event.phase is not DeepSearchPhase.RESEARCH_COMPLETED or event.presentation["kind"] == "deep_search.completed.v1" for event in publisher.events if event.presentation)


def test_writer_timeout_keeps_fetched_sources_and_delivers_partial_artifact(monkeypatch):
    stub_artifact(monkeypatch)
    publisher = DeepSearchEventPublisher(DeepSearchExecutionContext.create("caller-timeout"))

    result = service(Writer("deep_search_writer_timeout")).run_rich(
        DeepSearchStartRequest(topic="活动"), publisher=publisher
    )

    assert result.status is DeepSearchStatus.PARTIAL_SUCCESS
    assert result.delivery_disposition is DeepSearchDeliveryDisposition.TERMINAL_PARTIAL
    assert result.delivery_mode is DeepSearchDeliveryMode.SOURCE_MATERIALS
    assert result.artifacts and result.sources
    assert result.stop_reason == "deep_search_writer_timeout"
    assert any("自动综合" in item for item in result.limitations)
    completed = publisher.events[-1]
    assert completed.data["writerFailureCode"] == "deep_search_writer_timeout"
    assert "writer unavailable" not in str(result.model_dump(by_alias=True))


def test_writer_prompt_keeps_master_answer_prompt_rules_and_only_renders_four_inputs():
    rendered = writer_prompt("初音未来生日活动", "[src_1] 官方来源材料")

    assert "你是wwz自研的Deep Search模块的报告生成助手" in MASTER_ANSWER_PROMPT
    assert "###第二步：提取相关信息" in MASTER_ANSWER_PROMPT
    assert "每篇文章用html格式表示：<div>文章内容</div>" in MASTER_ANSWER_PROMPT
    assert "严禁放置于代码块内" in MASTER_ANSWER_PROMPT
    assert "初音未来生日活动" in rendered
    assert "[src_1] 官方来源材料" in rendered
    assert "{response_length}" not in rendered
    assert "{sub_qa}" not in rendered
    assert "{current_time}" not in rendered
    assert "{query}" not in rendered


def test_writer_uses_a_single_long_timeout_and_classifies_timeout_without_payload_leakage():
    def timeout_handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("private upstream response", request=_request)

    writer = OpenAiCompatibleDeepSearchWriter(
        settings(),
        transport=httpx.MockTransport(timeout_handler),
    )

    try:
        writer.write("private prompt", "private source material")
    except ToolExecutionError as error:
        assert error.error_code == "deep_search_writer_timeout"
        assert "private" not in error.error_message
    else:
        raise AssertionError("Writer timeout should surface a controlled error")


def test_standard_depth_uses_master_query_decomposition_then_one_reasoning_guided_follow_up(monkeypatch):
    class MasterFlowWriter:
        def __init__(self):
            self.calls: list[str] = []
            self.reasoning_calls = 0

        def write(self, prompt, _materials):
            self.calls.append(prompt)
            if prompt.startswith("你是一个任务分析专家"):
                return "需要补齐公开背景、近年案例与传播玩法，需要进行进一步检索"
            if prompt.startswith("Your goal is to generate sophisticated"):
                return "- 初音未来生日公开背景\n- 初音未来近年线上生日活动案例"
            if prompt.startswith("# 角色定义"):
                self.reasoning_calls += 1
                return '{"is_answer": 0, "rewrite_query": "初音未来生日活动传播玩法案例"}'
            return "# 研究摘要\n\n已综合多个公开来源。"

    class MultiProvider:
        def __init__(self):
            self.queries: list[str] = []

        def search(self, query, _limit):
            self.queries.append(query)
            suffix = len(self.queries)
            return [SearchHit(f"来源{suffix}", f"https://official.example/{suffix}", "official.example", "公开资料", "stub", suffix)]

    provider = MultiProvider()
    writer = MasterFlowWriter()
    stub_artifact(monkeypatch)
    publisher = DeepSearchEventPublisher(DeepSearchExecutionContext.create("master-style-flow"))
    active = DeepSearchService(provider, Fetcher(), writer, settings())

    result = active.run_rich(
        DeepSearchStartRequest(topic="初音未来生日线上活动", researchDepth=ResearchDepth.STANDARD),
        publisher=publisher,
    )

    assert result.status is DeepSearchStatus.COMPLETED
    assert set(provider.queries[:2]) == {"初音未来生日公开背景", "初音未来近年线上生日活动案例"}
    assert provider.queries[-1] == "初音未来生日活动传播玩法案例"
    assert writer.reasoning_calls == 1
    search_rounds = [event.data["iteration"] for event in publisher.events if event.phase is DeepSearchPhase.SEARCH_COMPLETED]
    assert search_rounds == [1, 2]


def test_new_runtime_config_overrides_legacy_config():
    value = DeepSearchRuntimeSettings.from_configuration({
        "research-runtime": {"model": "legacy", "api-base": "https://legacy.example/v1", "api-key": "legacy"},
        "deepsearch-runtime": {"model": "current", "api-base": "https://current.example/v1", "api-key": "current"},
    })
    assert value.model == "current"


def test_legacy_runtime_config_remains_supported():
    value = DeepSearchRuntimeSettings.from_configuration({
        "research-runtime": {"model": "legacy", "api-base": "https://legacy.example/v1", "api-key": "legacy"},
    })
    assert value.model == "legacy"


def test_master_chapters_process_materials_before_final_writer(monkeypatch):
    class ChapterWriter:
        def __init__(self):
            self.final_prompts: list[str] = []
            self.chapter_threads: list[str] = []

        def write(self, prompt, _materials):
            if prompt.startswith("你是专业的研究报告结构规划"):
                return '[{"title":"背景","content":"研究背景","search_queries":["背景查询"]},{"title":"案例","content":"研究案例","search_queries":["案例查询"]}]'
            if prompt.startswith("你是wwz自研的Deep Search模块的章节撰写助手"):
                self.chapter_threads.append(__import__("threading").current_thread().name)
                return "## 局部结论\n\n本章结论来自 [src]。"
            self.final_prompts.append(prompt)
            return "# 最终报告\n\n已合并章节结论。"

    class ChapterProvider:
        def search(self, query, _limit):
            return [SearchHit(query, f"https://official.example/{query}", "official.example", "snippet", "stub", 1)]

    class ChapterFetcher:
        def fetch(self, url, _timeout, _max_bytes):
            return FetchedResponse(url, 200, "text/plain", f"RAW BODY {url}".encode(), 0)

    writer = ChapterWriter()
    stub_artifact(monkeypatch)
    result = DeepSearchService(ChapterProvider(), ChapterFetcher(), writer, settings()).run_rich(
        DeepSearchStartRequest(topic="章节研究", researchDepth=ResearchDepth.STANDARD),
        publisher=DeepSearchEventPublisher(DeepSearchExecutionContext.create("chapter-input")),
    )

    assert result.status is DeepSearchStatus.COMPLETED
    assert len(writer.chapter_threads) == 2
    assert writer.final_prompts
    assert "局部结论" in writer.final_prompts[-1]
    assert "RAW BODY" not in writer.final_prompts[-1]
    assert all(item.get("chapterId") for item in result.sources)


def test_failed_chapter_does_not_clear_successful_chapter(monkeypatch):
    class ChapterWriter:
        def write(self, prompt, _materials):
            if prompt.startswith("你是专业的研究报告结构规划"):
                return '[{"title":"成功章","content":"成功研究","search_queries":["good"]},{"title":"失败章","content":"失败研究","search_queries":["bad"]}]'
            if prompt.startswith("你是wwz自研的Deep Search模块的章节撰写助手"):
                return "成功章节摘要"
            return "最终报告"

    class ChapterProvider:
        def search(self, query, _limit):
            if query == "bad":
                raise ToolExecutionError("provider_failed", "provider failed")
            return [SearchHit("成功来源", "https://official.example/good", "official.example", "snippet", "stub", 1)]

    stub_artifact(monkeypatch)
    result = DeepSearchService(ChapterProvider(), Fetcher(), ChapterWriter(), settings()).run_rich(
        DeepSearchStartRequest(topic="章节失败隔离"),
        publisher=DeepSearchEventPublisher(DeepSearchExecutionContext.create("chapter-failure")),
    )

    assert result.status is DeepSearchStatus.COMPLETED
    assert result.sources
    assert any("研究章节" in item for item in result.limitations)

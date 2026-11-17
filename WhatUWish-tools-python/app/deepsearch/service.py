"""DeepSearch 单主链：搜索、抓取、一次综合和来源材料交付。"""

from __future__ import annotations

import hashlib
import json
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Protocol
from urllib.parse import urlsplit

from app.deepsearch.execution import DeepSearchCancellationToken, DeepSearchEventPublisher, DeepSearchExecutionContext
from app.deepsearch.llm_client import DeepSearchWriter, OpenAiCompatibleDeepSearchWriter
from app.deepsearch.models import (
    DEEP_SEARCH_ARTIFACT_VERSION, CandidateSource, DeepSearchArtifactDescriptor, DeepSearchBudget,
    DeepSearchCallerAction, DeepSearchDeliveryDisposition, DeepSearchDeliveryMode, DeepSearchPhase,
    DeepSearchChapter, DeepSearchReport, DeepSearchResearchOutcome, DeepSearchResult, DeepSearchRunResult, DeepSearchRunState,
    DeepSearchStartRequest, DeepSearchStatus, FetchResult, ResearchDepth, SourceStatus,
)
from app.deepsearch.presentation import build_presentation
from app.deepsearch.prompts import (
    query_decompose_prompt,
    query_decompose_think_prompt,
    reasoning_prompt,
    chapter_reflection_prompt,
    chapter_structure_prompt,
    chapter_summary_prompt,
    writer_prompt,
)
from app.deepsearch.settings import DeepSearchRuntimeSettings
from app.runtime.artifact_storage import ArtifactStorageError, ArtifactStorageScope, StoredArtifact, persist_text
from app.runtime.errors import ToolExecutionError
from app.tools.content_fetcher import ContentFetcher
from app.tools.search_provider import SearchHit, SearchProvider, create_search_provider, normalize_public_http_url
from app.tools.web_fetch import WebFetchTool


class Fetcher(Protocol):
    def fetch(self, url: str, timeout_seconds: float, max_response_bytes: int):
        """返回通过安全检查后的公开 HTTP 内容。"""


@dataclass
class DeepSearchService:
    search_provider: SearchProvider
    fetcher: Fetcher
    writer: DeepSearchWriter
    settings: DeepSearchRuntimeSettings

    def run_rich(self, request: DeepSearchStartRequest, *, cancellation: DeepSearchCancellationToken | None = None,
                 publisher: DeepSearchEventPublisher | None = None) -> DeepSearchResult:
        execution = publisher.context if publisher else DeepSearchExecutionContext.create(request.execution_id)
        active_publisher = publisher or DeepSearchEventPublisher(execution)
        token = cancellation or DeepSearchCancellationToken()
        try:
            internal = self._execute(request, execution, active_publisher, token)
            return self._result(internal, execution, active_publisher.events)
        except ToolExecutionError as exc:
            status = DeepSearchStatus.CANCELLED if exc.error_code == "deep_search_cancelled" else DeepSearchStatus.FAILED
            phase = DeepSearchPhase.RESEARCH_CANCELLED if status is DeepSearchStatus.CANCELLED else DeepSearchPhase.RESEARCH_FAILED
            active_publisher.emit(phase, status, exc.error_message, {"errorCode": exc.error_code})
            return DeepSearchResult(
                executionId=execution.echo_execution_id, researchExecutionId=execution.research_execution_id,
                status=status, deliveryDisposition=failure_disposition(exc), researchOutcome=DeepSearchResearchOutcome.EVIDENCE_INSUFFICIENT,
                callerAction=failure_caller_action(exc), researchScopeFingerprint=research_scope_fingerprint(request),
                deliveryMode=DeepSearchDeliveryMode.INSUFFICIENT_EVIDENCE, summary=exc.error_message,
                presentationSummary=_presentation_summary(exc.error_message, []), stopReason=exc.error_code,
                limitations=[exc.error_message], stageSummaries=_stage_summaries(active_publisher.events),
            )

    def _execute(self, request: DeepSearchStartRequest, execution: DeepSearchExecutionContext,
                 publisher: DeepSearchEventPublisher, cancellation: DeepSearchCancellationToken) -> DeepSearchRunResult:
        budget = _budget(request.research_depth, self.settings)
        state = DeepSearchRunState(request=request, budget=budget)
        chapters = self._decompose_chapters(state, min(5, budget.max_searches), min(3, budget.max_results_per_search))
        state.chapters = chapters
        self._allocate_chapter_fetch_budgets(chapters, budget.max_fetches)
        pending_queries = [query for chapter in chapters for query in chapter.queries]
        publisher.emit(DeepSearchPhase.RESEARCH_STARTED, DeepSearchStatus.RUNNING, "DeepSearch 已启动，正在整理公开研究来源。", {
            "researchDepth": request.research_depth.value, "queryCount": len(pending_queries),
            "chapterCount": len(chapters),
        })
        cancellation.raise_if_cancelled()
        self._run_chapters(state, cancellation)
        state.sources = [source for chapter in chapters for source in chapter.sources]
        state.fetched = [item for chapter in chapters for item in chapter.fetched]
        state.queries = [query for chapter in chapters for query in chapter.queries]

        publisher.emit(DeepSearchPhase.SEARCH_STARTED, DeepSearchStatus.RUNNING, "正在并发搜索各研究章节的公开来源。", {
            "iteration": 1, "queryCount": len(state.queries), "chapterCount": len(chapters),
        })
        publisher.emit(DeepSearchPhase.SEARCH_COMPLETED, DeepSearchStatus.RUNNING, "各研究章节搜索完成。", {
            "iteration": 1, "queryCount": len(state.queries), "sourceCount": len(state.sources),
            "chapterCount": len(chapters),
        }, build_presentation(DeepSearchPhase.SEARCH_COMPLETED, state, {
            "iteration": 1, "queries": state.queries, "sources": state.sources,
        }))
        if any(chapter.reflection_performed for chapter in chapters):
            publisher.emit(DeepSearchPhase.SEARCH_COMPLETED, DeepSearchStatus.RUNNING, "章节反思补查完成。", {
                "iteration": 2, "queryCount": len(state.queries), "sourceCount": len(state.sources),
                "chapterCount": len(chapters),
            }, build_presentation(DeepSearchPhase.SEARCH_COMPLETED, state, {
                "iteration": 2, "queries": state.queries, "sources": state.sources,
            }))
        if state.fetched:
            publisher.emit(DeepSearchPhase.FETCH_STARTED, DeepSearchStatus.RUNNING, "正在整理各研究章节抓取的公开正文。", {
                "iteration": 1, "selectedCount": len(state.fetched), "chapterCount": len(chapters),
            })
            publisher.emit(DeepSearchPhase.FETCH_COMPLETED, DeepSearchStatus.RUNNING, "各研究章节正文处理完成。", {
                "iteration": 1, "selectedCount": len(state.fetched), "fetchedCount": len(state.fetched),
                "chapterCount": len(chapters),
            }, build_presentation(DeepSearchPhase.FETCH_COMPLETED, state, {
                "iteration": 1, "selectedSources": state.sources,
            }))

        if not state.sources:
            raise ToolExecutionError("deep_search_no_sources", "当前公开搜索未返回可用来源，无法形成研究材料。")
        if not state.fetched:
            raise ToolExecutionError("deep_search_no_fetchable_sources", "候选公开来源均未能抓取，无法形成可交付研究材料。")

        cancellation.raise_if_cancelled()
        report, disposition = self._write_or_materialize(state)
        artifact = self._persist(state, report, ArtifactStorageScope(execution_id=execution.research_execution_id, caller_scope="rich-api"))
        descriptor = self._descriptor(artifact, report, state)
        publisher.emit(DeepSearchPhase.ARTIFACT_CREATED, DeepSearchStatus.RUNNING, "研究来源材料已保存为 Artifact。", {
            "artifact": descriptor.model_dump(by_alias=True),
        }, build_presentation(DeepSearchPhase.ARTIFACT_CREATED, state, artifact=descriptor))
        final_status = DeepSearchStatus.COMPLETED if disposition is DeepSearchDeliveryDisposition.TERMINAL_COMPLETED else DeepSearchStatus.PARTIAL_SUCCESS
        publisher.emit(DeepSearchPhase.RESEARCH_COMPLETED, final_status, report.executive_summary, {
            "summary": report.executive_summary, "sourceCount": len(state.fetched),
            "researchOutcome": _outcome(report).value, "stopReason": _stop_reason(report),
            **_writer_failure_data(report),
        }, build_presentation(DeepSearchPhase.RESEARCH_COMPLETED, state, {
            "summary": report.executive_summary, "sourceCount": len(state.fetched),
            "researchOutcome": _outcome(report).value, "stopReason": _stop_reason(report),
            **_writer_failure_data(report),
        }))
        return DeepSearchRunResult(state=state, report=report, artifact=artifact, disposition=disposition)

    def _decompose_chapters(self, state: DeepSearchRunState, max_chapters: int,
                            max_search_queries: int) -> list[DeepSearchChapter]:
        """优先使用 master 的章节结构；旧查询拆分仅作为兼容回退。"""
        task = _research_task(state.request)
        try:
            response = self.writer.write(
                chapter_structure_prompt(task, max_chapters, max_search_queries), ""
            ).strip()
            chapters = _parse_chapters(response, max_chapters, max_search_queries)
            if chapters:
                return chapters
        except ToolExecutionError as exc:
            state.limitations.append("章节规划未完成（" + _writer_failure_label(exc.error_code) + "），已使用受控回退。")

        state.used_legacy_query_fallback = True
        queries = self._decompose_queries(state, min(5, state.budget.max_searches))
        return [DeepSearchChapter(
            chapter_id="C1", title=state.request.topic, content=task, order=1,
            queries=queries or [state.request.topic],
        )]

    @staticmethod
    def _allocate_chapter_fetch_budgets(chapters: list[DeepSearchChapter], total: int) -> None:
        if not chapters:
            return
        base, remainder = divmod(total, len(chapters))
        for index, chapter in enumerate(chapters):
            chapter.fetch_budget = base + (1 if index < remainder else 0)

    def _run_chapters(self, state: DeepSearchRunState,
                      cancellation: DeepSearchCancellationToken) -> None:
        max_workers = min(max(1, self.settings.search_concurrency), len(state.chapters))
        with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="deepsearch-chapter") as executor:
            futures = [executor.submit(self._process_chapter, chapter, state, cancellation)
                       for chapter in state.chapters]
            for future in as_completed(futures):
                cancellation.raise_if_cancelled()
                future.result()

    def _process_chapter(self, chapter: DeepSearchChapter, state: DeepSearchRunState,
                         cancellation: DeepSearchCancellationToken) -> None:
        chapter.status = "running"
        try:
            cancellation.raise_if_cancelled()
            chapter.queries = _unique_queries(chapter.queries or [chapter.content], [])[:state.budget.max_searches]
            chapter.sources = self._search_chapter(chapter, state, cancellation)
            selected = _select_sources(chapter.sources, chapter.fetch_budget, state.request.preferred_domains)
            chapter.fetched = self._fetch_chapter(selected, chapter, state, cancellation) if selected else []
            if not chapter.fetched:
                chapter.status = "failed"
                chapter.error_code = "deep_search_chapter_no_fetchable_sources"
                state.limitations.append("研究章节「" + chapter.title[:80] + "」没有可抓取来源，已保留其他章节结果。")
                return
            chapter.summary = self.writer.write(
                chapter_summary_prompt(state.request.topic, chapter.title, chapter.content, chapter.order,
                                       _chapter_materials(chapter, self.settings.max_context_chars), 1200), ""
            ).strip()
            if not chapter.summary:
                chapter.status = "failed"
                chapter.error_code = "deep_search_chapter_summary_empty"
                state.limitations.append("研究章节「" + chapter.title[:80] + "」摘要为空，已保留来源材料。")
                return

            reflection_enabled = state.used_legacy_query_fallback or os.getenv("DEEPSEARCH_LIGHT_REFLECTION", "0").lower() in {"1", "true", "yes"}
            if reflection_enabled and chapter.fetch_budget > len(chapter.fetched):
                self._reflect_chapter(chapter, state, cancellation)
            chapter.status = "completed"
        except ToolExecutionError as exc:
            if exc.error_code == "deep_search_cancelled":
                raise
            chapter.status = "failed"
            chapter.error_code = exc.error_code
            state.limitations.append("研究章节「" + chapter.title[:80] + "」未完成，已保留其他章节结果。")

    def _search_chapter(self, chapter: DeepSearchChapter, state: DeepSearchRunState,
                        cancellation: DeepSearchCancellationToken) -> list[CandidateSource]:
        candidates: dict[str, CandidateSource] = {}

        def search(query: str) -> tuple[str, list[SearchHit]]:
            cancellation.raise_if_cancelled()
            return query, self.search_provider.search(query, state.budget.max_results_per_search)

        with ThreadPoolExecutor(max_workers=min(self.settings.search_concurrency, len(chapter.queries)),
                                thread_name_prefix="deepsearch-chapter-search") as executor:
            futures = [executor.submit(search, query) for query in chapter.queries]
            for future in as_completed(futures):
                try:
                    _query, hits = future.result()
                except ToolExecutionError:
                    state.limitations.append("部分章节搜索请求未完成，已基于其余来源继续整理。")
                    continue
                for hit in hits:
                    url = normalize_public_http_url(hit.url)
                    if not url or url in candidates:
                        continue
                    candidates[url] = CandidateSource(
                        sourceId=_source_id(url), title=hit.title, url=url,
                        domain=(urlsplit(url).hostname or hit.domain).lower(),
                        snippet=hit.snippet, provider=hit.provider, rank=hit.rank,
                        chapterId=chapter.chapter_id,
                    )
        return sorted(candidates.values(), key=lambda item: (item.rank, item.url))

    def _fetch_chapter(self, selected: list[CandidateSource], chapter: DeepSearchChapter,
                       state: DeepSearchRunState,
                       cancellation: DeepSearchCancellationToken) -> list[FetchResult]:
        results: dict[str, FetchResult] = {}

        def fetch(source: CandidateSource) -> tuple[CandidateSource, FetchResult]:
            cancellation.raise_if_cancelled()
            response = self.fetcher.fetch(source.url, 20.0, 2 * 1024 * 1024)
            raw_text = response.content.decode("utf-8", errors="replace")
            title, content, _ = WebFetchTool._extract_content(raw_text, response.content_type, response.final_url)
            bounded = content[:100_000]
            if not bounded.strip():
                raise ToolExecutionError("content_empty", "来源正文为空")
            return source, FetchResult(sourceId=source.source_id, finalUrl=response.final_url,
                                       title=title or source.title, content=bounded,
                                       contentType=response.content_type, truncated=len(content) > len(bounded),
                                       contentHash=hashlib.sha256(bounded.encode("utf-8")).hexdigest(),
                                       chapterId=chapter.chapter_id)

        with ThreadPoolExecutor(max_workers=min(self.settings.fetch_concurrency, len(selected)),
                                thread_name_prefix="deepsearch-chapter-fetch") as executor:
            futures = {executor.submit(fetch, source): source for source in selected}
            for future in as_completed(futures):
                source = futures[future]
                try:
                    source, result = future.result()
                    source.status = SourceStatus.FETCHED
                    results[source.source_id] = result
                except ToolExecutionError as exc:
                    source.status = SourceStatus.FETCH_FAILED
                    source.fetch_error_code = exc.error_code
                    message = "部分章节来源无法抓取，已保留其余已获取材料。"
                    if message not in state.limitations:
                        state.limitations.append(message)
        return [results[source.source_id] for source in selected if source.source_id in results]

    def _reflect_chapter(self, chapter: DeepSearchChapter, state: DeepSearchRunState,
                         cancellation: DeepSearchCancellationToken) -> None:
        assessment = self.writer.write(
            reasoning_prompt(f"{state.request.topic} / 章节：{chapter.title}", chapter.queries, chapter.summary), ""
        )
        payload = _parse_reasoning(assessment)
        if payload.get("is_answer") in {1, "1", True}:
            return
        query = " ".join(str(payload.get("rewrite_query") or "").split())
        if not query or query.casefold() in {item.casefold() for item in chapter.queries}:
            return
        chapter.queries.append(query)
        chapter.reflection_performed = True
        cancellation.raise_if_cancelled()
        extra_sources = self._search_chapter(chapter, state, cancellation)
        known = {item.url for item in chapter.sources}
        chapter.sources.extend(item for item in extra_sources if item.url not in known)
        remaining = chapter.fetch_budget - len(chapter.fetched)
        selected = _select_sources([item for item in extra_sources if item.url not in known], remaining, state.request.preferred_domains)
        extra_fetched = self._fetch_chapter(selected, chapter, state, cancellation)
        chapter.fetched.extend(extra_fetched)
        if extra_fetched:
            chapter.summary = self.writer.write(
                chapter_reflection_prompt(state.request.topic, chapter.title, chapter.content, chapter.order,
                                          chapter.summary, _chapter_materials(chapter, self.settings.max_context_chars), 1200), ""
            ).strip() or chapter.summary

    def _decompose_queries(self, state: DeepSearchRunState, limit: int) -> list[str]:
        try:
            task = _research_task(state.request)
            thinking = self.writer.write(query_decompose_think_prompt(task), "").strip()
            response = self.writer.write(query_decompose_prompt(thinking, limit), "").strip()
            queries = _parse_markdown_queries(response)
            if queries:
                return queries[:limit]
            state.limitations.append("查询拆分未返回可用列表，已按原始研究范围继续搜索。")
        except ToolExecutionError as exc:
            state.limitations.append("查询拆分未完成（" + _writer_failure_label(exc.error_code) + "），已按原始研究范围继续搜索。")
        return _queries(state.request, limit)

    def _write_or_materialize(self, state: DeepSearchRunState) -> tuple[DeepSearchReport, DeepSearchDeliveryDisposition]:
        materials = _writer_materials(state, self.settings.max_context_chars)
        try:
            markdown = self.writer.write(writer_prompt(state.request.topic, materials), "").strip()
            summary = _summary(markdown) or "已基于抓取来源形成研究摘要。"
            return DeepSearchReport(summary, markdown, True), DeepSearchDeliveryDisposition.TERMINAL_COMPLETED
        except ToolExecutionError as exc:
            state.limitations.append("自动综合本次未完成（" + _writer_failure_label(exc.error_code) + "），已交付可阅读的来源材料包供后续使用。")
            return DeepSearchReport(
                "已交付抓取到的公开来源材料，自动综合未完成。",
                _source_material_markdown(state, exc.error_code),
                False,
                exc.error_code,
            ), DeepSearchDeliveryDisposition.TERMINAL_PARTIAL

    def _persist(self, state: DeepSearchRunState, report: DeepSearchReport, scope: ArtifactStorageScope) -> StoredArtifact:
        payload = {"schemaVersion": DEEP_SEARCH_ARTIFACT_VERSION, "topic": state.request.topic, "queries": state.queries,
                   "deliveryMode": "model_synthesized" if report.synthesized else "source_materials",
                   "limitations": _unique(state.limitations), "sources": _source_payload(state.sources)}
        document = "\n".join(["# DeepSearch 研究材料", "", "## 摘要", report.executive_summary, "", "## 研究整理", report.answer_markdown,
                                "", "## 来源目录", *[f"- [{item['sourceId']}] {item['title']} - {item['url']}" for item in _source_payload(state.sources)],
                                "", "## 限制", *[f"- {item}" for item in _unique(state.limitations)], "", f"<!-- {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))} -->"])
        try:
            return persist_text(document, "deepsearch-source-package.md", scope)
        except (ArtifactStorageError, OSError, ValueError) as exc:
            raise ToolExecutionError("deep_search_artifact_save_failed", "DeepSearch 研究材料无法保存为 Artifact。") from exc

    @staticmethod
    def _descriptor(artifact: StoredArtifact, report: DeepSearchReport, state: DeepSearchRunState) -> DeepSearchArtifactDescriptor:
        return DeepSearchArtifactDescriptor(artifactId=artifact.artifact_id, name="deepsearch-source-package", artifactType="research_source_package",
            artifactRole="output", visibility="visible", sourceType="deep_search", sourceName="deep_search_tool", fileName=artifact.file_name,
            storageType=artifact.storage_type, storageKey=artifact.storage_key, storageRef=artifact.storage_ref, contentSummary=report.executive_summary,
            mimeType=artifact.mime_type, fileSize=artifact.file_size, fileHash=artifact.file_hash,
            metadata={"schemaVersion": DEEP_SEARCH_ARTIFACT_VERSION, "sourceCount": len(state.fetched), "synthesized": report.synthesized})

    @staticmethod
    def _result(internal: DeepSearchRunResult, execution: DeepSearchExecutionContext, events) -> DeepSearchResult:
        state, report = internal.state, internal.report
        partial = internal.disposition is DeepSearchDeliveryDisposition.TERMINAL_PARTIAL
        return DeepSearchResult(executionId=execution.echo_execution_id, researchExecutionId=execution.research_execution_id,
            status=DeepSearchStatus.PARTIAL_SUCCESS if partial else DeepSearchStatus.COMPLETED, deliveryDisposition=internal.disposition,
            researchOutcome=_outcome(report), callerAction=DeepSearchCallerAction.NONE, researchScopeFingerprint=research_scope_fingerprint(state.request),
            deliveryMode=DeepSearchDeliveryMode.SOURCE_MATERIALS if partial else DeepSearchDeliveryMode.MODEL_SYNTHESIZED,
            summary=report.executive_summary, presentationSummary=_presentation_summary(report.executive_summary, state.limitations),
            sources=_source_payload(state.sources), evidence=[], limitations=_unique(state.limitations)[:24], stopReason=_stop_reason(report),
            stageSummaries=_stage_summaries(events), artifacts=[DeepSearchService._descriptor(internal.artifact, report, state)])


def _budget(depth: ResearchDepth, settings: DeepSearchRuntimeSettings) -> DeepSearchBudget:
    defaults = {ResearchDepth.QUICK: (3, 5, 3), ResearchDepth.STANDARD: (6, 6, 5), ResearchDepth.DEEP: (10, 8, 8)}[depth]
    return DeepSearchBudget(min(defaults[0], settings.max_searches), min(defaults[1], settings.max_results_per_search), min(defaults[2], settings.max_fetches))


def _queries(request: DeepSearchStartRequest, limit: int) -> list[str]:
    base = request.topic
    raw = request.questions or [base]
    queries = [f"{base} {item}" if item.casefold() not in base.casefold() else base for item in raw]
    return list(dict.fromkeys(" ".join(item.split()) for item in queries if item.strip()))[:limit]


def _research_task(request: DeepSearchStartRequest) -> str:
    if not request.questions:
        return request.topic
    return request.topic + "\n研究维度：" + "；".join(request.questions)


def _unique_queries(candidates: list[str], existing: list[str]) -> list[str]:
    seen = {" ".join(item.casefold().split()) for item in existing}
    result = []
    for candidate in candidates:
        normalized = " ".join(candidate.split())
        key = normalized.casefold()
        if normalized and key not in seen:
            seen.add(key)
            result.append(normalized)
    return result


def _parse_markdown_queries(value: str) -> list[str]:
    queries = []
    for line in value.splitlines():
        stripped = line.strip()
        if stripped.startswith("- "):
            queries.append(stripped[2:])
    return _unique_queries(queries, [])


def _parse_chapters(value: str, max_chapters: int, max_search_queries: int) -> list[DeepSearchChapter]:
    """解析 master 章节 Prompt 的 JSON，并兼容其 chapters/items 外壳。"""
    payload = value.strip()
    if payload.startswith("```"):
        payload = payload.split("\n", 1)[1] if "\n" in payload else ""
        payload = payload.rsplit("```", 1)[0].strip()
    try:
        parsed = json.loads(payload)
    except (TypeError, ValueError, json.JSONDecodeError):
        return []
    if isinstance(parsed, dict):
        parsed = parsed.get("chapters") or parsed.get("items") or []
    if not isinstance(parsed, list):
        return []
    chapters: list[DeepSearchChapter] = []
    for item in parsed:
        if isinstance(item, str):
            title = content = " ".join(item.split())
            raw_queries = [content]
        elif isinstance(item, dict):
            title = " ".join(str(item.get("title") or item.get("name") or "").split())
            content = " ".join(str(item.get("content") or item.get("description") or title).split())
            raw_queries = item.get("search_queries") or item.get("queries") or [content]
        else:
            continue
        if not title:
            continue
        queries = _unique_queries(
            [" ".join(str(query or "").split()) for query in (raw_queries if isinstance(raw_queries, list) else [raw_queries])],
            [],
        )[:max_search_queries]
        chapters.append(DeepSearchChapter(
            chapter_id=f"C{len(chapters) + 1}", title=title[:240], content=(content or title)[:600],
            order=len(chapters) + 1, queries=queries or [content or title],
        ))
        if len(chapters) >= max_chapters:
            break
    return chapters


def _parse_reasoning(value: str) -> dict[str, object]:
    payload = value.strip()
    if payload.startswith("```"):
        payload = payload.split("\n", 1)[1] if "\n" in payload else ""
        payload = payload.rsplit("```", 1)[0].strip()
    start, end = payload.find("{"), payload.rfind("}")
    if start < 0 or end <= start:
        return {}
    try:
        parsed = json.loads(payload[start:end + 1])
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _select_sources(sources: list[CandidateSource], limit: int, preferred_domains: list[str]) -> list[CandidateSource]:
    if limit <= 0:
        return []
    preferred = [item.casefold().strip() for item in preferred_domains if item.strip()]
    def preferred_domain(source: CandidateSource) -> bool:
        return any(source.domain == item or source.domain.endswith("." + item) for item in preferred)
    ordered = sorted(sources, key=lambda item: (not preferred_domain(item), item.rank, -len(item.snippet), item.url))
    result: list[CandidateSource] = []
    seen_domains: set[str] = set()
    for source in ordered:
        if source.domain not in seen_domains:
            result.append(source); seen_domains.add(source.domain)
        if len(result) == limit:
            return result
    for source in ordered:
        if source not in result:
            result.append(source)
        if len(result) == limit:
            break
    return result


def _writer_materials(state: DeepSearchRunState, limit: int) -> str:
    per_chapter = max(1600, (limit - 1800) // max(1, len(state.chapters)))
    blocks = [f"研究主题：{state.request.topic}", f"用户问题：{'；'.join(state.request.questions) or '未单列问题'}", "", "已完成的章节研究整理："]
    for chapter in sorted(state.chapters, key=lambda item: item.order):
        blocks.extend([f"## 章节 {chapter.order}：{chapter.title}", f"研究任务：{chapter.content}",
                       f"查询：{'；'.join(chapter.queries)}", chapter.summary[:per_chapter] or "（本章摘要不可用）"])
        for fetched in chapter.fetched:
            blocks.extend([f"来源 [{fetched.source_id}]：{fetched.title}", f"URL: {fetched.final_url}"])
        blocks.append("")
    return "\n".join(blocks)[:limit]


def _chapter_materials(chapter: DeepSearchChapter, limit: int) -> str:
    per_source = max(1800, (limit - 1200) // max(1, len(chapter.fetched)))
    blocks = [f"章节主题：{chapter.title}", f"章节任务：{chapter.content}",
              f"章节查询：{'；'.join(chapter.queries)}", "", "本章节检索材料："]
    for fetched in chapter.fetched:
        blocks.extend([
            f"## [{fetched.source_id}] {fetched.title}",
            f"URL: {fetched.final_url}",
            fetched.content[:per_source], "",
        ])
    return "\n".join(blocks)[:limit]


def _source_material_markdown(state: DeepSearchRunState, writer_failure_code: str) -> str:
    source_by_id = {item.source_id: item for item in state.sources}
    lines = ["# 已抓取公开来源", "", "自动综合未完成（" + _writer_failure_label(writer_failure_code) + "）；以下材料已保留，使用时请结合来源链接进行人工确认。"]
    for fetched in state.fetched:
        source = source_by_id[fetched.source_id]
        lines.extend(["", f"## {fetched.title}", f"- 来源：{fetched.final_url}", f"- 摘录：{fetched.content[:1800]}"])
    return "\n".join(lines)


def _source_payload(sources: list[CandidateSource]) -> list[dict[str, str]]:
    return [{"sourceId": item.source_id, "title": item.title, "url": item.url, "domain": item.domain,
             "status": item.status.value, "chapterId": item.chapter_id} for item in sources[:48]]


def _source_id(url: str) -> str:
    return "src_" + hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]


def _summary(markdown: str) -> str:
    return " ".join(line.lstrip("# ").strip() for line in markdown.splitlines() if line.strip())[:1200]


def _outcome(report: DeepSearchReport) -> DeepSearchResearchOutcome:
    return DeepSearchResearchOutcome.EVIDENCE_LIMITED if not report.synthesized else DeepSearchResearchOutcome.EVIDENCE_SUFFICIENT


def _stop_reason(report: DeepSearchReport) -> str:
    return report.writer_failure_code or "source_materials_delivered"


def _writer_failure_data(report: DeepSearchReport) -> dict[str, str]:
    return {} if not report.writer_failure_code else {"writerFailureCode": report.writer_failure_code}


def _writer_failure_label(code: str) -> str:
    labels = {
        "deep_search_writer_timeout": "自动综合请求超时",
        "deep_search_writer_transport_error": "自动综合网络请求失败",
        "deep_search_writer_authentication_failed": "自动综合认证失败",
        "deep_search_writer_upstream_unavailable": "自动综合上游暂不可用",
        "deep_search_writer_http_error": "自动综合上游返回异常",
        "deep_search_writer_invalid_response": "自动综合返回格式无效",
        "deep_search_writer_empty_response": "自动综合返回为空",
    }
    return labels.get(code, "自动综合未完成")


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(item for item in items if item))


def _stage_summaries(events) -> list[dict[str, str]]:
    return [{"sequence": event.sequence, "phase": event.phase.value, "status": event.status.value, "summary": event.summary} for event in events]


def _presentation_summary(summary: str, limitations: list[str]) -> str:
    result = " ".join(summary.split())[:900]
    if limitations:
        result += " 限制：" + "；".join(_unique(limitations)[:3])
    return result[:1200]


def research_scope_fingerprint(request: DeepSearchStartRequest) -> str:
    payload = {"topic": request.topic.casefold(), "questions": [item.casefold() for item in request.questions], "language": (request.language or "").casefold(),
               "preferredDomains": sorted(item.casefold() for item in request.preferred_domains), "researchDepth": request.research_depth.value}
    return "sha256:" + hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def failure_caller_action(error: ToolExecutionError) -> DeepSearchCallerAction:
    if error.error_code in {"deep_search_request_invalid", "deep_search_llm_not_configured", "research_llm_not_configured", "search_provider_not_configured", "search_provider_invalid_config", "search_provider_not_supported"}:
        return DeepSearchCallerAction.CHECK_CONFIGURATION
    if error.error_code == "search_provider_auth_failed" or error.diagnostics.get("category") == "authentication_failed":
        return DeepSearchCallerAction.REFRESH_OR_REPLACE_CREDENTIALS
    if error.error_code == "search_provider_rate_limited":
        return DeepSearchCallerAction.WAIT_FOR_PROVIDER_QUOTA
    if error.error_code.startswith("search_provider_"):
        return DeepSearchCallerAction.RETRY_LATER
    return DeepSearchCallerAction.USE_OTHER_CAPABILITY


def failure_disposition(error: ToolExecutionError) -> DeepSearchDeliveryDisposition:
    return DeepSearchDeliveryDisposition.CALLER_ACTION_REQUIRED if failure_caller_action(error) is not DeepSearchCallerAction.USE_OTHER_CAPABILITY else DeepSearchDeliveryDisposition.FAILED_UNRECOVERABLE


def create_deep_search_service(settings: DeepSearchRuntimeSettings | None = None) -> DeepSearchService:
    active = settings or DeepSearchRuntimeSettings.load()
    return DeepSearchService(create_search_provider(), ContentFetcher(), OpenAiCompatibleDeepSearchWriter(active), active)

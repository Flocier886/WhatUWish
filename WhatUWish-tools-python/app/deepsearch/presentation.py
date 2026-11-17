"""DeepSearch 新主链的受控展示投影。"""

from __future__ import annotations

from typing import Any
from urllib.parse import urlparse

from app.deepsearch.models import CandidateSource, DeepSearchArtifactDescriptor, DeepSearchPhase, DeepSearchRunState


def build_presentation(phase: DeepSearchPhase, state: DeepSearchRunState,
                       data: dict[str, Any] | None = None, artifact: DeepSearchArtifactDescriptor | None = None):
    if phase is DeepSearchPhase.SEARCH_COMPLETED:
        payload = data or {}
        sources = payload.get("sources") or state.sources
        return "deep_search.search_results.v1", {
            "iteration": int(payload.get("iteration") or 1), "queries": (payload.get("queries") or state.queries)[:8],
            "sources": [_source(source, True) for source in sources[:8]],
        }
    if phase is DeepSearchPhase.FETCH_COMPLETED:
        selected = (data or {}).get("selectedSources") or state.sources
        fetched = {item.source_id: item for item in state.fetched}
        return "deep_search.fetch_results.v1", {
            "iteration": int((data or {}).get("iteration") or 1),
            "sources": [{**_source(source, False), "status": source.status.value,
                         "contentLength": len(fetched[source.source_id].content) if source.source_id in fetched else 0,
                         "truncated": bool(fetched[source.source_id].truncated) if source.source_id in fetched else False,
                         "errorCode": source.fetch_error_code} for source in selected[:8]],
        }
    if phase is DeepSearchPhase.ARTIFACT_CREATED and artifact is not None:
        return "deep_search.artifact.v1", {"artifactId": artifact.artifact_id, "name": artifact.name,
            "artifactType": artifact.artifact_type, "mimeType": artifact.mime_type, "summary": artifact.content_summary,
            "fileSize": artifact.file_size}
    if phase is DeepSearchPhase.RESEARCH_COMPLETED:
        payload = data or {}
        return "deep_search.completed.v1", {"summary": str(payload.get("summary") or "")[:900],
            "evidenceCount": int(payload.get("sourceCount") or 0), "researchOutcome": payload.get("researchOutcome"),
            "stopReason": payload.get("stopReason"), "limitations": state.limitations[:3]}
    return None


def _source(source: CandidateSource, include_snippet: bool) -> dict[str, str]:
    result = {"sourceId": source.source_id, "title": source.title[:240], "url": _public_url(source.url),
              "domain": source.domain[:160], "provider": source.provider[:80]}
    if include_snippet:
        result["snippet"] = source.snippet[:360]
    return result


def _public_url(value: str) -> str:
    parsed = urlparse(value)
    return value if parsed.scheme in {"http", "https"} and parsed.netloc else ""

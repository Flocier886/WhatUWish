"""DeepSearch 的输入、运行状态与 Python -> Java 协议模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import Field, field_validator

from app.runtime.artifact_storage import StoredArtifact
from app.runtime.models import RuntimeModel


DEEP_SEARCH_RESULT_VERSION = "deep_search_result.v1"
DEEP_SEARCH_EVENT_VERSION = "deep_search_event.v1"
DEEP_SEARCH_ARTIFACT_VERSION = "research_source_package.v2"


class ResearchDepth(str, Enum):
    QUICK = "quick"
    STANDARD = "standard"
    DEEP = "deep"


class SourceStatus(str, Enum):
    CANDIDATE = "candidate"
    FETCHED = "fetched"
    FETCH_FAILED = "fetch_failed"


class DeepSearchPhase(str, Enum):
    RESEARCH_STARTED = "research_started"
    SEARCH_STARTED = "search_started"
    SEARCH_COMPLETED = "search_completed"
    FETCH_STARTED = "fetch_started"
    FETCH_COMPLETED = "fetch_completed"
    ARTIFACT_CREATED = "artifact_created"
    RESEARCH_COMPLETED = "research_completed"
    RESEARCH_FAILED = "research_failed"
    RESEARCH_CANCELLED = "research_cancelled"


class DeepSearchStatus(str, Enum):
    RUNNING = "running"
    COMPLETED = "completed"
    PARTIAL_SUCCESS = "partial_success"
    FAILED = "failed"
    CANCELLED = "cancelled"


class DeepSearchDeliveryDisposition(str, Enum):
    TERMINAL_COMPLETED = "terminal_completed"
    TERMINAL_PARTIAL = "terminal_partial"
    CALLER_ACTION_REQUIRED = "caller_action_required"
    FAILED_UNRECOVERABLE = "failed_unrecoverable"


class DeepSearchResearchOutcome(str, Enum):
    EVIDENCE_SUFFICIENT = "evidence_sufficient"
    EVIDENCE_LIMITED = "evidence_limited"
    EVIDENCE_INSUFFICIENT = "evidence_insufficient"


class DeepSearchCallerAction(str, Enum):
    NONE = "none"
    CHECK_CONFIGURATION = "check_configuration"
    REFRESH_OR_REPLACE_CREDENTIALS = "refresh_or_replace_credentials"
    WAIT_FOR_PROVIDER_QUOTA = "wait_for_provider_quota"
    RETRY_LATER = "retry_later"
    USE_OTHER_CAPABILITY = "use_other_capability"


class DeepSearchDeliveryMode(str, Enum):
    MODEL_SYNTHESIZED = "model_synthesized"
    SOURCE_MATERIALS = "source_materials"
    INSUFFICIENT_EVIDENCE = "insufficient_evidence"


class DeepSearchStartRequest(RuntimeModel):
    topic: str = Field(min_length=1, max_length=600)
    questions: list[str] = Field(default_factory=list, max_length=8)
    language: str | None = Field(default=None, max_length=32)
    preferred_domains: list[str] = Field(default_factory=list, alias="preferredDomains", max_length=8)
    research_depth: ResearchDepth = Field(default=ResearchDepth.STANDARD, alias="researchDepth")
    execution_id: str | None = Field(default=None, alias="executionId", max_length=160)

    @field_validator("topic", "execution_id")
    @classmethod
    def normalize_optional_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = " ".join(value.split())
        return normalized or None

    @field_validator("questions")
    @classmethod
    def normalize_questions(cls, values: list[str]) -> list[str]:
        normalized = list(dict.fromkeys(" ".join(item.split()) for item in values if " ".join(item.split())))
        if any(len(item) > 400 for item in normalized):
            raise ValueError("questions 单项不能超过 400 个字符")
        return normalized


class CandidateSource(RuntimeModel):
    source_id: str = Field(alias="sourceId")
    title: str
    url: str
    domain: str
    snippet: str = ""
    provider: str
    rank: int = Field(ge=1)
    status: SourceStatus = SourceStatus.CANDIDATE
    fetch_error_code: str | None = Field(default=None, alias="fetchErrorCode")
    chapter_id: str | None = Field(default=None, alias="chapterId")


class FetchResult(RuntimeModel):
    source_id: str = Field(alias="sourceId")
    final_url: str = Field(alias="finalUrl")
    title: str
    content: str
    content_type: str = Field(alias="contentType")
    truncated: bool = False
    content_hash: str = Field(alias="contentHash")
    chapter_id: str | None = Field(default=None, alias="chapterId")


class DeepSearchArtifactDescriptor(RuntimeModel):
    artifact_id: str = Field(alias="artifactId")
    name: str
    artifact_type: str = Field(alias="artifactType")
    artifact_role: str = Field(alias="artifactRole")
    visibility: str
    source_type: str = Field(alias="sourceType")
    source_name: str = Field(alias="sourceName")
    file_name: str = Field(alias="fileName")
    storage_type: str = Field(alias="storageType")
    storage_key: str = Field(alias="storageKey")
    storage_ref: str = Field(alias="storageRef")
    content_summary: str = Field(alias="contentSummary", max_length=1200)
    mime_type: str = Field(alias="mimeType")
    file_size: int = Field(alias="fileSize", ge=0)
    file_hash: str = Field(alias="fileHash")
    metadata: dict[str, Any] = Field(default_factory=dict)


class DeepSearchEvent(RuntimeModel):
    schema_version: str = Field(default=DEEP_SEARCH_EVENT_VERSION, alias="schemaVersion")
    event_id: str = Field(alias="eventId", min_length=1, max_length=80)
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    sequence: int = Field(ge=1)
    phase: DeepSearchPhase
    status: DeepSearchStatus
    occurred_at: datetime = Field(alias="occurredAt")
    summary: str = Field(min_length=1, max_length=1200)
    data: dict[str, Any] = Field(default_factory=dict)
    presentation: dict[str, Any] | None = None


class DeepSearchResult(RuntimeModel):
    schema_version: str = Field(default=DEEP_SEARCH_RESULT_VERSION, alias="schemaVersion")
    execution_id: str = Field(alias="executionId", min_length=1, max_length=160)
    research_execution_id: str = Field(alias="researchExecutionId", min_length=1, max_length=160)
    status: DeepSearchStatus
    delivery_disposition: DeepSearchDeliveryDisposition = Field(alias="deliveryDisposition")
    research_outcome: DeepSearchResearchOutcome = Field(default=DeepSearchResearchOutcome.EVIDENCE_INSUFFICIENT, alias="researchOutcome")
    caller_action: DeepSearchCallerAction = Field(default=DeepSearchCallerAction.NONE, alias="callerAction")
    research_scope_fingerprint: str = Field(alias="researchScopeFingerprint", min_length=16, max_length=96)
    delivery_mode: DeepSearchDeliveryMode = Field(alias="deliveryMode")
    summary: str = Field(min_length=1, max_length=1800)
    presentation_summary: str = Field(alias="presentationSummary", min_length=1, max_length=1200)
    sources: list[dict[str, Any]] = Field(default_factory=list, max_length=48)
    evidence: list[dict[str, Any]] = Field(default_factory=list, max_length=48)
    coverage_review: dict[str, Any] | None = Field(default=None, alias="coverageReview")
    limitations: list[str] = Field(default_factory=list, max_length=24)
    stop_reason: str = Field(alias="stopReason", min_length=1, max_length=300)
    stage_summaries: list[dict[str, Any]] = Field(default_factory=list, alias="stageSummaries", max_length=32)
    artifacts: list[DeepSearchArtifactDescriptor] = Field(default_factory=list, max_length=4)


@dataclass(frozen=True)
class DeepSearchBudget:
    max_searches: int
    max_results_per_search: int
    max_fetches: int


@dataclass
class DeepSearchChapter:
    """新版 master 的 ChapterState 在本项目运行时中的最小适配。"""

    chapter_id: str
    title: str
    content: str
    order: int
    queries: list[str] = field(default_factory=list)
    sources: list[CandidateSource] = field(default_factory=list)
    fetched: list[FetchResult] = field(default_factory=list)
    summary: str = ""
    status: str = "pending"
    error_code: str | None = None
    fetch_budget: int = 0
    reflection_performed: bool = False


@dataclass
class DeepSearchRunState:
    request: DeepSearchStartRequest
    budget: DeepSearchBudget
    queries: list[str] = field(default_factory=list)
    sources: list[CandidateSource] = field(default_factory=list)
    fetched: list[FetchResult] = field(default_factory=list)
    chapters: list[DeepSearchChapter] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)
    used_legacy_query_fallback: bool = False


@dataclass(frozen=True)
class DeepSearchReport:
    executive_summary: str
    answer_markdown: str
    synthesized: bool
    writer_failure_code: str | None = None


@dataclass(frozen=True)
class DeepSearchRunResult:
    state: DeepSearchRunState
    report: DeepSearchReport
    artifact: StoredArtifact
    disposition: DeepSearchDeliveryDisposition

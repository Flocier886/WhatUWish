"""DeepSearch 独立执行的取消和阶段事件基础设施。"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Event, Lock
from typing import Any, Callable
from uuid import uuid4

from app.deepsearch.models import DeepSearchEvent, DeepSearchPhase, DeepSearchStatus
from app.runtime.errors import ToolExecutionError


logger = logging.getLogger(__name__)


class DeepSearchCancellationToken:
    """由 HTTP 连接或调用方取消的协作式令牌。

    节点在发起 LLM、网络和文件副作用前调用 ``raise_if_cancelled``；已开始的
    阻塞请求由各自超时控制，令牌不会伪装成可强制中止的线程杀伤机制。
    """

    def __init__(self) -> None:
        self._cancelled = Event()
        self._reason = "调用方取消了 DeepSearch 研究。"

    def cancel(self, reason: str = "调用方取消了 DeepSearch 研究。") -> None:
        self._reason = reason[:300]
        self._cancelled.set()

    @property
    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()

    @property
    def reason(self) -> str:
        return self._reason

    def raise_if_cancelled(self) -> None:
        if self.is_cancelled:
            raise ToolExecutionError("deep_search_cancelled", self.reason)


@dataclass(frozen=True)
class DeepSearchExecutionContext:
    """本次独立研究执行的稳定、非 Java 语义身份。"""

    research_execution_id: str
    echo_execution_id: str
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    @classmethod
    def create(cls, requested_execution_id: str | None) -> "DeepSearchExecutionContext":
        generated = f"research-{uuid4().hex}"
        return cls(research_execution_id=generated, echo_execution_id=requested_execution_id or generated)


class DeepSearchEventPublisher:
    """以单调 sequence 发布受控领域事件，不保存网页正文或内部模型数据。"""

    def __init__(self, context: DeepSearchExecutionContext, sink: Callable[[DeepSearchEvent], None] | None = None) -> None:
        self._context = context
        self._sink = sink
        self._sequence = 0
        self._lock = Lock()
        self.events: list[DeepSearchEvent] = []

    @property
    def context(self) -> DeepSearchExecutionContext:
        """返回本发布器绑定的独立研究身份。"""
        return self._context

    def emit(
        self,
        phase: DeepSearchPhase,
        status: DeepSearchStatus,
        summary: str,
        data: dict[str, Any] | None = None,
        presentation: tuple[str, dict[str, Any]] | None = None,
    ) -> DeepSearchEvent:
        with self._lock:
            self._sequence += 1
            event = DeepSearchEvent(
                eventId=f"evt-{self._sequence:04d}",
                executionId=self._context.echo_execution_id,
                sequence=self._sequence,
                phase=phase,
                status=status,
                occurredAt=datetime.now(timezone.utc),
                summary=summary,
                data=data or {},
                presentation=(
                    {
                        "kind": presentation[0],
                        "schemaVersion": 1,
                        "data": presentation[1],
                    }
                    if presentation is not None
                    else None
                ),
            )
            self.events.append(event)
        # Keep diagnostics limited to externally visible stage metadata.
        logger.info(
            "DeepSearch stage emitted: researchExecutionId=%s executionId=%s sequence=%s phase=%s status=%s summaryLength=%s dataKeys=%s",
            self._context.research_execution_id,
            self._context.echo_execution_id,
            event.sequence,
            event.phase.value,
            event.status.value,
            len(event.summary),
            sorted(event.data.keys()),
        )
        if self._sink:
            self._sink(event)
        return event

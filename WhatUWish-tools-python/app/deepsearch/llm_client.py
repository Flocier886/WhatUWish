"""DeepSearch 单次来源综合的 OpenAI-compatible 客户端。"""

from __future__ import annotations

import logging
from typing import Protocol

import httpx

from app.deepsearch.settings import DeepSearchRuntimeSettings
from app.runtime.errors import ToolExecutionError
from app.runtime.http_utils import safe_upstream_error_detail

logger = logging.getLogger(__name__)


class DeepSearchWriter(Protocol):
    def write(self, system_prompt: str, materials: str) -> str:
        """基于已抓取来源生成 Markdown；不得自行检索。"""


class OpenAiCompatibleDeepSearchWriter:
    def __init__(self, settings: DeepSearchRuntimeSettings, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    def write(self, system_prompt: str, materials: str) -> str:
        payload = {
            "model": self._settings.model,
            "temperature": self._settings.temperature,
            "max_tokens": self._settings.max_output_tokens,
            "messages": ([{"role": "system", "content": system_prompt}] if system_prompt else [])
            + [{"role": "user", "content": materials}],
        }
        timeout = httpx.Timeout(self._settings.timeout_seconds, connect=min(10.0, self._settings.timeout_seconds))
        last_error: ToolExecutionError | None = None
        for _attempt in range(self._settings.max_retries + 1):
            try:
                with httpx.Client(timeout=timeout, transport=self._transport) as client:
                    response = client.post(self._endpoint(), headers={"Authorization": f"Bearer {self._settings.api_key}"}, json=payload)
            except httpx.TimeoutException:
                last_error = ToolExecutionError(
                    "deep_search_writer_timeout",
                    "DeepSearch 自动综合请求超时",
                    {"category": "timeout"},
                )
                logger.warning("DeepSearch writer attempt timed out, attempt=%s/%s", _attempt + 1, self._settings.max_retries + 1)
                continue
            except httpx.TransportError:
                last_error = ToolExecutionError(
                    "deep_search_writer_transport_error",
                    "DeepSearch 自动综合网络请求失败",
                    {"category": "transport_error"},
                )
                logger.warning("DeepSearch writer transport attempt failed, attempt=%s/%s", _attempt + 1, self._settings.max_retries + 1)
                continue
            if response.status_code in {401, 403}:
                raise ToolExecutionError(
                    "deep_search_writer_authentication_failed",
                    "DeepSearch 自动综合认证失败",
                    {"category": "authentication_failed"},
                )
            if response.status_code == 429 or response.status_code >= 500:
                last_error = ToolExecutionError(
                    "deep_search_writer_upstream_unavailable",
                    "DeepSearch 自动综合上游暂不可用",
                    {"category": "upstream_unavailable"},
                )
                logger.warning("DeepSearch writer upstream unavailable, status=%s, attempt=%s/%s", response.status_code, _attempt + 1, self._settings.max_retries + 1)
                continue
            if response.is_error:
                detail = safe_upstream_error_detail(response)
                raise ToolExecutionError("deep_search_writer_http_error", f"DeepSearch 自动综合返回 HTTP {response.status_code}" + (f"：{detail}" if detail else ""), {"category": "http_error"})
            try:
                content = response.json()["choices"][0]["message"]["content"]
            except (KeyError, IndexError, TypeError, ValueError) as exc:
                raise ToolExecutionError("deep_search_writer_invalid_response", "DeepSearch 自动综合没有返回有效内容", {"category": "invalid_response"}) from exc
            text = str(content or "").strip()
            if text:
                return text
            raise ToolExecutionError("deep_search_writer_empty_response", "DeepSearch 自动综合返回为空", {"category": "empty_response"})
        raise last_error or ToolExecutionError("deep_search_writer_failed", "DeepSearch 自动综合未完成", {"category": "unknown"})

    def _endpoint(self) -> str:
        base = self._settings.base_url.rstrip("/")
        return base if base.endswith("/chat/completions") else f"{base}/chat/completions" if base.endswith("/v1") else f"{base}/v1/chat/completions"

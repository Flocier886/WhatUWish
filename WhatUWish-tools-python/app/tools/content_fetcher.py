"""DeepSearch 正文提取策略。

搜索 Provider 只提供候选 URL。本模块在既有 `SafeWebFetcher` 的公开 URL / DNS /
重定向保护之上，为 DeepSearch 选择正文提取方式；它不会决定来源质量或证据结论。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import quote, urlsplit

import httpx

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.errors import ToolExecutionError
from app.tools.web_security import FetchedResponse, SafeWebFetcher, validate_public_http_url


DEFAULT_READER_BASE_URL = "https://r.jina.ai/"
DEFAULT_READER_MIN_CHARS = 240
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ContentFetchSettings:
    """正文提取配置；Reader 默认关闭，确保未配置时仍走既有安全直连抓取。"""

    strategy: str = "direct"
    reader_base_url: str = DEFAULT_READER_BASE_URL
    reader_api_key: str = ""
    reader_timeout_seconds: float = 20.0
    reader_min_content_chars: int = DEFAULT_READER_MIN_CHARS

    @classmethod
    def load(cls) -> "ContentFetchSettings":
        return cls.from_configuration(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "ContentFetchSettings":
        values = config_section(configuration, "web-content")
        strategy = str(values.get("strategy", "direct")).strip().lower() or "direct"
        if strategy not in {"direct", "jina_reader"}:
            raise ToolExecutionError("content_fetch_invalid_config", "web-content.strategy 仅支持 direct 或 jina_reader")
        try:
            timeout = float(values.get("reader-timeout-seconds", 20.0))
            minimum = int(values.get("reader-min-content-chars", DEFAULT_READER_MIN_CHARS))
        except (TypeError, ValueError) as exc:
            raise ToolExecutionError("content_fetch_invalid_config", "Reader 超时和正文最小长度必须是数字") from exc
        if timeout <= 0 or minimum < 1:
            raise ToolExecutionError("content_fetch_invalid_config", "Reader 超时和正文最小长度必须大于 0")
        return cls(
            strategy=strategy,
            reader_base_url=str(values.get("reader-base-url", DEFAULT_READER_BASE_URL)).strip() or DEFAULT_READER_BASE_URL,
            reader_api_key=str(values.get("reader-api-key", "")).strip(),
            reader_timeout_seconds=timeout,
            reader_min_content_chars=minimum,
        )


class ContentFetcher:
    """正文抓取器，Reader 异常始终回退到安全直连。"""

    def __init__(
        self,
        settings: ContentFetchSettings | None = None,
        *,
        safe_fetcher: SafeWebFetcher | None = None,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._settings = settings or ContentFetchSettings.load()
        self._safe_fetcher = safe_fetcher or SafeWebFetcher()
        self._transport = transport
        self.last_reader_fallback = False

    def fetch(self, url: str, timeout_seconds: float, max_response_bytes: int) -> FetchedResponse:
        # Reader 服务本身不是安全边界。先验证原始目标，之后无论 Reader 是否启用，直连回退
        # 仍会再由 SafeWebFetcher 检查重定向链，不能把 r.jina.ai 当作绕过 SSRF 的跳板。
        validate_public_http_url(url)
        self.last_reader_fallback = False
        if self._settings.strategy == "jina_reader":
            try:
                response = self._fetch_via_jina(url, timeout_seconds, max_response_bytes)
                logger.info(
                    "DeepSearch content fetched through Jina Reader: domain=%s contentBytes=%s",
                    _safe_domain(url),
                    len(response.content),
                )
                return response
            except ToolExecutionError as exc:
                self.last_reader_fallback = True
                logger.info(
                    "DeepSearch Jina Reader fallback to direct fetch: domain=%s errorCode=%s",
                    _safe_domain(url),
                    exc.error_code,
                )
        return self._safe_fetcher.fetch(url, timeout_seconds, max_response_bytes)

    def _fetch_via_jina(self, url: str, timeout_seconds: float, max_response_bytes: int) -> FetchedResponse:
        reader_url = self._settings.reader_base_url.rstrip("/") + "/" + quote(url, safe=":/?&=%#")
        headers = {"Accept": "text/markdown, text/plain;q=0.9"}
        if self._settings.reader_api_key:
            headers["Authorization"] = f"Bearer {self._settings.reader_api_key}"
        timeout = httpx.Timeout(min(timeout_seconds, self._settings.reader_timeout_seconds), connect=5.0)
        try:
            with httpx.Client(timeout=timeout, transport=self._transport) as client:
                response = client.get(reader_url, headers=headers)
        except httpx.TimeoutException as exc:
            raise ToolExecutionError("reader_timeout", "正文 Reader 请求超时") from exc
        except httpx.HTTPError as exc:
            raise ToolExecutionError("reader_request_failed", "正文 Reader 请求失败") from exc
        if response.is_error:
            raise ToolExecutionError("reader_request_failed", f"正文 Reader 返回 HTTP {response.status_code}")
        content = response.content
        if len(content) > max_response_bytes:
            raise ToolExecutionError("reader_response_too_large", "正文 Reader 返回内容超过大小上限")
        text = content.decode("utf-8", errors="replace").strip()
        if len(text) < self._settings.reader_min_content_chars:
            raise ToolExecutionError("reader_content_too_short", "正文 Reader 未返回足够的可用文本")
        return FetchedResponse(
            final_url=url,
            status_code=response.status_code,
            content_type="text/markdown",
            content=content,
            redirect_count=0,
        )


def _safe_domain(url: str) -> str:
    """日志只保留域名线索，避免保存完整用户目标 URL。"""
    return (urlsplit(url).hostname or "unknown").lower()

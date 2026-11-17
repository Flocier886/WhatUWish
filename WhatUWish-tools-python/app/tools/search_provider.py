"""可配置网页搜索 Provider 的统一适配与安全聚合。

本模块只把上游搜索结果转换为候选 URL 线索。它不判定网页是否可信、不抓取正文、
不调用研究模型；这些职责分别属于 DeepSearch 的选源、抓取和证据验证阶段。
"""

from __future__ import annotations

import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from threading import local
from typing import Any, Mapping, Protocol
from urllib.parse import urlsplit, urlunsplit

import httpx

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.errors import ToolExecutionError


DEFAULT_SEARCH_TIMEOUT_SECONDS = 15.0
DEFAULT_PROVIDER_CONCURRENCY = 3
DEFAULT_TRANSIENT_RETRIES = 2
DEFAULT_RETRY_DELAY_SECONDS = 0.35
SUPPORTED_PROVIDERS = {"grok", "gpt", "exa", "tavily", "brave"}
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SearchHit:
    """一个规范化搜索命中；所有字段均为上游线索，不是来源真实性断言。"""

    title: str
    url: str
    domain: str
    snippet: str
    provider: str
    rank: int
    published_at: str | None = None
    provider_names: tuple[str, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        result = {
            "title": self.title,
            "url": self.url,
            "domain": self.domain,
            "snippet": self.snippet,
            "provider": self.provider,
            "rank": self.rank,
        }
        if self.published_at:
            result["publishedAt"] = self.published_at
        if len(self.provider_names) > 1:
            result["providerNames"] = list(self.provider_names)
        return result


@dataclass(frozen=True)
class SearchResult:
    hits: list[SearchHit]
    summary: str | None = None
    provider: str | None = None


class SearchProvider(Protocol):
    """单个搜索上游的最小协议，便于离线替身和后续新 adapter 共用。"""

    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        """返回受统一 URL 约束的候选，不做正文抓取。"""


@dataclass(frozen=True)
class SearchProviderSettings:
    """单个搜索上游的配置；密钥只在进程内用于 HTTP 认证。"""

    provider: str
    api_base: str
    api_key: str
    timeout_seconds: float
    model: str = ""

    @classmethod
    def load(cls) -> "SearchProviderSettings":
        """保留旧调用方使用的单 Provider 入口，返回配置顺序中的第一个 Provider。"""
        return cls.load_many()[0]

    @classmethod
    def load_many(cls) -> list["SearchProviderSettings"]:
        return cls.from_configuration_many(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "SearchProviderSettings":
        return cls.from_configuration_many(configuration)[0]

    @classmethod
    def from_configuration_many(cls, configuration: Mapping[str, Any]) -> list["SearchProviderSettings"]:
        section = config_section(configuration, "web-search")
        configured = section.get("providers")
        if configured is None:
            # 兼容已有 application.local.yaml：不要求用户为了本次升级重复填写 Tavily 密钥。
            configured = [section]
        if not isinstance(configured, list) or not configured:
            raise ToolExecutionError("search_provider_not_configured", "web-search.providers 必须包含至少一个搜索 Provider 配置")
        settings = [cls._from_values(item) for item in configured if isinstance(item, Mapping)]
        if len(settings) != len(configured):
            raise ToolExecutionError("search_provider_invalid_config", "web-search.providers 中的每个 Provider 必须是对象")
        return settings

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> "SearchProviderSettings":
        """兼容旧环境变量部署入口；多 Provider 请使用正式 YAML 配置。"""
        import os

        env = environ if environ is not None else os.environ
        return cls._from_values({
            "provider": env.get("WEB_SEARCH_PROVIDER", ""),
            "api-base": env.get("WEB_SEARCH_API_BASE", ""),
            "api-key": env.get("WEB_SEARCH_API_KEY", ""),
            "timeout-seconds": env.get("WEB_SEARCH_TIMEOUT_SECONDS", DEFAULT_SEARCH_TIMEOUT_SECONDS),
        })

    @classmethod
    def _from_values(cls, values: Mapping[str, Any]) -> "SearchProviderSettings":
        provider = str(values.get("provider", "")).strip().lower()
        api_base = str(values.get("api-base", "")).strip()
        api_key = str(values.get("api-key", "")).strip()
        model = str(values.get("model", "")).strip()
        try:
            timeout_seconds = float(values.get("timeout-seconds", DEFAULT_SEARCH_TIMEOUT_SECONDS))
        except (TypeError, ValueError) as exc:
            raise ToolExecutionError("search_provider_invalid_config", "web-search.timeout-seconds 必须是数字") from exc
        if not provider or not api_base or not api_key:
            raise ToolExecutionError("search_provider_not_configured", "网页搜索 Provider 未配置；请在 config/application.local.yaml 的 web-search 段设置 api-key")
        if provider not in SUPPORTED_PROVIDERS:
            raise ToolExecutionError("search_provider_not_supported", f"不支持的网页搜索 Provider: {provider}")
        if provider in {"grok", "gpt"} and not model:
            raise ToolExecutionError("search_provider_invalid_config", f"web-search {provider} 必须配置 model")
        if timeout_seconds <= 0:
            raise ToolExecutionError("search_provider_invalid_config", "web-search.timeout-seconds 必须大于 0")
        return cls(provider=provider, api_base=api_base, api_key=api_key, timeout_seconds=timeout_seconds, model=model)


class TavilySearchProvider:
    """Tavily HTTP JSON API adapter；只处理 Tavily 协议差异。"""

    def __init__(
        self,
        settings: SearchProviderSettings,
        transport: httpx.BaseTransport | None = None,
        *,
        sleeper=time.sleep,
    ) -> None:
        self._settings = settings
        self._transport = transport
        self._sleeper = sleeper

    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        body = {"query": query, "max_results": limit, "search_depth": "basic", "include_answer": False}
        if allowed_domains:
            body["include_domains"] = list(allowed_domains)
        if blocked_domains:
            body["exclude_domains"] = list(blocked_domains)
        timeout = httpx.Timeout(self._settings.timeout_seconds, connect=min(self._settings.timeout_seconds, 5.0))
        response = self._request_with_retry(body, timeout, query_length=len(query))
        try:
            payload = response.json()
        except ValueError as exc:
            raise ToolExecutionError("search_provider_response_invalid", "网页搜索 Provider 返回了无法解析的响应") from exc
        raw_results = payload.get("results") if isinstance(payload, dict) else None
        if not isinstance(raw_results, list):
            raise ToolExecutionError("search_provider_response_invalid", "网页搜索 Provider 响应缺少 results 列表")
        hits: list[SearchHit] = []
        for item in raw_results:
            if not isinstance(item, dict):
                continue
            url = normalize_public_http_url(item.get("url"))
            if not url:
                continue
            parsed = urlsplit(url)
            hits.append(SearchHit(
                title=normalize_text(item.get("title")) or parsed.hostname or url,
                url=url,
                domain=(parsed.hostname or "").lower(),
                snippet=normalize_text(item.get("content") or item.get("snippet")),
                provider=self._settings.provider,
                rank=len(hits) + 1,
                published_at=normalize_text(item.get("published_date") or item.get("publishedAt")) or None,
                provider_names=(self._settings.provider,),
            ))
            if len(hits) >= limit:
                break
        return hits

    def _request_with_retry(self, body: dict[str, object], timeout: httpx.Timeout, *, query_length: int) -> httpx.Response:
        """只重试可恢复的上游故障，避免单次网络抖动将整轮研究直接变为零候选。"""
        last_error: ToolExecutionError | None = None
        for attempt in range(DEFAULT_TRANSIENT_RETRIES + 1):
            try:
                with httpx.Client(timeout=timeout, transport=self._transport) as client:
                    response = client.post(
                        self._settings.api_base,
                        headers={"Authorization": f"Bearer {self._settings.api_key}"},
                        json=body,
                    )
            except httpx.TimeoutException as exc:
                last_error = ToolExecutionError("search_provider_timeout", "网页搜索 Provider 请求超时")
                if self._retry_or_stop(last_error, attempt, query_length):
                    continue
                raise last_error from exc
            except httpx.HTTPError as exc:
                last_error = ToolExecutionError("search_provider_request_failed", "网页搜索 Provider 请求失败")
                if self._retry_or_stop(last_error, attempt, query_length):
                    continue
                raise last_error from exc

            if response.status_code in {401, 403}:
                raise ToolExecutionError("search_provider_auth_failed", "网页搜索 Provider 认证失败")
            if response.status_code == 429:
                last_error = ToolExecutionError("search_provider_rate_limited", "网页搜索 Provider 已限流，请稍后重试")
                if self._retry_or_stop(last_error, attempt, query_length, http_status=response.status_code):
                    continue
                raise last_error
            if response.status_code >= 500:
                last_error = ToolExecutionError("search_provider_request_failed", f"网页搜索 Provider 返回 HTTP {response.status_code}")
                if self._retry_or_stop(last_error, attempt, query_length, http_status=response.status_code):
                    continue
                raise last_error
            if response.is_error:
                # 其他 4xx 是请求或协议问题，重试无法改善且会浪费搜索预算。
                raise ToolExecutionError("search_provider_request_failed", f"网页搜索 Provider 返回 HTTP {response.status_code}")
            return response
        assert last_error is not None
        raise last_error

    def _retry_or_stop(
        self,
        error: ToolExecutionError,
        attempt: int,
        query_length: int,
        *,
        http_status: int | None = None,
    ) -> bool:
        if attempt >= DEFAULT_TRANSIENT_RETRIES:
            logger.warning(
                "DeepSearch Tavily search exhausted transient retries: errorCode=%s httpStatus=%s attempts=%s queryLength=%s",
                error.error_code,
                http_status,
                attempt + 1,
                query_length,
            )
            return False
        delay_seconds = DEFAULT_RETRY_DELAY_SECONDS * (attempt + 1)
        logger.warning(
            "DeepSearch Tavily search retry scheduled: errorCode=%s httpStatus=%s nextAttempt=%s delayMs=%s queryLength=%s",
            error.error_code,
            http_status,
            attempt + 2,
            round(delay_seconds * 1000),
            query_length,
        )
        self._sleeper(delay_seconds)
        return True


class SearchProviderSet:
    """按配置顺序聚合多个搜索 Provider，并隔离单个上游失败。"""

    def __init__(self, providers: list[SearchProvider], *, concurrency: int = DEFAULT_PROVIDER_CONCURRENCY) -> None:
        if not providers:
            raise ValueError("providers must not be empty")
        self._providers = providers
        self._concurrency = max(1, min(concurrency, len(providers)))
        # DeepSearch 会让多个计划 query 并发调用同一个 ProviderSet。失败信息只能属于
        # 当前 query 的执行线程，不能再放在跨 query 共享的可变 list 里。
        self._diagnostics = local()

    @property
    def last_failures(self) -> list[str]:
        """返回当前搜索调用线程的 Provider 失败摘要。"""
        return list(getattr(self._diagnostics, "last_failures", []))

    def search(self, query: str, limit: int) -> list[SearchHit]:
        self._diagnostics.last_failures = []
        indexed_hits: dict[int, list[SearchHit]] = {}
        with ThreadPoolExecutor(max_workers=self._concurrency, thread_name_prefix="deepsearch-provider") as executor:
            futures = {executor.submit(provider.search, query, limit): index for index, provider in enumerate(self._providers)}
            for future in as_completed(futures):
                index = futures[future]
                try:
                    indexed_hits[index] = future.result()
                except ToolExecutionError as exc:
                    self._diagnostics.last_failures.append(f"provider[{index}]={exc.error_code}")
                except Exception:
                    self._diagnostics.last_failures.append(f"provider[{index}]=unexpected_failure")
        if not indexed_hits:
            # 不丢失所有 Provider 失败后的真实类型：DeepSearch 需借此向 Java 传达“补配置”、“更换凭证”、“等待配额”或“稍后重试”，
            # 而不是把其统统压成模糊的 request_failed。
            failures = self.last_failures
            codes = [item.rsplit("=", 1)[-1] for item in failures]
            if "search_provider_auth_failed" in codes:
                code, message = "search_provider_auth_failed", "所有网页搜索 Provider 都因认证失败而不可用"
            elif "search_provider_rate_limited" in codes:
                code, message = "search_provider_rate_limited", "所有网页搜索 Provider 当前均被限流或配额耗尽"
            elif "search_provider_timeout" in codes:
                code, message = "search_provider_timeout", "所有网页搜索 Provider 请求均超时"
            else:
                code, message = "search_provider_request_failed", "所有网页搜索 Provider 都未能返回可用候选"
            raise ToolExecutionError(code, message, {"providerFailures": failures})
        return self._merge(indexed_hits, limit)

    def search_first(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        return self.search_first_with_summary(query, limit, allowed_domains, blocked_domains).hits

    def search_first_with_summary(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> SearchResult:
        """普通搜索沿 master 的优先级逐个尝试；研究搜索仍使用 search 聚合。"""
        self._diagnostics.last_failures = []
        summary_only: str | None = None
        summary_provider: str | None = None
        for index, provider in enumerate(self._providers):
            try:
                if hasattr(provider, "search_with_summary"):
                    result = provider.search_with_summary(query, limit, allowed_domains, blocked_domains)
                else:
                    result = SearchResult(provider.search(query, limit, allowed_domains, blocked_domains))
                filtered = filter_hits(result.hits, allowed_domains, blocked_domains)
                if filtered:
                    return SearchResult(self._merge({index: filtered}, limit), result.summary, filtered[0].provider)
                if result.summary and not summary_only:
                    summary_only = result.summary
                    summary_provider = result.provider
                self._diagnostics.last_failures.append(f"provider[{index}]=search_provider_no_results")
            except ToolExecutionError as exc:
                self._diagnostics.last_failures.append(f"provider[{index}]={exc.error_code}")
            except Exception:
                logger.exception("WebSearch provider[%s] failed unexpectedly", index)
                self._diagnostics.last_failures.append(f"provider[{index}]=unexpected_failure")
        failures = self.last_failures
        codes = [item.rsplit("=", 1)[-1] for item in failures]
        if summary_only:
            return SearchResult([], summary_only, summary_provider)
        if codes and all(code == "search_provider_no_results" for code in codes):
            return SearchResult([])
        if "search_provider_auth_failed" in codes:
            code, message = "search_provider_auth_failed", "网页搜索 Provider 认证失败，且其他上游没有可用来源"
        elif "search_provider_rate_limited" in codes:
            code, message = "search_provider_rate_limited", "网页搜索 Provider 被限流，且其他上游没有可用来源"
        elif "search_provider_timeout" in codes:
            code, message = "search_provider_timeout", "网页搜索 Provider 请求超时，且其他上游没有可用来源"
        else:
            code, message = "search_provider_no_results", "所有网页搜索 Provider 均未返回符合条件的公开来源"
        raise ToolExecutionError(code, message, {"providerFailures": failures})

    @staticmethod
    def _merge(indexed_hits: Mapping[int, list[SearchHit]], limit: int) -> list[SearchHit]:
        # 先按 Provider 配置顺序稳定遍历，之后才按 URL 去重；网络完成顺序不会影响结果。
        merged: dict[str, tuple[int, SearchHit, set[str]]] = {}
        for provider_index in sorted(indexed_hits):
            for hit in indexed_hits[provider_index]:
                url = normalize_public_http_url(hit.url)
                if not url:
                    continue
                names = set(hit.provider_names or (hit.provider,))
                existing = merged.get(url)
                if existing is None:
                    merged[url] = (provider_index, hit, names)
                else:
                    existing[2].update(names)
        ordered = sorted(merged.values(), key=lambda item: (item[0], item[1].rank, -len(item[1].snippet), item[1].url))
        return [SearchHit(
            title=hit.title,
            # 外部替身或未来 Provider 也必须经过统一 URL 规范化；否则 fragment 相同的
            # 页面虽然用规范化键去重，向下游暴露的 URL 却仍可能不稳定。
            url=normalize_public_http_url(hit.url) or hit.url,
            domain=(urlsplit(normalize_public_http_url(hit.url) or hit.url).hostname or hit.domain).lower(),
            snippet=hit.snippet,
            provider=hit.provider,
            rank=rank,
            published_at=hit.published_at,
            provider_names=tuple(sorted(names)),
        ) for rank, (_, hit, names) in enumerate(ordered[:limit], start=1)]


def create_search_provider() -> SearchProvider:
    """从正式配置构建 Provider 集合，保留配置顺序。"""
    from app.tools.search_provider_adapters import BraveSearchProvider, ExaSearchProvider, GptSearchProvider, GrokSearchProvider

    adapters = {"grok": GrokSearchProvider, "gpt": GptSearchProvider, "exa": ExaSearchProvider,
                "tavily": TavilySearchProvider, "brave": BraveSearchProvider}
    providers: list[SearchProvider] = []
    for settings in SearchProviderSettings.load_many():
        providers.append(adapters[settings.provider](settings))
    return SearchProviderSet(providers)


_DOMAIN_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$", re.IGNORECASE)


def parse_domain_filters(allowed: Any, blocked: Any) -> tuple[tuple[str, ...], tuple[str, ...]]:
    def parse(value: Any, name: str) -> tuple[str, ...]:
        if value is None:
            return ()
        if not isinstance(value, list) or len(value) > 20:
            raise ToolExecutionError("invalid_argument", f"{name} 必须是最多 20 个域名的数组")
        result = []
        for item in value:
            if not isinstance(item, str) or not _DOMAIN_RE.fullmatch(item.strip()):
                raise ToolExecutionError("invalid_argument", f"{name} 包含无效域名")
            result.append(item.strip().lower())
        return tuple(dict.fromkeys(result))

    allowed_domains = parse(allowed, "allowed_domains")
    blocked_domains = parse(blocked, "blocked_domains")
    if allowed_domains and blocked_domains:
        raise ToolExecutionError("invalid_argument", "allowed_domains 与 blocked_domains 不能同时指定")
    return allowed_domains, blocked_domains


def filter_hits(hits: list[SearchHit], allowed: tuple[str, ...], blocked: tuple[str, ...]) -> list[SearchHit]:
    def matches(host: str, domain: str) -> bool:
        return host == domain or host.endswith("." + domain)

    result = []
    for hit in hits:
        url = normalize_public_http_url(hit.url)
        if not url:
            continue
        host = (urlsplit(url).hostname or "").lower()
        if allowed and not any(matches(host, domain) for domain in allowed):
            continue
        if blocked and any(matches(host, domain) for domain in blocked):
            continue
        result.append(hit)
    return result


def normalize_public_http_url(value: Any) -> str | None:
    """归一化上游 URL，丢弃不能成为后续抓取候选的值。"""
    raw = normalize_text(value)
    if not raw:
        return None
    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError:
        return None
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        return None
    hostname = parsed.hostname.lower()
    netloc = hostname
    if port and not ((parsed.scheme.lower() == "http" and port == 80) or (parsed.scheme.lower() == "https" and port == 443)):
        netloc = f"{hostname}:{port}"
    return urlunsplit((parsed.scheme.lower(), netloc, parsed.path or "/", parsed.query, ""))


def normalize_text(value: Any) -> str:
    return " ".join(str(value or "").split())

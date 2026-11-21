"""master WebSearchTool 协议的 Python 适配；结果统一交给 SearchProviderSet。"""

from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlsplit

import httpx

from app.runtime.errors import ToolExecutionError
from app.tools.search_provider import SearchHit, SearchProviderSettings, SearchResult, normalize_public_http_url, normalize_text


class _HttpSearchProvider:
    def __init__(self, settings: SearchProviderSettings, transport: httpx.BaseTransport | None = None) -> None:
        self.settings = settings
        self.transport = transport

    def _request(self, method: str, *, body: dict[str, Any] | None = None,
                 params: dict[str, Any] | None = None, headers: dict[str, str] | None = None) -> dict[str, Any]:
        timeout = httpx.Timeout(self.settings.timeout_seconds, connect=min(5.0, self.settings.timeout_seconds))
        try:
            with httpx.Client(timeout=timeout, transport=self.transport) as client:
                response = client.request(method, self.settings.api_base, json=body, params=params, headers=headers)
        except httpx.TimeoutException as exc:
            raise ToolExecutionError("search_provider_timeout", "网页搜索 Provider 请求超时") from exc
        except httpx.HTTPError as exc:
            raise ToolExecutionError("search_provider_request_failed", "网页搜索 Provider 请求失败") from exc
        if response.status_code in {401, 403}:
            raise ToolExecutionError("search_provider_auth_failed", "网页搜索 Provider 认证失败或缺少搜索权限")
        if response.status_code == 429:
            raise ToolExecutionError("search_provider_rate_limited", "网页搜索 Provider 已限流")
        if response.is_error:
            raise ToolExecutionError("search_provider_request_failed", f"网页搜索 Provider 返回 HTTP {response.status_code}")
        try:
            payload = response.json()
        except ValueError as exc:
            raise ToolExecutionError("search_provider_response_invalid", "网页搜索 Provider 响应不是有效 JSON") from exc
        if not isinstance(payload, dict) or payload.get("error"):
            raise ToolExecutionError("search_provider_response_invalid", "网页搜索 Provider 返回错误或无效响应")
        return payload

    def _hits(self, items: list[Any], limit: int, *, snippet_fields: tuple[str, ...]) -> list[SearchHit]:
        hits = []
        seen = set()
        for item in items:
            if not isinstance(item, dict):
                continue
            url = normalize_public_http_url(item.get("url") or item.get("source_url") or item.get("link"))
            if not url or url in seen:
                continue
            seen.add(url)
            snippet = next((normalize_text(item.get(key)) for key in snippet_fields if normalize_text(item.get(key))), "")
            hits.append(SearchHit(
                title=normalize_text(item.get("title") or item.get("name")) or url,
                url=url, domain=(urlsplit(url).hostname or "").lower(), snippet=snippet[:500],
                provider=self.settings.provider, rank=len(hits) + 1,
                published_at=normalize_text(item.get("publishedDate") or item.get("published_at")) or None,
                provider_names=(self.settings.provider,),
            ))
            if len(hits) >= limit:
                break
        return hits


class ExaSearchProvider(_HttpSearchProvider):
    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        body: dict[str, Any] = {"query": query, "numResults": limit, "useAutoprompt": True,
                                "contents": {"text": {"maxCharacters": 2000}}}
        if allowed_domains:
            body["includeDomains"] = list(allowed_domains)
        if blocked_domains:
            body["excludeDomains"] = list(blocked_domains)
        payload = self._request("POST", body=body, headers={"x-api-key": self.settings.api_key})
        items = payload.get("results")
        if not isinstance(items, list):
            raise ToolExecutionError("search_provider_response_invalid", "Exa 响应缺少 results")
        return self._hits(items, limit, snippet_fields=("text", "extract", "summary", "snippet"))


def _filtered_query(query: str, allowed: tuple[str, ...], blocked: tuple[str, ...]) -> str:
    prefix = "(" + " OR ".join(f"site:{domain}" for domain in allowed) + ") " if allowed else ""
    return prefix + " ".join(f"-site:{domain}" for domain in blocked) + (" " if blocked else "") + query


class BraveSearchProvider(_HttpSearchProvider):
    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        payload = self._request("GET", params={"q": _filtered_query(query, allowed_domains, blocked_domains), "count": limit},
                                headers={"X-Subscription-Token": self.settings.api_key, "Accept": "application/json"})
        web = payload.get("web")
        items = web.get("results") if isinstance(web, dict) else None
        if not isinstance(items, list):
            raise ToolExecutionError("search_provider_response_invalid", "Brave 响应缺少 web.results")
        return self._hits(items, limit, snippet_fields=("description",))


class GptSearchProvider(_HttpSearchProvider):
    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        return self.search_with_summary(query, limit, allowed_domains, blocked_domains).hits

    def search_with_summary(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> SearchResult:
        tool: dict[str, Any] = {"type": "web_search_preview"}
        if allowed_domains:
            tool["filters"] = {"allowed_domains": list(allowed_domains)}
        elif blocked_domains:
            tool["filters"] = {"blocked_domains": list(blocked_domains)}
        payload = self._request("POST", body={"model": self.settings.model, "input": query, "tools": [tool]},
                                headers={"Authorization": f"Bearer {self.settings.api_key}"})
        output = payload.get("output")
        if output is not None and not isinstance(output, list):
            raise ToolExecutionError("search_provider_response_invalid", "GPT Responses 响应缺少 output")
        citations = []
        summary = str(payload.get("output_text") or "").strip()
        for item in output or []:
            if not isinstance(item, dict):
                continue
            if item.get("type") == "web_search_call":
                action = item.get("action")
                sources = action.get("sources") if isinstance(action, dict) else None
                for source in sources if isinstance(sources, list) else []:
                    if isinstance(source, dict):
                        citations.append(source)
                    elif isinstance(source, str):
                        citations.append({"url": source})
            for part in item.get("content", []) if isinstance(item.get("content"), list) else []:
                if not isinstance(part, dict):
                    continue
                if not summary:
                    summary = str(part.get("text") or "").strip()
                for annotation in part.get("annotations", []) if isinstance(part.get("annotations"), list) else []:
                    if isinstance(annotation, dict) and annotation.get("type") == "url_citation":
                        citations.append({**annotation, "snippet": part.get("text", "")})
        return SearchResult(self._hits(citations, limit, snippet_fields=("snippet",)), summary or None, "gpt")


_MARKDOWN_LINK = re.compile(r"\[([^\]]+)\]\((https?://[^\s)]+)\)")
_BARE_URL = re.compile(r"https?://[^\s<>\])]+")


class GrokSearchProvider(_HttpSearchProvider):
    def search(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> list[SearchHit]:
        return self.search_with_summary(query, limit, allowed_domains, blocked_domains).hits

    def search_with_summary(self, query: str, limit: int, allowed_domains: tuple[str, ...] = (), blocked_domains: tuple[str, ...] = ()) -> SearchResult:
        filtered_query = _filtered_query(query, allowed_domains, blocked_domains)
        body = {"model": self.settings.model, "temperature": 0, "stream": False,
                "messages": [
                    {"role": "system", "content": "You are a web search assistant. Use the web_search tool. Return concise findings with source titles and URLs as markdown links."},
                    {"role": "user", "content": "Search the web and summarize results for: " + filtered_query},
                ], "tools": [{"type": "web_search"}], "tool_choice": "auto"}
        headers = {"Authorization": f"Bearer {self.settings.api_key}"}
        first_error: ToolExecutionError | None = None
        try:
            result = self._parse(self._request("POST", body=body, headers=headers), limit)
            if result.hits or result.summary:
                return result
        except ToolExecutionError as exc:
            first_error = exc
        body.pop("tools")
        body.pop("tool_choice")
        body["messages"][0]["content"] = "You are a web search assistant. Use live web search. Return concise findings with source titles and URLs as markdown links."
        body["search_parameters"] = {"mode": "on", "return_citations": True, "max_search_results": limit}
        try:
            return self._parse(self._request("POST", body=body, headers=headers), limit)
        except ToolExecutionError:
            if first_error is not None:
                raise first_error
            raise

    def _parse(self, payload: dict[str, Any], limit: int) -> SearchResult:
        choices = payload.get("choices")
        first = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
        message = first.get("message") if isinstance(first.get("message"), dict) else {}
        citations = []
        for owner in (payload, first, message):
            values = owner.get("citations")
            if isinstance(values, list):
                citations.extend({"url": value} if isinstance(value, str) else value for value in values)
        annotations = message.get("annotations")
        if isinstance(annotations, list):
            citations.extend(value for value in annotations if isinstance(value, dict))
        content = message.get("content")
        if isinstance(content, list):
            content = "".join(part if isinstance(part, str) else str(part.get("text", ""))
                              for part in content if isinstance(part, (str, dict)))
        if not content:
            content = first.get("text")
        if not citations and isinstance(content, str):
            citations.extend({"title": title, "url": url} for title, url in _MARKDOWN_LINK.findall(content))
        if not citations and isinstance(content, str):
            citations.extend({"url": url} for url in _BARE_URL.findall(content))
        if isinstance(content, str) and content.strip():
            citations = [{**item, "snippet": item.get("snippet") or content} if isinstance(item, dict) else item for item in citations]
        return SearchResult(self._hits(citations, limit, snippet_fields=("snippet", "text", "description")),
                            str(content or "").strip() or None, "grok")

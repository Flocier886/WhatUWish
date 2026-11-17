import json

import httpx
import pytest

from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest
from app.tools.search_provider import SearchProviderSettings, TavilySearchProvider, normalize_public_http_url
from app.tools.web_search import WebSearchTool


def settings() -> SearchProviderSettings:
    return SearchProviderSettings(
        provider="tavily",
        api_base="https://search.provider.test/search",
        api_key="test-secret",
        timeout_seconds=10,
    )


def test_tavily_provider_normalizes_results_and_respects_limit():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.headers["authorization"] == "Bearer test-secret"
        assert request.url == httpx.URL("https://search.provider.test/search")
        assert json.loads(request.content) == {
            "query": "agent research",
            "max_results": 2,
            "search_depth": "basic",
            "include_answer": False,
        }
        return httpx.Response(
            200,
            json={
                "results": [
                    {
                        "title": "  Research source  ",
                        "url": "HTTPS://Example.COM:443/path?q=1#fragment",
                        "content": "  concise\n source snippet ",
                        "published_date": "2026-07-28",
                    },
                    {"url": "ftp://not-a-public-web-source.test/download"},
                    {"url": "https://example.com:bad-port/"},
                    {
                        "title": "Second source",
                        "url": "https://second.example.org/guide",
                        "snippet": "Second snippet",
                    },
                    {"title": "Ignored by limit", "url": "https://third.example.org/"},
                ]
            },
        )

    hits = TavilySearchProvider(settings(), transport=httpx.MockTransport(handler)).search("agent research", 2)

    assert [hit.to_dict() for hit in hits] == [
        {
            "title": "Research source",
            "url": "https://example.com/path?q=1",
            "domain": "example.com",
            "snippet": "concise source snippet",
            "provider": "tavily",
            "rank": 1,
            "publishedAt": "2026-07-28",
        },
        {
            "title": "Second source",
            "url": "https://second.example.org/guide",
            "domain": "second.example.org",
            "snippet": "Second snippet",
            "provider": "tavily",
            "rank": 2,
        },
    ]


@pytest.mark.parametrize(
    ("status_code", "expected_error"),
    [(401, "search_provider_auth_failed"), (403, "search_provider_auth_failed"), (429, "search_provider_rate_limited"), (500, "search_provider_request_failed")],
)
def test_tavily_provider_translates_http_failures(status_code: int, expected_error: str):
    provider = TavilySearchProvider(
        settings(),
        transport=httpx.MockTransport(lambda request: httpx.Response(status_code)),
    )

    with pytest.raises(ToolExecutionError) as error:
        provider.search("agent research", 1)
    assert error.value.error_code == expected_error


def test_tavily_provider_translates_timeout_and_invalid_response():
    timeout_provider = TavilySearchProvider(
        settings(),
        transport=httpx.MockTransport(lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("timeout", request=request))),
    )
    with pytest.raises(ToolExecutionError) as error:
        timeout_provider.search("agent research", 1)
    assert error.value.error_code == "search_provider_timeout"

    invalid_provider = TavilySearchProvider(
        settings(),
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"unexpected": []})),
    )
    with pytest.raises(ToolExecutionError) as error:
        invalid_provider.search("agent research", 1)
    assert error.value.error_code == "search_provider_response_invalid"


def test_tavily_provider_retries_a_transient_failure_before_returning_hits():
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(503)
        return httpx.Response(200, json={
            "results": [{"title": "Recovered", "url": "https://docs.example/recovered", "content": "usable"}]
        })

    delays: list[float] = []
    provider = TavilySearchProvider(
        settings(),
        transport=httpx.MockTransport(handler),
        sleeper=delays.append,
    )

    hits = provider.search("agent research", 1)

    assert [hit.url for hit in hits] == ["https://docs.example/recovered"]
    assert calls == 2
    assert delays == [0.35]


@pytest.mark.parametrize(
    "raw_url",
    ["", "ftp://example.com/file", "https://user@example.com/", "https://example.com:bad-port/"],
)
def test_normalize_public_http_url_discards_invalid_candidates(raw_url: str):
    assert normalize_public_http_url(raw_url) is None


def test_web_search_returns_controlled_missing_provider_failure(monkeypatch):
    def missing_provider():
        raise ToolExecutionError(
            "search_provider_not_configured",
            "网页搜索 Provider 未配置，请在 config/application.local.yaml 的 web-search 段设置 api-key。",
        )

    # 运行时配置现在可来自 application.local.yaml；测试必须显式隔离生产
    # Provider，不能靠清空当前进程环境变量误判为“未配置”。
    monkeypatch.setattr("app.tools.web_search.create_search_provider", missing_provider)

    response = WebSearchTool().execute(ToolExecuteRequest(toolName="web_search", arguments={"query": "agent research"}))

    assert response.success is False
    assert response.error_code == "search_provider_not_configured"
    assert "application.local.yaml" in response.llm_observation


def test_provider_settings_support_old_single_provider_and_new_provider_list():
    old = SearchProviderSettings.from_configuration({
        "web-search": {"provider": "tavily", "api-base": "https://old.example/search", "api-key": "old-key"}
    })
    new = SearchProviderSettings.from_configuration_many({
        "web-search": {"providers": [
            {"provider": "tavily", "api-base": "https://one.example/search", "api-key": "one-key"},
            {"provider": "tavily", "api-base": "https://two.example/search", "api-key": "two-key"},
        ]}
    })

    assert old.api_base == "https://old.example/search"
    assert [item.api_base for item in new] == ["https://one.example/search", "https://two.example/search"]

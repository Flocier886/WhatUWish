import json

import httpx
import pytest

from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest
from app.tools.search_provider import SearchHit, SearchProviderSet, SearchProviderSettings, parse_domain_filters
from app.tools.search_provider_adapters import BraveSearchProvider, ExaSearchProvider, GptSearchProvider, GrokSearchProvider
from app.tools.web_search import WebSearchTool


def setting(provider: str) -> SearchProviderSettings:
    return SearchProviderSettings(provider, f"https://{provider}.example/search", "test-key", 5, "test-model")


def hit(provider: str, url: str) -> SearchHit:
    return SearchHit("Source", url, httpx.URL(url).host, "Evidence", provider, 1)


def test_exa_and_brave_follow_master_protocol_and_domain_filters():
    def exa_handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["x-api-key"] == "test-key"
        assert json.loads(request.content) == {
            "query": "research", "numResults": 2, "useAutoprompt": True,
            "contents": {"text": {"maxCharacters": 2000}}, "includeDomains": ["example.org"],
        }
        return httpx.Response(200, json={"results": [{"title": "Exa", "url": "https://example.org/page", "text": "source text"}]})

    exa = ExaSearchProvider(setting("exa"), httpx.MockTransport(exa_handler))
    assert exa.search("research", 2, ("example.org",))[0].snippet == "source text"

    def brave_handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["x-subscription-token"] == "test-key"
        assert request.url.params["q"] == "-site:blocked.org research"
        assert request.url.params["count"] == "2"
        return httpx.Response(200, json={"web": {"results": [{"title": "Brave", "url": "https://example.org/page", "description": "summary"}]}})

    brave = BraveSearchProvider(setting("brave"), httpx.MockTransport(brave_handler))
    assert brave.search("research", 2, blocked_domains=("blocked.org",))[0].provider == "brave"


def test_gpt_uses_responses_citations_only():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body == {"model": "test-model", "input": "research", "tools": [
            {"type": "web_search_preview", "filters": {"allowed_domains": ["example.org"]}}]}
        return httpx.Response(200, json={"output": [{"content": [{"text": "Finding", "annotations": [
            {"type": "url_citation", "title": "Cited", "url": "https://example.org/page#section"}]}]}]})

    provider = GptSearchProvider(setting("gpt"), httpx.MockTransport(handler))
    assert [item.url for item in provider.search("research", 2, ("example.org",))] == ["https://example.org/page"]


def test_grok_falls_back_to_search_parameters_and_extracts_citation():
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if "tools" in body:
            return httpx.Response(400, json={"error": "unsupported tool"})
        return httpx.Response(200, json={"citations": [{"title": "Cited", "url": "https://example.org/page"}],
                                         "choices": [{"message": {"content": "Finding"}}]})

    provider = GrokSearchProvider(setting("grok"), httpx.MockTransport(handler))
    assert provider.search("research", 2)[0].url == "https://example.org/page"
    assert requests[0]["tools"] == [{"type": "web_search"}]
    assert requests[1]["search_parameters"]["return_citations"] is True


def test_search_first_falls_back_and_enforces_domain_boundary():
    called = []

    class Provider:
        def __init__(self, name: str, result):
            self.name, self.result = name, result

        def search(self, query, limit, allowed=(), blocked=()):
            called.append(self.name)
            if isinstance(self.result, Exception):
                raise self.result
            return self.result

    providers = SearchProviderSet([
        Provider("first", ToolExecutionError("search_provider_timeout", "timeout")),
        Provider("second", [hit("second", "https://fakeexample.org/page")]),
        Provider("third", [hit("third", "https://sub.example.org/page")]),
    ])
    results = providers.search_first("research", 3, ("example.org",))
    assert [item.provider for item in results] == ["third"]
    assert called == ["first", "second", "third"]
    assert len(providers.last_failures) == 2


@pytest.mark.parametrize("allowed,blocked", [(["example.org"], ["other.org"]), (["https://example.org"], []), ("example.org", [])])
def test_invalid_domain_filters_fail_before_search(allowed, blocked):
    with pytest.raises(ToolExecutionError) as error:
        parse_domain_filters(allowed, blocked)
    assert error.value.error_code == "invalid_argument"


def test_web_search_exposes_provider_and_sources(monkeypatch):
    class Provider:
        def search_first_with_summary(self, query, limit, allowed, blocked):
            from app.tools.search_provider import SearchResult

            assert (allowed, blocked) == (("example.org",), ())
            return SearchResult([hit("exa", "https://example.org/page")])

    monkeypatch.setattr("app.tools.web_search.create_search_provider", lambda: Provider())
    response = WebSearchTool().execute(ToolExecuteRequest(
        toolName="web_search", arguments={"query": "research", "allowed_domains": ["example.org"]}))
    assert response.success and response.metadata["provider"] == "exa"
    assert "https://example.org/page" in response.llm_observation


def test_multi_provider_settings_keep_config_order_and_require_native_model():
    settings = SearchProviderSettings.from_configuration_many({"web-search": {"providers": [
        {"provider": "gpt", "api-base": "https://api.openai.com/v1/responses", "api-key": "fake", "model": "gpt-4.1"},
        {"provider": "exa", "api-base": "https://api.exa.ai/search", "api-key": "fake"},
        {"provider": "tavily", "api-base": "https://api.tavily.com/search", "api-key": "fake"},
    ]}})
    assert [item.provider for item in settings] == ["gpt", "exa", "tavily"]
    with pytest.raises(ToolExecutionError) as error:
        SearchProviderSettings.from_configuration({"web-search": {
            "providers": [{"provider": "grok", "api-base": "https://api.x.ai/v1/chat/completions", "api-key": "fake"}]}})
    assert error.value.error_code == "search_provider_invalid_config"


def test_empty_search_is_distinct_from_upstream_failure():
    class EmptyProvider:
        def search(self, query, limit, allowed=(), blocked=()):
            return []

    assert SearchProviderSet([EmptyProvider()]).search_first("unmatched", 3) == []

    class FailedProvider:
        def search(self, query, limit, allowed=(), blocked=()):
            raise ToolExecutionError("search_provider_auth_failed", "invalid key")

    with pytest.raises(ToolExecutionError) as error:
        SearchProviderSet([EmptyProvider(), FailedProvider()]).search_first("unmatched", 3)
    assert error.value.error_code == "search_provider_auth_failed"


def test_deepsearch_aggregate_keeps_other_provider_when_one_fails():
    class Provider:
        def __init__(self, name, result):
            self.name, self.result = name, result

        def search(self, query, limit):
            if isinstance(self.result, Exception):
                raise self.result
            return self.result

    providers = SearchProviderSet([
        Provider("grok", ToolExecutionError("search_provider_rate_limited", "limited")),
        Provider("exa", [hit("exa", "https://example.org/page")]),
        Provider("brave", [hit("brave", "https://example.org/page#anchor"), hit("brave", "https://other.org/page")]),
    ])
    results = providers.search("research", 3)
    assert [item.url for item in results] == ["https://example.org/page", "https://other.org/page"]
    assert results[0].provider_names == ("brave", "exa")
    assert providers.last_failures == ["provider[0]=search_provider_rate_limited"]


def test_native_search_summary_is_kept_with_or_without_citations(monkeypatch):
    gpt = GptSearchProvider(setting("gpt"), httpx.MockTransport(
        lambda request: httpx.Response(200, json={"output_text": "Native research summary", "output": []})))
    assert gpt.search_with_summary("research", 2).summary == "Native research summary"

    class NativeProvider:
        def search_with_summary(self, query, limit, allowed=(), blocked=()):
            from app.tools.search_provider import SearchResult

            return SearchResult([], "Native research summary", "gpt")

    provider_set = SearchProviderSet([NativeProvider()])
    assert provider_set.search_first_with_summary("research", 2).provider == "gpt"
    monkeypatch.setattr("app.tools.web_search.create_search_provider", lambda: provider_set)
    response = WebSearchTool().execute(ToolExecuteRequest(toolName="web_search", arguments={"query": "research"}))
    assert response.success
    assert response.structured["summary"] == "Native research summary"
    assert response.structured["results"] == []
    assert "无引用来源" in response.llm_observation


def test_native_summary_falls_through_to_cited_provider():
    from app.tools.search_provider import SearchResult

    class NativeProvider:
        def search_with_summary(self, query, limit, allowed=(), blocked=()):
            return SearchResult([], "Uncited summary", "gpt")

    class CitedProvider:
        def search(self, query, limit, allowed=(), blocked=()):
            return [hit("exa", "https://example.org/source")]

    result = SearchProviderSet([NativeProvider(), CitedProvider()]).search_first_with_summary("research", 2)
    assert result.provider == "exa"
    assert [item.url for item in result.hits] == ["https://example.org/source"]

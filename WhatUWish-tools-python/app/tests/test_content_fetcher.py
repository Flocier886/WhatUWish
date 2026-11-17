from __future__ import annotations

import httpx

from app.tools.content_fetcher import ContentFetchSettings, ContentFetcher
from app.tools.web_security import FetchedResponse


class DirectFetcher:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def fetch(self, url: str, _timeout: float, _max_bytes: int) -> FetchedResponse:
        self.calls.append(url)
        return FetchedResponse(url, 200, "text/plain", b"direct fallback content", 0)


def test_reader_failure_falls_back_to_safe_direct_fetch(monkeypatch):
    direct = DirectFetcher()
    fetcher = ContentFetcher(ContentFetchSettings(strategy="jina_reader", reader_min_content_chars=100),
                             safe_fetcher=direct, transport=httpx.MockTransport(lambda _request: httpx.Response(503)))
    monkeypatch.setattr("app.tools.content_fetcher.validate_public_http_url", lambda url: url)

    result = fetcher.fetch("https://public.example/guide", 5, 2048)

    assert result.content == b"direct fallback content"
    assert direct.calls == ["https://public.example/guide"]
    assert fetcher.last_reader_fallback is True


def test_reader_success_does_not_skip_public_url_validation(monkeypatch):
    direct = DirectFetcher()
    content = ("# Reader result\n\n" + "public content " * 30).encode()
    fetcher = ContentFetcher(ContentFetchSettings(strategy="jina_reader", reader_min_content_chars=100), safe_fetcher=direct,
                             transport=httpx.MockTransport(lambda _request: httpx.Response(200, content=content)))
    monkeypatch.setattr("app.tools.content_fetcher.validate_public_http_url", lambda url: url)

    result = fetcher.fetch("https://public.example/guide", 5, 4096)

    assert result.content == content
    assert direct.calls == []
    assert fetcher.last_reader_fallback is False

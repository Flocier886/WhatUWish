import socket

import httpx
import pytest

from app.runtime.errors import ToolExecutionError
from app.runtime.artifact_storage import resolve_storage_key
from app.runtime.models import ToolExecuteRequest
from app.tools.web_fetch import WebFetchTool
from app.tools.web_security import FetchedResponse, SafeWebFetcher, validate_public_http_url


def resolver_for(addresses: dict[str, str]):
    def resolve(host: str, port: int, **kwargs):
        ip = addresses[host]
        family = socket.AF_INET6 if ":" in ip else socket.AF_INET
        return [(family, socket.SOCK_STREAM, 6, "", (ip, port))]

    return resolve


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "http://localhost/internal",
        "http://service.localhost/internal",
        "http://127.0.0.1/internal",
        "http://[::1]/internal",
        "http://example.com:bad-port/",
    ],
)
def test_validate_public_http_url_rejects_restricted_targets(url: str):
    resolver = resolver_for({"127.0.0.1": "127.0.0.1", "::1": "::1", "example.com": "8.8.8.8"})

    with pytest.raises(ToolExecutionError) as error:
        validate_public_http_url(url, resolver)
    assert error.value.error_code == "fetch_target_not_allowed"


def test_validate_public_http_url_rejects_dns_failure_and_non_global_ip():
    def dns_failure(host: str, port: int, **kwargs):
        raise socket.gaierror("not found")

    with pytest.raises(ToolExecutionError) as error:
        validate_public_http_url("https://missing.example/", dns_failure)
    assert error.value.error_code == "fetch_dns_failed"

    with pytest.raises(ToolExecutionError) as error:
        validate_public_http_url("https://internal.example/", resolver_for({"internal.example": "10.0.0.5"}))
    assert error.value.error_code == "fetch_target_not_allowed"


def test_safe_fetcher_rejects_redirect_to_private_target_before_request():
    requested_urls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested_urls.append(str(request.url))
        return httpx.Response(302, headers={"location": "http://internal.example/private"}, request=request)

    fetcher = SafeWebFetcher(
        resolver=resolver_for({"public.example": "8.8.8.8", "internal.example": "10.0.0.5"}),
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ToolExecutionError) as error:
        fetcher.fetch("https://public.example/start", 5, 1024)
    assert error.value.error_code == "fetch_target_not_allowed"
    assert requested_urls == ["https://public.example/start"]


def test_safe_fetcher_enforces_content_type_and_response_size():
    resolver = resolver_for({"public.example": "8.8.8.8"})

    binary_fetcher = SafeWebFetcher(
        resolver=resolver,
        transport=httpx.MockTransport(lambda request: httpx.Response(200, headers={"content-type": "image/png"}, content=b"image")),
    )
    with pytest.raises(ToolExecutionError) as error:
        binary_fetcher.fetch("https://public.example/image", 5, 1024)
    assert error.value.error_code == "fetch_content_type_not_allowed"

    oversized_fetcher = SafeWebFetcher(
        resolver=resolver,
        transport=httpx.MockTransport(lambda request: httpx.Response(200, headers={"content-type": "text/plain"}, content=b"012345")),
    )
    with pytest.raises(ToolExecutionError) as error:
        oversized_fetcher.fetch("https://public.example/large", 5, 5)
    assert error.value.error_code == "fetch_response_too_large"


@pytest.mark.parametrize("content_type", ["text/html; charset=utf-8", "text/markdown", "text/plain"])
def test_safe_fetcher_accepts_allowed_text_mime_types(content_type: str):
    fetcher = SafeWebFetcher(
        resolver=resolver_for({"public.example": "8.8.8.8"}),
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, headers={"content-type": content_type}, content=b"public text", request=request)
        ),
    )

    response = fetcher.fetch("https://public.example/document", 5, 1024)

    assert response.content == b"public text"
    assert response.content_type == content_type.split(";", 1)[0]


def test_safe_fetcher_translates_timeout():
    fetcher = SafeWebFetcher(
        resolver=resolver_for({"public.example": "8.8.8.8"}),
        transport=httpx.MockTransport(lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("timeout", request=request))),
    )

    with pytest.raises(ToolExecutionError) as error:
        fetcher.fetch("https://public.example/slow", 5, 1024)
    assert error.value.error_code == "fetch_timeout"


def test_web_fetch_registers_complete_page_as_artifact_with_bounded_observation(monkeypatch, tmp_path):
    extracted_content = "First fact. " + ("A" * 600) + " Second fact."

    class StubFetcher:
        def fetch(self, url: str, timeout_seconds: float, max_response_bytes: int) -> FetchedResponse:
            return FetchedResponse(
                final_url="https://public.example/article",
                status_code=200,
                content_type="text/html",
                content=("<html><title>Research Article</title><body><script>ignore()</script><p>"
                         + extracted_content + "</p></body></html>").encode("utf-8"),
                redirect_count=1,
            )

    monkeypatch.setattr("app.tools.web_fetch.SafeWebFetcher", StubFetcher)
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_TYPE", "local_artifact_storage")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_S3_ENABLED", "false")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))

    response = WebFetchTool().execute(ToolExecuteRequest(toolName="web_fetch", arguments={"url": "https://public.example/start"}))

    assert response.success is True
    assert response.structured["title"] == "Research Article"
    assert "content" not in response.structured
    assert response.structured["sourceContentChars"] == len(extracted_content)
    assert response.structured["extractor"] == "beautifulsoup"
    assert response.metadata["redirectCount"] == 1
    assert len(response.artifacts) == 1
    artifact = response.artifacts[0]
    assert artifact.artifact_id == response.structured["artifactId"]
    assert "storageKey" not in response.structured
    assert "storageType" not in response.structured
    assert "storageKey" not in response.llm_observation
    assert "storageType" not in response.llm_observation
    assert "artifact_read" in response.llm_observation
    assert "sourceArtifactIds" in response.llm_observation
    assert f"inputs/artifacts/{artifact.artifact_id}-web-page.txt" in response.llm_observation
    assert resolve_storage_key(artifact.storage_key).read_text(encoding="utf-8") == extracted_content
    assert "Second fact." not in response.llm_observation
    assert extracted_content[:500] in response.llm_observation
    assert extracted_content[:501] not in response.llm_observation


def test_web_fetch_does_not_claim_success_when_artifact_storage_fails(monkeypatch):
    class StubFetcher:
        def fetch(self, url: str, timeout_seconds: float, max_response_bytes: int) -> FetchedResponse:
            return FetchedResponse(
                final_url="https://public.example/article",
                status_code=200,
                content_type="text/plain",
                content=b"trusted source content",
                redirect_count=0,
            )

    monkeypatch.setattr("app.tools.web_fetch.SafeWebFetcher", StubFetcher)
    monkeypatch.setattr("app.tools.web_fetch.persist_text", lambda *args: (_ for _ in ()).throw(OSError("disk unavailable")))

    response = WebFetchTool().execute(ToolExecuteRequest(toolName="web_fetch", arguments={"url": "https://public.example/start"}))

    assert response.success is False
    assert response.error_code == "artifact_storage_error"
    assert response.artifacts == []


@pytest.mark.parametrize("degraded", [False, True])
def test_web_fetch_prompt_preserves_source_artifact_and_reports_extract_state(monkeypatch, tmp_path, degraded):
    class StubFetcher:
        def fetch(self, url, timeout_seconds, max_response_bytes):
            return FetchedResponse("https://public.example/page", 200, "text/plain", b"Primary source fact.", 0)

    monkeypatch.setattr("app.tools.web_fetch.SafeWebFetcher", StubFetcher)
    monkeypatch.setattr("app.tools.web_fetch.WebFetchTool._extract_with_prompt", staticmethod(lambda prompt, content: (
        ("Primary source fact.", True, "deep_search_writer_timeout") if degraded else ("Extracted fact.", False, None))))
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_TYPE", "local_artifact_storage")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_S3_ENABLED", "false")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    response = WebFetchTool().execute(ToolExecuteRequest(
        toolName="web_fetch", arguments={"url": "https://public.example/page", "prompt": "What is the fact?"}))
    assert response.success
    assert response.structured["extractDegraded"] is degraded
    assert response.structured["artifactId"] == response.artifacts[0].artifact_id
    assert resolve_storage_key(response.artifacts[0].storage_key).read_text(encoding="utf-8") == "Primary source fact."
    assert "按问题提炼" in response.llm_observation


def test_web_fetch_prompt_uses_existing_writer_and_degrades_on_model_error(monkeypatch):
    calls = []

    class Writer:
        def __init__(self, settings):
            calls.append(settings)

        def write(self, system_prompt, materials):
            assert system_prompt == ""
            assert materials == ("Web page content:\n---\nPrimary source fact.\n---\n\nWhat happened?\n\n"
                                 "Provide a concise response based only on the content above.\n"
                                 "- Prefer short quotes; do not dump the entire page\n"
                                 "- Use quotation marks for exact language from the source\n")
            return "Extracted fact."

    from app.deepsearch.settings import DeepSearchRuntimeSettings

    settings = DeepSearchRuntimeSettings("openai-compatible", "test-model", "https://model.example", "test-key")
    monkeypatch.setattr("app.tools.web_fetch.DeepSearchRuntimeSettings.load", lambda: settings)
    monkeypatch.setattr("app.tools.web_fetch.OpenAiCompatibleDeepSearchWriter", Writer)
    assert WebFetchTool._extract_with_prompt("What happened?", "Primary source fact.") == ("Extracted fact.", False, None)
    assert len(calls) == 1
    assert calls[0].timeout_seconds == 90
    assert calls[0].max_output_tokens == 1500
    assert calls[0].max_retries == 0

    def failed_load():
        raise ToolExecutionError("deep_search_llm_not_configured", "model unavailable")

    monkeypatch.setattr("app.tools.web_fetch.DeepSearchRuntimeSettings.load", failed_load)
    result = WebFetchTool._extract_with_prompt("What happened?", "Primary source fact.")
    assert result == ("Primary source fact.", True, "deep_search_llm_not_configured")


def test_web_fetch_writer_sends_master_prompt_as_user_message():
    import json

    from app.deepsearch.llm_client import OpenAiCompatibleDeepSearchWriter
    from app.deepsearch.settings import DeepSearchRuntimeSettings

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["messages"] == [{"role": "user", "content": "Web page content:\n---\nSource.\n---\n\nExtract.\n\n"
                                     "Provide a concise response based only on the content above.\n"
                                     "- Prefer short quotes; do not dump the entire page\n"
                                     "- Use quotation marks for exact language from the source\n"}]
        return httpx.Response(200, json={"choices": [{"message": {"content": "Extracted."}}]})

    settings = DeepSearchRuntimeSettings("openai-compatible", "test-model", "https://model.example/v1", "test-key")
    writer = OpenAiCompatibleDeepSearchWriter(settings, httpx.MockTransport(handler))
    assert writer.write("", "Web page content:\n---\nSource.\n---\n\nExtract.\n\n"
                        "Provide a concise response based only on the content above.\n"
                        "- Prefer short quotes; do not dump the entire page\n"
                        "- Use quotation marks for exact language from the source\n") == "Extracted."

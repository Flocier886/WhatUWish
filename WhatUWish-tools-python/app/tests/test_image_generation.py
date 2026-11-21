"""Image Rich Tool：master 核心迁入、Artifact 边界和私有 SSE 定向回归。"""

from __future__ import annotations

import asyncio
import base64
import json
from pathlib import Path

import httpx
import pytest

from app.api import routes
from app.image_generation import image_core
from app.image_generation.artifact_adapter import ImageArtifactAdapter
from app.image_generation.image_core import GeneratedImage, PersistedGeneratedImage
from app.image_generation.models import (
    ImageGenerationMode,
    ImageGenerationResult,
    ImageGenerationStartRequest,
    ImageGenerationStatus,
)
from app.image_generation.service import ImageGenerationService
from app.image_generation.settings import ImageGenerationSettings
from app.main import app
from app.runtime.artifact_storage import StoredArtifact
from app.runtime.errors import ToolExecutionError


PNG_BYTES = b"\x89PNG\r\n\x1a\nmock-png"


def settings() -> ImageGenerationSettings:
    return ImageGenerationSettings(
        provider="openai-compatible",
        model="image-test-model",
        base_url="https://image.example/v1",
        api_key="test-secret",
        timeout_seconds=300,
        max_reference_bytes=1024 * 1024,
        max_output_count=4,
        api_style="auto",
    )


def input_artifact(identifier: str, *, role: str = "reference") -> dict:
    return {
        "artifactId": identifier,
        "fileName": f"{identifier}.png",
        "mimeType": "image/png",
        "fileSize": len(PNG_BYTES),
        "storageType": "local_artifact_storage",
        "storageKey": f"private/{identifier}.png",
        "role": role,
    }


def start_request(**overrides) -> ImageGenerationStartRequest:
    payload = {
        "prompt": "turn the scene into a watercolor illustration",
        "executionId": "image-test-1",
        "outputFileName": "watercolor-result",
        "n": 1,
    }
    payload.update(overrides)
    return ImageGenerationStartRequest.model_validate(payload)


def stored_artifact(identifier: str = "image-output-1") -> StoredArtifact:
    return StoredArtifact(
        artifact_id=identifier,
        storage_type="local_artifact_storage",
        storage_key=f"private/{identifier}.png",
        storage_ref=f"private-ref/{identifier}",
        file_name=f"{identifier}.png",
        file_size=len(PNG_BYTES),
        mime_type="image/png",
        file_hash="sha256-test",
    )


def test_image_settings_uses_dedicated_section_and_never_exposes_key():
    configured = ImageGenerationSettings.from_configuration({
        "research-runtime": {
            "model": "shared-model",
            "api-base": "https://shared.example/v1",
            "api-key": "shared-secret",
        },
        "image-generation-runtime": {
            "api-style": "images",
            "model": "image-model",
            "timeout-seconds": 900,
            "max-output-count": 3,
        },
    })

    assert configured.model == "image-model"
    assert configured.api_style == "images"
    assert configured.max_output_count == 3
    assert "shared-secret" not in json.dumps(configured.safe_metadata())


def test_start_request_normalizes_mode_and_enforces_mask_role():
    automatic = start_request(referenceArtifacts=[input_artifact("reference-1")])
    assert image_core.resolve_generation_mode(automatic) == "edits"

    with pytest.raises(ValueError, match="maskArtifacts 必须使用 mask 角色"):
        start_request(
            referenceArtifacts=[input_artifact("reference-1")],
            maskArtifacts=[input_artifact("mask-1")],
        )


def test_master_core_is_embedded_without_runtime_master_path_bridge():
    image_root = Path(__file__).resolve().parents[1] / "image_generation"
    source_text = "\n".join(path.read_text(encoding="utf-8") for path in image_root.glob("*.py"))

    assert "ai-agent-master" not in source_text
    assert "D:\\JavaStudy" not in source_text
    core = (image_root / "image_core.py").read_text(encoding="utf-8")
    for function in (
        "def resolve_generation_mode",
        "def extract_generated_images",
        "async def generate_images",
        "async def _build_native_edit_request",
        "async def _build_edit_contents",
        "async def _execute_generation_request",
        "async def _upload_generated_images",
        "def _sanitize_raw_response",
    ):
        assert function in core


def test_text_to_image_keeps_responses_then_legacy_fallback_request_shape():
    primary, fallback = asyncio.run(
        image_core._build_generation_requests(
            start_request(size="1536x1024", n=2),
            "images",
            "https://image.example/v1",
            "image-test-model",
            object(),
            object(),
        )
    )

    assert primary["url"].endswith("/responses")
    assert primary["body"]["tools"] == [{"type": "image_generation", "size": "1536x1024", "n": 2}]
    assert fallback is not None
    assert fallback["url"].endswith("/images/generations")
    assert fallback["body"]["size"] == "1536x1024"


def test_workbuddy_image_style_uses_images_endpoint_without_responses_probe():
    primary, fallback = asyncio.run(
        image_core._build_generation_requests(
            start_request(size="1024x1024", n=1),
            "images",
            "http://127.0.0.1:3425/v1",
            "workbuddy/hunyuan-image-alpha",
            object(),
            object(),
            api_style="images",
        )
    )

    assert primary["url"].endswith("/images/generations")
    assert primary["body"]["model"] == "workbuddy/hunyuan-image-alpha"
    assert fallback is None


def test_responses_image_style_does_not_probe_images_endpoint():
    primary, fallback = asyncio.run(
        image_core._build_generation_requests(
            start_request(size="1024x1024", n=1),
            "images",
            "http://127.0.0.1:3425/v1",
            "responses-image-model",
            object(),
            object(),
            api_style="responses",
        )
    )

    assert primary["url"].endswith("/responses")
    assert fallback is None


def test_single_image_edit_keeps_native_multipart_mask_and_chat_fallback(monkeypatch):
    request = start_request(
        mode="edits",
        referenceArtifacts=[input_artifact("reference-1")],
        maskArtifacts=[input_artifact("mask-1", role="mask")],
        n=2,
    )

    async def read_reference(_client, artifact, _adapter):
        return PNG_BYTES, artifact.mime_type

    monkeypatch.setattr(image_core, "_reference_to_image_bytes", read_reference)
    monkeypatch.setattr(image_core, "_reference_to_data_url", lambda *_args: asyncio.sleep(0, result="data:image/png;base64,AAAA"))
    primary, fallback = asyncio.run(
        image_core._build_generation_requests(
            request,
            "edits",
            "https://image.example/v1",
            "image-test-model",
            object(),
            object(),
        )
    )

    assert primary["url"].endswith("/images/edits")
    assert primary["multipart"] is True
    assert [item[0] for item in primary["body"]] == ["model", "prompt", "response_format", "image", "n", "mask"]
    assert ("n", (None, "2")) in primary["body"]
    assert fallback is not None and fallback["url"].endswith("/chat/completions")


def test_multiple_reference_edit_uses_native_image_array_in_order(monkeypatch):
    request = start_request(referenceArtifacts=[input_artifact("ref-1"), input_artifact("ref-2")])
    seen = []

    async def image_bytes(_client, artifact, _adapter):
        seen.append(artifact.artifact_id)
        return PNG_BYTES, artifact.mime_type

    async def data_url(_client, artifact, _adapter):
        return f"data:image/png;base64,{artifact.artifact_id}"

    monkeypatch.setattr(image_core, "_reference_to_image_bytes", image_bytes)
    monkeypatch.setattr(image_core, "_reference_to_data_url", data_url)
    primary, fallback = asyncio.run(image_core._build_generation_requests(
        request, "edits", "https://image.example/v1", "image-test-model", object(), object(),
    ))

    assert primary["url"].endswith("/images/edits")
    assert [item[0] for item in primary["body"]] == ["model", "prompt", "response_format", "image[]", "image[]"]
    assert seen == ["ref-1", "ref-2"]
    assert fallback is not None and fallback["url"].endswith("/chat/completions")


def test_multiple_reference_with_mask_keeps_ordered_multimodal_contents(monkeypatch):
    request = start_request(
        referenceArtifacts=[input_artifact("ref-1"), input_artifact("ref-2")],
        maskArtifacts=[input_artifact("mask-1", role="mask")],
    )
    seen = []

    async def data_url(_client, artifact, _adapter):
        seen.append(artifact.artifact_id)
        return f"data:image/png;base64,{artifact.artifact_id}"

    monkeypatch.setattr(image_core, "_reference_to_data_url", data_url)
    primary, fallback = asyncio.run(
        image_core._build_generation_requests(
            request,
            "edits",
            "https://image.example/v1",
            "image-test-model",
            object(),
            object(),
        )
    )

    content = primary["body"]["input"][0]["content"]
    assert primary["url"].endswith("/responses")
    assert fallback is not None and fallback["url"].endswith("/chat/completions")
    assert seen == ["ref-1", "mask-1", "ref-2"]
    assert [item["type"] for item in content] == ["input_text", "input_image", "input_image", "input_image"]
    assert "red overlay marks the ONLY region" in content[0]["text"]


def test_request_exception_and_responses_status_both_trigger_fallback(monkeypatch):
    request = {"url": "https://image.example/v1/responses", "body": {}}
    fallback = {"url": "https://image.example/v1/images/generations", "body": {}}
    calls = []

    async def post(_client, current, *_args):
        calls.append(current["url"])
        if current is request:
            raise httpx.ConnectError("unavailable")
        return httpx.Response(200, json={"data": [{"b64_json": base64.b64encode(PNG_BYTES).decode()}]})

    monkeypatch.setattr(image_core, "_post_generation_request", post)
    payload, used_fallback = asyncio.run(image_core._execute_generation_request(object(), "key", request, fallback))

    assert used_fallback is True
    assert calls == [request["url"], fallback["url"]]
    assert image_core.extract_generated_images(payload)


def test_extracts_responses_legacy_chat_and_sanitizes_large_base64():
    encoded = base64.b64encode(PNG_BYTES).decode()
    responses = image_core.extract_generated_images({"output": [{"type": "image_generation_call", "result": encoded}]})
    legacy = image_core.extract_generated_images({"data": [{"b64_json": encoded}]})
    chat = image_core.extract_generated_images({"choices": [{"message": {"content": f"![result](https://image.example/result.png)"}}]})
    sanitized = image_core._sanitize_raw_response({"image": "data:image/png;base64," + ("A" * 300)})

    assert responses[0].data_url.startswith("data:image/png;base64,")
    assert legacy[0].data_url.startswith("data:image/png;base64,")
    assert chat[0].url == "https://image.example/result.png"
    assert sanitized["image"].startswith("[omitted data url payload")


def test_adapter_reads_only_authorized_descriptor_and_rejects_unsupported_type(monkeypatch):
    received = []
    monkeypatch.setattr(
        "app.image_generation.artifact_adapter.read_artifact_range",
        lambda storage_key, offset, limit, storage_type: received.append((storage_key, offset, limit, storage_type)) or PNG_BYTES,
    )
    adapter = ImageArtifactAdapter(settings(), "image-test-1")

    content, mime_type = adapter.read(start_request(referenceArtifacts=[input_artifact("input-1")]).reference_artifacts[0])
    assert content == PNG_BYTES
    assert mime_type == "image/png"
    assert received == [("private/input-1.png", 0, len(PNG_BYTES), "local_artifact_storage")]

    unsupported = input_artifact("vector-1")
    unsupported["mimeType"] = "image/svg+xml"
    with pytest.raises(ToolExecutionError, match="参考图片格式不受支持"):
        adapter.read(start_request(referenceArtifacts=[unsupported]).reference_artifacts[0])


def test_service_emits_one_artifact_event_per_image_and_keeps_result_safe(monkeypatch):
    generated = [
        PersistedGeneratedImage(stored_artifact("output-1"), "image/png", "output-1.png", 1),
        PersistedGeneratedImage(stored_artifact("output-2"), "image/png", "output-2.png", 2),
    ]

    async def core(*_args, **_kwargs):
        return {"summary": "图片生成完成", "artifacts": generated, "mode": "images", "usedFallback": True}

    monkeypatch.setattr("app.image_generation.service.generate_images", core)
    messages = []
    result = ImageGenerationService(settings()).run(start_request(n=2), emit=messages.append)

    phases = [message.phase.value for message in messages]
    artifacts = [message for message in messages if message.phase.value == "artifact_created"]
    assert result.status is ImageGenerationStatus.COMPLETED
    assert result.used_fallback is True
    assert phases == ["inputs_resolved", "generation_started", "artifact_created", "artifact_created", "completed"]
    assert [item.data["outputIndex"] for item in artifacts] == [1, 2]
    assert all("storageKey" not in message.data for message in messages if message.phase.value != "artifact_created")
    assert "private/output-1" not in result.summary


def test_stream_route_emits_versioned_progress_artifact_and_result(monkeypatch):
    class StubService:
        def run(self, request, *, emit):
            from app.image_generation.models import ImageGenerationPhase, ImageGenerationStatus
            publisher = __import__("app.image_generation.models", fromlist=["ImageGenerationEventPublisher"]).ImageGenerationEventPublisher
            events = publisher(request.execution_id, emit)
            events.emit(ImageGenerationPhase.INPUTS_RESOLVED, ImageGenerationStatus.RUNNING, "inputs")
            return ImageGenerationResult(
                executionId=request.execution_id,
                imageExecutionId="image-test-execution",
                status=ImageGenerationStatus.COMPLETED,
                summary="done",
                mode=ImageGenerationMode.IMAGES,
                requestedCount=1,
            )

    monkeypatch.setattr(routes, "create_image_generation_service", lambda: StubService())

    async def invoke() -> str:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/v1/rich-tools/image-generation/stream", json=start_request().model_dump(by_alias=True))
        assert response.status_code == 200
        return response.text

    payload = asyncio.run(invoke())
    events = [line.removeprefix("event: ") for line in payload.splitlines() if line.startswith("event: ")]
    bodies = [json.loads(line.removeprefix("data: ")) for line in payload.splitlines() if line.startswith("data: ")]

    assert events == ["progress", "result"]
    assert bodies[0]["schemaVersion"] == "image_generation_progress_event.v1"
    assert bodies[-1]["schemaVersion"] == "image_generation_result.v1"


def test_image_validation_error_does_not_echo_private_locator():
    async def invoke():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/rich-tools/image-generation/stream",
                json={"prompt": "x", "referenceArtifacts": [{"storageKey": "private/secret/key"}]},
            )

    response = asyncio.run(invoke())
    assert response.status_code == 422
    assert response.json()["errorCode"] == "image_generation_request_invalid"
    assert "private/secret/key" not in response.text

# -*- coding: utf-8 -*-
"""master ``image_generation.py`` 核心迁入；仅在 Artifact 读写边界适配本项目。"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote, urlparse

import httpx

from app.image_generation.artifact_adapter import ImageArtifactAdapter
from app.image_generation.models import ImageGenerationMode, ImageGenerationStartRequest
from app.image_generation.settings import ImageGenerationSettings
from app.runtime.artifact_storage import StoredArtifact
from app.runtime.errors import ToolExecutionError


DEFAULT_IMAGE_SIZE = "1024x1024"
RESPONSES_FALLBACK_STATUS = {404, 405, 501}
OPENAI_COMPAT_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) "
    "Gecko/20100101 Firefox/148.0"
)
DATA_URL_RE = re.compile(r"^data:(?P<mime>[^;,]+);base64,(?P<data>.+)$", re.IGNORECASE | re.DOTALL)
BASE64_IMAGE_RE = re.compile(r"[A-Za-z0-9+/=\s]{200,}")
HTTP_IMAGE_RE = re.compile(r"https?://[^\s\"'<>)]+", re.IGNORECASE)
MARKDOWN_IMAGE_RE = re.compile(r"!\[[^\]]*]\((https?://[^)\s]+)\)", re.IGNORECASE)


@dataclass
class GeneratedImage:
    """统一承载从上游响应中提取出的图片结果。"""

    data_url: str | None = None
    url: str | None = None


@dataclass(frozen=True)
class PersistedGeneratedImage:
    """核心物化后的内部 Artifact 结果，交由 Service 生成私有 descriptor。"""

    stored: StoredArtifact
    mime_type: str
    file_name: str
    output_index: int


def resolve_generation_mode(request: ImageGenerationStartRequest) -> str:
    """显式 images/edits 优先，未指定时由参考图决定。"""
    if request.mode in {ImageGenerationMode.IMAGES, ImageGenerationMode.EDITS}:
        return request.mode.value
    return "edits" if request.reference_artifacts else "images"


def extract_generated_images(payload: Any) -> list[GeneratedImage]:
    """保留 Responses、legacy images、chat/completions 的多种图片响应解析。"""
    images: list[GeneratedImage] = []
    if not payload:
        return images
    if isinstance(payload, dict):
        output_items = payload.get("output")
        if isinstance(output_items, list):
            for item in output_items:
                if not isinstance(item, dict):
                    continue
                if item.get("type") == "image_generation_call" and item.get("result"):
                    images.append(GeneratedImage(data_url=_normalize_data_url(str(item["result"]))))
                    continue
                if item.get("type") == "message" and isinstance(item.get("content"), list):
                    images.extend(_extract_images_from_content_parts(item["content"]))
        data_items = payload.get("data")
        if isinstance(data_items, list):
            for item in data_items:
                if not isinstance(item, dict):
                    continue
                if item.get("url"):
                    images.append(GeneratedImage(url=str(item["url"])))
                elif item.get("b64_json"):
                    images.append(GeneratedImage(data_url=_normalize_data_url(str(item["b64_json"]))))
        choices = payload.get("choices")
        if isinstance(choices, list):
            for choice in choices:
                if not isinstance(choice, dict):
                    continue
                message = choice.get("message") or choice.get("delta") or {}
                if not isinstance(message, dict):
                    continue
                content = message.get("content")
                if isinstance(content, str):
                    images.extend(_extract_images_from_text(content))
                elif isinstance(content, list):
                    images.extend(_extract_images_from_content_parts(content))
    if not images:
        images.extend(_extract_images_from_text(json.dumps(payload, ensure_ascii=False)))
    return _deduplicate_generated_images(images)


async def generate_images(
    request: ImageGenerationStartRequest,
    settings: ImageGenerationSettings,
    artifact_adapter: ImageArtifactAdapter,
) -> dict[str, Any]:
    """调用 OpenAI-compatible 接口并将结果写入正式 Artifact Storage。"""
    mode = resolve_generation_mode(request)
    if mode == "edits" and not request.reference_artifacts:
        raise ToolExecutionError("image_request_invalid", "图生图模式至少需要一张参考图片。")
    if request.mask_artifacts and len(request.mask_artifacts) > len(request.reference_artifacts):
        raise ToolExecutionError("image_request_invalid", "maskArtifacts 数量不能超过 referenceArtifacts。")
    if request.n > settings.max_output_count:
        raise ToolExecutionError("image_request_invalid", "请求生成数量超过服务端预算。")

    timeout = httpx.Timeout(timeout=float(settings.timeout_seconds))
    async with httpx.AsyncClient(timeout=timeout) as client:
        primary_request, fallback_request = await _build_generation_requests(
            request=request,
            mode=mode,
            base_url=_normalize_openai_compat_api_base(settings.base_url),
            model_name=settings.model,
            client=client,
            artifact_adapter=artifact_adapter,
        )
        payload, used_fallback = await _execute_generation_request(
            client=client,
            api_key=settings.api_key,
            primary_request=primary_request,
            fallback_request=fallback_request,
        )
        generated_images = extract_generated_images(payload)
        if not generated_images:
            text_output = _extract_text_output(payload)
            if text_output:
                raise ToolExecutionError("image_generation_empty", f"上游未返回图片内容，文本响应为：{text_output[:300]}")
            raise ToolExecutionError("image_generation_empty", "上游未返回可识别的图片结果。")
        artifacts = await _upload_generated_images(client, request, generated_images, artifact_adapter)

    return {
        "summary": _build_generation_summary(mode, artifacts, used_fallback),
        "artifacts": artifacts,
        "mode": mode,
        "usedFallback": used_fallback,
        # 严禁由 Service / SSE / Java 透传；仅留在本进程受控诊断返回值中。
        "rawResponse": _sanitize_raw_response(payload),
    }


async def _build_generation_requests(
    request: ImageGenerationStartRequest,
    mode: str,
    base_url: str,
    model_name: str,
    client: httpx.AsyncClient,
    artifact_adapter: ImageArtifactAdapter,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    """保留 master 的 images / 单图 edit / 多图 edit 及各自 fallback。"""
    if mode == "images":
        tool: dict[str, Any] = {"type": "image_generation"}
        if request.size:
            tool["size"] = request.size
        if request.n > 1:
            tool["n"] = request.n
        return (
            {"url": f"{base_url}/responses", "body": {"model": model_name, "input": request.prompt, "tools": [tool]}},
            {"url": f"{base_url}/images/generations", "body": {
                "model": model_name, "prompt": request.prompt, "n": request.n,
                "size": request.size or DEFAULT_IMAGE_SIZE,
            }},
        )
    if len(request.reference_artifacts) == 1:
        primary_request = await _build_native_edit_request(request, base_url, model_name, client, artifact_adapter)
        _, chat_content = await _build_edit_contents(request, client, artifact_adapter)
        return primary_request, {
            "url": f"{base_url}/chat/completions",
            "body": {"model": model_name, "messages": [{"role": "user", "content": chat_content}]},
        }

    if not request.mask_artifacts:
        primary_request = await _build_native_multi_edit_request(request, base_url, model_name, client, artifact_adapter)
        _, chat_content = await _build_edit_contents(request, client, artifact_adapter)
        return primary_request, {
            "url": f"{base_url}/chat/completions",
            "body": {"model": model_name, "messages": [{"role": "user", "content": chat_content}]},
        }

    responses_content, chat_content = await _build_edit_contents(request, client, artifact_adapter)
    tool = {"type": "image_generation"}
    if request.size:
        tool["size"] = request.size
    if request.n > 1:
        tool["n"] = request.n
    return (
        {"url": f"{base_url}/responses", "body": {
            "model": model_name,
            "input": [{"role": "user", "content": responses_content}],
            "tools": [tool],
        }},
        {"url": f"{base_url}/chat/completions", "body": {
            "model": model_name,
            "messages": [{"role": "user", "content": chat_content}],
        }},
    )


async def _build_native_edit_request(
    request: ImageGenerationStartRequest,
    base_url: str,
    model_name: str,
    client: httpx.AsyncClient,
    artifact_adapter: ImageArtifactAdapter,
) -> dict[str, Any]:
    """单图图生图优先走 master 的 native /images/edits multipart 路径。"""
    image_bytes, image_mime = await _reference_to_image_bytes(client, request.reference_artifacts[0], artifact_adapter)
    files = [
        ("model", (None, model_name)),
        ("prompt", (None, request.prompt)),
        ("response_format", (None, "b64_json")),
        ("image", ("image.png", image_bytes, image_mime)),
    ]
    if request.size:
        files.append(("size", (None, request.size)))
    files.append(("n", (None, str(request.n))))
    mask_artifact = request.mask_artifacts[0] if request.mask_artifacts else None
    if mask_artifact:
        mask_bytes, mask_mime = await _reference_to_image_bytes(client, mask_artifact, artifact_adapter)
        files.append(("mask", ("mask.png", mask_bytes, mask_mime)))
    return {"url": f"{base_url}/images/edits", "body": files, "multipart": True}


async def _build_native_multi_edit_request(
    request: ImageGenerationStartRequest,
    base_url: str,
    model_name: str,
    client: httpx.AsyncClient,
    artifact_adapter: ImageArtifactAdapter,
) -> dict[str, Any]:
    """无蒙版多图参考沿用 master 的 image[] 原生编辑协议。"""
    files = [
        ("model", (None, model_name)),
        ("prompt", (None, request.prompt)),
        ("response_format", (None, "b64_json")),
    ]
    if request.size:
        files.append(("size", (None, request.size)))
    for reference in request.reference_artifacts:
        image_bytes, image_mime = await _reference_to_image_bytes(client, reference, artifact_adapter)
        files.append(("image[]", (reference.file_name, image_bytes, image_mime)))
    return {"url": f"{base_url}/images/edits", "body": files, "multipart": True}


async def _build_edit_contents(
    request: ImageGenerationStartRequest,
    client: httpx.AsyncClient,
    artifact_adapter: ImageArtifactAdapter,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """保留单图、多图、带遮罩的 master 多模态指令与输入顺序。"""
    any_mask = bool(request.mask_artifacts)
    image_count = len(request.reference_artifacts)
    if image_count == 1 and not any_mask:
        instruction = (
            "Edit the attached image as described. "
            "Output the full edited image, same dimensions as the input.\n\n"
            f"Instruction:\n{request.prompt}"
        )
    elif any_mask:
        instruction = (
            "Attached are reference image(s). For any image immediately followed by a duplicate "
            "with a semi-transparent red overlay, the red overlay marks the ONLY region to edit "
            "in that image. Treat the red overlay as an instruction, NOT as image content. "
            "Modify only pixels inside the highlighted region; pixels outside must remain "
            "pixel-identical to the original.\n\n"
            f"Instruction:\n{request.prompt}\n\nOutput a single final image."
        )
    else:
        instruction = (
            f"Attached are {image_count} reference images (in order). Use them together to produce "
            f"a single final image according to the instruction.\n\nInstruction:\n{request.prompt}"
        )

    responses_content: list[dict[str, Any]] = [{"type": "input_text", "text": instruction}]
    chat_content: list[dict[str, Any]] = [{"type": "text", "text": instruction}]
    for index, image_artifact in enumerate(request.reference_artifacts):
        image_data_url = await _reference_to_data_url(client, image_artifact, artifact_adapter)
        responses_content.append({"type": "input_image", "image_url": image_data_url})
        chat_content.append({"type": "image_url", "image_url": {"url": image_data_url}})
        if index < len(request.mask_artifacts):
            mask_artifact = request.mask_artifacts[index]
            if mask_artifact:
                mask_data_url = await _reference_to_data_url(client, mask_artifact, artifact_adapter)
                responses_content.append({"type": "input_image", "image_url": mask_data_url})
                chat_content.append({"type": "image_url", "image_url": {"url": mask_data_url}})
    return responses_content, chat_content


async def _execute_generation_request(
    client: httpx.AsyncClient,
    api_key: str,
    primary_request: dict[str, Any],
    fallback_request: dict[str, Any] | None,
) -> tuple[Any, bool]:
    """保留请求异常和 404/405/501 的 master fallback 行为。"""
    json_headers = _build_openai_compat_headers({
        "Authorization": f"Bearer {api_key}", "Content-Type": "application/json", "Accept": "application/json",
    })
    multipart_headers = _build_openai_compat_headers({
        "Authorization": f"Bearer {api_key}", "Accept": "application/json",
    })
    used_fallback = False
    try:
        response = await _post_generation_request(client, primary_request, json_headers, multipart_headers)
    except Exception:
        if not fallback_request:
            raise
        used_fallback = True
        response = await _post_generation_request(client, fallback_request, json_headers, multipart_headers)
    if not response.is_success and fallback_request and not used_fallback and response.status_code in RESPONSES_FALLBACK_STATUS:
        used_fallback = True
        response = await _post_generation_request(client, fallback_request, json_headers, multipart_headers)
    raw_text = response.text
    try:
        payload = response.json()
    except Exception:
        payload = raw_text
    if not response.is_success:
        raise ToolExecutionError(
            "image_generation_upstream_failed",
            # master 这里保留上游 body 用于旧调试页面；本项目的 Rich Tool 运行
            # 事实和失败事件不能接受原始模型响应，因而只保留状态摘要。
            f"图片生成请求失败，status={response.status_code}。",
        )
    return payload, used_fallback


async def _post_generation_request(
    client: httpx.AsyncClient,
    request: dict[str, Any],
    json_headers: dict[str, str],
    multipart_headers: dict[str, str],
) -> httpx.Response:
    if request.get("multipart"):
        return await client.post(request["url"], headers=multipart_headers, files=request["body"])
    return await client.post(request["url"], headers=json_headers, json=request["body"])


async def _upload_generated_images(
    client: httpx.AsyncClient,
    request: ImageGenerationStartRequest,
    generated_images: Iterable[GeneratedImage],
    artifact_adapter: ImageArtifactAdapter,
) -> list[PersistedGeneratedImage]:
    """仅将 master 旧文件上传边缘替换为正式 Artifact Storage 写入。"""
    output_name = _sanitize_output_name(request.output_file_name)
    images = list(generated_images)
    artifacts: list[PersistedGeneratedImage] = []
    for index, image in enumerate(images):
        image_bytes, mime_type = await _materialize_generated_image(client, image)
        extension = _guess_extension(mime_type, image.url)
        file_name = _build_output_file_name(output_name, index, len(images), extension)
        artifacts.append(PersistedGeneratedImage(
            stored=artifact_adapter.persist(image_bytes, file_name),
            mime_type=mime_type,
            file_name=file_name,
            output_index=index + 1,
        ))
    if not artifacts:
        raise ToolExecutionError("image_artifact_persist_failed", "图片生成成功，但保存产物失败。")
    return artifacts


async def _materialize_generated_image(client: httpx.AsyncClient, image: GeneratedImage) -> tuple[bytes, str]:
    """保留 data URL/上游 URL 到二进制图片的 master 物化能力。"""
    if image.data_url:
        return _decode_image_data(image.data_url)
    if not image.url:
        raise ToolExecutionError("image_result_invalid", "图片结果缺少 data_url 和 url。")
    response = await client.get(image.url)
    response.raise_for_status()
    content = response.content
    mime_type = (
        response.headers.get("Content-Type", "").split(";", 1)[0].strip()
        or _guess_mime_from_name(image.url)
        or _guess_mime_from_bytes(content)
    )
    return content, mime_type or "image/png"


def _extract_images_from_content_parts(parts: list[Any]) -> list[GeneratedImage]:
    images: list[GeneratedImage] = []
    for part in parts:
        if not isinstance(part, dict):
            continue
        part_type = str(part.get("type") or "").strip()
        if part_type == "output_image":
            image_reference = part.get("image_url") or part.get("url") or part.get("b64_json") or part.get("image")
            if isinstance(image_reference, dict):
                image_reference = image_reference.get("url")
            if isinstance(image_reference, str) and image_reference:
                images.extend(_extract_images_from_text(image_reference))
            continue
        if part_type == "image_url":
            image_reference = part.get("image_url")
            if isinstance(image_reference, dict):
                image_reference = image_reference.get("url")
            if isinstance(image_reference, str) and image_reference:
                images.extend(_extract_images_from_text(image_reference))
            continue
        part_text = part.get("text")
        if isinstance(part_text, str) and part_text:
            images.extend(_extract_images_from_text(part_text))
    return images


def _extract_images_from_text(text: str) -> list[GeneratedImage]:
    if not text:
        return []
    images: list[GeneratedImage] = []
    for match in MARKDOWN_IMAGE_RE.finditer(text):
        images.append(GeneratedImage(url=match.group(1)))
    for match in re.finditer(r"data:image/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+", text, re.IGNORECASE):
        try:
            images.append(GeneratedImage(data_url=_normalize_data_url(match.group(0))))
        except Exception:
            continue
    for match in HTTP_IMAGE_RE.finditer(text):
        candidate = match.group(0)
        if re.search(r"\.(png|jpe?g|gif|webp|bmp|svg)(\?|$)", candidate, re.IGNORECASE):
            images.append(GeneratedImage(url=candidate))
    if not images:
        base64_match = BASE64_IMAGE_RE.search(text)
        if base64_match:
            try:
                images.append(GeneratedImage(data_url=_normalize_data_url(base64_match.group(0))))
            except Exception:
                pass
    return images


def _deduplicate_generated_images(images: Iterable[GeneratedImage]) -> list[GeneratedImage]:
    unique: list[GeneratedImage] = []
    seen: set[str] = set()
    for image in images:
        key = image.data_url[:120] if image.data_url else image.url
        if not key or key in seen:
            continue
        seen.add(key)
        unique.append(image)
    return unique


def _extract_text_output(payload: Any) -> str:
    if isinstance(payload, str):
        return payload
    if not isinstance(payload, dict):
        return ""
    text_parts: list[str] = []
    output_items = payload.get("output")
    if isinstance(output_items, list):
        for item in output_items:
            if isinstance(item, dict) and isinstance(item.get("content"), list):
                for part in item["content"]:
                    if isinstance(part, dict) and isinstance(part.get("text"), str):
                        text_parts.append(part["text"])
    choices = payload.get("choices")
    if isinstance(choices, list):
        for choice in choices:
            if not isinstance(choice, dict):
                continue
            message = choice.get("message") or choice.get("delta") or {}
            if not isinstance(message, dict):
                continue
            content = message.get("content")
            if isinstance(content, str):
                text_parts.append(content)
            elif isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and isinstance(part.get("text"), str):
                        text_parts.append(part["text"])
    return "".join(text_parts)


async def _reference_to_data_url(client: httpx.AsyncClient, artifact, adapter: ImageArtifactAdapter) -> str:
    content, mime_type = await _reference_to_image_bytes(client, artifact, adapter)
    return _bytes_to_data_url(content, mime_type)


async def _reference_to_image_bytes(_client: httpx.AsyncClient, artifact, adapter: ImageArtifactAdapter) -> tuple[bytes, str]:
    """本项目唯一允许的输入边界：Java 已授权的 Artifact descriptor。"""
    return adapter.read(artifact)


def _build_generation_summary(mode: str, artifacts: list[PersistedGeneratedImage], used_fallback: bool) -> str:
    action = "图片编辑" if mode == "edits" else "图片生成"
    file_names = "、".join(item.file_name for item in artifacts)
    fallback_hint = "；已自动切换兼容接口" if used_fallback else ""
    return f"{action}完成，共生成 {len(artifacts)} 个图片文件：{file_names}{fallback_hint}"


def _sanitize_raw_response(payload: Any) -> Any:
    """保留 master 大 data URL/Base64 脱敏；其结果不得越过 Python 私有边界。"""
    if isinstance(payload, dict):
        return {key: _sanitize_raw_response(value) for key, value in payload.items()}
    if isinstance(payload, list):
        return [_sanitize_raw_response(item) for item in payload]
    if isinstance(payload, str):
        normalized = payload.strip()
        compact = normalized.replace("\n", "").replace("\r", "")
        if normalized.startswith("data:image/") and len(normalized) > 200:
            return f"[omitted data url payload, length={len(normalized)}]"
        if BASE64_IMAGE_RE.fullmatch(compact) and len(compact) > 200:
            return f"[omitted base64 payload, length={len(compact)}]"
    return payload


def _sanitize_output_name(raw_name: str) -> str:
    normalized = (raw_name or "图片生成结果").strip()
    normalized = re.sub(r"[<>:\"/\\|?*\x00-\x1F]+", "_", normalized).strip().rstrip(". ")
    return (normalized or "图片生成结果")[:120]


def _build_output_file_name(base_name: str, index: int, total: int, extension: str) -> str:
    stem = Path(base_name).stem or "图片生成结果"
    suffix = f".{extension.lstrip('.')}"
    return f"{stem}{suffix}" if total <= 1 else f"{stem}_{index + 1}{suffix}"


def _guess_extension(mime_type: str | None, url: str | None) -> str:
    if mime_type and (guessed := mimetypes.guess_extension(mime_type)):
        return guessed.lstrip(".")
    if url and (suffix := Path(unquote(urlparse(url).path)).suffix.lstrip(".")):
        return suffix
    return "png"


def _guess_mime_from_name(name: str) -> str | None:
    return mimetypes.guess_type(name)[0]


def _guess_mime_from_bytes(content: bytes) -> str | None:
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if content.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if content[:4] == b"RIFF" and content[8:12] == b"WEBP":
        return "image/webp"
    if content.startswith(b"BM"):
        return "image/bmp"
    if content.lstrip().startswith(b"<svg"):
        return "image/svg+xml"
    return None


def _bytes_to_data_url(content: bytes, mime_type: str) -> str:
    return f"data:{mime_type};base64,{base64.b64encode(content).decode('ascii')}"


def _normalize_data_url(raw: str) -> str:
    text = (raw or "").strip()
    if not text:
        raise ValueError("图片内容为空")
    match = DATA_URL_RE.match(text)
    mime_type = match.group("mime").strip() if match else ""
    payload = re.sub(r"\s+", "", match.group("data") if match else text)
    image_bytes = base64.b64decode(_pad_base64(payload), validate=True)
    return _bytes_to_data_url(image_bytes, mime_type or _guess_mime_from_bytes(image_bytes) or "image/png")


def _decode_image_data(data_url: str) -> tuple[bytes, str]:
    match = DATA_URL_RE.match(data_url)
    if not match:
        raise ValueError("非法的 data url 图片内容")
    return (
        base64.b64decode(_pad_base64(re.sub(r"\s+", "", match.group("data"))), validate=True),
        match.group("mime").strip() or "image/png",
    )


def _pad_base64(value: str) -> str:
    remainder = len(value) % 4
    return value if remainder == 0 else value + ("=" * (4 - remainder))


def _normalize_api_base(api_base: str) -> str:
    if not api_base:
        return api_base
    normalized = api_base.strip().rstrip("/")
    return normalized if normalized.lower().endswith("/v1") else f"{normalized}/v1"


def _normalize_openai_compat_api_base(api_base: str) -> str:
    """精确保留 master 对 endpoint tail、/v1 和 bigmodel.cn 的处理语义。"""
    if not api_base:
        return api_base
    normalized = api_base.strip().rstrip("/")
    suffixes = (
        "/v1/chat/completions", "/chat/completions", "/v1/completions",
        "/completions", "/v1/responses", "/responses",
    )
    lower = normalized.lower()
    changed = True
    while changed:
        changed = False
        for suffix in suffixes:
            if lower.endswith(suffix):
                normalized = normalized[:-len(suffix)].rstrip("/")
                lower = normalized.lower()
                changed = True
                break
    if "bigmodel.cn" in lower:
        return normalized
    return _normalize_api_base(normalized)


def _build_openai_compat_headers(existing_headers: dict[str, str] | None = None) -> dict[str, str]:
    """精确保留 master 的稳定 User-Agent 补齐策略。"""
    headers = dict(existing_headers or {})
    if "user-agent" not in {str(key).lower() for key in headers}:
        headers["User-Agent"] = os.getenv("OPENAI_COMPAT_USER_AGENT", OPENAI_COMPAT_DEFAULT_USER_AGENT)
    return headers

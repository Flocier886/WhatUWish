# -*- coding: utf-8 -*-
"""图片 OCR：保留 master 的模型与提示语义，接入本项目独立的服务端配置。"""
from __future__ import annotations

import base64
import os
from pathlib import Path
from typing import Any

import httpx

from app.runtime.configuration import config_section, load_application_configuration

_ALLOWED_SUFFIXES = frozenset({
    ".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tif", ".tiff", ".gif",
})
_MAX_BYTES = 20 * 1024 * 1024  # 20MB


def run_image_ocr_sync(file_path: str, *, prompt: str | None = None, lang: str | None = None) -> dict[str, Any]:
    """对已授权暂存的图片执行 OCR；缺少后端配置时明确返回不可用。

    Returns a data dict with text/metadata. Raises on validation or empty OCR.
    """
    path = Path(file_path)
    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {file_path}")
    suffix = path.suffix.lower()
    if suffix not in _ALLOWED_SUFFIXES:
        raise ValueError(
            f"Unsupported image type: {suffix or '(none)'}. "
            f"Allowed: {', '.join(sorted(_ALLOWED_SUFFIXES))}"
        )
    size = path.stat().st_size
    if size > _MAX_BYTES:
        raise ValueError(f"Image too large: {size} bytes (max {_MAX_BYTES})")
    if size == 0:
        raise ValueError("Image file is empty")

    settings = config_section(load_application_configuration(), "material-ocr")
    ocr_type = (os.getenv("OCR_TYPE") or str(settings.get("type") or "")).strip().lower()
    if not ocr_type:
        raise RuntimeError("OCR 后端未配置：请先配置 OCR_TYPE 和对应服务凭据。")
    if ocr_type == "paddleocr-vl":
        raise RuntimeError("PaddleOCR-VL 后端尚未接入本项目，请配置 VLM 或 DeepSeek OCR。")
    if ocr_type not in {"vlm-ocr", "deepseek-ocr"}:
        raise RuntimeError(f"当前 OCR 后端不可用：{ocr_type}")
    text = _invoke_ocr(str(path.resolve()), prompt=prompt, lang=lang, ocr_type=ocr_type)
    text = (text or "").strip()
    if not text:
        raise RuntimeError(
            f"OCR returned empty text (ocr_type={ocr_type}). "
            "Check OCR_TYPE / VLM_* / DEEPSEEK_OCR_* / PADDLE_OCR_* credentials."
        )
    return {
        "text": text,
        "char_count": len(text),
        "ocr_type": ocr_type,
        "file_path": str(path.resolve()),
        "file_name": path.name,
        "file_size": size,
        "lang": lang or "",
        "source": "material-ocr",
    }


def _invoke_ocr(image_path: str, *, prompt: str | None, lang: str | None, ocr_type: str) -> str:
    """沿用 master 的 OCR_TYPE/默认提示语义，调用本项目已配置的多模态端点。"""
    custom = (prompt or "").strip()
    if lang and not custom:
        hint = {
            "ch": "提取图片中的中文文字，保持原有顺序和换行。",
            "en": "Extract all English text from the image. Preserve line breaks.",
            "ch_en": "提取图片中的中英文文字，保持原有顺序和换行。",
        }.get(str(lang).strip().lower())
        if hint:
            custom = hint
    return _vlm_ocr_with_prompt(image_path, custom or "提取图片中的文字", ocr_type=ocr_type)


def _vlm_ocr_with_prompt(image_path: str, prompt: str, *, ocr_type: str) -> str:
    """沿用 master 多模态消息格式，复用本项目的配置加载与 HTTP 客户端。"""
    # OCR 使用独立凭据，不能隐式借用调研模型；环境变量兼容 master。
    values = dict(config_section(load_application_configuration(), "material-ocr"))
    if ocr_type == "deepseek-ocr":
        values["api-base"] = os.getenv("DEEPSEEK_OCR_BASE_URL") or values.get("deepseek-api-base")
        values["api-key"] = os.getenv("DEEPSEEK_OCR_API_KEY") or values.get("deepseek-api-key")
        values["model"] = os.getenv("DEEPSEEK_OCR_MODEL_NAME") or values.get("deepseek-model")
    else:
        values["api-base"] = os.getenv("VLM_MODEL_BASE_URL") or values.get("api-base")
        values["api-key"] = os.getenv("VLM_API_KEY") or values.get("api-key")
        values["model"] = os.getenv("VLM_MODEL_NAME") or values.get("model")
    base_url = str(values.get("api-base") or "").rstrip("/")
    api_key = str(values.get("api-key") or "")
    model = str(values.get("model") or "")
    if not base_url or not api_key or not model:
        raise RuntimeError("OCR 模型未配置完整：需要 api-base、api-key 和 model。")
    from urllib.parse import urlsplit
    parsed = urlsplit(base_url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise RuntimeError("OCR 服务地址必须是受信任的 HTTPS 端点。")
    if base_url.endswith("/chat/completions"):
        endpoint = base_url
    else:
        endpoint = base_url + ("/chat/completions" if base_url.endswith("/v1") else "/v1/chat/completions")
    suffix = Path(image_path).suffix.lower()
    mime = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".tif": "image/tiff",
            ".tiff": "image/tiff", ".bmp": "image/bmp"}.get(suffix, "image/" + suffix.lstrip("."))
    encoded = base64.b64encode(Path(image_path).read_bytes()).decode("ascii")
    try:
        with httpx.Client(timeout=120, trust_env=False) as client:
            response = client.post(endpoint, headers={"Authorization": f"Bearer {api_key}"}, json={
                "model": model, "temperature": 0, "max_tokens": 2048,
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}},
                ]}],
            })
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as failure:
        # 上游错误可能包含 endpoint、响应体或凭据，不能写入 LLM observation。
        raise RuntimeError("OCR 服务调用失败或响应格式无效，请检查后端配置。") from failure
    return content if isinstance(content, str) else "".join(
        part.get("text", "") for part in content if isinstance(part, dict))

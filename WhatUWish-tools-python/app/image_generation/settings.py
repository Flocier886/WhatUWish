"""图片生成服务端配置；模型凭据只来自 Python 配置或进程环境。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.errors import ToolExecutionError


@dataclass(frozen=True)
class ImageGenerationSettings:
    provider: str
    model: str
    base_url: str
    api_key: str
    timeout_seconds: int
    max_reference_bytes: int
    max_output_count: int
    api_style: str = "auto"

    @classmethod
    def load(cls) -> "ImageGenerationSettings":
        return cls.from_configuration(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "ImageGenerationSettings":
        # 独立图片段只覆盖其确有必要的模型连接和任务预算；未显式指定的连接
        # 参数可以沿用 research-runtime，避免本地为同一可信服务重复写密钥。
        values = dict(config_section(configuration, "research-runtime"))
        values.update(config_section(configuration, "image-generation-runtime"))

        def text(name: str, default: str = "") -> str:
            return str(values.get(name, default) or "").strip() or default

        provider = text("provider", "openai-compatible").lower()
        api_style = text("api-style", "auto").lower()
        model = text("model")
        base_url = text("api-base")
        api_key = text("api-key")
        if not model or not base_url or not api_key:
            raise ToolExecutionError(
                "image_generation_not_configured",
                "图片模型未配置，请在 config/application.local.yaml 的 image-generation-runtime 段设置 model、api-base 和 api-key。",
            )
        if provider not in {"openai", "openai-compatible"}:
            raise ToolExecutionError("image_generation_not_configured", f"不支持的图片模型 Provider: {provider}")
        if api_style not in {"auto", "responses", "images"}:
            raise ToolExecutionError(
                "image_generation_not_configured",
                "image-generation-runtime.api-style 必须是 auto、responses 或 images",
            )
        return cls(
            provider=provider,
            api_style=api_style,
            model=model,
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            timeout_seconds=number(values, "timeout-seconds", 900, 10, 1800),
            max_reference_bytes=number(values, "max-reference-bytes", 10_485_760, 1024, 52_428_800),
            max_output_count=number(values, "max-output-count", 10, 1, 10),
        )

    def safe_metadata(self) -> dict[str, object]:
        return {
            "provider": self.provider,
            "apiStyle": self.api_style,
            "modelAlias": self.model,
            "configured": True,
        }


def number(values: Mapping[str, Any], name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(str(values.get(name, default)).strip())
    except (TypeError, ValueError) as exc:
        raise ToolExecutionError("image_generation_not_configured", f"{name} 配置无效") from exc
    if not minimum <= value <= maximum:
        raise ToolExecutionError("image_generation_not_configured", f"{name} 必须在 {minimum} 到 {maximum} 之间")
    return value

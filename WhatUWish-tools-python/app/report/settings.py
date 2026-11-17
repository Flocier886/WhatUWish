"""Report Rich Tool 的独立运行配置。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.errors import ToolExecutionError


@dataclass(frozen=True)
class ReportRuntimeSettings:
    provider: str
    model: str
    base_url: str
    api_key: str
    timeout_seconds: float
    max_retries: int
    temperature: float
    max_output_tokens: int
    max_input_chars: int
    max_file_chars: int
    max_artifact_bytes: int
    delta_batch_chars: int
    delta_interval_ms: int
    max_delta_chars: int

    @classmethod
    def load(cls) -> "ReportRuntimeSettings":
        return cls.from_configuration(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "ReportRuntimeSettings":
        # Report 与 DeepSearch 共用同一个服务侧模型连接配置，但仍拥有独立的温度、
        # 输入/输出预算和重试策略。连接参数可在 report-runtime 显式覆盖；这是一项
        # 明确的服务级共享策略，不从 Java 请求、Artifact 或任意运行输入读取模型连接。
        values = dict(config_section(configuration, "research-runtime"))
        values.update(config_section(configuration, "report-runtime"))
        return cls._from_values(values)

    @classmethod
    def _from_values(cls, values: Mapping[str, Any]) -> "ReportRuntimeSettings":
        def text(name: str, default: str = "") -> str:
            return str(values.get(name, default) or "").strip() or default

        provider = text("provider", "openai-compatible").lower()
        model = text("model")
        base_url = text("api-base")
        api_key = text("api-key")
        if not model or not base_url or not api_key:
            raise ToolExecutionError(
                "report_llm_not_configured",
                "报告模型未配置，请在 config/application.local.yaml 的 report-runtime 段设置 model、api-base 和 api-key。",
            )
        if provider not in {"openai", "openai-compatible"}:
            raise ToolExecutionError("report_llm_not_configured", f"不支持的报告模型 Provider: {provider}")
        return cls(
            provider=provider,
            model=model,
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            timeout_seconds=_number(values, "timeout-seconds", 120.0, minimum=10.0, maximum=300.0, cast=float),
            max_retries=_number(values, "max-retries", 1, minimum=0, maximum=3, cast=int),
            temperature=_number(values, "temperature", 0.0, minimum=0.0, maximum=1.0, cast=float),
            max_output_tokens=_number(values, "max-output-tokens", 8192, minimum=512, maximum=8192, cast=int),
            max_input_chars=_number(values, "max-input-chars", 48000, minimum=2000, maximum=100000, cast=int),
            max_file_chars=_number(values, "max-file-chars", 16000, minimum=1000, maximum=50000, cast=int),
            max_artifact_bytes=_number(values, "max-artifact-bytes", 2097152, minimum=4096, maximum=10485760, cast=int),
            delta_batch_chars=_number(values, "delta-batch-chars", 480, minimum=64, maximum=4000, cast=int),
            delta_interval_ms=_number(values, "delta-interval-ms", 350, minimum=50, maximum=3000, cast=int),
            max_delta_chars=_number(values, "max-delta-chars", 120000, minimum=1000, maximum=500000, cast=int),
        )

    def safe_metadata(self) -> dict[str, object]:
        return {"provider": self.provider, "modelAlias": self.model, "configured": True}


def _number(values: Mapping[str, Any], name: str, default: int | float, *, minimum: int | float,
            maximum: int | float, cast):
    try:
        value = cast(str(values.get(name, default)).strip())
    except (TypeError, ValueError) as exc:
        raise ToolExecutionError("report_llm_not_configured", f"{name} 配置无效") from exc
    if not minimum <= value <= maximum:
        raise ToolExecutionError("report_llm_not_configured", f"{name} 必须在 {minimum} 到 {maximum} 之间")
    return value

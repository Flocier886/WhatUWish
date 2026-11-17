"""DeepSearch 独立运行配置，兼容旧 research-runtime 本地段。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Mapping

from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.errors import ToolExecutionError


@dataclass(frozen=True)
class DeepSearchRuntimeSettings:
    provider: str
    model: str
    base_url: str
    api_key: str
    timeout_seconds: float = 240.0
    max_retries: int = 0
    temperature: float = 0.0
    max_output_tokens: int = 3200
    max_context_chars: int = 48_000
    max_searches: int = 12
    max_results_per_search: int = 12
    max_fetches: int = 10
    search_concurrency: int = 5
    fetch_concurrency: int = 4

    @classmethod
    def load(cls) -> "DeepSearchRuntimeSettings":
        return cls.from_configuration(load_application_configuration())

    @classmethod
    def from_configuration(cls, configuration: Mapping[str, Any]) -> "DeepSearchRuntimeSettings":
        current = config_section(configuration, "deepsearch-runtime")
        legacy = config_section(configuration, "research-runtime")
        values = {**legacy, **current}
        return cls._from_values(values)

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> "DeepSearchRuntimeSettings":
        env = environ if environ is not None else os.environ
        return cls._from_values({
            "provider": env.get("DEEPSEARCH_LLM_PROVIDER", env.get("RESEARCH_LLM_PROVIDER", "openai-compatible")),
            "model": env.get("DEEPSEARCH_LLM_MODEL", env.get("RESEARCH_LLM_MODEL", "")),
            "api-base": env.get("DEEPSEARCH_LLM_BASE_URL", env.get("RESEARCH_LLM_BASE_URL", "")),
            "api-key": env.get("DEEPSEARCH_LLM_API_KEY", env.get("RESEARCH_LLM_API_KEY", "")),
        })

    @classmethod
    def _from_values(cls, values: Mapping[str, Any]) -> "DeepSearchRuntimeSettings":
        def text(name: str, default: str = "") -> str:
            return str(values.get(name, default) or "").strip() or default

        provider, model, base_url, api_key = text("provider", "openai-compatible").lower(), text("model"), text("api-base"), text("api-key")
        if not model or not base_url or not api_key:
            raise ToolExecutionError("deep_search_llm_not_configured", "DeepSearch 模型未配置，请在 deepsearch-runtime 段设置 model、api-base 和 api-key。")
        if provider not in {"openai-compatible", "openai"}:
            raise ToolExecutionError("deep_search_llm_not_configured", f"不支持的 DeepSearch 模型 Provider: {provider}")
        return cls(
            provider=provider, model=model, base_url=base_url.rstrip("/"), api_key=api_key,
            timeout_seconds=_number(values, "timeout-seconds", 240.0, 10.0, 600.0, float),
            max_retries=_number(values, "max-retries", 0, 0, 3, int),
            temperature=_number(values, "temperature", 0.0, 0.0, 1.0, float),
            max_output_tokens=_number(values, "max-output-tokens", 3200, 512, 8192, int),
            max_context_chars=_number(values, "max-context-chars", 48_000, 4_000, 80_000, int),
            max_searches=_number(values, "max-searches", 12, 1, 12, int),
            max_results_per_search=_number(values, "max-results-per-search", 12, 1, 12, int),
            max_fetches=_number(values, "max-fetches", 10, 1, 10, int),
            search_concurrency=_number(values, "search-concurrency", 5, 1, 8, int),
            fetch_concurrency=_number(values, "fetch-concurrency", 4, 1, 8, int),
        )

    def safe_metadata(self) -> dict[str, object]:
        return {"provider": self.provider, "modelAlias": self.model, "configured": True}


def _number(values: Mapping[str, Any], name: str, default: Any, minimum: Any, maximum: Any, parser):
    try:
        value = parser(str(values.get(name, default)).strip())
    except (TypeError, ValueError) as exc:
        raise ToolExecutionError("deep_search_llm_not_configured", f"{name} 必须是数字") from exc
    if not minimum <= value <= maximum:
        raise ToolExecutionError("deep_search_llm_not_configured", f"{name} 必须在 {minimum} 到 {maximum} 之间")
    return value

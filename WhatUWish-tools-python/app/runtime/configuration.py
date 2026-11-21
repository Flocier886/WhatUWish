"""Python Tool Runtime 的文件化配置加载器。

配置优先级从低到高依次为：版本库默认配置、本机覆盖配置、进程环境变量。
本机密钥只应写在 ``config/application.local.yaml``，该文件已被 Git 忽略；
环境变量保留给容器、CI 和生产部署使用。
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from copy import deepcopy
from pathlib import Path
from typing import Any

import yaml

from app.runtime.errors import ToolExecutionError


_ENVIRONMENT_OVERRIDES: dict[str, tuple[str, str]] = {
    "WEB_SEARCH_PROVIDER": ("web-search", "provider"),
    "WEB_SEARCH_API_BASE": ("web-search", "api-base"),
    "WEB_SEARCH_API_KEY": ("web-search", "api-key"),
    "WEB_SEARCH_TIMEOUT_SECONDS": ("web-search", "timeout-seconds"),
    "RESEARCH_LLM_PROVIDER": ("research-runtime", "provider"),
    "RESEARCH_LLM_MODEL": ("research-runtime", "model"),
    "RESEARCH_LLM_BASE_URL": ("research-runtime", "api-base"),
    "RESEARCH_LLM_API_KEY": ("research-runtime", "api-key"),
    "RESEARCH_LLM_TIMEOUT_SECONDS": ("research-runtime", "timeout-seconds"),
    "RESEARCH_LLM_MAX_RETRIES": ("research-runtime", "max-retries"),
    "RESEARCH_LLM_TEMPERATURE": ("research-runtime", "temperature"),
    "RESEARCH_LLM_MAX_OUTPUT_TOKENS": ("research-runtime", "max-output-tokens"),
    "RESEARCH_LLM_MAX_CALLS_PER_RUN": ("research-runtime", "max-calls-per-run"),
    "RESEARCH_LLM_MAX_CONTEXT_CHARS": ("research-runtime", "max-context-chars"),
    "RESEARCH_MAX_ITERATIONS": ("research-runtime", "max-iterations"),
    "RESEARCH_MAX_SEARCHES": ("research-runtime", "max-searches"),
    "RESEARCH_MAX_RESULTS_PER_SEARCH": ("research-runtime", "max-results-per-search"),
    "RESEARCH_MAX_FETCHES": ("research-runtime", "max-fetches"),
    "RESEARCH_SEARCH_CONCURRENCY": ("research-runtime", "search-concurrency"),
    "RESEARCH_FETCH_CONCURRENCY": ("research-runtime", "fetch-concurrency"),
    "DEEPSEARCH_LLM_PROVIDER": ("deepsearch-runtime", "provider"),
    "DEEPSEARCH_LLM_MODEL": ("deepsearch-runtime", "model"),
    "DEEPSEARCH_LLM_BASE_URL": ("deepsearch-runtime", "api-base"),
    "DEEPSEARCH_LLM_API_KEY": ("deepsearch-runtime", "api-key"),
    "DEEPSEARCH_LLM_TIMEOUT_SECONDS": ("deepsearch-runtime", "timeout-seconds"),
    "DEEPSEARCH_LLM_MAX_RETRIES": ("deepsearch-runtime", "max-retries"),
    "DEEPSEARCH_LLM_TEMPERATURE": ("deepsearch-runtime", "temperature"),
    "DEEPSEARCH_LLM_MAX_OUTPUT_TOKENS": ("deepsearch-runtime", "max-output-tokens"),
    "DEEPSEARCH_MAX_CONTEXT_CHARS": ("deepsearch-runtime", "max-context-chars"),
    "DEEPSEARCH_MAX_SEARCHES": ("deepsearch-runtime", "max-searches"),
    "DEEPSEARCH_MAX_RESULTS_PER_SEARCH": ("deepsearch-runtime", "max-results-per-search"),
    "DEEPSEARCH_MAX_FETCHES": ("deepsearch-runtime", "max-fetches"),
    "DEEPSEARCH_SEARCH_CONCURRENCY": ("deepsearch-runtime", "search-concurrency"),
    "DEEPSEARCH_FETCH_CONCURRENCY": ("deepsearch-runtime", "fetch-concurrency"),
    "CODE_INTERPRETER_MODEL": ("code-interpreter-runtime", "model"),
    "CODE_INTERPRETER_API_BASE": ("code-interpreter-runtime", "api-base"),
    "CODE_INTERPRETER_API_KEY": ("code-interpreter-runtime", "api-key"),
    "CODE_INTERPRETER_MAX_OUTPUT_TOKENS": ("code-interpreter-runtime", "max-output-tokens"),
    "REPORT_LLM_PROVIDER": ("report-runtime", "provider"),
    "REPORT_LLM_MODEL": ("report-runtime", "model"),
    "REPORT_LLM_BASE_URL": ("report-runtime", "api-base"),
    "REPORT_LLM_API_KEY": ("report-runtime", "api-key"),
    "REPORT_LLM_TIMEOUT_SECONDS": ("report-runtime", "timeout-seconds"),
    "REPORT_LLM_MAX_RETRIES": ("report-runtime", "max-retries"),
    "REPORT_LLM_TEMPERATURE": ("report-runtime", "temperature"),
    "REPORT_LLM_MAX_OUTPUT_TOKENS": ("report-runtime", "max-output-tokens"),
    "REPORT_MAX_INPUT_CHARS": ("report-runtime", "max-input-chars"),
    "REPORT_MAX_FILE_CHARS": ("report-runtime", "max-file-chars"),
    "REPORT_MAX_ARTIFACT_BYTES": ("report-runtime", "max-artifact-bytes"),
    "REPORT_DELTA_BATCH_CHARS": ("report-runtime", "delta-batch-chars"),
    "REPORT_DELTA_INTERVAL_MS": ("report-runtime", "delta-interval-ms"),
    "REPORT_MAX_DELTA_CHARS": ("report-runtime", "max-delta-chars"),
    # 图片生成模型与普通调研/报告模型可以共用连接配置，但图片任务的耗时和
    # 输入输出预算必须独立收紧。凭据只在服务端 YAML 或部署环境中出现。
    "IMAGE_GENERATION_PROVIDER": ("image-generation-runtime", "provider"),
    "IMAGE_GENERATION_API_STYLE": ("image-generation-runtime", "api-style"),
    "IMAGE_GENERATION_MODEL": ("image-generation-runtime", "model"),
    "IMAGE_GENERATION_BASE_URL": ("image-generation-runtime", "api-base"),
    "IMAGE_GENERATION_API_KEY": ("image-generation-runtime", "api-key"),
    "IMAGE_GENERATION_TIMEOUT_SECONDS": ("image-generation-runtime", "timeout-seconds"),
    "IMAGE_GENERATION_MAX_REFERENCE_BYTES": ("image-generation-runtime", "max-reference-bytes"),
    "IMAGE_GENERATION_MAX_OUTPUT_COUNT": ("image-generation-runtime", "max-output-count"),
    # Artifact Storage 必须与 Java 内容访问端使用语义一致的配置来源。默认值可
    # 提交，S3 endpoint、bucket 与凭据只写本机 local YAML 或部署环境变量。
    "AI_AGENT_ARTIFACT_STORAGE_TYPE": ("artifact-storage", "type"),
    "AI_AGENT_ARTIFACT_STORAGE_DIR": ("artifact-storage", "local-root"),
    "AI_AGENT_ARTIFACT_S3_ENABLED": ("artifact-storage", "s3-enabled"),
    "AI_AGENT_ARTIFACT_S3_ENDPOINT": ("artifact-storage", "s3-endpoint"),
    "AI_AGENT_ARTIFACT_S3_BUCKET": ("artifact-storage", "s3-bucket"),
    "AI_AGENT_ARTIFACT_S3_REGION": ("artifact-storage", "s3-region"),
    "AI_AGENT_ARTIFACT_S3_PATH_STYLE": ("artifact-storage", "s3-path-style"),
    "AI_AGENT_ARTIFACT_S3_ACCESS_KEY": ("artifact-storage", "s3-access-key"),
    "AI_AGENT_ARTIFACT_S3_SECRET_KEY": ("artifact-storage", "s3-secret-key"),
    "AI_AGENT_ARTIFACT_S3_PREFIX": ("artifact-storage", "s3-prefix"),
    "AI_AGENT_ARTIFACT_S3_CONNECT_TIMEOUT_SECONDS": ("artifact-storage", "s3-connect-timeout-seconds"),
    "AI_AGENT_ARTIFACT_S3_READ_TIMEOUT_SECONDS": ("artifact-storage", "s3-read-timeout-seconds"),
    # OCR 与普通调研模型独立配置；兼容 master 的 OCR_TYPE/VLM/DeepSeek 环境变量。
    "OCR_TYPE": ("material-ocr", "type"),
    "VLM_MODEL_BASE_URL": ("material-ocr", "api-base"),
    "VLM_API_KEY": ("material-ocr", "api-key"),
    "VLM_MODEL_NAME": ("material-ocr", "model"),
    "DEEPSEEK_OCR_BASE_URL": ("material-ocr", "deepseek-api-base"),
    "DEEPSEEK_OCR_API_KEY": ("material-ocr", "deepseek-api-key"),
    "DEEPSEEK_OCR_MODEL_NAME": ("material-ocr", "deepseek-model"),
    "PYTHON_TOOL_SERVER_HOST": ("server", "host"),
    "PYTHON_TOOL_SERVER_PORT": ("server", "port"),
}

_OPENAI_FALLBACK_OVERRIDES: dict[str, tuple[str, str]] = {
    "OPENAI_PROVIDER": ("research-runtime", "provider"),
    "OPENAI_MODEL": ("research-runtime", "model"),
    "OPENAI_BASE_URL": ("research-runtime", "api-base"),
    "OPENAI_API_KEY": ("research-runtime", "api-key"),
}


def project_root() -> Path:
    """返回 Python Tool Service 的工程根目录，不依赖当前工作目录。"""
    return Path(__file__).resolve().parents[2]


def load_application_configuration(
    *,
    environ: Mapping[str, str] | None = None,
    root: Path | None = None,
) -> dict[str, Any]:
    """加载合并后的运行配置。

    ``application.yaml`` 承载可公开提交的默认值，``application.local.yaml``
    只承载开发机的密钥或本地覆盖值。文件不存在时按空配置处理，以便测试和
    受环境变量管理的部署继续工作。
    """
    active_root = root or project_root()
    configuration = _load_yaml(active_root / "config" / "application.yaml")
    local_overrides = _load_yaml(active_root / "config" / "application.local.yaml")
    merged = _deep_merge(configuration, local_overrides)
    return _apply_environment_overrides(merged, environ if environ is not None else os.environ)


def config_section(configuration: Mapping[str, Any], section: str) -> Mapping[str, Any]:
    """读取一个顶级配置段，并把错误限制在配置边界内。"""
    value = configuration.get(section, {})
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise ToolExecutionError("application_config_invalid", f"配置段 {section} 必须是对象。")
    return value


def _load_yaml(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        parsed = yaml.safe_load(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ToolExecutionError("application_config_invalid", f"无法读取配置文件：{path.name}") from exc
    except yaml.YAMLError as exc:
        raise ToolExecutionError("application_config_invalid", f"配置文件 {path.name} 不是有效 YAML。") from exc
    if parsed is None:
        return {}
    if not isinstance(parsed, dict):
        raise ToolExecutionError("application_config_invalid", f"配置文件 {path.name} 的根节点必须是对象。")
    return parsed


def _deep_merge(base: Mapping[str, Any], overrides: Mapping[str, Any]) -> dict[str, Any]:
    result = deepcopy(dict(base))
    for key, override in overrides.items():
        current = result.get(key)
        # 本地模板中的“只有注释的配置段”会被 YAML 解析为 null。它表达的是
        # 不覆盖默认段，而不是删除 application.yaml 中的整段默认配置。
        if isinstance(current, Mapping) and override is None:
            continue
        if isinstance(current, Mapping) and isinstance(override, Mapping):
            result[key] = _deep_merge(current, override)
        else:
            result[key] = deepcopy(override)
    return result


def _apply_environment_overrides(configuration: Mapping[str, Any], environ: Mapping[str, str]) -> dict[str, Any]:
    result = deepcopy(dict(configuration))
    # 旧的通用 OpenAI 环境变量仅在对应 Research 配置不存在时兜底，
    # 不得覆盖 application.local.yaml 或新的 RESEARCH_LLM_* 变量。
    for environment_name, (section, key) in _OPENAI_FALLBACK_OVERRIDES.items():
        value = str(environ.get(environment_name, "")).strip()
        if not value:
            continue
        section_data = result.setdefault(section, {})
        if not isinstance(section_data, dict):
            raise ToolExecutionError("application_config_invalid", f"配置段 {section} 必须是对象。")
        if not str(section_data.get(key, "")).strip():
            section_data[key] = value
    for environment_name, (section, key) in _ENVIRONMENT_OVERRIDES.items():
        value = str(environ.get(environment_name, "")).strip()
        if not value:
            continue
        section_data = result.setdefault(section, {})
        if not isinstance(section_data, dict):
            raise ToolExecutionError("application_config_invalid", f"配置段 {section} 必须是对象。")
        section_data[key] = value
    return result

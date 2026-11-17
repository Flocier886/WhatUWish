"""安全抓取单个公开网页正文。"""

from __future__ import annotations

import os
from dataclasses import replace

from bs4 import BeautifulSoup

from app.deepsearch.llm_client import OpenAiCompatibleDeepSearchWriter
from app.deepsearch.settings import DeepSearchRuntimeSettings
from app.runtime.artifact_storage import ArtifactStorageError, persist_text
from app.runtime.context import clip_text
from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.web_security import SafeWebFetcher


DEFAULT_FETCH_TIMEOUT_SECONDS = 15.0
DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
DEFAULT_OBSERVATION_LIMIT = 500
MAX_PROMPT_CHARS = 1000
MAX_EXTRACT_INPUT_CHARS = 100_000
MAX_EXTRACT_OUTPUT_CHARS = 5000
MAX_EXTRACT_TIMEOUT_SECONDS = 90


class WebFetchTool(BasePythonTool):
    """抓取已校验的公开 HTTP(S) 网页，并返回预算化正文。"""

    name = "web_fetch"
    description = "安全抓取公开 HTTP(S) 网页正文，仅支持 HTML、Markdown 和纯文本。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            url = self.require_argument(request.arguments, "url")
            prompt = request.arguments.get("prompt")
            if prompt is not None and (not isinstance(prompt, str) or len(prompt.strip()) > MAX_PROMPT_CHARS):
                raise ToolExecutionError("invalid_argument", f"prompt 必须是最多 {MAX_PROMPT_CHARS} 字符的字符串")
            prompt = prompt.strip() if prompt else ""
            timeout_seconds = self._resolve_timeout(request)
            max_response_bytes = self._positive_env_int("WEB_FETCH_MAX_RESPONSE_BYTES", DEFAULT_MAX_RESPONSE_BYTES)
            fetched = SafeWebFetcher().fetch(url, timeout_seconds, max_response_bytes)
            raw_text = fetched.content.decode("utf-8", errors="replace")
            title, content, extractor = self._extract_content(raw_text, fetched.content_type, fetched.final_url)
            title = title or fetched.final_url
            stored = persist_text(content, "web-page.txt", request.context)
            artifact = self.artifact(
                artifact_id=stored.artifact_id,
                name=title,
                artifact_type="web_page",
                artifact_role="source",
                visibility="visible",
                source_type="web_fetch",
                source_name=self.name,
                file_name=stored.file_name,
                storage_type=stored.storage_type,
                storage_ref=stored.storage_ref,
                storage_key=stored.storage_key,
                content_summary=clip_text(content, DEFAULT_OBSERVATION_LIMIT),
                mime_type=stored.mime_type,
                file_size=stored.file_size,
                file_hash=stored.file_hash,
                metadata={
                    "finalUrl": fetched.final_url,
                    "sourceContentChars": len(content),
                    "extractor": extractor,
                },
            )
            extraction = self._extract_with_prompt(prompt, content) if prompt else None
            observation = self._observation(title, fetched.final_url, content, stored)
            if extraction is not None:
                extracted_text, degraded, error_code = extraction
                observation += "\n按问题提炼" + ("（模型失败，以下为原文摘录）" if degraded else "") + "：\n" + extracted_text
                if error_code:
                    observation += f"\n提炼错误类别：{error_code}"

            return self.success(
                output=f"已抓取网页并登记 artifact：{title}",
                llm_observation=observation,
                structured={
                    "url": url,
                    "finalUrl": fetched.final_url,
                    "title": title,
                    "contentType": fetched.content_type,
                    "sourceContentChars": len(content),
                    "extractor": extractor,
                    "artifactId": stored.artifact_id,
                    **({"extractedContent": extraction[0], "extractDegraded": extraction[1],
                        "extractErrorCode": extraction[2]} if extraction else {}),
                },
                artifacts=[artifact],
                metadata={
                    "statusCode": fetched.status_code,
                    "responseBytes": len(fetched.content),
                    "contentChars": len(content),
                    "redirectCount": fetched.redirect_count,
                    "maxResponseBytes": max_response_bytes,
                },
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except (ArtifactStorageError, OSError, ValueError) as exc:
            return self.failure(
                "artifact_storage_error",
                f"网页正文已提取，但无法登记 artifact：{exc}",
                llm_observation="网页正文已提取，但 artifact storage 写入失败；当前内容不可作为可追踪来源使用。请检查存储配置后重试该 URL。",
            )

    @staticmethod
    def _extract_with_prompt(prompt: str, content: str) -> tuple[str, bool, str | None]:
        # 原文已经持久化；模型结果只作为 observation，失败不会丢失来源文件。
        # 对齐 master WebFetchTool.applyPromptToContent 的完整用户 prompt。
        model_prompt = (f"Web page content:\n---\n{content[:MAX_EXTRACT_INPUT_CHARS]}\n---\n\n"
                        f"{prompt}\n\nProvide a concise response based only on the content above.\n"
                        "- Prefer short quotes; do not dump the entire page\n"
                        "- Use quotation marks for exact language from the source\n")
        try:
            settings = DeepSearchRuntimeSettings.load()
            extract_settings = replace(settings, timeout_seconds=min(settings.timeout_seconds, MAX_EXTRACT_TIMEOUT_SECONDS),
                                       max_retries=0, max_output_tokens=min(settings.max_output_tokens, 1500))
            answer = OpenAiCompatibleDeepSearchWriter(extract_settings).write("", model_prompt)
            return clip_text(answer, MAX_EXTRACT_OUTPUT_CHARS), False, None
        except ToolExecutionError as exc:
            return clip_text(content, DEFAULT_OBSERVATION_LIMIT), True, exc.error_code
        except Exception:
            return clip_text(content, DEFAULT_OBSERVATION_LIMIT), True, "web_fetch_extract_failed"

    @staticmethod
    def _observation(title: str, final_url: str, content: str, stored) -> str:
        return "\n".join([
            f"已抓取网页：{title}",
            f"最终 URL：{final_url}",
            f"正文长度：{len(content)} 字符；完整内容已保存为 artifact。",
            f"artifactId: {stored.artifact_id}",
            f"代码分析导入后路径：inputs/artifacts/{stored.artifact_id}-web-page.txt",
            "摘要：",
            clip_text(content, DEFAULT_OBSERVATION_LIMIT),
            "后续建议：如需正文细节，请用 artifact_read 并传入 artifactId 分段读取；如需代码分析，可将该 ID 放入 sourceArtifactIds。",
        ])

    def _resolve_timeout(self, request: ToolExecuteRequest) -> float:
        raw_value = request.arguments.get("timeoutSeconds", DEFAULT_FETCH_TIMEOUT_SECONDS)
        try:
            timeout_seconds = float(raw_value)
        except (TypeError, ValueError) as exc:
            raise ToolExecutionError("invalid_argument", "timeoutSeconds 必须是数字") from exc
        if not 1 <= timeout_seconds <= 60:
            raise ToolExecutionError("invalid_argument", "timeoutSeconds 必须在 1 到 60 秒之间")
        return timeout_seconds

    @staticmethod
    def _positive_env_int(name: str, default: int) -> int:
        raw_value = os.getenv(name, str(default))
        try:
            value = int(raw_value)
        except ValueError as exc:
            raise ToolExecutionError("fetch_invalid_config", f"{name} 必须是整数") from exc
        if value <= 0:
            raise ToolExecutionError("fetch_invalid_config", f"{name} 必须大于 0")
        return value

    @staticmethod
    def _extract_content(raw_text: str, content_type: str, final_url: str) -> tuple[str, str, str]:
        if content_type in {"text/html", "application/xhtml+xml"}:
            soup = BeautifulSoup(raw_text, "lxml")
            title_node = soup.title
            title = title_node.get_text(strip=True) if title_node else ""
            if title_node:
                title_node.decompose()
            for tag in soup(["script", "style", "noscript", "svg"]):
                tag.decompose()
            content = "\n".join(line for line in soup.get_text("\n", strip=True).splitlines() if line.strip())
            return title, content, "beautifulsoup"
        content = "\n".join(line for line in raw_text.splitlines() if line.strip())
        return final_url, content, "plain_text"

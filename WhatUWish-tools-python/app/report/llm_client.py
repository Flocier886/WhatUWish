"""最小 OpenAI-compatible Report 流式模型客户端。"""

from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

import httpx

from app.report.settings import ReportRuntimeSettings
from app.runtime.http_utils import safe_upstream_error_detail
from app.runtime.errors import ToolExecutionError


class OpenAiCompatibleReportLlmClient:
    def __init__(self, settings: ReportRuntimeSettings, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    def stream(self, *, system_prompt: str, user_prompt: str) -> Iterator[str]:
        payload = {
            "model": self._settings.model,
            "temperature": self._settings.temperature,
            "max_tokens": self._settings.max_output_tokens,
            "stream": True,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }
        last_error: ToolExecutionError | None = None
        for attempt in range(self._settings.max_retries + 1):
            emitted_content = False
            try:
                for content in self._stream_once(payload):
                    emitted_content = True
                    yield content
                return
            except ToolExecutionError as exc:
                # 已经把正文交给调用方时重试会重复写入最终 Artifact，因此只允许在
                # 首个 chunk 前重试可恢复故障。
                if emitted_content or exc.error_code in {"report_llm_authentication_failed", "report_llm_request_rejected"}:
                    raise
                last_error = exc
        assert last_error is not None
        last_error.diagnostics.update({"retryCount": self._settings.max_retries, "retryExhausted": True})
        raise last_error

    def _stream_once(self, payload: dict[str, Any]) -> Iterator[str]:
        timeout = httpx.Timeout(self._settings.timeout_seconds, connect=min(10.0, self._settings.timeout_seconds))
        try:
            with httpx.Client(timeout=timeout, transport=self._transport) as client:
                with client.stream(
                    "POST",
                    self._endpoint(),
                    headers={"Authorization": f"Bearer {self._settings.api_key}", "Content-Type": "application/json"},
                    json=payload,
                ) as response:
                    self._raise_for_status(response)
                    received_content = False
                    for line in response.iter_lines():
                        if not line or not line.startswith("data:"):
                            continue
                        raw_data = line[5:].strip()
                        if raw_data == "[DONE]":
                            if received_content:
                                return
                            break
                        try:
                            body = json.loads(raw_data)
                            if not isinstance(body, dict):
                                raise ValueError("response body invalid")
                            choices = body.get("choices")
                            if not isinstance(choices, list):
                                raise ValueError("choices missing")
                            # 部分 OpenAI-compatible 服务会在 [DONE] 前补一帧 usage，
                            # 该帧 choices 为空且不携带正文，直接忽略即可。
                            if not choices:
                                continue
                            choice = choices[0]
                            if not isinstance(choice, dict):
                                raise ValueError("choice invalid")
                            delta = choice.get("delta")
                            # OpenAI-compatible SSE 的首帧常常只声明 role，没有正文；
                            # 它是合法帧，不能把它误判为协议异常。
                            if delta is None:
                                delta = {}
                            if not isinstance(delta, dict):
                                raise ValueError("delta invalid")
                            content = delta.get("content")
                            if content is not None and not isinstance(content, str):
                                raise ValueError("content invalid")
                        except (AttributeError, KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
                            raise ToolExecutionError("report_llm_response_invalid", "报告模型流式响应格式无效") from exc
                        if isinstance(content, str) and content:
                            received_content = True
                            yield content
                    if received_content:
                        return
        except httpx.TimeoutException as exc:
            raise ToolExecutionError("report_llm_request_failed", "报告模型请求超时", {"category": "timeout"}) from exc
        except httpx.HTTPError as exc:
            raise ToolExecutionError("report_llm_request_failed", "报告模型请求失败", {"category": "transport_error"}) from exc
        raise ToolExecutionError("report_llm_response_invalid", "报告模型没有返回正文内容")

    def _raise_for_status(self, response: httpx.Response) -> None:
        if not response.is_error:
            return
        if response.status_code in {401, 403}:
            raise ToolExecutionError("report_llm_authentication_failed", "报告模型认证失败", {"httpStatus": response.status_code})
        if response.status_code == 429 or response.status_code >= 500:
            raise ToolExecutionError("report_llm_request_failed", "报告模型暂时不可用", {"httpStatus": response.status_code})
        detail = safe_upstream_error_detail(response)
        suffix = f"：{detail}" if detail else ""
        raise ToolExecutionError("report_llm_request_rejected", f"报告模型返回 HTTP {response.status_code}{suffix}", {"httpStatus": response.status_code})

    def _endpoint(self) -> str:
        base = self._settings.base_url.rstrip("/")
        if base.endswith("/chat/completions"):
            return base
        if base.endswith("/v1"):
            return f"{base}/chat/completions"
        return f"{base}/v1/chat/completions"

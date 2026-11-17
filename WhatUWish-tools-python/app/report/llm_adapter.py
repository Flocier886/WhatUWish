"""将本项目的 OpenAI-compatible 流客户端适配为公开 Report 的 ``ask_llm`` 调用形态。"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from app.report.llm_client import OpenAiCompatibleReportLlmClient


class ReportLlmAdapter:
    def __init__(self, client: OpenAiCompatibleReportLlmClient) -> None:
        self._client = client

    def ask_llm(
        self,
        *,
        messages: str | list[dict[str, Any]],
        model: str,
        stream: bool,
        temperature: float | None,
        top_p: float,
        only_content: bool,
    ) -> Iterator[str]:
        """保留 master 调用参数形态；模型名由本项目 Report Runtime 配置拥有。"""
        del model, stream, temperature, top_p, only_content
        if isinstance(messages, str):
            yield from self._client.stream(system_prompt="", user_prompt=messages)
            return
        system_prompt = ""
        user_prompt = ""
        for message in messages:
            if message.get("role") == "system":
                system_prompt = str(message.get("content", ""))
            elif message.get("role") == "user":
                user_prompt = str(message.get("content", ""))
        yield from self._client.stream(system_prompt=system_prompt, user_prompt=user_prompt)

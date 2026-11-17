"""跨 Rich Tool 的受控 HTTP 响应辅助函数。"""

from __future__ import annotations

import re

import httpx


def safe_upstream_error_detail(response: httpx.Response) -> str | None:
    """返回上游拒绝的短摘要，不泄漏请求体、认证信息或 HTML 正文。"""
    try:
        payload = response.json()
    except ValueError:
        return None
    if not isinstance(payload, dict):
        return None
    error = payload.get("error")
    candidates = (
        error.get("message") if isinstance(error, dict) else None,
        error.get("code") if isinstance(error, dict) else None,
        payload.get("message"),
    )
    for candidate in candidates:
        text = " ".join(str(candidate or "").split())
        if not text:
            continue
        text = re.sub(r"(?i)(bearer\s+)[^\s,;]+", r"\1***", text)
        text = re.sub(r"(?i)(api[_-]?key\s*[:=]\s*)[^\s,;]+", r"\1***", text)
        return text[:240]
    return None

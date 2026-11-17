"""网页抓取的 URL、DNS 与响应预算安全边界。"""

from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from typing import Callable
from urllib.parse import urljoin, urlsplit

import httpx

from app.runtime.errors import ToolExecutionError


DEFAULT_MAX_REDIRECTS = 5


def validate_public_http_url(url: str, resolver: Callable[..., list[tuple]] = socket.getaddrinfo) -> str:
    """校验 URL 协议、主机和当前 DNS 解析结果均可安全访问。"""

    try:
        parsed = urlsplit(url.strip())
        port = parsed.port
    except ValueError as exc:
        raise ToolExecutionError("fetch_target_not_allowed", "web_fetch 收到无效的目标端口") from exc
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ToolExecutionError("fetch_target_not_allowed", "web_fetch 仅允许不含用户信息的 HTTP(S) 公网 URL")

    hostname = parsed.hostname.rstrip(".").lower()
    if hostname == "localhost" or hostname.endswith(".localhost"):
        raise ToolExecutionError("fetch_target_not_allowed", "web_fetch 不允许访问 localhost")
    try:
        addresses = resolver(hostname, port or (443 if parsed.scheme.lower() == "https" else 80), type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ToolExecutionError("fetch_dns_failed", "web_fetch 无法解析目标域名") from exc
    except OSError as exc:
        raise ToolExecutionError("fetch_dns_failed", "web_fetch 解析目标域名失败") from exc

    resolved_ips = {item[4][0] for item in addresses if len(item) >= 5 and item[4]}
    if not resolved_ips:
        raise ToolExecutionError("fetch_dns_failed", "web_fetch 未解析到目标地址")
    for raw_ip in resolved_ips:
        try:
            ip = ipaddress.ip_address(raw_ip)
        except ValueError as exc:
            raise ToolExecutionError("fetch_dns_failed", "web_fetch 得到无效目标地址") from exc
        if not ip.is_global:
            raise ToolExecutionError("fetch_target_not_allowed", "web_fetch 不允许访问非公网目标地址")
    return url


def resolve_redirect_url(current_url: str, location: str | None) -> str:
    if not location:
        raise ToolExecutionError("fetch_redirect_invalid", "web_fetch 收到缺少 Location 的重定向响应")
    return urljoin(current_url, location)


def is_allowed_text_content_type(content_type: str | None) -> bool:
    mime_type = (content_type or "").split(";", 1)[0].strip().lower()
    return mime_type in {
        "text/html",
        "application/xhtml+xml",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
    }


@dataclass(frozen=True)
class FetchedResponse:
    final_url: str
    status_code: int
    content_type: str
    content: bytes
    redirect_count: int


class SafeWebFetcher:
    """手动跟随重定向并限制响应体的 HTTP 客户端。"""

    def __init__(
        self,
        *,
        resolver: Callable[..., list[tuple]] = socket.getaddrinfo,
        transport: httpx.BaseTransport | None = None,
        max_redirects: int = DEFAULT_MAX_REDIRECTS,
    ) -> None:
        self._resolver = resolver
        self._transport = transport
        self._max_redirects = max_redirects

    def fetch(self, url: str, timeout_seconds: float, max_response_bytes: int) -> FetchedResponse:
        current_url = url
        redirect_count = 0
        timeout = httpx.Timeout(timeout_seconds, connect=min(timeout_seconds, 5.0))
        try:
            with httpx.Client(timeout=timeout, follow_redirects=False, transport=self._transport) as client:
                while True:
                    validate_public_http_url(current_url, self._resolver)
                    with client.stream("GET", current_url) as response:
                        if response.is_redirect:
                            if redirect_count >= self._max_redirects:
                                raise ToolExecutionError("fetch_too_many_redirects", "web_fetch 重定向次数超过上限")
                            current_url = resolve_redirect_url(current_url, response.headers.get("location"))
                            redirect_count += 1
                            continue
                        if response.is_error:
                            raise ToolExecutionError("http_fetch_failed", f"web_fetch 返回 HTTP {response.status_code}")
                        content_type = response.headers.get("content-type", "")
                        if not is_allowed_text_content_type(content_type):
                            raise ToolExecutionError("fetch_content_type_not_allowed", "web_fetch 仅支持 HTML、Markdown 或纯文本内容")
                        content = self._read_limited_response(response, max_response_bytes)
                        return FetchedResponse(
                            final_url=str(response.url),
                            status_code=response.status_code,
                            content_type=content_type.split(";", 1)[0].strip().lower(),
                            content=content,
                            redirect_count=redirect_count,
                        )
        except ToolExecutionError:
            raise
        except httpx.TimeoutException as exc:
            raise ToolExecutionError("fetch_timeout", "web_fetch 请求超时") from exc
        except httpx.HTTPError as exc:
            raise ToolExecutionError("http_fetch_failed", "web_fetch 请求失败") from exc

    @staticmethod
    def _read_limited_response(response: httpx.Response, max_response_bytes: int) -> bytes:
        chunks: list[bytes] = []
        total_bytes = 0
        for chunk in response.iter_bytes():
            total_bytes += len(chunk)
            if total_bytes > max_response_bytes:
                raise ToolExecutionError("fetch_response_too_large", "web_fetch 响应正文超过大小上限")
            chunks.append(chunk)
        return b"".join(chunks)

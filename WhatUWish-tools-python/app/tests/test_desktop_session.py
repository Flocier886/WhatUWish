"""4D 桌面会话对现有 owner 沙箱与受认证 API 的合同验证。"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import pytest

from app.code_execution import desktop_session
from app.code_execution import sandbox_backend_config
from app.main import app


class _Commands:
    def __init__(self, *, healthy: bool = True, starts: bool = True):
        self.healthy = healthy
        self.starts = starts
        self.calls: list[str] = []

    def run(self, command: str, **kwargs):
        self.calls.append(command)
        if command.startswith("/start_command.sh"):
            self.healthy = self.starts
            return SimpleNamespace(exit_code=0 if self.starts else 1)
        return SimpleNamespace(exit_code=0 if self.healthy else 7)


class _Manager:
    def __init__(self, commands: _Commands):
        self.sandbox = SimpleNamespace(commands=commands, get_host=lambda port: f"{port}-sandbox.e2b.app")
        self.acquired: list[str] = []
        self.released: list[str] = []
        self.closed: list[str] = []

    def acquire(self, owner: str, **kwargs):
        self.acquired.append(owner)
        return SimpleNamespace(sandbox=self.sandbox)

    def enter_user_operating(self, owner: str, ttl_sec: int):
        return 1700000000.0 + ttl_sec

    def exit_user_operating(self, owner: str):
        self.closed.append(owner)

    def release(self, owner: str):
        self.released.append(owner)


def _payload() -> desktop_session.DesktopSessionRequest:
    return desktop_session.DesktopSessionRequest(
        requestId="run-1", sessionId="session-1", ownerKey="code:session-1", ttlSeconds=600,
    )


def test_open_and_close_reuse_session_owner_hold(monkeypatch):
    manager = _Manager(_Commands())
    monkeypatch.setattr(desktop_session, "get_sandbox_backend", lambda: "e2b")
    monkeypatch.setattr(desktop_session, "get_user_sandbox_manager", lambda: manager)
    opened = desktop_session.open_desktop_session(_payload())
    assert opened == {
        "url": "https://6080-sandbox.e2b.app/vnc.html?autoconnect=1&resize=scale",
        "port": 6080,
        "holdUntil": 1700000600.0,
    }
    assert manager.acquired == manager.released == ["code:session-1"]
    assert manager.closed == []
    assert desktop_session.close_desktop_session(_payload()) == {"ok": True}
    assert manager.closed == ["code:session-1"]


def test_open_requires_e2b_and_matching_session(monkeypatch):
    manager = _Manager(_Commands())
    monkeypatch.setattr(desktop_session, "get_user_sandbox_manager", lambda: manager)
    monkeypatch.setattr(desktop_session, "get_sandbox_backend", lambda: "local")
    with pytest.raises(desktop_session.DesktopSessionError, match="requires CODE_SANDBOX_BACKEND"):
        desktop_session.open_desktop_session(_payload())
    monkeypatch.setattr(desktop_session, "get_sandbox_backend", lambda: "e2b")
    payload = _payload().model_copy(update={"owner_key": "code:another-session"})
    with pytest.raises(desktop_session.DesktopSessionError, match="does not match"):
        desktop_session.open_desktop_session(payload)
    assert manager.acquired == []


def test_failed_desktop_start_releases_hold(monkeypatch):
    manager = _Manager(_Commands(healthy=False, starts=False))
    monkeypatch.setattr(desktop_session, "get_sandbox_backend", lambda: "e2b")
    monkeypatch.setattr(desktop_session, "get_user_sandbox_manager", lambda: manager)
    monkeypatch.setattr(desktop_session.time, "sleep", lambda _: None)
    with pytest.raises(desktop_session.DesktopSessionError, match="failed to start"):
        desktop_session.open_desktop_session(_payload())
    assert manager.closed == manager.released == ["code:session-1"]


def test_desktop_api_requires_material_bridge_token(monkeypatch):
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "bridge-test-token")

    async def invoke():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/api/v1/material/desktop-session/open", json=_payload().model_dump(by_alias=True))

    response = asyncio.run(invoke())
    assert response.status_code == 403


def test_e2b_public_traffic_is_explicit_for_browser_desktop(monkeypatch):
    monkeypatch.delenv("E2B_ALLOW_PUBLIC_TRAFFIC", raising=False)
    monkeypatch.setattr(sandbox_backend_config, "_code_execution_config", lambda key: "true" if key == "e2b-allow-public-traffic" else "")
    assert sandbox_backend_config.build_e2b_create_kwargs(120)["network"] == {"allow_public_traffic": True}
    monkeypatch.setenv("E2B_ALLOW_PUBLIC_TRAFFIC", "false")
    assert sandbox_backend_config.build_e2b_create_kwargs(120)["network"] == {"allow_public_traffic": False}

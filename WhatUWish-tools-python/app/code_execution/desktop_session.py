"""E2B 桌面会话：复用 3A owner 沙箱，打开 noVNC 并保持用户操作时段。"""

from __future__ import annotations

import time
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.code_execution.sandbox_backend_config import get_sandbox_backend
from app.code_execution.user_sandbox_manager import get_user_sandbox_manager

VNC_PORT = 6080
_VNC_HEALTH_CHECK = f"curl -fsS --max-time 1 http://127.0.0.1:{VNC_PORT}/vnc.html >/dev/null"
_START_DESKTOP_AND_WAIT = (
    "/start_command.sh && "
    "for attempt in $(seq 1 20); do "
    f"if {_VNC_HEALTH_CHECK}; then exit 0; fi; "
    "sleep 1; "
    "done; "
    "echo 'noVNC service failed to start on port 6080' >&2; "
    "exit 1"
)


class DesktopSessionRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    request_id: str = Field(alias="requestId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    owner_key: str = Field(alias="ownerKey", min_length=1)
    ttl_seconds: int = Field(default=600, alias="ttlSeconds", ge=60, le=3600)


class DesktopSessionError(Exception):
    def __init__(self, status_code: int, message: str):
        super().__init__(message)
        self.status_code = status_code


def open_desktop_session(body: DesktopSessionRequest) -> dict[str, Any]:
    if get_sandbox_backend() != "e2b":
        raise DesktopSessionError(400, "desktop_session requires CODE_SANDBOX_BACKEND=e2b")
    owner = _owner(body)
    manager = get_user_sandbox_manager()
    lease = manager.acquire(owner, timeout_sec=max(120, body.ttl_seconds), update_timeout=False)
    hold_set = False
    try:
        hold_until = manager.enter_user_operating(owner, ttl_sec=body.ttl_seconds)
        if hold_until is None:
            raise DesktopSessionError(500, "failed to enter user operating hold")
        hold_set = True
        _ensure_desktop_ready(lease.sandbox)
        get_host = getattr(lease.sandbox, "get_host", None)
        if not callable(get_host) or not (host := str(get_host(VNC_PORT) or "").strip()):
            raise DesktopSessionError(503, "sandbox get_host returned empty host")
        return {
            "url": f"https://{host}/vnc.html?autoconnect=1&resize=scale",
            "port": VNC_PORT,
            "holdUntil": hold_until,
        }
    except Exception:
        if hold_set:
            manager.exit_user_operating(owner)
        raise
    finally:
        manager.release(owner)


def close_desktop_session(body: DesktopSessionRequest) -> dict[str, bool]:
    if get_sandbox_backend() == "e2b":
        get_user_sandbox_manager().exit_user_operating(_owner(body))
    return {"ok": True}


def _owner(body: DesktopSessionRequest) -> str:
    # 3A 的代码执行按会话复用 E2B 沙箱；桌面必须指向同一个 owner。
    if body.owner_key != f"code:{body.session_id}":
        raise DesktopSessionError(400, "desktop owner does not match session")
    return body.owner_key


def _ensure_desktop_ready(sandbox: Any) -> None:
    commands = getattr(sandbox, "commands", None)
    run = getattr(commands, "run", None)
    if not callable(run):
        raise DesktopSessionError(503, "sandbox does not support desktop commands")
    try:
        if _succeeded(run(_VNC_HEALTH_CHECK, timeout=5)):
            return
    except Exception:
        pass
    try:
        result = run(_START_DESKTOP_AND_WAIT, user="root", timeout=45)
    except Exception as exc:
        if _ready_after_start(run):
            return
        raise DesktopSessionError(503, f"desktop service failed to start: {exc}") from exc
    if _succeeded(result) or _ready_after_start(run):
        return
    raise DesktopSessionError(503, "desktop service failed to start on port 6080")


def _ready_after_start(run: Any) -> bool:
    for attempt in range(2):
        try:
            if _succeeded(run(_VNC_HEALTH_CHECK, timeout=3)):
                return True
        except Exception:
            pass
        if attempt == 0:
            time.sleep(0.5)
    return False


def _succeeded(result: Any) -> bool:
    return getattr(result, "exit_code", 0) in (None, 0) and not getattr(result, "error", None)

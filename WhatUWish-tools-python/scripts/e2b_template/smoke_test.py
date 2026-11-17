"""真实验收 4D 模板的桌面、可见浏览器、OpenCLI 与 3A run_code。"""

from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from e2b_code_interpreter import Sandbox  # noqa: E402
from app.code_execution.sandbox_backend_config import (  # noqa: E402
    ensure_e2b_code_interpreter_ready, get_e2b_proxy, require_e2b_api_key,
)
from app.code_execution.desktop_session import _ensure_desktop_ready  # noqa: E402
from template import TEMPLATE_ALIAS  # noqa: E402


def check(sandbox: Sandbox, command: str) -> None:
    result = sandbox.commands.run(command, timeout=90)
    if result.exit_code != 0:
        raise RuntimeError(f"smoke command failed: {command}; stderr={result.stderr}")


def main() -> int:
    os.environ.setdefault("E2B_API_KEY", require_e2b_api_key())
    kwargs = {"template": TEMPLATE_ALIAS, "timeout": 600}
    if proxy := get_e2b_proxy():
        kwargs["proxy"] = proxy
    sandbox = Sandbox.create(**kwargs)
    try:
        _ensure_desktop_ready(sandbox)
        check(sandbox, "curl -fsS http://127.0.0.1:6080/vnc.html >/dev/null")
        check(sandbox, "DISPLAY=:0 xdotool getdisplaygeometry >/dev/null")
        check(sandbox, "pgrep -u user -f '/usr/bin/chromium.*chromium-visible' >/dev/null")
        check(sandbox, "opencli --version && opencli doctor")
        # 复合模板保留 master 的懒启动：真实 3A 后端也先启动 Jupyter。
        ensure_e2b_code_interpreter_ready(sandbox)
        result = sandbox.run_code("print('desktop_code_ready')")
        output = "".join(result.logs.stdout)
        if result.error or "desktop_code_ready" not in output:
            raise RuntimeError(f"run_code failed: {result.error}")
        print("desktop=ready chromium=visible opencli=ready run_code=ready")
        print(f"noVNC=https://{sandbox.get_host(6080)}/vnc.html?autoconnect=1&resize=scale")
        return 0
    finally:
        sandbox.kill()


if __name__ == "__main__":
    raise SystemExit(main())

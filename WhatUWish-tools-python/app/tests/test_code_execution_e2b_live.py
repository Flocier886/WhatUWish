"""Opt-in integration tests using the configured E2B account."""

from __future__ import annotations

import os
import json
import socket
import threading
import time
import uuid
from pathlib import Path

import pytest
import uvicorn

from app.code_execution.direct_code_execution import ExecutionCancellation, execute_code
from app.code_execution.bash_sandbox import execute_bash
from app.code_execution.desktop_session import DesktopSessionRequest, open_desktop_session, close_desktop_session
from app.code_execution.e2b_session_sync import session_remote_root
from app.code_execution.user_sandbox_manager import get_user_sandbox_manager
from app.main import app
from app.runtime.models import MaterialExecuteRequest


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_E2B_LIVE_TESTS") != "1", reason="real E2B tests are opt-in"
)


@pytest.fixture
def workspace(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ENABLED", "true")
    monkeypatch.setenv("CODE_SANDBOX_BACKEND", "e2b")
    root = tmp_path / f"session-{uuid.uuid4()}"
    (root / "inputs").mkdir(parents=True)
    (root / "outputs").mkdir()
    return root


def run(workspace: Path, source: str, authorized: list[str] | None = None,
        timeout: int = 15, cancellation: ExecutionCancellation | None = None):
    session_id = workspace.name.removeprefix("session-")
    return execute_code(
        MaterialExecuteRequest(
            toolName="code_execution",
            arguments={
                "source": source,
                "timeoutSeconds": timeout,
                "__authorizedInputPaths": authorized or [],
            },
            context={
                "sessionId": session_id,
                "runId": str(uuid.uuid4()),
                "toolCallId": str(uuid.uuid4()),
            },
            workspaceRoot=str(workspace),
        ), cancellation=cancellation,
    )


def test_real_e2b_reads_input_and_returns_output(workspace: Path):
    (workspace / "inputs" / "sales.csv").write_text("value\n2\n3\n", encoding="utf-8")
    result = run(
        workspace,
        "import pandas as pd\n"
        "df = pd.read_csv('inputs/sales.csv')\n"
        "print(int(df['value'].sum()))\n"
        "df.to_csv('outputs/result.csv', index=False)",
        ["inputs/sales.csv"],
    )
    assert result.success, result.llm_observation
    assert "5" in result.structured["stdout"]
    assert (workspace / "outputs" / "result.csv").read_text(encoding="utf-8") == "value\n2\n3\n"
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/result.csv"]


def test_real_e2b_code_bash_and_desktop_share_session(workspace: Path):
    session_id = workspace.name.removeprefix("session-")
    first = run(workspace, "from pathlib import Path\n"
                "Path('outputs/from-code.txt').write_text('code-ready')", timeout=20)
    assert first.success, first.llm_observation
    owner = f"code:{session_id}"
    sandbox_id = get_user_sandbox_manager()._load(owner)["sandbox_id"]
    request = DesktopSessionRequest(requestId=str(uuid.uuid4()), sessionId=session_id,
                                    ownerKey=owner, ttlSeconds=60)
    opened = open_desktop_session(request)
    try:
        assert "/vnc.html" in opened["url"]
        assert get_user_sandbox_manager()._load(owner)["sandbox_id"] == sandbox_id
    finally:
        close_desktop_session(request)

    command = "cat outputs/from-code.txt && xdotool getdisplaygeometry && opencli --version && printf 'bash-ready' > outputs/from-bash.txt"
    second = execute_bash(MaterialExecuteRequest(
        toolName="bash", arguments={"command": command, "timeout_sec": 20,
                                    "__authorizedInputPaths": []},
        context={"sessionId": session_id, "runId": str(uuid.uuid4()),
                 "toolCallId": str(uuid.uuid4())},
        workspaceRoot=str(workspace),
    ))
    assert second.success, second.llm_observation
    assert second.structured["exitCode"] == 0
    assert "code-ready" in second.structured["stdout"]
    assert (workspace / "outputs" / "from-bash.txt").read_text() == "bash-ready"
    assert [item["relativePath"] for item in second.material_outputs] == ["outputs/from-bash.txt"]
    assert get_user_sandbox_manager()._load(owner)["sandbox_id"] == sandbox_id

    # Mirror master smoke: bind OpenCLI to the visible desktop Chromium and read a real page.
    browser_command = """set -e
opencli doctor >/dev/null
profile_list="$(opencli profile list)"
connected_profile="$(printf '%s\\n' "$profile_list" | awk '
  /^Connected Browser Bridge profiles/ { connected = 1; next }
  /^Disconnected saved profiles/ { exit }
  connected && NF { print $1; exit }
')"
test -n "$connected_profile"
opencli profile use "$connected_profile"
browser_pid="$(pgrep -u user -f '^/usr/bin/chromium --no-sandbox --no-first-run --disable-dev-shm-usage .*--user-data-dir=/home/user/.opencli/chromium-visible' | head -n1)"
window_id="$(xdotool search --onlyvisible --pid "$browser_pid" | head -n1)"
xdotool windowactivate --sync "$window_id"
opencli browser e2b-bash bind
opencli browser e2b-bash open https://example.com
opencli browser e2b-bash get title
"""
    browser = execute_bash(MaterialExecuteRequest(
        toolName="bash", arguments={"command": browser_command, "timeout_sec": 90,
                                    "__authorizedInputPaths": []},
        context={"sessionId": session_id, "runId": str(uuid.uuid4()),
                 "toolCallId": str(uuid.uuid4())},
        workspaceRoot=str(workspace),
    ))
    assert browser.success, browser.llm_observation
    assert browser.structured["exitCode"] == 0, browser.llm_observation
    assert "Example Domain" in browser.structured["stdout"]
    assert get_user_sandbox_manager()._load(owner)["sandbox_id"] == sandbox_id


def test_real_e2b_bash_materializes_and_syncs_registered_skill(workspace: Path, tmp_path: Path):
    skill = tmp_path / "skill-library" / "demo"
    (skill / "scripts").mkdir(parents=True)
    (skill / "SKILL.md").write_text("# demo", encoding="utf-8")
    (skill / "data.txt").write_text("before", encoding="utf-8")
    (skill / "scripts" / "run.py").write_text(
        "from pathlib import Path\n"
        "Path('skills/demo/data.txt').write_text('remote-edit')\n"
        "Path('outputs/skill-result.txt').write_text('from-skill')\n"
        "print('skill-executed')\n", encoding="utf-8")
    session_id = workspace.name.removeprefix("session-")
    result = execute_bash(MaterialExecuteRequest(
        toolName="bash",
        arguments={"command": "python skills/demo/scripts/run.py", "timeout_sec": 60,
                   "__authorizedInputPaths": [], "__skillPaths": {"demo": str(skill)},
                   "__skillLibraryRoot": str(skill.parent)},
        context={"sessionId": session_id, "runId": str(uuid.uuid4()),
                 "toolCallId": str(uuid.uuid4())},
        workspaceRoot=str(workspace),
    ))
    assert result.success, result.llm_observation
    assert result.structured["exitCode"] == 0, result.llm_observation
    assert "skill-executed" in result.structured["stdout"]
    assert result.structured["skillsMaterialized"] == ["demo"]
    assert result.structured["skillsSyncedBack"] == ["demo"]
    assert (skill / "data.txt").read_text(encoding="utf-8") == "remote-edit"
    assert (workspace / "outputs" / "skill-result.txt").read_text(encoding="utf-8") == "from-skill"
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/skill-result.txt"]


def test_real_e2b_input_is_a_mutable_remote_copy(workspace: Path):
    original = workspace / "inputs" / "sales.csv"
    original.write_text("original", encoding="utf-8")
    result = run(
        workspace,
        "from pathlib import Path\n"
        "working = Path('inputs/sales.csv')\n"
        "working.write_text('changed')\n"
        "Path('outputs/proof.txt').write_text(working.read_text())",
        ["inputs/sales.csv"],
    )
    assert result.success, result.llm_observation
    assert original.read_text(encoding="utf-8") == "original"
    assert (workspace / "outputs" / "proof.txt").read_text(encoding="utf-8") == "changed"
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/proof.txt"]

    owner = f"code:{workspace.name.removeprefix('session-')}"
    first_sandbox_id = get_user_sandbox_manager()._load(owner)["sandbox_id"]
    second = run(
        workspace,
        "from pathlib import Path\nprint(Path('inputs/sales.csv').read_text())",
        ["inputs/sales.csv"],
    )
    assert second.success, second.llm_observation
    assert second.structured["stdout"].strip() == "original"
    assert get_user_sandbox_manager()._load(owner)["sandbox_id"] == first_sandbox_id


def test_real_e2b_only_syncs_authorized_inputs_and_delivers_outputs(workspace: Path):
    (workspace / "inputs" / "private.txt").write_text("private", encoding="utf-8")
    result = run(
        workspace,
        "from pathlib import Path\n"
        "print(Path('inputs/private.txt').exists())\n"
        "Path('scratch.txt').write_text('temporary')\n"
        "Path('outputs/result.txt').write_text('deliver')",
    )
    assert result.success, result.llm_observation
    assert "False" in result.structured["stdout"]
    assert not (workspace / "scratch.txt").exists()
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/result.txt"]


def test_real_e2b_preserves_partial_output_on_error(workspace: Path):
    result = run(
        workspace,
        "from pathlib import Path\n"
        "Path('outputs/partial.txt').write_text('ready')\n"
        "raise ValueError('expected')",
    )
    assert not result.success
    assert result.error_code == "code_runtime_error"
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/partial.txt"]


def test_real_e2b_timeout_discards_late_output(workspace: Path):
    result = run(
        workspace,
        "from pathlib import Path\n"
        "Path('outputs/started.txt').write_text('started')\n"
        "while True: pass",
        timeout=1,
    )
    assert not result.success
    assert result.error_code == "code_timeout"
    assert result.material_outputs == []


def test_real_e2b_cancel_discards_output(workspace: Path):
    cancellation = ExecutionCancellation()
    results = []
    worker = threading.Thread(
        target=lambda: results.append(run(workspace, "while True: pass", timeout=30,
                                          cancellation=cancellation)),
        daemon=True,
    )
    worker.start()
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        executor = cancellation._executor
        if executor is not None and executor._impl._sandbox_id:
            break
        time.sleep(0.1)
    else:
        pytest.fail("real E2B sandbox did not start")
    cancellation.cancel()
    worker.join(timeout=15)
    assert not worker.is_alive()
    assert results and results[0].error_code == "code_cancelled"
    assert results[0].material_outputs == []


def test_real_e2b_http_disconnect_kills_sandbox_without_output(workspace: Path, monkeypatch):
    token = f"test-{uuid.uuid4()}"
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", token)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port,
                                           log_level="error", lifespan="off"))
    server_thread = threading.Thread(target=server.run, daemon=True)
    server_thread.start()
    client = None
    session_id = workspace.name.removeprefix("session-")
    owner = f"code:{session_id}"
    manager = get_user_sandbox_manager()
    try:
        deadline = time.monotonic() + 10
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started
        payload = json.dumps({
            "toolName": "code_execution",
            "arguments": {"source": "from pathlib import Path\n"
                        "Path('outputs/started.txt').write_text('started')\n"
                        "while True: pass", "timeoutSeconds": 30},
            "context": {"sessionId": session_id, "runId": str(uuid.uuid4()),
                        "toolCallId": str(uuid.uuid4())},
            "workspaceRoot": str(workspace),
        }).encode("utf-8")
        client = socket.create_connection(("127.0.0.1", port), timeout=5)
        client.sendall(
            f"POST /api/v1/material/execute HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{port}\r\n"
            f"X-Material-Bridge-Token: {token}\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload
        )
        remote_started = f"{session_remote_root(session_id)}/outputs/started.txt"
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            sandbox = manager._live.get(owner)
            if sandbox is not None:
                try:
                    if sandbox.files.read(remote_started, format="bytes") == b"started":
                        break
                except Exception:
                    pass
            time.sleep(0.2)
        else:
            pytest.fail("real E2B code did not reach its execution marker")
        client.close()
        client = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            row = manager._load(owner)
            if row is not None and row["state"] == "MISSING" and not row["sandbox_id"]:
                break
            time.sleep(0.2)
        else:
            pytest.fail("HTTP disconnect did not invalidate the E2B sandbox")
        assert not (workspace / "outputs" / "started.txt").exists()
    finally:
        if client is not None:
            client.close()
        server.should_exit = True
        server_thread.join(timeout=10)
        assert not server_thread.is_alive()

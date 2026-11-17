"""Single-step code execution on the station session workspace."""

from pathlib import Path
import threading
import time
from types import SimpleNamespace
from contextlib import nullcontext
from dataclasses import replace

import pytest

from app.code_execution.direct_code_execution import ExecutionCancellation, execute_code
from app.runtime.models import MaterialExecuteRequest


@pytest.fixture
def workspace(tmp_path: Path, monkeypatch) -> Path:
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ENABLED", "true")
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "true")
    monkeypatch.setenv("CODE_SANDBOX_BACKEND", "local")
    root = tmp_path / "session-test"
    (root / "inputs").mkdir(parents=True)
    (root / "outputs").mkdir()
    return root


def run(root: Path, source: str, **arguments):
    authorized = [path.relative_to(root).as_posix() for path in (root / "inputs").rglob("*") if path.is_file()]
    return execute_code(MaterialExecuteRequest(
        toolName="code_execution",
        arguments={"source": source, "__authorizedInputPaths": authorized, **arguments},
        context={"sessionId": "test", "runId": "run-1", "toolCallId": "call-1"},
        workspaceRoot=str(root),
    ))


def test_python_config_enables_code_execution_without_process_flag(workspace: Path, monkeypatch):
    from app.code_execution import sandbox_backend_config

    monkeypatch.delenv("AI_AGENT_CODE_EXECUTION_ENABLED")
    monkeypatch.setattr(sandbox_backend_config, "_code_execution_config",
                        lambda name: "true" if name == "enabled" else "")
    assert run(workspace, "print(7)").success

    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ENABLED", "false")
    denied = run(workspace, "print(8)")
    assert not denied.success
    assert "未启用" in denied.error_message


def test_csv_calculation_and_changed_output_only(workspace: Path):
    (workspace / "inputs" / "sales.csv").write_text("value\n2\n3\n", encoding="utf-8")
    (workspace / "outputs" / "old.txt").write_text("old", encoding="utf-8")
    result = run(workspace, "import pandas as pd\n"
                 "df = pd.read_csv('inputs/sales.csv')\n"
                 "print(int(df['value'].sum()))\n"
                 "df.to_csv('outputs/result.csv', index=False)")
    assert result.success, result.llm_observation
    assert "5" in result.structured["stdout"]
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/result.csv"]
    assert (workspace / "inputs" / "sales.csv").read_text(encoding="utf-8") == "value\n2\n3\n"
    assert (workspace / "outputs" / "result.csv").exists()


def test_retrieved_source_file_can_be_analyzed_and_delivered(workspace: Path):
    source = workspace / "inputs" / "artifacts" / "web-source-web-page.txt"
    source.parent.mkdir()
    source.write_text("source fact: 42", encoding="utf-8")
    result = run(workspace, "from pathlib import Path\n"
                 "text = Path('inputs/artifacts/web-source-web-page.txt').read_text(encoding='utf-8')\n"
                 "print(text)\n"
                 "Path('outputs/source-analysis.txt').write_text(text, encoding='utf-8')")
    assert result.success, result.llm_observation
    assert "source fact: 42" in result.structured["stdout"]
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/source-analysis.txt"]


def test_excel_calculation(workspace: Path):
    from openpyxl import Workbook

    book = Workbook()
    sheet = book.active
    sheet.append(["value"])
    sheet.append([4])
    sheet.append([6])
    book.save(workspace / "inputs" / "sales.xlsx")
    result = run(workspace, "import pandas as pd\n"
                 "print(int(pd.read_excel('inputs/sales.xlsx')['value'].sum()))")
    assert result.success, result.llm_observation
    assert "10" in result.structured["stdout"]
    assert not result.material_outputs


def test_chart_is_collected_as_real_png(workspace: Path):
    result = run(workspace, "import matplotlib.pyplot as plt\n"
                 "plt.plot([1, 2, 3], [3, 1, 4])\n"
                 "plt.savefig('outputs/chart.png')")
    assert result.success, result.llm_observation
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/chart.png"]
    assert (workspace / "outputs" / "chart.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n")


def test_syntax_and_runtime_failure_keep_diagnostics_and_partial_file(workspace: Path):
    syntax = run(workspace, "def broken(:\n pass")
    assert not syntax.success
    assert syntax.error_code == "code_syntax_error"
    assert syntax.structured["syntaxDiagnostics"]["line"] == 1

    runtime = run(workspace, "from pathlib import Path\n"
                  "print('before failure')\n"
                  "Path('outputs/partial.txt').write_text('ready')\n"
                  "raise RuntimeError('boom')")
    assert not runtime.success
    assert runtime.error_code == "code_runtime_error"
    assert "before failure" in runtime.structured["stdout"]
    assert "boom" in runtime.structured["stderr"]
    assert [item["relativePath"] for item in runtime.material_outputs] == ["outputs/partial.txt"]
    assert runtime.material_outputs[0]["sha256"]


def test_failure_preserves_stdout_truncation(workspace: Path):
    result = run(workspace, "print('x' * 250000)\nraise RuntimeError('boom')")
    assert not result.success
    assert result.structured["stdoutTruncated"] is True
    assert len(result.structured["stdout"]) <= 10_020


def test_authorized_input_byte_budget(workspace: Path):
    (workspace / "inputs" / "large.bin").write_bytes(b"x" * (32 * 1024 * 1024 + 1))
    result = run(workspace, "print('should not run')")
    assert not result.success
    assert result.error_code == "code_request_or_output_invalid"
    assert not result.material_outputs


def test_input_write_and_outside_read_are_denied(workspace: Path):
    source = workspace / "inputs" / "keep.txt"
    source.write_text("original", encoding="utf-8")
    overwrite = run(workspace, "from pathlib import Path\n"
                    "Path('inputs/keep.txt').write_text('changed')")
    assert not overwrite.success
    assert source.read_text(encoding="utf-8") == "original"
    assert not overwrite.material_outputs

    outside = run(workspace, "from pathlib import Path\n"
                  "print(Path('../secret.txt').read_text())")
    assert not outside.success
    assert not outside.material_outputs

    dynamic = run(workspace, "p = 'inputs/keep.txt'\n"
                  "def overwrite(path):\n    open(path, 'w').write('changed')\n"
                  "overwrite(p)")
    assert not dynamic.success
    assert dynamic.error_code == "code_permission_denied"
    assert source.read_text(encoding="utf-8") == "original"

    revoked = run(workspace, "print(open('inputs/keep.txt').read())", __authorizedInputPaths=[])
    assert not revoked.success
    assert revoked.error_code == "code_permission_denied"


def test_timeout_and_server_controls(workspace: Path, monkeypatch):
    timeout = run(workspace, "while True:\n    pass", timeoutSeconds=1)
    assert not timeout.success
    assert timeout.error_code == "code_timeout"

    rejected = run(workspace, "print('x')", fileNames=["https://example.com/x.csv"])
    assert not rejected.success
    assert rejected.error_code == "code_request_or_output_invalid"

    monkeypatch.delenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL")
    disabled = run(workspace, "print('x')")
    assert not disabled.success
    assert disabled.error_code == "code_request_or_output_invalid"


def test_cancellation_stops_local_runner_and_discards_late_output(workspace: Path):
    cancellation = ExecutionCancellation()
    request = MaterialExecuteRequest(
        toolName="code_execution",
        arguments={"source": "from pathlib import Path\n"
                    "Path('outputs/started.txt').write_text('started')\n"
                    "while True: pass"},
        context={"sessionId": "test", "runId": "run-1", "toolCallId": "cancel-1"},
        workspaceRoot=str(workspace),
    )
    results = []
    worker = threading.Thread(target=lambda: results.append(execute_code(request, cancellation)))
    worker.start()
    deadline = time.monotonic() + 10
    while not (workspace / "outputs" / "started.txt").exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    assert (workspace / "outputs" / "started.txt").exists()
    cancellation.cancel()
    worker.join(timeout=10)
    assert not worker.is_alive()
    assert results[0].error_code == "code_cancelled"
    assert results[0].material_outputs == []


def test_e2b_without_credentials_never_falls_back_to_local(workspace: Path, monkeypatch):
    from app.code_execution import sandbox_backend_config

    monkeypatch.setenv("CODE_SANDBOX_BACKEND", "e2b")
    monkeypatch.delenv("E2B_API_KEY", raising=False)
    monkeypatch.setattr(sandbox_backend_config, "_code_execution_config", lambda name: "")
    result = run(workspace, "print('must not run locally')")
    assert not result.success
    assert result.error_code == "code_backend_error"
    assert "must not run locally" not in result.structured["stdout"]


def test_material_http_route_requires_bridge_token(workspace: Path, monkeypatch):
    import asyncio
    import httpx

    from app.main import app

    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "secret")
    payload = {
        "toolName": "code_execution", "arguments": {"source": "print(7)"},
        "context": {"sessionId": "test", "runId": "run-1", "toolCallId": "call-1"},
        "workspaceRoot": str(workspace),
    }

    async def invoke():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            denied = await client.post("/api/v1/material/execute", json=payload)
            accepted = await client.post("/api/v1/material/execute", json=payload,
                                         headers={"X-Material-Bridge-Token": "secret"})
            return denied, accepted

    denied, accepted = asyncio.run(invoke())
    assert denied.status_code == 403
    assert accepted.status_code == 200
    assert accepted.json()["structured"]["stdout"] == "7\n"


def test_large_stdout_is_bounded_and_internal_result_path_hidden(workspace: Path):
    result = run(workspace, "print('x' * 250000)\nresult = workspace_root")
    assert result.success
    assert result.structured["stdoutTruncated"] is True
    assert len(result.structured["stdout"]) <= 10_020
    assert str(workspace) not in result.llm_observation
    assert str(workspace) not in str(result.structured)


def test_e2b_only_downloads_bounded_outputs(workspace: Path, monkeypatch):
    from app.code_execution import e2b_session_sync as sync

    remote_root = "/home/user/workspace/sessions/test"
    data = {f"{remote_root}/outputs/result.csv": b"value\n5\n"}
    sandbox = SimpleNamespace(files=SimpleNamespace(read=lambda path, **kwargs: data[path]))
    monkeypatch.setattr(sync, "snapshot_remote_files", lambda *args, **kwargs: {"outputs/result.csv": (8, 1)})
    paths = []
    changed = sync.download_changed_files(
        sandbox, remote_root, workspace, {}, paths, allowed_top=("outputs",),
        max_file_bytes=32, max_total_bytes=64, max_files=1,
    )
    assert changed == ["outputs/result.csv"]
    assert paths == [workspace / "outputs" / "result.csv"]
    assert paths[0].read_bytes() == b"value\n5\n"

    monkeypatch.setattr(sync, "snapshot_remote_files", lambda *args, **kwargs: {
        "outputs/next.csv": (5, 2), "scratch/secrets.txt": (5, 2),
    })
    data[f"{remote_root}/outputs/next.csv"] = b"hello"
    assert sync.download_changed_files(sandbox, remote_root, workspace, {},
                                       allowed_top=("outputs",)) == ["outputs/next.csv"]
    assert (workspace / "outputs" / "next.csv").read_bytes() == b"hello"
    assert not (workspace / "scratch").exists()

    monkeypatch.setattr(sync, "snapshot_remote_files", lambda *args, **kwargs: {"outputs/too-big.bin": (100, 1)})
    with pytest.raises(ValueError, match="size limit"):
        sync.download_changed_files(sandbox, remote_root, workspace, {}, allowed_top=("outputs",), max_file_bytes=32)


def test_e2b_executor_syncs_authorized_inputs_and_outputs(workspace: Path, monkeypatch):
    from app.code_execution import e2b_sandbox_backend as e2b
    from app.code_execution.code_interpreter_policy import build_permission_policy

    (workspace / "inputs" / "ready.csv").write_bytes(b"value\n5\n")
    (workspace / "inputs" / "revoked.csv").write_bytes(b"secret")
    (workspace / "outputs" / "previous.csv").write_bytes(b"old")
    policy = build_permission_policy("workspace", str(workspace), str(workspace / "outputs"), [])
    policy = replace(policy, allowed_read_paths=(str(workspace / "inputs" / "ready.csv"),))
    sandbox = SimpleNamespace(files=SimpleNamespace(), run_code=lambda code, timeout: SimpleNamespace(
        logs=SimpleNamespace(stdout=["done"], stderr=[]), error=None, results=[]))
    class Manager:
        def acquire(self, owner, timeout, **kwargs):
            return SimpleNamespace(sandbox=sandbox, sandbox_id="fake", generation=1)
        def release(self, owner):
            pass
        def session_gate(self, owner, session):
            return nullcontext()

    uploads = []
    refreshed_inputs = []
    monkeypatch.setattr(e2b, "ensure_e2b_code_interpreter_ready", lambda sandbox: None)
    monkeypatch.setattr(e2b, "mkdir_remote", lambda sandbox, remote_root: None)
    monkeypatch.setattr(e2b, "push_workspace", lambda *args, **kwargs: uploads.append(kwargs["allowed_files"]))
    monkeypatch.setattr(e2b, "write_e2b_files", lambda files_api, files, **kwargs: refreshed_inputs.extend(files))
    monkeypatch.setattr(e2b, "snapshot_remote_files", lambda *args, **kwargs: {})
    def download(*args, **kwargs):
        assert kwargs["allowed_top"] == ("outputs",)
        result = workspace / "outputs" / "result.csv"
        result.write_bytes(b"value\n5\n")
        kwargs["produced_paths"].append(result)
    monkeypatch.setattr(e2b, "download_changed_files", download)
    monkeypatch.setattr(e2b, "record_uploaded", lambda *args, **kwargs: None)

    executor = e2b.E2BPythonSandboxExecutor(policy, session_id="test", owner_key="call-1", manager=Manager())
    try:
        result = executor.execute("print('done')")
    finally:
        executor.close()
    assert result.stdout == "done"
    assert [item["relative_path"] for item in result.produced_files] == ["outputs/result.csv"]
    assert uploads and all(allowed == {"inputs/ready.csv", "outputs/previous.csv"} for allowed in uploads)
    assert len(refreshed_inputs) == 1
    assert refreshed_inputs[0]["data"] == b"value\n5\n"

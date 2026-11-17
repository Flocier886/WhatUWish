"""3B master CIAgent loop on the station workspace."""

from pathlib import Path
import asyncio
import json
import os
import socket
import threading
import time
import uuid

import pytest
import httpx
import uvicorn
from smolagents import ChatMessage, ChatMessageStreamDelta

from app.code_execution import code_interpreter as interpreter
from app.runtime.models import MaterialExecuteRequest


@pytest.fixture
def workspace(tmp_path: Path, monkeypatch) -> Path:
    monkeypatch.setenv("AI_AGENT_CODE_INTERPRETER_ENABLED", "true")
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "true")
    monkeypatch.setenv("CODE_SANDBOX_BACKEND", "local")
    root = tmp_path / "session-test"
    (root / "inputs").mkdir(parents=True)
    (root / "outputs").mkdir()
    return root


def request(root: Path, task: str = "分析销售数据") -> MaterialExecuteRequest:
    return MaterialExecuteRequest(toolName="code_interpreter", arguments={
        "task": task, "__authorizedInputPaths": ["inputs/sales.csv"]},
        context={"sessionId": "test", "runId": "run-1", "toolCallId": "call-1"},
        workspaceRoot=str(root))


def test_multistep_failure_recovery_and_output(workspace: Path, monkeypatch):
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n3\n", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            self.calls = 0

        def generate_stream(self, messages, **kwargs):
            self.calls += 1
            code = ("print(unknown_name)" if self.calls == 1 else
                    "import pandas as pd\n"
                    "frame = pd.read_csv('inputs/sales.csv')\n"
                    "print(int(frame['amount'].sum()))\n"
                    "frame.to_csv('outputs/结果.csv', index=False)\n"
                    "from pathlib import Path\n"
                    "Path('outputs/代码分析报告.md').write_text('完整分析报告', encoding='utf-8')")
            yield ChatMessageStreamDelta(content=f"Task: 分析\nThought: 执行统计\nCode:\n<code>\n{code}\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": true}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    events = list(interpreter.run_interpreter(request(workspace), interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "success", events[-1]
    assert events[-1]["stepCount"] == 2
    assert "unknown_name" in events[0]["code"]
    assert [item["relativePath"] for item in events[-1]["materialOutputs"]] == [
        "outputs/代码分析报告.md", "outputs/结果.csv"]
    assert "5" in events[-2]["observation"]
    assert (workspace / "inputs" / "sales.csv").read_text(encoding="utf-8") == "amount\n2\n3\n"
    assert (workspace / "outputs" / "代码分析报告.md").read_text(encoding="utf-8") == "完整分析报告"


def test_retrieved_source_is_readable_by_multistep_sandbox(workspace: Path, monkeypatch):
    source = workspace / "inputs" / "artifacts" / "deep-source-deepsearch-source-package.md"
    source.parent.mkdir()
    source.write_text("# 来源目录\n- source fact: 42", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            pass

        def generate_stream(self, messages, **kwargs):
            code = ("from pathlib import Path\n"
                    "text = Path('inputs/artifacts/deep-source-deepsearch-source-package.md').read_text(encoding='utf-8')\n"
                    "print(text)\n"
                    "Path('outputs/3c-report.md').write_text(text, encoding='utf-8')")
            yield ChatMessageStreamDelta(content=f"Task: 分析\nThought: 读取来源\nCode:\n<code>\n{code}\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": true}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    payload = request(workspace)
    payload.arguments["__authorizedInputPaths"] = [source.relative_to(workspace).as_posix()]
    payload.arguments["fileName"] = "3c-report.md"
    events = list(interpreter.run_interpreter(payload, interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "success", events[-1]
    assert "source fact: 42" in events[0]["observation"]
    assert [item["relativePath"] for item in events[-1]["materialOutputs"]] == ["outputs/3c-report.md"]
    assert (workspace / "outputs" / "3c-report.md").read_text(encoding="utf-8") == source.read_text(encoding="utf-8")


def test_output_artifact_id_request_finishes_after_report_is_written(workspace: Path, monkeypatch):
    source = workspace / "inputs" / "artifacts" / "source-web-page.txt"
    source.parent.mkdir()
    source.write_text("Example source", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            self.calls = 0

        def generate_stream(self, messages, **kwargs):
            self.calls += 1
            if self.calls == 1:
                code = ("text = read_text_file(resolve_input_path('inputs/artifacts/source-web-page.txt'))\n"
                        "print(len(text))")
            else:
                code = ("report = f'# 来源分析\\n\\n字符数：{len(text)}'\n"
                        "write_text_file(build_output_path('source-report.md'), report)\n"
                        "print('报告已写入，字符数：', len(text))")
            yield ChatMessageStreamDelta(content=f"Task: 分析\nThought: 执行\nCode:\n<code>\n{code}\n</code>")

        def generate(self, messages, **kwargs):
            scope = str(messages[0].content)
            assert "not an internal completion condition" in scope
            completed = (workspace / "outputs" / "source-report.md").is_file()
            return ChatMessage.from_dict({"role": "assistant",
                                          "content": json.dumps({"is_final": completed})})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    payload = request(workspace, "读取来源并写报告，返回输出文件 Artifact ID 和字符数")
    payload.arguments["__authorizedInputPaths"] = [source.relative_to(workspace).as_posix()]
    payload.arguments["fileName"] = "source-report.md"

    events = list(interpreter.run_interpreter(payload, interpreter.InterpreterCancellation()))

    assert events[-1]["status"] == "success", events[-1]
    assert events[-1]["stepCount"] == 2
    assert [item["relativePath"] for item in events[-1]["materialOutputs"]] == ["outputs/source-report.md"]
    assert "14" in (workspace / "outputs" / "source-report.md").read_text(encoding="utf-8")


@pytest.mark.skipif(os.getenv("RUN_LIVE_CODE_INTERPRETER") != "1", reason="requires configured model")
def test_live_output_id_handoff_after_source_report(workspace: Path, monkeypatch):
    source = workspace / "inputs" / "artifacts" / "source-web-page.txt"
    source.parent.mkdir()
    source.write_text(
        "This domain is for use in documentation examples without needing permission. "
        "This is not a service; avoid relying on it for testing and monitoring purposes.",
        encoding="utf-8",
    )
    payload = request(workspace,
        "只使用 inputs/artifacts/source-web-page.txt 实际读取正文，以 Python len 计算字符数并注明有无尾随换行；"
        "将来源 https://example.com/、原文和统计结果写入 outputs/3c-source-report.md。"
        "返回输出报告的 Artifact ID 和实际计算结果。")
    payload.arguments["__authorizedInputPaths"] = [source.relative_to(workspace).as_posix()]
    payload.arguments["fileName"] = "3c-source-report.md"

    events = list(interpreter.run_interpreter(payload, interpreter.InterpreterCancellation()))

    assert events[-1]["status"] == "success", events[-1]
    assert events[-1]["stepCount"] < interpreter.MAX_STEPS
    assert [item["relativePath"] for item in events[-1]["materialOutputs"]] == ["outputs/3c-source-report.md"]
    report = (workspace / "outputs" / "3c-source-report.md").read_text(encoding="utf-8")
    assert "156" in report
    assert "https://example.com/" in report


def test_missing_model_is_explicit_failure(workspace: Path, monkeypatch):
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: (_ for _ in ()).throw(ValueError("模型未配置")))
    events = list(interpreter.run_interpreter(request(workspace), interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "failed"
    assert "模型未配置" in events[-1]["error"]
    assert events[-1]["materialOutputs"] == []


def test_cancel_after_first_step_does_not_deliver_files(workspace: Path, monkeypatch):
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            pass

        def generate_stream(self, messages, **kwargs):
            yield ChatMessageStreamDelta(content="Task: 检查\nThought: 读取\nCode:\n<code>\nprint(2)\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": false}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    cancellation = interpreter.InterpreterCancellation()
    events = interpreter.run_interpreter(request(workspace), cancellation)
    first = next(events)
    assert first["type"] == "step"
    cancellation.cancel()
    terminal = list(events)[-1]
    assert terminal["errorCode"] == "code_cancelled"
    assert terminal["materialOutputs"] == []
    assert not (workspace / "outputs" / "代码分析报告.md").exists()


def test_max_steps_has_distinct_terminal_without_artifacts(workspace: Path, monkeypatch):
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")
    monkeypatch.setattr(interpreter, "MAX_STEPS", 2)
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            pass

        def generate_stream(self, messages, **kwargs):
            yield ChatMessageStreamDelta(content="Task: 未完成\nThought: 继续\nCode:\n<code>\nprint(2)\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": false}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    events = list(interpreter.run_interpreter(request(workspace), interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "failed"
    assert events[-1]["errorCode"] == "code_max_steps_exceeded"
    assert events[-1]["materialOutputs"] == []


def test_permission_error_can_be_corrected_next_step(workspace: Path, monkeypatch):
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            self.calls = 0

        def generate_stream(self, messages, **kwargs):
            self.calls += 1
            code = ("from pathlib import Path\nprint(Path('../outside.txt').read_text())"
                    if self.calls == 1 else "print(2)")
            yield ChatMessageStreamDelta(content=f"Task: 读取\nThought: 处理文件\nCode:\n<code>\n{code}\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": true}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    events = list(interpreter.run_interpreter(request(workspace), interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "success", events[-1]
    assert events[-1]["stepCount"] == 2
    assert "权限" in events[0]["observation"] or "blocked" in events[0]["observation"]
    assert "2" in events[1]["observation"]


def test_private_sse_requires_bridge_and_preserves_step_order(workspace: Path, monkeypatch):
    from app.main import app
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "test-bridge")
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")

    def fake_run(_request, _cancellation):
        yield {"type": "step", "sequence": 1, "step": 1,
               "thought": "分析", "code": "print(2)", "observation": "2"}
        yield {"type": "result", "sequence": 2, "status": "success", "stepCount": 1,
               "conclusion": "2", "materialOutputs": []}

    monkeypatch.setattr("app.api.routes.run_interpreter", fake_run)

    async def invoke():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            denied = await client.post("/api/v1/material/code-interpreter/stream",
                                       json=request(workspace).model_dump(by_alias=True))
            accepted = await client.post("/api/v1/material/code-interpreter/stream",
                headers={"X-Material-Bridge-Token": "test-bridge"},
                json=request(workspace).model_dump(by_alias=True))
        return denied, accepted

    denied, accepted = asyncio.run(invoke())
    assert denied.status_code == 403
    assert accepted.status_code == 200
    assert "event: step" in accepted.text and "event: result" in accepted.text
    assert '"sequence": 1' in accepted.text and '"sequence": 2' in accepted.text


@pytest.mark.skipif(os.getenv("RUN_LIVE_CODE_INTERPRETER") != "1", reason="requires configured model")
def test_live_csv_excel_multistep(workspace: Path, monkeypatch):
    monkeypatch.setenv("CODE_SANDBOX_BACKEND", os.getenv("RUN_LIVE_CODE_INTERPRETER_BACKEND", "local"))
    from openpyxl import Workbook
    (workspace / "inputs" / "sales.csv").write_text("category,amount\nA,2\nB,3\n", encoding="utf-8")
    book = Workbook()
    book.active.append(["category", "amount"])
    book.active.append(["A", 4])
    book.active.append(["B", 6])
    book.save(workspace / "inputs" / "returns.xlsx")
    task = ("读取 inputs/sales.csv 与 inputs/returns.xlsx，先分别检查数据并计算金额合计；"
            "然后按 category 合并并计算两个金额之和。将结果保存为 outputs/汇总.csv，"
            "再绘制 outputs/汇总.png 柱状图。最后说明总额。请分步执行。")
    payload = MaterialExecuteRequest(toolName="code_interpreter", arguments={
        "task": task, "__authorizedInputPaths": ["inputs/sales.csv", "inputs/returns.xlsx"]},
        context={"sessionId": "test", "runId": "live-run", "toolCallId": "live-call"},
        workspaceRoot=str(workspace))
    events = list(interpreter.run_interpreter(payload, interpreter.InterpreterCancellation()))
    assert events[-1]["status"] == "success", events[-1]
    assert events[-1]["stepCount"] >= 2
    assert (workspace / "outputs" / "汇总.csv").exists()
    assert (workspace / "outputs" / "汇总.png").read_bytes().startswith(b"\x89PNG")


@pytest.mark.skipif(os.getenv("RUN_LIVE_CODE_INTERPRETER_SOCKET") != "1", reason="requires E2B and real socket")
def test_live_e2b_socket_disconnect_cancels_without_delivery(workspace: Path, monkeypatch):
    from app.code_execution.e2b_session_sync import session_remote_root
    from app.code_execution.user_sandbox_manager import get_user_sandbox_manager
    from app.main import app

    monkeypatch.setenv("CODE_SANDBOX_BACKEND", "e2b")
    token = f"test-{uuid.uuid4()}"
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", token)
    monkeypatch.setattr(interpreter, "_model_config", lambda: ("test", "https://unused/v1", "test", 1024))

    class FakeModel:
        model_id = "test"

        def __init__(self, **kwargs):
            pass

        def generate_stream(self, messages, **kwargs):
            yield ChatMessageStreamDelta(content="Task: 等待\nThought: 执行\nCode:\n<code>\n"
                "from pathlib import Path\nPath('outputs/started.txt').write_text('started')\n"
                "while True: pass\n</code>")

        def generate(self, messages, **kwargs):
            return ChatMessage.from_dict({"role": "assistant", "content": '{"is_final": false}'})

    monkeypatch.setattr(interpreter, "OpenAIServerModel", FakeModel)
    (workspace / "inputs" / "sales.csv").write_text("amount\n2\n", encoding="utf-8")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port,
                                           log_level="error", lifespan="off"))
    server_thread = threading.Thread(target=server.run, daemon=True)
    server_thread.start()
    client = None
    manager = get_user_sandbox_manager()
    owner = "code:test"
    try:
        deadline = time.monotonic() + 10
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started
        payload = request(workspace).model_dump(by_alias=True)
        body = json.dumps(payload).encode("utf-8")
        client = socket.create_connection(("127.0.0.1", port), timeout=5)
        client.sendall((f"POST /api/v1/material/code-interpreter/stream HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{port}\r\nX-Material-Bridge-Token: {token}\r\n"
            f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n").encode("ascii") + body)
        remote_marker = f"{session_remote_root('test')}/outputs/started.txt"
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            sandbox = manager._live.get(owner)
            if sandbox is not None:
                try:
                    if sandbox.files.read(remote_marker, format="bytes") == b"started":
                        break
                except Exception:
                    pass
            time.sleep(0.2)
        else:
            pytest.fail("3B E2B execution did not reach marker")
        client.close()
        client = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            row = manager._load(owner)
            if row is not None and row["state"] == "MISSING" and not row["sandbox_id"]:
                break
            time.sleep(0.2)
        else:
            pytest.fail("3B HTTP disconnect did not invalidate E2B sandbox")
        assert not (workspace / "outputs" / "started.txt").exists()
    finally:
        if client is not None:
            client.close()
        server.should_exit = True
        server_thread.join(timeout=10)
        assert not server_thread.is_alive()

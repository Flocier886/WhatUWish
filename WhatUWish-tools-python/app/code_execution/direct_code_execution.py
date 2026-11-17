"""Single-step code_execution adapted from master direct_code_execution.py."""

from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import threading
import time
import uuid
from dataclasses import replace
from pathlib import Path
from typing import Any

from app.code_execution.code_interpreter_policy import (
    CodeExecutionPermissionError,
    build_permission_policy,
    validate_code_against_policy,
)
from app.code_execution.code_interpreter_runtime_guard import extract_runtime_permission_error
from app.code_execution.python_sandbox_executor import (
    PythonSandboxExecutionError,
    PythonSandboxExecutor,
)
from app.code_execution.sandbox_backend_config import code_execution_enabled, get_sandbox_backend
from app.runtime.models import MaterialExecuteRequest, ToolExecuteResponse

_MAX_SOURCE = 100_000
_MAX_INPUTS = 32_000
_MAX_FILE = 32 * 1024 * 1024
_MAX_TOTAL = 64 * 1024 * 1024
_MAX_FILES = 40
_MAX_OBSERVATION = 16_000
_RESERVED_INPUTS = frozenset({
    "workspace_root", "output_dir", "input_file_paths", "input_files",
    "permission_profile", "__builtins__", "__name__", "__file__",
})


class ExecutionCancellation:
    def __init__(self) -> None:
        self._cancelled = threading.Event()
        self._lock = threading.Lock()
        self._executor: PythonSandboxExecutor | None = None

    def attach(self, executor: PythonSandboxExecutor) -> None:
        with self._lock:
            self._executor = executor
            if self._cancelled.is_set():
                try:
                    executor.cancel()
                except Exception:
                    pass

    def cancel(self) -> None:
        with self._lock:
            self._cancelled.set()
            if self._executor is not None:
                try:
                    self._executor.cancel()
                except Exception:
                    pass

    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()

    def detach(self, executor: PythonSandboxExecutor) -> None:
        with self._lock:
            if self._executor is executor:
                self._executor = None


def execute_code(request: MaterialExecuteRequest,
                 cancellation: ExecutionCancellation | None = None) -> ToolExecuteResponse:
    """Prepare workspace, check syntax, execute, then return changed output files."""
    started = time.monotonic()
    try:
        source, inputs, timeout, root, backend, authorized_inputs = _prepare(request)
        try:
            ast.parse(source, filename="<code_execution>")
        except SyntaxError as exc:
            return _result(False, "code_syntax_error", f"语法错误：第 {exc.lineno} 行第 {exc.offset} 列：{exc.msg}",
                           "", "", None, [], started, {"line": exc.lineno, "column": exc.offset})

        outputs = root / "outputs"
        before = _snapshot(outputs)
        policy = build_permission_policy("workspace", str(root), str(outputs), [])
        import matplotlib
        package_assets = str(Path(matplotlib.__file__).resolve().parent)
        policy = replace(policy, allowed_read_paths=tuple(str(root / path) for path in authorized_inputs),
                         allowed_read_roots=(str(outputs), package_assets),
                         allowed_write_roots=(str(outputs),))
        if backend == "local":
            try:
                validate_code_against_policy(source, policy)
            except CodeExecutionPermissionError as exc:
                return _result(False, "code_permission_denied", _public_text(str(exc), root),
                               "", "", None, [], started)

        executor = PythonSandboxExecutor(
            policy, timeout, inputs, backend=backend,
            owner_key=f"code:{request.context.session_id}",
            session_id=request.context.session_id,
        )
        if cancellation is not None:
            cancellation.attach(executor)
        success = False
        error_code = None
        error_message = None
        stdout = ""
        stderr = ""
        result_value = None
        duration_ms = 0
        stdout_truncated = False
        stderr_truncated = False
        try:
            result = executor.execute(source, source_file=str(root / "__code_execution__.py"))
            success = True
            stdout, stderr, result_value = result.stdout, result.stderr, result.result
            duration_ms = result.duration_ms
            stdout_truncated, stderr_truncated = result.stdout_truncated, result.stderr_truncated
        except PythonSandboxExecutionError as exc:
            permission_error = extract_runtime_permission_error(str(exc))
            error_code = "code_permission_denied" if permission_error else "code_runtime_error"
            error_message = str(permission_error) if permission_error else str(exc)
            stdout, stderr, result_value = exc.stdout, exc.stderr, exc.result
            if permission_error:
                stderr = stderr.split("__CI_PERMISSION__", 1)[0]
            duration_ms = exc.duration_ms
            stdout_truncated, stderr_truncated = exc.stdout_truncated, exc.stderr_truncated
        except TimeoutError:
            error_code, error_message = "code_timeout", f"Python 执行超过 {timeout} 秒"
        except Exception as exc:
            error_code, error_message = "code_backend_error", str(exc)
        finally:
            executor.close()
            if cancellation is not None:
                cancellation.detach(executor)

        if cancellation is not None and cancellation.is_cancelled():
            return _result(False, "code_cancelled", "代码执行已取消", "", "", None, [], started)

        changed = _changed_outputs(outputs, before)
        if cancellation is not None and cancellation.is_cancelled():
            return _result(False, "code_cancelled", "代码执行已取消", "", "", None, [], started)
        stdout = _public_text(stdout, root, 10_000)
        stderr = _public_text(stderr, root, 10_000)
        error_message = _public_text(error_message or "", root, 2_000) or None
        details = {
            "durationMs": duration_ms,
            "stdoutTruncated": stdout_truncated,
            "stderrTruncated": stderr_truncated,
            "result": _safe_result(result_value, root),
        }
        return _result(success, error_code, error_message, stdout, stderr,
                       details, changed, started)
    except Exception as exc:
        if cancellation is not None and cancellation.is_cancelled():
            return _result(False, "code_cancelled", "代码执行已取消", "", "", None, [], started)
        # A validation or storage failure must never claim an artifact was delivered.
        return _result(False, "code_request_or_output_invalid", _public_text(str(exc), Path(request.workspace_root)), "", "",
                       None, [], started)


def _prepare(request: MaterialExecuteRequest) -> tuple[str, dict[str, Any], int, Path, str, list[str]]:
    if not code_execution_enabled():
        raise ValueError("代码执行服务未启用")
    if request.tool_name != "code_execution":
        raise ValueError("代码执行工具名无效")
    context = request.context
    if not context.session_id or not context.run_id or not context.tool_call_id:
        raise ValueError("代码执行缺少可信 Run、会话或工具调用身份")
    args = request.arguments
    if set(args) - {"source", "inputs", "timeoutSeconds", "__authorizedInputPaths"}:
        raise ValueError("代码执行包含不受支持的参数")
    source = args.get("source")
    if not isinstance(source, str) or not source.strip() or len(source) > _MAX_SOURCE:
        raise ValueError("Python 源码为空或超过上限")
    inputs = args.get("inputs") or {}
    if not isinstance(inputs, dict) or len(json.dumps(inputs, ensure_ascii=False, default=str)) > _MAX_INPUTS:
        raise ValueError("注入变量超过上限")
    if any(not isinstance(key, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key)
           or key in _RESERVED_INPUTS for key in inputs):
        raise ValueError("注入变量名无效")
    raw_timeout = args.get("timeoutSeconds", 120)
    if isinstance(raw_timeout, bool) or not isinstance(raw_timeout, int) or not 1 <= raw_timeout <= 120:
        raise ValueError("timeoutSeconds 必须在 1 到 120 之间")
    root = Path(request.workspace_root).resolve()
    if root.name != f"session-{context.session_id}" or not root.is_dir():
        raise ValueError("会话工作区无效")
    for name in ("inputs", "outputs"):
        child = root / name
        if child.is_symlink() or not child.is_dir():
            raise ValueError("会话工作区目录无效")
    authorized_inputs = args.get("__authorizedInputPaths", [])
    if not isinstance(authorized_inputs, list) or len(authorized_inputs) > 100:
        raise ValueError("授权输入清单无效")
    input_bytes = 0
    for relative in authorized_inputs:
        if not isinstance(relative, str) or not relative.startswith("inputs/") or "\\" in relative:
            raise ValueError("授权输入路径无效")
        path = (root / relative).resolve()
        if not path.is_relative_to(root / "inputs") or not path.is_file() or path.is_symlink():
            raise ValueError("授权输入文件无效")
        size = path.stat().st_size
        input_bytes += size
        if size > _MAX_FILE or input_bytes > _MAX_TOTAL:
            raise ValueError("授权输入文件超过大小上限")
    backend = get_sandbox_backend()
    if backend == "local" and os.getenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "").lower() not in {"1", "true", "yes"}:
        raise ValueError("本地代码执行仅允许显式开发配置")
    return source, inputs, raw_timeout, root, backend, authorized_inputs


def _snapshot(outputs: Path) -> dict[str, tuple[int, str]]:
    files: dict[str, tuple[int, str]] = {}
    for path in outputs.rglob("*"):
        if path.is_symlink():
            raise ValueError("输出目录包含符号链接")
        if not path.is_file():
            continue
        relative = path.relative_to(outputs).as_posix()
        if any(part.startswith(".") for part in Path(relative).parts):
            continue
        size = path.stat().st_size
        if size > _MAX_FILE:
            raise ValueError("输出文件超过 32 MiB")
        files[relative] = (size, _hash(path))
    return files


def _changed_outputs(outputs: Path, before: dict[str, tuple[int, str]]) -> list[dict[str, str]]:
    after = _snapshot(outputs)
    changed = [(relative, info) for relative, info in after.items() if before.get(relative) != info]
    if len(changed) > _MAX_FILES or sum(info[0] for _, info in changed) > _MAX_TOTAL:
        raise ValueError("本次生成文件超过数量或总容量上限")
    return [{"artifactId": str(uuid.uuid4()), "relativePath": f"outputs/{relative}",
             "fileName": Path(relative).name, "sha256": info[1]}
            for relative, info in sorted(changed)]


def _hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _public_text(value: str, root: Path, limit: int = _MAX_OBSERVATION) -> str:
    text = str(value or "").replace(str(root) + os.sep, "")
    text = text.replace(str(root), ".")
    text = re.sub(r"[A-Za-z]:[/\\][^\s\"']+", "[内部路径]", text)
    text = re.sub(r"(?<![:/])/(?:[^\s\"'<>/]+/)*[^\s\"'<>/]+", "[内部路径]", text)
    return text[:limit] + ("...[truncated]" if len(text) > limit else "")


def _safe_result(value: Any, root: Path) -> Any:
    if value is None:
        return None
    serialized = json.dumps(value, ensure_ascii=False, default=str)
    public = _public_text(serialized, root, 4_000)
    if len(serialized) > 4_000:
        return public
    return json.loads(public)


def _result(success: bool, error_code: str | None, error_message: str | None,
            stdout: str, stderr: str, details: dict[str, Any] | None,
            outputs: list[dict[str, str]], started: float,
            diagnostics: dict[str, Any] | None = None) -> ToolExecuteResponse:
    state = "成功" if success else f"失败（{error_code or 'unknown'}）"
    observation = f"Python 执行{state}。"
    if error_message:
        observation += f"\n原因：{error_message[:2_000]}"
    if stdout:
        observation += f"\nstdout:\n{stdout}"
    if stderr:
        observation += f"\nstderr:\n{stderr}"
    if details and details.get("result") is not None:
        observation += f"\nresult: {details['result']}"
    if outputs:
        observation += "\n本次生成文件：" + ", ".join(item["relativePath"] for item in outputs)
        observation += "。Artifact ID 以登记成功后的工具结果为准。"
    observation = observation[:_MAX_OBSERVATION]
    structured = {"executionStatus": "ok" if success else "error", "stdout": stdout,
                  "stderr": stderr, "errorCode": error_code, "error": error_message,
                  "durationMs": int((time.monotonic() - started) * 1000)}
    if details:
        structured.update(details)
    if diagnostics:
        structured["syntaxDiagnostics"] = diagnostics
    return ToolExecuteResponse(
        success=success, status="success" if success else "failed", output=observation,
        llmObservation=observation, structured=structured,
        ledgerFacts=[{"factType": "code_execution", "status": structured["executionStatus"],
                      "errorCode": error_code or "", "generatedFileCount": len(outputs)}],
        materialOutputs=outputs, errorCode=error_code, errorMessage=error_message,
    )

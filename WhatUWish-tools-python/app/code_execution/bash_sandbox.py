"""Agent bash: run shell in the same E2B session as code_execution and desktop control."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from app.code_execution.direct_code_execution import _changed_outputs, _snapshot
from app.code_execution.e2b_session_sync import (
    download_changed_files,
    push_workspace,
    record_uploaded,
    session_remote_root,
    snapshot_remote_files,
)
from app.code_execution.sandbox_backend_config import code_execution_enabled, get_sandbox_backend
from app.code_execution.bash_skill_sync import (
    changed_local_skills,
    library_skill_paths,
    local_skill_view,
    push_e2b_skills,
    registered_skill_paths,
    skill_library_root,
    sync_e2b_skills,
)
from app.code_execution.user_sandbox_manager import get_user_sandbox_manager
from app.runtime.models import MaterialExecuteRequest, ToolExecuteResponse

MAX_OUTPUT_CHARS = 64_000
MAX_OBSERVATION_CHARS = 12_000


def execute_bash(request: MaterialExecuteRequest) -> ToolExecuteResponse:
    started = time.monotonic()
    try:
        command, timeout, root, authorized, backend, skill_paths, library_root = _prepare(request)
        outputs = root / "outputs"
        if backend == "e2b":
            owner = f"code:{request.context.session_id}"
            session_id = request.context.session_id
            manager = get_user_sandbox_manager()
            lease = manager.acquire(owner, timeout_sec=timeout + 60)
            try:
                with manager.session_gate(owner, session_id):
                    before_local = _snapshot(outputs)
                    allowed = {path.relative_to(root).as_posix() for path in outputs.rglob("*") if path.is_file()}
                    allowed.update(authorized)
                    push_workspace(lease.sandbox, root, session_id,
                                   owner_key=owner, sandbox_id=lease.sandbox_id,
                                   generation=lease.generation, allowed_files=allowed)
                    remote_root = session_remote_root(session_id)
                    materialized = push_e2b_skills(
                        lease.sandbox, root, remote_root, skill_paths, command,
                        owner_key=owner, sandbox_id=lease.sandbox_id, generation=lease.generation)
                    before_remote = snapshot_remote_files(lease.sandbox, remote_root, skip_top=("inputs", "skills"))
                    exit_code, stdout, stderr, timed_out = _run_command(
                        lease.sandbox, command, remote_root, timeout)
                    downloaded: list[Path] = []
                    download_changed_files(
                        lease.sandbox, remote_root, root, before_remote,
                        produced_paths=downloaded, skip_top=("inputs", "skills"),
                        allowed_top=("outputs",), max_file_bytes=32 * 1024 * 1024,
                        max_total_bytes=64 * 1024 * 1024, max_files=40,
                    )
                    for path in downloaded:
                        record_uploaded(root, path.relative_to(root).as_posix(), path,
                                        owner_key=owner, sandbox_id=lease.sandbox_id,
                                        generation=lease.generation)
                    synced = sync_e2b_skills(
                        lease.sandbox, root, remote_root, skill_paths, library_root,
                        owner_key=owner, sandbox_id=lease.sandbox_id, generation=lease.generation)
                    produced = _changed_outputs(outputs, before_local)
            finally:
                manager.release(owner)
        else:
            before_local = _snapshot(outputs)
            with local_skill_view(root, skill_paths) as (materialized, before_skills):
                exit_code, stdout, stderr, timed_out = _run_local_command(command, root, timeout)
                synced = changed_local_skills(skill_paths, before_skills)
            produced = _changed_outputs(outputs, before_local)

        stdout, stdout_truncated = _truncate(stdout)
        stderr, stderr_truncated = _truncate(stderr)
        observation = f"bash 退出码：{exit_code if exit_code is not None else '无'}。"
        if timed_out:
            observation += f"\n命令在 {timeout} 秒后超时。"
        if stdout:
            observation += f"\nstdout:\n{stdout}"
        if stderr:
            observation += f"\nstderr:\n{stderr}"
        if produced:
            observation += "\n本次生成文件：" + ", ".join(item["relativePath"] for item in produced)
            observation += "。Artifact ID 以登记成功后的工具结果为准。"
        if synced:
            observation += "\n已回写 skill：" + ", ".join(synced)
        observation = observation[:MAX_OBSERVATION_CHARS]
        return ToolExecuteResponse(
            success=True, status="success" if exit_code == 0 else "failed",
            output=observation, llmObservation=observation,
            structured={"exitCode": exit_code, "stdout": stdout, "stderr": stderr,
                        "timedOut": timed_out, "truncated": stdout_truncated or stderr_truncated,
                        "durationMs": int((time.monotonic() - started) * 1000), "cwd": ".",
                        "skillsMaterialized": materialized, "skillsSyncedBack": synced},
            ledgerFacts=[{"factType": "bash", "exitCode": exit_code,
                          "timedOut": timed_out, "generatedFileCount": len(produced)}],
            materialOutputs=produced,
        )
    except Exception as exc:
        message = str(exc)[:1_000] or "bash 沙箱执行失败"
        return ToolExecuteResponse(success=False, status="failed", output=message,
                                   llmObservation=message, errorCode="bash_execution_failed",
                                   errorMessage=message)


def _prepare(request: MaterialExecuteRequest) -> tuple[str, int, Path, list[str], str, dict[str, Path], Path | None]:
    if not code_execution_enabled():
        raise ValueError("代码执行服务未启用")
    backend = get_sandbox_backend()
    if backend == "local" and os.getenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "").lower() not in {"1", "true", "yes"}:
        raise ValueError("本地代码执行仅允许显式开发配置")
    if request.tool_name != "bash":
        raise ValueError("bash 工具名无效")
    context = request.context
    if not context.session_id or not context.run_id or not context.tool_call_id:
        raise ValueError("bash 缺少可信 Run、会话或工具调用身份")
    args = request.arguments
    if set(args) - {"command", "timeout_sec", "__authorizedInputPaths", "__skillPaths", "__skillLibraryRoot"}:
        raise ValueError("bash 包含不受支持的参数")
    command = args.get("command")
    if not isinstance(command, str) or not command.strip() or len(command) > 32_000:
        raise ValueError("shell 命令为空或超过上限")
    timeout = args.get("timeout_sec", 120)
    if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 600:
        raise ValueError("timeout_sec 必须在 1 到 600 之间")
    root = Path(request.workspace_root).resolve()
    if root.name != f"session-{context.session_id}" or not root.is_dir():
        raise ValueError("会话工作区无效")
    for name in ("inputs", "outputs"):
        child = root / name
        if child.is_symlink() or not child.is_dir():
            raise ValueError("会话工作区目录无效")
    authorized = args.get("__authorizedInputPaths", [])
    if not isinstance(authorized, list) or len(authorized) > 100:
        raise ValueError("授权输入清单无效")
    total = 0
    for relative in authorized:
        if not isinstance(relative, str) or not relative.startswith("inputs/") or "\\" in relative:
            raise ValueError("授权输入路径无效")
        path = (root / relative).resolve()
        if not path.is_relative_to(root / "inputs") or not path.is_file() or path.is_symlink():
            raise ValueError("授权输入文件无效")
        size = path.stat().st_size
        total += size
        if size > 32 * 1024 * 1024 or total > 64 * 1024 * 1024:
            raise ValueError("授权输入文件超过大小上限")
    skills = registered_skill_paths(args.get("__skillPaths"))
    library_root = skill_library_root(args.get("__skillLibraryRoot"))
    skills = library_skill_paths(library_root, skills)
    return command, timeout, root, authorized, backend, skills, library_root


def _run_local_command(command: str, cwd: Path, timeout: int) -> tuple[int | None, str, str, bool]:
    """Master's local shell lifecycle: cwd, enriched Python PATH, timeout and process kill."""
    if os.name == "nt":
        argv = [os.environ.get("ComSpec") or "cmd.exe", "/c", command]
    else:
        shell = shutil.which("bash") or shutil.which("sh") or "/bin/sh"
        argv = [shell, "-lc", command]
    env = os.environ.copy()
    env["SKILL_WORKSPACE"] = str(cwd)
    env["PYTHONIOENCODING"] = "utf-8"
    env.setdefault("LANG", "C.UTF-8")
    python_dir = str(Path(sys.executable).resolve().parent)
    env["PATH"] = python_dir + os.pathsep + (env.get("PATH") or env.get("Path") or "")
    env["SKILL_PYTHON"] = sys.executable
    env["PYTHON"] = sys.executable
    process = subprocess.Popen(argv, cwd=str(cwd), env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        return None, "", f"execution timed out after {timeout}s", True
    return process.returncode, stdout.decode("utf-8", errors="replace"), \
        stderr.decode("utf-8", errors="replace"), False


def _run_command(sandbox: Any, command: str, cwd: str, timeout: int) -> tuple[int | None, str, str, bool]:
    # master bash_sandbox uses DISPLAY=:0 so shell-launched browser windows appear on the shared desktop.
    try:
        result = sandbox.commands.run(command, envs={"DISPLAY": ":0"}, cwd=cwd, timeout=timeout)
    except Exception as exc:
        if "timeout" in str(exc).lower() or "timed out" in str(exc).lower():
            return None, "", f"execution timed out after {timeout}s", True
        return int(getattr(exc, "exit_code", 1) or 1), str(getattr(exc, "stdout", "") or ""), \
            str(getattr(exc, "stderr", "") or str(exc)), False
    return int(getattr(result, "exit_code", 0) or 0), \
        str(getattr(result, "stdout", "") or ""), str(getattr(result, "stderr", "") or ""), False


def _truncate(value: str) -> tuple[str, bool]:
    if len(value) <= MAX_OUTPUT_CHARS:
        return value, False
    head = MAX_OUTPUT_CHARS // 2
    return value[:head] + "\n...[truncated]...\n" + value[-head:], True

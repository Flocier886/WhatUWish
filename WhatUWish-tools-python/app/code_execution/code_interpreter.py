"""Master CIAgent adapted to the station session workspace and material bridge."""

from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from typing import Any, Iterator

import pandas as pd
import yaml
from jinja2 import Template
from smolagents import ActionStep, ChatMessageStreamDelta, FinalAnswerStep, OpenAIServerModel, PythonInterpreterTool

from app.code_execution.ci_agent import CIAgent, CodeOuput, _extract_thought_text
from app.code_execution.code_interpreter_policy import build_permission_policy, validate_code_against_policy
from app.code_execution.direct_code_execution import _changed_outputs, _public_text, _snapshot
from app.code_execution.sandbox_backend_config import get_sandbox_backend
from app.runtime.configuration import config_section, load_application_configuration
from app.runtime.models import MaterialExecuteRequest

MAX_STEPS = 10
MAX_TASK_CHARS = 12_000
MAX_SECONDS = 600
MAX_STEP_SECONDS = 120
MAX_STEP_TEXT = 4_000


class InterpreterCancellation:
    def __init__(self) -> None:
        self._cancelled = threading.Event()
        self._executor = None
        self._lock = threading.Lock()

    def attach(self, executor) -> None:
        with self._lock:
            self._executor = executor
            if self._cancelled.is_set():
                executor.cancel()

    def cancel(self) -> None:
        self._cancelled.set()
        with self._lock:
            executor = self._executor
        if executor is not None:
            executor.cancel()

    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()


def _model_config() -> tuple[str, str, str, int]:
    config = load_application_configuration()
    own = config_section(config, "code-interpreter-runtime")
    deep = config_section(config, "deepsearch-runtime")
    common = config_section(config, "research-runtime")
    def selected(key: str) -> str:
        return str(own.get(key) or deep.get(key) or common.get(key) or "").strip()
    model, base, key = (selected(name) for name in ("model", "api-base", "api-key"))
    if not model or not base or not key:
        raise ValueError("代码分析模型未配置：需要 model、api-base 和 api-key")
    output_tokens = int(own.get("max-output-tokens") or 4096)
    if not 256 <= output_tokens <= 8192:
        raise ValueError("代码分析模型输出预算无效")
    base = base.rstrip("/")
    for suffix in ("/v1/chat/completions", "/chat/completions", "/v1/responses", "/responses"):
        if base.lower().endswith(suffix):
            base = base[:-len(suffix)].rstrip("/")
            break
    if not base.lower().endswith("/v1") and "bigmodel.cn" not in base.lower():
        base += "/v1"
    return model, base, key, output_tokens


def _prepare(request: MaterialExecuteRequest) -> tuple[Path, str, str, list[Path]]:
    if os.getenv("AI_AGENT_CODE_INTERPRETER_ENABLED", "").lower() not in {"1", "true", "yes"}:
        raise ValueError("多步代码分析服务未启用")
    if request.tool_name != "code_interpreter":
        raise ValueError("多步代码分析工具名无效")
    context = request.context
    if not context.session_id or not context.run_id or not context.tool_call_id:
        raise ValueError("多步代码分析缺少可信调用身份")
    arguments = request.arguments
    if set(arguments) - {"task", "fileName", "__authorizedInputPaths"}:
        raise ValueError("多步代码分析包含不受支持的参数")
    task = arguments.get("task")
    if not isinstance(task, str) or not task.strip() or len(task) > MAX_TASK_CHARS:
        raise ValueError("分析任务为空或超过上限")
    file_name = arguments.get("fileName") or "代码分析报告.md"
    if not isinstance(file_name, str) or not file_name.endswith(".md") or len(file_name) > 120 \
            or file_name in {".", ".."} or "/" in file_name or "\\" in file_name:
        raise ValueError("报告文件名无效")
    root = Path(request.workspace_root).resolve()
    if root.name != f"session-{context.session_id}" or not root.is_dir():
        raise ValueError("会话工作区无效")
    for child in (root / "inputs", root / "outputs"):
        if child.is_symlink() or not child.is_dir():
            raise ValueError("会话工作区目录无效")
    relatives = arguments.get("__authorizedInputPaths", [])
    if not isinstance(relatives, list) or len(relatives) > 100:
        raise ValueError("授权输入清单无效")
    files = []
    total = 0
    for relative in relatives:
        if not isinstance(relative, str) or not relative.startswith("inputs/") or "\\" in relative:
            raise ValueError("授权输入路径无效")
        path = (root / relative).resolve()
        if not path.is_relative_to(root / "inputs") or not path.is_file() or path.is_symlink():
            raise ValueError("授权输入文件无效")
        total += path.stat().st_size
        if path.stat().st_size > 32 * 1024 * 1024 or total > 64 * 1024 * 1024:
            raise ValueError("授权输入文件超过大小上限")
        files.append(path)
    return root, task, file_name, files


def _abstract(path: Path) -> str:
    try:
        if path.suffix.lower() == ".csv":
            return pd.read_csv(path, nrows=10).to_string(index=False)[:2000]
        if path.suffix.lower() in {".xlsx", ".xls"}:
            return pd.read_excel(path, nrows=10).to_string(index=False)[:2000]
        if path.suffix.lower() in {".txt", ".md", ".html"}:
            return path.read_text(encoding="utf-8", errors="replace")[:2000]
    except Exception:
        return "无法生成预览，请在沙箱中读取文件。"
    return ""


def run_interpreter(request: MaterialExecuteRequest, cancellation: InterpreterCancellation) -> Iterator[dict[str, Any]]:
    """Yield bounded step events followed by exactly one terminal result."""
    started = time.monotonic()
    sequence = 0
    steps = 0
    agent = None
    deadline_timer = threading.Timer(MAX_SECONDS, cancellation.cancel)
    deadline_timer.daemon = True
    deadline_timer.start()
    root = Path(request.workspace_root)
    try:
        root, task, report_name, files = _prepare(request)
        model_id, api_base, api_key, output_tokens = _model_config()
        backend = get_sandbox_backend()
        if backend == "local" and os.getenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "").lower() not in {"1", "true", "yes"}:
            raise ValueError("本地代码执行仅允许显式开发配置")
        outputs = root / "outputs"
        before = _snapshot(outputs)
        policy = build_permission_policy("analysis", str(root), str(outputs),
            [{"name": path.relative_to(root).as_posix(), "path": str(path)} for path in files])
        prompt = yaml.safe_load(Path(__file__).with_name("code_interpreter.yaml").read_text(encoding="utf-8"))
        rendered_task = Template(prompt["task_template"]).render(
            files=[{"path": path.relative_to(root).as_posix(), "abstract": _abstract(path)} for path in files],
            task=task, output_dir="outputs/",
            **policy.to_prompt_context())
        model = OpenAIServerModel(model_id=model_id, api_base=api_base, api_key=api_key,
                                  max_tokens=output_tokens, client_kwargs={"timeout": MAX_STEP_SECONDS})
        agent = CIAgent(tools=[PythonInterpreterTool()], model=model, prompt_templates=prompt,
            additional_authorized_imports=list(policy.authorized_imports),
            output_dir=str(outputs), return_full_result=False,
            before_execute=lambda code: validate_code_against_policy(code, policy) if backend == "local" else None,
            runtime_permission_policy=policy, sandbox_timeout_seconds=MAX_STEP_SECONDS,
            sandbox_backend=backend, sandbox_owner_key=f"code:{request.context.session_id}",
            sandbox_session_id=request.context.session_id,
            on_sandbox_ready=cancellation.attach, is_cancelled=cancellation.is_cancelled)
        final = None
        for item in agent.run(task=rendered_task, stream=True, max_steps=MAX_STEPS):
            if cancellation.is_cancelled():
                raise InterruptedError("代码分析已取消")
            if time.monotonic() - started > MAX_SECONDS:
                raise TimeoutError("多步代码分析超过总时限")
            if isinstance(item, CodeOuput):
                steps += 1
                sequence += 1
                yield {"type": "step", "sequence": sequence, "step": steps,
                       "thought": _public_text(item.thought, root, MAX_STEP_TEXT),
                       "code": _public_text(item.code, root, 12_000),
                       "observation": _public_text(item.execution_logs, root, MAX_STEP_TEXT)}
            elif isinstance(item, ActionStep) and item.error is not None:
                steps = max(steps, item.step_number or 0)
                sequence += 1
                failed_code = item.tool_calls[0].arguments if item.tool_calls else ""
                yield {"type": "step", "sequence": sequence, "step": steps,
                       "thought": _public_text(_extract_thought_text(item.model_output or ""), root, MAX_STEP_TEXT),
                       "code": _public_text(str(failed_code), root, 12_000),
                       "observation": _public_text(str(item.error), root, MAX_STEP_TEXT)}
            elif isinstance(item, FinalAnswerStep):
                final = str(item.output or "").strip()
            elif isinstance(item, ChatMessageStreamDelta):
                continue
        if cancellation.is_cancelled():
            raise InterruptedError("代码分析已取消")
        if agent.memory.steps and getattr(agent.memory.steps[-1], "error", None) is not None:
            if type(agent.memory.steps[-1].error).__name__ == "AgentMaxStepsError":
                raise RuntimeError("code_max_steps_exceeded: 多步代码分析已耗尽步骤预算")
        if not final:
            raise RuntimeError("多步代码分析没有生成结论")
        report = outputs / report_name
        # 模型已在本轮写出正式报告时保留原文；否则用 master 终答生成报告。
        if not report.is_file() or before.get(report_name) == _snapshot(outputs).get(report_name):
            report.write_text(final, encoding="utf-8")
        changed = _changed_outputs(outputs, before)
        if cancellation.is_cancelled():
            raise InterruptedError("代码分析已取消")
        sequence += 1
        yield {"type": "result", "sequence": sequence, "status": "success", "stepCount": steps,
               "conclusion": _public_text(final, root, 12_000), "materialOutputs": changed,
               "durationMs": int((time.monotonic() - started) * 1000)}
    except Exception as exc:
        code = "code_max_steps_exceeded" if "code_max_steps_exceeded" in str(exc) else (
            "code_timeout" if isinstance(exc, TimeoutError) or time.monotonic() - started >= MAX_SECONDS else (
            "code_cancelled" if cancellation.is_cancelled() or isinstance(exc, InterruptedError)
            else "code_interpreter_failed"))
        sequence += 1
        yield {"type": "result", "sequence": sequence, "status": "failed", "stepCount": steps,
               "errorCode": code, "error": _public_text(str(exc), root, 2000),
               "materialOutputs": [], "durationMs": int((time.monotonic() - started) * 1000)}
    finally:
        deadline_timer.cancel()
        if agent is not None:
            try:
                agent.close_sandbox()
            except Exception:
                pass

import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from app.runtime.artifact_storage import persist_file
from app.runtime.context import clip_text
from app.runtime.models import ToolArtifact, ToolContext, ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.observation import script_observation


SUPPORTED_RUNTIMES = {"python", "node", "shell", "powershell", "bat"}


@dataclass
class PreparedSkillWorkspace:
    """脚本执行前准备好的临时 skill 工作区。"""

    temp_dir: Path
    skill_root: Path
    script_path: Path
    arguments_file: Path
    output_dir: Path
    baseline_files: dict[str, tuple[int, int]]


class SkillScriptRunnerTool(BasePythonTool):
    """兼容旧 skill_script_runner action；主 Agent 已改用 bash 沙箱。

    这个工具只负责受控执行：脚本路径必须从 Java 侧已注册 action 传入，
    并且最终解析后必须停留在 skill 包根目录内。真实执行发生在临时
    skill 副本里，避免社区脚本污染原始 skill 包。
    """

    name = "skill_script_runner"
    description = "Execute a declared skill script action with path, runtime and timeout boundaries."

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        """执行脚本 action，并把所有成功/失败结果归一化为 ToolExecuteResponse。"""
        args = request.arguments or {}
        request_id = str(args.get("requestId") or request.context.request_id or "")
        skill_execution_id = str(args.get("skillExecutionId") or "")
        skill_name = str(args.get("skillName") or "")
        skill_version = str(args.get("skillVersion") or "")
        action_name = str(args.get("actionName") or args.get("scriptName") or "")
        runtime = str(args.get("runtime") or "python").strip().lower()
        artifact_policy = str(args.get("artifactPolicy") or "none")

        if runtime not in SUPPORTED_RUNTIMES:
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                "blocked",
                None,
                "",
                "",
                "Unsupported script runtime.",
                {},
                [],
                "Use one of the supported runtimes: python, node, shell, powershell or bat.",
                "script_runtime_unsupported",
                f"Unsupported runtime: {runtime}",
            )

        try:
            # scriptPath 必须在 skillBasePath 内解析，不能让用户输入任意本机路径。
            skill_base_path = Path(str(args["skillBasePath"])).resolve()
            script_relative_path = str(args["scriptPath"])
            self._resolve_script_path(skill_base_path, script_relative_path)
        except KeyError as exc:
            return self._invalid_argument(
                request_id, skill_execution_id, skill_name, skill_version, action_name, runtime, f"{exc.args[0]} is required"
            )
        except ValueError as exc:
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                "blocked",
                None,
                "",
                "",
                "Script path is outside skill package.",
                {},
                [],
                "Use the registered script path from skill metadata.",
                "script_path_not_allowed",
                str(exc),
            )

        timeout_seconds = self._bounded_timeout(args.get("timeoutSeconds"))
        argv = [str(item) for item in (args.get("argv") or [])]
        payload = {
            # 传给脚本的 stdin payload 是稳定协议，脚本不得依赖 runner 内部变量。
            "requestId": request_id,
            "skillExecutionId": skill_execution_id,
            "skillName": skill_name,
            "skillVersion": skill_version,
            "actionName": action_name,
            "scriptName": action_name,
            "arguments": args.get("arguments") or {},
            "artifactPolicy": artifact_policy,
        }
        arguments_json = json.dumps(payload["arguments"], ensure_ascii=False)
        prepared_workspace: PreparedSkillWorkspace | None = None
        completed: subprocess.CompletedProcess[str] | None = None
        try:
            prepared_workspace = self._prepare_workspace(skill_base_path, script_relative_path, arguments_json)
            # 环境变量是给社区脚本的稳定协议；stdin 继续保留，兼容已有 fixture。
            env = os.environ.copy()
            env.update(
                {
                    "SKILL_ARGUMENTS_JSON": arguments_json,
                    "SKILL_ARGUMENTS_FILE": str(prepared_workspace.arguments_file),
                    "SKILL_WORKSPACE": str(prepared_workspace.skill_root),
                    "SKILL_OUTPUT_DIR": str(prepared_workspace.output_dir),
                }
            )
            # 根据注册 runtime 选择解释器；cwd 固定在临时 skill 副本，避免污染原始 skill 包。
            command = self._build_command(runtime, prepared_workspace.script_path, argv)
            completed = subprocess.run(
                command,
                input=json.dumps(payload, ensure_ascii=False),
                text=True,
                encoding="utf-8",
                errors="replace",
                capture_output=True,
                cwd=str(prepared_workspace.skill_root),
                timeout=timeout_seconds,
                env=env,
            )
        except subprocess.TimeoutExpired as exc:
            artifacts = self._stage_generated_artifacts(prepared_workspace, request_id, skill_execution_id, action_name, request.context)
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                "timeout",
                None,
                clip_text(exc.stdout or ""),
                clip_text(exc.stderr or ""),
                "Script action timed out.",
                {},
                [],
                "Retry with a smaller input or increase the declared timeout within policy.",
                "script_timeout",
                f"Script timed out after {timeout_seconds}s",
                artifacts,
            )
        except ModuleNotFoundError as exc:
            return self._dependency_missing(request_id, skill_execution_id, skill_name, skill_version, action_name, runtime, str(exc))
        except FileNotFoundError as exc:
            return self._runtime_unavailable(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                str(exc),
            )
        except Exception as exc:
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                "failed",
                None,
                "",
                "",
                "Script action failed before execution.",
                {},
                [],
                "Check script path, permissions and runtime dependencies.",
                "script_execution_error",
                str(exc),
            )
        finally:
            if completed is None:
                self._cleanup_workspace(prepared_workspace)

        if completed is None:
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                "failed",
                None,
                "",
                "",
                "Script action failed before execution.",
                {},
                [],
                "Check script path, permissions and runtime dependencies.",
                "script_execution_error",
                "Script process did not produce a completion result.",
            )

        stdout = clip_text(completed.stdout or "")
        stderr = clip_text(completed.stderr or "")
        parsed = self._parse_structured_stdout(completed.stdout)
        try:
            artifacts = self._stage_generated_artifacts(prepared_workspace, request_id, skill_execution_id, action_name, request.context)
        finally:
            self._cleanup_workspace(prepared_workspace)
        if completed.returncode != 0:
            # 脚本可以用 JSON stdout 明确声明 blocked/failed 等状态，runner 优先保留脚本语义。
            status = parsed.get("status") or self._failure_status_from_stderr(stderr)
            error_code = parsed.get("errorCode") or self._error_code_from_status(status)
            return self._result(
                request_id,
                skill_execution_id,
                skill_name,
                skill_version,
                action_name,
                runtime,
                False,
                status,
                completed.returncode,
                stdout,
                stderr,
                parsed.get("summary") or "Script action failed.",
                parsed,
                self._artifact_refs(parsed),
                parsed.get("recoveryHint") or self._recovery_hint_from_status(status),
                error_code,
                parsed.get("errorMessage") or stderr or "Script returned non-zero exit code.",
                artifacts,
            )

        return self._result(
            request_id,
            skill_execution_id,
            skill_name,
            skill_version,
            action_name,
            runtime,
            True,
            parsed.get("status") or "success",
            completed.returncode,
            stdout,
            stderr,
            parsed.get("summary") or "Script action completed.",
            parsed,
            self._artifact_refs(parsed),
            parsed.get("recoveryHint"),
            parsed.get("errorCode"),
            parsed.get("errorMessage"),
            artifacts,
        )

    def _resolve_script_path(self, skill_base_path: Path, script_path: str) -> Path:
        """解析并校验脚本路径，防止越过 skill 包根目录。"""
        resolved = (skill_base_path / script_path).resolve()
        if resolved != skill_base_path and skill_base_path not in resolved.parents:
            raise ValueError(f"script path escapes skill root: {script_path}")
        if not resolved.exists() or not resolved.is_file():
            raise ValueError(f"script file not found: {script_path}")
        return resolved

    def _build_command(self, runtime: str, script_path: Path, argv: list[str]) -> list[str]:
        """按 master-style runtime 约定构造脚本命令，不经过 shell 字符串拼接。"""
        normalized_runtime = (runtime or "").strip().lower()
        if normalized_runtime not in SUPPORTED_RUNTIMES:
            raise ValueError(f"Unsupported runtime: {runtime}")

        if normalized_runtime == "python":
            executable = self._resolve_executable(
                "SKILL_PYTHON_BIN",
                [sys.executable, "python", "python3"],
            )
            return [executable, str(script_path), *argv]
        if normalized_runtime == "node":
            executable = self._resolve_executable("SKILL_NODE_BIN", ["node"])
            return [executable, str(script_path), *argv]
        if normalized_runtime == "shell":
            executable = self._resolve_executable("SKILL_SHELL_BIN", self._shell_candidates())
            return [executable, str(script_path), *argv]
        if normalized_runtime == "powershell":
            executable = self._resolve_executable("SKILL_POWERSHELL_BIN", ["pwsh", "powershell"])
            return [
                executable,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(script_path),
                *argv,
            ]

        executable = self._resolve_executable("SKILL_BAT_BIN", [os.environ.get("ComSpec"), "cmd"])
        return [executable, "/c", str(script_path), *argv]

    def _resolve_executable(self, env_key: str, candidates: list[str | None]) -> str:
        """优先使用显式运行时配置，否则从当前进程 PATH 中解析可执行文件。"""
        override = os.getenv(env_key)
        if override:
            resolved_override = shutil.which(override)
            if resolved_override:
                return resolved_override
            override_path = Path(override).expanduser()
            if override_path.is_file():
                return str(override_path.resolve())
            raise FileNotFoundError(f"Runtime executable configured by {env_key} was not found: {override}")

        for candidate in candidates:
            if not candidate:
                continue
            resolved = shutil.which(candidate)
            if resolved:
                return resolved
            candidate_path = Path(candidate).expanduser()
            if candidate_path.is_file():
                return str(candidate_path.resolve())
        raise FileNotFoundError(f"Runtime executable not found for {env_key}")

    def _shell_candidates(self) -> list[str | None]:
        """Windows 优先使用 Git Bash，避免命中未安装发行版的 WSL bash 占位程序。"""
        candidates: list[str | None] = []
        if os.name == "nt":
            git = shutil.which("git")
            if git:
                git_root = Path(git).resolve().parents[1]
                candidates.extend([
                    str(git_root / "bin" / "bash.exe"),
                    str(git_root / "usr" / "bin" / "bash.exe"),
                ])
        candidates.extend(["bash", "sh"])
        return candidates

    def _prepare_workspace(self, skill_base_path: Path, script_path: str, arguments_json: str) -> PreparedSkillWorkspace:
        """复制 skill 包到临时工作区，并写入脚本参数文件。"""
        temp_dir = Path(tempfile.mkdtemp(prefix="skill-runner-"))
        workspace_root = temp_dir / skill_base_path.name
        shutil.copytree(skill_base_path, workspace_root)
        internal_dir = workspace_root / ".skill"
        internal_dir.mkdir(parents=True, exist_ok=True)
        arguments_file = internal_dir / "arguments.json"
        arguments_file.write_text(arguments_json, encoding="utf-8")
        output_dir = workspace_root / "output"
        output_dir.mkdir(exist_ok=True)
        script_file = self._resolve_script_path(workspace_root, script_path)
        return PreparedSkillWorkspace(
            temp_dir=temp_dir,
            skill_root=workspace_root,
            script_path=script_file,
            arguments_file=arguments_file,
            output_dir=output_dir,
            baseline_files=self._snapshot_regular_files(workspace_root),
        )

    def _cleanup_workspace(self, prepared_workspace: PreparedSkillWorkspace | None) -> None:
        """清理临时工作区，避免 runner 长期堆积 skill 副本。"""
        if prepared_workspace is None:
            return
        shutil.rmtree(prepared_workspace.temp_dir, ignore_errors=True)

    def _snapshot_regular_files(self, root_path: Path) -> dict[str, tuple[int, int]]:
        """记录文件快照，用于识别脚本新增或修改的产物。"""
        snapshot: dict[str, tuple[int, int]] = {}
        for file_path in root_path.rglob("*"):
            if file_path.is_file():
                relative_path = file_path.relative_to(root_path).as_posix()
                stat_result = file_path.stat()
                snapshot[relative_path] = (int(stat_result.st_size), int(stat_result.st_mtime_ns))
        return snapshot

    def _collect_generated_files(self, prepared_workspace: PreparedSkillWorkspace | None) -> list[Path]:
        """收集临时工作区中脚本新增或修改的文件，跳过 runner 内部协议文件。"""
        if prepared_workspace is None:
            return []
        current_snapshot = self._snapshot_regular_files(prepared_workspace.skill_root)
        generated_files: list[Path] = []
        for relative_path, meta in current_snapshot.items():
            if relative_path.startswith(".skill/"):
                continue
            if prepared_workspace.baseline_files.get(relative_path) != meta:
                generated_files.append(prepared_workspace.skill_root / relative_path)
        return sorted(generated_files, key=lambda item: item.as_posix())

    def _stage_generated_artifacts(
        self,
        prepared_workspace: PreparedSkillWorkspace | None,
        request_id: str,
        skill_execution_id: str,
        action_name: str,
        tool_context: ToolContext,
    ) -> list[ToolArtifact]:
        """把临时工作区产物复制到本地暂存区；正式 artifact/ledger 对接后可替换这里。"""
        generated_files = self._collect_generated_files(prepared_workspace)
        if prepared_workspace is None or not generated_files:
            return []
        staged: list[ToolArtifact] = []
        for generated_file in generated_files[:50]:
            relative_path = generated_file.relative_to(prepared_workspace.skill_root)
            stored = persist_file(generated_file, relative_path.name, tool_context)
            staged.append(
                self.artifact(
                    artifact_id=stored.artifact_id,
                    name=relative_path.as_posix(),
                    artifact_type=generated_file.suffix.lstrip(".") or "file",
                    storage_type=stored.storage_type,
                    storage_ref=stored.storage_ref,
                    content_summary=f"Generated by skill script {action_name}",
                    artifact_role="output",
                    visibility="visible",
                    source_type="skill_generated_artifact",
                    source_name=self.name,
                    file_name=stored.file_name,
                    storage_key=stored.storage_key,
                    mime_type=stored.mime_type,
                    file_size=stored.file_size,
                    file_hash=stored.file_hash,
                    metadata={
                        "relativePath": relative_path.as_posix(),
                        "requestId": request_id,
                        "skillExecutionId": skill_execution_id,
                        "actionName": action_name,
                        "storageKey": stored.storage_key,
                    },
                )
            )
        return staged

    def _safe_name(self, value: str) -> str:
        """把 requestId 等外部标识压成可用于目录名的安全文本。"""
        cleaned = "".join(ch if ch.isalnum() or ch in ("-", "_") else "-" for ch in value.strip())
        return cleaned[:80] or "skill-run"

    def _bounded_timeout(self, raw: Any) -> int:
        """把声明超时限制在 runner 允许范围内。"""
        try:
            value = int(raw)
        except (TypeError, ValueError):
            value = 30
        return max(1, min(value, 120))

    def _parse_structured_stdout(self, stdout: str | None) -> dict[str, Any]:
        """优先把 stdout 当成 JSON 结构化结果解析，失败时保留截断文本。"""
        if not stdout or not stdout.strip():
            return {}
        text = stdout.strip()
        try:
            parsed = json.loads(text)
            return parsed if isinstance(parsed, dict) else {"value": parsed}
        except json.JSONDecodeError:
            return {"stdout": clip_text(text)}

    def _artifact_refs(self, structured: dict[str, Any]) -> list[dict[str, Any]]:
        """从脚本结构化输出中提取 artifactRefs。"""
        refs = structured.get("artifactRefs") if structured else []
        return refs if isinstance(refs, list) else []

    def _failure_status_from_stderr(self, stderr: str) -> str:
        """根据 stderr 粗略识别依赖缺失类失败。"""
        if "ModuleNotFoundError" in stderr or "No module named" in stderr:
            return "blocked"
        return "failed"

    def _error_code_from_status(self, status: str) -> str:
        """把状态映射为稳定错误码。"""
        if status == "blocked":
            return "script_dependency_missing"
        if status == "timeout":
            return "script_timeout"
        return "script_failed"

    def _recovery_hint_from_status(self, status: str) -> str:
        """给 agent 的恢复建议必须来自显式状态。"""
        if status == "blocked":
            return "Install the declared dependency or adapt the skill package."
        if status == "timeout":
            return "Retry with a smaller input or increase the declared timeout within policy."
        return "Review stderr and action arguments, then retry."

    def _invalid_argument(
        self,
        request_id: str,
        skill_execution_id: str,
        skill_name: str,
        skill_version: str,
        action_name: str,
        runtime: str,
        message: str,
    ) -> ToolExecuteResponse:
        """构造缺少 runner 请求字段时的失败结果。"""
        return self._result(
            request_id,
            skill_execution_id,
            skill_name,
            skill_version,
            action_name,
            runtime,
            False,
            "failed",
            None,
            "",
            "",
            message,
            {},
            [],
            "Provide the missing script action request field.",
            "script_invalid_arguments",
            message,
        )

    def _dependency_missing(
        self,
        request_id: str,
        skill_execution_id: str,
        skill_name: str,
        skill_version: str,
        action_name: str,
        runtime: str,
        message: str,
    ) -> ToolExecuteResponse:
        """构造依赖缺失时的 blocked 结果。"""
        return self._result(
            request_id,
            skill_execution_id,
            skill_name,
            skill_version,
            action_name,
            runtime,
            False,
            "blocked",
            None,
            "",
            message,
            "Script dependency is missing.",
            {},
            [],
            "Install the declared dependency or mark the package as adaptable.",
            "script_dependency_missing",
            message,
        )

    def _runtime_unavailable(
        self,
        request_id: str,
        skill_execution_id: str,
        skill_name: str,
        skill_version: str,
        action_name: str,
        runtime: str,
        message: str,
    ) -> ToolExecuteResponse:
        """构造运行时解释器未安装或配置错误时的可恢复 blocked 结果。"""
        return self._result(
            request_id,
            skill_execution_id,
            skill_name,
            skill_version,
            action_name,
            runtime,
            False,
            "blocked",
            None,
            "",
            message,
            "Script runtime is unavailable.",
            {},
            [],
            f"Install the {runtime} runtime or configure its SKILL_*_BIN environment variable.",
            "script_runtime_unavailable",
            message,
        )

    def _result(
        self,
        request_id: str,
        skill_execution_id: str,
        skill_name: str,
        skill_version: str,
        action_name: str,
        runtime: str,
        success: bool,
        status: str,
        exit_code: int | None,
        stdout: str,
        stderr: str,
        summary: str,
        structured_output: dict[str, Any],
        artifact_refs: list[dict[str, Any]],
        recovery_hint: str | None,
        error_code: str | None,
        error_message: str | None,
        artifacts: list[ToolArtifact] | None = None,
    ) -> ToolExecuteResponse:
        """构造 Python 工具统一响应，同时附带 skill/action 事实字段。"""
        artifact_payloads = [self._artifact_payload(item) for item in (artifacts or [])]
        structured = {
            "requestId": request_id,
            "skillExecutionId": skill_execution_id,
            "skillName": skill_name,
            "skillVersion": skill_version,
            "actionName": action_name,
            "scriptName": action_name,
            "runtime": runtime,
            "success": success,
            "status": status,
            "exitCode": exit_code,
            "stdout": stdout,
            "stderr": stderr,
            "summary": summary,
            "structuredOutput": structured_output or {},
            "artifactRefs": artifact_refs,
            "generatedArtifacts": artifact_payloads,
            "artifactCaptureStatus": "stable_storage_pending_ledger" if artifact_payloads else "none",
            "recoveryHint": recovery_hint,
            "errorCode": error_code,
            "errorMessage": error_message,
        }
        fact_type = "skill_action_finished" if success else "skill_execution_failed"
        facts = [
            {
                "factType": fact_type,
                "skillExecutionId": skill_execution_id,
                "skillName": skill_name,
                "skillVersion": skill_version,
                "actionName": action_name,
                "status": status,
                "artifactRefs": [
                    item.get("artifactRef") for item in artifact_refs if isinstance(item, dict)
                ] + [
                    item.get("storageRef") for item in artifact_payloads if isinstance(item, dict)
                ],
                "summary": summary,
                "errorCode": error_code,
            }
        ]
        observation = script_observation(
            skill_name=skill_name,
            action_name=action_name,
            runtime=runtime,
            success=success,
            status=status,
            exit_code=exit_code,
            stdout=stdout,
            stderr=stderr,
            summary=summary,
            artifact_refs=artifact_refs,
            recovery_hint=recovery_hint,
            error_code=error_code,
            error_message=error_message,
        )
        return ToolExecuteResponse(
            success=success,
            status=status,
            output=summary,
            llmObservation=observation,
            structured=structured,
            ledgerFacts=facts,
            artifacts=artifacts or [],
            errorCode=error_code,
            errorMessage=error_message,
            metadata={"producer": "skill_script_runner"},
        )

    def _artifact_payload(self, artifact: ToolArtifact) -> dict[str, Any]:
        """把 pydantic 产物模型转为使用协议别名的字典。"""
        if hasattr(artifact, "model_dump"):
            return artifact.model_dump(by_alias=True)
        return artifact.dict(by_alias=True)

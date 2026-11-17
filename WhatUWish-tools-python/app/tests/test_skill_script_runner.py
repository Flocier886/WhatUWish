import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from app.runtime.artifact_storage import reset_artifact_storage_runtime, resolve_storage_key

from app.runtime.models import ToolContext, ToolExecuteRequest
from app.tools.skill_script_runner import SkillScriptRunnerTool


SKILL_BASE = Path(__file__).resolve().parents[3] / "runtime" / "skills" / "community-script-fixture"
TEST_SHELL_BIN = None


def find_usable_shell() -> str | None:
    """寻找可实际执行脚本的 Bash/Sh；Windows 的 WSL bash 占位但无发行版时应跳过。"""
    candidates: list[str] = []
    configured = shutil.which("bash") or shutil.which("sh")
    if configured:
        candidates.append(configured)
    git = shutil.which("git")
    if git:
        git_root = Path(git).resolve().parents[1]
        candidates.extend([str(git_root / "bin" / "bash.exe"), str(git_root / "usr" / "bin" / "bash.exe")])
    for candidate in dict.fromkeys(candidates):
        if not Path(candidate).is_file():
            continue
        try:
            probe = subprocess.run(
                [candidate, "--version"],
                capture_output=True,
                timeout=3,
                check=False,
            )
            if probe.returncode == 0:
                return candidate
        except (OSError, subprocess.SubprocessError):
            continue
    return None


TEST_SHELL_BIN = find_usable_shell()


@pytest.fixture(autouse=True)
def local_artifact_storage(tmp_path, monkeypatch):
    """Runner 单测固定使用隔离 Local Backend，不受开发机 MinIO 配置影响。"""
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_TYPE", "local_artifact_storage")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "artifact-storage"))
    monkeypatch.setenv("AI_AGENT_ARTIFACT_S3_ENABLED", "false")
    reset_artifact_storage_runtime()
    yield
    reset_artifact_storage_runtime()


def execute_runner(
    script_path: str,
    arguments: dict | None = None,
    timeout: int = 5,
    runtime: str = "python",
    skill_base: Path = SKILL_BASE,
    argv: list[str] | None = None,
):
    """构造 skill_script_runner 测试请求，统一模拟 Java 侧传入的 action 调用。"""
    tool = SkillScriptRunnerTool()
    return tool.execute(
        ToolExecuteRequest(
            toolName="skill_script_runner",
            arguments={
                "requestId": "req-py-1",
                "skillExecutionId": "skill-exec-py-1",
                "skillName": "community-script-fixture",
                "skillVersion": "1.0.0",
                "skillBasePath": str(skill_base),
                "actionName": script_path.split("/")[-1].split(".")[0],
                "scriptPath": script_path,
                "runtime": runtime,
                "arguments": arguments or {},
                "argv": argv or [],
                "timeoutSeconds": timeout,
                "artifactPolicy": "optional",
            },
            context=ToolContext(requestId="req-py-1"),
        )
    )


def test_skill_script_runner_happy_path():
    """验证脚本在临时工作区执行，不污染原始 skill 包，并暂存 markdown 产物。"""
    original_output = SKILL_BASE / "output" / "pytest-summary.md"
    if original_output.exists():
        original_output.unlink()

    response = execute_runner("scripts/summarize.py", {"text": "hello runtime", "artifact_name": "pytest-summary"})

    assert response.success is True
    assert response.status == "success"
    assert response.structured["actionName"] == "summarize"
    assert response.structured["artifactRefs"][0]["artifactName"] == "pytest-summary.md"
    assert response.structured["artifactCaptureStatus"] == "stable_storage_pending_ledger"
    assert "hello runtime" in response.llm_observation
    assert "output/pytest-summary.md" in response.llm_observation
    assert response.artifacts
    staged_artifact = response.artifacts[0]
    assert staged_artifact.name == "output/pytest-summary.md"
    assert staged_artifact.file_name == "pytest-summary.md"
    assert staged_artifact.artifact_id
    assert staged_artifact.storage_key
    assert staged_artifact.storage_type == "local_artifact_storage"
    assert staged_artifact.source_type == "skill_generated_artifact"
    assert staged_artifact.source_name == "skill_script_runner"
    assert staged_artifact.file_size and staged_artifact.file_size > 0
    assert staged_artifact.metadata["relativePath"] == "output/pytest-summary.md"
    assert staged_artifact.storage_ref == staged_artifact.storage_key
    assert "hello runtime" in resolve_storage_key(staged_artifact.storage_key).read_text(encoding="utf-8")
    assert not original_output.exists()


def test_skill_script_runner_rejects_invalid_arguments():
    """验证脚本自行声明的参数错误会保留为稳定错误码。"""
    response = execute_runner("scripts/summarize.py", {})

    assert response.success is False
    assert response.status == "failed"
    assert response.error_code == "script_invalid_arguments"
    assert "text is required" in response.error_message


def test_skill_script_runner_preserves_dependency_blocked_status():
    """验证依赖缺失类失败会返回 blocked，供 agent 决定是否适配或停止。"""
    response = execute_runner("scripts/dependency_gap.py")

    assert response.success is False
    assert response.status == "blocked"
    assert response.error_code == "script_dependency_missing"
    assert response.structured["errorCode"] == "script_dependency_missing"


def test_skill_script_runner_timeout():
    """验证 runner 超时会返回 timeout 和恢复建议。"""
    response = execute_runner("scripts/timeout.py", timeout=1)

    assert response.success is False
    assert response.status == "timeout"
    assert response.error_code == "script_timeout"


def test_skill_script_runner_blocks_path_escape():
    """验证脚本路径不能逃出 skill 包根目录。"""
    response = execute_runner("../outside.py")

    assert response.success is False
    assert response.status == "blocked"
    assert response.error_code == "script_path_not_allowed"


@pytest.mark.parametrize(
    ("runtime", "executable", "expected_prefix"),
    [
        ("python", "python-bin", ["python-bin"]),
        ("node", "node-bin", ["node-bin"]),
        ("shell", "shell-bin", ["shell-bin"]),
        (
            "powershell",
            "powershell-bin",
            ["powershell-bin", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"],
        ),
        ("bat", "cmd-bin", ["cmd-bin", "/c"]),
    ],
)
def test_skill_script_runner_builds_registered_runtime_commands(monkeypatch, tmp_path, runtime, executable, expected_prefix):
    """验证五种 master-style runtime 都映射到参数数组，不使用 shell 字符串拼接。"""
    tool = SkillScriptRunnerTool()
    script = tmp_path / "demo.script"
    script.write_text("test", encoding="utf-8")
    monkeypatch.setattr(tool, "_resolve_executable", lambda *_args: executable)

    command = tool._build_command(runtime, script, ["--flag", "value with space"])

    assert command[: len(expected_prefix)] == expected_prefix
    assert str(script) in command
    assert command[-2:] == ["--flag", "value with space"]


def test_skill_script_runner_rejects_unknown_runtime():
    """验证未声明的运行时不会进入任意命令执行。"""
    response = execute_runner("scripts/summarize.py", runtime="ruby")

    assert response.success is False
    assert response.status == "blocked"
    assert response.error_code == "script_runtime_unsupported"


def test_skill_script_runner_returns_blocked_when_runtime_executable_is_missing(monkeypatch, tmp_path):
    """验证已支持 runtime 缺少解释器时返回可恢复 blocked，而不是业务脚本失败。"""
    skill = create_runtime_skill(tmp_path, "missing-node", "js", "console.log('unused');")
    monkeypatch.setenv("SKILL_NODE_BIN", str(tmp_path / "missing-node.exe"))

    response = execute_runner("scripts/run.js", runtime="node", skill_base=skill)

    assert response.success is False
    assert response.status == "blocked"
    assert response.error_code == "script_runtime_unavailable"
    assert "SKILL_NODE_BIN" in response.error_message


@pytest.mark.skipif(shutil.which("node") is None, reason="node runtime is not installed")
def test_skill_script_runner_executes_node_runtime(tmp_path):
    """真实执行 Node 脚本并保留结构化输出。"""
    skill = create_runtime_skill(
        tmp_path,
        "node-runtime",
        "js",
        """
const args = JSON.parse(process.env.SKILL_ARGUMENTS_JSON || "{}");
const fs = require("fs");
const path = require("path");
fs.writeFileSync(path.join(process.env.SKILL_OUTPUT_DIR, "node-result.txt"), args.value, "utf8");
console.log(JSON.stringify({status: "success", summary: `node:${args.value}`}));
""".strip(),
    )

    response = execute_runner("scripts/run.js", {"value": "ok"}, runtime="node", skill_base=skill)

    assert response.success is True
    assert response.structured["runtime"] == "node"
    assert response.structured["summary"] == "node:ok"
    assert response.artifacts
    assert response.artifacts[0].name == "output/node-result.txt"
    assert resolve_storage_key(response.artifacts[0].storage_key).read_text(encoding="utf-8") == "ok"


@pytest.mark.skipif(TEST_SHELL_BIN is None, reason="usable Bash/Sh runtime is not installed")
def test_skill_script_runner_executes_shell_runtime(tmp_path, monkeypatch):
    """真实执行 Shell 脚本；Windows 默认应优先解析 Git Bash。"""
    skill = create_runtime_skill(tmp_path, "shell-runtime", "sh", "printf 'shell:%s\\n' \"$1\"")
    monkeypatch.delenv("SKILL_SHELL_BIN", raising=False)

    response = execute_runner("scripts/run.sh", runtime="shell", skill_base=skill, argv=["ok"])

    assert response.success is True
    assert response.structured["runtime"] == "shell"
    assert "shell:ok" in response.structured["stdout"]


@pytest.mark.skipif(shutil.which("pwsh") is None and shutil.which("powershell") is None, reason="PowerShell runtime is not installed")
def test_skill_script_runner_executes_powershell_runtime(tmp_path):
    """真实执行 PowerShell 脚本，并验证 argv 可安全透传。"""
    skill = create_runtime_skill(
        tmp_path,
        "powershell-runtime",
        "ps1",
        """
param([string]$Value)
@{ status = "success"; summary = "powershell:$Value" } | ConvertTo-Json -Compress
""".strip(),
    )

    response = execute_runner(
        "scripts/run.ps1",
        runtime="powershell",
        skill_base=skill,
        argv=["-Value", "ok"],
    )

    assert response.success is True
    assert response.structured["runtime"] == "powershell"
    assert response.structured["summary"] == "powershell:ok"


@pytest.mark.skipif(sys.platform != "win32" or shutil.which("cmd") is None, reason="Windows cmd runtime is not installed")
def test_skill_script_runner_executes_bat_runtime(tmp_path):
    """真实执行 BAT/CMD 脚本。"""
    skill = create_runtime_skill(tmp_path, "bat-runtime", "cmd", "@echo off\necho bat:%1")

    response = execute_runner("scripts/run.cmd", runtime="bat", skill_base=skill, argv=["ok"])

    assert response.success is True
    assert response.structured["runtime"] == "bat"
    assert "bat:ok" in response.structured["stdout"]


def create_runtime_skill(root: Path, name: str, extension: str, content: str) -> Path:
    """创建仅供 runner 定向测试使用的临时社区 Skill 包。"""
    skill = root / name
    scripts = skill / "scripts"
    scripts.mkdir(parents=True)
    (skill / "SKILL.md").write_text(
        f"---\nname: {name}\ndescription: runtime test\n---\n",
        encoding="utf-8",
    )
    (scripts / f"run.{extension}").write_text(content + "\n", encoding="utf-8")
    return skill

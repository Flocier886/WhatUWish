"""Bash shares the E2B owner and session directory with code_execution and desktop control."""

from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.code_execution import bash_sandbox
from app.runtime.models import MaterialExecuteRequest


class FakeCommands:
    def __init__(self, root: Path):
        self.root = root
        self.calls = []

    def run(self, command, *, envs, cwd, timeout):
        self.calls.append((command, envs, cwd, timeout))
        if command == "timeout":
            raise TimeoutError("command timed out")
        if command == "write":
            (self.root / "outputs" / "result.txt").write_text("from bash", encoding="utf-8")
        if command == "fail":
            return SimpleNamespace(exit_code=7, stdout="partial", stderr="command failed")
        return SimpleNamespace(exit_code=0, stdout="checked", stderr="")


class FakeManager:
    def __init__(self, commands):
        self.commands = commands
        self.acquired = []
        self.released = []

    def acquire(self, owner, timeout_sec):
        self.acquired.append((owner, timeout_sec))
        return SimpleNamespace(sandbox=SimpleNamespace(commands=self.commands),
                               sandbox_id="sandbox-1", generation=1)

    def session_gate(self, owner, session_id):
        assert owner == "code:test" and session_id == "test"
        return nullcontext()

    def release(self, owner):
        self.released.append(owner)


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    root = tmp_path / "session-test"
    (root / "inputs").mkdir(parents=True)
    (root / "outputs").mkdir()
    commands = FakeCommands(root)
    manager = FakeManager(commands)
    synced = []
    recorded = []
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ENABLED", "true")
    monkeypatch.setattr(bash_sandbox, "get_sandbox_backend", lambda: "e2b")
    monkeypatch.setattr(bash_sandbox, "get_user_sandbox_manager", lambda: manager)
    monkeypatch.setattr(bash_sandbox, "push_workspace", lambda *args, **kwargs: synced.append(kwargs))
    monkeypatch.setattr(bash_sandbox, "snapshot_remote_files", lambda *args, **kwargs: {})
    monkeypatch.setattr(bash_sandbox, "download_changed_files", lambda *args, **kwargs: [])
    monkeypatch.setattr(bash_sandbox, "record_uploaded", lambda *args, **kwargs: recorded.append(kwargs))
    monkeypatch.setattr(bash_sandbox, "sync_e2b_skills", lambda *args, **kwargs: [])
    return root, manager, commands, synced, recorded


def execute(root: Path, command: str, **arguments):
    return bash_sandbox.execute_bash(MaterialExecuteRequest(
        toolName="bash", arguments={"command": command, "__authorizedInputPaths": [], **arguments},
        context={"sessionId": "test", "runId": "run-1", "toolCallId": "call-1"},
        workspaceRoot=str(root),
    ))


def test_bash_uses_same_owner_and_visible_desktop(runtime):
    root, manager, commands, synced, _ = runtime
    result = execute(root, "check", timeout_sec=15)
    assert result.success and result.structured["exitCode"] == 0
    assert result.structured["stdout"] == "checked"
    assert manager.acquired == [("code:test", 75)]
    assert manager.released == ["code:test"]
    assert synced[0]["owner_key"] == "code:test"
    assert commands.calls == [("check", {"DISPLAY": ":0"},
                               bash_sandbox.session_remote_root("test"), 15)]


def test_bash_uses_python_config_when_process_flag_is_absent(runtime, monkeypatch):
    from app.code_execution import sandbox_backend_config

    root, _, _, _, _ = runtime
    monkeypatch.delenv("AI_AGENT_CODE_EXECUTION_ENABLED")
    monkeypatch.setattr(sandbox_backend_config, "_code_execution_config",
                        lambda name: "true" if name == "enabled" else "")
    assert execute(root, "check").success


def test_failure_and_timeout_keep_execution_facts(runtime):
    root, _, _, _, _ = runtime
    failed = execute(root, "fail")
    assert failed.success and failed.status == "failed"
    assert failed.structured["exitCode"] == 7
    assert "partial" in failed.llm_observation and "command failed" in failed.llm_observation
    timed_out = execute(root, "timeout")
    assert timed_out.success and timed_out.structured["timedOut"]
    assert timed_out.structured["exitCode"] is None


def test_output_descriptor_uses_existing_artifact_contract(runtime):
    root, _, _, _, _ = runtime
    result = execute(root, "write")
    assert result.success
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/result.txt"]
    assert result.material_outputs[0]["sha256"]


def test_local_bash_requires_explicit_development_switch(runtime, monkeypatch):
    root, manager, _, _, _ = runtime
    monkeypatch.setattr(bash_sandbox, "get_sandbox_backend", lambda: "local")
    result = execute(root, "check")
    assert not result.success and "显式开发配置" in result.llm_observation
    assert manager.acquired == []


def test_local_bash_runs_registered_skill_and_keeps_library_edits(runtime, monkeypatch, tmp_path):
    root, manager, _, _, _ = runtime
    skill = tmp_path / "skill-library" / "demo"
    (skill / "scripts").mkdir(parents=True)
    (skill / "SKILL.md").write_text("# demo", encoding="utf-8")
    (skill / "data.txt").write_text("before", encoding="utf-8")
    (skill / "scripts" / "run.py").write_text(
        "from pathlib import Path\n"
        "Path('skills/demo/data.txt').write_text('after')\n"
        "Path('outputs/result.txt').write_text('skill-ready')\n"
        "print('skill-ran')\n", encoding="utf-8")
    monkeypatch.setattr(bash_sandbox, "get_sandbox_backend", lambda: "local")
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "true")
    result = execute(root, "python skills/demo/scripts/run.py",
                     __skillPaths={"demo": str(skill)},
                     __skillLibraryRoot=str(skill.parent))
    assert result.success, result.llm_observation
    assert result.structured["exitCode"] == 0
    assert "skill-ran" in result.structured["stdout"]
    assert result.structured["skillsMaterialized"] == ["demo"]
    assert result.structured["skillsSyncedBack"] == ["demo"]
    assert skill.joinpath("data.txt").read_text(encoding="utf-8") == "after"
    assert not root.joinpath("skills").exists()
    assert [item["relativePath"] for item in result.material_outputs] == ["outputs/result.txt"]
    assert manager.acquired == []


def test_local_bash_runs_new_skill_before_registry_refresh(runtime, monkeypatch, tmp_path):
    root, _, _, _, _ = runtime
    library = tmp_path / "skill-library"
    skill = library / "new-skill"
    (skill / "scripts").mkdir(parents=True)
    (skill / "SKILL.md").write_text("# new skill", encoding="utf-8")
    (skill / "scripts" / "run.py").write_text("print('new-skill-ready')\n", encoding="utf-8")
    monkeypatch.setattr(bash_sandbox, "get_sandbox_backend", lambda: "local")
    monkeypatch.setenv("AI_AGENT_CODE_EXECUTION_ALLOW_LOCAL", "true")
    result = execute(root, "python skills/new-skill/scripts/run.py",
                     __skillPaths={}, __skillLibraryRoot=str(library))
    assert result.success, result.llm_observation
    assert result.structured["exitCode"] == 0
    assert "new-skill-ready" in result.structured["stdout"]
    assert result.structured["skillsMaterialized"] == ["new-skill"]

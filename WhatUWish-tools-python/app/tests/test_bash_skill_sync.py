"""Master-style skill transfer adapted to the station's registered skill roots."""

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

from app.code_execution import bash_skill_sync


def test_e2b_pushes_only_referenced_skill_and_skips_unchanged(tmp_path, monkeypatch):
    workspace = tmp_path / "session-test"
    workspace.mkdir()
    demo = tmp_path / "root-one" / "demo"
    other = tmp_path / "root-two" / "other"
    for root in (demo, other):
        (root / "scripts").mkdir(parents=True)
        (root / "SKILL.md").write_text(root.name, encoding="utf-8")
        (root / "scripts" / "run.py").write_text("print(1)", encoding="utf-8")
    written = []
    monkeypatch.setattr(bash_skill_sync, "write_files",
                        lambda _sandbox, files, **_kwargs: written.extend(files))
    paths = {"demo": demo, "other": other}
    selected = bash_skill_sync.push_e2b_skills(
        object(), workspace, "/remote/session-test", paths,
        "python skills/demo/scripts/run.py", owner_key="code:test",
        sandbox_id="sandbox-1", generation=1)
    assert selected == ["demo"]
    assert len(written) == 2
    assert all("/skills/demo/" in item["path"] for item in written)
    bash_skill_sync.push_e2b_skills(
        object(), workspace, "/remote/session-test", paths,
        "python skills/demo/scripts/run.py", owner_key="code:test",
        sandbox_id="sandbox-1", generation=1)
    assert len(written) == 2


def test_e2b_syncs_modified_and_new_skill_files_back(tmp_path, monkeypatch):
    workspace = tmp_path / "session-test"
    workspace.mkdir()
    library = tmp_path / "skill-library"
    demo = library / "demo"
    (demo / "scripts").mkdir(parents=True)
    (demo / "SKILL.md").write_text("# demo", encoding="utf-8")
    original = demo / "scripts" / "run.py"
    original.write_text("print(1)", encoding="utf-8")
    remote_files = {
        "/remote/session-test/skills/demo/scripts/run.py": b"print(2)",
        "/remote/session-test/skills/new-skill/SKILL.md": b"# new",
    }
    sandbox = SimpleNamespace(files=SimpleNamespace(read=lambda path, format=None: remote_files[path]))

    def remote_listing(_sandbox, _script, *, timeout):
        items = [{"rel": path.split("/skills/", 1)[1], "size": len(data),
                  "sha256": hashlib.sha256(data).hexdigest()}
                 for path, data in remote_files.items()]
        return "__SKILLS_META__" + json.dumps(items)

    monkeypatch.setattr(bash_skill_sync, "run_remote_python", remote_listing)
    changed = bash_skill_sync.sync_e2b_skills(
        sandbox, workspace, "/remote/session-test", {"demo": demo}, library,
        owner_key="code:test", sandbox_id="sandbox-1", generation=1)
    assert changed == ["demo", "new-skill"]
    assert original.read_text(encoding="utf-8") == "print(2)"
    assert (library / "new-skill" / "SKILL.md").read_text(encoding="utf-8") == "# new"
    assert bash_skill_sync.sync_e2b_skills(
        sandbox, workspace, "/remote/session-test", {"demo": demo}, library,
        owner_key="code:test", sandbox_id="sandbox-1", generation=1) == []

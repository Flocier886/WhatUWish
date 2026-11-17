"""Master-style skills/ view for local Bash and incremental E2B skill sync."""

from __future__ import annotations

import json
import os
import re
import subprocess
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from app.code_execution.e2b_session_sync import (
    file_sha256,
    load_manifest,
    record_uploaded,
    run_remote_python,
    should_skip_rel,
    write_files,
)

SKILL_PATH_RE = re.compile(r"(?:^|[^\w.-])(?:\./)?skills[/\\]+([A-Za-z0-9._-]+)", re.IGNORECASE)
MAX_SKILL_FILE_BYTES = 32 * 1024 * 1024


def registered_skill_paths(raw: object) -> dict[str, Path]:
    if raw is None:
        return {}
    if not isinstance(raw, dict) or len(raw) > 500:
        raise ValueError("已注册 skill 清单无效")
    paths: dict[str, Path] = {}
    for name, location in raw.items():
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", name):
            raise ValueError("skill 名称无效")
        if not isinstance(location, str) or not Path(location).is_absolute():
            raise ValueError("skill 目录无效")
        path = Path(location).resolve()
        if not path.is_dir() or not (path / "SKILL.md").is_file():
            raise ValueError(f"已注册 skill 不可用：{name}")
        paths[name] = path
    return paths


def skill_library_root(raw: object) -> Path | None:
    if raw is None:
        return None
    if not isinstance(raw, str) or not Path(raw).is_absolute():
        raise ValueError("skill 库根目录无效")
    root = Path(raw).resolve()
    if not root.is_dir():
        raise ValueError("skill 库根目录不存在")
    return root


def library_skill_paths(root: Path | None, registered: dict[str, Path]) -> dict[str, Path]:
    """与参考项目一致，bash 从库目录发现 skill，包含本轮新建而未刷新的包。"""
    if root is None:
        return registered
    paths = dict(registered)
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.is_symlink() or child.name.startswith("."):
            continue
        if not re.fullmatch(r"[A-Za-z0-9._-]+", child.name):
            continue
        paths.setdefault(child.name, child)
    return paths


def referenced_skills(command: str, paths: dict[str, Path]) -> list[str]:
    available = {name.lower(): name for name in paths}
    selected: list[str] = []
    for match in SKILL_PATH_RE.finditer(command):
        name = available.get(match.group(1).lower())
        if name is not None and name not in selected:
            selected.append(name)
    return selected


def _skill_files(root: Path) -> Iterator[tuple[str, Path]]:
    for path in root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            relative = path.resolve().relative_to(root.resolve())
        except ValueError:
            continue
        if should_skip_rel(relative):
            continue
        if path.stat().st_size > MAX_SKILL_FILE_BYTES:
            continue
        yield relative.as_posix(), path


def _snapshot_skills(paths: dict[str, Path]) -> dict[str, dict[str, str]]:
    return {name: {relative: file_sha256(path) for relative, path in _skill_files(root)}
            for name, root in paths.items()}


@contextmanager
def local_skill_view(workspace: Path, paths: dict[str, Path]) -> Iterator[tuple[list[str], dict[str, dict[str, str]]]]:
    """Copy master's workspace/skills links; edits land directly in the registered library."""
    before = _snapshot_skills(paths)
    view = workspace / "skills"
    if view.exists() or view.is_symlink():
        raise ValueError("工作区 skills/ 已存在，不能覆盖现有文件")
    view.mkdir()
    linked: list[Path] = []
    try:
        for name, source in sorted(paths.items()):
            target = view / name
            if os.name == "nt":
                result = subprocess.run(["cmd", "/c", "mklink", "/J", str(target), str(source)],
                                        capture_output=True, text=True)
                if result.returncode != 0:
                    raise OSError(result.stderr or result.stdout or "mklink /J failed")
            else:
                target.symlink_to(source, target_is_directory=True)
            linked.append(target)
        yield list(paths), before
    finally:
        for target in reversed(linked):
            if target.is_symlink():
                target.unlink()
            elif os.name == "nt" and _is_junction(target):
                target.rmdir()
        if view.exists():
            view.rmdir()


def changed_local_skills(paths: dict[str, Path], before: dict[str, dict[str, str]]) -> list[str]:
    after = _snapshot_skills(paths)
    return sorted(name for name, files in after.items() if before.get(name) != files)


def _is_junction(path: Path) -> bool:
    if os.name != "nt":
        return False
    try:
        if hasattr(path, "is_junction"):
            return path.is_junction()
        return bool(path.is_dir() and (path.lstat().st_file_attributes & 0x400))
    except OSError:
        return False


def push_e2b_skills(sandbox: Any, workspace: Path, remote_root: str,
                    paths: dict[str, Path], command: str, *, owner_key: str,
                    sandbox_id: str, generation: int) -> list[str]:
    """Upload only command-referenced registered skills, as master bash_sandbox does."""
    selected = referenced_skills(command, paths)
    manifest = load_manifest(workspace)
    pending: list[dict[str, Any]] = []
    records: list[tuple[str, Path]] = []
    for name in selected:
        for relative, path in _skill_files(paths[name]):
            rel = f"skills/{name}/{relative}"
            digest = file_sha256(path)
            previous = manifest.files.get(rel)
            if previous is not None and previous.sha256 == digest:
                continue
            pending.append({"path": f"{remote_root}/{rel}", "data": path.read_bytes()})
            records.append((rel, path))
            if len(pending) >= 32:
                write_files(sandbox, pending, label="bash_skills")
                pending = []
    if pending:
        write_files(sandbox, pending, label="bash_skills")
    for rel, path in records:
        record_uploaded(workspace, rel, path, owner_key=owner_key,
                        sandbox_id=sandbox_id, generation=generation)
    return selected


def sync_e2b_skills(sandbox: Any, workspace: Path, remote_root: str,
                    paths: dict[str, Path], library_root: Path | None, *,
                    owner_key: str, sandbox_id: str, generation: int) -> list[str]:
    """Copy remote skill additions/changes back to the registered library by SHA-256."""
    remote_skills = f"{remote_root}/skills"
    script = f"""
import hashlib
import json
from pathlib import Path
root = Path({remote_skills!r})
files = []
if root.is_dir():
    for path in root.rglob('*'):
        if path.is_file() and not path.is_symlink():
            try:
                rel = path.resolve().relative_to(root.resolve())
            except ValueError:
                continue
            if any(part.startswith('.') for part in rel.parts):
                continue
            files.append({{'rel': rel.as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                          'size': path.stat().st_size}})
print('__SKILLS_META__' + json.dumps(files, ensure_ascii=True))
"""
    stdout = run_remote_python(sandbox, script, timeout=60)
    marker = next((line.split("__SKILLS_META__", 1)[1] for line in reversed(stdout.splitlines())
                   if "__SKILLS_META__" in line), None)
    if marker is None:
        raise RuntimeError("E2B skill 文件清单缺失")
    changed: set[str] = set()
    for item in json.loads(marker or "[]"):
        rel = str(item.get("rel") or "")
        parts = Path(rel).parts
        if len(parts) < 2 or any(part in {"", ".", ".."} or part.startswith(".") for part in parts):
            continue
        name = parts[0]
        base = paths.get(name) or (library_root / name if library_root is not None else None)
        if base is None:
            continue
        target = base.joinpath(*parts[1:])
        if not target.resolve().is_relative_to(base.resolve()) or target.is_symlink():
            continue
        size = int(item.get("size") or 0)
        if size < 0 or size > MAX_SKILL_FILE_BYTES:
            continue
        remote_hash = str(item.get("sha256") or "").lower()
        if target.is_file() and remote_hash and file_sha256(target) == remote_hash:
            continue
        remote_path = f"{remote_skills}/{rel}"
        try:
            content = sandbox.files.read(remote_path, format="bytes")
        except TypeError:
            content = sandbox.files.read(remote_path)
        data = content.encode("utf-8") if isinstance(content, str) else bytes(content)
        if len(data) > MAX_SKILL_FILE_BYTES:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        record_uploaded(workspace, f"skills/{rel}", target,
                        owner_key=owner_key, sandbox_id=sandbox_id, generation=generation)
        changed.add(name)
    return sorted(changed)

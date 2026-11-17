import os
import time

import pytest

from app.runtime.artifact_storage import (
    ArtifactStorageScope,
    cleanup_expired_artifacts,
    persist_text,
    resolve_artifact_id,
    resolve_storage_key,
)
from app.runtime.models import ToolContext


def context(session_id: str, run_id: str) -> ToolContext:
    return ToolContext(sessionId=session_id, runId=run_id, requestId="request-1")


def test_storage_should_isolate_runs_and_resolve_stable_references(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    first = persist_text("first", "report.txt", context("session-a", "run-a"))
    second = persist_text("second", "report.txt", context("session-a", "run-b"))

    assert first.artifact_id != second.artifact_id
    assert first.storage_type == "local_artifact_storage"
    assert second.storage_type == "local_artifact_storage"
    assert first.storage_key.startswith("session-a/run-a/")
    assert second.storage_key.startswith("session-a/run-b/")
    assert first.file_name == "report.txt"
    assert resolve_storage_key(first.storage_key).read_text(encoding="utf-8") == "first"
    assert resolve_artifact_id(first.artifact_id) == resolve_storage_key(first.storage_key)


def test_storage_cleanup_should_only_remove_expired_registered_artifacts(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    monkeypatch.setenv("AI_AGENT_ARTIFACT_RETENTION_SECONDS", "10")
    expired = persist_text("expired", "old.txt", context("session-a", "run-a"))
    retained = persist_text("retained", "new.txt", context("session-a", "run-a"))
    now = time.time()
    os.utime(resolve_storage_key(expired.storage_key), (now - 30, now - 30))

    deleted = cleanup_expired_artifacts(now=now)

    assert expired.storage_key in deleted
    assert not resolve_storage_key(expired.storage_key).exists()
    assert resolve_storage_key(retained.storage_key).exists()


def test_storage_should_reject_path_traversal(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))

    with pytest.raises(ValueError):
        resolve_storage_key("../outside.txt")


def test_storage_should_support_long_runtime_identifiers_on_windows_paths(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text(
        "long path artifact",
        "report-" + "f" * 180 + ".txt",
        context("session-" + "s" * 120, "run-" + "r" * 120),
    )

    target = resolve_storage_key(stored.storage_key)
    assert target.read_text(encoding="utf-8") == "long path artifact"
    assert len(str(target)) <= 240
    assert stored.file_name.endswith(".txt")


def test_storage_scope_allows_rich_tool_to_persist_without_tool_context(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text("research facts", "evidence.md", ArtifactStorageScope(execution_id="research-1", caller_scope="rich-api"))

    assert stored.storage_key.startswith("rich-api/research-1/")
    assert resolve_storage_key(stored.storage_key).read_text(encoding="utf-8") == "research facts"


def test_s3_active_mode_should_fail_closed_when_configuration_is_incomplete(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    # 隔离本机 application.local.yaml 的真实 MinIO 配置，才能验证缺参时拒绝写入。
    monkeypatch.setattr("app.runtime.artifact_storage.load_application_configuration",
                        lambda: {"artifact-storage": {"type": "s3_object_storage",
                                                      "local-root": str(tmp_path / "storage")}})

    with pytest.raises(ValueError, match="必要配置不完整"):
        persist_text("must not fall back", "report.txt", context("session-a", "run-a"))

    assert list((tmp_path / "storage").rglob("*.txt")) == []

from app.runtime.artifact_storage import persist_text
from app.runtime.models import ToolContext, ToolExecuteRequest
from app.tools.artifact_read import ArtifactReadTool, MAX_READ_LIMIT


def request(arguments: dict) -> ToolExecuteRequest:
    return ToolExecuteRequest(toolName="artifact_read", arguments=arguments)


def private_arguments(stored, **extra) -> dict:
    return {
        "artifactId": stored.artifact_id,
        "storageType": stored.storage_type,
        "storageKey": stored.storage_key,
        **extra,
    }


def test_artifact_read_returns_bounded_segments_without_structured_content(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text("abcdefghij", "note.txt", ToolContext(sessionId="session-a", runId="run-a", requestId="request-a"))
    tool = ArtifactReadTool()

    first = tool.execute(request(private_arguments(stored, limit=4)))

    assert first.success is True
    assert first.structured["contentLength"] == 10
    assert "storageType" not in first.structured
    assert "storageKey" not in first.structured
    assert first.structured["offset"] == 0
    assert first.structured["returnedLength"] == 4
    assert first.structured["hasMore"] is True
    assert first.structured["nextOffset"] == 4
    assert "content" not in first.structured
    assert "abcd" in first.llm_observation

    second = tool.execute(request(private_arguments(stored, offset=4, limit=4)))

    assert second.success is True
    assert second.structured["offset"] == 4
    assert second.structured["returnedLength"] == 4
    assert second.structured["nextOffset"] == 8
    assert "efgh" in second.llm_observation

    final = tool.execute(request(private_arguments(stored, offset=8, limit=4)))

    assert final.success is True
    assert final.structured["hasMore"] is False
    assert final.structured["nextOffset"] is None
    assert "ij" in final.llm_observation
    assert "storageKey" not in final.llm_observation
    assert "storageType" not in final.llm_observation


def test_artifact_read_uses_five_thousand_character_default_segment(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text("a" * (MAX_READ_LIMIT + 1), "long-note.txt",
                          ToolContext(sessionId="session-a", runId="run-a", requestId="request-a"))

    first = ArtifactReadTool().execute(request(private_arguments(stored)))

    assert first.success is True
    assert first.structured["returnedLength"] == MAX_READ_LIMIT
    assert first.structured["hasMore"] is True
    assert first.structured["nextOffset"] == MAX_READ_LIMIT

    final = ArtifactReadTool().execute(request(private_arguments(stored, offset=MAX_READ_LIMIT)))
    assert final.success is True
    assert final.structured["returnedLength"] == 1
    assert final.structured["hasMore"] is False


def test_artifact_read_rejects_invalid_ranges_before_reading(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text("private body", "note.txt", ToolContext(sessionId="session-a", runId="run-a", requestId="request-a"))
    tool = ArtifactReadTool()

    negative_offset = tool.execute(request(private_arguments(stored, offset=-1)))
    zero_limit = tool.execute(request(private_arguments(stored, limit=0)))
    oversized_limit = tool.execute(request(private_arguments(stored, limit=MAX_READ_LIMIT + 1)))

    for response in (negative_offset, zero_limit, oversized_limit):
        assert response.success is False
        assert response.error_code == "invalid_argument"
        assert "private body" not in response.llm_observation


def test_artifact_read_requires_java_injected_private_locator():
    response = ArtifactReadTool().execute(request({"artifactId": "artifact-only"}))

    assert response.success is False
    assert response.error_code == "invalid_argument"


def test_artifact_read_does_not_probe_other_backends(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "storage"))
    stored = persist_text("private body", "note.txt", ToolContext(sessionId="session-a", runId="run-a", requestId="request-a"))

    wrong_storage_type = "local_artifact_storage" if stored.storage_type == "s3_object_storage" else "s3_object_storage"
    response = ArtifactReadTool().execute(request({
        "artifactId": stored.artifact_id,
        "storageType": wrong_storage_type,
        "storageKey": stored.storage_key,
    }))

    assert response.success is False
    assert response.error_code == "artifact_not_found"
    assert "private body" not in response.llm_observation

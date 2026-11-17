from app.runtime import context as runtime_context
from app.runtime.models import ToolExecuteRequest
from app.tools.artifact_save import ArtifactSaveTool
from app.tools.artifact_read import ArtifactReadTool
from app.tools.directory_list import DirectoryListTool
from app.tools.file_read import FileReadTool
from app.tools.file_write import FileWriteTool
from app.tools.file_search import FileSearchTool
from app.tools.search_provider import SearchHit
from app.tools.web_search import WebSearchTool


def request(tool_name: str, arguments: dict):
    return ToolExecuteRequest(toolName=tool_name, arguments=arguments)


def test_file_read_observation_contains_content_preview(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    target = tmp_path / "notes.md"
    target.write_text("第一行\n关键内容：工具 observation 必须有事实\n", encoding="utf-8")

    response = FileReadTool().execute(request("file_read", {"path": "notes.md"}))

    assert response.success is True
    assert "关键内容" in response.llm_observation
    assert "内容长度" in response.llm_observation


def test_directory_list_observation_contains_entries(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "guide.md").write_text("guide", encoding="utf-8")

    response = DirectoryListTool().execute(request("directory_list", {"path": ".", "maxDepth": 2}))

    assert response.success is True
    assert "docs/" in response.llm_observation
    assert "guide.md" in response.llm_observation


def test_file_search_observation_contains_match_snippet(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    target = tmp_path / "guide.md"
    target.write_text("普通内容\nlookup works for observation\n", encoding="utf-8")

    response = FileSearchTool().execute(request("file_search", {"query": "lookup works", "path": "."}))

    assert response.success is True
    assert "guide.md" in response.llm_observation
    assert "lookup works" in response.llm_observation


def test_artifact_read_observation_contains_artifact_preview(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    monkeypatch.setattr(runtime_context, "DEFAULT_ARTIFACT_DIR", tmp_path / "artifacts")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "artifact-storage"))
    saved = ArtifactSaveTool().execute(
        request("artifact_save", {"name": "summary.md", "content": "产物正文：observation 需要可读预览"})
    )
    assert saved.success is True

    response = ArtifactReadTool().execute(request("artifact_read", {
        "artifactId": saved.structured["artifactId"],
        "storageType": saved.structured["storageType"],
        "storageKey": saved.structured["storageKey"],
    }))

    assert response.success is True
    assert "产物正文" in response.llm_observation
    assert "已读取 artifact" in response.llm_observation


def test_artifact_save_and_read_should_close_reference_loop(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    monkeypatch.setattr(runtime_context, "DEFAULT_ARTIFACT_DIR", tmp_path / "artifacts")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "artifact-storage"))

    save_response = ArtifactSaveTool().execute(
        request("artifact_save", {"name": "tool-summary.txt", "content": "产物闭环内容", "artifactType": "text"})
    )

    assert save_response.success is True
    assert "artifactId:" in save_response.llm_observation
    assert "storageKey:" in save_response.llm_observation
    assert save_response.artifacts
    saved_artifact = save_response.artifacts[0]
    assert saved_artifact.file_name == save_response.structured["fileName"]
    assert saved_artifact.artifact_id == save_response.structured["artifactId"]
    assert saved_artifact.storage_key == save_response.structured["storageKey"]
    assert saved_artifact.storage_type == save_response.structured["storageType"]
    assert saved_artifact.storage_ref == save_response.structured["storageRef"]
    assert saved_artifact.source_type == "artifact_save"
    assert saved_artifact.source_name == "artifact_save"
    assert saved_artifact.file_size == save_response.structured["fileSize"]
    assert saved_artifact.mime_type == save_response.structured["mimeType"]

    read_by_private_locator = ArtifactReadTool().execute(
        request("artifact_read", {
            "artifactId": saved_artifact.artifact_id,
            "storageType": saved_artifact.storage_type,
            "storageKey": saved_artifact.storage_key,
        })
    )
    assert read_by_private_locator.success is True
    assert "产物闭环内容" in read_by_private_locator.llm_observation
    assert "storageKey" not in read_by_private_locator.llm_observation
    assert read_by_private_locator.artifacts == []

    legacy_read = ArtifactReadTool().execute(request("artifact_read", {"artifactId": saved_artifact.artifact_id}))
    assert legacy_read.success is False
    assert legacy_read.error_code == "invalid_argument"


def test_file_write_returns_artifact_metadata(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "artifact-storage"))

    response = FileWriteTool().execute(request("file_write", {"path": "reports/result.md", "content": "# 结果"}))

    assert response.success is True
    assert response.artifacts
    artifact = response.artifacts[0]
    assert artifact.name == "result.md"
    assert artifact.file_name == "result.md"
    assert artifact.source_type == "file_write"
    assert artifact.source_name == "file_write"
    assert artifact.artifact_id
    assert artifact.storage_key
    assert artifact.storage_type == response.structured["storageType"]
    assert artifact.storage_ref == response.structured["storageRef"]
    assert artifact.file_size == response.structured["fileSize"]
    assert artifact.mime_type == response.structured["mimeType"]
    assert "storageKey:" in response.llm_observation


def test_artifact_save_should_return_structured_failure_when_storage_is_unavailable(monkeypatch):
    def unavailable(*_args, **_kwargs):
        raise OSError("storage unavailable")

    monkeypatch.setattr("app.tools.artifact_save.persist_text", unavailable)

    response = ArtifactSaveTool().execute(
        request("artifact_save", {"name": "report.txt", "content": "artifact content"})
    )

    assert response.success is False
    assert response.status == "failed"
    assert response.error_code == "artifact_storage_error"
    assert "artifact storage" in response.llm_observation


def test_file_write_should_return_structured_failure_when_artifact_storage_is_unavailable(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_context, "WORKSPACE_ROOT", tmp_path)

    def unavailable(*_args, **_kwargs):
        raise OSError("storage unavailable")

    monkeypatch.setattr("app.tools.file_write.persist_file", unavailable)

    response = FileWriteTool().execute(
        request("file_write", {"path": "reports/result.md", "content": "report content"})
    )

    assert response.success is False
    assert response.status == "failed"
    assert response.error_code == "artifact_storage_error"
    assert "artifact storage" in response.llm_observation


def test_web_search_observation_contains_real_source_summary(monkeypatch):
    class StubSearchProvider:
        def search_first_with_summary(self, query: str, limit: int, allowed, blocked):
            from app.tools.search_provider import SearchResult

            assert query == "agent tool observation"
            assert limit == 2
            assert allowed == blocked == ()
            return SearchResult([
                SearchHit(
                    title="真实研究来源",
                    url="https://example.com/research",
                    domain="example.com",
                    snippet="这是经过 Provider 返回并规范化的真实来源摘要。",
                    provider="tavily",
                    rank=1,
                )
            ])

    monkeypatch.setattr("app.tools.web_search.create_search_provider", lambda: StubSearchProvider())
    response = WebSearchTool().execute(request("web_search", {"query": "agent tool observation", "limit": 2}))

    assert response.success is True
    assert "真实研究来源" in response.llm_observation
    assert "URL:" in response.llm_observation
    assert "example.com (tavily)" in response.llm_observation
    assert "占位" not in response.llm_observation
    assert response.metadata == {"limit": 2, "resultCount": 1, "provider": "tavily"}

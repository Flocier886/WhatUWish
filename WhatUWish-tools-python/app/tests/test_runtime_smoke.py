from app.runtime.registry import TOOL_REGISTRY


def test_registry_should_include_workspace_tools():
    names = {tool.name for tool in TOOL_REGISTRY.list_tools()}
    assert "web_search" in names
    assert "web_fetch" in names
    assert "directory_list" in names
    assert "file_search" in names
    assert "file_read" in names
    assert "file_write" in names
    assert "json_extract" in names
    assert "artifact_save" in names
    assert "artifact_read" in names
    assert "skill_script_runner" in names

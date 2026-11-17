"""把 docgen 的八个原生动作接到本站会话工作区。"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

from app.docgen import service


RUNNERS = {
    "document_generate": service.run_document_generate,
    "slides_generate": service.run_slides_generate,
    "excel_generator": service.run_excel_generator,
    "chart_generator": service.run_chart_generator,
    "checklist_generate": service.run_checklist_generate,
    "template_filler": service.run_template_filler,
    "document_template": service.run_document_template,
    "theme_designer": service.run_theme_designer,
}

_FILE_KEYS = {"path", "image_path", "logo_path", "template_path", "data_file"}
_FILE_LIST_KEYS = {"merge_sources"}
_DIRECTORY_LIST_KEYS = {"include_paths"}


def _workspace_path(value: str, root: Path, directory: bool = False) -> str:
    path = Path(value.replace("\\", "/"))
    if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] not in {"inputs", "outputs"}:
        raise ValueError("文档材料必须使用当前会话的 inputs/ 或 outputs/ 相对路径")
    target = (root / path).resolve()
    if not target.is_relative_to(root) or not (target.is_dir() if directory else target.is_file()):
        raise ValueError("文档材料不存在或不可访问")
    return str(target)


def _resolve_sources(value: Any, root: Path, key: str | None = None) -> Any:
    if key in _FILE_KEYS and isinstance(value, str):
        return _workspace_path(value, root)
    if key in _FILE_LIST_KEYS and isinstance(value, list):
        return [_workspace_path(item, root) for item in value]
    if key in _DIRECTORY_LIST_KEYS and isinstance(value, list):
        return [_workspace_path(item, root, directory=True) for item in value]
    if key == "file_id":
        raise ValueError("file_id 暂不能作为本站文档图片引用；请使用工作区相对路径")
    if isinstance(value, dict):
        return {name: _resolve_sources(item, root, name) for name, item in value.items()}
    if isinstance(value, list):
        return [_resolve_sources(item, root) for item in value]
    return value


def execute_docgen(name: str, arguments: dict[str, Any], root: Path, request_id: str | None) -> dict[str, Any]:
    if name not in RUNNERS:
        raise ValueError("未知文档生成工具")
    params = _resolve_sources(dict(arguments), root)
    token = service._workspace_output_root.set((root / "outputs").resolve())
    try:
        return asyncio.run(RUNNERS[name](request_id or "default", params))
    finally:
        service._workspace_output_root.reset(token)

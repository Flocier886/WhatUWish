"""Report 核心对正式 Artifact Storage 的薄适配。

此处只替换公开 Report 实现中的 URL / 本地路径下载能力：核心层仍然拿到
``file_name/content`` 文件集合，并保留其后续的筛选、展开与顺序截断行为。
"""

from __future__ import annotations

import json
from copy import deepcopy
from dataclasses import dataclass
from typing import Any

from app.report.models import ReportInputArtifact
from app.report.settings import ReportRuntimeSettings
from app.runtime.artifact_storage import read_artifact_range
from app.runtime.errors import ToolExecutionError


TEXT_MIME_TYPES = {
    "text/plain",
    "text/markdown",
    "text/html",
    "text/csv",
    "application/csv",
}
TEXT_EXTENSIONS = {".md", ".markdown", ".txt", ".html", ".htm", ".csv"}


@dataclass(frozen=True)
class ReportMaterialStatistics:
    input_artifact_count: int
    accepted_artifact_count: int
    read_char_count: int
    read_truncated_count: int

    def as_dict(self) -> dict[str, int]:
        return {
            "inputArtifactCount": self.input_artifact_count,
            "acceptedArtifactCount": self.accepted_artifact_count,
            "inputChars": self.read_char_count,
            "truncatedArtifactCount": self.read_truncated_count,
        }


class ReportArtifactAdapter:
    """将 Java 已授权 descriptor 适配为 master 兼容文件集合。"""

    def __init__(self, settings: ReportRuntimeSettings) -> None:
        self._settings = settings
        self.last_statistics = ReportMaterialStatistics(0, 0, 0, 0)
        self._prepared_files: dict[tuple[str, ...], list[dict[str, Any]]] = {}

    def download_all_files(self, file_names: list[ReportInputArtifact]) -> list[dict[str, Any]]:
        """保持 master ``download_all_files`` 的输入顺序和 ``file_name/content`` 返回语义。"""
        cache_key = tuple(artifact.artifact_id for artifact in file_names)
        prepared = self._prepared_files.get(cache_key)
        if prepared is not None:
            return deepcopy(prepared)
        file_contents: list[dict[str, Any]] = []
        read_chars = 0
        read_truncated_count = 0
        for artifact in file_names:
            if not self._is_text(artifact):
                raise ToolExecutionError("report_input_unsupported", "报告暂只支持文本类输入 Artifact。")
            # 对象读取只使用 Java 私有 descriptor；不接受 URL、任意路径或文件名作为 locator。
            read_limit = min(artifact.file_size, self._settings.max_artifact_bytes)
            raw = read_artifact_range(artifact.storage_key, 0, read_limit, artifact.storage_type)
            if artifact.file_size > read_limit:
                read_truncated_count += 1
            content = self._decode(raw)
            read_chars += len(content)
            file_contents.append({"file_name": artifact.file_name, "content": content})
        if not file_contents:
            raise ToolExecutionError("report_input_empty", "没有可用于生成报告的文本材料。")
        self.last_statistics = ReportMaterialStatistics(
            input_artifact_count=len(file_names),
            accepted_artifact_count=len(file_contents),
            read_char_count=read_chars,
            read_truncated_count=read_truncated_count,
        )
        self._prepared_files[cache_key] = deepcopy(file_contents)
        return file_contents

    @staticmethod
    def truncate_files(files: list[dict[str, Any]], max_tokens: int) -> list[dict[str, Any]]:
        """实体保留 master ``file_util.truncate_files`` 的顺序累积截断语义。"""
        truncated_files: list[dict[str, Any]] = []
        token_size = 0
        for file_item in files:
            file_copy = deepcopy(file_item)
            if token_size >= max_tokens:
                break
            file_copy["content"] = file_copy["content"][: max_tokens - token_size]
            token_size += len(file_copy.get("content", ""))
            truncated_files.append(file_copy)
        return truncated_files

    @staticmethod
    def flatten_search_file(search_file: dict[str, Any]) -> list[dict[str, Any]]:
        """实体保留 master ``file_util.flatten_search_file`` 的 JSON 展开语义。"""
        flat_files: list[dict[str, Any]] = []
        try:
            contents = json.loads(search_file["content"])
            for _, value in contents.items():
                flat_files.extend(value)
        except (TypeError, ValueError, AttributeError):
            # 与 master 一致：搜索结果结构不合法时只忽略该展开，不把文件 locator 暴露到错误中。
            return flat_files
        return flat_files

    @staticmethod
    def _is_text(artifact: ReportInputArtifact) -> bool:
        suffix = artifact.file_name.lower().rsplit(".", 1)
        extension = f".{suffix[-1]}" if len(suffix) == 2 else ""
        mime_type = artifact.mime_type.lower().split(";", 1)[0].strip()
        return mime_type in TEXT_MIME_TYPES or extension in TEXT_EXTENSIONS

    @staticmethod
    def _decode(raw: bytes) -> str:
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            return raw.decode("gbk", errors="ignore")

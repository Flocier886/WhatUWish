"""材料工具目录：模型描述沿用 Java 定义，参数沿用 Python 内核可执行 schema。"""

from __future__ import annotations

from copy import deepcopy
from typing import Any

from app.material.service import _tool

NAMES = (
    "pdf_reader", "pdf_structure", "citation_extractor", "word_reader", "excel_reader",
    "csv_processor", "html_processor", "markdown_processor", "text_processor", "image_ocr",
    "data_clean", "data_merge", "data_transform", "data_aggregate", "data_validate", "sql_query",
)

# 与 master 的 Java defaultDescription 对齐；OCR 去掉本项目尚未接入的后端。
DESCRIPTIONS = {
    "pdf_reader": "PDF toolkit: operation=read|extract_tables|extract_images|extract_links|search|page_info|outline|convert_to_images|split|merge|extract_pages|metadata. Prefer read for text; use pdf_structure for academic outline.",
    "pdf_structure": "Analyze PDF structure: page count, title, outline/bookmarks, heuristic section headings, figures/tables. Use before summarizing academic papers.",
    "citation_extractor": "Extract reference/bibliography list from an academic PDF (marker, full text, DOI/URL when detected).",
    "word_reader": "Read Word documents (.docx/.docm structure; legacy .doc plain text). Extract paragraphs, headings, tables, optional headers/footers.",
    "excel_reader": "Read Excel .xlsx/.xlsm: sheet by name/index, optional cell_range, output_format=records|dict|list.",
    "csv_processor": "CSV/TSV processor: operation=read|query|write|stats|convert. Read rows, filter/query, write output, compute stats, or convert formats.",
    "html_processor": "HTML processor: operation=read|extract_links|extract_tables|extract_metadata|convert. Extract text/links/tables/meta or convert to markdown/plain_text.",
    "markdown_processor": "Markdown processor: read/write/create/append/section edit/merge/convert/format/template. Use for structured markdown authoring and TOC/link extraction.",
    "text_processor": "Text file processor: read/write/search/replace/insert/transform/stats/diff with encoding detection.",
    "image_ocr": "Extract text from an image (PNG/JPG/WEBP/...) via cloud OCR. Pass workspace file_path. Optional lang=ch|en|ch_en or custom prompt. Backend selected by OCR_TYPE (vlm-ocr / deepseek-ocr).",
    "data_clean": "Clean tabular data: dedupe, fill/drop missing, trim whitespace, normalize types. For fill_missing with a literal value, pass fill_value (not value). Input via data / source_path / artifact.",
    "data_merge": "Merge/join tabular datasets: operation=merge|concat, how=inner|outer|left|right|cross. Provide left_data/right_data or left_artifact/right_artifact or datasets list.",
    "data_transform": "Transform tabular data: rename columns, cast types, map values, derive columns, string upper/lower/strip. Pass transformations=[{type, ...}] (operations is accepted as alias).",
    "data_aggregate": "Aggregate tabular data: operation=groupby|pivot|describe|value_counts|rolling. Supports sum/avg/count/min/max and pivot tables. Input via data (records), source_path, or artifact.",
    "data_validate": "Validate tabular data against schema/rules: types, ranges, required fields, regex patterns, uniqueness. Returns validation report (not a transformed table).",
    "sql_query": "Read-only SQL SELECT on tabular data (JOIN/WHERE/GROUP BY/ORDER BY/aggregates). Provide query + tables map or data/source_path (registered as table 'data'). Destructive SQL is rejected.",
}


def catalog() -> dict[str, dict[str, Any]]:
    """生成模型可见 schema；数据集各层只允许内联记录或 Java 已授权的引用。"""
    from app.material.docread.pdf_research import PDFStructureTool, CitationExtractorTool

    special = {"pdf_structure": PDFStructureTool(), "citation_extractor": CitationExtractorTool()}
    result = {}
    for name in NAMES:
        tool = special.get(name)
        if tool is None and name != "image_ocr":
            tool = _tool(name)
        if tool is None:
            schema = {"type": "object", "properties": {
                "file_path": {"type": "string", "description": "图片文件路径"},
                "lang": {"type": "string", "description": "可选语言提示 ch|en|ch_en"},
                "prompt": {"type": "string", "description": "可选 OCR 指令"}},
                "required": ["file_path"], "additionalProperties": False}
        else:
            schema = deepcopy(tool.parameters)
        properties = schema.get("properties", {})
        for key in ("file_path", "file_path_2", "source_path", "output_path", "output_dir"):
            if key in properties and isinstance(properties[key], dict):
                # 上游部分说明允许 file:// 等宽松定位；本项目实际只接受会话路径/授权 ID。
                original = properties[key].get("description", "")
                original = original.replace("(or file:// URI) ", "").replace("file:// URI", "workspace path")
                if name == "csv_processor" and key == "file_path":
                    original = original.replace("(may be a new file under the session uploads directory)",
                                                "(write target must be under outputs/)")
                boundary = ("生成文件只能写入会话工作区 outputs/ 相对路径。"
                            if key.startswith("output_") else
                            "接受会话工作区 inputs/ 或 outputs/ 相对路径，或同会话可见的 Artifact ID。"
                            if key in {"file_path", "source_path"} else
                            "接受会话工作区 inputs/ 或 outputs/ 相对路径。")
                properties[key]["description"] = (original + " " + boundary).strip()
        for key in ("artifact", "left_artifact", "right_artifact"):
            if key in properties:
                original = properties[key].get("description", "")
                original = original.replace(" (e.g. minio://bucket/key, file:///abs/path)", "")
                properties[key] = {"type": "string", "description":
                                   (original + " 同会话可见的 Artifact ID，或会话工作区相对路径。").strip()}
        if "datasets" in properties:
            original = properties["datasets"].get("description", "")
            properties["datasets"] = {"type": "array", "items": {"oneOf": [
                {"type": "array", "items": {"type": "object"}},
                {"type": "string"}]}, "description":
                (original + " 内联记录集或会话工作区路径/授权 Artifact ID 列表。").strip()}
        if "tables" in properties:
            original = properties["tables"].get("description", "")
            properties["tables"] = {"type": "object", "description":
                                    (original + " 表名映射到内联记录集、会话工作区路径或授权 Artifact ID。").strip(),
                                    "additionalProperties": {"oneOf": [
                                        {"type": "array", "items": {"type": "object"}}, {"type": "string"}]}}
        for key in ("left_data", "right_data"):
            if key in properties:
                original = properties[key].get("description", "")
                properties[key] = {"oneOf": [{"type": "array", "items": {"type": "object"}},
                                               {"type": "string"}],
                                   "description": (original + " 内联行记录、会话工作区路径或授权 Artifact ID。").strip()}
        if "source_files" in properties or "merge_files" in properties:
            for key in ("source_files", "merge_files"):
                if key in properties:
                    properties[key]["description"] = properties[key].get("description", "") + " 每项必须是会话工作区相对路径。"
        if name == "pdf_reader":
            # merge 允许只传 merge_files；具体操作的必需参数由解析内核校验。
            schema["required"] = []
        schema["additionalProperties"] = False
        result[name] = {"description": DESCRIPTIONS[name], "params": schema}
    return result

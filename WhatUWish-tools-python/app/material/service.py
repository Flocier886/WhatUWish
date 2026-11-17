"""在 Java/Python 共享的会话工作区运行材料处理内核。"""

from __future__ import annotations

import json
from contextlib import contextmanager
import os
import re
import shutil
import zipfile
from pathlib import Path
from typing import Any

from app.material.docread._compat import ToolContext, ToolResult
from app.docgen.runtime import RUNNERS as DOCGEN_RUNNERS, execute_docgen
from app.runtime.models import ToolContext as RuntimeToolContext, ToolExecuteResponse

_MAX_OUTPUT = 32 * 1024 * 1024
_MAX_OUTPUT_TOTAL = 64 * 1024 * 1024
_MAX_EXPANDED = 128 * 1024 * 1024
_MAX_XLSX_CELLS = 200_000
_MAX_PDF_PAGES = 200
_MAX_RESULT = 16_000
_PATH_KEYS = frozenset({"file_path", "file_path_2", "source_path", "source_files", "merge_files", "output_path", "output_dir", "artifact", "left_artifact", "right_artifact", "datasets", "tables", "data", "left_data", "right_data"})
_WRITE_OPERATIONS = {
    "text_processor": {"write", "append", "prepend", "insert", "replace", "transform", "join"},
    "markdown_processor": {"write", "create", "append", "prepend", "insert_section",
                           "replace_section", "delete_section", "format", "build_table",
                           "build_list", "merge", "template"},
    "csv_processor": {"write"},
}


@contextmanager
def _open_workspace(workspace_root: str):
    if not workspace_root or not Path(workspace_root).is_absolute():
        raise ValueError("共享工作区路径无效")
    root = Path(workspace_root).resolve()
    if not (root / "inputs").is_dir() or not (root / "outputs").is_dir():
        raise ValueError("共享工作区不可用，请检查 Java/Python 的目录挂载")
    yield root


def _tool(name: str):
    """延迟加载解析内核，缺少 OCR 依赖不会阻断其余十五个工具。"""
    if name in DOCGEN_RUNNERS:
        return name
    from app.material.docread.csv_processor import CSVProcessorTool
    from app.material.docread.excel_reader import ExcelReaderTool
    from app.material.docread.html_processor import HTMLProcessorTool
    from app.material.docread.markdown_processor import MarkdownProcessorTool
    from app.material.docread.pdf_reader import PDFReaderTool
    from app.material.docread.text_processor import TextFileProcessorTool
    from app.material.docread.word_reader import WordReaderTool
    from app.material.dataprep.data_aggregate import DataAggregateTool
    from app.material.dataprep.data_clean import DataCleanTool
    from app.material.dataprep.data_merge import DataMergeTool
    from app.material.dataprep.data_transform import DataTransformTool
    from app.material.dataprep.data_validate import DataValidateTool
    from app.material.dataprep.sql_query import SQLQueryTool

    classes = (CSVProcessorTool, ExcelReaderTool, HTMLProcessorTool, MarkdownProcessorTool,
               PDFReaderTool, TextFileProcessorTool, WordReaderTool, DataAggregateTool,
               DataCleanTool, DataMergeTool, DataTransformTool, DataValidateTool, SQLQueryTool)
    for cls in classes:
        if cls.name == name:
            return cls()
    if name in {"pdf_structure", "citation_extractor", "image_ocr"}:
        return name
    raise ValueError("未知材料工具")


def _resolve(value: Any, key: str, root: Path, output_root: Path, mutate_source: bool) -> Any:
    """把 Java 已校验的相对路径定位到同一会话工作区。"""
    if isinstance(value, list):
        if key == "artifact" and all(isinstance(row, dict) for row in value):
            return value
        if key in {"data", "left_data", "right_data"} and all(isinstance(row, dict) for row in value):
            return value
        return [_resolve(item, key, root, output_root, mutate_source) for item in value]
    if isinstance(value, dict):
        if key == "tables":
            return {name: _resolve(item, "artifact", root, output_root, False) for name, item in value.items()}
        if _has_locator(value):
            raise ValueError("材料输入不接受 URI 或存储定位信息")
        if key in {"artifact", "left_artifact", "right_artifact", "datasets"}:
            raise ValueError("材料输入需使用已授权的文件引用或内联行记录")
        if key in {"data", "left_data", "right_data", "datasets"}:
            return value
        return {name: _resolve(item, name, root, output_root, mutate_source) for name, item in value.items()}
    if not isinstance(value, str):
        return value
    if key in _PATH_KEYS:
        relative = Path(value.replace("\\", "/"))
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("材料路径超出工作区")
        target = (root / relative).resolve()
        if not target.is_relative_to(root):
            raise ValueError("材料路径超出工作区")
        if key in {"output_path", "output_dir"} or (key == "file_path" and not target.exists()):
            if not target.is_relative_to(output_root):
                raise ValueError("生成文件只能写入 outputs/")
            target.parent.mkdir(parents=True, exist_ok=True)
        elif not target.is_file():
            raise ValueError("材料文件不存在")
        if key == "file_path" and mutate_source and target.is_relative_to(root / "inputs"):
            writable = output_root / target.name
            if not writable.exists():
                shutil.copyfile(target, writable)
            return str(writable)
        return str(target)
    return value


def _has_locator(value: Any) -> bool:
    """拒绝嵌套对象伪造私有定位，尤其是 SQL 命名表与左右数据集。"""
    if isinstance(value, dict):
        return any(key in {"uri", "storageKey", "storageType", "storageRef", "artifactId"}
                   or _has_locator(item) for key, item in value.items())
    if isinstance(value, list):
        return any(_has_locator(item) for item in value)
    return False


def _sanitize(value: Any, root: Path) -> Any:
    """剥离内核返回的临时绝对路径，保留页码、行数和样例等决策信息。"""
    if isinstance(value, dict):
        return {key: _sanitize(item, root) for key, item in value.items()
                if key not in {"uri", "storageKey", "storageRef", "workspace_root"}}
    if isinstance(value, list):
        return [_sanitize(item, root) for item in value[:200]]
    if isinstance(value, str):
        if value.startswith("file://"):
            value = value[7:]
        value = value.replace(str(root) + os.sep, "")
        if re.search(r"(?:[A-Za-z]:[/\\]|file://|minio://|s3://)", value):
            return "[内部路径已隐藏]"
        return value[:_MAX_RESULT]
    return value


def _check_expansion(name: str, clean: dict[str, Any]) -> None:
    """在进入 master 解析循环前约束高压缩比 OOXML 和超大页数。"""
    if name == "excel_reader":
        path = Path(clean["file_path"])
        if path.suffix.lower() in {".xlsx", ".xlsm"} and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as package:
                if sum(item.file_size for item in package.infolist()) > _MAX_EXPANDED:
                    raise ValueError("Excel 解压展开量超过材料预算")
        from openpyxl import load_workbook
        workbook = load_workbook(path, read_only=True, data_only=True)
        try:
            sheet_name = clean.get("sheet_name")
            sheet = workbook[sheet_name] if sheet_name else workbook.worksheets[int(clean.get("sheet_index", 0))]
            cell_range = clean.get("cell_range")
            if cell_range:
                from openpyxl.utils import range_boundaries
                first_col, first_row, last_col, last_row = range_boundaries(cell_range)
                cells = (last_col - first_col + 1) * (last_row - first_row + 1)
            else:
                cells = sheet.max_row * sheet.max_column
            if cells > _MAX_XLSX_CELLS:
                raise ValueError("Excel 读取范围超过 20 万单元格，请指定 cell_range")
        finally:
            workbook.close()
    if name in {"pdf_reader", "pdf_structure", "citation_extractor"}:
        import fitz
        path = clean.get("file_path")
        if path:
            with fitz.open(path) as document:
                if len(document) > _MAX_PDF_PAGES:
                    raise ValueError("PDF 页数超过材料预算，请拆分文件")


def execute_material(name: str, arguments: dict[str, Any], context: RuntimeToolContext,
                     workspace_root: str) -> ToolExecuteResponse:
    """解析共享工作区中的文件，并把新输出路径交给 Java 登记。"""
    try:
        tool = _tool(name)
        with _open_workspace(workspace_root) as root:
            input_root, output_root = root / "inputs", root / "outputs"
            before = {path.relative_to(output_root): (path.stat().st_size, path.stat().st_mtime_ns)
                      for path in output_root.rglob("*") if path.is_file() and not path.is_symlink()}
            operation = str(arguments.get("operation") or "read").lower()
            mutate_source = operation in _WRITE_OPERATIONS.get(name, set())
            if name == "csv_processor" and operation == "write" and not str(arguments.get("file_path", "")).startswith("outputs/"):
                raise ValueError("CSV 写入目标必须是 outputs/ 下的文件")
            clean = {}
            for key, value in arguments.items():
                # 文本/CSV 写入的 data 是正文或行记录，只有 dataprep 的同名字段才是文件引用。
                if name in DOCGEN_RUNNERS and key not in {"output_path", "source_path"}:
                    clean[key] = value
                elif key == "data" and name in {"text_processor", "csv_processor"}:
                    clean[key] = value
                else:
                    clean[key] = _resolve(value, key, root, output_root, mutate_source)
            if name == "pdf_reader":
                from app.material.docread.pdf_reader import normalize_pdf_reader_params
                clean = normalize_pdf_reader_params(clean)
                if clean.get("operation") == "merge" and "file_path" not in clean:
                    merged = clean.get("merge_files") or []
                    if merged:
                        clean["file_path"] = merged[0]
                if clean.get("operation") in {"extract_images", "convert_to_images", "split"} and not clean.get("file_path"):
                    raise ValueError("PDF 生成操作需要明确的已授权输入文件")
                if clean.get("operation") in {"extract_images", "convert_to_images", "split"}:
                    clean.setdefault("output_dir", str(output_root / "pdf_pages"))
                if clean.get("operation") in {"merge", "extract_pages"}:
                    clean.setdefault("output_path", str(output_root / (
                        "merged.pdf" if clean.get("operation") == "merge" else "extracted.pdf")))
            if name == "csv_processor" and operation == "write" and "file_path" not in clean:
                raise ValueError("CSV 写入需要指定 outputs/ 下的目标文件")
            if name == "sql_query":
                query = str(clean.get("query") or "")
                if ";" in query or not re.match(r"^\s*(SELECT|WITH)\b", query, re.I):
                    raise ValueError("SQL 仅允许单条只读 SELECT/CTE 查询")
            if name in {"excel_reader", "pdf_reader", "pdf_structure", "citation_extractor"}:
                _check_expansion(name, clean)
            kernel_context = ToolContext(session_id=context.session_id, request_id=context.request_id,
                                         temp_dir=str(output_root), extra={"temp_dir": str(output_root)})
            if name in DOCGEN_RUNNERS:
                result = execute_docgen(name, clean, root, context.request_id)
            elif name == "pdf_structure":
                from app.material.docread.pdf_research_core import extract_structure
                result = extract_structure(clean["file_path"])
            elif name == "citation_extractor":
                from app.material.docread.pdf_research_core import extract_citations
                citations = extract_citations(clean["file_path"])
                result = {"citations": citations, "count": len(citations)}
            elif name == "image_ocr":
                from app.material.docread.image_ocr import run_image_ocr_sync
                result = run_image_ocr_sync(clean["file_path"], prompt=clean.get("prompt"), lang=clean.get("lang"))
            else:
                result = tool.execute_sync(clean, kernel_context)
            if isinstance(result, ToolResult):
                if not result.success:
                    raise ValueError(result.error or "材料解析失败")
                result = result.data
            # 内联表格不得因 observation 截断而被当作完整结果；转为可续用 spill。
            if name.startswith("data_") or name == "sql_query":
                if isinstance(result, dict) and isinstance(result.get("data"), (list, dict)):
                    estimated = len(json.dumps(result, ensure_ascii=False, default=str))
                    if estimated > _MAX_RESULT:
                        from app.material.dataprep._data.records import emit_records
                        records = result["data"]
                        if isinstance(records, dict):
                            columns = list(records)
                            if not all(isinstance(records[key], list) for key in columns):
                                raise ValueError("列式数据结果格式无效，无法安全持久化")
                            lengths = {len(records[key]) for key in columns}
                            if len(lengths) > 1:
                                raise ValueError("列式数据结果长度不一致，无法安全持久化")
                            records = [dict(zip(columns, values)) for values in zip(*(records[key] for key in columns))]
                        if not all(isinstance(row, dict) for row in records):
                            raise ValueError("行记录结果格式无效，无法安全持久化")
                        envelope = emit_records(records, kernel_context, op_name=name, force_spill=True)
                        result.update(envelope.to_dict())
                        result["data"] = None
                if isinstance(result, dict) and isinstance(result.get("valid_data"), list):
                    valid_rows = result["valid_data"]
                    if len(json.dumps(valid_rows, ensure_ascii=False, default=str)) > _MAX_RESULT:
                        from app.material.dataprep._data.records import emit_records
                        envelope = emit_records(valid_rows, kernel_context,
                                                op_name="data_validate_valid", force_spill=True)
                        result["valid_artifact"] = envelope.artifact.to_dict()
                        result["valid_preview"] = _sanitize(valid_rows[:10], root)
                        result.pop("valid_data", None)
                if isinstance(result, dict) and isinstance(result.get("preview"), list):
                    # 上游默认预览可包含超长单元格；模型只看有界样例，完整行仍在 spill 文件。
                    result["preview"] = _sanitize(result["preview"][:10], root)
            material_outputs: list[dict[str, str]] = []
            stored_by_path: dict[Path, dict[str, str]] = {}
            output_bytes = 0
            for path in sorted(output_root.rglob("*")):
                if path.is_symlink():
                    raise ValueError("生成文件包含符号链接")
                if not path.is_file():
                    continue
                stat = path.stat()
                relative = path.relative_to(output_root)
                if before.get(relative) == (stat.st_size, stat.st_mtime_ns):
                    continue
                size = path.stat().st_size
                output_bytes += size
                if len(material_outputs) >= 40 or size == 0 or size > _MAX_OUTPUT or output_bytes > _MAX_OUTPUT_TOTAL:
                    raise ValueError("生成文件超出数量或大小预算")
                import uuid
                output = {"artifactId": uuid.uuid4().hex, "fileName": path.name,
                          "relativePath": path.relative_to(root).as_posix()}
                material_outputs.append(output)
                stored_by_path[path.resolve()] = output
            if isinstance(result, dict):
                # 校验 spill 确已进入正式存储；不能因匹配失败而丢弃 URI 后假报成功。
                for field in ("artifact", "valid_artifact"):
                    ref = result.get(field)
                    if not isinstance(ref, dict) or "uri" not in ref:
                        continue
                    spill = ref["uri"]
                    if not isinstance(spill, str) or not spill.startswith("file://"):
                        raise ValueError("数据处理结果未使用受控 spill 文件")
                    path = Path(spill[7:]).resolve()
                    if not path.is_relative_to(output_root) or not path.is_file():
                        raise ValueError("数据处理 spill 文件不在受控目录")
                    matching = stored_by_path.get(path)
                    if matching is None:
                        raise ValueError("数据处理 spill 文件未能持久化")
                    result[field] = {"artifactId": matching["artifactId"],
                                     "kind": ref.get("kind", "jsonl")}
            safe = _sanitize(result, root)
            encoded = json.dumps(safe, ensure_ascii=False, default=str)
            observation = encoded[:_MAX_RESULT]
            if len(encoded) > _MAX_RESULT:
                observation += f"…[结果已截断，原长度 {len(encoded)} 字符；请缩小读取范围或使用已登记 Artifact 继续处理]"
            output_ids = [item["artifactId"] for item in material_outputs]
            if output_ids:
                observation += "\n生成的 Artifact ID: " + ", ".join(output_ids)
                observation += "\n生成文件路径: " + ", ".join(item["relativePath"] for item in material_outputs)
                observation += "\n本轮可直接用上述路径继续处理；后续 Run 可使用 Artifact ID。"
            summary = safe if len(encoded) <= _MAX_RESULT else _bounded_summary(safe)
            return ToolExecuteResponse(success=True, output=observation, llmObservation=observation,
                                       structured={"result": summary},
                                       materialOutputs=material_outputs)
    except Exception as exc:
        # 不向模型或客户端泄露真实路径、凭据及内部异常栈。
        message = str(exc)
        if os.path.isabs(message) or "material-" in message:
            message = "材料解析失败，请检查文件格式、参数与服务依赖"
        return ToolExecuteResponse(success=False, status="failed", output=message,
                                   llmObservation=message, errorCode="material_tool_failed",
                                   errorMessage=message)


def _bounded_summary(result: Any) -> dict[str, Any]:
    """长结果保留可续用身份与定位规模，不向 Ledger 写入无界结构化正文。"""
    summary: dict[str, Any] = {"truncated": True}
    if isinstance(result, dict):
        for key in ("artifact", "valid_artifact", "row_count", "rows_before", "rows_after",
                    "page_count", "total_pages", "file_name", "operation", "spilled"):
            if key in result and len(json.dumps(result[key], ensure_ascii=False, default=str)) <= 2000:
                summary[key] = result[key]
    return summary

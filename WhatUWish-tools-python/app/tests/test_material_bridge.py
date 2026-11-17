"""材料工具共享工作区、解析与稳定结果回归。"""

import asyncio
import io
import json
import shutil
import tempfile
from pathlib import Path

import httpx
import pytest

from app.main import app
from app.material.catalog import catalog
from app.material.service import execute_material as execute_in_workspace
from app.runtime.artifact_storage import reset_artifact_storage_runtime
from app.runtime.models import ToolContext


@pytest.fixture(autouse=True)
def isolated_storage(tmp_path, monkeypatch):
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_TYPE", "local_artifact_storage")
    monkeypatch.setenv("AI_AGENT_ARTIFACT_STORAGE_DIR", str(tmp_path / "artifacts"))
    reset_artifact_storage_runtime()
    yield
    reset_artifact_storage_runtime()
    for root in _WORKSPACES:
        shutil.rmtree(root, ignore_errors=True)
    _WORKSPACES.clear()
    _OUTPUT_PATHS.clear()


def _context() -> ToolContext:
    return ToolContext(sessionId="material-session", runId="material-run",
                       requestId="material-request", toolCallId="material-call")


_WORKSPACES: list[Path] = []
_OUTPUT_PATHS: dict[str, Path] = {}


def test_java_material_catalog_matches_executable_python_schema():
    resource = (Path(__file__).resolve().parents[3] / "WhatUWish-domain"
                / "src/main/resources/material-tool-catalog.json")
    definitions = json.loads(resource.read_text(encoding="utf-8"))
    assert definitions == catalog()
    assert definitions["pdf_reader"]["params"]["required"] == []
    assert "本轮相对路径" not in json.dumps(definitions, ensure_ascii=False)
    assert "minio://" not in json.dumps(definitions, ensure_ascii=False)


def _workspace() -> Path:
    root = Path(tempfile.mkdtemp(prefix="material-test-")).resolve()
    (root / "inputs").mkdir()
    (root / "outputs").mkdir()
    _WORKSPACES.append(root)
    return root


def execute_material(name, arguments, context, delivered):
    """现有解析回归以测试文件构造工作区，不经过 HTTP 文件上传。"""
    root = _workspace()
    paths = {}
    for index, (filename, content) in enumerate(delivered):
        path = root / "inputs" / f"{index}-{filename}"
        path.write_bytes(content)
        paths[f"@input/{index}"] = path.relative_to(root).as_posix()

    def replace(value):
        if isinstance(value, str):
            if value.startswith("@output/"):
                return "outputs/" + value[len("@output/"):]
            return paths.get(value, value)
        if isinstance(value, list):
            return [replace(item) for item in value]
        if isinstance(value, dict):
            return {key: replace(item) for key, item in value.items()}
        return value

    response = execute_in_workspace(name, replace(arguments), context, str(root))
    for item in response.material_outputs:
        _OUTPUT_PATHS[item["artifactId"]] = root / item["relativePath"]
    return response


def _output_content(response, index=0):
    return _OUTPUT_PATHS[response.material_outputs[index]["artifactId"]].read_bytes()


def test_material_route_requires_service_token_before_parsing_request(monkeypatch):
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "test-only-secret")

    async def invoke(headers):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                    base_url="http://test") as client:
            return await client.post("/api/v1/material/execute", headers=headers,
                                     content=b"untrusted request")

    assert asyncio.run(invoke({})).status_code == 403
    assert asyncio.run(invoke({"X-Material-Bridge-Token": "wrong"})).status_code == 403
    assert asyncio.run(invoke({"X-Material-Bridge-Token": "test-only-secret"})).status_code == 422


def test_material_route_uses_python_local_config_without_environment_token(monkeypatch):
    monkeypatch.delenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", raising=False)
    monkeypatch.setattr("app.main.load_application_configuration",
                        lambda: {"material-bridge": {"token": "local-config-token"}})

    async def invoke(token):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                    base_url="http://test") as client:
            return await client.post("/api/v1/material/execute",
                                     headers={"X-Material-Bridge-Token": token},
                                     content=b"untrusted request")

    assert asyncio.run(invoke("wrong")).status_code == 403
    assert asyncio.run(invoke("local-config-token")).status_code == 422


def test_material_route_reads_shared_workspace_without_upload(monkeypatch):
    monkeypatch.setenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "test-only-secret")
    root = _workspace()
    (root / "inputs/sales.csv").write_bytes(b"name,amount\nAda,4\n")

    async def invoke():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                    base_url="http://test") as client:
            return await client.post("/api/v1/material/execute",
                headers={"X-Material-Bridge-Token": "test-only-secret"},
                json={"toolName": "csv_processor", "arguments": {
                    "operation": "read", "file_path": "inputs/sales.csv"},
                    "context": _context().model_dump(by_alias=True),
                    "workspaceRoot": str(root)})

    response = asyncio.run(invoke())
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["success"] is True
    assert "Ada" in payload["llmObservation"]
    assert "storageKey" not in response.text


def test_csv_read_and_nested_locator_rejection():
    result = execute_material("csv_processor", {"operation": "read",
        "file_path": "@input/0"}, _context(), [("sales.csv", b"name,amount\nAlice,12\nBob,5\n")])
    assert result.success and "Alice" in result.llm_observation
    denied = execute_material("sql_query", {"query": "SELECT * FROM data",
        "tables": {"data": {"uri": "file:///private/secret.csv"}}}, _context(), [])
    assert not denied.success
    assert "private" not in denied.llm_observation


def test_material_rejects_path_outside_shared_workspace(tmp_path):
    root = _workspace()
    outside = tmp_path / "private.csv"
    outside.write_bytes(b"secret")
    response = execute_in_workspace("csv_processor", {"operation": "read",
        "file_path": str(outside)}, _context(), str(root))
    assert not response.success
    assert "工作区" in response.llm_observation


def test_pdf_word_and_excel_original_bytes_are_parsed():
    import fitz
    from docx import Document
    from openpyxl import Workbook

    pdf = fitz.open()
    page = pdf.new_page()
    page.insert_text((72, 72), "PDF material fact")
    pdf_bytes = pdf.tobytes()
    pdf.close()
    document = Document()
    document.add_heading("Word material fact", 1)
    word_file = io.BytesIO()
    document.save(word_file)
    workbook = Workbook()
    workbook.active.append(["person", "score"])
    workbook.active.append(["Ada", 9])
    excel_file = io.BytesIO()
    workbook.save(excel_file)

    cases = (
        ("pdf_reader", "facts.pdf", pdf_bytes, "PDF material fact"),
        ("pdf_structure", "facts.pdf", pdf_bytes, "page_count"),
        ("citation_extractor", "facts.pdf", pdf_bytes, "citations"),
        ("word_reader", "facts.docx", word_file.getvalue(), "Word material fact"),
        ("excel_reader", "facts.xlsx", excel_file.getvalue(), "Ada"),
    )
    for tool, filename, content, expected in cases:
        response = execute_material(tool, {"file_path": "@input/0"},
                                    _context(), [(filename, content)])
        assert response.success, (tool, response.error_message)
        assert expected in response.llm_observation, tool


def test_pdf_merge_accepts_master_merge_files_without_primary_file_path():
    import fitz

    document = fitz.open()
    document.new_page().insert_text((72, 72), "Merge fact")
    pdf_bytes = document.tobytes()
    document.close()
    response = execute_material("pdf_reader", {"operation": "merge",
        "merge_files": ["@input/0", "@input/1"]}, _context(),
        [("first.pdf", pdf_bytes), ("second.pdf", pdf_bytes)])
    assert response.success, response.error_message
    assert len(response.material_outputs) == 1


def test_pdf_read_respects_one_based_page_selection():
    import fitz

    document = fitz.open()
    document.new_page().insert_text((72, 72), "First page only")
    document.new_page().insert_text((72, 72), "Second page fact")
    content = document.tobytes()
    document.close()
    response = execute_material("pdf_reader", {"operation": "read", "file_path": "@input/0",
        "start_page": 2, "end_page": 2}, _context(), [("pages.pdf", content)])
    assert response.success, response.error_message
    assert "Second page fact" in response.llm_observation
    assert "First page only" not in response.llm_observation


def test_dataprep_spill_is_stable_and_can_be_reused():
    response = execute_material("data_clean", {"data": [{"name": "Ada", "amount": 4}],
        "operations": [{"type": "trim_whitespace"}], "force_spill": True}, _context(), [])
    assert response.success, response.error_message
    assert len(response.material_outputs) == 1
    artifact_id = response.material_outputs[0]["artifactId"]
    assert response.structured["result"]["artifact"]["artifactId"] == artifact_id
    content = _output_content(response)
    assert json.loads(content.decode("utf-8").strip())["amount"] == 4
    continued = execute_material("sql_query", {"query": "SELECT SUM(amount) AS total FROM data",
        "artifact": "@input/0"}, _context(), [("continued.jsonl", content)])
    assert continued.success, continued.error_message
    assert '"total": 4' in continued.llm_observation


def test_material_output_returns_shared_path_without_encoded_bytes():
    response = execute_material("data_clean", {"data": [{"amount": 4}],
        "operations": [{"type": "trim_whitespace"}], "force_spill": True},
        _context(), [])
    assert response.success, response.error_message
    assert response.artifacts == []
    assert len(response.material_outputs) == 1
    output = response.material_outputs[0]
    assert output["artifactId"] == response.structured["result"]["artifact"]["artifactId"]
    assert output["relativePath"].startswith("outputs/")
    assert "contentBase64" not in output
    assert json.loads(_output_content(response).decode("utf-8").strip())["amount"] == 4


def test_later_read_uses_same_output_without_redeclaring_it():
    root = _workspace()
    written = execute_in_workspace("csv_processor", {"operation": "write",
        "file_path": "outputs/result.csv", "data": [{"name": "Ada"}]}, _context(), str(root))
    assert written.success and len(written.material_outputs) == 1
    read = execute_in_workspace("csv_processor", {"operation": "read",
        "file_path": "outputs/result.csv"}, _context(), str(root))
    assert read.success and "Ada" in read.llm_observation
    assert read.material_outputs == []


def test_large_inline_result_spills_instead_of_silent_truncation():
    records = [{"name": f"row-{index}", "description": "x" * 90} for index in range(200)]
    response = execute_material("data_clean", {"data": records,
        "operations": [{"type": "trim_whitespace"}]}, _context(), [])
    assert response.success, response.error_message
    assert response.structured["result"]["spilled"] is True
    assert response.structured["result"]["row_count"] == 200
    assert response.structured["result"]["artifact"]["artifactId"] == response.material_outputs[0]["artifactId"]


def test_large_valid_rows_are_spilled_without_losing_ledger_summary():
    records = [{"id": index, "note": "x" * 90} for index in range(200)]
    records.append({"id": None, "note": "invalid"})
    response = execute_material("data_validate", {"data": records,
        "schema": {"columns": {"id": {"required": True, "nullable": False}}},
        "return_valid_rows": True}, _context(), [])
    assert response.success, response.error_message
    assert response.structured["result"]["summary"]["invalid_rows"] == 1
    assert response.structured["result"]["valid_artifact"]["artifactId"] == response.material_outputs[0]["artifactId"]
    assert "valid_data" not in response.structured["result"]


def test_ocr_missing_configuration_and_mocked_multimodal_request(tmp_path, monkeypatch):
    from app.material.docread import image_ocr

    image = tmp_path / "scan.png"
    image.write_bytes(b"image-placeholder")
    monkeypatch.delenv("OCR_TYPE", raising=False)
    monkeypatch.setattr(image_ocr, "load_application_configuration", lambda: {"material-ocr": {}})
    with pytest.raises(RuntimeError, match="OCR 后端未配置"):
        image_ocr.run_image_ocr_sync(str(image))

    monkeypatch.setattr(image_ocr, "load_application_configuration", lambda: {
        "material-ocr": {"type": "vlm-ocr", "api-base": "https://ocr.example/v1",
                         "api-key": "private", "model": "vision"}})
    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "识别文字"}}]}

    class FakeClient:
        def __init__(self, **kwargs):
            captured["options"] = kwargs

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def post(self, url, **kwargs):
            captured["url"] = url
            captured["request"] = kwargs
            return FakeResponse()

    monkeypatch.setattr(image_ocr.httpx, "Client", FakeClient)
    result = image_ocr.run_image_ocr_sync(str(image), lang="ch")
    assert result["text"] == "识别文字"
    assert captured["url"] == "https://ocr.example/v1/chat/completions"
    assert captured["options"]["trust_env"] is False
    assert "中文文字" in captured["request"]["json"]["messages"][0]["content"][0]["text"]


def test_data_transform_accepts_arithmetic_and_rejects_python_escape():
    ordinary = execute_material("data_transform", {"data": [{"amount": 4}],
        "transformations": [{"type": "derive", "expression": "amount * 2",
                             "new_column": "double"}]}, _context(), [])
    assert ordinary.success, ordinary.error_message
    assert '"double": 8' in ordinary.llm_observation
    escaped = execute_material("data_transform", {"data": [{"amount": 4}],
        "transformations": [{"type": "derive",
                             "expression": "amount.__class__.__mro__",
                             "new_column": "unsafe"}]}, _context(), [])
    assert not escaped.success


def test_sql_rejects_mutating_cte_and_multiple_statements():
    for query in ("DELETE FROM data", "SELECT * FROM data; DROP TABLE data",
                  "WITH x AS (DELETE FROM data RETURNING *) SELECT * FROM x"):
        response = execute_material("sql_query", {"query": query,
            "data": [{"amount": 4}]}, _context(), [])
        assert not response.success, query


def test_master_fill_value_contract_is_preserved():
    from app.material.dataprep.data_clean import DataCleanTool

    properties = DataCleanTool().parameters["properties"]["operations"]["items"]["properties"]
    assert "fill_value" in properties and "value" not in properties
    result = execute_material("data_clean", {"data": [{"value": 1}, {"value": None}],
        "operations": [{"type": "fill_missing", "columns": ["value"], "fill_value": 0}]},
        _context(), [])
    assert result.success, result.error_message
    assert '"values_filled": 1' in result.llm_observation


def test_data_merge_aggregate_validate_and_named_sql_use_real_rows():
    left = [{"id": 1, "amount": 4}, {"id": 2, "amount": 6}]
    right = [{"id": 1, "category": "A"}, {"id": 2, "category": "B"}]
    merged = execute_material("data_merge", {"left_data": left, "right_data": right,
        "on": ["id"]}, _context(), [])
    assert merged.success, merged.error_message
    rows = merged.structured["result"]["data"]
    assert len(rows) == 2 and rows[0]["category"] == "A"

    aggregated = execute_material("data_aggregate", {"data": rows,
        "operation": "groupby", "group_by": ["category"],
        "aggregations": {"amount": "sum"}}, _context(), [])
    assert aggregated.success, aggregated.error_message
    assert '"row_count": 2' in aggregated.llm_observation

    validated = execute_material("data_validate", {"data": rows,
        "schema": {"required_columns": ["id", "amount", "category"]}}, _context(), [])
    assert validated.success, validated.error_message

    queried = execute_material("sql_query", {"query":
        "SELECT SUM(s.amount) AS total FROM sales s JOIN labels l ON s.id = l.id",
        "tables": {"sales": left, "labels": right}}, _context(), [])
    assert queried.success, queried.error_message
    assert queried.structured["result"]["data"][0]["total"] == 10


def test_nested_dataset_tokens_are_resolved_for_concat_and_named_sql():
    first = b"id,amount\n1,4\n"
    second = b"id,amount\n2,6\n"
    concatenated = execute_material("data_merge", {"operation": "concat",
        "datasets": ["@input/0", "@input/1"]}, _context(),
        [("first.csv", first), ("second.csv", second)])
    assert concatenated.success, concatenated.error_message
    assert concatenated.structured["result"]["row_count"] == 2

    queried = execute_material("sql_query", {"query":
        "SELECT SUM(CAST(amount AS INTEGER)) AS total FROM sales",
        "tables": {"sales": "@input/0"}}, _context(), [("sales.csv", first)])
    assert queried.success, queried.error_message
    assert queried.structured["result"]["data"][0]["total"] == 4


def test_docread_html_markdown_and_text_smoke():
    cases = (("html_processor", "page.html", b"<html><body><p>Hello HTML</p></body></html>",
              "Hello HTML"),
             ("markdown_processor", "note.md", b"# Hello Markdown\nbody\n", "Hello Markdown"),
             ("text_processor", "note.txt", b"Hello text\n", "Hello text"))
    for tool, filename, content, expected in cases:
        response = execute_material(tool, {"operation": "read", "file_path": "@input/0"},
                                    _context(), [(filename, content)])
        assert response.success, (tool, response.error_message)
        assert expected in response.llm_observation


def test_write_creates_artifact_without_modifying_input():
    source = b"before\n"
    response = execute_material("text_processor", {"operation": "append",
        "file_path": "@input/0", "data": "after\n"}, _context(), [("note.txt", source)])
    assert response.success, response.error_message
    assert len(response.material_outputs) == 1
    assert source == b"before\n"
    assert b"after" in _output_content(response)
    assert response.material_outputs[0]["relativePath"].startswith("outputs/")


def test_mutating_prior_input_reuses_existing_output_copy():
    root = _workspace()
    source = root / "inputs" / "file-id-note.txt"
    source.write_text("original\n", encoding="utf-8")
    arguments = {"operation": "append", "file_path": "inputs/file-id-note.txt", "data": "first\n"}
    first = execute_in_workspace("text_processor", arguments, _context(), str(root))
    assert first.success, first.error_message
    second = execute_in_workspace("text_processor", {**arguments, "data": "second\n"},
                                  _context(), str(root))
    assert second.success, second.error_message
    assert source.read_text(encoding="utf-8") == "original\n"
    assert (root / "outputs" / source.name).read_text(encoding="utf-8") == "original\nfirst\nsecond\n"


def test_generated_csv_is_available_at_shared_path():
    response = execute_material("csv_processor", {"operation": "write",
        "file_path": "@output/summary.csv", "data": [{"name": "Ada"}]},
        _context(), [])
    assert response.success, response.error_message
    assert response.material_outputs[0]["relativePath"] == "outputs/summary.csv"
    assert b"Ada" in _output_content(response)


def test_csv_write_requires_controlled_output_path():
    response = execute_material("csv_processor", {"operation": "write",
        "file_path": "@output/table.csv", "data": [{"name": "Ada", "amount": 4}]},
        _context(), [])
    assert response.success, response.error_message
    assert len(response.material_outputs) == 1

    denied = execute_material("csv_processor", {"operation": "write",
        "file_path": "@input/0", "data": [{"name": "Ada"}]},
        _context(), [("input.csv", b"name\nBob\n")])
    assert not denied.success


def test_unsupported_or_corrupt_file_reports_failure_without_storage_locator():
    response = execute_material("excel_reader", {"file_path": "@input/0"},
                                _context(), [("fake.xlsx", b"not an excel file")])
    assert not response.success
    assert "material-" not in response.llm_observation


def test_legacy_word_tries_libreoffice_when_pyantiword_is_missing(monkeypatch, tmp_path):
    from app.material.docread import word_reader

    source = tmp_path / "legacy.doc"
    source.write_bytes(b"legacy document")
    monkeypatch.setattr(word_reader, "_read_legacy_doc_libreoffice", lambda _path: "Recovered text")
    assert word_reader._read_legacy_doc_binary(source) == ("Recovered text", "libreoffice")

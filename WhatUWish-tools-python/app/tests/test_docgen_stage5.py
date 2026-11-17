"""第 5A 阶段的真实文件生成与工作区交付回归。"""

from __future__ import annotations

import tempfile
import zipfile
from pathlib import Path

from app.material.service import execute_material
from app.runtime.models import ToolContext


def _run(name: str, arguments: dict) -> tuple[object, Path]:
    root = Path(tempfile.mkdtemp(prefix="docgen-stage5-")).resolve()
    (root / "inputs").mkdir()
    (root / "outputs").mkdir()
    context = ToolContext(sessionId="docgen-session", runId="docgen-run",
                          requestId="docgen-request", toolCallId="docgen-call")
    response = execute_material(name, arguments, context, str(root))
    return response, root


def test_document_pdf_and_docx_are_real_files():
    content = "# 中文研究摘要\n\n本季度收入增长 12%，建议继续观察。\n\n| 指标 | 数值 |\n| --- | ---: |\n| 收入 | 120 |"
    for fmt, signature in (("pdf", b"%PDF"), ("docx", b"PK")):
        response, root = _run("document_generate", {
            "output_path": f"outputs/report.{fmt}", "format": fmt,
            "title": "季度研究", "content": content,
        })
        assert response.success, response.error_message
        assert len(response.material_outputs) == 1
        output = root / response.material_outputs[0]["relativePath"]
        assert output.read_bytes().startswith(signature)
        assert response.material_outputs[0]["artifactId"] in response.llm_observation
        if fmt == "pdf":
            import fitz
            with fitz.open(output) as document:
                assert "中文研究摘要" in document[0].get_text()
                assert "收入" in document[0].get_text()
        else:
            from docx import Document
            document = Document(output)
            assert any("中文研究摘要" in paragraph.text for paragraph in document.paragraphs)


def test_html_markdown_and_filled_template_are_readable():
    for fmt, expected in (("html", "中文报告"), ("markdown", "中文报告")):
        response, root = _run("document_generate", {
            "output_path": f"outputs/report.{ 'md' if fmt == 'markdown' else 'html' }",
            "format": fmt, "content": "# 中文报告\n\n含有结论的正文。",
        })
        assert response.success, response.error_message
        text = (root / response.material_outputs[0]["relativePath"]).read_text(encoding="utf-8")
        assert expected in text and "结论" in text
    filled, root = _run("template_filler", {
        "template_source": "string", "template_string": "尊敬的 {{ name }}：报告已完成。",
        "variables": {"name": "张三"}, "output_path": "outputs/notice.txt",
    })
    assert filled.success, filled.error_message
    assert "张三" in (root / filled.material_outputs[0]["relativePath"]).read_text(encoding="utf-8")


def test_slides_excel_chart_and_checklist_generate_files():
    samples = (
        ("slides_generate", {"output_path": "outputs/deck.pptx", "title": "季度研究",
                             "slides": [{"layout": "title", "title": "季度研究", "subtitle": "增长分析"}]}, ".pptx"),
        ("excel_generator", {"output_path": "outputs/data.xlsx", "sheets": [
            {"name": "收入", "headers": ["季度", "金额"], "data": [["Q1", 120], ["Q2", 140]]}]}, ".xlsx"),
        ("chart_generator", {"output_path": "outputs/chart.png", "chart_type": "bar",
                             "data": {"categories": ["Q1", "Q2"], "series": [{"name": "收入", "values": [120, 140]}]}}, ".png"),
        ("checklist_generate", {"output_path": "outputs/checklist.md", "title": "交付检查",
                                "items": [{"text": "核对来源"}]}, ".md"),
    )
    for name, args, suffix in samples:
        response, root = _run(name, args)
        assert response.success, (name, response.error_message)
        output = root / response.material_outputs[0]["relativePath"]
        assert output.suffix == suffix and output.stat().st_size > 0
        if suffix in {".pptx", ".xlsx"}:
            assert zipfile.is_zipfile(output)
        if suffix == ".pptx":
            from pptx import Presentation
            deck = Presentation(output)
            assert len(deck.slides) >= 1
            assert any("季度研究" in shape.text for slide in deck.slides for shape in slide.shapes if shape.has_text_frame)
        if suffix == ".xlsx":
            from openpyxl import load_workbook
            workbook = load_workbook(output, read_only=True)
            assert workbook["收入"]["B2"].value == 120
            workbook.close()
        if suffix == ".png":
            from PIL import Image
            with Image.open(output) as chart:
                chart.verify()
        if name == "checklist_generate":
            text = output.read_text(encoding="utf-8")
            assert "交付检查" in text and "核对来源" in text


def test_template_theme_metadata_and_output_boundary(monkeypatch, tmp_path):
    monkeypatch.setenv("REACTOR_DOCGEN_HOME", str(tmp_path / "docgen-home"))
    listed, _ = _run("theme_designer", {"action": "list"})
    assert listed.success and not listed.material_outputs
    saved, _ = _run("document_template", {"action": "save", "name": "stage5-template",
        "kind": "document", "content": "# {{ title }}\n\n正文", "variables": [{"name": "title", "required": True}]})
    assert saved.success, saved.error_message
    templates, _ = _run("document_template", {"action": "list"})
    assert templates.success and "stage5-template" in templates.llm_observation
    loaded, _ = _run("document_template", {"action": "get", "name": "stage5-template"})
    assert loaded.success and "{{ title }}" in loaded.llm_observation
    preview, _ = _run("document_template", {"action": "preview", "name": "stage5-template",
        "values": {"title": "预览"}})
    assert preview.success and "预览" in preview.llm_observation and not preview.material_outputs
    missing_value, _ = _run("document_template", {"action": "generate", "name": "stage5-template",
        "output_path": "outputs/missing.pdf"})
    assert not missing_value.success and not missing_value.material_outputs
    theme, _ = _run("theme_designer", {"action": "create", "name": "stage5-brand",
        "kind": "document", "primary": "#246B59"})
    assert theme.success and "stage5-brand" in theme.llm_observation
    theme_read, _ = _run("theme_designer", {"action": "get", "name": "stage5-brand"})
    assert theme_read.success and "stage5-brand" in theme_read.llm_observation
    themed, themed_root = _run("document_generate", {"output_path": "outputs/branded.html",
        "format": "html", "theme": "stage5-brand", "content": "# 主题文档\n\n品牌内容。"})
    assert themed.success, themed.error_message
    assert "品牌内容" in (themed_root / themed.material_outputs[0]["relativePath"]).read_text(encoding="utf-8")
    generated, root = _run("document_template", {"action": "generate", "name": "stage5-template",
        "values": {"title": "测试"}, "format": "markdown", "output_path": "outputs/from-template.md"})
    assert generated.success, generated.error_message
    assert (root / generated.material_outputs[0]["relativePath"]).is_file()
    removed, _ = _run("document_template", {"action": "delete", "name": "stage5-template"})
    assert removed.success and '"deleted": true' in removed.llm_observation.lower()
    theme_removed, _ = _run("theme_designer", {"action": "delete", "name": "stage5-brand"})
    assert theme_removed.success and '"deleted": true' in theme_removed.llm_observation.lower()
    invalid, _ = _run("document_generate", {"output_path": "../outside.pdf", "content": "# 错误"})
    assert not invalid.success and not invalid.material_outputs


def test_workspace_markdown_and_structured_images_are_embedded(tmp_path):
    import io
    from PIL import Image

    root = tmp_path / "workspace"
    (root / "inputs").mkdir(parents=True)
    (root / "outputs").mkdir()
    image = Image.new("RGB", (24, 16), "#246B59")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    (root / "inputs" / "figure.png").write_bytes(buffer.getvalue())
    context = ToolContext(sessionId="docgen-session", runId="docgen-run",
                          requestId="docgen-request", toolCallId="docgen-call")
    html = execute_material("document_generate", {
        "output_path": "outputs/figures.html", "format": "html",
        "content": "# 图片报告\n\n![图一](inputs/figure.png)",
    }, context, str(root))
    assert html.success, html.error_message
    html_text = (root / html.material_outputs[0]["relativePath"]).read_text(encoding="utf-8")
    assert "data:image/png;base64," in html_text
    pdf = execute_material("document_generate", {
        "output_path": "outputs/figures.pdf", "format": "pdf",
        "blocks": [{"type": "image", "path": "inputs/figure.png", "caption": "图一"}],
    }, context, str(root))
    assert pdf.success, pdf.error_message
    import fitz
    with fitz.open(root / pdf.material_outputs[-1]["relativePath"]) as document:
        assert document[0].get_images()


def test_file_template_includes_and_data_file_use_workspace(tmp_path):
    root = tmp_path / "workspace"
    includes = root / "inputs" / "includes"
    includes.mkdir(parents=True)
    (root / "outputs").mkdir()
    (root / "inputs" / "notice.html").write_text(
        "<h1>{{ title }}</h1>{% include 'closing.html' %}", encoding="utf-8")
    (includes / "closing.html").write_text("<p>确认完成</p>", encoding="utf-8")
    (root / "inputs" / "values.json").write_text('{"title":"中文通知"}', encoding="utf-8")
    context = ToolContext(sessionId="docgen-session", runId="docgen-run",
                          requestId="docgen-request", toolCallId="docgen-call")
    response = execute_material("template_filler", {
        "template_source": "file", "template_path": "inputs/notice.html",
        "include_paths": ["inputs/includes"], "data_file": "inputs/values.json",
        "output_format": "html", "output_path": "outputs/filled.html",
    }, context, str(root))
    assert response.success, response.error_message
    text = (root / response.material_outputs[0]["relativePath"]).read_text(encoding="utf-8")
    assert "中文通知" in text and "确认完成" in text
    escaped = execute_material("template_filler", {
        "template_source": "file", "template_path": "../private.html",
        "output_path": "outputs/should-not-exist.html",
    }, context, str(root))
    assert not escaped.success and not escaped.material_outputs

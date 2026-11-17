"""Document generation subsystem.

One document model, one font pipeline, one theme system, N renderers:

- :mod:`app.docgen.fonts` — guaranteed pan-Unicode font pipeline
  (env override → managed download dir → system scan → auto-download).
- :mod:`app.docgen.model` — typed Document / Deck IR (Pydantic v2).
- :mod:`app.docgen.markdown` — markdown → IR parser (markdown-it-py:
  GFM, math, footnotes, definition lists, front matter, callouts).
- :mod:`app.docgen.mathtext` — LaTeX math layout (matplotlib mathtext):
  native vector geometry (PDF) + raster fallback + Unicode fallback.
- :mod:`app.docgen.omml` — LaTeX → OMML for native, editable Word /
  PowerPoint equations (no rasterisation).
- :mod:`app.docgen.themes` — named themes (typography, palette, page geometry).
- :mod:`app.docgen.theming` — theme generation (brand seed -> palette),
  WCAG contrast lint, custom theme store.
- :mod:`app.docgen.templates` — reusable parameterized document/deck
  templates (Jinja2 placeholders, validated against the IR).
- :mod:`app.docgen.tables` — shared table engine (normalization, column
  type inference, number polish, total-row/delta semantics, style contract).
- :mod:`app.docgen.slides` — slide composition engine (deck type scale,
  layout regions, multi-level bullet plans, text autofit).
- :mod:`app.docgen.charts` — chart blocks rendered to PNG via matplotlib.
- :mod:`app.docgen.renderers` — PDF / DOCX / PPTX / HTML / Markdown renderers.

Agent-facing tools (``document_generate`` / ``slides_generate``) drive this package.
"""

# 统一中间表示连接解析、主题、字体和多格式渲染；工具入口不应绕过本包直接拼接文件。
from app.docgen.checklist import (
    build_checklist_block,
    checklist_stats,
    checklist_to_dict,
)
from app.docgen.fonts import FontManager, ResolvedFonts, get_font_manager
from app.docgen.mathtext import (
    MathVector,
    latex_lines,
    latex_to_unicode,
    math_vector_path,
    render_math_png,
)
from app.docgen.model import DeckSpec, DocumentSpec, SlideSpec
from app.docgen.omml import latex_to_omml_element, latex_to_omml_xml
from app.docgen.slides import (
    DeckTypography,
    SlideGeometry,
    fit_body_size,
    flatten_body,
)
from app.docgen.tables import (
    ProcessedTable,
    TableStyleSpec,
    process_table,
    resolve_table_style,
)
from app.docgen.templates import (
    DocTemplate,
    delete_template,
    list_templates,
    load_template,
    render_template,
    save_template,
)
from app.docgen.themes import Theme, get_theme, list_theme_names
from app.docgen.theming import (
    derive_theme_payload,
    lint_theme,
    save_custom_theme,
)

__all__ = [
    "FontManager",
    "ResolvedFonts",
    "get_font_manager",
    "DocumentSpec",
    "DeckSpec",
    "SlideSpec",
    "render_math_png",
    "math_vector_path",
    "MathVector",
    "latex_lines",
    "latex_to_unicode",
    "latex_to_omml_xml",
    "latex_to_omml_element",
    "DeckTypography",
    "SlideGeometry",
    "fit_body_size",
    "flatten_body",
    "ProcessedTable",
    "TableStyleSpec",
    "process_table",
    "resolve_table_style",
    "DocTemplate",
    "save_template",
    "load_template",
    "list_templates",
    "delete_template",
    "render_template",
    "Theme",
    "get_theme",
    "list_theme_names",
    "derive_theme_payload",
    "lint_theme",
    "save_custom_theme",
    "checklist_stats",
    "checklist_to_dict",
    "build_checklist_block",
]

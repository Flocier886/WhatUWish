"""公开 Report 核心主链的实体迁入。

函数顺序、格式分流、文件筛选、搜索结果展开、上下文截断和 Prompt 选择与公开
``report.py`` 对齐；仅由 Adapter 替换文件 I/O 和模型 I/O。
"""

from __future__ import annotations

import os
from datetime import datetime
from typing import Iterator, Literal

from app.report.artifact_adapter import ReportArtifactAdapter
from app.report.llm_adapter import ReportLlmAdapter
from app.report.models import ReportInputArtifact
from app.report.prompts import FIX_HTML_PROMPT, HTML_PROMPT, render_html_task, render_markdown_prompt, render_ppt_prompt


def report(
    task: str,
    file_names: list[ReportInputArtifact] | None = None,
    model: str = "report-runtime",
    file_type: Literal["markdown", "html", "ppt"] = "markdown",
    template_type: str = "html",
    *,
    artifact_adapter: ReportArtifactAdapter,
    llm_adapter: ReportLlmAdapter,
    context_length: int,
) -> Iterator[str]:
    """实体迁入 master ``report`` 的格式入口与分流。"""
    file_names = file_names or []
    report_factory = {
        "ppt": ppt_report,
        "markdown": markdown_report,
        "html": html_report,
    }
    if file_type.lower() not in report_factory:
        raise ValueError(f"unsupported report file_type: {file_type}")
    if file_type.lower() == "html":
        yield from html_report(
            task,
            file_names,
            model,
            template_type=template_type,
            artifact_adapter=artifact_adapter,
            llm_adapter=llm_adapter,
            context_length=context_length,
        )
    else:
        yield from report_factory[file_type](
            task,
            file_names,
            model,
            artifact_adapter=artifact_adapter,
            llm_adapter=llm_adapter,
            context_length=context_length,
        )


def ppt_report(
    task: str,
    file_names: list[ReportInputArtifact] | None = None,
    model: str = "report-runtime",
    temperature: float | None = None,
    top_p: float = 0.6,
    *,
    artifact_adapter: ReportArtifactAdapter,
    llm_adapter: ReportLlmAdapter,
    context_length: int,
) -> Iterator[str]:
    """实体迁入 master ``ppt_report`` 主链。"""
    files = artifact_adapter.download_all_files(file_names or [])
    flat_files: list[dict] = []

    # 1. 首先解析 md html 文件，没有这部分文件则使用全部
    filtered_files = [
        file_item
        for file_item in files
        if file_item["file_name"].split(".")[-1] in ["md", "html"]
        and not file_item["file_name"].endswith("_搜索结果.md")
    ] or files
    for file_item in filtered_files:
        # 对于搜索文件有结构，需要重新解析
        if file_item["file_name"].endswith("_search_result.txt"):
            flat_files.extend(artifact_adapter.flatten_search_file(file_item))
        else:
            flat_files.append(file_item)

    truncate_flat_files = artifact_adapter.truncate_files(flat_files, max_tokens=int(context_length * 0.8))
    prompt = render_ppt_prompt(task, truncate_flat_files, datetime.now().strftime("%Y-%m-%d"))

    yield from llm_adapter.ask_llm(
        messages=prompt,
        model=model,
        stream=True,
        temperature=temperature,
        top_p=top_p,
        only_content=True,
    )


def markdown_report(
    task: str,
    file_names: list[ReportInputArtifact] | None = None,
    model: str = "report-runtime",
    temperature: float = 0,
    top_p: float = 0.9,
    *,
    artifact_adapter: ReportArtifactAdapter,
    llm_adapter: ReportLlmAdapter,
    context_length: int,
) -> Iterator[str]:
    """实体迁入 master ``markdown_report`` 主链。"""
    files = artifact_adapter.download_all_files(file_names or [])
    flat_files: list[dict] = []
    for file_item in files:
        # 对于搜索文件有结构，需要重新解析
        if file_item["file_name"].endswith("_search_result.txt"):
            flat_files.extend(artifact_adapter.flatten_search_file(file_item))
        else:
            flat_files.append(file_item)

    truncate_flat_files = artifact_adapter.truncate_files(flat_files, max_tokens=int(context_length * 0.8))
    prompt = render_markdown_prompt(task, truncate_flat_files, datetime.now().strftime("%Y-%m-%d %H:%M:%S"))

    yield from llm_adapter.ask_llm(
        messages=prompt,
        model=model,
        stream=True,
        temperature=temperature,
        top_p=top_p,
        only_content=True,
    )


def html_report(
    task: str,
    file_names: list[ReportInputArtifact] | None = None,
    model: str = "report-runtime",
    temperature: float = 0,
    top_p: float = 0.9,
    template_type: str = "html",
    *,
    artifact_adapter: ReportArtifactAdapter,
    llm_adapter: ReportLlmAdapter,
    context_length: int,
) -> Iterator[str]:
    """实体迁入 master ``html_report`` 主链。"""
    files = artifact_adapter.download_all_files(file_names or [])
    key_files: list[dict] = []
    flat_files: list[dict] = []
    # 对于搜索文件有结构，需要重新解析
    for file_item in files:
        fpath = file_item["file_name"]
        fname = os.path.basename(fpath)
        if fname.split(".")[-1] in ["md", "txt", "csv"]:
            # CI 输出结果
            if "代码输出" in fname:
                # master 的 link 是原路径；本项目不允许将内部 storage locator 进入 Prompt。
                key_files.append({"content": file_item["content"], "description": fname, "type": "txt", "link": ""})
            # 搜索文件
            elif fname.endswith("_search_result.txt"):
                flat_files.extend(
                    {
                        "content": flattened["content"],
                        "description": flattened.get("title") or flattened["content"][:20],
                        "type": "txt",
                        # 仅保留搜索结果本身公开声明的 link；绝不回填 Artifact 内部 locator。
                        "link": flattened.get("link", ""),
                    }
                    for flattened in artifact_adapter.flatten_search_file(file_item)
                )
            # 其他文件
            else:
                flat_files.append({"content": file_item["content"], "description": fname, "type": "txt", "link": ""})
    discount = int(context_length * 0.8)
    key_files = artifact_adapter.truncate_files(key_files, max_tokens=discount)
    flat_files = artifact_adapter.truncate_files(
        flat_files,
        max_tokens=discount - sum(len(file_item["content"]) for file_item in key_files),
    )

    prompt = render_html_task(task, key_files, flat_files, datetime.now().strftime("%Y年%m月%d日"))

    if template_type == "fix":
        yield from llm_adapter.ask_llm(
            messages=[{"role": "system", "content": FIX_HTML_PROMPT}, {"role": "user", "content": prompt}],
            model=model,
            stream=True,
            temperature=temperature,
            top_p=top_p,
            only_content=True,
        )
    else:
        yield from llm_adapter.ask_llm(
            messages=[{"role": "system", "content": HTML_PROMPT}, {"role": "user", "content": prompt}],
            model=model,
            stream=True,
            temperature=temperature,
            top_p=top_p,
            only_content=True,
        )


def sanitize_report_html_content(content: str) -> str:
    """实体迁入 master ``report_file_util.sanitize_report_html_content``。"""
    normalized = (content or "").strip()
    if normalized.lower().startswith("html:"):
        normalized = normalized[5:].lstrip()
    if normalized.startswith("```html"):
        normalized = normalized[len("```html"):].lstrip()
    elif normalized.startswith("```"):
        normalized = normalized[len("```"):].lstrip()
    if normalized.endswith("```"):
        normalized = normalized[:-3].rstrip()
    return normalized

from typing import Any

from app.runtime.context import clip_text


def content_observation(
    *,
    title: str,
    path: str,
    content: str,
    limit: int,
    next_step: str,
) -> str:
    """为读取类工具生成包含真实内容预览的 observation。"""
    preview = content[:limit]
    line_count = len(content.splitlines())
    truncated = len(content) > limit
    lines = [
        f"{title}：{path}",
        f"内容长度：{len(content)} 字符，约 {line_count} 行" + ("，已截断" if truncated else ""),
        "",
        preview,
    ]
    if truncated:
        lines.append(f"\n后续建议：{next_step}")
    return "\n".join(lines)


def directory_observation(path: str, lines: list[str], entry_count: int, max_items: int = 80) -> str:
    """为目录工具生成包含目录项的 observation。"""
    visible = lines[: max_items + 1]
    truncated = len(lines) > len(visible)
    text = "\n".join(visible)
    result = [
        f"已列出目录：{path}",
        f"目录项数量：{entry_count}" + ("，展示结果已截断" if truncated else ""),
        "",
        text,
    ]
    if truncated:
        result.append("\n后续建议：缩小 path 或 maxDepth 后继续列出目录。")
    else:
        result.append("\n后续建议：根据目录项选择 file_read 或 file_search。")
    return "\n".join(result)


def search_observation(query: str, path: str, matches: list[dict[str, Any]], limit: int) -> str:
    """为搜索工具生成包含命中路径、行号和片段的 observation。"""
    if not matches:
        return (
            f"搜索已执行完成，但没有找到与“{query}”相关的内容。\n"
            f"搜索范围：{path}\n"
            "后续建议：调整关键词、扩大路径范围，或先用 directory_list 查看目录结构。"
        )
    visible = matches[:limit]
    lines = [
        f"搜索关键词：{query}",
        f"搜索范围：{path}",
        f"命中 {len(matches)} 条，展示 {len(visible)} 条：",
        "",
    ]
    for index, item in enumerate(visible, start=1):
        snippet = clip_text(str(item.get("snippet", "")), 180)
        lines.append(f"{index}. {item.get('relativePath')}:{item.get('line')} - {snippet}")
    if len(matches) > len(visible):
        lines.append("\n后续建议：缩小关键词或读取最相关文件附近内容。")
    else:
        lines.append("\n后续建议：优先读取命中文件，或直接基于片段继续整理答案。")
    return "\n".join(lines)


def web_results_observation(query: str, results: list[dict[str, Any]]) -> str:
    """为真实搜索结果生成有预算的来源摘要。"""
    lines = [f"网页搜索查询：{query}", f"返回 {len(results)} 条候选结果：", ""]
    for index, item in enumerate(results, start=1):
        lines.append(f"{index}. {item.get('title')}")
        lines.append(f"   来源: {item.get('domain')} ({item.get('provider')})")
        lines.append(f"   URL: {item.get('url')}")
        lines.append(f"   摘要: {clip_text(str(item.get('snippet', '')), 180)}")
    lines.append("\n后续建议：优先用 web_fetch 阅读最相关、最可信的公开来源正文。")
    return clip_text("\n".join(lines), 2800)


def script_observation(
    *,
    skill_name: str,
    action_name: str,
    runtime: str,
    success: bool,
    status: str,
    exit_code: int | None,
    stdout: str,
    stderr: str,
    summary: str,
    artifact_refs: list[dict[str, Any]],
    recovery_hint: str | None,
    error_code: str | None,
    error_message: str | None,
) -> str:
    """为 skill 脚本执行结果生成可供主智能体继续判断的 observation。"""
    lines = [
        f"脚本执行{'成功' if success else '失败'}：skill={skill_name}, action={action_name}",
        f"状态：{status}，运行时：{runtime}，退出码：{exit_code}",
    ]
    if error_code:
        lines.append(f"错误码：{error_code}")
    if error_message:
        lines.append(f"错误信息：{error_message}")
    if summary:
        lines.extend(["", "summary：", clip_text(summary, 1200)])
    if stdout:
        lines.extend(["", "stdout 摘要：", clip_text(stdout, 4000)])
    if stderr:
        lines.extend(["", "stderr 摘要：", clip_text(stderr, 4000)])
    if artifact_refs:
        lines.extend(["", "产物引用："])
        for item in artifact_refs:
            ref = item.get("artifactRef") or item.get("storageRef") or item.get("path") or item.get("artifactName")
            lines.append(f"- {ref}")
    if not success and recovery_hint:
        lines.extend(["", f"恢复建议：{recovery_hint}"])
    return "\n".join(lines)

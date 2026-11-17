from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[2]
WORKSPACE_ROOT = ROOT_DIR.parent.resolve()
DEFAULT_ARTIFACT_DIR = WORKSPACE_ROOT / "data" / "python-tool-artifacts"

DEFAULT_OUTPUT_LIMIT = 1200
DEFAULT_CONTENT_PREVIEW_LIMIT = 4000


def ensure_workspace_path(raw_path: str) -> Path:
    """将输入路径解析为工作区内的安全绝对路径。"""
    candidate = Path(raw_path)
    resolved = candidate.resolve() if candidate.is_absolute() else (WORKSPACE_ROOT / candidate).resolve()
    if resolved != WORKSPACE_ROOT and WORKSPACE_ROOT not in resolved.parents:
        raise ValueError(f"path is outside workspace: {raw_path}")
    return resolved


def ensure_artifact_dir() -> Path:
    """确保 Python 侧产物目录存在。"""
    DEFAULT_ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    return DEFAULT_ARTIFACT_DIR


def clip_text(text: str, limit: int = DEFAULT_OUTPUT_LIMIT) -> str:
    """裁剪返回给 LLM observation 的文本，避免输出过大。"""
    if len(text) <= limit:
        return text
    return f"{text[:limit]}...(truncated, total={len(text)})"

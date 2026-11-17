class ToolExecutionError(Exception):
    """Python 工具执行失败时抛出的统一异常。"""

    def __init__(self, error_code: str, error_message: str, diagnostics: dict[str, object] | None = None):
        super().__init__(error_message)
        self.error_code = error_code
        self.error_message = error_message
        # 仅保存可对外暴露的错误分类、状态码与重试事实；不得放入 prompt、密钥或上游原文。
        self.diagnostics = dict(diagnostics or {})

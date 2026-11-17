import json

from app.runtime.errors import ToolExecutionError
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool


class JsonExtractTool(BasePythonTool):
    """从 JSON 文本中抽取指定字段。"""

    name = "json_extract"
    description = "从 JSON 文本中抽取指定字段。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            content = self.require_argument(request.arguments, "content")
            fields = request.arguments.get("fields", [])
            if not isinstance(fields, list) or not fields:
                raise ToolExecutionError("invalid_argument", "fields 必须是非空数组")

            data = json.loads(content)
            result = {field: data.get(field) for field in fields}
            return self.success(
                output=json.dumps(result, ensure_ascii=False),
                structured=result,
                metadata={"fieldCount": len(fields)},
            )
        except ToolExecutionError as exc:
            return self.failure(exc.error_code, exc.error_message)
        except json.JSONDecodeError:
            return self.failure("invalid_json", "content 不是合法 JSON")


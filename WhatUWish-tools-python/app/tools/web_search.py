from app.runtime.errors import ToolExecutionError
from app.runtime.context import clip_text
from app.runtime.models import ToolExecuteRequest, ToolExecuteResponse
from app.tools.base import BasePythonTool
from app.tools.observation import web_results_observation
from app.tools.search_provider import create_search_provider, parse_domain_filters


class WebSearchTool(BasePythonTool):
    """通过受配置控制的真实 Provider 返回网页候选来源。"""

    name = "web_search"
    description = "通过真实搜索 Provider 返回网页候选来源。"

    def execute(self, request: ToolExecuteRequest) -> ToolExecuteResponse:
        try:
            query = self.require_argument(request.arguments, "query")
            limit = self.optional_int_argument(request.arguments, "limit", default=5, min_value=1, max_value=10)
            allowed, blocked = parse_domain_filters(request.arguments.get("allowed_domains"), request.arguments.get("blocked_domains"))
            provider = create_search_provider()
            search = provider.search_first_with_summary(query, limit, allowed, blocked)
            results = [hit.to_dict() for hit in search.hits]
            summary = clip_text(search.summary, 2000) if search.summary else None
            output = "\n".join(f"- {item['title']} ({item['domain']}): {item['url']}" for item in results)
            if summary:
                output = ("搜索摘要" + ("（无引用来源）" if not results else "") + f"：{summary}\n" + output).strip()
            observation = web_results_observation(query, results)
            if summary:
                observation += "\n搜索摘要" + ("（无引用来源，需进一步核查）" if not results else "") + f"：{summary}"
            return self.success(
                output=output or f"网页搜索完成，但没有找到与“{query}”匹配的公开来源。",
                llm_observation=observation,
                structured={"query": query, "results": results, **({"summary": summary} if summary else {})},
                metadata={"limit": limit, "resultCount": len(results),
                          "provider": search.provider or (results[0]["provider"] if results else None)},
            )
        except ToolExecutionError as exc:
            failures = exc.diagnostics.get("providerFailures", [])
            detail = "；上游状态：" + ", ".join(failures) if failures else ""
            return self.failure(exc.error_code, exc.error_message,
                                llm_observation=exc.error_message + detail)

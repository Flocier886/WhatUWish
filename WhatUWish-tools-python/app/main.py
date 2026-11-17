import logging
import os
import secrets

from fastapi import FastAPI
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.runtime.configuration import config_section, load_application_configuration


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)


app = FastAPI(
    title="WhatUWish Python Tool Service",
    version="0.1.0",
)

app.include_router(router)


def _material_bridge_token() -> str:
    override = os.getenv("AI_AGENT_MATERIAL_BRIDGE_TOKEN", "").strip()
    if override:
        return override
    settings = config_section(load_application_configuration(), "material-bridge")
    return str(settings.get("token") or "").strip()


@app.middleware("http")
async def authenticate_material_bridge(request, call_next):
    """材料工作区入口只接受已认证的 Java 服务调用。"""
    if request.url.path in {"/api/v1/material/execute", "/api/v1/material/code-interpreter/stream",
                            "/api/v1/material/desktop-session/open", "/api/v1/material/desktop-session/close"}:
        expected = _material_bridge_token()
        actual = request.headers.get("X-Material-Bridge-Token", "")
        if not expected or not secrets.compare_digest(actual, expected):
            return JSONResponse(status_code=403, content={"detail": "材料服务认证失败"})
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(request, exc: RequestValidationError):
    """将 Rich Tool 的入口校验失败收敛为不回显原始请求的协议。"""
    error_profiles = {
        "/api/v1/rich-tools/deep-search/stream": (
            "deep_search_request_invalid",
            "DeepSearch 请求不符合输入契约。",
            "DeepSearch",
        ),
        "/api/v1/rich-tools/report/stream": (
            "report_request_invalid",
            "Report 请求不符合输入契约。",
            "Report",
        ),
        "/api/v1/rich-tools/image-generation/stream": (
            "image_generation_request_invalid",
            "图片生成请求不符合输入契约。",
            "Image Generation",
        ),
        "/api/v1/material/code-interpreter/stream": (
            "code_interpreter_request_invalid",
            "代码分析请求不符合输入契约。",
            "Code Interpreter",
        ),
    }
    profile = error_profiles.get(request.url.path)
    if profile is None:
        return await request_validation_exception_handler(request, exc)
    error_code, message, tool_name = profile
    violations = []
    for error in exc.errors()[:8]:
        violation = {
            "field": _validation_field(error.get("loc")),
            "rule": _validation_rule(error.get("type")),
        }
        limit = _validation_limit(error.get("ctx"))
        if limit is not None:
            violation["limit"] = limit
        violations.append(violation)
    logging.getLogger(__name__).warning(
        "%s request validation failed: violationCount=%s fields=%s",
        tool_name,
        len(violations),
        [item["field"] for item in violations],
    )
    return JSONResponse(
        status_code=422,
        content={
            "errorCode": error_code,
            "message": message,
            "violations": violations,
        },
    )


def _validation_field(location) -> str:
    parts = [str(item) for item in location if str(item) != "body"] if isinstance(location, tuple | list) else []
    return ".".join(parts) if parts else "request"


def _validation_rule(error_type) -> str:
    value = str(error_type or "invalid")
    return value if value.replace("_", "").isalnum() else "invalid"


def _validation_limit(context) -> int | float | None:
    if not isinstance(context, dict):
        return None
    for name in ("max_length", "min_length", "ge", "le"):
        value = context.get(name)
        if isinstance(value, int | float) and not isinstance(value, bool):
            return value
    return None


def run_local_server() -> None:
    """以 application.yaml 的 server 配置启动本地服务。"""
    import uvicorn

    server = config_section(load_application_configuration(), "server")
    host = str(server.get("host", "127.0.0.1")).strip() or "127.0.0.1"
    try:
        port = int(server.get("port", 18081))
    except (TypeError, ValueError) as exc:
        raise RuntimeError("server.port 必须是整数") from exc
    uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    run_local_server()

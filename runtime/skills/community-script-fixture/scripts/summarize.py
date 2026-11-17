import json
from pathlib import Path
import sys


def main() -> int:
    """fixture happy path：读取 text，生成结构化摘要和 markdown 产物引用。"""
    payload = json.loads(sys.stdin.read() or "{}")
    args = payload.get("arguments") or {}
    text = str(args.get("text") or "").strip()
    if not text:
        # 缺少必填参数时用 JSON stdout 声明稳定错误码，方便 runner 保留业务语义。
        print(json.dumps({
            "status": "failed",
            "summary": "text is required",
            "structuredOutput": {},
            "artifactRefs": [],
            "recoveryHint": "Provide text in arguments.",
            "errorCode": "script_invalid_arguments",
            "errorMessage": "text is required"
        }, ensure_ascii=False))
        return 2

    artifact_name = str(args.get("artifact_name") or "summary").strip()
    # fixture 产物只写到 skill 包内 output 目录，便于路径边界和 ignore 规则验证。
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)
    artifact_path = output_dir / f"{artifact_name}.md"
    words = text.split()
    summary = " ".join(words[:40])
    artifact_path.write_text(f"# Summary\n\n{summary}\n", encoding="utf-8")
    artifact_ref = f"file://{artifact_path.as_posix()}"
    print(json.dumps({
        "status": "success",
        "summary": f"Generated markdown summary for {len(text)} characters.",
        "finalAnswer": summary,
        "structuredOutput": {
            "summary": summary,
            "inputLength": len(text)
        },
        "artifactRefs": [
            {
                "artifactRef": artifact_ref,
                "artifactName": artifact_path.name,
                "artifactType": "markdown",
                "role": "final",
                "producer": "script-action",
                "skillExecutionId": payload.get("skillExecutionId"),
                "actionName": payload.get("actionName"),
                "summary": "Generated markdown summary"
            }
        ],
        "recoveryHint": None,
        "errorCode": None,
        "errorMessage": None
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

import json
import sys


def main() -> int:
    """fixture failure path：模拟脚本依赖缺失，并显式返回 blocked 状态。"""
    payload = json.loads(sys.stdin.read() or "{}")
    print(json.dumps({
        "status": "blocked",
        "summary": "Required dependency is missing.",
        "structuredOutput": {},
        "artifactRefs": [],
        "recoveryHint": "Install the declared fixture dependency or adapt the package.",
        "errorCode": "script_dependency_missing",
        "errorMessage": "No module named fixture_missing_dependency",
        "skillExecutionId": payload.get("skillExecutionId"),
        "actionName": payload.get("actionName")
    }, ensure_ascii=False))
    return 3


if __name__ == "__main__":
    raise SystemExit(main())

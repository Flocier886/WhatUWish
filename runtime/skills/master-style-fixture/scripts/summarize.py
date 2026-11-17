import json
import sys
from pathlib import Path


def main() -> None:
    text = sys.argv[1] if len(sys.argv) > 1 else "master-style skill fixture"
    output_dir = Path("outputs")
    output_dir.mkdir(exist_ok=True)
    artifact_path = output_dir / "summary.txt"
    artifact_path.write_text(text[:200], encoding="utf-8")
    print(json.dumps({
        "status": "success",
        "summary": "Fixture script summarized the provided text.",
        "textLength": len(text),
        "outputPath": str(artifact_path).replace("\\", "/")
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

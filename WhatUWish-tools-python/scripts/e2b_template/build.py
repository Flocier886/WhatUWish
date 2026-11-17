"""Build the Reactor E2B template (requires E2B_API_KEY).

Usage:
  cd WhatUWish-tools-python
  python scripts/e2b_template/build.py

Then set in the ignored config/application.local.yaml:
  CODE_SANDBOX_BACKEND=e2b
  E2B_API_KEY=e2b_***
  E2B_TEMPLATE=reactor-code-playwright-desktop
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(_ROOT))
sys.path.insert(0, str(_SCRIPT_DIR))

from e2b import Template, default_build_logger  # noqa: E402
from app.code_execution.sandbox_backend_config import (  # noqa: E402
    get_e2b_proxy,
    require_e2b_api_key,
)

from template import TEMPLATE_ALIAS, template  # noqa: E402


def main() -> int:
    os.environ.setdefault("E2B_API_KEY", require_e2b_api_key())
    print(f"Building E2B template alias={TEMPLATE_ALIAS!r} ...")
    Template.build(
        template,
        TEMPLATE_ALIAS,
        cpu_count=2,
        memory_mb=4096,
        on_build_logs=default_build_logger(),
        proxy=get_e2b_proxy(),
    )
    print(
        f"\nDone. Set:\n"
        f"  E2B_TEMPLATE={TEMPLATE_ALIAS}\n"
        f"  CODE_SANDBOX_BACKEND=e2b\n"
        f"and restart reactor-tool.\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

---
name: master-style-fixture
description: A lightweight skill fixture for validating discovery, reference reading, and sandbox script execution.
---

# Master Style Fixture

Use this skill when validating the master-style skill runtime.

## Workflow

1. Use `skill_view(name="master-style-fixture")` to read this guide.
2. Inspect `skills/master-style-fixture/references/guide.md` with `workspace_read`.
3. Run `python skills/master-style-fixture/scripts/summarize.py "<text>"` with `bash`.

## Scripts

- `summarize`: accepts text as a command-line argument and writes `outputs/summary.txt`.

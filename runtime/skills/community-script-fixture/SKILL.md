---
name: community-script-fixture
description: 用于验证声明式脚本 action、结构化输出、artifact 引用和失败恢复语义。
version: 1.0.0
owner: local-fixture
---

# community-script-fixture

## 能力定位

该 skill 用于验证声明式 script action 的执行边界。它通过本地 Python 脚本接收结构化参数，返回结构化结果、恢复建议和可选 artifact 引用。

## 适用场景

- 验证脚本化 skill 的 happy path。
- 验证缺失依赖、参数错误、超时等失败语义。
- 验证社区风格 skill 能否被兼容性服务识别和归一化。

## 使用约束

- 只能执行 `skill.yaml` 或 `scripts.yaml` 中声明的脚本。
- 脚本路径必须位于当前 skill 包根目录内。
- 所有失败都必须返回结构化结果，而不是只返回 stdout/stderr。

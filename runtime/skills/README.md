# Runtime Skills

本目录保存本地可发现的 skill 包。当前推荐使用 master 风格轻量结构，让 agent 可以按需读取说明、检索资料并执行注册脚本。

## 推荐结构

```text
runtime/skills/<skill-name>/
|-- SKILL.md
|-- scripts.yaml
|-- scripts/
|-- references/
|-- assets/
`-- tools/
```

## 文件约定

- `SKILL.md` 必须存在，并在 front matter 中声明 `name` 和 `description`。
- `scripts/` 可放置 `.py`、`.js`、`.mjs`、`.cjs`、`.sh`、`.ps1`、`.bat`、`.cmd` 脚本。
- `scripts.yaml` 可显式声明脚本名称、路径、runtime 和描述；显式声明优先于自动扫描。
- `references/` 用于放 agent 可按需读取和搜索的资料，不会在 skill 列表阶段一次性注入。
- `assets/` 用于放图片、模板或静态资源。
- `tools/` 用于放包内辅助工具；除非被 `scripts.yaml` 或 `scripts/` 发现机制注册，否则不会自动暴露为可执行脚本。

## 安全边界

- 文件读取、列目录、glob 和 grep 都必须限制在目标 skill 包根目录内。
- 执行脚本时只能通过 `skill_name + script_name`，不能让模型直接传入任意路径。
- 无效包、重复名称、越界脚本路径和不支持 runtime 都应被拒绝或标记为不可用。

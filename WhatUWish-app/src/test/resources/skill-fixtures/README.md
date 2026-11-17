# Skill Fixtures

这里存放或说明 master 风格 skill runtime 的测试 fixture。

当前主要使用 `runtime/skills/master-style-fixture` 做端到端验证，覆盖：

- `SKILL.md` front matter 解析。
- `references/` 包内资料读取与搜索。
- `scripts.yaml` 注册脚本声明。
- `skill_script_runner_tool` 执行注册脚本。

测试 fixture 应保持轻量，不要依赖外部网络或大型文件。

# workspace-readme-summarizer

## 技能定位
这个 skill 用来帮助 Agent 读取某个 README 文档，提炼关键信息，并在需要时把整理结果保存为产物。

## 适用场景
- 先理解项目说明，再形成结构化摘要
- 需要把摘要结果保存下来，供后续 ledger、memory、replay 使用

## 建议用法
- 优先使用 `file_read` 读取明确路径的 README
- 如果任务要求保存结果，再使用 `artifact_save`
- 最终回答应说明读取结论和保存结果

## 注意事项
- 不要读取未明确指定的敏感文件
- 摘要要聚焦用途、结构和关键能力

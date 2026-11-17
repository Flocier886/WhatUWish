# Community Script Fixture

该 fixture 用于验证脚本化 skill：

- `summarize`：读取输入文本并生成结构化摘要，可写出 markdown artifact。
- `dependency_gap`：模拟缺失依赖，返回可恢复的 blocked 结果。
- `timeout`：模拟超时场景，验证 timeout 归一化。

该包只用于本地测试，不依赖外部网络。

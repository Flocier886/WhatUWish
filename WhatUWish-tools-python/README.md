# WhatUWish-tools-python

这是项目的 Python 工具服务工程，负责通过 FastAPI 暴露真实可调用的工具能力，供 Java 侧 Agent Runtime 通过 HTTP 接入。

## 目标

- 作为 Java Agent Runtime 的外部工具服务
- 提供统一的工具执行接口
- 为 Ledger、Artifact、Memory、Skill 等后续能力提供可结构化复用的工具结果

## 目录结构

```text
WhatUWish-tools-python/
  app/
    api/        # FastAPI 路由
    deepsearch/ # DeepSearch 节点、状态机与研究结论编排
    runtime/    # 工具执行上下文、模型、注册表
    tools/      # 真实工具实现
    tests/      # Python 侧最小测试
    main.py
  config/
    application.yaml       # 可提交的默认运行配置
    application.local.yaml # 本机密钥与覆盖项，Git 忽略
  start-local.ps1          # 读取 YAML 的本地启动入口
  requirements.txt
```

## 本地配置与启动

1. 安装依赖

```bash
pip install -r requirements.txt
```

2. 首次填写本机配置

直接编辑 `config/application.local.yaml`，取消注释并填写这两组真实凭证：

```yaml
web-search:
  api-key: "你的 Tavily API Key"

deepsearch-runtime:
  model: "你的模型名"
  api-base: "你的 OpenAI Compatible Base URL"
  api-key: "你的模型 API Key"
```

`application.local.yaml` 已被 Git 忽略，不能提交到仓库。服务会先读取
`application.yaml` 的安全默认项，再以该本机文件覆盖；环境变量最后覆盖两份
YAML，保留给 Docker、CI 和生产部署。日常本地开发不需要再手动设置环境变量。
新拉取的工作区第一次执行 `start-local.ps1` 时，会从
`config/application.local.example.yaml` 自动创建该本机文件并提示你填写。

3. 启动服务

在本目录运行：

```powershell
.\start-local.ps1
```

它自动使用 `.venv`、加载 YAML 中的 `server.host` 和 `server.port`，默认地址是
`http://127.0.0.1:18081`。

4. 健康检查

```bash
curl http://localhost:18081/api/v1/health
```

## 接口

- `GET /api/v1/health`
- `GET /api/v1/tools`
- `POST /api/v1/tools/execute`
- `POST /api/v1/material/execute`：Java 传入当前会话的 `workspaceRoot` 和相对文件路径，Python 直接读写共享目录
- `POST /api/v1/rich-tools/deep-search/stream`

材料工具要求 Java 与 Python 进程能看到同一绝对路径的会话工作区。Java 的
`AI_AGENT_FILE_WORKSPACE_ROOT` 默认为项目运行目录下的 `data/run-workspaces`；
本机分别启动时，两进程须访问同一磁盘。分容器部署时给两端挂载同一共享卷，
并把 Java 的 `AI_AGENT_FILE_WORKSPACE_ROOT` 设置为两容器中相同的绝对路径，
例如 `/data/run-workspaces`。材料请求仍使用两端相同的
`AI_AGENT_MATERIAL_BRIDGE_TOKEN` 认证；目录未共享时调用明确失败。
材料生成文件由 Java 从工作区写入配置的 Local/S3 Artifact Store，Python 不再
通过 HTTP 回传文件字节。
同一会话的 `inputs/` 与 `outputs/` 跨 Run 保留；Run 结束只释放上下文。
过期会话目录由 Java 定时回收，默认每日扫描一次，`workspace-cleanup-interval-ms`
可调整扫描间隔。多 Java 实例部署时还需保证同一会话只在一个实例上运行，
否则进程内的会话占用无法防止跨实例同时写同一目录。

## 当前工具清单

- `directory_list`
  - 查看工作区目录结构
  - 关键参数：`path`、`maxDepth`、`includeHidden`
- `file_search`
  - 在工作区中按关键词搜索文件内容
  - 关键参数：`query`、`path`、`limit`、`glob`
- `file_read`
  - 读取工作区内文本文件
  - 关键参数：`path`
- `file_write`
  - 向工作区内写入文本文件
  - 关键参数：`path`、`content`、`mode`
- `json_extract`
  - 从 JSON 文本中抽取指定字段
  - 关键参数：`content`、`fields`
- `artifact_save`
  - 保存文本产物并返回产物引用
  - 关键参数：`name`、`content`、`artifactType`
- `artifact_read`
  - 按路径或名称读取已保存产物
  - 关键参数：`path` 或 `name`
- `web_fetch`
  - 安全抓取单个公开网页正文，仅接受 HTML、Markdown 和纯文本响应
  - 关键参数：`url`、`timeoutSeconds`
- `web_search`
  - 调用已配置的真实 Search Provider，返回可供后续抓取的公开来源候选
  - 关键参数：`query`、`limit`
- `deep_search_tool`
  - 独立 Rich Research Agent，只通过 `POST /api/v1/rich-tools/deep-search/stream` 提供服务；它不注册到普通 `/api/v1/tools/execute` 工具目录
  - Java 仅负责路由、SSE 接收、Tool Invocation ledger 与面向 ReAct 的终态 observation；Python 负责查询需求分析、互补查询拆分、检索、安全抓取、有限补查与研究材料交付
  - Java 调用参数：`topic`、`questions`、`researchDepth`、`language`、`preferredDomains`。搜索与抓取预算由 Python 依据研究深度裁决，调用方不直接传递内部预算
  - `terminal_completed` / `terminal_partial` 都是当前研究范围的终态交付，主 ReAct 应消费结论、限制和 artifact，而不是重调同一范围

旧的 `research_gather_tool` 与 Research 多节点工作流已删除；DeepSearch 直接复用通用搜索与安全抓取能力，不再被当作可直接交付给用户的第二条调研链路。

## 网页检索与抓取配置

`web_search` 不提供占位结果。日常本地配置位于 `config/application.local.yaml`；可提交的
`config/application.yaml` 已给出 Tavily 的 Provider、API 地址和超时默认值。只需在本地文件填写
`web-search.api-key`，不需要反复输入 PowerShell 环境变量。

```yaml
web-search:
  api-key: "你的 Tavily API Key"
```

首个适配器使用 Tavily HTTP JSON 契约：向 `web-search.api-base` 发送 `POST`，以 `Authorization: Bearer` 传递凭证，提交 `query`、`max_results`、`search_depth` 与 `include_answer`，并读取响应中的 `results` 列表。Provider 凭证只存在于 Python 服务本机配置或部署环境，不能写入 Java 工具配置、Tool 结果、observation、日志摘要或 ledger。

在部署环境中，可继续使用 `WEB_SEARCH_PROVIDER`、`WEB_SEARCH_API_BASE`、`WEB_SEARCH_API_KEY` 和 `WEB_SEARCH_TIMEOUT_SECONDS` 覆盖 YAML。

未配置 Provider、认证失败、限流、请求超时或上游响应格式不正确时，`web_search` 返回明确的受控失败，例如 `search_provider_not_configured`；它不会把空结果或伪造 URL 伪装成成功。

`web_fetch` 的默认预算也可在 Python 服务侧调整：

```text
WEB_FETCH_MAX_RESPONSE_BYTES=2097152
```

抓取工具仅允许 HTTP(S) 公开目标：初始 URL 和每一次重定向都会执行协议、主机、DNS 和 IP 校验，拒绝 localhost、私网、环回、链路本地、保留及其他非公网地址。它手动限制最多 5 次重定向、请求超时、允许的文本 MIME 类型和流式响应字节数。成功提取的完整正文会登记为 artifact；首次 `llmObservation` 只保留最多 500 字符摘要和后续 `artifact_read` 指引。

## DeepSearch 调研配置

`deep_search_tool` 同时依赖上面的搜索 Provider 和 Python 自己的 DeepSearch Writer 配置。模型凭证应写在本机覆盖文件，不能从 Java 工具请求、Java YAML、ledger、SSE 或日志摘要传递：

```yaml
deepsearch-runtime:
  model: "你的模型名"
  api-base: "你的 OpenAI Compatible Base URL"
  api-key: "你的模型 API Key"
```

`application.yaml` 中的 `deepsearch-runtime` 定义模型调用、搜索、候选和抓取上限。最终 Writer 使用 `240s` 单次超时、`0` 次自动重试、`3200` 输出 token 和 `48000` 字符材料上限；它是多来源长文本综合，不使用普通短请求的 60 秒预算。`quick`、`standard`、`deep` 分别具有 `1/2/3` 轮检索机会，总预算为 `3/5/3`、`6/6/5`、`10/8/8`（查询数 / 每查询候选数 / 最大抓取数）；后续轮次只在 master reasoning 判断当前材料不足时发生，最终综合只执行一次。

容器或 CI 可使用 `DEEPSEARCH_LLM_*` 与 `DEEPSEARCH_*` 环境变量覆盖 YAML。为兼容已有部署，缺少新段时仍可读取旧 `research-runtime`，但新 `deepsearch-runtime` 优先；它不会读取 Java YAML、内存对象或数据库配置。

独立 Rich DeepSearch 的内部主链为：

```text
规范化 topic/questions
  -> master 查询需求分析与互补查询拆分
  -> 并发搜索、URL 去重和确定性选源
  -> 有界安全抓取
  -> master reasoning 有限补查（未达轮次且材料不足时）
  -> master answer 一次来源综合 Writer
  -> research_source_package Artifact
```

Writer 的最终写作 Prompt 直接迁入隔壁 master 的 `deepsearch.yaml#answer_prompt`，本项目只填充用户问题、来源材料、当前时间和输出长度四个占位符。Python 保留搜索、URL/SSRF 校验、预算、`sourceId`、安全抓取和 Artifact 写入权力，不再执行 Planner、LLM 选源、quote 映射、覆盖审查或多轮补查。

当 Writer 成功时，工具返回 `terminal_completed` 和研究材料 Artifact；当已抓取来源存在但 Writer 超时、网络失败、上游不可用或返回无效内容时，仍生成来源材料 Artifact 并返回 `terminal_partial`。终态只包含受控失败码和限制说明，不包含上游正文、Prompt、原始响应或凭证。

## 独立 Rich DeepSearch API

`POST /api/v1/rich-tools/deep-search/stream` 是 DeepSearch 的正式 Python 研究入口。请求使用 `DeepSearchStartRequest`，可选 `executionId` 仅作为不透明关联值回显；它不是 Java tool invocation、数据库主键或授权依据。接口以 SSE 依次发送 `progress`、`artifact`、`result` 与失败/取消的 `error`。每个阶段事件均带 `schemaVersion`、`eventId`、`executionId` 和单调递增 `sequence`。

事件、最终结果和 artifact descriptor 不会包含完整网页正文、完整 prompt、原始模型回答、token 明细、认证头、密钥、内部堆栈或本地绝对路径。artifact 只有在原子发布、大小/MIME/hash 已完成后才发送。SSE 断开会触发协作式取消；已经原子发布的 artifact 保留，尚未开始的 LLM、网络与文件副作用不会继续启动。

Python 仍掌握预算、搜索、SSRF 安全抓取、去重和 Artifact 写入。Java/前端的稳定契约保持为版本化 DeepSearch SSE/result、受控 presentation 与 Artifact 元数据；前端不消费 Python 内部 Prompt、抓取正文或存储 locator。

离线 Mock 测试验证协议、安全边界和降级语义，不代表真实模型研究质量。配置真实 Tavily 与 Writer 后，应至少人工检查：查询与来源组合是否合理、综合内容是否不超出来源材料、成功分支是否生成 Artifact，以及 Writer 受控失败时是否仍交付来源材料。

```yaml
# config/application.local.yaml
web-search:
  providers:
    - provider: tavily
      api-base: https://api.tavily.com/search
      api-key: "你的第一个搜索 API Key"
    # 当前实现只内置 Tavily adapter；新增其他 Provider 前不要写入未支持名称。
    - provider: tavily
      api-base: https://api.tavily.com/search
      api-key: "可选的第二个 Tavily Key"

# Jina Reader 是正文提取 Provider，不是搜索 Provider。默认 direct 保持现有安全直连抓取。
web-content:
  strategy: jina_reader
  reader-base-url: https://r.jina.ai/
  # reader-api-key: "仅当你的 Reader 套餐需要时填写"
  reader-timeout-seconds: 20
  reader-min-content-chars: 240

deepsearch-runtime:
  search-concurrency: 5
  fetch-concurrency: 4
```

启用 `jina_reader` 后，Python 仍会先校验原始公开 URL；Reader 超时、返回错误或正文过短时会安全回退到 `SafeWebFetcher` 直连。Reader 成功只表示获得了更可读的材料，不表示来源已经可信或已形成证据。

流式阶段事件是追加事实，当前主要包括：`research_started`、`search_started`、`search_completed`、`fetch_started`、`fetch_completed`、`artifact_created` 和终态事件。事件不包含网页全文、完整 Prompt、模型原文、token、密钥或绝对路径。Java Progress Ledger / Replay / 前端将以这些事实构建实时状态与完整历史，不能用完成事件覆盖开始事件。

真实验收前，先配置 Tavily 与 DeepSearch Writer，再以多来源问题运行一次 Rich API。验收时检查：查询、候选和抓取数量是否符合预算，综合完成时是否生成 Artifact，Writer 超时或不可用时是否以 `terminal_partial` 交付来源材料，以及 Prompt/网页正文是否没有出现在结果和事件中。离线 Mock 测试只保证协议和约束，不保证真实模型质量。

## 响应结构

所有工具统一返回：

```json
{
  "success": true,
  "output": "给 LLM 看的简洁 observation",
  "structured": {},
  "artifacts": [
    {
      "name": "report.md",
      "artifactType": "markdown",
      "artifactRole": "output",
      "visibility": "visible",
      "sourceType": "artifact_save",
      "sourceName": "artifact_save",
      "fileName": "report.md",
      "artifactId": "stable-artifact-id",
      "storageType": "local_artifact_storage",
      "storageRef": "session-a/run-a/stable-artifact-id-report.md",
      "storageKey": "session-a/run-a/stable-artifact-id-report.md",
      "contentSummary": "产物摘要",
      "mimeType": "text/markdown",
      "fileSize": 128,
      "metadata": {}
    }
  ],
  "errorCode": null,
  "errorMessage": null,
  "metadata": {}
}
```

## Artifact 存储配置

- `AI_AGENT_ARTIFACT_STORAGE_DIR`：真实 artifact 文件的稳定存储根目录，默认是 Python 工具项目工作区下的 `data/artifact-storage`。
- `AI_AGENT_ARTIFACT_RETENTION_SECONDS`：稳定 artifact 的保留秒数，默认 7 天；设置为 `0` 时关闭自动清理。

`artifact_save`、`file_write` 与 `skill_script_runner` 会把可复用文件保存为 `sessionId/runId/artifactId-fileName` 形式的 `storageKey`。`storageKey` 是跨进程和历史回放使用的稳定引用；`storageRef` 为兼容字段，不再承载临时绝对路径。`artifact_read` 优先接受 `storageKey` 或 `artifactId`，读取动作不会产生新的 artifact。

说明：

- `output`
  - 面向 ReAct observation，要求简洁、稳定、可继续推理
- `structured`
  - 面向程序消费，保留结构化字段；普通结构化结果不会自动视为产物
- `artifacts`
  - 面向账本与产物复用，只放文件写出、脚本输出、用户上传、工具显式生成的可寻址对象
- `metadata`
  - 面向调试、统计和扩展

Artifact 约定：

- Python 工具不直接访问 Java ledger 或数据库，只声明产物元信息。
- Java runtime 会在 tool invocation ledger 写入后统一收集 `artifacts`、skill runner 的 `generatedArtifacts`、map 型 `artifactRefs`，并写入 `artifact_ledger`。
- `artifact_read` 是读取动作，不返回新的 `artifacts`，避免把一次读取误记成新产物。
- `web_fetch` 的完整正文只保存在 artifact；首次 `llmObservation` 只保留最多 500 字符摘要、产物引用和读取建议。
- `artifact_read` 默认最多返回 5,000 字符，可用 `offset` 与 `limit` 分段继续读取；正文不会进入 canonical result。

## 测试建议

可以先分别验证：

1. Python 服务是否启动成功
2. `GET /api/v1/tools` 是否能看到全部工具
3. Java 侧 `PythonToolRuntimeLiveTest`
4. Java 侧 `ReActPythonToolFlowLiveTest`

## 一个适合联调的复杂提示词

```text
请先使用 directory_list 查看 WhatUWish-tools-python/app/tools 目录，
再使用 file_search 搜索 artifact_save 的实现位置，
接着读取对应文件并整理一段中文摘要，
然后使用 artifact_save 保存为 tool-summary.txt，
最后通过 artifact_read 回读这个产物并告诉我结果。
```

## Artifact Storage 配置

- `AI_AGENT_ARTIFACT_STORAGE_DIR`：稳定 artifact 存储根目录。
- `AI_AGENT_ARTIFACT_RETENTION_SECONDS`：保留秒数；`0` 表示关闭过期清理。
- `artifact_read` 的单次正文读取上限固定为 5,000 字符；`AI_AGENT_ARTIFACT_PREVIEW_LIMIT` 不再影响该工具的读取额度。
- Java 存储网关：`agent.artifact-storage.python-service`、`agent.artifact-storage.python-endpoint` 与 `agent.artifact-storage.preview-limit`。

import { expect, test, type Page } from '@playwright/test';

const sessionId = 'session-demo-001';
const runId = 'run-workbench-fixture';
const requestId = 'request-workbench-fixture';
const agentId = 'research-react-agent';
const occurredAt = '2026-08-16T10:00:00.000Z';

const event = (sequence: number, eventType: string, payload: Record<string, unknown>) => ({
  schemaVersion: 'v1', eventId: `fixture-event-${sequence}`, sequence, eventType,
  occurredAt, sessionId, runId, requestId, agentId, payload,
});

const events = [
  event(1, 'RUN_STARTED', { status: 'RUNNING', task: '验证运行工作台与 Artifact Reader' }),
  event(2, 'TOOL_STARTED', { toolInvocationId: 'tool-ordinary', toolName: 'skill_read_tool', status: 'RUNNING', association: 'PERSISTED', argumentsSummary: 'path=/workspace/input.md' }),
  event(3, 'TOOL_RESULT', { toolInvocationId: 'tool-ordinary', toolName: 'skill_read_tool', status: 'SUCCEEDED', association: 'PERSISTED', argumentsSummary: 'path=/workspace/input.md', observation: '已读取公开输入摘要。' }),
  event(4, 'TOOL_STARTED', { toolInvocationId: 'tool-grep', toolName: 'skill_grep_tool', status: 'RUNNING', association: 'PERSISTED', argumentsSummary: '搜索对象存储配置' }),
  event(5, 'TOOL_RESULT', { toolInvocationId: 'tool-grep', toolName: 'skill_grep_tool', status: 'SUCCEEDED', association: 'PERSISTED', argumentsSummary: '搜索对象存储配置', observation: '定位到对象存储适配器配置。' }),
  event(6, 'TOOL_STARTED', { toolInvocationId: 'tool-rich', toolName: 'deep_search_tool', status: 'RUNNING', association: 'PERSISTED', argumentsSummary: '研究对象存储文件服务' }),
  { ...event(7, 'TOOL_PROGRESS', { runtimeEventId: 'fixture-event-7', toolInvocationId: 'tool-rich', toolName: 'deep_search_tool', stage: 'search_completed', status: 'completed', message: '搜索完成', presentationKind: 'deep_search.search_results.v1', presentationSchemaVersion: '1', presentationData: { sources: [{ title: 'MinIO 文档', domain: 'min.io', url: 'https://min.io/docs/minio/linux/index.html', snippet: 'MinIO 对象存储公开文档', rawBody: 'must-not-render' }] } }), eventId: 'fixture-event-7' },
  { ...event(8, 'TOOL_PROGRESS', { runtimeEventId: 'fixture-event-8', toolInvocationId: 'tool-rich', toolName: 'deep_search_tool', stage: 'evidence_summary', status: 'completed', message: '证据已核验', presentationKind: 'deep_search.evidence_summary.v1', presentationSchemaVersion: '1', presentationData: { evidenceCount: 2, evidence: [{ claim: '对象存储可替换', verification: 'verified', title: 'MinIO 文档', url: 'https://min.io/docs/minio/linux/index.html', quotePreview: 'S3 compatible object storage', rawBody: 'must-not-render' }] } }), eventId: 'fixture-event-8' },
  { ...event(9, 'TOOL_PROGRESS', { runtimeEventId: 'fixture-event-9', toolInvocationId: 'tool-rich', toolName: 'deep_search_tool', stage: 'research_completed', status: 'completed', message: '研究完成', presentationKind: 'deep_search.completed.v1', presentationSchemaVersion: '1', presentationData: { summary: '对象存储接口可替换，但仍需验证迁移与权限边界。', researchOutcome: 'evidence_limited', stopReason: 'coverage_incomplete', evidenceCount: 2, limitations: ['迁移回滚策略尚未由当前证据覆盖'] } }), eventId: 'fixture-event-9' },
  event(10, 'TOOL_RESULT', { toolInvocationId: 'tool-rich', toolName: 'deep_search_tool', status: 'PARTIAL_SUCCESS', association: 'PERSISTED', observation: '研究完成，保留两条经过核验的公开证据。', artifactIds: ['artifact-workbench'] }),
  event(11, 'ARTIFACT_CREATED', { artifactId: 'artifact-workbench', reference: 'artifact:workbench-report', name: '产品级 Artifact 阅读验收报告.md', artifactType: 'report', mimeType: 'text/markdown', size: 4096, summary: '用于工作台与长文阅读验收的稳定报告。', visibility: 'VISIBLE', sourceToolInvocationId: 'tool-rich', sourceStage: 'deep_search_tool', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'markdown', schemaVersion: 'v1' } }),
  event(12, 'RUN_COMPLETED', { status: 'COMPLETED', summary: '稳定 Fixture 运行已经完成。', artifactIds: ['artifact-workbench'], artifactSelection: 'explicit' }),
];

const complexMarkdown = `# 产品级 Artifact 阅读验收：一个足够长、能够验证移动端换行行为的标题

这是正文，包含 **强调**、[外链](https://example.com) 和 \`inline code\`。

## 嵌套清单

1. 第一项
   - 子项 A
   - 子项 B
2. 第二项

> 引用内容用于验证排版。

## 代码

\`\`\`typescript
export const longValue = 'reader-local-scroll-only-reader-local-scroll-only';
\`\`\`

## 宽表格

| 能力 | 状态 | 很长的说明字段 |
| --- | --- | --- |
| Preview | READY | 宽内容必须只在表格区域局部横向滚动 |

![远程图片](https://example.com/image.png)
`;

async function installFixture(page: Page): Promise<void> {
  await page.route('http://127.0.0.1:8091/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0000', data }) });
    if (path.endsWith('/runtime-events/agents')) return json([{ agentId, displayName: '研究分析 Agent', runtimeKind: 'REACT', available: true }]);
    if (path.endsWith('/runtime-events/sessions')) return json([{ sessionId, title: '工作台稳定验收', updatedAt: occurredAt, runCount: 1 }]);
    if (path.endsWith('/browser/status')) return json({ connected: false });
    if (path.endsWith('/agent-browser/status')) return json({ exists: false });
    if (path.endsWith('/desktop-controls')) return json([]);
    if (path.endsWith(`/runtime-events/sessions/${sessionId}`)) return json({ session: { sessionId, title: '工作台稳定验收', updatedAt: occurredAt, runCount: 1 }, replays: [{ runId, sessionId, requestId, agentId, task: '验证运行工作台与 Artifact Reader', status: 'COMPLETED', historical: true, summary: '稳定 Fixture 运行已经完成。', events }] });
    if (path.endsWith('/artifact-workbench/preview')) return route.fulfill({ status: 200, contentType: 'text/markdown', body: complexMarkdown });
    if (path.endsWith('/artifact-workbench/download')) return route.fulfill({ status: 200, contentType: 'text/markdown', headers: { 'Content-Disposition': 'attachment; filename="workbench-report.md"' }, body: complexMarkdown });
    return route.fulfill({ status: 404, body: '' });
  });
}

test.beforeEach(async ({ page }) => { await installFixture(page); });

test('桌面聚合 Operation 并支持固定、恢复跟随、折叠和专注', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop');
  const consoleErrors: string[] = [];
  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  await page.goto('/');
  const workspace = page.getByRole('complementary', { name: '运行详情工作区' });
  await expect(workspace).toContainText('深度研究动态');
  await expect(workspace).toContainText('研究完成');
  await expect(workspace).toContainText('证据有限');
  await expect(workspace).toContainText('仍有问题未被充分覆盖');
  const processToggle = page.getByRole('button', { name: /执行过程.*3 项操作/ });
  await expect(processToggle).toBeVisible();
  await expect(page.getByRole('button', { name: '在工作区查看 读取技能资料' })).toBeVisible();
  await expect(page.getByRole('button', { name: '在工作区查看 搜索内容' })).toBeVisible();
  await expect(page.getByRole('button', { name: '在工作区查看 深度研究' })).toBeVisible();
  await page.getByRole('button', { name: /深度研究.*研究对象存储文件服务/ }).click();
  await page.getByRole('region', { name: '本次执行过程' }).getByRole('button', { name: '搜索完成 1 条来源', exact: true }).click();
  await expect(page.getByRole('link', { name: '打开来源 MinIO 文档' }).first()).toHaveAttribute('href', 'https://min.io/docs/minio/linux/index.html');
  await expect(page.getByText('must-not-render')).toHaveCount(0);

  await page.getByRole('button', { name: '在工作区查看 读取技能资料' }).click();
  await expect(workspace).toContainText('已固定详情');
  await expect(workspace).toContainText('已读取公开输入摘要');
  await workspace.getByRole('button', { name: '恢复跟随最新' }).click();
  await expect(workspace).toContainText('深度研究动态');
  await expect(workspace).toContainText('研究完成');

  const beforeWidth = (await workspace.boundingBox())!.width;
  const handle = workspace.getByRole('button', { name: '调整运行详情宽度' });
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + 2, box.y + 30);
  await page.mouse.down();
  await page.mouse.move(box.x - 70, box.y + 30, { steps: 5 });
  await page.mouse.up();
  await expect.poll(async () => (await workspace.boundingBox())!.width).toBeGreaterThan(beforeWidth + 40);

  await workspace.getByRole('button', { name: '进入详情专注模式' }).click();
  await expect(page.locator('.runtime-conversation-pane')).toBeHidden();
  await workspace.getByRole('button', { name: '退出详情专注模式' }).click();
  await workspace.getByRole('button', { name: '折叠运行详情' }).click();
  await expect(workspace.getByRole('button', { name: '展开运行详情' })).toBeVisible();
  await page.getByRole('button', { name: '在工作区查看 深度研究' }).click();
  await expect(workspace.getByRole('button', { name: '折叠运行详情' })).toBeVisible();
  await page.getByRole('button', { name: '在工作区查看阶段 搜索完成' }).click();
  await expect(workspace.getByRole('navigation', { name: '研究阶段' })).toBeVisible();
  await expect(workspace.getByRole('button', { name: '查看总体结果与限制' })).toBeVisible();
  await expect(workspace.getByRole('button', { name: '返回研究概览' })).toHaveCount(0);
  await expect(workspace.getByRole('link', { name: '打开来源 MinIO 文档' })).toHaveAttribute('href', 'https://min.io/docs/minio/linux/index.html');
  expect(consoleErrors).toEqual([]);
  await workspace.getByRole('tab', { name: /文件/ }).click();
  await expect(workspace).toContainText('1 个');
  await expect(workspace.getByRole('heading', { level: 1, name: /产品级 Artifact 阅读验收/ })).toBeVisible();
  await expect(workspace.getByRole('button', { name: '打开文档目录' })).toBeVisible();
  await expect(workspace.getByRole('button', { name: '放大阅读 产品级 Artifact 阅读验收报告.md' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('desktop-runtime-files.png'), fullPage: true });
  await workspace.getByRole('tab', { name: '动态' }).click();
  await expect(workspace).toContainText('研究完成');
  const composerBox = (await page.locator('.task-composer').boundingBox())!;
  const workspaceBox = (await workspace.boundingBox())!;
  expect(composerBox.x + composerBox.width).toBeLessThanOrEqual(workspaceBox.x);
  await page.screenshot({ path: testInfo.outputPath('desktop-runtime-workbench.png'), fullPage: true });
});

test('移动与 320px 使用近全屏 Sheet 且 Reader 无页面横向溢出', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile');
  await page.setViewportSize({ width: 320, height: 760 });
  await page.goto('/');
  const trigger = page.getByRole('button', { name: '在工作区查看 深度研究' });
  await trigger.click();
  const sheet = page.getByRole('dialog', { name: '深度研究详情' });
  await expect(sheet).toBeVisible();
  await sheet.getByRole('button', { name: /搜索完成/ }).click();
  const stageSheet = page.getByRole('dialog', { name: '搜索完成' });
  await expect(stageSheet).toContainText('MinIO 文档');
  await expect(stageSheet.getByRole('link', { name: '打开来源 MinIO 文档' })).toBeVisible();
  await expect(stageSheet.getByRole('link', { name: '打开来源 MinIO 文档' })).toHaveAttribute('target', '_blank');
  await page.screenshot({ path: testInfo.outputPath('mobile-runtime-stage.png'), fullPage: true });
  await stageSheet.getByRole('button', { name: '返回研究概览' }).click();
  await expect(page.getByRole('dialog', { name: '深度研究详情' })).toBeVisible();
  await page.getByRole('dialog', { name: '深度研究详情' }).getByRole('button', { name: '关闭运行详情' }).click();
  await expect(trigger).toBeFocused();

  await page.getByRole('button', { name: '查看文件详情 产品级 Artifact 阅读验收报告.md' }).click();
  const fileSheet = page.getByRole('dialog', { name: '产品级 Artifact 阅读验收报告.md' });
  await expect(fileSheet.getByRole('heading', { level: 1, name: /产品级 Artifact 阅读验收/ })).toBeVisible();
  await expect(fileSheet.getByRole('button', { name: '放大阅读 产品级 Artifact 阅读验收报告.md' })).toBeVisible();
  await fileSheet.getByRole('button', { name: '关闭运行详情' }).click();

  await page.getByRole('button', { name: '预览文件 产品级 Artifact 阅读验收报告.md' }).click();
  const readerDialog = page.getByRole('dialog');
  const readerRoot = readerDialog.getByTestId('artifact-reader-scroll-root');
  await expect(readerRoot.getByRole('heading', { level: 1, name: /产品级 Artifact 阅读验收/ })).toBeVisible();
  await readerDialog.getByRole('button', { name: '打开文档目录' }).click();
  await expect(readerDialog.getByRole('navigation', { name: '文档目录' })).toBeVisible();
  await expect(readerDialog.getByRole('region', { name: '可横向滚动的表格' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await readerDialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('mobile-320-runtime-reader.png'), fullPage: true });
});

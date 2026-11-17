import { expect, test, type Page, type TestInfo } from '@playwright/test';

interface ArtifactE2eConfig {
  sessionId: string;
  runId: string;
  artifactId: string;
  artifactName: string;
  expectDeleted: boolean;
}

interface HistoryEnvelope {
  code?: string;
  data?: {
    session?: { sessionId?: string; title?: string };
    replays?: Array<{
      runId?: string;
      events?: Array<{ eventType?: string; payload?: Record<string, unknown> }>;
    }>;
  };
}

interface ArtifactMetadataEnvelope {
  code?: string;
  data?: {
    artifactId?: string;
    accessState?: string;
    previewSupported?: boolean;
    downloadSupported?: boolean;
  };
}

function loadConfig(): ArtifactE2eConfig | undefined {
  const sessionId = process.env.ARTIFACT_E2E_SESSION_ID?.trim();
  const runId = process.env.ARTIFACT_E2E_RUN_ID?.trim();
  const artifactId = process.env.ARTIFACT_E2E_ARTIFACT_ID?.trim();
  const artifactName = process.env.ARTIFACT_E2E_ARTIFACT_NAME?.trim();
  const deleted = process.env.ARTIFACT_E2E_EXPECT_DELETED?.trim().toLowerCase();
  if (!sessionId || !runId || !artifactId || !artifactName || (deleted !== 'true' && deleted !== 'false')) return undefined;
  return { sessionId, runId, artifactId, artifactName, expectDeleted: deleted === 'true' };
}

const config = loadConfig();
const apiBaseUrl = (process.env.ARTIFACT_E2E_API_BASE_URL ?? 'http://127.0.0.1:8091').replace(/\/$/, '');

test.skip(!config, '设置 ARTIFACT_E2E_SESSION_ID、RUN_ID、ARTIFACT_ID、ARTIFACT_NAME 和 EXPECT_DELETED 后运行真实 Artifact 验收。');

async function openConfiguredSession(page: Page, testInfo: TestInfo, current: ArtifactE2eConfig): Promise<void> {
  const historyResponse = await page.request.get(
    `${apiBaseUrl}/api/v1/runtime-events/sessions/${encodeURIComponent(current.sessionId)}?limit=100`,
    { headers: { Accept: 'application/json' } },
  );
  expect(historyResponse.status()).toBe(200);
  const history = await historyResponse.json() as HistoryEnvelope;
  expect(history.code).toBe('0000');
  expect(history.data?.session?.sessionId).toBe(current.sessionId);

  const replay = history.data?.replays?.find((candidate) => candidate.runId === current.runId);
  expect(replay, `Session 中应存在 Run ${current.runId}`).toBeDefined();
  const artifactEvent = replay?.events?.find((event) => event.eventType === 'ARTIFACT_CREATED'
    && event.payload?.artifactId === current.artifactId);
  expect(artifactEvent?.payload?.name).toBe(current.artifactName);
  expect(artifactEvent?.payload?.accessState).toBe(current.expectDeleted ? 'NOT_FOUND' : 'AVAILABLE');

  const artifactPath = `${apiBaseUrl}/api/v1/artifacts/runs/${encodeURIComponent(current.runId)}/${encodeURIComponent(current.artifactId)}`;
  const metadataResponse = await page.request.get(artifactPath, { headers: { Accept: 'application/json' } });
  expect(metadataResponse.status()).toBe(200);
  const metadata = await metadataResponse.json() as ArtifactMetadataEnvelope;
  expect(metadata.code).toBe('0000');
  expect(metadata.data?.artifactId).toBe(current.artifactId);
  expect(metadata.data?.accessState, '文件服务必须能够实时读取既有 Artifact；UNAVAILABLE 通常表示后端未启用对应存储 Backend').toBe(
    current.expectDeleted ? 'NOT_FOUND' : 'AVAILABLE',
  );
  expect(metadata.data?.previewSupported).toBe(!current.expectDeleted);
  expect(metadata.data?.downloadSupported).toBe(!current.expectDeleted);

  await page.goto('/');
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  const sessionTitle = history.data?.session?.title;
  expect(sessionTitle).toBeTruthy();
  const sessionItem = page.locator('.session-item:visible').filter({ hasText: sessionTitle! }).first();
  await expect(sessionItem).toBeVisible({ timeout: 20_000 });
  await sessionItem.click();
}

test('真实 S3 Replay 的 Artifact 交付末态正确', async ({ page }, testInfo) => {
  if (!config) return;
  if (testInfo.project.name === 'mobile') await page.setViewportSize({ width: 320, height: 760 });

  const consoleErrors: string[] = [];
  const failedResources: string[] = [];
  const artifactContentRequests: string[] = [];
  let previewRequests = 0;
  const artifactPath = `/api/v1/artifacts/runs/${encodeURIComponent(config.runId)}/${encodeURIComponent(config.artifactId)}`;
  const previewPath = `${artifactPath}/preview`;
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(`${message.text()} @ ${message.location().url}`);
  });
  page.on('request', (request) => {
    const url = request.url();
    if (url.includes(artifactPath)) artifactContentRequests.push(url);
    if (url.endsWith(previewPath)) previewRequests += 1;
  });
  page.on('response', (response) => {
    if (response.status() >= 400) failedResources.push(`${response.status()} ${response.url()}`);
  });

  await openConfiguredSession(page, testInfo, config);

  const artifactCard = page.locator('article').filter({ hasText: config.artifactName }).last();
  await expect(artifactCard).toBeVisible({ timeout: 20_000 });

  if (config.expectDeleted) {
    await artifactCard.scrollIntoViewIfNeeded();
    await expect(artifactCard.getByText('文件当前不可访问。')).toBeVisible();
    await expect(artifactCard.getByRole('button', { name: `预览文件 ${config.artifactName}` })).toHaveCount(0);
    await expect(artifactCard.getByRole('link', { name: `下载文件 ${config.artifactName}` })).toHaveCount(0);
    expect(artifactContentRequests).toEqual([]);
    await artifactCard.screenshot({ path: testInfo.outputPath(`${testInfo.project.name}-artifact-deleted-card.png`) });
    await page.screenshot({ path: testInfo.outputPath(`${testInfo.project.name}-artifact-deleted-page.png`), fullPage: true });
    expect({ consoleErrors, failedResources }).toEqual({ consoleErrors: [], failedResources: [] });
    return;
  }

  const detailTrigger = artifactCard.getByRole('button', { name: `查看文件详情 ${config.artifactName}` });
  await detailTrigger.click();
  if (testInfo.project.name === 'mobile') {
    const workspaceSheet = page.getByRole('dialog');
    await expect(workspaceSheet.getByRole('banner').getByRole('heading', { name: config.artifactName })).toBeVisible();
    await expect(workspaceSheet.getByText('AVAILABLE', { exact: true })).toBeVisible();
    await expect(workspaceSheet.getByRole('link', { name: '下载文件' })).toBeVisible();
    await workspaceSheet.getByRole('button', { name: '关闭运行详情' }).click();
    await expect(workspaceSheet).not.toBeVisible();
    await expect(detailTrigger).toBeFocused();
  } else {
    const workspace = page.getByRole('complementary', { name: '运行详情工作区' });
    await expect(workspace.getByRole('heading', { name: config.artifactName })).toBeVisible();
    await expect(workspace.getByText('AVAILABLE', { exact: true })).toBeVisible();
    await expect(workspace.getByRole('link', { name: '下载文件' })).toBeVisible();
  }

  const previewTrigger = artifactCard.getByRole('button', { name: `预览文件 ${config.artifactName}` });
  await expect(previewTrigger).toBeVisible();
  const conversationScroll = page.locator('.conversation-scroll');
  await previewTrigger.scrollIntoViewIfNeeded();
  const conversationBefore = await conversationScroll.evaluate((element) => ({ scrollTop: element.scrollTop, scrollHeight: element.scrollHeight }));
  await previewTrigger.click();

  const dialog = page.getByRole('dialog');
  const reader = page.getByTestId('artifact-reader-scroll-root');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('heading', { name: config.artifactName })).toBeVisible();
  await expect(reader.locator('h1, h2, h3').first()).toBeVisible({ timeout: 20_000 });
  await expect(dialog.getByRole('region', { name: '可横向滚动的表格' }).first()).toBeVisible();
  expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await reader.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);

  const firstParagraph = reader.locator('blockquote p').first();
  const typography = await firstParagraph.evaluate((element) => {
    const style = getComputedStyle(element);
    return { fontSize: style.fontSize, letterSpacing: style.letterSpacing };
  });
  expect(typography.fontSize).toBe(testInfo.project.name === 'mobile' ? '15px' : '16px');
  expect(['0px', 'normal']).toContain(typography.letterSpacing);

  const toc = dialog.getByRole('navigation', { name: '文档目录' });
  if (testInfo.project.name === 'mobile') await dialog.getByRole('button', { name: '打开文档目录' }).click();
  await expect(toc).toBeVisible();
  const tocTarget = toc.getByRole('button').nth(2);
  await tocTarget.click();
  await expect(tocTarget).toHaveAttribute('aria-current', 'location');

  await dialog.getByRole('button', { name: '复制代码' }).first().click();
  await expect(dialog.getByRole('button', { name: '代码已复制' })).toBeVisible();

  const downloadPromise = page.waitForEvent('download');
  await dialog.getByRole('link', { name: `下载文件 ${config.artifactName}` }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe(config.artifactName);

  await dialog.getByRole('button', { name: '关闭文件预览' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(previewTrigger).toBeFocused();
  const conversationAfter = await conversationScroll.evaluate((element) => ({ scrollTop: element.scrollTop, scrollHeight: element.scrollHeight }));
  expect(conversationAfter).toEqual(conversationBefore);

  await previewTrigger.click();
  await expect(reader.locator('h1, h2, h3').first()).toBeVisible();
  expect(previewRequests).toBe(2);
  await expect.poll(() => dialog.evaluate((element) => (
    element.getAnimations().every((animation) => animation.playState === 'finished')
  ))).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`${testInfo.project.name}-artifact-reader.png`), fullPage: true });
  const bounds = await dialog.boundingBox();
  const layoutViewport = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    height: document.documentElement.clientHeight,
  }));
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(layoutViewport.width);
  expect(bounds!.y).toBeGreaterThanOrEqual(0);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(layoutViewport.height);
  expect({ consoleErrors, failedResources }).toEqual({ consoleErrors: [], failedResources: [] });
});

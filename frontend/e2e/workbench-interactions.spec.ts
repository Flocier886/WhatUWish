import { expect, test } from '@playwright/test';
import { installDemoRuntimeFixture } from './demo-runtime-fixture';

test.beforeEach(async ({ page }) => { await installDemoRuntimeFixture(page); });

test('搜索框直接输入，附件直接打开选择器，会话文件不挤压输入区', async ({ page }, testInfo) => {
  await page.goto('/');
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  const sidebar = testInfo.project.name === 'mobile' ? page.getByRole('dialog', { name: '会话导航' }) : page.getByRole('complementary', { name: '会话导航' });
  const search = sidebar.getByRole('searchbox', { name: '按标题搜索会话' });
  await search.click();
  await expect(search).toBeFocused();
  await search.fill('课程反馈');
  await expect(sidebar.getByRole('button', { name: /课程反馈行动建议/ })).toBeVisible();
  await expect(sidebar.getByRole('searchbox')).toHaveCount(1);
  await sidebar.getByRole('button', { name: '清除会话搜索' }).click();
  await expect(search).toHaveValue('');
  if (testInfo.project.name === 'mobile') {
    await sidebar.getByRole('button', { name: '关闭会话导航', exact: true }).click();
    await page.keyboard.press('Control+k');
    await expect(page.getByRole('dialog', { name: '会话导航' }).getByRole('searchbox', { name: '按标题搜索会话' })).toBeFocused();
    await page.getByRole('button', { name: '关闭会话导航', exact: true }).click();
  } else {
    await page.keyboard.press('Control+k');
    await expect(search).toBeFocused();
  }

  await page.getByRole('button', { name: '附件' }).click();
  await expect(page.getByText('当前会话没有上传凭据，请先新建对话。')).toBeVisible();
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  await page.getByRole('button', { name: '新建对话' }).click();
  const fileChooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: /^附件/ }).click();
  await fileChooser;
  const composer = page.locator('.task-composer');
  const before = (await composer.boundingBox())!.height;
  await page.getByRole('button', { name: '会话文件' }).click();
  await expect(page.getByRole('region', { name: '会话文件' })).toBeVisible();
  expect((await composer.boundingBox())!.height).toBe(before);
  await page.screenshot({ path: testInfo.outputPath('files-popover.png'), fullPage: true });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('region', { name: '会话文件' })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('composer-search.png'), fullPage: true });
});

test('生图工作台的设置、参考图、生成画廊与放大阅读可操作', async ({ page }, testInfo) => {
  if (testInfo.project.name === 'mobile') await page.setViewportSize({ width: 320, height: 760 });
  await page.goto('/');
  const imageData = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 320; canvas.height = 320;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#b9d8d1'; context.fillRect(0, 0, 320, 320);
    context.fillStyle = '#d8646d'; context.fillRect(45, 45, 230, 230);
    context.fillStyle = '#fff'; context.fillRect(110, 110, 100, 100);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const image = Buffer.from(imageData, 'base64');
  await page.route('http://127.0.0.1:8091/api/v1/image-generation-workbench/**', async (route) => {
    const url = route.request().url();
    if (url.includes('/history?')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: '0000', data: { sessionId: 'session-demo-001', pageNo: 1, pageSize: 12, total: 0, hasMore: false, items: [] } }) });
    if (url.endsWith('/generations')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: '0000', data: { executionId: 'image-e2e-1', status: 'completed', summary: '生成完成', artifacts: [{ artifactId: 'image-1', name: '城市海报.png', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'image', schemaVersion: 'v1' } }] } }) });
    if (url.endsWith('/preview') || url.endsWith('/download')) return route.fulfill({ contentType: 'image/png', body: image });
    return route.fulfill({ status: 404 });
  });

  await page.getByRole('button', { name: '打开图片生成工作台' }).click();
  const workbench = page.getByRole('region', { name: '图片生成工作台' });
  await expect(workbench.getByRole('heading', { name: '图片', exact: true })).toBeVisible();
  await workbench.getByRole('button', { name: '生成设置' }).click();
  await expect(page.getByRole('dialog', { name: '生成设置' })).toBeVisible();
  await page.getByRole('button', { name: '关闭生成设置' }).click();

  await workbench.locator('input[type="file"]').setInputFiles({ name: 'reference.png', mimeType: 'image/png', buffer: image });
  await expect(workbench.getByRole('img', { name: '参考图 1' })).toBeVisible();
  await expect(workbench.getByRole('button', { name: '图生图 / 编辑' })).toHaveAttribute('aria-pressed', 'true');
  await workbench.getByRole('button', { name: '编辑 reference.png' }).click();
  await expect(page.getByRole('dialog', { name: 'reference.png' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('image-editor.png'), fullPage: true });
  await page.getByRole('button', { name: '关闭遮罩编辑' }).click();
  await workbench.getByRole('textbox', { name: '图片描述' }).fill('城市海报');
  await workbench.getByRole('button', { name: '生成', exact: true }).click();
  await expect(workbench.getByRole('button', { name: '预览 城市海报.png' })).toBeVisible();
  await workbench.getByRole('button', { name: '预览 城市海报.png' }).click();
  await expect(page.getByRole('dialog', { name: '城市海报.png' })).toBeVisible();
  await page.getByRole('button', { name: '关闭图片预览' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('image-workbench.png'), fullPage: true });
});

test('生图工作台多图批处理保留逐图结果和部分失败', async ({ page }, testInfo) => {
  let requests = 0;
  const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l3sAAAAASUVORK5CYII=', 'base64');
  await page.route('http://127.0.0.1:8091/api/v1/image-generation-workbench/**', async (route) => {
    const url = route.request().url();
    if (url.includes('/history?')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: '0000', data: { sessionId: 'session-demo-001', pageNo: 1, pageSize: 12, total: 0, hasMore: false, items: [] } }) });
    if (url.endsWith('/generations')) {
      requests += 1;
      const failed = route.request().postData()?.includes('second.png');
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: '0000', data: {
        executionId: failed ? 'image-batch-2' : 'image-batch-1', status: failed ? 'failed' : 'completed',
        summary: failed ? '第二张图片生成失败' : '第一张图片已生成',
        errorMessage: failed ? '第二张图片生成失败' : undefined,
        artifacts: failed ? [] : [{ artifactId: 'batch-output-1', name: 'first.png', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'image', schemaVersion: 'v1' } }],
      } }) });
    }
    if (url.endsWith('/preview')) return route.fulfill({ contentType: 'image/png', body: image });
    return route.fulfill({ status: 404 });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '打开图片生成工作台' }).click();
  const workbench = page.getByRole('region', { name: '图片生成工作台' });
  await workbench.locator('input[type="file"]').setInputFiles([
    { name: 'first.png', mimeType: 'image/png', buffer: image },
    { name: 'second.png', mimeType: 'image/png', buffer: image },
  ]);
  await expect(workbench.getByRole('checkbox', { name: '多图批处理' })).toBeChecked();
  await workbench.getByRole('textbox', { name: '图片描述' }).fill('修改背景');
  await workbench.getByRole('button', { name: '生成', exact: true }).click();
  await expect(workbench.getByText('第一张图片已生成')).toBeVisible();
  await expect(workbench.getByText('第二张图片生成失败').first()).toBeVisible();
  await expect(workbench.getByRole('button', { name: '预览 first.png' })).toBeVisible();
  expect(requests).toBe(2);
  await expect(workbench.getByRole('textbox', { name: '图片描述' })).toHaveValue('修改背景');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('image-batch-partial-failure.png'), fullPage: true });
});

import { expect, test } from '@playwright/test';

const sessionId = process.env.GENUI_E2E_SESSION_ID;
test.skip(!sessionId, '需要真实 GenUI Agent Run');

test('真实 GenUI 画布在历史会话中交互、展示 3D 并导出', async ({ page }, info) => {
  await page.addInitScript((id) => {
    if (window.top !== window) return;
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');
  const canvas = page.getByRole('region', { name: '交互画布' }).first();
  await expect(canvas).toBeVisible({ timeout: 20_000 });
  await expect(canvas.getByText('季度经营分析｜销售表现总览（演示数据）')).toBeVisible();
  // ECharts 将标题绘制在 canvas 内，不能用 DOM 文本定位。
  const chart = canvas.locator('canvas').first();
  await expect(chart).toBeVisible({ timeout: 20_000 });
  await expect.poll(async () => chart.evaluate((element) => {
    const drawing = element as HTMLCanvasElement;
    const context = drawing.getContext('2d');
    if (!context || drawing.width < 100 || drawing.height < 100) return 0;
    const pixels = context.getImageData(0, 0, drawing.width, drawing.height).data;
    let colored = 0;
    for (let i = 0; i < pixels.length; i += 80) {
      if (pixels[i + 3] > 0 && Math.max(pixels[i], pixels[i + 1], pixels[i + 2])
          - Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) > 35) colored += 1;
    }
    return colored;
  }), { timeout: 20_000 }).toBeGreaterThan(20);
  await expect(canvas.getByText('从销售额到经营决策')).toBeVisible();
  await canvas.getByRole('button', { name: '播放' }).click();
  await expect(canvas.getByRole('button', { name: '暂停' })).toBeVisible();
  await canvas.getByRole('button', { name: '暂停' }).click();
  const field = canvas.getByRole('textbox').first();
  await field.fill('华东');
  await expect(field).toHaveValue('华东');
  await canvas.getByRole('button', { name: '提交筛选' }).click();
  await expect(canvas.getByText('已提交')).toBeVisible();
  const frame = canvas.locator('iframe[title="季度销售柱形 3D 视图"]');
  await expect(frame).toBeVisible();
  await expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
  const scene = frame.contentFrame().locator('#genui-three-canvas');
  await expect(scene).toBeVisible({ timeout: 20_000 });
  await expect(frame.contentFrame().locator('#genui-three-error')).toBeHidden();
  await expect.poll(async () => scene.evaluate((element) => {
    const drawing = element as HTMLCanvasElement;
    return Boolean((window as Window & { THREE?: unknown }).THREE) ? drawing.width : 0;
  }), { timeout: 20_000 }).toBeGreaterThan(100);
  const dimensions = await scene.evaluate((element) => {
    const drawing = element as HTMLCanvasElement;
    return [drawing.width, drawing.height];
  });
  expect(dimensions[0]).toBeGreaterThan(100);
  expect(dimensions[1]).toBeGreaterThan(100);
  await frame.screenshot({ path: `test-results/genui-3d-${info.project.name}.png` });
  await canvas.screenshot({ path: `test-results/genui-${info.project.name}.png` });
  const downloadPromise = page.waitForEvent('download');
  await canvas.getByRole('button', { name: '导出 PDF 内容快照' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.pdf$/u);
  await canvas.getByRole('button', { name: '在工作台查看交互画布' }).click();
  const workbench = info.project.name === 'mobile' ? page.getByRole('dialog')
    : page.getByRole('complementary', { name: '运行详情工作区' });
  await expect(workbench.getByText('季度经营分析｜销售表现总览（演示数据）')).toBeVisible();
});

test('Three.js 资源不可用时只在 3D 组件显示错误', async ({ page }) => {
  await page.route('https://unpkg.com/three@0.160.0/**', (route) => route.abort());
  await page.addInitScript((id) => {
    if (window.top !== window) return;
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');
  const canvas = page.getByRole('region', { name: '交互画布' }).first();
  await expect(canvas.getByText('季度经营分析｜销售表现总览（演示数据）')).toBeVisible({ timeout: 20_000 });
  await expect(canvas.locator('canvas').first()).toBeVisible();
  const frame = canvas.locator('iframe[title="季度销售柱形 3D 视图"]');
  await expect(frame.contentFrame().locator('#genui-three-error')).toBeVisible({ timeout: 20_000 });
  await expect(canvas.getByRole('textbox').first()).toBeVisible();
});

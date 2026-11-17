import { expect, test } from '@playwright/test';

const sessionId = process.env.GENUI_MODEL_E2E_SESSION_ID;
test.skip(!sessionId, '需要含真实 GLB 模型的 GenUI Agent Run');

test('Model3D 加载可访问的真实 GLB 并显示非空场景', async ({ page }, info) => {
  await page.addInitScript((id) => {
    if (window.top !== window) return;
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');
  const canvas = page.getByRole('region', { name: '交互画布' }).first();
  await expect(canvas).toBeVisible({ timeout: 20_000 });
  const model = canvas.getByRole('img', { name: 'Lee Perry Smith 3D 模型' });
  await expect(model.locator('canvas')).toBeVisible({ timeout: 30_000 });
  await expect(canvas.getByText('Failed to load model')).toBeHidden({ timeout: 30_000 });
  await expect(canvas.getByText('Loading 3D model…')).toBeHidden({ timeout: 30_000 });
  const size = await model.locator('canvas').evaluate((element) => {
    const drawing = element as HTMLCanvasElement;
    return [drawing.width, drawing.height];
  });
  expect(size[0]).toBeGreaterThan(100);
  expect(size[1]).toBeGreaterThan(100);
  const bounds = await model.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(info.project.name === 'mobile' ? 391 : 1441);
  const runWidth = await page.locator('.conversation-run').first().evaluate((element) => [element.clientWidth, element.scrollWidth]);
  expect(runWidth[1]).toBeLessThanOrEqual(runWidth[0] + 1);
  await model.screenshot({ path: `test-results/genui-model-${info.project.name}.png` });
});

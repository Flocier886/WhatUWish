import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import { installDemoRuntimeFixture } from './demo-runtime-fixture';

test.skip(process.env.RUN_REAL_IMAGE_E2E !== '1', '需要运行中的 Java、Python、Artifact 存储和真实图片 Provider');
test.setTimeout(240_000);

test('真实图片工作台逐图批处理生成并交付两张图片', async ({ page }, testInfo) => {
  await installDemoRuntimeFixture(page);
  await page.route('http://127.0.0.1:8091/api/v1/image-generation-workbench/**', (route) => route.continue());
  const requests: string[] = [];
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/image-generation-workbench/generations')) requests.push(request.postData() ?? '');
  });
  await page.goto('/');
  await page.getByRole('button', { name: '打开图片生成工作台' }).click();
  const workbench = page.getByRole('region', { name: '图片生成工作台' });
  await workbench.locator('input[type="file"]').setInputFiles([
    { name: 'reference-one.png', mimeType: 'image/png', buffer: readFileSync(resolve(process.cwd(), '../tmp/image-workbench-reference.png')) },
    { name: 'reference-two.png', mimeType: 'image/png', buffer: readFileSync(resolve(process.cwd(), '../tmp/image-generation-e2e/agent-source.png')) },
  ]);
  await expect(workbench.getByRole('checkbox', { name: '多图批处理' })).toBeChecked();
  await workbench.getByRole('textbox', { name: '图片描述' }).fill('基于参考图制作简洁的绿色产品海报');
  await workbench.getByRole('button', { name: '生成', exact: true }).click();

  await expect(workbench.getByRole('button', { name: /预览 reference-one/ })).toBeVisible({ timeout: 200_000 });
  await expect(workbench.getByRole('button', { name: /预览 reference-two/ })).toBeVisible({ timeout: 200_000 });
  expect(requests).toHaveLength(2);
  await expect(workbench.getByRole('textbox', { name: '图片描述' })).toHaveValue('');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('real-image-batch.png'), fullPage: true });
});

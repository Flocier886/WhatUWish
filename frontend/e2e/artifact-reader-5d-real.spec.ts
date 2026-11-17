import { expect, test } from '@playwright/test';

const sessionId = process.env.STAGE5_READER_SESSION_ID;
test.skip(!sessionId, '需要已生成 DOCX/XLSX 的真实 Agent 会话');

test.beforeEach(async ({ page }) => {
  await page.addInitScript((id) => {
    if (window.top !== window) return;
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');
});

test('真实中文 DOCX 可阅读并下载原件', async ({ page }, info) => {
  const name = '5d-reader-report.docx';
  const previewButton = page.getByRole('button', { name: `预览文件 ${name}` }).first();
  await expect(previewButton).toBeVisible({ timeout: 20_000 });
  await previewButton.click();
  const reader = page.getByRole('dialog', { name });
  await expect(reader.getByText('第五阶段阅读验收').first()).toBeVisible({ timeout: 20_000 });
  await expect(reader.getByText('华东区域销售增长百分之十二', { exact: false }).first()).toBeVisible();
  if (info.project.name === 'mobile') {
    const fits = await reader.locator('[class*="document"]').first().evaluate((element) => {
      const page = element.querySelector('.docx-preview');
      return Boolean(page && page.getBoundingClientRect().right <= window.innerWidth + 2);
    });
    expect(fits).toBe(true);
  }
  await reader.screenshot({ path: `test-results/5d-docx-${info.project.name}.png` });
  const download = page.waitForEvent('download');
  await reader.getByRole('link', { name: `下载文件 ${name}` }).click();
  expect((await download).suggestedFilename()).toBe(name);
  await reader.getByRole('button', { name: '关闭文件预览' }).click();
  await expect(reader).toBeHidden();
  await previewButton.click();
  await expect(page.getByRole('dialog', { name }).getByText('第五阶段阅读验收').first()).toBeVisible();
});

test('真实 XLSX 可切换工作表、搜索、选格并下载', async ({ page }, info) => {
  const name = '5d-reader-sales.xlsx';
  const previewButton = page.getByRole('button', { name: `预览文件 ${name}` }).first();
  await expect(previewButton).toBeVisible({ timeout: 20_000 });
  await previewButton.click();
  const reader = page.getByRole('dialog', { name });
  await expect(reader.getByRole('tab', { name: '华东销售' })).toBeVisible({ timeout: 20_000 });
  await reader.getByRole('searchbox', { name: '搜索工作表' }).fill('上海');
  const cell = reader.getByRole('button', { name: /上海/ }).first();
  await expect(cell).toBeVisible();
  await cell.click();
  await expect(reader.locator('[class*="address"]')).toContainText(/[A-Z]+\d+/u);
  await reader.getByRole('tab', { name: '华南销售' }).click();
  await expect(reader.getByRole('tab', { name: '华南销售' })).toHaveAttribute('aria-selected', 'true');
  await reader.screenshot({ path: `test-results/5d-xlsx-${info.project.name}.png` });
  const download = page.waitForEvent('download');
  await reader.getByRole('link', { name: `下载文件 ${name}` }).click();
  expect((await download).suggestedFilename()).toBe(name);
});

test('真实 PDF 可翻页缩放、选取中文并下载', async ({ page }, info) => {
  const name = '5d-reader-summary.pdf';
  await page.getByRole('button', { name: `预览文件 ${name}` }).first().click();
  const reader = page.getByRole('dialog', { name });
  await expect(reader.getByRole('spinbutton', { name: '页码' })).toHaveValue('1', { timeout: 20_000 });
  await expect(reader.locator('canvas').first()).toBeVisible();
  await expect.poll(() => reader.locator('canvas').first().evaluate((element) => {
    const canvas = element as HTMLCanvasElement;
    const pixels = canvas.getContext('2d')?.getImageData(0, 0, canvas.width, canvas.height).data;
    if (!pixels || canvas.width < 100) return 0;
    let ink = 0;
    for (let i = 0; i < pixels.length; i += 80) {
      if (pixels[i + 3] > 0 && Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) < 190) ink++;
    }
    return ink;
  }), { timeout: 20_000 }).toBeGreaterThan(20);
  await expect(reader.locator('.textLayer')).toContainText('华东销售分析', { timeout: 20_000 });
  await reader.getByRole('button', { name: '下一页' }).click();
  await expect(reader.getByRole('spinbutton', { name: '页码' })).toHaveValue('2');
  await expect(reader.locator('.textLayer')).toContainText('华南销售分析', { timeout: 20_000 });
  await reader.getByRole('button', { name: '放大' }).click();
  await expect(reader.getByText('115%')).toBeVisible();
  await reader.screenshot({ path: `test-results/5d-pdf-${info.project.name}.png` });
  const download = page.waitForEvent('download');
  await reader.getByRole('link', { name: `下载文件 ${name}` }).click();
  expect((await download).suggestedFilename()).toBe(name);
});

test('损坏的 PDF 只使当前 Reader 降级并保留下载', async ({ page }) => {
  await page.route('**/api/v1/artifacts/runs/**/49ed3acc5b82499b9437810fcfb1cefd/preview', (route) =>
    route.fulfill({ status: 200, contentType: 'application/pdf', body: 'not a pdf' }));
  await page.getByRole('button', { name: '预览文件 5d-reader-summary.pdf' }).first().click();
  const reader = page.getByRole('dialog', { name: '5d-reader-summary.pdf' });
  await expect(reader.getByRole('alert')).toContainText('PDF 文件无法预览', { timeout: 20_000 });
  await expect(reader.getByRole('link', { name: '下载原件' })).toBeVisible();
  await reader.getByRole('button', { name: '关闭文件预览' }).click();
  await page.getByRole('button', { name: '预览文件 5d-reader-areas.csv' }).first().click();
  await expect(page.getByRole('dialog', { name: '5d-reader-areas.csv' }).getByRole('tab')).toBeVisible();
});

test('损坏的 Office 内容只使对应 Reader 降级', async ({ page }) => {
  for (const [name, id, mime, expected] of [
    ['5d-reader-report.docx', 'b3aff2e16c704a28b128ff0be86ee489',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'Word 文件无法预览'],
    ['5d-reader-sales.xlsx', 'ad97d5c93cf245e894b2be444f7035d4',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '工作表无法预览'],
  ]) {
    await page.route(`**/api/v1/artifacts/runs/**/${id}/preview`, (route) =>
      route.fulfill({ status: 200, contentType: mime, body: 'broken office' }));
    await page.getByRole('button', { name: `预览文件 ${name}` }).first().click();
    const reader = page.getByRole('dialog', { name });
    await expect(reader.getByRole('alert')).toContainText(expected, { timeout: 20_000 });
    await expect(reader.getByRole('link', { name: '下载原件' })).toBeVisible();
    await reader.getByRole('button', { name: '关闭文件预览' }).click();
  }
  await page.getByRole('button', { name: '预览文件 5d-reader-areas.csv' }).first().click();
  await expect(page.getByRole('dialog', { name: '5d-reader-areas.csv' }).getByRole('tab')).toBeVisible();
});

test('真实 CSV 作为工作表可搜索和选格', async ({ page }) => {
  const name = '5d-reader-areas.csv';
  await page.getByRole('button', { name: `预览文件 ${name}` }).first().click();
  const reader = page.getByRole('dialog', { name });
  await expect(reader.getByRole('tab')).toBeVisible({ timeout: 20_000 });
  await reader.getByRole('searchbox', { name: '搜索工作表' }).fill('上海');
  const cell = reader.getByRole('button', { name: /上海/ }).first();
  await expect(cell).toBeVisible();
  await cell.click();
  await expect(reader.locator('[class*="address"]')).toContainText(/[A-Z]+\d+/u);
});

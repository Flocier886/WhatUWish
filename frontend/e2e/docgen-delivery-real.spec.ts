import { expect, test } from '@playwright/test';

const sessionId = process.env.DOCGEN_E2E_SESSION_ID;
const previewName = process.env.DOCGEN_E2E_PREVIEW_NAME;
const previewKind = process.env.DOCGEN_E2E_PREVIEW_KIND;
const downloadNames = (process.env.DOCGEN_E2E_DOWNLOAD_NAMES ?? '').split('|').filter(Boolean);

test.skip(!sessionId || !previewName || !previewKind || !downloadNames.length,
  '需要真实 docgen 会话、预览格式及文件名');

test('真实文档交付可在历史会话中阅读与下载', async ({ page }) => {
  await page.addInitScript((id) => {
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');

  for (const name of downloadNames) {
    const card = page.locator('article').filter({ has: page.getByRole('link', { name: `下载文件 ${name}` }) }).first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card.getByRole('link', { name: `下载文件 ${name}` })).toHaveAttribute('href', /\/download$/u);
  }

  await page.getByRole('button', { name: `预览文件 ${previewName}` }).first().click();
  const workspace = page.getByRole('dialog', { name: previewName });
  await expect(workspace).toBeVisible();
  if (previewKind === 'pdf') await expect(workspace.getByRole('spinbutton', { name: '页码' })).toBeVisible();
  if (previewKind === 'html') await expect(workspace.getByTitle('隔离的 HTML Artifact 预览')).toBeVisible();
  if (previewKind === 'image') await expect(workspace.getByAltText('Artifact 图片预览')).toBeVisible();
  if (previewKind === 'markdown') await expect(workspace.getByText('研究摘要').first()).toBeVisible();
});

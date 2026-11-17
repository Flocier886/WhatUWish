import { expect, test } from '@playwright/test';

test('真实文件从浏览器上传、选用到回放', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/');
  if (test.info().project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  await expect(page.getByRole('button', { name: '新建对话' })).toBeVisible();
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page.getByRole('button', { name: /ReAct/ })).toBeVisible();
  await page.getByLabel('选择附件').setInputFiles({ name: 'browser-e2e.txt', mimeType: 'text/plain', buffer: Buffer.from('browser acceptance unique 2026') });
  await expect(page.getByText('已就绪')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('textbox', { name: '消息' }).fill('请用 workspace_list 找文件，再用 workspace_read 读取 browser-e2e.txt，并把内容写到 outputs/browser-result.txt 后 workspace_register。');
  await page.getByRole('button', { name: '发送消息' }).click();
  await expect(page.getByText('browser-e2e.txt').last()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('回答已整理完成').last()).toBeVisible({ timeout: 100_000 });
  await page.reload();
  await expect(page.getByText('browser-e2e.txt').last()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('回答已整理完成').last()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('button', { name: '预览文件 browser-result.txt' })).toBeVisible();
  await page.getByRole('button', { name: 'browser-e2e.txt 30 B' }).click();
  await expect(page.getByText('browser acceptance unique 2026').last()).toBeVisible();
  await page.getByRole('button', { name: '关闭文件预览' }).click();
});

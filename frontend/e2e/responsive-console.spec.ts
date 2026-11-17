import { expect, test } from '@playwright/test';

test('桌面与移动端会话工作区没有横向溢出并可打开会话导航', async ({ page }, testInfo) => {
  await page.goto('/');
  expect(await page.evaluate(() => document.body.scrollWidth <= window.innerWidth)).toBe(true);
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('button', { name: '打开会话导航' }).click();
    await expect(page.getByRole('dialog', { name: '会话导航' })).toBeVisible();
    await page.getByRole('button', { name: '关闭会话导航', exact: true }).click();
  } else {
    await page.getByRole('button', { name: '收起会话导航' }).click();
    await expect(page.getByRole('button', { name: '展开会话导航' })).toBeVisible();
  }
  await page.screenshot({ path: testInfo.outputPath(`${testInfo.project.name}-session-workspace.png`), fullPage: true });
});

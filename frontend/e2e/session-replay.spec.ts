import { expect, test } from '@playwright/test';
import { installDemoRuntimeFixture } from './demo-runtime-fixture';

test.beforeEach(async ({ page }) => { await installDemoRuntimeFixture(page); });

test('用户可新建并切换会话，消息不会混入其他会话', async ({ page }, testInfo) => {
  await page.goto('/');
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page.getByRole('heading', { name: '有什么可以一起推进的吗？' })).toBeVisible();
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '打开会话导航' }).click();
  await page.getByRole('button', { name: /课程反馈行动建议/ }).click();
  await expect(page.getByRole('region', { name: '当前会话消息' })).toBeVisible();
  await expect(page.getByText(/汇总本周课程反馈/).first()).toBeVisible();
});

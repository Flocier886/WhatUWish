import { expect, test } from '@playwright/test';
import { installDemoRuntimeFixture } from './demo-runtime-fixture';

test.beforeEach(async ({ page }) => { await installDemoRuntimeFixture(page); });

test('用户在当前会话发送消息并看到连续 Agent 答复', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('button', { name: /研究分析 Agent/ })).toBeVisible();
  await page.getByRole('textbox', { name: '消息' }).fill('生成课程反馈行动建议');
  await page.getByRole('button', { name: '发送消息' }).click();
  await expect(page.getByText('生成课程反馈行动建议', { exact: true })).toBeVisible();
  await expect(page.getByText(/分析完成。优先处理/).last()).toBeVisible({ timeout: 8_000 });
});

test('失败消息保留在当前会话而不是进入独立错误页', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('button', { name: /研究分析 Agent/ })).toBeVisible();
  await page.getByRole('textbox', { name: '消息' }).fill('失败演示任务');
  await page.getByRole('button', { name: '发送消息' }).click();
  await expect(page.getByText('输入字段缺失，运行已停止。', { exact: true })).toBeVisible({ timeout: 8_000 });
});

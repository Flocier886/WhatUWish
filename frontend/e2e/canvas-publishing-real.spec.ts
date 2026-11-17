import { expect, test } from '@playwright/test';

const sessionId = process.env.CANVAS_E2E_SESSION_ID;
const runId = process.env.CANVAS_E2E_RUN_ID;
const artifactId = process.env.CANVAS_E2E_ARTIFACT_ID;
const api = (process.env.CANVAS_E2E_API_BASE_URL ?? 'http://127.0.0.1:8091').replace(/\/$/u, '');
test.skip(!sessionId || !runId || !artifactId, '需要现成的 Canvas Agent Run 与 Artifact');

test('真实 Canvas 页面可在历史对话中打开并执行隔离脚本', async ({ page }, info) => {
  const response = await page.request.get(api + '/api/v1/runtime-events/sessions/' + sessionId + '?limit=100');
  expect(response.status()).toBe(200);
  const history = await response.json() as { data?: { session?: { title?: string } } };
  await page.addInitScript((id) => {
    window.sessionStorage.setItem('runtime-active-session-id', id);
  }, sessionId!);
  await page.goto('/');
  expect(history.data?.session?.title).toBeTruthy();
  await page.getByRole('button', { name: /^发布 HTML 画布/u }).first().click();
  const button = page.getByRole('button', { name: '打开画布 第五阶段画布验收' });
  await expect(button).toBeVisible({ timeout: 20_000 });
  await button.click();
  const workspace = info.project.name === 'mobile' ? page.getByRole('dialog')
    : page.getByRole('complementary', { name: '运行详情工作区' });
  const heading = info.project.name === 'mobile'
    ? workspace.getByRole('banner').getByRole('heading', { name: 'canvas-acceptance.html' })
    : workspace.getByRole('heading', { name: 'canvas-acceptance.html', level: 2 });
  await expect(heading).toBeVisible();
  const frame = workspace.getByTitle('隔离的 HTML Artifact 预览');
  await expect(frame).toBeVisible();
  await expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
  await expect(frame).not.toHaveAttribute('sandbox', /allow-same-origin/u);
  await expect(frame.contentFrame().getByRole('heading', { name: '第五阶段画布验收' })).toBeVisible();
  await frame.contentFrame().getByRole('button', { name: '切换文字' }).click();
  await expect(frame.contentFrame().getByText('当前文字：验收已通过')).toBeVisible();
  const metadata = await page.request.get(api + '/api/v1/artifacts/runs/' + runId + '/' + artifactId);
  expect((await metadata.json()).data.preview).toEqual({ kind: 'html', schemaVersion: 'v1' });
});

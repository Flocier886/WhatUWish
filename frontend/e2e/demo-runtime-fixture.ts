import type { Page } from '@playwright/test';
import { createLiveReplay, demoAgents, DEMO_SESSION_ID, historySuccessReplay } from '../src/mocks/runtime-fixtures';
import type { RunSummary, SessionSummary } from '../src/domain/runtime/types';

/** 给旧演示路径提供确定性的 API/SSE，避免测试依赖本机模型与历史数据。 */
export async function installDemoRuntimeFixture(page: Page): Promise<void> {
  const sessions = new Map<string, SessionSummary>([
    [DEMO_SESSION_ID, { sessionId: DEMO_SESSION_ID, title: '课程反馈行动建议', updatedAt: '2026-07-23T02:00:00Z', runCount: 1 }],
  ]);
  let createdCount = 0;
  let runCount = 0;

  await page.route('http://127.0.0.1:8091/api/v1/**', async (route) => {
    const { pathname } = new URL(route.request().url());
    const method = route.request().method();
    const json = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0000', data }) });

    if (pathname.endsWith('/runtime-events/agents')) return json(demoAgents.map((agent) => ({ agentId: agent.agentId, displayName: agent.name, runtimeKind: agent.strategy, available: agent.availability === 'AVAILABLE' })));
    if (pathname.endsWith('/runtime-events/sessions') && method === 'GET') return json([...sessions.values()]);
    if (pathname.endsWith('/conversation-files/sessions') && method === 'POST') {
      createdCount += 1;
      const sessionId = `e2e-new-${createdCount}`;
      sessions.set(sessionId, { sessionId, title: '新建对话', updatedAt: new Date().toISOString(), runCount: 0 });
      return json({ sessionId, token: `e2e-token-${createdCount}`, expiresAt: new Date(Date.now() + 3600_000).toISOString() });
    }
    if (pathname.endsWith('/desktop-controls')) return json([]);
    if (pathname.endsWith('/browser/status')) return json({ connected: false });
    if (pathname.endsWith('/agent-browser/status')) return json({ exists: false });
    if (pathname.endsWith(`/runtime-events/sessions/${DEMO_SESSION_ID}`)) {
      return json({ session: sessions.get(DEMO_SESSION_ID), replays: [{ ...historySuccessReplay.run, events: historySuccessReplay.events }] });
    }
    if (/\/runtime-events\/sessions\/e2e-new-\d+$/.test(pathname)) {
      const sessionId = pathname.split('/').at(-1)!;
      return json({ session: sessions.get(sessionId), replays: [] });
    }
    if (pathname.endsWith('/runtime-events/run') && method === 'POST') {
      runCount += 1;
      const input = route.request().postDataJSON() as { aiAgentId: string; message: string; sessionId: string };
      const run: RunSummary = { runId: `e2e-run-${runCount}`, requestId: `e2e-request-${runCount}`, sessionId: input.sessionId, agentId: input.aiAgentId, task: input.message, status: 'RUNNING' };
      const frames = createLiveReplay(run, /失败/.test(input.message)).events.map((event) => `event: runtime-event\ndata: ${JSON.stringify(event)}\n\n`).join('');
      return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'X-Run-Id': run.runId, 'X-Request-Id': run.requestId, 'Access-Control-Expose-Headers': 'X-Run-Id, X-Request-Id' }, body: frames });
    }
    return route.fulfill({ status: 404, body: '' });
  });
}

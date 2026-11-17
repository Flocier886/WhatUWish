import type { RuntimeDataSource } from '@/gateways/RuntimeDataSource';
import type { ConversationSnapshot, RunReplay, RunSummary, RuntimeEvent, SessionSummary, StartRunCommand } from '@/domain/runtime/types';
import { DEMO_SESSION_ID, createLiveReplay, demoAgents, historyRuns, replayFixtures } from './runtime-fixtures';

const pause = (milliseconds: number) => new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds));

export class DemoRuntimeDataSource implements RuntimeDataSource {
  private readonly replays = new Map<string, RunReplay>(Object.entries(replayFixtures));
  private readonly runs = new Map<string, RunSummary>(historyRuns.map((run) => [run.runId, run]));
  private counter = 0;
  private readonly sessions = new Map<string, SessionSummary>([
    [DEMO_SESSION_ID, { sessionId: DEMO_SESSION_ID, title: '课程反馈行动建议', updatedAt: '2026-07-23T02:00:00Z', runCount: historyRuns.filter((run) => run.sessionId === DEMO_SESSION_ID).length, messageCount: historyRuns.filter((run) => run.sessionId === DEMO_SESSION_ID).length * 2 }],
    ['session-demo-002', { sessionId: 'session-demo-002', title: '下周课程设计讨论', updatedAt: '2026-07-22T09:00:00Z', runCount: 0, messageCount: 0 }],
    ['session-demo-003', { sessionId: 'session-demo-003', title: '课程运营复盘', updatedAt: '2026-07-21T09:00:00Z', runCount: 1, messageCount: 2 }],
  ]);

  async listSessions() { await pause(80); return [...this.sessions.values()].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)); }

  async createSession(): Promise<SessionSummary> {
    await pause(80);
    this.counter += 1;
    const session = { sessionId: `session-live-${this.counter}`, title: '新建对话', updatedAt: new Date().toISOString(), runCount: 0, messageCount: 0 };
    this.sessions.set(session.sessionId, session);
    return session;
  }

  async getConversation(sessionId: string): Promise<ConversationSnapshot> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error('未找到请求的会话。');
    const runs = await this.listRuns(sessionId);
    const replays = await Promise.all(runs.map((run) => this.getRunReplay(run.runId)));
    return { session, replays };
  }

  async listAgents() { await pause(120); return demoAgents; }

  async startRun(command: StartRunCommand): Promise<RunSummary> {
    await pause(160);
    this.counter += 1;
    const now = new Date().toISOString();
    const runId = `run-live-${this.counter}`;
    const run: RunSummary = {
      runId, sessionId: command.sessionId || DEMO_SESSION_ID, requestId: `request-live-${this.counter}`,
      agentId: command.agentId, task: command.task.trim(), status: 'CONNECTING', startedAt: now,
    };
    const replay = createLiveReplay(run, /失败|fail/i.test(command.task));
    this.runs.set(runId, run);
    this.replays.set(runId, replay);
    const session = this.sessions.get(run.sessionId);
    if (session) this.sessions.set(run.sessionId, { ...session, title: session.runCount === 0 ? run.task.slice(0, 22) : session.title, updatedAt: now, runCount: session.runCount + 1, messageCount: (session.messageCount ?? 0) + 2 });
    return run;
  }

  async subscribeRunEvents(runId: string, listener: (event: RuntimeEvent) => void, signal: AbortSignal): Promise<void> {
    const replay = this.replays.get(runId);
    if (!replay) throw new Error('未找到请求的 Run。');
    const events = replay.events;
    const delivery = events.length > 3 ? [events[0], events[2], events[1], events[1], ...events.slice(3)] : events;
    for (const item of delivery) {
      if (signal.aborted) return;
      await pause(260);
      if (signal.aborted) return;
      listener(item);
    }
  }

  async listRuns(sessionId: string): Promise<RunSummary[]> {
    await pause(100);
    return [...this.runs.values()]
      .filter((run) => run.sessionId === sessionId)
      .sort((left, right) => (right.startedAt ?? '').localeCompare(left.startedAt ?? ''))
      .slice(0, 4);
  }

  async getRunReplay(runId: string): Promise<RunReplay> {
    await pause(180);
    const replay = this.replays.get(runId);
    if (replay) return replay;
    const run = this.runs.get(runId);
    if (!run) throw new Error('未找到历史 Run。');
    return createLiveReplay({ ...run, status: 'RUNNING', finishedAt: undefined, summary: undefined, error: undefined, isHistorical: true }, run.status === 'FAILED');
  }
}

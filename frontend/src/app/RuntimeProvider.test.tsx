import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RuntimeProvider, useRuntimeContext } from './RuntimeProvider';
import type { RuntimeDataSource } from '@/gateways/RuntimeDataSource';
import type { RunReplay, RunSummary, RuntimeEvent, ToolProgressPayload } from '@/domain/runtime/types';

const run: RunSummary = {
  runId: 'run-disconnected',
  sessionId: 'session-demo-001',
  requestId: 'request-disconnected',
  agentId: 'react-runtime',
  task: '调研运行时回补',
  status: 'CONNECTING',
};

const progressPayload: ToolProgressPayload = {
  runtimeEventId: 'runtime-fact-1',
  toolInvocationId: 'tool-deep-search-1',
  toolName: 'deep_search_tool',
  toolSequence: 1,
  stage: 'search_completed',
  status: 'completed',
  message: '已获得候选来源',
  presentationKind: 'deep_search.search_results.v1',
  presentationSchemaVersion: '1',
  presentationData: { sources: [] },
};

const progressEvent: RuntimeEvent<ToolProgressPayload> = {
  schemaVersion: 'v1',
  eventId: progressPayload.runtimeEventId,
  sequence: 2,
  eventType: 'TOOL_PROGRESS',
  occurredAt: '2026-08-05T08:00:00Z',
  sessionId: run.sessionId,
  runId: run.runId,
  requestId: run.requestId,
  agentId: run.agentId,
  payload: progressPayload,
};

const startedEvent: RuntimeEvent = {
  schemaVersion: 'v1',
  eventId: 'run-started-1',
  sequence: 1,
  eventType: 'RUN_STARTED',
  occurredAt: '2026-08-05T07:59:59Z',
  sessionId: run.sessionId,
  runId: run.runId,
  requestId: run.requestId,
  agentId: run.agentId,
  payload: { status: 'RUNNING', task: run.task },
};

const completedEvent: RuntimeEvent = {
  schemaVersion: 'v1',
  eventId: 'run-completed-1',
  sequence: 3,
  eventType: 'RUN_COMPLETED',
  occurredAt: '2026-08-05T08:00:02Z',
  sessionId: run.sessionId,
  runId: run.runId,
  requestId: run.requestId,
  agentId: run.agentId,
  payload: { status: 'COMPLETED', summary: '研究已完成' },
};

function replay(events: RuntimeEvent[]): RunReplay {
  return { run: { ...run, status: 'RUNNING', isHistorical: true }, events };
}

function createDataSource(overrides: Partial<RuntimeDataSource> = {}): RuntimeDataSource {
  return {
    listSessions: async () => [],
    createSession: async () => ({ sessionId: 'session-created', title: '新建对话', updatedAt: '', runCount: 0 }),
    getConversation: async (sessionId) => ({
      session: { sessionId, title: '测试会话', updatedAt: '', runCount: 0 },
      replays: [],
    }),
    listAgents: async () => [],
    startRun: async () => run,
    subscribeRunEvents: async () => undefined,
    listRuns: async () => [],
    getRunReplay: async () => replay([]),
    ...overrides,
  };
}

function Probe() {
  const { state, startRun } = useRuntimeContext();
  const runState = state.runsById[run.runId];
  const events = state.eventsByRunId[run.runId] ?? [];
  const latestIssue = state.issues.at(-1)?.message ?? '';

  return (
    <>
      <button type="button" onClick={() => void startRun({ agentId: run.agentId, sessionId: run.sessionId, task: run.task })}>
        启动
      </button>
      <output data-testid="runtime-state">
        {`${runState?.status ?? 'MISSING'}|${state.streamState}|${events.length}|${events.map((event) => event.eventId).join(',')}|${latestIssue}`}
      </output>
    </>
  );
}

function SessionProbe() {
  const { state, createSession } = useRuntimeContext();
  return <><button type="button" onClick={() => void createSession()}>创建文件会话</button>
    <output data-testid="active-session">{state.activeSessionId}</output></>;
}

function SessionOrderProbe() {
  const { sessions } = useRuntimeContext();
  return <output data-testid="session-order">{sessions.map((session) => session.sessionId).join(',')}</output>;
}

describe('RuntimeProvider', () => {
  it('默认会话详情晚到时仍保持最近会话在列表最前', async () => {
    window.localStorage.removeItem('runtime-active-session-id');
    window.sessionStorage.removeItem('runtime-active-session-id');
    const source = createDataSource({
      listSessions: async () => [
        { sessionId: 'recent', title: '最近会话', updatedAt: '2026-11-14T12:00:00Z', runCount: 1 },
      ],
      getConversation: async (sessionId) => ({ session: {
        sessionId, title: '旧默认会话', updatedAt: '2026-07-25T12:00:00Z', runCount: 0,
      }, replays: [] }),
    });
    render(<RuntimeProvider dataSource={source}><SessionOrderProbe /></RuntimeProvider>);
    await waitFor(() => expect(screen.getByTestId('session-order')).toHaveTextContent('recent,session-demo-001'));
  });

  it('刷新重建 Provider 后恢复本标签页选中的文件会话', async () => {
    window.sessionStorage.removeItem('runtime-active-session-id');
    window.localStorage.removeItem('runtime-active-session-id');
    const source = createDataSource();
    const first = render(<RuntimeProvider dataSource={source}><SessionProbe /></RuntimeProvider>);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '创建文件会话' })); });
    await waitFor(() => expect(screen.getByTestId('active-session')).toHaveTextContent('session-created'));
    await act(async () => { first.unmount(); });
    await act(async () => { render(<RuntimeProvider dataSource={source}><SessionProbe /></RuntimeProvider>); });
    expect(screen.getByTestId('active-session')).toHaveTextContent('session-created');
    window.sessionStorage.removeItem('runtime-active-session-id');
    window.localStorage.removeItem('runtime-active-session-id');
  });

  it('新标签页恢复正在执行的 Run，并持续回放到终态', async () => {
    window.localStorage.setItem('runtime-active-session-id', run.sessionId);
    const getRunReplay = vi.fn(async () => ({ run: { ...run, status: 'COMPLETED' as const, isHistorical: true }, events: [progressEvent, completedEvent] }));
    const source = createDataSource({
      getConversation: async (sessionId) => ({
        session: { sessionId, title: '搜索测试', updatedAt: '', runCount: 1 },
        replays: [{ run: { ...run, status: 'RUNNING', isHistorical: true }, events: [progressEvent] }],
      }),
      getRunReplay,
    });
    try {
      render(<RuntimeProvider dataSource={source}><Probe /></RuntimeProvider>);
      await waitFor(() => expect(screen.getByTestId('runtime-state')).toHaveTextContent('RUNNING'));
      await waitFor(() => expect(screen.getByTestId('runtime-state')).toHaveTextContent('COMPLETED|IDLE|2|runtime-fact-1,run-completed-1'), { timeout: 3500 });
      expect(getRunReplay).toHaveBeenCalledWith(run.runId);
    } finally {
      window.localStorage.removeItem('runtime-active-session-id');
    }
  });

  it('SSE 异常后以包含终态的 Replay 原子回补已提交的 Progress', async () => {
    const getRunReplay = vi.fn(async () => replay([progressEvent, completedEvent]));
    const dataSource = createDataSource({
      getRunReplay,
      subscribeRunEvents: async (_runId, listener) => {
        listener(startedEvent);
        listener(progressEvent);
        throw new Error('connection closed');
      },
    });

    render(<RuntimeProvider dataSource={dataSource}><Probe /></RuntimeProvider>);
    fireEvent.click(screen.getByRole('button', { name: '启动' }));

    await waitFor(() => {
      expect(screen.getByTestId('runtime-state')).toHaveTextContent('COMPLETED|IDLE|2|runtime-fact-1,run-completed-1');
    });
    expect(getRunReplay).toHaveBeenCalledTimes(1);
  });

  it('回补后仍未终态时转为持续回放', async () => {
    const dataSource = createDataSource({
      getRunReplay: async () => replay([progressEvent]),
      subscribeRunEvents: async (_runId, listener) => {
        listener(startedEvent);
        throw new Error('connection closed');
      },
    });

    render(<RuntimeProvider dataSource={dataSource}><Probe /></RuntimeProvider>);
    fireEvent.click(screen.getByRole('button', { name: '启动' }));

    await waitFor(() => {
      expect(screen.getByTestId('runtime-state')).toHaveTextContent('RUNNING|CONNECTING|1|runtime-fact-1|实时连接已断开，正在通过历史回放跟踪运行。');
    });
  });

  it('回补失败时保留断开前已经展示的 Progress', async () => {
    const dataSource = createDataSource({
      getRunReplay: async () => { throw new Error('history unavailable'); },
      subscribeRunEvents: async (_runId, listener) => {
        listener(startedEvent);
        listener(progressEvent);
        throw new Error('connection closed');
      },
    });

    render(<RuntimeProvider dataSource={dataSource}><Probe /></RuntimeProvider>);
    fireEvent.click(screen.getByRole('button', { name: '启动' }));

    await waitFor(() => {
      expect(screen.getByTestId('runtime-state')).toHaveTextContent('RUNNING|ERROR|2|run-started-1,runtime-fact-1|运行输出连接已断开，且无法回补已提交的过程。');
    });
  });

  it('主动取消订阅时不发起历史回补', async () => {
    const getRunReplay = vi.fn(async () => replay([]));
    const subscribeRunEvents = vi.fn((_runId: string, _listener: (event: RuntimeEvent) => void, signal: AbortSignal) => new Promise<void>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
    }));
    const { unmount } = render(<RuntimeProvider dataSource={createDataSource({ getRunReplay, subscribeRunEvents })}><Probe /></RuntimeProvider>);
    fireEvent.click(screen.getByRole('button', { name: '启动' }));

    await waitFor(() => expect(subscribeRunEvents).toHaveBeenCalledTimes(1));
    unmount();

    await waitFor(() => expect(getRunReplay).not.toHaveBeenCalled());
  });
});

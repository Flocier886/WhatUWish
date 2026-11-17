import { afterEach, describe, expect, it, vi } from 'vitest';
import { RuntimeEventApiDataSource } from './RuntimeEventApiDataSource';

describe('RuntimeEventApiDataSource', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('从 Runtime Event 目录加载新 ReAct，而不是读取旧数据库 Agent 接口', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: '0000',
      data: [{
        agentId: 'react-runtime',
        displayName: 'ReAct Runtime',
        description: '独立运行时能力',
        runtimeKind: 'REACT',
        available: true,
      }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const agents = await new RuntimeEventApiDataSource().listAgents();

    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:8091/api/v1/runtime-events/agents',
      { headers: { Accept: 'application/json' } },
    );
    expect(agents).toEqual([{
      agentId: 'react-runtime',
      name: 'ReAct Runtime',
      description: '独立运行时能力',
      strategy: 'REACT',
      availability: 'AVAILABLE',
    }]);
  });

  it('保留后端对不可用运行时能力的明确状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: '0000',
      data: [{ agentId: 'future-runtime', displayName: 'Future Runtime', runtimeKind: 'REACT', available: false }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    const [agent] = await new RuntimeEventApiDataSource().listAgents();

    expect(agent.availability).toBe('UNAVAILABLE');
  });

  it('从 HTTP 恢复历史 Session', async () => {
    const event = { schemaVersion: 'v1', eventId: 'history:run-a:final', sequence: 1, eventType: 'RUN_COMPLETED', occurredAt: '2026-07-25T12:00:00.000Z', sessionId: 'session-a', runId: 'run-a', requestId: 'request-a', agentId: 'react-runtime', payload: { status: 'COMPLETED', summary: 'done' } };
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url.includes('/sessions?')) return Promise.resolve(new Response(JSON.stringify({ code: '0000', data: [{ sessionId: 'session-a', title: '历史会话', updatedAt: '2026-07-25T12:00:00.000Z', runCount: 1 }] }), { status: 200 }));
      return Promise.resolve(new Response(JSON.stringify({ code: '0000', data: { session: { sessionId: 'session-a', title: '历史会话', updatedAt: '2026-07-25T12:00:00.000Z', runCount: 1 }, replays: [{ runId: 'run-a', sessionId: 'session-a', requestId: 'request-a', agentId: 'react-runtime', task: '测试任务', status: 'COMPLETED', historical: true, dataIncomplete: false, events: [event] }] } }), { status: 200 }));
    }));
    const source = new RuntimeEventApiDataSource();
    expect((await source.listSessions()).map((session) => session.sessionId)).toEqual(['session-a']);
    const conversation = await source.getConversation('session-a');
    expect(conversation.replays[0].run.isHistorical).toBe(true);
    expect(conversation.replays[0].events[0].eventType).toBe('RUN_COMPLETED');
  });

  it('从后端统一历史恢复受控会话，无需浏览器令牌', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/sessions?')) return Promise.resolve(new Response(JSON.stringify({ code: '0000', data: [
        { sessionId: 'controlled-session', title: '搜索测试', updatedAt: '2026-11-14T12:00:00Z', runCount: 1 },
      ] }), { status: 200 }));
      return Promise.resolve(new Response(JSON.stringify({ code: '0000', data: {
        session: { sessionId: 'controlled-session', title: '搜索测试', updatedAt: '2026-11-14T12:00:00Z', runCount: 1 }, replays: [],
      } }), { status: 200 }));
    });
    vi.stubGlobal('fetch', fetchMock);
    const source = new RuntimeEventApiDataSource();
    expect(await source.listSessions()).toMatchObject([{ sessionId: 'controlled-session', title: '搜索测试', runCount: 1 }]);
    await source.getConversation('controlled-session');
    expect(fetchMock.mock.calls[1][1].headers).toEqual({ Accept: 'application/json' });
  });

  it('历史映射接受 TOOL_STARTED 并保留给 reducer 做安全白名单清洗', async () => {
    const started = { schemaVersion: 'v1', eventId: 'history:run-a:tool-started', sequence: 1, eventType: 'TOOL_STARTED', occurredAt: '2026-08-16T12:00:00.000Z', sessionId: 'session-a', runId: 'run-a', requestId: 'request-a', agentId: 'react-runtime', payload: { toolInvocationId: 'tool-1', toolName: 'skill_read_tool', status: 'RUNNING', argumentsSummary: 'path=report.md' } };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0000', data: { session: { sessionId: 'session-a', title: '历史会话', updatedAt: '2026-08-16T12:00:00.000Z', runCount: 1 }, replays: [{ runId: 'run-a', sessionId: 'session-a', requestId: 'request-a', agentId: 'react-runtime', task: '测试任务', status: 'RUNNING', historical: true, events: [started] }] } }), { status: 200 })));

    const conversation = await new RuntimeEventApiDataSource().getConversation('session-a');

    expect(conversation.replays[0].events[0]).toMatchObject({ eventType: 'TOOL_STARTED', payload: { toolInvocationId: 'tool-1', status: 'RUNNING' } });
  });

  it('校验 Live Report delta，但拒绝将不完整 delta 当作 Runtime Event', async () => {
    const delta = { schemaVersion: 'v1', eventId: 'live-delta-1', sequence: 1, eventType: 'REPORT_CONTENT_DELTA', occurredAt: '2026-08-28T12:00:00.000Z', sessionId: 'session-a', runId: 'run-a', requestId: 'request-a', agentId: 'react-runtime', payload: { schemaVersion: 'report.content_delta.v1', toolInvocationId: 'tool-1', toolName: 'report_tool', outputType: 'html', chunkIndex: 1, content: '<h1>草稿</h1>' } };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0000', data: { session: { sessionId: 'session-a', title: 'Live', updatedAt: '2026-08-28T12:00:00.000Z', runCount: 1 }, replays: [{ runId: 'run-a', sessionId: 'session-a', requestId: 'request-a', agentId: 'react-runtime', task: '测试任务', status: 'RUNNING', historical: true, events: [delta] }] } }), { status: 200 })));

    const conversation = await new RuntimeEventApiDataSource().getConversation('session-a');

    expect(conversation.replays[0].events[0].eventType).toBe('REPORT_CONTENT_DELTA');
  });
});

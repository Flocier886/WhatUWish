import { describe, expect, it } from 'vitest';
import { DemoRuntimeDataSource } from './DemoRuntimeDataSource';
import { DEMO_SESSION_ID } from './runtime-fixtures';

describe('DemoRuntimeDataSource', () => {
  it('提供可用 Agent、历史 Run 与完整回放', async () => {
    const source = new DemoRuntimeDataSource();
    const agents = await source.listAgents();
    const runs = await source.listRuns(DEMO_SESSION_ID);
    const conversation = await source.getConversation(DEMO_SESSION_ID);
    const replay = await source.getRunReplay('run-history-success');
    expect(agents.some((agent) => agent.strategy === 'REACT')).toBe(true);
    expect(runs).toHaveLength(4);
    expect(conversation.replays).toHaveLength(4);
    expect(conversation.replays[0].run.sessionId).toBe(DEMO_SESSION_ID);
    expect(replay.events[0].sequence).toBe(1);
  });
});

import { act, renderHook } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { describe, expect, it } from 'vitest';
import type { RunReplay } from '@/domain/runtime/types';
import { RuntimeProvider } from '@/app/RuntimeProvider';
import { DemoRuntimeDataSource } from '@/mocks/DemoRuntimeDataSource';
import { useRunReplay } from './useRunReplay';

describe('useRunReplay', () => {
  it('读取历史 replay 后返回该 Run', async () => {
    const source = new DemoRuntimeDataSource();
    const wrapper = ({ children }: PropsWithChildren) => <RuntimeProvider dataSource={source}>{children}</RuntimeProvider>;
    const { result } = renderHook(() => useRunReplay(), { wrapper });
    let replay: RunReplay | undefined;
    await act(async () => { replay = await result.current.openReplay('run-history-success'); });
    expect(replay?.events.length).toBeGreaterThan(0);
    expect(replay?.run.runId).toBe('run-history-success');
  });

  it('为未预置的历史 Run 也提供完整演示回放', async () => {
    const source = new DemoRuntimeDataSource();
    const replay = await source.getRunReplay('run-history-2');
    expect(replay.events.length).toBeGreaterThan(3);
  });
});

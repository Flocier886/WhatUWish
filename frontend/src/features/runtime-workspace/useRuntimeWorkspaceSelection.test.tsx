import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { RuntimeWorkspaceTarget } from './runtime-workspace-target';
import { useRuntimeWorkspaceSelection } from './useRuntimeWorkspaceSelection';

const first: RuntimeWorkspaceTarget = { kind: 'operation', runId: 'run-1', operationId: 'tool-1' };
const second: RuntimeWorkspaceTarget = { kind: 'operation', runId: 'run-1', operationId: 'tool-2' };
const stage: RuntimeWorkspaceTarget = { kind: 'stage', runId: 'run-1', operationId: 'tool-2', stageId: 'stage-2' };
const artifact: RuntimeWorkspaceTarget = { kind: 'artifact', runId: 'run-1', artifactId: 'artifact-1' };

describe('useRuntimeWorkspaceSelection', () => {
  it('默认跟随最新 Operation', () => {
    const { result, rerender } = renderHook(
      ({ available, latest }) => useRuntimeWorkspaceSelection('session-1', available, latest),
      { initialProps: { available: [first], latest: first as RuntimeWorkspaceTarget | undefined } },
    );
    expect(result.current.target).toEqual(first);
    rerender({ available: [first, second], latest: second });
    expect(result.current.target).toEqual(second);
    expect(result.current.following).toBe(true);
  });

  it('跟随目标可以精确更新到最新 Rich Stage', () => {
    const { result, rerender } = renderHook(
      ({ latest }) => useRuntimeWorkspaceSelection('session-1', [first, second, stage], latest),
      { initialProps: { latest: second as RuntimeWorkspaceTarget | undefined } },
    );
    rerender({ latest: stage });
    expect(result.current.target).toEqual(stage);
    expect(result.current.view).toBe('activity');
    expect(result.current.following).toBe(true);
  });

  it('动态与文件视图复用唯一 target，并可从文件恢复跟随最新', () => {
    const { result } = renderHook(() => useRuntimeWorkspaceSelection('session-1', [first, second, artifact], second, artifact));
    act(() => result.current.selectView('files'));
    expect(result.current.view).toBe('files');
    expect(result.current.target).toEqual(artifact);
    expect(result.current.following).toBe(false);
    expect(result.current.hasNewActivity).toBe(true);
    act(() => result.current.resumeFollowing());
    expect(result.current.view).toBe('activity');
    expect(result.current.target).toEqual(second);
    expect(result.current.following).toBe(true);
  });

  it('主对话选择 Stage 或 Artifact 时自动切换到对应一级视图', () => {
    const { result } = renderHook(() => useRuntimeWorkspaceSelection('session-1', [second, stage, artifact], second, artifact));
    act(() => result.current.select(artifact));
    expect(result.current.view).toBe('files');
    expect(result.current.target).toEqual(artifact);
    act(() => result.current.select(stage));
    expect(result.current.view).toBe('activity');
    expect(result.current.target).toEqual(stage);
    expect(result.current.following).toBe(false);
  });

  it('用户固定历史目标后新事件不抢占，恢复跟随后才回到最新', () => {
    const { result, rerender } = renderHook(
      ({ available, latest }) => useRuntimeWorkspaceSelection('session-1', available, latest),
      { initialProps: { available: [first, second], latest: second as RuntimeWorkspaceTarget | undefined } },
    );
    act(() => result.current.select(first));
    const third: RuntimeWorkspaceTarget = { kind: 'operation', runId: 'run-1', operationId: 'tool-3' };
    rerender({ available: [first, second, third], latest: third });
    expect(result.current.target).toEqual(first);
    expect(result.current.following).toBe(false);
    act(() => result.current.resumeFollowing());
    expect(result.current.target).toEqual(third);
    expect(result.current.following).toBe(true);
  });

  it('固定目标失效时确定性回退最新 Operation', () => {
    const { result, rerender } = renderHook(
      ({ available, latest }) => useRuntimeWorkspaceSelection('session-1', available, latest),
      { initialProps: { available: [first, second], latest: second as RuntimeWorkspaceTarget | undefined } },
    );
    act(() => result.current.select(first));
    rerender({ available: [second], latest: second });
    expect(result.current.target).toEqual(second);
    expect(result.current.following).toBe(true);
  });

  it('会话切换时重置固定模式并关闭移动 Sheet', () => {
    const matchMedia = vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
    const { result, rerender } = renderHook(
      ({ sessionId }) => useRuntimeWorkspaceSelection(sessionId, [first], first),
      { initialProps: { sessionId: 'session-1' } },
    );
    act(() => result.current.select(first));
    expect(result.current.mobileOpen).toBe(true);
    rerender({ sessionId: 'session-2' });
    expect(result.current.following).toBe(true);
    expect(result.current.mobileOpen).toBe(false);
    matchMedia.mockRestore();
  });

  it('关闭移动 Sheet 后把焦点还给触发按钮', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();
    const { result } = renderHook(() => useRuntimeWorkspaceSelection('session-1', [first], first));
    act(() => result.current.select(first));
    const sheetTarget = document.createElement('button');
    document.body.appendChild(sheetTarget);
    sheetTarget.focus();
    act(() => result.current.select({ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'stage-1' }));
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();
    act(() => result.current.setMobileOpen(false));
    act(() => result.current.restoreMobileTriggerFocus());
    expect(trigger).toHaveFocus();
    trigger.remove();
    sheetTarget.remove();
    outside.remove();
    vi.restoreAllMocks();
  });
});

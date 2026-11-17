import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createInitialState, runtimeReducer } from '@/domain/runtime/event-reducer';
import { projectRuntimeOperations } from '@/domain/runtime/runtime-operation';
import { historySuccessReplay } from '@/mocks/runtime-fixtures';
import { ConversationStream } from './ConversationStream';

function operationsFor(state: ReturnType<typeof createInitialState>) {
  const runId = historySuccessReplay.run.runId;
  return { [runId]: projectRuntimeOperations(state.eventsByRunId[runId] ?? [], state.artifactsById) };
}

describe('ConversationStream', () => {
  it('会话读取失败时显示原因，而不是空白欢迎页', () => {
    const state = runtimeReducer(createInitialState('controlled-session'), { type: 'ISSUE_REPORTED', issue: {
      id: 'conversation-1', sessionId: 'controlled-session', message: '无法加载此会话的历史记录。',
    } });
    render(<ConversationStream state={state} />);
    expect(screen.getByRole('alert')).toHaveTextContent('无法加载此会话的历史记录。');
    expect(screen.queryByText('有什么可以一起推进的吗？')).not.toBeInTheDocument();
  });

  it('在对话中展开工具结果，并通过统一 target 打开工作区详情', async () => {
    const state = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    const onWorkspaceSelect = vi.fn();
    render(<ConversationStream state={state} operationsByRunId={operationsFor(state)} onWorkspaceSelect={onWorkspaceSelect} />);

    expect(screen.getByText(historySuccessReplay.run.task)).toBeInTheDocument();
    expect(screen.getByText(/已完成反馈汇总/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /搜索内容.*pattern=满意度/ }));
    expect(screen.getByText(/48 条反馈中发现 19 条/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: '在工作区查看 搜索内容' }));
    expect(onWorkspaceSelect).toHaveBeenCalledWith({ kind: 'operation', runId: 'run-history-success', operationId: 'tool-history-1' });
    expect(screen.queryByText(/artifact:feedback-summary-july/)).not.toBeInTheDocument();
  });

  it('only renders messages belonging to the active session', () => {
    let state = runtimeReducer(createInitialState('session-empty'), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    state = runtimeReducer(state, { type: 'SESSION_SELECTED', sessionId: 'session-empty' });
    render(<ConversationStream state={state} />);
    expect(screen.getByRole('heading', { name: '有什么可以一起推进的吗？' })).toBeInTheDocument();
    expect(screen.queryByText(historySuccessReplay.run.task)).not.toBeInTheDocument();
  });

  it('renders one final answer even when process and final events all contain text', () => {
    const state = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    render(<ConversationStream state={state} />);
    expect(screen.getAllByTestId('final-answer')).toHaveLength(1);
    expect(screen.queryByText(/我会先读取反馈目录/)).not.toBeInTheDocument();
    expect(screen.queryByText(/反馈集中在节奏/)).not.toBeInTheDocument();
  });

  it('终答可直接复制原文并显示结果', async () => {
    const user = userEvent.setup();
    const clipboardWrite = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    const state = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    render(<ConversationStream state={state} />);
    await user.click(screen.getByRole('button', { name: '复制回答原文' }));
    expect(clipboardWrite).toHaveBeenCalledWith(expect.stringContaining('已完成反馈汇总'));
    expect(await screen.findByRole('status')).toHaveTextContent('已复制');
    clipboardWrite.mockRestore();
  });

  it('只为真实 Operation 和 Artifact 渲染可用详情入口', () => {
    const state = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    render(<ConversationStream state={state} operationsByRunId={operationsFor(state)} />);
    expect(screen.getByRole('button', { name: '执行过程 1 项操作已完成' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('button', { name: '查看文件详情 课程反馈聚合表.csv' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '展开运行状态' })).not.toBeInTheDocument();
  });

  it('终态过程默认展示工具行，用户仍可收起整个过程', async () => {
    const state = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events: historySuccessReplay.events });
    render(<ConversationStream state={state} operationsByRunId={operationsFor(state)} />);
    const toggle = screen.getByRole('button', { name: '执行过程 1 项操作已完成' });
    expect(screen.getByRole('button', { name: '在工作区查看 搜索内容' })).toBeInTheDocument();
    await userEvent.setup().click(toggle);
    expect(screen.queryByRole('button', { name: '在工作区查看 搜索内容' })).not.toBeInTheDocument();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('renders the formal artifact delivery card for a historical replay', () => {
    const state = runtimeReducer(createInitialState(), {
      type: 'REPLAY_LOADED', run: historySuccessReplay.run,
      events: historySuccessReplay.events.map((event) => event.eventType === 'ARTIFACT_CREATED'
        ? { ...event, payload: { ...event.payload, previewSupported: true, downloadSupported: true, preview: { kind: 'markdown', schemaVersion: 'v1' } } }
        : event),
    });
    render(<ConversationStream state={state} />);
    expect(screen.getByRole('button', { name: '预览文件 课程反馈聚合表.csv' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '下载文件 课程反馈聚合表.csv' })).toBeInTheDocument();
  });

  it('为当前 Run 与 History Replay 复用同一文件卡入口', () => {
    const events = historySuccessReplay.events.map((event) => event.eventType === 'ARTIFACT_CREATED'
      ? { ...event, payload: { ...event.payload, previewSupported: true, downloadSupported: true, preview: { kind: 'markdown', schemaVersion: 'v1' } } }
      : event);
    const liveRun = { ...historySuccessReplay.run, status: 'RUNNING' as const, summary: undefined, isHistorical: false };
    let liveState = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run: liveRun });
    events.filter((event) => event.eventType !== 'RUN_COMPLETED').forEach((event) => { liveState = runtimeReducer(liveState, { type: 'EVENT_RECEIVED', event }); });
    const { unmount } = render(<ConversationStream state={liveState} />);
    expect(screen.getByRole('button', { name: '预览文件 课程反馈聚合表.csv' })).toBeInTheDocument();
    unmount();

    const replayState = runtimeReducer(createInitialState(), { type: 'REPLAY_LOADED', run: historySuccessReplay.run, events });
    render(<ConversationStream state={replayState} />);
    expect(screen.getByRole('button', { name: '预览文件 课程反馈聚合表.csv' })).toBeInTheDocument();
  });

  it('shows active feedback before the first model response arrives', () => {
    const run = { ...historySuccessReplay.run, status: 'RUNNING' as const, summary: undefined, isHistorical: false };
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: historySuccessReplay.events[0] });
    render(<ConversationStream state={state} />);
    expect(screen.getByRole('status')).toHaveTextContent('正在分析你的问题');
    expect(screen.getByTestId('final-answer')).toHaveTextContent('正在生成回答');
  });

  it('运行中的过程组默认展开并原位展示当前工具', () => {
    const run = { ...historySuccessReplay.run, status: 'RUNNING' as const, summary: undefined, isHistorical: false };
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    historySuccessReplay.events
      .filter((event) => event.eventType !== 'RUN_COMPLETED')
      .forEach((event) => { state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event }); });
    const operations = operationsFor(state);
    const current = operations[run.runId][0];
    operations[run.runId] = [{ ...current, state: 'RUNNING' }];
    render(<ConversationStream state={state} operationsByRunId={operations} />);
    expect(screen.getByRole('button', { name: /正在执行任务.*正在搜索内容/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: '在工作区查看 搜索内容' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('正在');
  });
});

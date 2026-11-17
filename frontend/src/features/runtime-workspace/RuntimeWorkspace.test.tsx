import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { RuntimeOperation } from '@/domain/runtime/runtime-operation';
import type { ArtifactReference } from '@/domain/runtime/types';
import { RuntimeWorkspace } from './RuntimeWorkspace';

const operation: RuntimeOperation = {
  operationId: 'tool-1', toolName: 'deep_search_tool', state: 'PARTIAL_SUCCESS', lifecycleStartMissing: false,
  startedAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:03Z', argumentsSummary: '对象存储方案', observation: '已形成带来源的研究摘要', artifactIds: ['artifact-1'], isRich: true,
  stages: [{ stageId: 'search-results', slotKey: 'search-results', eventId: 'event-2', sequence: 2, occurredAt: '2026-08-16T00:00:02Z', status: 'completed', label: '搜索完成', summary: '2 条来源', details: [{ label: '公开文档', value: 'example.com', href: 'https://example.com/docs', description: '对象存储公开文档' }] }],
};
const artifact: ArtifactReference = { artifactId: 'artifact-1', runId: 'run-1', reference: 'artifact:report', name: '研究报告.md', artifactType: 'report', mimeType: 'text/markdown', size: 1024, summary: '带来源的研究报告', visibility: 'VISIBLE', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'markdown', schemaVersion: 'v1' } };

function props() {
  return { view: 'activity' as const, target: { kind: 'operation' as const, runId: 'run-1', operationId: 'tool-1' }, following: false, hasNewActivity: false, sessionRunIds: ['run-1'], operationsByRunId: { 'run-1': [operation] }, artifactsById: { 'artifact-1': artifact }, width: 390, collapsed: false, focused: false, mobileOpen: false, onMobileOpenChange: vi.fn(), onMobileCloseAutoFocus: vi.fn(), onSelect: vi.fn(), onViewChange: vi.fn(), onResumeFollowing: vi.fn(), onWidthChange: vi.fn(), onCollapsedChange: vi.fn(), onFocusedChange: vi.fn() };
}

describe('RuntimeWorkspace', () => {
  it('展示安全 Operation 详情并通过同一 target 选择阶段', async () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} />);
    expect(screen.getByRole('complementary', { name: '运行详情工作区' })).toHaveTextContent('已形成带来源的研究摘要');
    await userEvent.setup().click(screen.getByRole('button', { name: /搜索完成/ }));
    expect(runtimeProps.onSelect).toHaveBeenCalledWith({ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'search-results' });
    expect(screen.queryByText(/storageKey|storageRef|rawBody/)).not.toBeInTheDocument();
  });

  it('展示受控阶段详情且不输出原始 JSON', () => {
    const runtimeProps = props();
    const unavailable = { ...operation.stages[0], stageId: 'unknown', unavailable: true, summary: '该过程详情暂不可展示', details: [{ label: '阶段状态', value: 'running' }] };
    render(<RuntimeWorkspace {...runtimeProps} target={{ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'unknown' }} operationsByRunId={{ 'run-1': [{ ...operation, stages: [unavailable] }] }} />);
    expect(screen.getAllByText('该过程详情暂不可展示')).not.toHaveLength(0);
    expect(screen.getByText(/原始数据不会直接显示/)).toBeInTheDocument();
  });

  it('桌面阶段详情保持研究概览和阶段导航同屏，不需要进入返回页', () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} target={{ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'search-results' }} />);
    expect(screen.getByRole('navigation', { name: '研究阶段' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '查看总体结果与限制' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '返回研究概览' })).not.toBeInTheDocument();
  });

  it('阶段详情安全展示 versioned adapter 提供的来源链接', () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} target={{ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'search-results' }} />);
    expect(screen.getByRole('link', { name: '打开来源 公开文档' })).toHaveAttribute('href', 'https://example.com/docs');
    expect(screen.getByRole('link', { name: '打开来源 公开文档' })).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('不同 Rich Tool 使用自己的工作区名称', () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} operationsByRunId={{ 'run-1': [{ ...operation, toolName: 'code_interpreter' }] }} />);
    expect(screen.getByRole('heading', { name: '多步代码分析详情' })).toBeInTheDocument();
    expect(screen.getByLabelText('多步代码分析概览')).toBeInTheDocument();
  });

  it('文件工作区直接加载后端许可的预览并保留放大和下载', async () => {
    const previousFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, headers: new Headers({ 'content-type': 'text/markdown' }), text: async () => '# 可阅读的研究报告' });
    const runtimeProps = props();
    try {
      render(<RuntimeWorkspace {...runtimeProps} view="files" target={{ kind: 'artifact', runId: 'run-1', artifactId: 'artifact-1' }} />);
      expect(await screen.findByRole('heading', { name: '可阅读的研究报告' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '下载文件 研究报告.md' })).toHaveAttribute('href', expect.stringContaining('/run-1/artifact-1/download'));
      expect(screen.getByRole('button', { name: '放大阅读 研究报告.md' })).toBeInTheDocument();
    } finally { globalThis.fetch = previousFetch; }
  });

  it('文件失效时展示访问状态且不请求预览正文', async () => {
    const previousFetch = globalThis.fetch;
    globalThis.fetch = vi.fn();
    const runtimeProps = props();
    try {
      render(<RuntimeWorkspace {...runtimeProps} view="files" target={{ kind: 'artifact', runId: 'run-1', artifactId: 'artifact-1' }} artifactsById={{ 'artifact-1': { ...artifact, accessState: 'NOT_FOUND', previewSupported: false, downloadSupported: false } }} />);
      expect(await screen.findByRole('alert')).toHaveTextContent('这个文件不存在或已被删除');
      expect(screen.queryByRole('link', { name: '下载文件 研究报告.md' })).not.toBeInTheDocument();
      expect(globalThis.fetch).not.toHaveBeenCalled();
    } finally { globalThis.fetch = previousFetch; }
  });

  it('动态与文件一级视图通过受控回调切换，文件视图保留放大入口', async () => {
    const runtimeProps = props();
    const { rerender } = render(<RuntimeWorkspace {...runtimeProps} />);
    await userEvent.setup().click(screen.getByRole('tab', { name: /文件/ }));
    expect(runtimeProps.onViewChange).toHaveBeenCalledWith('files');
    rerender(<RuntimeWorkspace {...runtimeProps} view="files" target={{ kind: 'artifact', runId: 'run-1', artifactId: 'artifact-1' }} />);
    expect(screen.getByRole('button', { name: '放大阅读 研究报告.md' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '下载文件 研究报告.md' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('预览服务暂时不可用'));
  });

  it('折叠、专注与恢复跟随均由外部受控状态负责', async () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '折叠运行详情' }));
    await user.click(screen.getByRole('button', { name: '进入详情专注模式' }));
    await user.click(screen.getByRole('button', { name: '恢复跟随最新' }));
    expect(runtimeProps.onCollapsedChange).toHaveBeenCalledWith(true);
    expect(runtimeProps.onFocusedChange).toHaveBeenCalledWith(true);
    expect(runtimeProps.onResumeFollowing).toHaveBeenCalledOnce();
  });

  it('移动 Sheet 关闭动作交给受控 Dialog 并保留同一详情内容', async () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} mobileOpen />);
    expect(screen.getByRole('dialog')).toHaveTextContent('深度研究详情');
    await userEvent.setup().click(screen.getByRole('button', { name: '关闭运行详情' }));
    expect(runtimeProps.onMobileOpenChange).toHaveBeenCalledWith(false);
  });

  it('移动 Stage Sheet 保留返回同一父 Operation 的层级动作', async () => {
    const runtimeProps = props();
    render(<RuntimeWorkspace {...runtimeProps} target={{ kind: 'stage', runId: 'run-1', operationId: 'tool-1', stageId: 'search-results' }} mobileOpen />);
    await userEvent.setup().click(screen.getByRole('button', { name: '返回研究概览' }));
    expect(runtimeProps.onSelect).toHaveBeenCalledWith({ kind: 'operation', runId: 'run-1', operationId: 'tool-1' });
  });
});

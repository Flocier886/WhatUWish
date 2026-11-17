import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DesktopControlPayload } from '@/domain/runtime/types';
import { DesktopPreviewPanel } from '@/features/runtime-workspace/DesktopPreviewPanel';
import { DesktopControlCard } from './DesktopControlCard';

const control: DesktopControlPayload = {
  presentationSchemaVersion: 'v1', controlId: 'dc-1', sourceRunId: 'run-1',
  toolCallId: 'call-1', reason: '请检查浏览器', status: 'PENDING',
  streamUrl: 'https://desktop.example/vnc.html', holdUntil: 1_800_000_002,
};

afterEach(() => vi.useRealTimers());

describe('桌面接管截止时间', () => {
  it('到期后卡片停止提供完成操作', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(1_800_000_000_000));
    render(<DesktopControlCard control={control} onOpen={vi.fn()} onComplete={vi.fn(async () => true)} />);
    expect(screen.getByRole('button', { name: '我已完成' })).toBeEnabled();
    act(() => vi.advanceTimersByTime(2_100));
    expect(screen.getByRole('button', { name: '操作已结束' })).toBeDisabled();
  });

  it('到期后预览停止连接远端桌面', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(1_800_000_000_000));
    render(<DesktopPreviewPanel control={control} onClose={vi.fn()} onComplete={vi.fn(async () => true)} />);
    expect(screen.getByTitle('E2B 桌面实时预览')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(2_100));
    expect(screen.queryByTitle('E2B 桌面实时预览')).not.toBeInTheDocument();
    expect(screen.getByText(/桌面会话已结束/)).toBeInTheDocument();
  });

  it('失败记录只保留原因，不再提供完成入口', () => {
    render(<DesktopControlCard control={{ ...control, status: 'FAILED' }} onOpen={vi.fn()} onComplete={vi.fn(async () => true)} />);
    expect(screen.getByText('请检查浏览器')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '在工作区查看' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '操作已结束' })).toBeDisabled();
  });
});

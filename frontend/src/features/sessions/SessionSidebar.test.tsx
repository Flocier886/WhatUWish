import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SessionSidebar } from './SessionSidebar';

const sessions = [
  { sessionId: 'session-1', title: '课程反馈行动建议', updatedAt: '2026-07-24T09:30:00Z', runCount: 2, messageCount: 4 },
  { sessionId: 'session-2', title: '下周课程设计讨论', updatedAt: '2026-07-23T09:30:00Z', runCount: 0, messageCount: 0 },
];

describe('SessionSidebar', () => {
  it('提供新建对话、当前选中态和会话切换', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    const onSelect = vi.fn();
    render(<SessionSidebar sessions={sessions} activeSessionId="session-1" collapsed={false} onToggle={vi.fn()} onCreate={onCreate} onSelect={onSelect} />);

    expect(screen.getByRole('button', { name: /课程反馈行动建议/ })).toHaveClass('session-item-active');
    await user.click(screen.getByRole('button', { name: '新建对话' }));
    await user.click(screen.getByRole('button', { name: /下周课程设计讨论/ }));
    expect(onCreate).toHaveBeenCalledOnce();
    expect(onSelect).toHaveBeenCalledWith('session-2');
  });

  it('收起后仍保留可发现的导航展开入口', () => {
    render(<SessionSidebar sessions={sessions} activeSessionId="session-1" collapsed onToggle={vi.fn()} onCreate={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByRole('button', { name: '展开会话导航' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建对话' })).not.toBeInTheDocument();
  });

  it('按标题筛选已加载会话并展示空结果', async () => {
    const user = userEvent.setup();
    const onQuery = vi.fn();
    const { rerender } = render(<SessionSidebar sessions={sessions} activeSessionId="session-1" collapsed={false} searchQuery="课程设计" onSearchQueryChange={onQuery} onToggle={vi.fn()} onCreate={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByRole('button', { name: /下周课程设计讨论/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /课程反馈行动建议/ })).not.toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: '按标题搜索会话' }), 'x');
    expect(onQuery).toHaveBeenCalledWith('课程设计x');
    rerender(<SessionSidebar sessions={sessions} activeSessionId="session-1" collapsed={false} searchQuery="无匹配" onToggle={vi.fn()} onCreate={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByText('未找到匹配的会话')).toBeInTheDocument();
  });
});

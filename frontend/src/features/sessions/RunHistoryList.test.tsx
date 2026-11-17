import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { historyRuns } from '@/mocks/runtime-fixtures';
import { RunHistoryList } from './RunHistoryList';

describe('RunHistoryList', () => {
  it('显示历史 Run 并报告选择项', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<RunHistoryList runs={historyRuns.slice(0, 2)} onSelect={onSelect} onRefresh={vi.fn()} />);
    await user.click(screen.getByText('汇总本周课程反馈，列出行动建议。'));
    expect(onSelect).toHaveBeenCalledWith('run-history-success');
  });
});

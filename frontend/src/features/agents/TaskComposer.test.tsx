import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { demoAgents } from '@/mocks/runtime-fixtures';
import { TaskComposer } from './TaskComposer';

describe('TaskComposer', () => {
  it('阻止未选择 Agent 或空消息的发送', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<TaskComposer agents={demoAgents} submitting={false} onSelect={vi.fn()} onSubmit={onSubmit} />);
    await user.click(screen.getByRole('button', { name: '发送消息' }));
    expect(screen.getByRole('alert')).toHaveTextContent('请选择一个可用 Agent');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('用选择的 ReAct Agent 发送结构化 command 并清空输入', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<TaskComposer agents={demoAgents} selectedAgentId="research-react-agent" submitting={false} onSelect={onSelect} onSubmit={onSubmit} />);
    await user.type(screen.getByLabelText('消息'), '生成行动建议');
    await user.click(screen.getByRole('button', { name: '发送消息' }));
    expect(onSubmit).toHaveBeenCalledWith('生成行动建议', 10);
    expect(screen.getByLabelText('消息')).toHaveValue('');
  });

  it('提交中按 Enter 不会重复发送', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<TaskComposer agents={demoAgents} selectedAgentId="research-react-agent" submitting onSelect={vi.fn()} onSubmit={onSubmit} />);
    await user.type(screen.getByLabelText('消息'), '再次发送{Enter}');
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByLabelText('消息')).toHaveValue('再次发送');
  });

  it('使用自绘浮层切换 Agent，而不是依赖原生 select', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<TaskComposer agents={demoAgents} selectedAgentId="research-react-agent" submitting={false} onSelect={onSelect} onSubmit={vi.fn()} />);

    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /研究分析 Agent · ReAct/ }));
    const menu = screen.getByRole('listbox', { name: '选择可用 Agent' });
    expect(within(menu).getByRole('option', { name: /研究分析 Agent/ })).toHaveAttribute('aria-selected', 'true');

    await user.click(within(menu).getByRole('option', { name: /课程运营 Agent/ }));
    expect(onSelect).toHaveBeenCalledWith('course-ops-agent');
  });
});

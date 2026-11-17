import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RuntimeProvider } from './RuntimeProvider';
import { App } from './App';
import { DemoRuntimeDataSource } from '@/mocks/DemoRuntimeDataSource';

describe('App', () => {
  it('加载 AI 对话工作台的主要工作区域', async () => {
    render(<RuntimeProvider dataSource={new DemoRuntimeDataSource()}><App /></RuntimeProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: '新建对话' })).toBeInTheDocument());
    expect(screen.getByRole('complementary', { name: '会话导航' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('region', { name: '当前会话消息' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '打开图片生成工作台' }));
    expect(screen.getByRole('region', { name: '图片生成工作台' })).toBeInTheDocument();
  });

  it('手机会话抽屉关闭后焦点回到打开按钮', async () => {
    render(<RuntimeProvider dataSource={new DemoRuntimeDataSource()}><App /></RuntimeProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: '打开会话导航' })).toBeInTheDocument());
    const trigger = screen.getByRole('button', { name: '打开会话导航' });
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('dialog', { name: '会话导航' }).querySelector('.sheet-dismiss')!);
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});

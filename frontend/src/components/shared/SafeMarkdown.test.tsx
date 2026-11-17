import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { SafeMarkdown } from './SafeMarkdown';
import { complexMarkdown } from '@/test/fixtures/complexMarkdown';

describe('SafeMarkdown', () => {
  afterEach(() => vi.restoreAllMocks());

  it('移除任意 HTML 与危险协议', () => {
    const { container } = render(<SafeMarkdown content={'<script>alert(1)</script>\n\n<iframe src="https://bad.example"></iframe>\n\n[danger](javascript:alert(1))'} />);
    expect(container.querySelector('script, iframe')).toBeNull();
    expect(screen.getByText('danger').closest('a')).not.toHaveAttribute('href', expect.stringContaining('javascript:'));
  });

  it('为外链设置安全新窗口属性并保留标准 fragment', () => {
    render(<SafeMarkdown content={'[外链](https://example.com/report) [章节](#section)'} onFragmentNavigate={vi.fn()} />);
    expect(screen.getByRole('link', { name: '外链' })).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: '外链' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: '章节' })).not.toHaveAttribute('target');
  });

  it('阻止远程图片自动加载并提供受控地址入口', () => {
    const { container } = render(<SafeMarkdown content="![tracking pixel](https://tracker.example/pixel.png)" variant="document" />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByRole('note')).toHaveTextContent('远程图片未自动加载：tracking pixel');
    expect(screen.getByRole('link', { name: '查看图片地址' })).toHaveAttribute('href', 'https://tracker.example/pixel.png');
  });

  it('渲染 GFM 表格并复制代码块纯文本', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    render(<SafeMarkdown variant="document" content={'| A | B |\n|---|---|\n| 1 | 2 |\n\n```ts\nconst answer = 42;\n```'} />);
    expect(screen.getByRole('region', { name: '可横向滚动的表格' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '复制代码' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('const answer = 42;'));
    expect(screen.getByRole('button', { name: '代码已复制' })).toBeInTheDocument();
  });

  it('不把普通非链接引用编号猜测为来源', () => {
    render(<SafeMarkdown content="结论来自多个来源 [1]，但这里没有标准链接。" variant="document" />);
    expect(screen.getByText(/\[1\]/)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('稳定渲染复杂 document 内容且宽内容只进入局部滚动容器', () => {
    const { container } = render(<SafeMarkdown content={complexMarkdown} variant="document" />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('产品级 Artifact 阅读验收');
    expect(screen.getByText('更深一层的编号项')).toBeInTheDocument();
    expect(container.querySelector('blockquote')).toHaveTextContent('引用内容');
    expect(screen.getByRole('region', { name: '可横向滚动的表格' })).toContainElement(container.querySelector('table'));
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('远程图片未自动加载');
    expect(container.querySelector('img')).toBeNull();
  });
});

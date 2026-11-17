import { useRef } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MarkdownArtifactRenderer } from './MarkdownArtifactRenderer';
import { complexMarkdown } from '@/test/fixtures/complexMarkdown';

function ReaderFixture({ content }: { content: string }) {
  const rootRef = useRef<HTMLDivElement>(null);
  return <div ref={rootRef} data-testid="root"><MarkdownArtifactRenderer preview={{ kind: 'text', content }} scrollRootRef={rootRef} /></div>;
}

describe('MarkdownArtifactRenderer', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('为中文和重复标题生成稳定去重 id 与目录', () => {
    const { container } = render(<ReaderFixture content={'# 中文标题\n\n## 重复标题\n\n## 重复标题'} />);
    expect(container.querySelector('h1')).toHaveAttribute('id', '中文标题');
    expect(container.querySelectorAll('h2')[0]).toHaveAttribute('id', '重复标题');
    expect(container.querySelectorAll('h2')[1]).toHaveAttribute('id', '重复标题-1');
    expect(screen.getByRole('navigation', { name: '文档目录' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '重复标题' })).toHaveLength(2);
  });

  it('标题不足三个时不显示目录但 fragment 仍只滚 Reader', async () => {
    const scrollTo = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: scrollTo });
    render(<ReaderFixture content={'# 第一节\n\n[跳转](#第二节)\n\n## 第二节'} />);
    expect(screen.queryByRole('navigation', { name: '文档目录' })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('link', { name: '跳转' }));
    expect(scrollTo).toHaveBeenCalledTimes(1);
    expect(scrollTo).toHaveBeenCalledWith(expect.objectContaining({ top: expect.any(Number) }));
  });

  it('使用 Reader scroll root 的 IntersectionObserver 更新当前章节', () => {
    let observerCallback: IntersectionObserverCallback | undefined;
    const observe = vi.fn();
    const disconnect = vi.fn();
    class TestIntersectionObserver {
      readonly root = null;
      readonly rootMargin = '';
      readonly thresholds = [];
      constructor(callback: IntersectionObserverCallback) { observerCallback = callback; }
      observe = observe;
      unobserve = vi.fn();
      disconnect = disconnect;
      takeRecords = () => [];
    }
    vi.stubGlobal('IntersectionObserver', TestIntersectionObserver);
    const { container } = render(<ReaderFixture content={'# 第一节\n\n## 第二节\n\n### 第三节'} />);
    const second = container.querySelectorAll('h1, h2, h3')[1];
    act(() => observerCallback?.([{ isIntersecting: true, target: second, boundingClientRect: { top: 20 } } as IntersectionObserverEntry], {} as IntersectionObserver));
    expect(screen.getByRole('button', { name: '第二节' })).toHaveAttribute('aria-current', 'location');
    expect(observe).toHaveBeenCalledTimes(3);
    expect(disconnect).not.toHaveBeenCalled();
  });

  it('为复杂长文生成桌面目录且所有内容仍位于 Reader 根内', () => {
    const { container } = render(<ReaderFixture content={complexMarkdown} />);
    const root = screen.getByTestId('root');
    expect(screen.getByRole('navigation', { name: '文档目录' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /产品级 Artifact 阅读验收/ })).toBeInTheDocument();
    expect(root).toContainElement(container.querySelector('[aria-label="可横向滚动的表格"]'));
    expect(root).toContainElement(screen.getByRole('note'));
  });
});

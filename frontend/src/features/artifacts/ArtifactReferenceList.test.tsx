import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ArtifactReference } from '@/domain/runtime/types';
import { ArtifactContentError, type ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { ArtifactReferenceList } from './ArtifactReferenceList';

const markdownArtifact: ArtifactReference = {
  artifactId: 'artifact-1',
  runId: 'run-1',
  reference: 'artifact:safe-report',
  name: 'report.md',
  artifactType: 'report',
  mimeType: 'text/markdown',
  size: 128,
  summary: 'safe summary',
  visibility: 'VISIBLE',
  sourceStage: 'deep_search_tool',
  accessState: 'AVAILABLE',
  previewSupported: true,
  downloadSupported: true,
  preview: { kind: 'markdown', schemaVersion: 'v1' },
};

function dataSource(content = '# Preview title'): ArtifactContentDataSource {
  return {
    loadPreview: vi.fn().mockResolvedValue({ kind: 'text', content }),
    buildDownloadHref: vi.fn((runId, artifactId) => `/api/v1/artifacts/runs/${runId}/${artifactId}/download`),
  };
}

describe('ArtifactReferenceList', () => {
  afterEach(() => vi.restoreAllMocks());

  it('在独立 Reader 加载 Markdown，关闭后释放正文、恢复焦点且不滚动会话', async () => {
    const source = dataSource();
    const { container } = render(<div className="conversation-scroll"><ArtifactReferenceList artifacts={[markdownArtifact]} dataSource={source} /></div>);
    const conversation = container.querySelector<HTMLElement>('.conversation-scroll')!;
    conversation.scrollTop = 137;
    const previewButton = screen.getByRole('button', { name: '预览文件 report.md' });

    await userEvent.setup().click(previewButton);

    expect(await screen.findByRole('heading', { name: 'Preview title' })).toBeInTheDocument();
    expect(source.loadPreview).toHaveBeenCalledTimes(1);
    expect(source.loadPreview).toHaveBeenCalledWith('run-1', 'artifact-1', expect.any(AbortSignal));
    expect(screen.getByTestId('artifact-reader-scroll-root')).toBeInTheDocument();
    expect(conversation.scrollTop).toBe(137);
    expect(screen.getByRole('link', { name: '下载文件 report.md' })).toHaveAttribute('href', '/api/v1/artifacts/runs/run-1/artifact-1/download');

    await userEvent.setup().click(screen.getByRole('button', { name: '关闭文件预览' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Preview title' })).not.toBeInTheDocument());
    expect(previewButton).toHaveFocus();
    expect(conversation.scrollTop).toBe(137);

    await userEvent.setup().click(previewButton);
    await screen.findByRole('heading', { name: 'Preview title' });
    expect(source.loadPreview).toHaveBeenCalledTimes(2);
  });

  it('使用 Escape 关闭 Reader 并返回原触发器', async () => {
    render(<ArtifactReferenceList artifacts={[markdownArtifact]} dataSource={dataSource()} />);
    const user = userEvent.setup();
    const trigger = screen.getByRole('button', { name: '预览文件 report.md' });
    await user.click(trigger);
    await screen.findByRole('dialog');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it('descriptor 缺失、kind 未知或版本未知时不展示预览动作且保留下载', () => {
    const source = dataSource();
    render(<ArtifactReferenceList artifacts={[
      { ...markdownArtifact, artifactId: 'missing', name: 'missing.md', preview: undefined },
      { ...markdownArtifact, artifactId: 'json', name: 'data.json', preview: { kind: 'json', schemaVersion: 'v1' } },
      { ...markdownArtifact, artifactId: 'future', name: 'future.md', preview: { kind: 'markdown', schemaVersion: 'v2' } },
    ]} dataSource={source} />);

    expect(screen.queryByRole('button', { name: /预览文件/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /下载文件/ })).toHaveLength(3);
    expect(screen.getAllByText('当前客户端不支持在线预览。')).toHaveLength(3);
    expect(source.loadPreview).not.toHaveBeenCalled();
  });

  it('PPTX 保持下载，HTML Slides 明确使用在线 Reader', () => {
    render(<ArtifactReferenceList artifacts={[
      { ...markdownArtifact, artifactId: 'pptx', name: 'slides.pptx',
        mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        preview: undefined, previewSupported: false },
      { ...markdownArtifact, artifactId: 'html-slides', name: 'slides.html',
        mimeType: 'text/html+slides', preview: { kind: 'html-slides', schemaVersion: 'v1' } },
    ]} dataSource={dataSource()} />);
    expect(screen.getByText('PPTX 原件可下载；HTML 演示页需打开对应文件。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览文件 slides.pptx' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '下载文件 slides.pptx' })).toBeInTheDocument();
    expect(screen.getByText('HTML 演示页可在线预览。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预览文件 slides.html' })).toBeInTheDocument();
  });

  it('为受控错误显示固定文案，不展示底层响应内容', async () => {
    const source = dataSource();
    vi.mocked(source.loadPreview).mockRejectedValue(new ArtifactContentError('NOT_FOUND'));
    render(<ArtifactReferenceList artifacts={[markdownArtifact]} dataSource={source} />);
    await userEvent.setup().click(screen.getByRole('button', { name: '预览文件 report.md' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('这个文件不存在或已被删除。');
    expect(screen.queryByText(/storage|private|exception/i)).not.toBeInTheDocument();
  });

  it('不为不可访问历史 Artifact 渲染无效操作', () => {
    render(<ArtifactReferenceList artifacts={[{ ...markdownArtifact, accessState: 'NOT_FOUND', previewSupported: false, downloadSupported: false }]} dataSource={dataSource()} />);
    expect(screen.getByText('文件当前不可访问。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /预览文件/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /下载文件/ })).not.toBeInTheDocument();
  });

  it('不渲染输入对象中的内部 locator 字段', () => {
    render(<ArtifactReferenceList artifacts={[{ ...markdownArtifact, storageRef: '/private/file', storageKey: 'private-key', downloadUrl: 'https://persistent.example/private' } as ArtifactReference]} dataSource={dataSource()} />);
    expect(screen.queryByText(/private-key|private\/file|persistent\.example/)).not.toBeInTheDocument();
  });

  it('稳定展示文件身份、访问状态、来源元信息和固定图标操作', () => {
    const onSelectArtifact = vi.fn();
    render(<ArtifactReferenceList artifacts={[markdownArtifact]} dataSource={dataSource()} onSelectArtifact={onSelectArtifact} />);
    expect(screen.getByText('可访问')).toBeInTheDocument();
    expect(screen.getByText('text/markdown')).toBeInTheDocument();
    expect(screen.getByText('来源：deep_search_tool')).toBeInTheDocument();
    expect(screen.getByText('支持在线预览')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '查看文件详情 report.md' }));
    expect(onSelectArtifact).toHaveBeenCalledWith(markdownArtifact);
  });

  it('遮罩可关闭 Reader，焦点保持在 Dialog 内并在关闭后返回触发器', async () => {
    render(<ArtifactReferenceList artifacts={[markdownArtifact]} dataSource={dataSource('body')} />);
    const user = userEvent.setup();
    const trigger = screen.getByRole('button', { name: '预览文件 report.md' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await user.tab();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    expect(screen.getByText('body').closest('[role="dialog"]')).toBe(dialog);
    const overlay = dialog.previousElementSibling as HTMLElement;
    expect(overlay).toBeTruthy();
    await user.click(overlay);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it('仅在 image/v1 精确命中时通过受控 Blob 打开图片，不根据 MIME 或工具名称猜测', async () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:generated-image');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const source: ArtifactContentDataSource = {
      loadPreview: vi.fn().mockResolvedValue({ kind: 'binary', blob: new Blob(['image'], { type: 'image/png' }) }),
      buildDownloadHref: vi.fn(() => '/download'),
    };
    const imageArtifact: ArtifactReference = {
      ...markdownArtifact,
      artifactId: 'image-1',
      name: 'generated.png',
      artifactType: 'image',
      mimeType: 'image/png',
      preview: { kind: 'image', schemaVersion: 'v1' },
    };
    const first = render(<ArtifactReferenceList artifacts={[imageArtifact]} dataSource={source} />);
    await userEvent.setup().click(screen.getByRole('button', { name: '预览文件 generated.png' }));
    const image = await screen.findByRole('img', { name: 'Artifact 图片预览' });
    expect(image).toHaveAttribute('src', 'blob:generated-image');
    expect(source.loadPreview).toHaveBeenCalledWith('run-1', 'image-1', expect.any(AbortSignal));
    await userEvent.setup().click(screen.getByRole('button', { name: '关闭文件预览' }));
    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:generated-image'));
    first.unmount();

    const { unmount } = render(<ArtifactReferenceList artifacts={[{ ...imageArtifact, preview: { kind: 'image', schemaVersion: 'v2' } }]} dataSource={source} />);
    const previewButtons = screen.queryAllByRole('button', { name: '预览文件 generated.png' });
    expect(previewButtons).toHaveLength(0);
    unmount();
  });
});

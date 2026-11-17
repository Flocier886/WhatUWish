import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImageGenerationWorkbench } from './ImageGenerationWorkbench';
import type { ImageGenerationWorkbenchDataSource } from './ImageGenerationWorkbenchDataSource';

function source(overrides: Partial<ImageGenerationWorkbenchDataSource> = {}): ImageGenerationWorkbenchDataSource {
  return {
    generate: vi.fn().mockResolvedValue({ executionId: 'execution-1', status: 'completed', summary: '图片生成完成', artifacts: [{ artifactId: 'artifact-1', name: 'output.png', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'image', schemaVersion: 'v1' } }] }),
    history: vi.fn().mockResolvedValue({ sessionId: 'session-image', pageNo: 1, pageSize: 12, total: 0, hasMore: false, items: [] }),
    loadPreview: vi.fn().mockResolvedValue(new Blob(['image'], { type: 'image/png' })),
    download: vi.fn().mockResolvedValue(new Blob(['image'], { type: 'image/png' })),
    ...overrides,
  };
}

function stubObjectUrls() {
  const createObjectURL = vi.fn(() => 'blob:workbench-image');
  const revokeObjectURL = vi.fn();
  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
  return { createObjectURL, revokeObjectURL };
}

function installImageLoader() {
  class TestImage {
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    naturalWidth = 640;
    naturalHeight = 480;

    set src(_: string) {
      queueMicrotask(() => this.onload?.());
    }
  }

  vi.stubGlobal('Image', TestImage);
}

function stubCanvasWithoutMask() {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
}

describe('ImageGenerationWorkbench', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('通过直接工作台请求更新本地生成结果，而不调用 Runtime Replay', async () => {
    stubObjectUrls();
    const dataSource = source();
    render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);
    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '雨夜城市海报' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));
    await waitFor(() => expect(dataSource.generate).toHaveBeenCalledTimes(1));
    expect(dataSource.generate).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'session-image', prompt: '雨夜城市海报', mode: 'images' }));
    expect(await screen.findByText('图片生成完成')).toBeInTheDocument();
    expect(dataSource.loadPreview).toHaveBeenCalledWith('execution-1', 'artifact-1', expect.any(AbortSignal));
  });

  it('直接请求失败时只更新当前生成项，保留已恢复的历史批次', async () => {
    const dataSource = source({
      generate: vi.fn().mockRejectedValue(new Error('上游图片服务暂时不可用')),
      history: vi.fn().mockResolvedValue({
        sessionId: 'session-image', pageNo: 1, pageSize: 12, total: 1, hasMore: false,
        items: [{ executionId: 'history-1', status: 'completed', summary: '此前的作品', artifacts: [], startedAt: '2026-08-31T08:00:00Z' }],
      }),
    });
    render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);
    expect((await screen.findAllByText('此前的作品')).length).toBeGreaterThan(0);

    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '失败请求' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));

    expect(await screen.findByText('上游图片服务暂时不可用')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '图片描述' })).toHaveValue('失败请求');
    expect(screen.getAllByText('此前的作品').length).toBeGreaterThan(0);
    expect(dataSource.loadPreview).not.toHaveBeenCalled();
  });

  it('编辑模式保留参考图顺序，并仍通过独立工作台数据源提交 multipart 请求', async () => {
    stubObjectUrls();
    installImageLoader();
    stubCanvasWithoutMask();
    const dataSource = source({
      generate: vi.fn().mockResolvedValue({ executionId: 'edit-1', status: 'completed', summary: '编辑完成', artifacts: [] }),
    });
    const { container } = render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);

    fireEvent.click(screen.getByRole('button', { name: '图生图 / 编辑' }));
    const referenceOne = new File(['first'], 'first.png', { type: 'image/png' });
    const referenceTwo = new File(['second'], 'second.png', { type: 'image/png' });
    const input = container.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).not.toBeNull();
    fireEvent.change(input!, { target: { files: [referenceOne, referenceTwo] } });
    expect(await screen.findByRole('img', { name: '参考图 1' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '参考图 2' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: '多图批处理' }));

    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '把背景改成黄昏' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));

    await waitFor(() => expect(dataSource.generate).toHaveBeenCalledTimes(1));
    expect(dataSource.generate).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'edits',
      references: [referenceOne, referenceTwo],
      masks: [],
    }));
  });

  it('默认多图批处理逐图调用，部分失败时保留成功结果和提示词', async () => {
    stubObjectUrls();
    installImageLoader();
    stubCanvasWithoutMask();
    const generate = vi.fn().mockImplementation(async ({ references }: { references: File[] }) => {
      if (references[0].name === 'second.png') throw new Error('第二张图片生成失败');
      return { executionId: 'edit-first', status: 'completed', summary: '第一张图片已生成', artifacts: [] };
    });
    const { container } = render(<ImageGenerationWorkbench sessionId="session-image" dataSource={source({ generate })} />);
    const first = new File(['first'], 'first.png', { type: 'image/png' });
    const second = new File(['second'], 'second.png', { type: 'image/png' });
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [first, second] } });
    expect(await screen.findByRole('checkbox', { name: '多图批处理' })).toBeChecked();
    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '修改背景' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));

    await waitFor(() => expect(generate).toHaveBeenCalledTimes(2));
    expect(generate).toHaveBeenCalledWith(expect.objectContaining({ references: [first], masks: [], outputFileName: 'first' }));
    expect(generate).toHaveBeenCalledWith(expect.objectContaining({ references: [second], masks: [], outputFileName: 'second' }));
    expect(await screen.findByText('第一张图片已生成')).toBeInTheDocument();
    expect(await screen.findByText('第二张图片生成失败')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '图片描述' })).toHaveValue('修改背景');
  });

  it('结果卡通过 executionId 获取 Blob，下载和卸载后释放临时 Object URL', async () => {
    const { createObjectURL, revokeObjectURL } = stubObjectUrls();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const dataSource = source();
    const view = render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);
    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '日落海报' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));

    await screen.findByRole('img', { name: 'output.png' });
    fireEvent.click(screen.getByRole('button', { name: '下载 output.png' }));
    await waitFor(() => expect(dataSource.download).toHaveBeenCalledWith('execution-1', 'artifact-1'));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:workbench-image'));

    view.unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:workbench-image');
  });

  it('上游返回图片少于请求数量时显示真实数量', async () => {
    const dataSource = source({ generate: vi.fn().mockResolvedValue({
      executionId: 'short-output', status: 'completed', summary: '图片编辑完成', requestedCount: 2, generatedCount: 1, artifacts: [],
    }) });
    render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);
    fireEvent.click(screen.getByRole('button', { name: '生成设置' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: '输出数量' }), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: '关闭生成设置' }));
    fireEvent.change(screen.getByRole('textbox', { name: '图片描述' }), { target: { value: '海报' } });
    fireEvent.click(screen.getByRole('button', { name: '生成' }));
    expect(await screen.findByRole('status')).toHaveTextContent('请求 2 张，实际生成 1 张');
  });

  it('历史分页只追加工作台 execution 批次', async () => {
    const history = vi.fn()
      .mockResolvedValueOnce({ sessionId: 'session-image', pageNo: 1, pageSize: 12, total: 2, hasMore: true, items: [{ executionId: 'history-1', status: 'completed', summary: '第一张图', artifacts: [], startedAt: '2026-08-31T08:00:00Z' }] })
      .mockResolvedValueOnce({ sessionId: 'session-image', pageNo: 2, pageSize: 12, total: 2, hasMore: false, items: [{ executionId: 'history-2', status: 'completed', summary: '第二张图', artifacts: [], startedAt: '2026-08-30T08:00:00Z' }] });
    const dataSource = source({ history });
    render(<ImageGenerationWorkbench sessionId="session-image" dataSource={dataSource} />);
    expect((await screen.findAllByText('第一张图')).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }));
    expect(await screen.findByText('第二张图')).toBeInTheDocument();
    expect(history).toHaveBeenNthCalledWith(2, 'session-image', 2, 12);
  });
});

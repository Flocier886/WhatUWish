import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ArtifactReference } from '@/domain/runtime/types';
import { ArtifactContentError, type ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { useArtifactPreview } from './useArtifactPreview';

const artifact = (artifactId: string): ArtifactReference => ({
  artifactId,
  runId: 'run-1',
  reference: `artifact:${artifactId}`,
  name: `${artifactId}.md`,
  artifactType: 'report',
  visibility: 'VISIBLE',
  accessState: 'AVAILABLE',
  previewSupported: true,
  preview: { kind: 'markdown', schemaVersion: 'v1' },
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => { resolve = resolvePromise; reject = rejectPromise; });
  return { promise, resolve, reject };
}

describe('useArtifactPreview', () => {
  it('覆盖 loading、ready、关闭释放与重新请求', async () => {
    const source: ArtifactContentDataSource = { loadPreview: vi.fn().mockResolvedValue({ kind: 'text', content: '# content' }), buildDownloadHref: vi.fn() };
    const { result, rerender } = renderHook(({ open }) => useArtifactPreview({ artifact: artifact('a'), open, rendererSupported: true, dataSource: source }), { initialProps: { open: true } });

    expect(result.current.status).toBe('LOADING');
    await waitFor(() => expect(result.current).toMatchObject({ status: 'READY', value: { kind: 'text', content: '# content' } }));
    rerender({ open: false });
    expect(result.current.status).toBe('IDLE');
    expect(result.current).not.toHaveProperty('content');
    rerender({ open: true });
    await waitFor(() => expect(result.current.status).toBe('READY'));
    expect(source.loadPreview).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['FORBIDDEN'], ['NOT_FOUND'], ['UNAVAILABLE'], ['NETWORK_ERROR'],
  ] as const)('保留受控错误 %s 且支持重试', async (code) => {
    const source: ArtifactContentDataSource = {
      loadPreview: vi.fn().mockRejectedValueOnce(new ArtifactContentError(code)).mockResolvedValueOnce({ kind: 'text', content: 'recovered' }),
      buildDownloadHref: vi.fn(),
    };
    const { result } = renderHook(() => useArtifactPreview({ artifact: artifact('a'), open: true, rendererSupported: true, dataSource: source }));
    await waitFor(() => expect(result.current.status).toBe(code));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current).toMatchObject({ status: 'READY', value: { kind: 'text', content: 'recovered' } }));
  });

  it('descriptor 缺失或 Registry 未命中时不请求正文', () => {
    const source: ArtifactContentDataSource = { loadPreview: vi.fn(), buildDownloadHref: vi.fn() };
    const { result } = renderHook(() => useArtifactPreview({ artifact: { ...artifact('a'), preview: undefined }, open: true, rendererSupported: false, dataSource: source }));
    expect(result.current.status).toBe('UNAVAILABLE');
    expect(source.loadPreview).not.toHaveBeenCalled();
  });

  it('切换文件会取消旧请求且迟到响应不能覆盖新内容', async () => {
    const first = deferred<{ kind: 'text'; content: string }>();
    const second = deferred<{ kind: 'text'; content: string }>();
    const signals: AbortSignal[] = [];
    const source: ArtifactContentDataSource = {
      loadPreview: vi.fn((_runId, artifactId, signal) => {
        signals.push(signal);
        return artifactId === 'a' ? first.promise : second.promise;
      }),
      buildDownloadHref: vi.fn(),
    };
    const { result, rerender, unmount } = renderHook(({ current }) => useArtifactPreview({ artifact: artifact(current), open: true, rendererSupported: true, dataSource: source }), { initialProps: { current: 'a' } });
    rerender({ current: 'b' });
    expect(signals[0].aborted).toBe(true);
    await act(async () => { first.resolve({ kind: 'text', content: 'stale' }); await Promise.resolve(); });
    expect(result.current.status).toBe('LOADING');
    await act(async () => { second.resolve({ kind: 'text', content: 'fresh' }); });
    expect(result.current).toMatchObject({ status: 'READY', value: { kind: 'text', content: 'fresh' } });
    unmount();
    expect(signals[1].aborted).toBe(true);
  });

  it('为二进制图片创建 Object URL，并在关闭、切换和卸载时释放', async () => {
    const createObjectURL = vi.fn().mockReturnValueOnce('blob:image-a').mockReturnValueOnce('blob:image-b');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const source: ArtifactContentDataSource = {
      loadPreview: vi.fn().mockResolvedValue({ kind: 'binary', blob: new Blob(['image'], { type: 'image/png' }) }),
      buildDownloadHref: vi.fn(),
    };
    const { result, rerender, unmount } = renderHook(
      ({ current, open }) => useArtifactPreview({ artifact: artifact(current), open, rendererSupported: true, dataSource: source }),
      { initialProps: { current: 'a', open: true } },
    );
    await waitFor(() => expect(result.current).toMatchObject({ status: 'READY', value: { kind: 'binary', objectUrl: 'blob:image-a' } }));
    rerender({ current: 'b', open: true });
    await waitFor(() => expect(result.current).toMatchObject({ status: 'READY', value: { kind: 'binary', objectUrl: 'blob:image-b' } }));
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:image-a');
    rerender({ current: 'b', open: false });
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:image-b');
    unmount();
  });
});

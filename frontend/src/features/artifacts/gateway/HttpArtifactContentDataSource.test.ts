import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArtifactContentError } from './ArtifactContentDataSource';
import { HttpArtifactContentDataSource } from './HttpArtifactContentDataSource';

describe('HttpArtifactContentDataSource', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('编码公共文件身份并读取文本响应', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('# report', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const source = new HttpArtifactContentDataSource();
    const signal = new AbortController().signal;

    await expect(source.loadPreview('run / 1', 'artifact?#1', signal)).resolves.toEqual({ kind: 'text', content: '# report' });
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:8091/api/v1/artifacts/runs/run%20%2F%201/artifact%3F%231/preview',
      { headers: { Accept: 'text/markdown, text/plain;q=0.9, text/csv, image/png, image/jpeg, image/webp, image/gif, image/bmp, application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }, signal },
    );
    expect(source.buildDownloadHref('run / 1', 'artifact?#1')).toBe(
      'http://127.0.0.1:8091/api/v1/artifacts/runs/run%20%2F%201/artifact%3F%231/download',
    );
  });

  it.each([
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [409, 'UNAVAILABLE'],
    [500, 'NETWORK_ERROR'],
  ] as const)('将 HTTP %s 映射为受控错误且不读取错误正文', async (status, code) => {
    const text = vi.fn().mockResolvedValue('private path C:\\storage\\secret');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, text }));

    const failure = await new HttpArtifactContentDataSource().loadPreview('run-1', 'artifact-1', new AbortController().signal).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ArtifactContentError);
    expect((failure as ArtifactContentError).code).toBe(code);
    expect((failure as Error).message).toBe(code);
    expect(text).not.toHaveBeenCalled();
  });

  it('将 fetch 失败收敛为网络错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('socket leaked internal host')));
    await expect(new HttpArtifactContentDataSource().loadPreview('run-1', 'artifact-1', new AbortController().signal))
      .rejects.toMatchObject({ code: 'NETWORK_ERROR', message: 'NETWORK_ERROR' });
  });

  it('透传 AbortSignal 并保留取消语义', async () => {
    const controller = new AbortController();
    const abortError = new DOMException('aborted', 'AbortError');
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      expect(init.signal).toBe(controller.signal);
      return Promise.reject(abortError);
    });
    vi.stubGlobal('fetch', fetchMock);
    controller.abort();

    await expect(new HttpArtifactContentDataSource().loadPreview('run-1', 'artifact-1', controller.signal)).rejects.toBe(abortError);
  });

  it('仅依据受控图片 MIME 将 preview 读取为 Blob，不持久化上游 URL', async () => {
    const blob = new Blob(['png'], { type: 'image/png' });
    const fetchMock = vi.fn().mockResolvedValue(new Response(blob, {
      status: 200,
      headers: { 'content-type': 'image/png' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await new HttpArtifactContentDataSource().loadPreview('run-1', 'image-1', new AbortController().signal);

    expect(result.kind).toBe('binary');
    if (result.kind === 'binary') expect(result.blob.type).toBe('image/png');
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/preview'), expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it('将已授权 PDF 预览读取为二进制内容', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Blob(['%PDF-1.7']), {
      status: 200, headers: { 'content-type': 'application/pdf' },
    })));
    const result = await new HttpArtifactContentDataSource().loadPreview('run-1', 'pdf-1', new AbortController().signal);
    expect(result.kind).toBe('binary');
    if (result.kind === 'binary') expect(result.blob.type).toBe('application/pdf');
  });

  it('将 DOCX/XLSX 作为二进制预览，CSV 保持文本', async () => {
    const source = new HttpArtifactContentDataSource();
    for (const mime of ['application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet']) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Blob(['PK'], { type: mime }), {
        status: 200, headers: { 'content-type': mime },
      })));
      await expect(source.loadPreview('run', 'file', new AbortController().signal))
        .resolves.toMatchObject({ kind: 'binary' });
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('名称,金额\n华东,42', {
      status: 200, headers: { 'content-type': 'text/plain; charset=UTF-8' },
    })));
    await expect(source.loadPreview('run', 'csv', new AbortController().signal))
      .resolves.toEqual({ kind: 'text', content: '名称,金额\n华东,42' });
  });
});

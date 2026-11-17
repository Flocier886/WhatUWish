import { afterEach, describe, expect, it, vi } from 'vitest';
import { HttpImageGenerationWorkbenchDataSource, WorkbenchApiError } from './ImageGenerationWorkbenchDataSource';

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: '0000', data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('HttpImageGenerationWorkbenchDataSource', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('只向独立 generations 路由提交 multipart，并保留图片文件顺序', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      executionId: 'execution-1', status: 'completed', artifacts: [],
    }));
    vi.stubGlobal('fetch', fetchMock);
    const first = new File(['one'], 'first.png', { type: 'image/png' });
    const second = new File(['two'], 'second.png', { type: 'image/png' });
    const source = new HttpImageGenerationWorkbenchDataSource();

    await source.generate({
      sessionId: 'session-1', prompt: '生成一张图', mode: 'edits', size: '1024x1024', count: 1,
      outputFileName: 'first', references: [first, second], masks: [],
    });

    const [target, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(target).toContain('/api/v1/image-generation-workbench/generations');
    expect(target).not.toContain('/run');
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ Accept: 'application/json' });
    const form = init.body as FormData;
    expect(form.get('sessionId')).toBe('session-1');
    expect(form.get('outputFileName')).toBe('first');
    expect(form.getAll('references')).toEqual([first, second]);
  });

  it('history 与图片读取均以 executionId 受控路由访问，不构造 SSE 或 Runtime 路径', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ sessionId: 'session-1', pageNo: 2, pageSize: 12, total: 13, hasMore: false, items: [] }))
      .mockResolvedValueOnce(new Response(new Blob(['image'], { type: 'image/png' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(new Blob(['image'], { type: 'image/png' }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const source = new HttpImageGenerationWorkbenchDataSource();

    await source.history('session-1', 2, 12);
    await source.loadPreview('execution-1', 'artifact-1', new AbortController().signal);
    await source.download('execution-1', 'artifact-1');

    const targets = fetchMock.mock.calls.map(([target]) => String(target));
    expect(targets[0]).toContain('/history?sessionId=session-1&pageNo=2&pageSize=12');
    expect(targets[1]).toContain('/executions/execution-1/artifacts/artifact-1/preview');
    expect(targets[2]).toContain('/executions/execution-1/artifacts/artifact-1/download');
    expect(targets.join(' ')).not.toMatch(/\/run|replay|text\/event-stream/i);
  });

  it('拒绝服务端误传的存储定位字段，避免进入工作台持久状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      executionId: 'execution-1', status: 'completed', storageKey: 'v1/artifacts/private', artifacts: [],
    })));
    const source = new HttpImageGenerationWorkbenchDataSource();

    await expect(source.generate({
      sessionId: 'session-1', prompt: '生成一张图', mode: 'images', size: '1024x1024', count: 1, references: [], masks: [],
    })).rejects.toBeInstanceOf(WorkbenchApiError);
  });
});

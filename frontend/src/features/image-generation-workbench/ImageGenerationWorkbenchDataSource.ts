import { apiUrl } from '@/gateways/api-base';

export type WorkbenchImageArtifact = {
  artifactId: string;
  name?: string;
  mimeType?: string;
  size?: number;
  accessState?: string;
  preview?: { kind?: string; schemaVersion?: string };
  previewSupported?: boolean;
  downloadSupported?: boolean;
};

export type WorkbenchGenerationResult = {
  executionId: string;
  status: 'completed' | 'failed' | string;
  summary?: string;
  mode?: string;
  size?: string;
  requestedCount?: number;
  generatedCount?: number;
  referenceCount?: number;
  maskCount?: number;
  usedFallback?: boolean;
  errorCode?: string;
  errorMessage?: string;
  artifacts: WorkbenchImageArtifact[];
};

export type WorkbenchHistoryItem = WorkbenchGenerationResult & {
  startedAt?: string;
  finishedAt?: string;
};

export type WorkbenchHistoryPage = {
  sessionId: string;
  pageNo: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
  items: WorkbenchHistoryItem[];
};

export type WorkbenchGenerationCommand = {
  sessionId: string;
  prompt: string;
  mode: 'images' | 'edits';
  size: string;
  count: number;
  outputFileName?: string;
  references: File[];
  masks: File[];
};

export class WorkbenchApiError extends Error {
  readonly code?: string;

  constructor(message: string, code?: string) {
    super(message);
    this.name = 'WorkbenchApiError';
    this.code = code;
  }
}

export interface ImageGenerationWorkbenchDataSource {
  generate(command: WorkbenchGenerationCommand): Promise<WorkbenchGenerationResult>;
  history(sessionId: string, pageNo: number, pageSize: number): Promise<WorkbenchHistoryPage>;
  loadPreview(executionId: string, artifactId: string, signal: AbortSignal): Promise<Blob>;
  download(executionId: string, artifactId: string): Promise<Blob>;
}

type ApiResponse<T> = { code?: string | number; info?: string; data?: T };
const forbiddenKeys = new Set(['storagekey', 'storageref', 'objectkey', 'previewurl', 'downloadurl', 'ossurl', 'base64', 'rawresponse', 'endpoint', 'apikey', 'api_key']);

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function assertSafePayload(value: unknown): void {
  if (!value || typeof value !== 'object') return;
  if (Array.isArray(value)) {
    value.forEach(assertSafePayload);
    return;
  }
  Object.entries(value as Record<string, unknown>).forEach(([key, child]) => {
    if (forbiddenKeys.has(key.toLowerCase())) throw new WorkbenchApiError('工作台接口返回了不允许的内部字段。');
    assertSafePayload(child);
  });
}

async function readApi<T>(response: Response): Promise<T> {
  let body: ApiResponse<T> | undefined;
  try {
    body = await response.json() as ApiResponse<T>;
  } catch {
    throw new WorkbenchApiError(response.ok ? '工作台响应格式无效。' : '图片工作台请求失败。');
  }
  if (!response.ok || String(body?.code ?? '') !== '0000' || body?.data === undefined) {
    throw new WorkbenchApiError(text(body?.info) || '图片工作台请求失败。', String(body?.code ?? ''));
  }
  assertSafePayload(body.data);
  return body.data;
}

function artifactPath(executionId: string, artifactId: string): string {
  return `/api/v1/image-generation-workbench/executions/${encodeURIComponent(executionId)}/artifacts/${encodeURIComponent(artifactId)}`;
}

/** 图片工作台的独立 HTTP 数据源，不使用 Runtime Run/SSE/Replay。 */
export class HttpImageGenerationWorkbenchDataSource implements ImageGenerationWorkbenchDataSource {
  async generate(command: WorkbenchGenerationCommand): Promise<WorkbenchGenerationResult> {
    const form = new FormData();
    form.append('sessionId', command.sessionId);
    form.append('prompt', command.prompt);
    form.append('mode', command.mode);
    form.append('size', command.size);
    form.append('n', String(command.count));
    if (command.outputFileName) form.append('outputFileName', command.outputFileName);
    command.references.forEach((file) => form.append('references', file));
    command.masks.forEach((file) => form.append('masks', file));
    let response: Response;
    try {
      response = await fetch(apiUrl('/api/v1/image-generation-workbench/generations'), {
        method: 'POST', body: form, headers: { Accept: 'application/json' },
      });
    } catch {
      throw new WorkbenchApiError('无法连接图片工作台服务。');
    }
    return readApi<WorkbenchGenerationResult>(response);
  }

  async history(sessionId: string, pageNo: number, pageSize: number): Promise<WorkbenchHistoryPage> {
    let response: Response;
    try {
      const query = new URLSearchParams({ sessionId, pageNo: String(pageNo), pageSize: String(pageSize) });
      response = await fetch(apiUrl(`/api/v1/image-generation-workbench/history?${query.toString()}`), {
        headers: { Accept: 'application/json' },
      });
    } catch {
      throw new WorkbenchApiError('无法加载图片工作台历史。');
    }
    return readApi<WorkbenchHistoryPage>(response);
  }

  async loadPreview(executionId: string, artifactId: string, signal: AbortSignal): Promise<Blob> {
    let response: Response;
    try {
      response = await fetch(apiUrl(`${artifactPath(executionId, artifactId)}/preview`), { signal });
    } catch (error) {
      if (signal.aborted) throw error;
      throw new WorkbenchApiError('图片预览当前不可用。');
    }
    if (!response.ok) throw new WorkbenchApiError('图片预览当前不可用。');
    return response.blob();
  }

  async download(executionId: string, artifactId: string): Promise<Blob> {
    let response: Response;
    try {
      response = await fetch(apiUrl(`${artifactPath(executionId, artifactId)}/download`));
    } catch {
      throw new WorkbenchApiError('图片下载当前不可用。');
    }
    if (!response.ok) throw new WorkbenchApiError('图片下载当前不可用。');
    return response.blob();
  }
}

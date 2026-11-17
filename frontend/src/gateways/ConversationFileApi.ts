import { apiUrl } from './api-base';

/** 后端公开的安全文件视图；预览类型取决于服务端 preview 合同，而不是扩展名。 */
export interface ConversationFile {
  fileId: string;
  sessionId: string;
  name: string;
  mimeType: string;
  size: number;
  status: 'READY' | 'UNAVAILABLE' | 'REMOVED';
  createdAt: string;
  preview?: { kind: string; schemaVersion: string } | null;
  downloadSupported: boolean;
}

type ApiResult<T> = { code?: string; info?: string; data?: T };

function path(sessionId: string, suffix = ''): string {
  return `/api/v1/conversation-files/sessions/${encodeURIComponent(sessionId)}${suffix}`;
}

async function read<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => undefined) as ApiResult<T> | undefined;
  if (!response.ok || body?.code !== '0000' || body.data === undefined) {
    throw new Error(body?.info || `文件请求失败（HTTP ${response.status}）。`);
  }
  return body.data;
}

/** 本地单用户文件请求由服务端按 sessionId/fileId 核对归属；内部 locator 不进入前端协议。 */
export const conversationFileApi = {
  async metadata(sessionId: string, fileId: string): Promise<ConversationFile> {
    const response = await fetch(apiUrl(path(sessionId, `/files/${encodeURIComponent(fileId)}`)), {
      headers: { Accept: 'application/json' },
    });
    return read<ConversationFile>(response);
  },

  async list(sessionId: string): Promise<ConversationFile[]> {
    const response = await fetch(apiUrl(path(sessionId, '/files')), {
      headers: { Accept: 'application/json' },
    });
    return read<ConversationFile[]>(response);
  },

  /** XHR 用于上传进度；只有后端返回文件视图后，队列才可标记为成功。 */
  upload(sessionId: string, file: File, onProgress: (percent: number) => void): Promise<ConversationFile> {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      request.open('POST', apiUrl(path(sessionId, '/files')));
      request.setRequestHeader('Accept', 'application/json');
      request.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) onProgress(Math.min(99, Math.round(event.loaded / event.total * 100)));
      };
      request.onerror = () => reject(new Error('上传连接失败，请重试。'));
      request.onabort = () => reject(new Error('上传已中断。'));
      request.onload = () => {
        try {
          const body = JSON.parse(request.responseText) as ApiResult<ConversationFile>;
          if (request.status < 200 || request.status >= 300 || body.code !== '0000' || !body.data?.fileId) {
            reject(new Error(body.info || `上传失败（HTTP ${request.status}）。`));
            return;
          }
          onProgress(100);
          resolve(body.data);
        } catch { reject(new Error('上传响应无法读取。')); }
      };
      const form = new FormData();
      form.append('file', file);
      request.send(form);
    });
  },

  async remove(sessionId: string, fileId: string): Promise<void> {
    const response = await fetch(apiUrl(path(sessionId, `/files/${encodeURIComponent(fileId)}`)), {
      method: 'DELETE',
    });
    if (!response.ok) throw new Error(`移除文件失败（HTTP ${response.status}）。`);
  },

  /** 预览和下载都经受控 API；调用方按服务端声明的 preview.kind 选择 Renderer。 */
  async content(sessionId: string, fileId: string, kind: 'preview' | 'download'): Promise<Blob> {
    const response = await fetch(apiUrl(path(sessionId, `/files/${encodeURIComponent(fileId)}/${kind}`)), {
      cache: 'no-store',
    });
    if (!response.ok) throw new Error(`读取文件失败（HTTP ${response.status}）。`);
    return response.blob();
  },
};

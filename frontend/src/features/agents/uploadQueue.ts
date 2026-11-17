import type { ConversationFile } from '@/gateways/ConversationFileApi';

/** 上传状态只描述浏览器队列，不代表服务端会话文件的 READY/REMOVED 状态。 */
export type UploadStatus = 'pending' | 'uploading' | 'success' | 'error';
export type UploadAttachmentState = {
  id: string;
  file: File;
  status: UploadStatus;
  progress: number;
  error?: string;
  uploadedFile?: ConversationFile;
};

export function markUploadUploading(queue: Record<string, UploadAttachmentState>, id: string) {
  const current = queue[id];
  return current ? { ...queue, [id]: { ...current, status: 'uploading' as const, progress: 0, error: undefined } } : queue;
}

export function markUploadProgress(queue: Record<string, UploadAttachmentState>, id: string, progress: number) {
  const current = queue[id];
  return current?.status === 'uploading' ? { ...queue, [id]: { ...current, progress } } : queue;
}

/** 成功后保留后端文件元数据；发送任务时只提取已就绪文件的 fileId。 */
export function markUploadSuccess(queue: Record<string, UploadAttachmentState>, id: string, uploadedFile: ConversationFile) {
  const current = queue[id];
  return current ? { ...queue, [id]: { ...current, status: 'success' as const, progress: 100, error: undefined, uploadedFile } } : queue;
}

export function markUploadError(queue: Record<string, UploadAttachmentState>, id: string, error: string) {
  const current = queue[id];
  return current ? { ...queue, [id]: { ...current, status: 'error' as const, error } } : queue;
}

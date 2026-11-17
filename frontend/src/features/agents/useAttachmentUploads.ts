import { useCallback, useEffect, useRef, useState } from 'react';
import { conversationFileApi } from '@/gateways/ConversationFileApi';
import { markUploadError, markUploadProgress, markUploadSuccess, markUploadUploading, type UploadAttachmentState } from './uploadQueue';

/** 管理当前会话的本地上传队列；队列项是临时 UI 状态，成功后才取得服务端 fileId。 */
export function useAttachmentUploads(sessionId: string) {
  const [queue, setQueue] = useState<Record<string, UploadAttachmentState>>({});
  const [order, setOrder] = useState<string[]>([]);
  const current = useRef(queue);
  // 会话切换会递增 epoch；旧上传即便稍后完成，也不能写入新会话的队列。
  const epoch = useRef(0);
  useEffect(() => { current.current = queue; }, [queue]);
  useEffect(() => {
    epoch.current++;
    setQueue({});
    setOrder([]);
  }, [sessionId]);

  const upload = useCallback(async (id: string, file: File) => {
    const startedAt = epoch.current;
    setQueue((prev) => markUploadUploading(prev, id));
    try {
      const uploaded = await conversationFileApi.upload(sessionId, file, (percent) => {
        if (startedAt === epoch.current) setQueue((prev) => markUploadProgress(prev, id, percent));
      });
      if (startedAt === epoch.current) setQueue((prev) => markUploadSuccess(prev, id, uploaded));
    } catch (error) {
      if (startedAt === epoch.current) setQueue((prev) => markUploadError(prev, id,
        error instanceof Error ? error.message : '上传失败，请重试。'));
    }
  }, [sessionId]);

  const add = useCallback((files: File[]) => {
    const entries = files.map((file) => ({ id: crypto.randomUUID(), file }));
    setQueue((prev) => Object.fromEntries([...Object.entries(prev),
      ...entries.map(({ id, file }) => [id, { id, file, status: 'pending' as const, progress: 0 }]) ]));
    setOrder((prev) => [...prev, ...entries.map((entry) => entry.id)]);
    entries.forEach(({ id, file }) => { void upload(id, file); });
  }, [upload]);

  /** 重试沿用原 File 对象；成功项只从服务端返回值读取 fileId。 */
  const retry = useCallback((id: string) => {
    const entry = current.current[id];
    if (entry?.status === 'error') void upload(id, entry.file);
  }, [upload]);

  const remove = useCallback((id: string) => {
    setQueue((prev) => { const next = { ...prev }; delete next[id]; return next; });
    setOrder((prev) => prev.filter((item) => item !== id));
  }, []);

  const clearSucceeded = useCallback(() => {
    setQueue((prev) => Object.fromEntries(Object.entries(prev).filter(([, entry]) => entry.status !== 'success')));
    setOrder((prev) => prev.filter((id) => current.current[id]?.status !== 'success'));
  }, []);

  return { queue, order, add, retry, remove, clearSucceeded };
}

import * as Dialog from '@radix-ui/react-dialog';
import { Download, LoaderCircle, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { ConversationFile } from '@/gateways/ConversationFileApi';
import { conversationFileApi } from '@/gateways/ConversationFileApi';
import { artifactRendererRegistry } from '@/features/artifacts/renderers/artifactRenderers';
import type { ArtifactPreviewValue } from '@/features/artifacts/useArtifactPreview';
import styles from '@/features/artifacts/ArtifactPreviewDialog.module.css';

/** 输入文件沿用版本化 Artifact Renderer，但正文只从文件会话 API 获取。 */
export function ConversationFilePreviewDialog({ file, onClose }: { file?: ConversationFile; onClose(): void }) {
  const scrollRootRef = useRef<HTMLDivElement>(null);
  const [value, setValue] = useState<ArtifactPreviewValue>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const Renderer = artifactRendererRegistry.resolve(file?.preview ?? undefined);
  const fileId = file?.fileId;
  const sessionId = file?.sessionId;
  const previewKind = file?.preview?.kind;

  // 对话框关闭或切换文件时撤销 Blob URL，避免把旧图片留在浏览器内存。
  useEffect(() => {
    if (!fileId || !sessionId || !Renderer) return;
    let active = true;
    let objectUrl: string | undefined;
    setValue(undefined);
    setError(undefined);
    setLoading(true);
    void conversationFileApi.content(sessionId, fileId, 'preview').then(async (blob) => {
      if (!active) return;
      if (previewKind === 'image') {
        objectUrl = URL.createObjectURL(blob);
        setValue({ kind: 'binary', objectUrl });
      } else {
        setValue({ kind: 'text', content: await blob.text() });
      }
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '文件内容暂时不可用。');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [fileId, sessionId, previewKind, Renderer]);

  const download = async () => {
    if (!file) return;
    try {
      const blob = await conversationFileApi.content(file.sessionId, file.fileId, 'download');
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '下载失败。');
    }
  };

  return <Dialog.Root open={Boolean(file)} onOpenChange={(open) => { if (!open) onClose(); }}>
    <Dialog.Portal>
      <Dialog.Overlay className={styles.overlay} />
      <Dialog.Content className={styles.dialog} aria-describedby={undefined}>
        <header className={styles.header}>
          <div className={styles.identity}><Dialog.Title>{file?.name ?? '文件'}</Dialog.Title>
            <p>{file?.mimeType} · {file?.size.toLocaleString()} B</p></div>
          <div className={styles.actions}>
            <button type="button" className={styles.iconButton} onClick={() => { void download(); }} aria-label="下载文件" title="下载文件"><Download size={18} /></button>
            <Dialog.Close className={styles.iconButton} aria-label="关闭文件预览" title="关闭文件预览"><X size={19} /></Dialog.Close>
          </div>
        </header>
        <div ref={scrollRootRef} className={styles.scrollRoot}>
          {loading && <div className={styles.state} role="status"><LoaderCircle size={20} />正在加载文件内容</div>}
          {error && <div className={styles.state} role="alert">{error}</div>}
          {!loading && !error && value && Renderer && <Renderer preview={value} scrollRootRef={scrollRootRef} />}
          {!loading && !error && !Renderer && <div className={styles.state}>此文件可下载。</div>}
        </div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}

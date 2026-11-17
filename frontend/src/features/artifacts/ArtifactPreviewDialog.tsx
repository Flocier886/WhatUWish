import * as Dialog from '@radix-ui/react-dialog';
import { Download, X } from 'lucide-react';
import type { MutableRefObject } from 'react';
import type { ArtifactReference } from '@/domain/runtime/types';
import type { ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { ArtifactPreviewContent } from './ArtifactPreviewContent';
import styles from './ArtifactPreviewDialog.module.css';

interface ArtifactPreviewDialogProps {
  artifact?: ArtifactReference;
  dataSource: ArtifactContentDataSource;
  open: boolean;
  returnFocusRef: MutableRefObject<HTMLButtonElement | undefined>;
  onOpenChange: (open: boolean) => void;
}

function formatSize(size?: number): string {
  return typeof size === 'number' ? `${size.toLocaleString()} B` : '大小未知';
}

export function ArtifactPreviewDialog({ artifact, dataSource, open, returnFocusRef, onOpenChange }: ArtifactPreviewDialogProps) {
  const downloadHref = artifact?.runId && artifact.downloadSupported !== false
    ? dataSource.buildDownloadHref(artifact.runId, artifact.artifactId)
    : undefined;

  return <Dialog.Root open={open} onOpenChange={onOpenChange}>
    <Dialog.Portal>
      <Dialog.Overlay className={styles.overlay} />
      <Dialog.Content
        className={styles.dialog}
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          returnFocusRef.current?.focus({ preventScroll: true });
        }}
      >
        <header className={styles.header}>
          <div className={styles.identity}>
            <Dialog.Title>{artifact?.name ?? '文件预览'}</Dialog.Title>
            <p>{artifact?.mimeType ?? artifact?.artifactType ?? '未知类型'} · {formatSize(artifact?.size)}</p>
          </div>
          <div className={styles.actions}>
            {downloadHref && <a className={styles.iconButton} href={downloadHref} aria-label={`下载文件 ${artifact?.name ?? ''}`} title="下载文件"><Download size={18} /></a>}
            <Dialog.Close className={styles.iconButton} aria-label="关闭文件预览" title="关闭文件预览"><X size={19} /></Dialog.Close>
          </div>
        </header>
        <ArtifactPreviewContent artifact={artifact} dataSource={dataSource} open={open} />
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}

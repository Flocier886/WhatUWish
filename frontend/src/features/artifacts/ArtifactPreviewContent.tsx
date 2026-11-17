import { LoaderCircle, RefreshCw } from 'lucide-react';
import { useRef } from 'react';
import type { ArtifactReference } from '@/domain/runtime/types';
import type { ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { artifactRendererRegistry } from './renderers/artifactRenderers';
import { useArtifactPreview, type ArtifactPreviewStatus } from './useArtifactPreview';
import styles from './ArtifactPreviewDialog.module.css';

const statusMessage: Record<Exclude<ArtifactPreviewStatus, 'IDLE' | 'LOADING' | 'READY'>, string> = {
  FORBIDDEN: '你当前没有权限访问这个文件。',
  NOT_FOUND: '这个文件不存在或已被删除。',
  UNAVAILABLE: '这个文件当前不支持在线预览。',
  NETWORK_ERROR: '预览服务暂时不可用，请稍后重试。',
};

export function ArtifactPreviewContent({ artifact, dataSource, open = true, className }: { artifact?: ArtifactReference; dataSource: ArtifactContentDataSource; open?: boolean; className?: string }) {
  const scrollRootRef = useRef<HTMLDivElement>(null);
  const Renderer = artifactRendererRegistry.resolve(artifact?.preview);
  const canLoad = Boolean(Renderer && artifact?.previewSupported && artifact.accessState !== 'FORBIDDEN' && artifact.accessState !== 'NOT_FOUND' && artifact.accessState !== 'UNAVAILABLE');
  const preview = useArtifactPreview({ artifact, open, rendererSupported: canLoad, dataSource });
  const status = artifact?.accessState === 'FORBIDDEN' ? 'FORBIDDEN'
    : artifact?.accessState === 'NOT_FOUND' ? 'NOT_FOUND'
      : preview.status;

  return <div ref={scrollRootRef} className={className ?? styles.scrollRoot} data-testid="artifact-reader-scroll-root">
    {status === 'LOADING' && <div className={styles.state} role="status"><LoaderCircle className={styles.spinner} size={22} />正在加载文件内容</div>}
    {status === 'READY' && preview.value?.kind === 'text' && preview.value.content.trim() === '' && <div className={styles.state} role="status">这个文件没有可显示的内容。</div>}
    {status === 'READY' && preview.value && (preview.value.kind === 'binary' || preview.value.content.trim() !== '') && Renderer && <Renderer preview={preview.value} scrollRootRef={scrollRootRef} downloadHref={artifact?.runId ? dataSource.buildDownloadHref(artifact.runId, artifact.artifactId) : undefined} />}
    {status !== 'IDLE' && status !== 'LOADING' && status !== 'READY' && <div className={styles.state} role="alert">
      <p>{statusMessage[status]}</p>
      {(status === 'NETWORK_ERROR' || status === 'UNAVAILABLE') && <button type="button" className={styles.retryButton} onClick={preview.retry}><RefreshCw size={16} />重试</button>}
    </div>}
  </div>;
}

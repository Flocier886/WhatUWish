import { Download, Eye, FileKey, Info, LockKeyhole } from 'lucide-react';
import type { ArtifactReference } from '@/domain/runtime/types';
import type { ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { useArtifactPreview } from './useArtifactPreview';
import styles from './ArtifactReference.module.css';

interface ArtifactReferenceItemProps {
  artifact: ArtifactReference;
  canPreview: boolean;
  previewUnavailable: boolean;
  downloadHref?: string;
  dataSource: ArtifactContentDataSource;
  onPreview: (trigger: HTMLButtonElement) => void;
  onSelect?: () => void;
}

export function ArtifactReferenceItem({ artifact, canPreview, previewUnavailable, downloadHref, dataSource, onPreview, onSelect }: ArtifactReferenceItemProps) {
  // 输出图片同样只依据后端预览合同直显，不根据文件名、MIME 或工具名猜测。
  const inlineImage = canPreview && artifact.preview?.kind === 'image' && artifact.preview.schemaVersion === 'v1';
  const image = useArtifactPreview({ artifact, open: inlineImage, rendererSupported: inlineImage, dataSource });
  const available = artifact.accessState !== 'UNAVAILABLE' && artifact.accessState !== 'FORBIDDEN' && artifact.accessState !== 'NOT_FOUND';
  const accessLabel = available ? '可访问' : artifact.accessState === 'FORBIDDEN' ? '无权限' : artifact.accessState === 'NOT_FOUND' ? '已删除' : '暂不可用';
  const sizeLabel = artifact.size === undefined ? undefined : artifact.size < 1024 ? `${artifact.size} B` : artifact.size < 1024 * 1024 ? `${(artifact.size / 1024).toFixed(1)} KB` : `${(artifact.size / 1024 / 1024).toFixed(1)} MB`;
  const isPresentation = artifact.mimeType === 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
  const capabilityLabel = !available ? '文件当前不可访问。'
    : previewUnavailable ? '当前客户端不支持在线预览。'
      : artifact.preview?.kind === 'html-slides' ? 'HTML 演示页可在线预览。'
        : isPresentation ? 'PPTX 原件可下载；HTML 演示页需打开对应文件。'
          : canPreview ? '支持在线预览' : '可下载交付';
  return <article className={styles.card}>
    <span className={styles.icon}>{artifact.visibility === 'RESTRICTED' ? <LockKeyhole size={16} /> : <FileKey size={16} />}</span>
    <div className={styles.content}>
      <div className={styles.identity}><strong title={artifact.name}>{artifact.name}</strong><span className={`${styles.access} ${available ? styles.available : styles.unavailable}`}>{accessLabel}</span></div>
      <p>{artifact.summary ?? '未提供摘要。'}</p>
      {image.status === 'READY' && image.value?.kind === 'binary' && <img className={styles.inlineImage} src={image.value.objectUrl} alt={artifact.name} />}
      <div className={styles.metadata}><span>{artifact.artifactType}</span>{artifact.mimeType && <span>{artifact.mimeType}</span>}{sizeLabel && <span>{sizeLabel}</span>}{artifact.sourceStage && <span>来源：{artifact.sourceStage}</span>}</div>
      <div className={styles.footer}><span className={styles.capability}>{capabilityLabel}</span><div className={styles.actions}>
        {onSelect && <button type="button" className={styles.iconButton} aria-label={`查看文件详情 ${artifact.name}`} title="查看文件详情" onClick={onSelect}><Info size={16} /></button>}
        {available && canPreview && <button type="button" className={styles.iconButton} aria-label={`预览文件 ${artifact.name}`} title="预览文件" onClick={(event) => onPreview(event.currentTarget)}><Eye size={16} /></button>}
        {available && artifact.downloadSupported !== false && downloadHref && <a className={styles.iconButton} aria-label={`下载文件 ${artifact.name}`} title="下载文件" href={downloadHref}><Download size={16} /></a>}
      </div></div>
    </div>
  </article>;
}

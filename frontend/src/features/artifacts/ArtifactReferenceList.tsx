import { useRef, useState } from 'react';
import type { ArtifactReference } from '@/domain/runtime/types';
import type { ArtifactContentDataSource } from './gateway/ArtifactContentDataSource';
import { HttpArtifactContentDataSource } from './gateway/HttpArtifactContentDataSource';
import { artifactRendererRegistry } from './renderers/artifactRenderers';
import { ArtifactPreviewDialog } from './ArtifactPreviewDialog';
import { ArtifactReferenceItem } from './ArtifactReferenceItem';
import styles from './ArtifactReference.module.css';

const defaultDataSource = new HttpArtifactContentDataSource();

interface ArtifactReferenceListProps {
  artifacts: ArtifactReference[];
  compact?: boolean;
  heading?: string;
  dataSource?: ArtifactContentDataSource;
  onSelectArtifact?: (artifact: ArtifactReference) => void;
}

function isAvailable(artifact: ArtifactReference): boolean {
  return artifact.accessState !== 'UNAVAILABLE' && artifact.accessState !== 'FORBIDDEN' && artifact.accessState !== 'NOT_FOUND';
}

export function ArtifactReferenceList({ artifacts, compact = false, heading = '关联文件', dataSource = defaultDataSource, onSelectArtifact }: ArtifactReferenceListProps) {
  const [selectedArtifact, setSelectedArtifact] = useState<ArtifactReference>();
  const triggerRef = useRef<HTMLButtonElement | undefined>(undefined);
  if (artifacts.length === 0) return null;

  const selectArtifact = (artifact: ArtifactReference, trigger: HTMLButtonElement) => {
    triggerRef.current = trigger;
    setSelectedArtifact(artifact);
  };

  return <section className={`${styles.list} ${compact ? styles.compact : ''}`} aria-label="关联产物">
    <h3>{heading}</h3>
    {artifacts.map((artifact) => {
      const available = isAvailable(artifact);
      const canPreview = available && artifact.previewSupported === true && artifactRendererRegistry.supports(artifact.preview) && Boolean(artifact.runId);
      const downloadHref = available && artifact.downloadSupported !== false && artifact.runId
        ? dataSource.buildDownloadHref(artifact.runId, artifact.artifactId)
        : undefined;
      return <ArtifactReferenceItem
        key={artifact.artifactId}
        artifact={artifact}
        canPreview={canPreview}
        previewUnavailable={available && artifact.previewSupported === true && !canPreview}
        downloadHref={downloadHref}
        dataSource={dataSource}
        onPreview={(trigger) => selectArtifact(artifact, trigger)}
        onSelect={onSelectArtifact ? () => onSelectArtifact(artifact) : undefined}
      />;
    })}
    <ArtifactPreviewDialog
      artifact={selectedArtifact}
      dataSource={dataSource}
      open={Boolean(selectedArtifact)}
      returnFocusRef={triggerRef}
      onOpenChange={(open) => { if (!open) setSelectedArtifact(undefined); }}
    />
  </section>;
}

import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';
import styles from './ImageArtifactRenderer.module.css';

/** 只接收 useArtifactPreview 在 Reader 生命周期中创建的临时 Object URL。 */
export function ImageArtifactRenderer({ preview }: ArtifactRendererProps) {
  if (preview.kind !== 'binary') return null;
  return <figure className={styles.figure}>
    <img className={styles.image} src={preview.objectUrl} alt="Artifact 图片预览" />
  </figure>;
}

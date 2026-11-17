import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';

export function TextArtifactRenderer({ preview }: ArtifactRendererProps) {
  return <pre style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere',
    fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', fontSize: 13,
    lineHeight: 1.65, color: '#26313a' }}>{preview.kind === 'text' ? preview.content : ''}</pre>;
}

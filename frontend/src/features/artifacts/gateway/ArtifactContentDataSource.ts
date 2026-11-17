export type ArtifactContentErrorCode = 'FORBIDDEN' | 'NOT_FOUND' | 'UNAVAILABLE' | 'NETWORK_ERROR';

export class ArtifactContentError extends Error {
  readonly code: ArtifactContentErrorCode;

  constructor(code: ArtifactContentErrorCode) {
    super(code);
    this.name = 'ArtifactContentError';
    this.code = code;
  }
}

/** 内容 API 的受控结果：文本不经 URL 转换，图片二进制只在 Reader 生命周期内保留 Blob。 */
export type ArtifactPreviewContent =
  | { kind: 'text'; content: string }
  | { kind: 'binary'; blob: Blob };

export interface ArtifactContentDataSource {
  loadPreview(runId: string, artifactId: string, signal: AbortSignal): Promise<ArtifactPreviewContent>;
  buildDownloadHref(runId: string, artifactId: string): string;
}

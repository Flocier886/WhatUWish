import { apiUrl } from '@/gateways/api-base';
import { ArtifactContentError, type ArtifactContentDataSource, type ArtifactContentErrorCode, type ArtifactPreviewContent } from './ArtifactContentDataSource';

const errorByStatus: Partial<Record<number, ArtifactContentErrorCode>> = {
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'UNAVAILABLE',
};

function artifactPath(runId: string, artifactId: string): string {
  return `/api/v1/artifacts/runs/${encodeURIComponent(runId)}/${encodeURIComponent(artifactId)}`;
}

export class HttpArtifactContentDataSource implements ArtifactContentDataSource {
  async loadPreview(runId: string, artifactId: string, signal: AbortSignal): Promise<ArtifactPreviewContent> {
    let response: Response;
    try {
      response = await fetch(apiUrl(`${artifactPath(runId, artifactId)}/preview`), {
        headers: { Accept: 'text/markdown, text/plain;q=0.9, text/csv, image/png, image/jpeg, image/webp, image/gif, image/bmp, application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
        signal,
      });
    } catch (error) {
      if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error;
      throw new ArtifactContentError('NETWORK_ERROR');
    }
    if (!response.ok) throw new ArtifactContentError(errorByStatus[response.status] ?? 'NETWORK_ERROR');
    try {
      const mimeType = response.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase();
      if (mimeType && ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/bmp', 'application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'].includes(mimeType)) {
        return { kind: 'binary', blob: await response.blob() };
      }
      return { kind: 'text', content: await response.text() };
    } catch (error) {
      if (signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error;
      throw new ArtifactContentError('NETWORK_ERROR');
    }
  }

  buildDownloadHref(runId: string, artifactId: string): string {
    return apiUrl(`${artifactPath(runId, artifactId)}/download`);
  }
}

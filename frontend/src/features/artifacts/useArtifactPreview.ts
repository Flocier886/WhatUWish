import { useCallback, useEffect, useRef, useState } from 'react';
import type { ArtifactReference } from '@/domain/runtime/types';
import { ArtifactContentError, type ArtifactContentDataSource, type ArtifactContentErrorCode, type ArtifactPreviewContent } from './gateway/ArtifactContentDataSource';

export type ArtifactPreviewStatus = 'IDLE' | 'LOADING' | 'READY' | ArtifactContentErrorCode;

export type ArtifactPreviewValue =
  | { kind: 'text'; content: string }
  | { kind: 'binary'; objectUrl: string };

export interface ArtifactPreviewState {
  status: ArtifactPreviewStatus;
  value?: ArtifactPreviewValue;
  retry: () => void;
}

interface UseArtifactPreviewOptions {
  artifact?: ArtifactReference;
  open: boolean;
  rendererSupported: boolean;
  dataSource: ArtifactContentDataSource;
}

export function useArtifactPreview({ artifact, open, rendererSupported, dataSource }: UseArtifactPreviewOptions): ArtifactPreviewState {
  const [state, setState] = useState<Omit<ArtifactPreviewState, 'retry'>>({ status: 'IDLE' });
  const [attempt, setAttempt] = useState(0);
  const requestIdentity = useRef(0);
  const objectUrlRef = useRef<string | undefined>(undefined);

  const releaseObjectUrl = useCallback(() => {
    if (!objectUrlRef.current) return;
    URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = undefined;
  }, []);

  useEffect(() => {
    const currentRequest = ++requestIdentity.current;
    releaseObjectUrl();
    if (!open) {
      setState({ status: 'IDLE' });
      return;
    }
    if (!artifact?.runId || !rendererSupported) {
      setState({ status: 'UNAVAILABLE' });
      return;
    }

    const controller = new AbortController();
    setState({ status: 'LOADING' });
    void dataSource.loadPreview(artifact.runId, artifact.artifactId, controller.signal).then((content: ArtifactPreviewContent) => {
      if (controller.signal.aborted || requestIdentity.current !== currentRequest) return;
      if (content.kind === 'text') {
        setState({ status: 'READY', value: content });
        return;
      }
      const objectUrl = URL.createObjectURL(content.blob);
      if (controller.signal.aborted || requestIdentity.current !== currentRequest) {
        URL.revokeObjectURL(objectUrl);
        return;
      }
      objectUrlRef.current = objectUrl;
      setState({ status: 'READY', value: { kind: 'binary', objectUrl } });
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestIdentity.current !== currentRequest) return;
      const status = error instanceof ArtifactContentError ? error.code : 'NETWORK_ERROR';
      setState({ status });
    });

    return () => {
      controller.abort();
      requestIdentity.current += 1;
      releaseObjectUrl();
    };
  }, [artifact?.artifactId, artifact?.runId, attempt, dataSource, open, releaseObjectUrl, rendererSupported]);

  const retry = useCallback(() => setAttempt((current) => current + 1), []);
  return { ...state, retry };
}

import { useState } from 'react';
import { useRuntimeConsole } from '@/app/useRuntimeConsole';

export function useRunReplay() {
  const { loadReplay, selectRun } = useRuntimeConsole();
  const [loadingRunId, setLoadingRunId] = useState<string>();
  const openReplay = async (runId: string) => {
    setLoadingRunId(runId);
    const replay = await loadReplay(runId);
    if (replay) selectRun(runId);
    setLoadingRunId(undefined);
    return replay;
  };
  return { loadingRunId, openReplay };
}

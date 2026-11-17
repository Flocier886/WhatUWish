import { selectActiveEvents, selectActiveRun, selectSessionRuns } from '@/domain/runtime/selectors';
import { useRuntimeContext } from './RuntimeProvider';

export function useRuntimeConsole() {
  const runtime = useRuntimeContext();
  return {
    ...runtime,
    activeRun: selectActiveRun(runtime.state),
    activeEvents: selectActiveEvents(runtime.state),
    sessionRuns: selectSessionRuns(runtime.state),
  };
}

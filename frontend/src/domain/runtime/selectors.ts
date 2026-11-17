import type { RuntimeConsoleState } from './types';

export const selectActiveRun = (state: RuntimeConsoleState) => state.selectedRunId ? state.runsById[state.selectedRunId] : undefined;
export const selectActiveEvents = (state: RuntimeConsoleState) => state.selectedRunId ? state.eventsByRunId[state.selectedRunId] ?? [] : [];
export const selectSessionRuns = (state: RuntimeConsoleState, sessionId = state.activeSessionId) =>
  (state.runIdsBySession[sessionId] ?? []).map((runId) => state.runsById[runId]).filter(Boolean);

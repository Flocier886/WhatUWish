import type { ArtifactCreatedPayload, ArtifactPreviewDescriptor, ArtifactReference, ReportContentDeltaPayload, ReportLiveDraft, RunSummary, RuntimeConsoleState, RuntimeEvent, RuntimeIssue, ToolProgressPayload, ToolStartedPayload } from './types';
import { isTerminalStatus } from './types';

export type RuntimeAction =
  | { type: 'AGENTS_LOADING' }
  | { type: 'AGENTS_LOADED'; agents: RuntimeConsoleState['agents'] }
  | { type: 'AGENTS_FAILED'; message: string }
  | { type: 'RUN_CREATED'; run: RunSummary }
  | { type: 'RUNS_LOADED'; runs: RunSummary[] }
  | { type: 'EVENT_RECEIVED'; event: RuntimeEvent }
  | { type: 'REPLAY_LOADED'; run: RunSummary; events: RuntimeEvent[] }
  | { type: 'RUN_SELECTED'; runId: string }
  | { type: 'SESSION_SELECTED'; sessionId: string }
  | { type: 'STREAM_STATE_CHANGED'; state: RuntimeConsoleState['streamState']; message?: string }
  | { type: 'ISSUE_REPORTED'; issue: RuntimeIssue };

export const createInitialState = (sessionId = 'session-demo-001'): RuntimeConsoleState => ({
  agents: [],
  agentsLoadState: 'IDLE',
  activeSessionId: sessionId,
  runsById: {},
  runIdsBySession: {},
  eventsByRunId: {},
  artifactsById: {},
  reportDraftsByToolInvocationId: {},
  streamState: 'IDLE',
  issues: [],
});

const forbiddenArtifactKeys = new Set(['storageRef', 'storageKey', 'downloadUrl', 'previewUrl', 'path', 'localPath', 'content']);
const permittedArtifactKeys = new Set(['artifactId', 'reference', 'name', 'artifactType', 'mimeType', 'size', 'summary', 'visibility', 'sourceToolInvocationId', 'sourceStage', 'accessState', 'previewSupported', 'downloadSupported']);

const descriptorToken = /^[a-z0-9][a-z0-9._-]{0,63}$/i;

function sanitizePreview(value: unknown): ArtifactPreviewDescriptor | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.kind !== 'string' || typeof candidate.schemaVersion !== 'string') return undefined;
  const kind = candidate.kind.trim();
  const schemaVersion = candidate.schemaVersion.trim();
  if (!descriptorToken.test(kind) || !descriptorToken.test(schemaVersion)) return undefined;
  return { kind, schemaVersion };
}

export function sanitizeArtifact(payload: ArtifactCreatedPayload, runId = ''): ArtifactReference {
  const candidate = payload as unknown as Record<string, unknown>;
  const sanitized: Record<string, unknown> = {};
  Object.entries(candidate).forEach(([key, value]) => {
    if (permittedArtifactKeys.has(key) && !forbiddenArtifactKeys.has(key)) sanitized[key] = value;
  });
  const preview = sanitizePreview(candidate.preview);
  if (preview) sanitized.preview = preview;
  return { ...(sanitized as unknown as ArtifactReference), runId };
}

function appendIssue(state: RuntimeConsoleState, issue: RuntimeIssue): RuntimeConsoleState {
  return { ...state, issues: [...state.issues.slice(-19), issue] };
}

const associations = new Set<ToolStartedPayload['association']>(['PERSISTED', 'INFERRED', 'ORPHAN']);
const reportOutputTypes = new Set<ReportContentDeltaPayload['outputType']>(['markdown', 'html', 'ppt']);
const MAX_REPORT_DRAFT_LENGTH = 48_000;

function sanitizeToolStarted(payload: Partial<ToolStartedPayload>): ToolStartedPayload | undefined {
  if (typeof payload.toolInvocationId !== 'string' || !payload.toolInvocationId.trim()
    || typeof payload.toolName !== 'string' || !payload.toolName.trim()
    || payload.status !== 'RUNNING') return undefined;
  const sanitized: ToolStartedPayload = {
    toolInvocationId: payload.toolInvocationId.trim(),
    toolName: payload.toolName.trim(),
    status: 'RUNNING',
  };
  if (typeof payload.toolCallId === 'string' && payload.toolCallId.trim()) sanitized.toolCallId = payload.toolCallId.trim();
  if (typeof payload.parentLlmInvocationId === 'string' && payload.parentLlmInvocationId.trim()) sanitized.parentLlmInvocationId = payload.parentLlmInvocationId.trim();
  if (typeof payload.stepNo === 'number' && Number.isInteger(payload.stepNo) && payload.stepNo >= 0) sanitized.stepNo = payload.stepNo;
  if (typeof payload.dispatchOrder === 'number' && Number.isInteger(payload.dispatchOrder) && payload.dispatchOrder >= 0) sanitized.dispatchOrder = payload.dispatchOrder;
  if (payload.association && associations.has(payload.association)) sanitized.association = payload.association;
  if (typeof payload.argumentsSummary === 'string' && payload.argumentsSummary.trim()) sanitized.argumentsSummary = payload.argumentsSummary.trim();
  return sanitized;
}

function sanitizeReportContentDelta(payload: Partial<ReportContentDeltaPayload>): ReportContentDeltaPayload | undefined {
  if (payload.schemaVersion !== 'report.content_delta.v1'
    || typeof payload.toolInvocationId !== 'string' || !payload.toolInvocationId.trim()
    || typeof payload.toolName !== 'string' || !payload.toolName.trim()
    || typeof payload.outputType !== 'string' || !reportOutputTypes.has(payload.outputType as ReportContentDeltaPayload['outputType'])
    || !Number.isInteger(payload.chunkIndex) || Number(payload.chunkIndex) < 1
    || typeof payload.content !== 'string' || !payload.content.trim() || payload.content.length > 4000) return undefined;
  return {
    schemaVersion: 'report.content_delta.v1',
    toolInvocationId: payload.toolInvocationId.trim(),
    toolName: payload.toolName.trim(),
    outputType: payload.outputType as ReportContentDeltaPayload['outputType'],
    chunkIndex: Number(payload.chunkIndex),
    content: payload.content,
  };
}

function clearReportDraftsForRun(state: RuntimeConsoleState, runId: string): RuntimeConsoleState {
  const entries = Object.entries(state.reportDraftsByToolInvocationId)
    .filter(([, draft]) => draft.runId !== runId);
  if (entries.length === Object.keys(state.reportDraftsByToolInvocationId).length) return state;
  return { ...state, reportDraftsByToolInvocationId: Object.fromEntries(entries) };
}

function applyReportContentDelta(state: RuntimeConsoleState, event: RuntimeEvent, payload: ReportContentDeltaPayload): RuntimeConsoleState {
  const existing = state.reportDraftsByToolInvocationId[payload.toolInvocationId];
  if (existing && (existing.runId !== event.runId || payload.chunkIndex <= existing.lastChunkIndex)) return state;
  const accumulated = `${existing?.content ?? ''}${payload.content}`;
  const truncated = accumulated.length > MAX_REPORT_DRAFT_LENGTH;
  const content = truncated ? accumulated.slice(-MAX_REPORT_DRAFT_LENGTH) : accumulated;
  const draft: ReportLiveDraft = {
    runId: event.runId,
    toolInvocationId: payload.toolInvocationId,
    toolName: payload.toolName,
    outputType: payload.outputType,
    lastChunkIndex: payload.chunkIndex,
    content,
    truncated: existing?.truncated || truncated,
  };
  return {
    ...state,
    reportDraftsByToolInvocationId: { ...state.reportDraftsByToolInvocationId, [draft.toolInvocationId]: draft },
  };
}

function reportEventIssue(state: RuntimeConsoleState, event: Partial<RuntimeEvent>, message: string): RuntimeConsoleState {
  const withIssue = appendIssue(state, { id: `issue-${Date.now()}-${state.issues.length}`, runId: event.runId, message });
  if (!event.runId || !withIssue.runsById[event.runId]) return withIssue;
  return {
    ...withIssue,
    runsById: { ...withIssue.runsById, [event.runId]: { ...withIssue.runsById[event.runId], dataIncomplete: true } },
  };
}

function addRun(state: RuntimeConsoleState, run: RunSummary): RuntimeConsoleState {
  const currentIds = state.runIdsBySession[run.sessionId] ?? [];
  const runIds = currentIds.includes(run.runId) ? currentIds : [run.runId, ...currentIds];
  return {
    ...state,
    activeSessionId: run.sessionId,
    selectedRunId: run.runId,
    runsById: { ...state.runsById, [run.runId]: run },
    runIdsBySession: { ...state.runIdsBySession, [run.sessionId]: runIds },
    eventsByRunId: state.eventsByRunId[run.runId] ? state.eventsByRunId : { ...state.eventsByRunId, [run.runId]: [] },
  };
}

function applyTerminalEvent(run: RunSummary, event: RuntimeEvent): RunSummary {
  if (event.eventType === 'RUN_WAITING_USER_INPUT') return { ...run, status: 'WAITING_USER_INPUT', finishedAt: event.occurredAt };
  if (event.eventType === 'RUN_COMPLETED') {
    const payload = event.payload as { summary: string };
    return { ...run, status: 'COMPLETED', summary: payload.summary, finishedAt: event.occurredAt };
  }
  if (event.eventType === 'RUN_FAILED') {
    const payload = event.payload as { errorCode?: string; errorMessage: string };
    return { ...run, status: 'FAILED', finishedAt: event.occurredAt, error: { code: payload.errorCode, message: payload.errorMessage } };
  }
  if (event.eventType === 'RUN_STARTED' || event.eventType === 'RUN_STATUS') {
    const payload = event.payload as { status: RunSummary['status'] };
    return { ...run, status: payload.status, startedAt: run.startedAt ?? event.occurredAt };
  }
  return run;
}

function ingestEvent(state: RuntimeConsoleState, event: RuntimeEvent): RuntimeConsoleState {
  if (event.schemaVersion !== 'v1' || !event.eventId || !event.runId || !event.sessionId || !event.requestId || !event.agentId || event.sequence < 1) {
    return reportEventIssue(state, event, '收到缺少稳定关联字段、未知版本或无效 sequence 的运行事件。');
  }
  const run = state.runsById[event.runId];
  if (!run) return reportEventIssue(state, event, '收到的事件未关联到当前已知 Run。');
  if (isTerminalStatus(run.status) && event.eventType !== 'RUN_COMPLETED' && event.eventType !== 'RUN_FAILED'
    && event.eventType !== 'RUN_WAITING_USER_INPUT') return state;
  let acceptedEvent = event;
  if (event.eventType === 'TOOL_STARTED') {
    const payload = sanitizeToolStarted(event.payload as Partial<ToolStartedPayload>);
    if (!payload) return reportEventIssue(state, event, '工具开始事件缺少稳定调用身份、工具名称或 RUNNING 状态。');
    acceptedEvent = { ...event, payload };
  }
  if (event.eventType === 'TOOL_PROGRESS') {
    const payload = event.payload as Partial<ToolProgressPayload>;
    if (!payload.runtimeEventId || payload.runtimeEventId !== event.eventId || !payload.toolInvocationId) {
      return reportEventIssue(state, event, '工具过程事件缺少稳定事实身份或调用归属。');
    }
  }
  if (event.eventType === 'REPORT_CONTENT_DELTA') {
    const payload = sanitizeReportContentDelta(event.payload as Partial<ReportContentDeltaPayload>);
    if (!payload) return reportEventIssue(state, event, 'Report 正文增量不符合受控 Live 协议。');
    acceptedEvent = { ...event, payload };
  }

  const existing = state.eventsByRunId[event.runId] ?? [];
  if (existing.some((item) => item.eventId === event.eventId)) return state;
  if (existing.some((item) => item.sequence === event.sequence)) {
    return reportEventIssue(state, event, `Run ${event.runId} 收到重复 sequence ${event.sequence} 的不同事件。`);
  }

  const events = [...existing, acceptedEvent].sort((left, right) => left.sequence - right.sequence);
  const updatedRun = applyTerminalEvent(run, acceptedEvent);
  const next: RuntimeConsoleState = {
    ...state,
    eventsByRunId: { ...state.eventsByRunId, [event.runId]: events },
    runsById: { ...state.runsById, [event.runId]: updatedRun },
    streamState: acceptedEvent.eventType === 'RUN_COMPLETED' || acceptedEvent.eventType === 'RUN_FAILED'
      || acceptedEvent.eventType === 'RUN_WAITING_USER_INPUT' ? 'IDLE' : 'STREAMING',
  };
  let nextWithDraft = next;
  if (acceptedEvent.eventType === 'REPORT_CONTENT_DELTA') {
    nextWithDraft = applyReportContentDelta(next, acceptedEvent, acceptedEvent.payload as ReportContentDeltaPayload);
  }
  if (acceptedEvent.eventType === 'RUN_COMPLETED' || acceptedEvent.eventType === 'RUN_FAILED') {
    nextWithDraft = clearReportDraftsForRun(nextWithDraft, event.runId);
  }
  if (event.eventType === 'ARTIFACT_CREATED') {
    const artifact = sanitizeArtifact(event.payload as ArtifactCreatedPayload, event.runId);
    if (!artifact.artifactId || !artifact.reference || !artifact.name || !artifact.artifactType || !artifact.visibility) {
      return reportEventIssue(nextWithDraft, event, 'artifact 事件缺少安全展示所需的元数据。');
    }
    return { ...nextWithDraft, artifactsById: { ...nextWithDraft.artifactsById, [artifact.artifactId]: artifact } };
  }
  return nextWithDraft;
}

export function runtimeReducer(state: RuntimeConsoleState, action: RuntimeAction): RuntimeConsoleState {
  switch (action.type) {
    case 'AGENTS_LOADING': return { ...state, agentsLoadState: 'LOADING' };
    case 'AGENTS_LOADED': return { ...state, agents: action.agents, agentsLoadState: 'READY' };
    case 'AGENTS_FAILED': return { ...state, agentsLoadState: 'ERROR', issues: [...state.issues, { id: `agents-${Date.now()}`, message: action.message }] };
    case 'RUN_CREATED': return addRun(state, action.run);
    case 'RUNS_LOADED': {
      return action.runs.reduce((next, run) => {
        const currentIds = next.runIdsBySession[run.sessionId] ?? [];
        const ids = currentIds.includes(run.runId) ? currentIds : [...currentIds, run.runId];
        return {
          ...next,
          runsById: { ...next.runsById, [run.runId]: { ...next.runsById[run.runId], ...run } },
          runIdsBySession: { ...next.runIdsBySession, [run.sessionId]: ids },
          eventsByRunId: next.eventsByRunId[run.runId] ? next.eventsByRunId : { ...next.eventsByRunId, [run.runId]: [] },
        };
      }, state);
    }
    case 'EVENT_RECEIVED': return ingestEvent(state, action.event);
    case 'SESSION_SELECTED': return { ...state, activeSessionId: action.sessionId, selectedRunId: undefined };
    case 'RUN_SELECTED': return state.runsById[action.runId] ? { ...state, selectedRunId: action.runId } : state;
    case 'STREAM_STATE_CHANGED': {
      const next = action.message
        ? appendIssue({ ...state, streamState: action.state }, { id: `stream-${Date.now()}`, message: action.message })
        : { ...state, streamState: action.state };
      if (action.state !== 'ERROR') return next;
      const activeRunIds = Object.values(next.runsById)
        .filter((run) => !isTerminalStatus(run.status))
        .map((run) => run.runId);
      return activeRunIds.reduce((current, runId) => clearReportDraftsForRun(current, runId), next);
    }
    case 'ISSUE_REPORTED': return appendIssue(state, action.issue);
    case 'REPLAY_LOADED': {
      const replayingRun = { ...action.run, status: 'RUNNING' as const, finishedAt: undefined, summary: undefined, error: undefined, isHistorical: true };
      let next = clearReportDraftsForRun(
        addRun({ ...state, eventsByRunId: { ...state.eventsByRunId, [action.run.runId]: [] } }, replayingRun),
        action.run.runId,
      );
      [...action.events].sort((left, right) => left.sequence - right.sequence).forEach((event) => { next = ingestEvent(next, event); });
      return next;
    }
    default: return state;
  }
}

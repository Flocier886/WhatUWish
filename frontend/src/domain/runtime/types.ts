export type AgentStrategy = 'REACT' | 'LEGACY';
export type AgentAvailability = 'AVAILABLE' | 'UNAVAILABLE';
export type RunStatus = 'QUEUED' | 'CONNECTING' | 'RUNNING' | 'WAITING_USER_INPUT' | 'COMPLETED' | 'FAILED' | 'DISCONNECTED';

export type RuntimeEventType =
  | 'RUN_STARTED'
  | 'INPUT_FILE'
  | 'RUN_STATUS'
  | 'LLM_RESPONSE'
  | 'TOOL_STARTED'
  | 'TOOL_PROGRESS'
  | 'REPORT_CONTENT_DELTA'
  | 'TOOL_RESULT'
  | 'ARTIFACT_CREATED'
  | 'DESKTOP_CONTROL'
  | 'RUN_WAITING_USER_INPUT'
  | 'RUN_COMPLETED'
  | 'RUN_FAILED';

export interface AgentSummary {
  agentId: string;
  name: string;
  description?: string;
  strategy: AgentStrategy;
  availability: AgentAvailability;
}

export interface SessionSummary {
  sessionId: string;
  title: string;
  updatedAt: string;
  runCount: number;
  messageCount?: number;
}

export type ConversationMessageKind = 'USER' | 'AGENT' | 'ACTIVITY' | 'SYSTEM' | 'ERROR';
export type ConversationMessageStatus = 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'HISTORICAL';

export interface ConversationActivity {
  activityId: string;
  kind: 'THINKING' | 'TOOL' | 'ARTIFACT' | 'STATUS';
  label: string;
  summary: string;
  detail?: string;
  state: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
}

export interface ConversationMessage {
  messageId: string;
  sessionId: string;
  runId?: string;
  kind: ConversationMessageKind;
  status: ConversationMessageStatus;
  content: string;
  createdAt: string;
  activities?: ConversationActivity[];
}

export interface RunError {
  code?: string;
  message: string;
}

export interface RunSummary {
  runId: string;
  sessionId: string;
  requestId: string;
  agentId: string;
  task: string;
  status: RunStatus;
  startedAt?: string;
  finishedAt?: string;
  summary?: string;
  error?: RunError;
  isHistorical?: boolean;
  dataIncomplete?: boolean;
}

export interface ArtifactPreviewDescriptor {
  kind: string;
  schemaVersion: string;
}

export interface InputFilePayload {
  fileId: string;
  name: string;
  mimeType: string;
  size: number;
  order: number;
  status: 'AVAILABLE' | 'UNAVAILABLE';
}

export interface ArtifactReference {
  artifactId: string;
  runId?: string;
  reference: string;
  name: string;
  artifactType: string;
  mimeType?: string;
  size?: number;
  summary?: string;
  visibility: 'VISIBLE' | 'RESTRICTED';
  sourceToolInvocationId?: string;
  sourceStage?: string;
  accessState?: 'AVAILABLE' | 'UNAVAILABLE' | 'FORBIDDEN' | 'NOT_FOUND';
  previewSupported?: boolean;
  downloadSupported?: boolean;
  preview?: ArtifactPreviewDescriptor;
}

export interface RunStartedPayload { status: RunStatus; task: string }
export interface RunStatusPayload { status: RunStatus; message?: string }
export interface LlmResponsePayload { llmInvocationId: string; stepNo?: number; model?: string; scene?: string; text: string }
export interface ToolStartedPayload {
  toolInvocationId: string;
  toolCallId?: string;
  parentLlmInvocationId?: string;
  stepNo?: number;
  dispatchOrder?: number;
  toolName: string;
  status: 'RUNNING';
  association?: 'PERSISTED' | 'INFERRED' | 'ORPHAN';
  argumentsSummary?: string;
}
export interface ToolResultPayload {
  toolInvocationId: string;
  toolCallId?: string;
  parentLlmInvocationId?: string;
  stepNo?: number;
  dispatchOrder?: number;
  toolName: string;
  status: 'SUCCEEDED' | 'PARTIAL_SUCCESS' | 'FAILED';
  association?: 'PERSISTED' | 'INFERRED' | 'ORPHAN';
  argumentsSummary?: string;
  observation: string;
  errorCode?: string;
  errorMessage?: string;
  artifactIds?: string[];
    presentation?: {
      kind: 'canvas.publish.v1'; schemaVersion: '1';
      data: { artifactId: string; title: string; openInPanel: boolean };
    } | {
      kind: 'genui.tree.v1' | 'genui.patch.v1'; schemaVersion: string;
      data: Record<string, unknown>;
    };
}
export interface ToolProgressPayload {
  runtimeEventId: string;
  toolInvocationId: string;
  toolName: string;
  toolSequence?: number;
  stage: string;
  status: string;
  message: string;
  artifactId?: string;
  presentationKind: string;
  presentationSchemaVersion: string;
  presentationData: Record<string, unknown>;
}
export interface ReportContentDeltaPayload {
  schemaVersion: 'report.content_delta.v1';
  toolInvocationId: string;
  toolName: string;
  outputType: 'markdown' | 'html' | 'ppt';
  chunkIndex: number;
  content: string;
}
export type ArtifactCreatedPayload = Omit<ArtifactReference, 'runId'>;
export interface RunCompletedPayload { status: 'COMPLETED'; summary: string; artifactIds?: string[]; artifactSelection?: 'explicit' | 'fallback' }
export interface RunFailedPayload { status: 'FAILED'; errorCode?: string; errorMessage: string }
export interface DesktopControlPayload {
  presentationSchemaVersion: 'v1';
  controlId: string;
  sourceRunId: string;
  resumeRunId?: string;
  toolCallId: string;
  reason: string;
  streamUrl?: string;
  holdUntil?: number;
  status: 'PENDING' | 'RESUME_PENDING' | 'RESUMING' | 'COMPLETED' | 'FAILED' | 'EXPIRED' | 'CANCELLED';
  resumeRequestId?: string;
}
export interface RunWaitingUserInputPayload { status: 'WAITING_USER_INPUT'; message: string }

export type RuntimePayload =
  | RunStartedPayload
  | InputFilePayload
  | RunStatusPayload
  | LlmResponsePayload
  | ToolStartedPayload
  | ToolProgressPayload
  | ReportContentDeltaPayload
  | ToolResultPayload
  | ArtifactCreatedPayload
  | RunCompletedPayload
  | RunFailedPayload
  | DesktopControlPayload
  | RunWaitingUserInputPayload;

export interface RuntimeEvent<TPayload extends RuntimePayload = RuntimePayload> {
  schemaVersion: 'v1';
  eventId: string;
  sequence: number;
  eventType: RuntimeEventType;
  occurredAt: string;
  sessionId: string;
  runId: string;
  requestId: string;
  agentId: string;
  payload: TPayload;
}

export interface RunReplay {
  run: RunSummary;
  events: RuntimeEvent[];
}

export interface ConversationSnapshot {
  session: SessionSummary;
  replays: RunReplay[];
}

export interface StartRunCommand {
  agentId: string;
  sessionId: string;
  task: string;
  maxStep?: number;
  fileIds?: string[];
}

export interface RuntimeIssue {
  id: string;
  runId?: string;
  sessionId?: string;
  message: string;
}

/** 仅属于当前 Live Run 的临时 Report 草稿，不可由历史回放恢复。 */
export interface ReportLiveDraft {
  runId: string;
  toolInvocationId: string;
  toolName: string;
  outputType: 'markdown' | 'html' | 'ppt';
  lastChunkIndex: number;
  content: string;
  truncated?: boolean;
}

export interface RuntimeConsoleState {
  agents: AgentSummary[];
  agentsLoadState: 'IDLE' | 'LOADING' | 'READY' | 'ERROR';
  activeSessionId: string;
  selectedRunId?: string;
  runsById: Record<string, RunSummary>;
  runIdsBySession: Record<string, string[]>;
  eventsByRunId: Record<string, RuntimeEvent[]>;
  artifactsById: Record<string, ArtifactReference>;
  reportDraftsByToolInvocationId: Record<string, ReportLiveDraft>;
  streamState: 'IDLE' | 'CONNECTING' | 'STREAMING' | 'ERROR';
  issues: RuntimeIssue[];
}

export const isTerminalStatus = (status: RunStatus): boolean => status === 'COMPLETED' || status === 'FAILED' || status === 'WAITING_USER_INPUT';

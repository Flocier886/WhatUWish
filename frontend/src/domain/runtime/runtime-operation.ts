import { projectRichPresentation, type RichPresentationDetail } from './rich-presentation-adapter';
import type { ArtifactReference, RuntimeEvent, ToolProgressPayload, ToolResultPayload, ToolStartedPayload } from './types';

export type RuntimeOperationState = 'RUNNING' | 'SUCCEEDED' | 'PARTIAL_SUCCESS' | 'FAILED';

export interface RuntimeOperationStage {
  stageId: string;
  slotKey: string;
  eventId: string;
  sequence: number;
  occurredAt: string;
  status: string;
  label: string;
  summary: string;
  details: RichPresentationDetail[];
  unavailable?: boolean;
}

export interface RuntimeOperation {
  operationId: string;
  toolName: string;
  toolCallId?: string;
  parentLlmInvocationId?: string;
  stepNo?: number;
  dispatchOrder?: number;
  association?: 'PERSISTED' | 'INFERRED' | 'ORPHAN';
  state: RuntimeOperationState;
  lifecycleStartMissing: boolean;
  startedAt?: string;
  updatedAt: string;
  argumentsSummary?: string;
  observation?: string;
  errorCode?: string;
  errorMessage?: string;
  stages: RuntimeOperationStage[];
  artifactIds: string[];
  canvasPresentation?: Extract<NonNullable<ToolResultPayload['presentation']>, { kind: 'canvas.publish.v1' }>;
  isRich: boolean;
}

interface MutableRuntimeOperation extends RuntimeOperation {
  firstSequence: number;
}

function createOperation(
  toolInvocationId: string,
  toolName: string,
  event: RuntimeEvent,
  lifecycleStartMissing: boolean,
): MutableRuntimeOperation {
  return {
    operationId: toolInvocationId,
    toolName,
    state: 'RUNNING',
    lifecycleStartMissing,
    updatedAt: event.occurredAt,
    stages: [],
    artifactIds: [],
    isRich: false,
    firstSequence: event.sequence,
  };
}

function addArtifactId(operation: MutableRuntimeOperation, artifactId: string | undefined): void {
  if (artifactId && !operation.artifactIds.includes(artifactId)) operation.artifactIds.push(artifactId);
}

function applyStarted(operation: MutableRuntimeOperation, event: RuntimeEvent): void {
  const payload = event.payload as ToolStartedPayload;
  operation.toolName = payload.toolName;
  operation.toolCallId = payload.toolCallId;
  operation.parentLlmInvocationId = payload.parentLlmInvocationId;
  operation.stepNo = payload.stepNo;
  operation.dispatchOrder = payload.dispatchOrder;
  operation.association = payload.association;
  operation.argumentsSummary = payload.argumentsSummary;
  operation.lifecycleStartMissing = false;
  operation.startedAt = event.occurredAt;
  operation.updatedAt = event.occurredAt;
}

function applyProgress(operation: MutableRuntimeOperation, event: RuntimeEvent): void {
  const payload = event.payload as ToolProgressPayload;
  const projection = projectRichPresentation(payload);
  const stage: RuntimeOperationStage = {
    stageId: projection.mergePolicy === 'replace' ? projection.slotKey : event.eventId,
    slotKey: projection.slotKey,
    eventId: event.eventId,
    sequence: event.sequence,
    occurredAt: event.occurredAt,
    status: payload.status,
    ...projection.view,
  };
  if (projection.mergePolicy === 'replace') {
    const currentIndex = operation.stages.findIndex((item) => item.slotKey === projection.slotKey);
    if (currentIndex >= 0) operation.stages[currentIndex] = stage;
    else operation.stages.push(stage);
  } else {
    operation.stages.push(stage);
  }
  operation.stages.sort((left, right) => left.sequence - right.sequence);
  operation.isRich = true;
  operation.updatedAt = event.occurredAt;
  addArtifactId(operation, payload.artifactId);
}

function applyResult(operation: MutableRuntimeOperation, event: RuntimeEvent): void {
  const payload = event.payload as ToolResultPayload;
  operation.toolName = payload.toolName;
  operation.toolCallId = payload.toolCallId ?? operation.toolCallId;
  operation.parentLlmInvocationId = payload.parentLlmInvocationId ?? operation.parentLlmInvocationId;
  operation.stepNo = payload.stepNo ?? operation.stepNo;
  operation.dispatchOrder = payload.dispatchOrder ?? operation.dispatchOrder;
  operation.association = payload.association ?? operation.association;
  operation.argumentsSummary = payload.argumentsSummary ?? operation.argumentsSummary;
  operation.observation = payload.observation;
  operation.errorCode = payload.errorCode;
  operation.errorMessage = payload.errorMessage;
  operation.state = payload.status;
  operation.updatedAt = event.occurredAt;
  payload.artifactIds?.forEach((artifactId) => addArtifactId(operation, artifactId));
  if (operation.toolName === 'canvas_publish' && payload.status === 'SUCCEEDED'
    && payload.presentation?.kind === 'canvas.publish.v1'
    && payload.presentation.schemaVersion === '1'
    && typeof payload.presentation.data?.artifactId === 'string'
    && typeof payload.presentation.data?.title === 'string'
    && typeof payload.presentation.data?.openInPanel === 'boolean') {
    operation.canvasPresentation = {
      kind: 'canvas.publish.v1', schemaVersion: '1',
      data: { artifactId: payload.presentation.data.artifactId,
        title: payload.presentation.data.title, openInPanel: payload.presentation.data.openInPanel },
    };
  }
}

function publicOperation(operation: MutableRuntimeOperation): RuntimeOperation {
  const projected: Partial<MutableRuntimeOperation> = { ...operation };
  delete projected.firstSequence;
  return projected as RuntimeOperation;
}

/** 将 Live 与 Replay 的同一组安全事实确定性聚合为工具操作展示模型。 */
export function projectRuntimeOperations(
  events: RuntimeEvent[],
  artifactsById: Record<string, ArtifactReference> = {},
): RuntimeOperation[] {
  const operations = new Map<string, MutableRuntimeOperation>();
  const seenEventIds = new Set<string>();
  const ordered = [...events].sort((left, right) => left.sequence - right.sequence);

  ordered.forEach((event) => {
    if (seenEventIds.has(event.eventId)) return;
    seenEventIds.add(event.eventId);
    if (event.eventType !== 'TOOL_STARTED' && event.eventType !== 'TOOL_PROGRESS' && event.eventType !== 'TOOL_RESULT') return;
    const payload = event.payload as ToolStartedPayload | ToolProgressPayload | ToolResultPayload;
    const operationId = payload.toolInvocationId;
    if (!operationId) return;
    let operation = operations.get(operationId);
    if (!operation) {
      operation = createOperation(operationId, payload.toolName, event, event.eventType !== 'TOOL_STARTED');
      operations.set(operationId, operation);
    }
    if (event.eventType === 'TOOL_STARTED') applyStarted(operation, event);
    if (event.eventType === 'TOOL_PROGRESS') applyProgress(operation, event);
    if (event.eventType === 'TOOL_RESULT') applyResult(operation, event);
  });

  Object.values(artifactsById).forEach((artifact) => {
    if (!artifact.sourceToolInvocationId) return;
    const operation = operations.get(artifact.sourceToolInvocationId);
    if (operation) addArtifactId(operation, artifact.artifactId);
  });

  return [...operations.values()]
    .sort((left, right) => (left.dispatchOrder ?? left.firstSequence) - (right.dispatchOrder ?? right.firstSequence)
      || left.firstSequence - right.firstSequence)
    .map(publicOperation);
}

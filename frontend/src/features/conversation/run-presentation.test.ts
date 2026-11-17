import { describe, expect, it } from 'vitest';
import { createLiveReplay, historySuccessReplay } from '@/mocks/runtime-fixtures';
import type { RunSummary, RuntimeEvent, ToolProgressPayload } from '@/domain/runtime/types';
import { deriveRunPresentation } from './run-presentation';

describe('deriveRunPresentation', () => {
  it('merges ReAct, Summary, and completed result into one final-answer slot', () => {
    const presentation = deriveRunPresentation(historySuccessReplay.events, historySuccessReplay.run);
    expect(presentation.finalText).toBe(historySuccessReplay.run.summary);
    expect(presentation.finalEventId).toBeUndefined();
    expect(presentation.processCount).toBe(2);
    expect(presentation.phase).toBe('COMPLETED');
  });

  it('uses the latest model response as the only temporary final answer while running', () => {
    const run: RunSummary = { runId: 'run-live', sessionId: 'session-live', requestId: 'request-live', agentId: 'react-runtime', task: 'stream presentation test', status: 'RUNNING', startedAt: '2026-07-25T00:00:00Z' };
    const replay = createLiveReplay(run, false);
    const presentation = deriveRunPresentation(replay.events.slice(0, 4), run);
    expect(presentation.finalText).toContain('资料可用');
    expect(presentation.finalEventId).toBe('evt-run-live-4');
    expect(presentation.isStreaming).toBe(true);
    expect(presentation).not.toHaveProperty('activities');
  });

  it('不再承担工具 observation 展示职责', () => {
    const run: RunSummary = { runId: 'run-detail', sessionId: 'session-detail', requestId: 'request-detail', agentId: 'react-runtime', task: 'activity detail test', status: 'COMPLETED', summary: 'complete' };
    const presentation = deriveRunPresentation(createLiveReplay(run, false).events, run);
    expect(presentation).not.toHaveProperty('activities');
  });

  it('不把 Rich artifact progress 或正式 Artifact 混入回答状态投影', () => {
    const run: RunSummary = { runId: 'run-rich-artifact', sessionId: 'session-rich-artifact', requestId: 'request-rich-artifact', agentId: 'react-runtime', task: 'artifact folding', status: 'RUNNING' };
    const progressPayload: ToolProgressPayload = {
      runtimeEventId: 'progress-artifact-1', toolInvocationId: 'tool-rich-1', toolName: 'deep_search_tool', stage: 'artifact_created', status: 'completed', message: '产物已登记', artifactId: 'artifact-rich-1', presentationKind: 'deep_search.artifact.v1', presentationSchemaVersion: '1', presentationData: { artifactId: 'artifact-rich-1', name: '研究证据包', artifactType: 'research_evidence_package' },
    };
    const progress: RuntimeEvent<ToolProgressPayload> = { schemaVersion: 'v1', eventId: progressPayload.runtimeEventId, sequence: 1, eventType: 'TOOL_PROGRESS', occurredAt: '2026-08-05T00:00:00Z', sessionId: run.sessionId, runId: run.runId, requestId: run.requestId, agentId: run.agentId, payload: progressPayload };
    const artifact: RuntimeEvent = { schemaVersion: 'v1', eventId: 'artifact-created-1', sequence: 2, eventType: 'ARTIFACT_CREATED', occurredAt: '2026-08-05T00:00:01Z', sessionId: run.sessionId, runId: run.runId, requestId: run.requestId, agentId: run.agentId, payload: { artifactId: 'artifact-rich-1', reference: 'artifact:research-evidence', name: '研究证据包', artifactType: 'research_evidence_package', visibility: 'VISIBLE' } };
    const presentation = deriveRunPresentation([progress, artifact], run);
    expect(presentation).not.toHaveProperty('activities');
    expect(presentation.finalText).toBe('');
  });
});

import { describe, expect, it } from 'vitest';
import { createInitialState, runtimeReducer, sanitizeArtifact } from './event-reducer';
import type { RunSummary, RuntimeEvent } from './types';

const run: RunSummary = { runId: 'run-test', sessionId: 'session-test', requestId: 'request-test', agentId: 'agent-test', task: '测试任务', status: 'RUNNING' };
const makeEvent = (sequence: number, eventType: RuntimeEvent['eventType'], payload: RuntimeEvent['payload']): RuntimeEvent => ({ schemaVersion: 'v1', eventId: `event-${sequence}`, sequence, eventType, occurredAt: '2026-07-23T00:00:00Z', sessionId: run.sessionId, runId: run.runId, requestId: run.requestId, agentId: run.agentId, payload });

describe('runtimeReducer', () => {
  it('按 sequence 排序并按 eventId 去重', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    const later = makeEvent(2, 'LLM_RESPONSE', { llmInvocationId: 'llm-2', text: '第二个事件' });
    const earlier = makeEvent(1, 'RUN_STARTED', { status: 'RUNNING', task: run.task });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: later });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: earlier });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: earlier });
    expect(state.eventsByRunId[run.runId].map((item) => item.sequence)).toEqual([1, 2]);
  });

  it('终态后忽略新增的普通事件', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: makeEvent(1, 'RUN_COMPLETED', { status: 'COMPLETED', summary: '完成' }) });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: makeEvent(2, 'LLM_RESPONSE', { llmInvocationId: 'late', text: '不应追加' }) });
    expect(state.runsById[run.runId].status).toBe('COMPLETED');
    expect(state.eventsByRunId[run.runId]).toHaveLength(1);
  });

  it('过滤 artifact 的路径和存储实现字段', () => {
    const artifact = sanitizeArtifact({ artifactId: 'artifact-1', reference: 'artifact:safe', name: '报告.md', artifactType: 'report', visibility: 'VISIBLE', sourceStage: 'deep_search_tool', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'future-json', schemaVersion: 'v7', content: 'secret nested body', previewUrl: 'https://persistent.example/preview', storageRef: '/private/nested', path: 'C:\\private' }, storageRef: '/private/file', storageKey: 'private-key', downloadUrl: 'https://persistent.example/file', content: 'secret body' } as never, 'run-1');
    expect(artifact).toMatchObject({ artifactId: 'artifact-1', reference: 'artifact:safe', runId: 'run-1', sourceStage: 'deep_search_tool', accessState: 'AVAILABLE', previewSupported: true, downloadSupported: true, preview: { kind: 'future-json', schemaVersion: 'v7' } });
    expect(artifact).not.toHaveProperty('storageRef');
    expect(artifact).not.toHaveProperty('storageKey');
    expect(artifact).not.toHaveProperty('downloadUrl');
    expect(artifact).not.toHaveProperty('content');
    expect(artifact.preview).toEqual({ kind: 'future-json', schemaVersion: 'v7' });
    expect(artifact.preview).not.toHaveProperty('content');
    expect(artifact.preview).not.toHaveProperty('previewUrl');
    expect(artifact.preview).not.toHaveProperty('storageRef');
    expect(artifact.preview).not.toHaveProperty('path');
  });

  it('丢弃结构无效的 preview descriptor', () => {
    expect(sanitizeArtifact({ artifactId: 'artifact-1', preview: { kind: 'markdown', schemaVersion: '../v1' } } as never).preview).toBeUndefined();
    expect(sanitizeArtifact({ artifactId: 'artifact-1', preview: { kind: 'markdown' } } as never).preview).toBeUndefined();
    expect(sanitizeArtifact({ artifactId: 'artifact-1', preview: 'markdown/v1' } as never).preview).toBeUndefined();
  });

  it('按持久化 runtimeEventId 对同一 Rich Progress 去重', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    const progress = makeEvent(1, 'TOOL_PROGRESS', {
      runtimeEventId: 'runtime-fact-1',
      toolInvocationId: 'tool-1',
      toolName: 'deep_search_tool',
      stage: 'search_completed',
      status: 'completed',
      message: '已获得候选来源',
      presentationKind: 'deep_search.search_results.v1',
      presentationSchemaVersion: '1',
      presentationData: { sources: [] },
    });
    progress.eventId = 'runtime-fact-1';

    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: progress });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: { ...progress, sequence: 2 } });

    expect(state.eventsByRunId[run.runId]).toHaveLength(1);
    expect(state.eventsByRunId[run.runId][0].eventId).toBe('runtime-fact-1');
  });

  it('保留 Image Runtime 阶段事实但不把图片正文、上游 URL、prompt 或 locator 写入 Artifact 状态', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    const image = makeEvent(1, 'ARTIFACT_CREATED', {
      artifactId: 'image-1', reference: 'artifact:image-1', name: 'generated.png', artifactType: 'image',
      mimeType: 'image/png', visibility: 'VISIBLE', accessState: 'AVAILABLE', previewSupported: true,
      downloadSupported: true, preview: { kind: 'image', schemaVersion: 'v1' },
      prompt: 'secret prompt', storageKey: 'private/key', storageRef: 'private/ref', url: 'https://upstream.example/image',
      base64: 'data:image/png;base64,secret', content: 'image bytes',
    } as never);
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: image });

    const stored = state.artifactsById['image-1'];
    expect(stored.preview).toEqual({ kind: 'image', schemaVersion: 'v1' });
    expect(JSON.stringify(stored)).not.toContain('secret prompt');
    expect(JSON.stringify(stored)).not.toContain('private/key');
    expect(JSON.stringify(stored)).not.toContain('upstream.example');
    expect(JSON.stringify(stored)).not.toContain('base64');
  });

  it('安全接收 TOOL_STARTED 并丢弃扩展字段', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    const started = makeEvent(1, 'TOOL_STARTED', {
      toolInvocationId: ' tool-1 ',
      toolName: ' skill_read_tool ',
      status: 'RUNNING',
      association: 'PERSISTED',
      argumentsSummary: ' path=/workspace/report.md ',
      storageKey: 'private/object',
      requestPayload: { secret: 'must-not-survive' },
    } as never);

    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: started });

    expect(state.eventsByRunId[run.runId][0].payload).toEqual({
      toolInvocationId: 'tool-1',
      toolName: 'skill_read_tool',
      status: 'RUNNING',
      association: 'PERSISTED',
      argumentsSummary: 'path=/workspace/report.md',
    });
    expect(JSON.stringify(state.eventsByRunId[run.runId][0])).not.toContain('must-not-survive');
    expect(JSON.stringify(state.eventsByRunId[run.runId][0])).not.toContain('private/object');
  });

  it('拒绝结构无效的 TOOL_STARTED 并标记数据不完整', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    state = runtimeReducer(state, {
      type: 'EVENT_RECEIVED',
      event: makeEvent(1, 'TOOL_STARTED', { toolInvocationId: 'tool-1', toolName: 'skill_read_tool', status: 'SUCCEEDED' } as never),
    });

    expect(state.eventsByRunId[run.runId]).toEqual([]);
    expect(state.runsById[run.runId].dataIncomplete).toBe(true);
    expect(state.issues.at(-1)?.message).toContain('工具开始事件');
  });

  it('仅在 Live 中按工具调用归并 Report 草稿，并在终态或回放时清除', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    const chunk = (sequence: number, chunkIndex: number, content: string) => makeEvent(sequence, 'REPORT_CONTENT_DELTA', {
      schemaVersion: 'report.content_delta.v1', toolInvocationId: 'report-1', toolName: 'report_tool', outputType: 'html', chunkIndex, content,
    });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: chunk(1, 1, '<h1>') });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: chunk(2, 1, '重复') });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: chunk(3, 2, '报告</h1>') });

    expect(state.reportDraftsByToolInvocationId['report-1']).toMatchObject({ outputType: 'html', lastChunkIndex: 2, content: '<h1>报告</h1>' });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: makeEvent(4, 'RUN_COMPLETED', { status: 'COMPLETED', summary: '完成' }) });
    expect(state.reportDraftsByToolInvocationId).toEqual({});

    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: chunk(5, 3, '终态后不可见') });
    state = runtimeReducer(state, { type: 'REPLAY_LOADED', run: { ...run, status: 'COMPLETED' }, events: [] });
    expect(state.reportDraftsByToolInvocationId).toEqual({});
  });

  it('断线时丢弃未持久化的 Report 草稿', () => {
    let state = runtimeReducer(createInitialState(), { type: 'RUN_CREATED', run });
    state = runtimeReducer(state, { type: 'EVENT_RECEIVED', event: makeEvent(1, 'REPORT_CONTENT_DELTA', {
      schemaVersion: 'report.content_delta.v1', toolInvocationId: 'report-1', toolName: 'report_tool', outputType: 'markdown', chunkIndex: 1, content: '草稿',
    }) });

    state = runtimeReducer(state, { type: 'STREAM_STATE_CHANGED', state: 'ERROR', message: '连接中断' });

    expect(state.reportDraftsByToolInvocationId).toEqual({});
  });
});

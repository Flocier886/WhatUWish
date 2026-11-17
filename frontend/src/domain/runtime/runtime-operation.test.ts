import { describe, expect, it } from 'vitest';
import type { ArtifactReference, RuntimeEvent, RuntimeEventType, RuntimePayload, ToolProgressPayload } from './types';
import { projectRuntimeOperations } from './runtime-operation';

const base = {
  schemaVersion: 'v1' as const,
  sessionId: 'session-1',
  runId: 'run-1',
  requestId: 'request-1',
  agentId: 'agent-1',
};

function event<TPayload extends RuntimePayload>(
  sequence: number,
  eventType: RuntimeEventType,
  payload: TPayload,
  eventId = `event-${sequence}`,
): RuntimeEvent<TPayload> {
  return { ...base, eventId, sequence, eventType, occurredAt: `2026-08-16T00:00:${String(sequence).padStart(2, '0')}Z`, payload };
}

function progress(overrides: Partial<ToolProgressPayload> = {}): ToolProgressPayload {
  return {
    runtimeEventId: 'progress-2',
    toolInvocationId: 'tool-1',
    toolName: 'deep_search_tool',
    stage: 'search_completed',
    status: 'completed',
    message: '搜索完成',
    presentationKind: 'deep_search.search_results.v1',
    presentationSchemaVersion: '1',
    presentationData: { sources: [{ title: '公开文档', domain: 'example.com', url: 'https://example.com/docs', rawBody: 'private body' }] },
    ...overrides,
  };
}

describe('projectRuntimeOperations', () => {
  it('Canvas 发布的版本化入口在 Live 与 Replay 保留标题和预览选择', () => {
    const result = event(1, 'TOOL_RESULT', {
      toolInvocationId: 'canvas-call', toolName: 'canvas_publish', status: 'SUCCEEDED', observation: '已发布',
      artifactIds: ['artifact-canvas'],
      presentation: { kind: 'canvas.publish.v1', schemaVersion: '1',
        data: { artifactId: 'artifact-canvas', title: '中文页面', openInPanel: false } },
    });
    const live = projectRuntimeOperations([result])[0];
    const replay = projectRuntimeOperations([{ ...result, eventId: 'history-canvas' }])[0];
    expect(live.canvasPresentation).toEqual(replay.canvasPresentation);
    expect(live.canvasPresentation?.data).toEqual({ artifactId: 'artifact-canvas', title: '中文页面', openInPanel: false });
    expect(live.artifactIds).toEqual(['artifact-canvas']);
  });

  it('将 Started、Progress、Result 和 Artifact 聚合为同一个 Operation', () => {
    const events: RuntimeEvent[] = [
      event(1, 'TOOL_STARTED', { toolInvocationId: 'tool-1', toolCallId: 'call-1', toolName: 'deep_search_tool', status: 'RUNNING', association: 'PERSISTED', argumentsSummary: 'AI 文件服务' }),
      event(2, 'TOOL_PROGRESS', progress(), 'progress-2'),
      event(3, 'TOOL_RESULT', { toolInvocationId: 'tool-1', toolName: 'deep_search_tool', status: 'PARTIAL_SUCCESS', observation: '已形成可用结论', artifactIds: ['artifact-1'] }),
    ];
    const artifacts: Record<string, ArtifactReference> = {
      'artifact-1': { artifactId: 'artifact-1', reference: 'artifact:report', name: '研究报告.md', artifactType: 'report', visibility: 'VISIBLE', sourceToolInvocationId: 'tool-1' },
    };

    const [operation] = projectRuntimeOperations(events, artifacts);

    expect(operation).toMatchObject({
      operationId: 'tool-1', toolCallId: 'call-1', toolName: 'deep_search_tool', state: 'PARTIAL_SUCCESS',
      lifecycleStartMissing: false, argumentsSummary: 'AI 文件服务', observation: '已形成可用结论', isRich: true,
      artifactIds: ['artifact-1'],
    });
    expect(operation.stages).toHaveLength(1);
    expect(operation.stages[0]).toMatchObject({ label: '搜索完成', summary: '1 条来源' });
    expect(operation.stages[0].details[0]).toMatchObject({ href: 'https://example.com/docs', label: '公开文档' });
    expect(JSON.stringify(operation)).not.toContain('private body');
  });

  it('兼容缺少 Started、乱序输入和重复事件且不伪造 startedAt', () => {
    const result = event(3, 'TOOL_RESULT', { toolInvocationId: 'tool-1', toolName: 'skill_read_tool', status: 'SUCCEEDED', observation: '读取完成' });
    const projected = projectRuntimeOperations([result, event(2, 'TOOL_PROGRESS', progress(), 'progress-2'), { ...result }]);

    expect(projected).toHaveLength(1);
    expect(projected[0]).toMatchObject({ state: 'SUCCEEDED', lifecycleStartMissing: true });
    expect(projected[0]).not.toHaveProperty('startedAt');
    expect(projected[0].stages).toHaveLength(1);
  });

  it('按 adapter 的 replace 与 append 策略合并阶段', () => {
    const events: RuntimeEvent[] = [
      event(1, 'TOOL_PROGRESS', progress({ runtimeEventId: 'progress-1', presentationData: { sources: [] } }), 'progress-1'),
      event(2, 'TOOL_PROGRESS', progress({ runtimeEventId: 'progress-2', presentationData: { sources: [{ title: 'A', domain: 'a.example' }] } }), 'progress-2'),
      event(3, 'TOOL_PROGRESS', progress({ runtimeEventId: 'evidence-1', presentationKind: 'deep_search.evidence_summary.v1', stage: 'evidence', presentationData: { evidenceCount: 1, evidence: [] } }), 'evidence-1'),
      event(4, 'TOOL_PROGRESS', progress({ runtimeEventId: 'evidence-2', presentationKind: 'deep_search.evidence_summary.v1', stage: 'evidence', presentationData: { evidenceCount: 2, evidence: [] } }), 'evidence-2'),
    ];

    const [operation] = projectRuntimeOperations(events);

    expect(operation.stages.map((stage) => stage.eventId)).toEqual(['progress-2', 'evidence-1', 'evidence-2']);
  });

  it('为未知 kind/version 保留受控阶段但不输出原始 JSON', () => {
    const [operation] = projectRuntimeOperations([
      event(1, 'TOOL_PROGRESS', progress({
        runtimeEventId: 'unknown-1', presentationKind: 'future.private.v9', presentationSchemaVersion: '9',
        presentationData: { storageKey: 'private-key', rawBody: 'secret body' },
      }), 'unknown-1'),
    ]);

    expect(operation.stages[0]).toMatchObject({ unavailable: true, summary: '该过程详情暂不可展示' });
    expect(JSON.stringify(operation)).not.toContain('private-key');
    expect(JSON.stringify(operation)).not.toContain('secret body');
  });

  it('Rich adapter 只为 http/https 来源建立链接', () => {
    const [operation] = projectRuntimeOperations([
      event(1, 'TOOL_PROGRESS', progress({ presentationData: { sources: [
        { title: '安全来源', domain: 'example.com', url: 'https://example.com/a' },
        { title: '危险来源', domain: 'local', url: 'javascript:alert(1)' },
      ] } }), 'progress-safe-link'),
    ]);
    expect(operation.stages[0].details[0].href).toBe('https://example.com/a');
    expect(operation.stages[0].details[1].href).toBeUndefined();
  });

  it('将 DeepSearch 完成原因、证据结果与限制翻译为受控详情', () => {
    const [operation] = projectRuntimeOperations([
      event(1, 'TOOL_PROGRESS', progress({
        runtimeEventId: 'completed-1',
        presentationKind: 'deep_search.completed.v1',
        stage: 'research_completed',
        status: 'completed',
        presentationData: {
          summary: '已形成保守结论',
          researchOutcome: 'evidence_limited',
          stopReason: 'coverage_incomplete',
          evidenceCount: 3,
          limitations: ['部分问题只有间接证据'],
        },
      }), 'completed-1'),
    ]);

    expect(operation.stages[0]).toMatchObject({ label: '研究完成', summary: '已形成保守结论' });
    expect(operation.stages[0].details).toEqual(expect.arrayContaining([
      { label: '研究结果', value: '证据有限' },
      { label: '完成原因', value: '仍有问题未被充分覆盖' },
      { label: '限制 1', value: '部分问题只有间接证据' },
    ]));
  });

  it('Artifact 晚到时去重，终态后的 Progress 不让状态回退', () => {
    const events: RuntimeEvent[] = [
      event(1, 'TOOL_RESULT', { toolInvocationId: 'tool-1', toolName: 'deep_search_tool', status: 'FAILED', observation: '部分过程已保留', artifactIds: ['artifact-1'], errorMessage: '抓取失败' }),
      event(2, 'TOOL_PROGRESS', progress({ runtimeEventId: 'late-progress', artifactId: 'artifact-1' }), 'late-progress'),
    ];
    const artifacts: Record<string, ArtifactReference> = {
      'artifact-1': { artifactId: 'artifact-1', reference: 'artifact:report', name: '报告.md', artifactType: 'report', visibility: 'VISIBLE', sourceToolInvocationId: 'tool-1' },
    };

    const [operation] = projectRuntimeOperations(events, artifacts);

    expect(operation.state).toBe('FAILED');
    expect(operation.artifactIds).toEqual(['artifact-1']);
    expect(operation.stages).toHaveLength(1);
  });

  it('Live 累积事实与同一组 Replay 事实得到等价投影', () => {
    const events: RuntimeEvent[] = [
      event(1, 'TOOL_STARTED', { toolInvocationId: 'tool-1', toolName: 'deep_search_tool', status: 'RUNNING' }),
      event(2, 'TOOL_PROGRESS', progress(), 'progress-2'),
      event(3, 'TOOL_RESULT', { toolInvocationId: 'tool-1', toolName: 'deep_search_tool', status: 'SUCCEEDED', observation: '完成' }),
    ];

    const live = events.reduce<RuntimeEvent[]>((accumulated, current) => [...accumulated, current], []);

    expect(projectRuntimeOperations(live)).toEqual(projectRuntimeOperations([...events].reverse()));
  });

  it('优先按 dispatchOrder 排列并在旧回放缺失时回退首次事件 sequence', () => {
    const events: RuntimeEvent[] = [
      event(1, 'TOOL_STARTED', { toolInvocationId: 'tool-second', toolName: 'slow_tool', status: 'RUNNING', dispatchOrder: 1 }),
      event(2, 'TOOL_RESULT', { toolInvocationId: 'tool-second', toolName: 'slow_tool', status: 'SUCCEEDED', observation: '后发先完成', dispatchOrder: 1 }),
      event(3, 'TOOL_STARTED', { toolInvocationId: 'tool-first', toolName: 'fast_tool', status: 'RUNNING', dispatchOrder: 0 }),
      event(4, 'TOOL_RESULT', { toolInvocationId: 'tool-first', toolName: 'fast_tool', status: 'SUCCEEDED', observation: '先发后完成', dispatchOrder: 0 }),
      event(5, 'TOOL_STARTED', { toolInvocationId: 'legacy-tool', toolName: 'legacy_tool', status: 'RUNNING' }),
    ];

    const projected = projectRuntimeOperations(events);

    expect(projected.map((operation) => operation.operationId)).toEqual(['tool-first', 'tool-second', 'legacy-tool']);
    expect(projected[0].dispatchOrder).toBe(0);
    expect(projected[1].dispatchOrder).toBe(1);
    expect(projected[2].dispatchOrder).toBeUndefined();
  });
});

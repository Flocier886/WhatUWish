import type { AgentSummary, RunReplay, RunSummary, RuntimeEvent, RuntimeEventType } from '@/domain/runtime/types';

export const DEMO_SESSION_ID = 'session-demo-001';

export const demoAgents: AgentSummary[] = [
  { agentId: 'research-react-agent', name: '研究分析 Agent', description: '使用 ReAct 和已注册工具检索、归纳并输出可追溯结论。', strategy: 'REACT', availability: 'AVAILABLE' },
  { agentId: 'course-ops-agent', name: '课程运营 Agent', description: '整理课程反馈、识别风险并生成行动建议。', strategy: 'REACT', availability: 'AVAILABLE' },
  { agentId: 'legacy-flow-agent', name: '旧版流程 Agent', description: '保留用于兼容旧工作流；不提供完整 ReAct 时间线。', strategy: 'LEGACY', availability: 'AVAILABLE' },
  { agentId: 'offline-agent', name: '离线 Agent', description: '当前不可执行，用于展示不可用状态。', strategy: 'REACT', availability: 'UNAVAILABLE' },
];

const iso = (offsetSeconds: number) => new Date(Date.UTC(2026, 6, 23, 2, 0, offsetSeconds)).toISOString();

function event<T extends RuntimeEvent['payload']>(
  run: Pick<RunSummary, 'runId' | 'sessionId' | 'requestId' | 'agentId'>,
  sequence: number,
  eventType: RuntimeEventType,
  payload: T,
): RuntimeEvent<T> {
  return {
    schemaVersion: 'v1', eventId: `evt-${run.runId}-${sequence}`, sequence, eventType,
    occurredAt: iso(sequence), sessionId: run.sessionId, runId: run.runId,
    requestId: run.requestId, agentId: run.agentId, payload,
  };
}

export const historyRuns: RunSummary[] = Array.from({ length: 10 }, (_, index) => {
  const number = index + 1;
  const failed = number === 4 || number === 8;
  const runId = number === 1 ? 'run-history-success' : number === 4 ? 'run-history-failed' : `run-history-${number}`;
  const task = [
    '汇总本周课程反馈，列出行动建议。',
    '根据已汇总的问题，给出下周课程的调整方案。',
    '把行动项整理成课程运营复盘摘要。',
    '检查反馈来源是否足以支持本次分析。',
  ][index] ?? `继续完善课程反馈复盘（第 ${number} 轮）。`;
  const summary = [
    '已完成反馈汇总，并生成三项优先行动建议。',
    '已给出下周课程的节奏、作业与案例调整建议。',
    '已整理出便于跟进的课程运营复盘摘要。',
  ][index] ?? `已补充第 ${number} 轮课程反馈复盘结论。`;
  return {
    runId,
    sessionId: number === 5 ? 'session-demo-003' : DEMO_SESSION_ID,
    requestId: `request-history-${number}`,
    agentId: number % 3 === 0 ? 'legacy-flow-agent' : 'research-react-agent',
    task,
    status: failed ? 'FAILED' : 'COMPLETED',
    startedAt: iso(-number * 15),
    finishedAt: iso(-number * 15 + 8),
    summary: failed ? undefined : summary,
    error: failed ? { code: 'tool_timeout', message: '外部资料检索超时，未能完成本次分析。' } : undefined,
    isHistorical: true,
  };
});

const historySuccess = historyRuns[0];
const historyFailure = historyRuns[3];

export const historySuccessReplay: RunReplay = {
  run: historySuccess,
  events: [
    event(historySuccess, 1, 'RUN_STARTED', { status: 'RUNNING', task: historySuccess.task }),
    event(historySuccess, 2, 'LLM_RESPONSE', { llmInvocationId: 'llm-history-1', stepNo: 1, model: 'gpt-5', scene: 'react-think', text: '我会先读取反馈目录，再归纳反复出现的问题。' }),
    event(historySuccess, 3, 'TOOL_RESULT', { toolInvocationId: 'tool-history-1', toolName: 'skill_grep_tool', status: 'SUCCEEDED', association: 'PERSISTED', argumentsSummary: 'pattern=满意度|节奏|作业', observation: '在 48 条反馈中发现 19 条提到节奏偏快，12 条提到作业反馈延迟。', artifactIds: ['artifact-feedback-summary'] }),
    event(historySuccess, 4, 'ARTIFACT_CREATED', { artifactId: 'artifact-feedback-summary', reference: 'artifact:feedback-summary-july', name: '课程反馈聚合表.csv', artifactType: 'dataset', mimeType: 'text/csv', size: 2840, summary: '按主题统计的 48 条课程反馈。', visibility: 'VISIBLE', sourceToolInvocationId: 'tool-history-1' }),
    event(historySuccess, 5, 'LLM_RESPONSE', { llmInvocationId: 'llm-history-2', stepNo: 2, model: 'gpt-5', scene: 'react-think', text: '反馈集中在节奏、作业反馈和案例关联度。我将输出可立即执行的优先级建议。' }),
    event(historySuccess, 6, 'RUN_COMPLETED', { status: 'COMPLETED', summary: '已完成反馈汇总。建议：1. 将高密度模块拆分为两次课；2. 为作业建立 48 小时反馈承诺；3. 每周加入一个真实案例复盘。', artifactIds: ['artifact-feedback-summary'], artifactSelection: 'explicit' }),
  ],
};

export const historyFailureReplay: RunReplay = {
  run: historyFailure,
  events: [
    event(historyFailure, 1, 'RUN_STARTED', { status: 'RUNNING', task: historyFailure.task }),
    event(historyFailure, 2, 'LLM_RESPONSE', { llmInvocationId: 'llm-history-fail-1', stepNo: 1, model: 'gpt-5', scene: 'react-think', text: '我需要先检索本次任务的来源资料。' }),
    event(historyFailure, 3, 'TOOL_RESULT', { toolInvocationId: 'tool-history-fail-1', toolName: 'skill_read_tool', status: 'FAILED', association: 'PERSISTED', observation: '等待来源服务响应超过 20 秒，未取得可供分析的内容。', errorCode: 'tool_timeout', errorMessage: '外部资料检索超时。' }),
    event(historyFailure, 4, 'RUN_FAILED', { status: 'FAILED', errorCode: 'tool_timeout', errorMessage: '外部资料检索超时，未能完成本次分析。' }),
  ],
};

export function createLiveReplay(run: RunSummary, shouldFail: boolean): RunReplay {
  const base: RuntimeEvent[] = [
    event(run, 1, 'RUN_STARTED', { status: 'RUNNING', task: run.task }),
    event(run, 2, 'LLM_RESPONSE', { llmInvocationId: `llm-${run.runId}-1`, stepNo: 1, model: 'gpt-5', scene: 'react-think', text: '我会拆分任务并调用合适的工具获取可验证的事实。' }),
    event(run, 3, 'TOOL_RESULT', { toolInvocationId: `tool-${run.runId}-1`, toolName: 'skill_list_directory_tool', status: 'SUCCEEDED', association: 'PERSISTED', argumentsSummary: 'path=/workspace/feedback', observation: '已找到 3 份可用输入：feedback.csv、course-notes.md、action-log.md。' }),
    event(run, 4, 'LLM_RESPONSE', { llmInvocationId: `llm-${run.runId}-2`, stepNo: 2, model: 'gpt-5', scene: 'react-think', text: '资料可用。我会聚合其中的主题，并保留可复用的数据产物引用。' }),
  ];
  if (shouldFail) {
    return { run, events: [...base, event(run, 5, 'TOOL_RESULT', { toolInvocationId: `tool-${run.runId}-2`, toolName: 'skill_script_runner_tool', status: 'FAILED', association: 'PERSISTED', argumentsSummary: 'script=aggregate_feedback.py', observation: '脚本读取到不完整的输入，无法安全生成聚合报告。', errorCode: 'invalid_input', errorMessage: '输入字段缺失。' }), event(run, 6, 'RUN_FAILED', { status: 'FAILED', errorCode: 'invalid_input', errorMessage: '输入字段缺失，运行已停止。' })] };
  }
  return { run, events: [...base,
    event(run, 5, 'ARTIFACT_CREATED', { artifactId: `artifact-${run.runId}`, reference: `artifact:run-report-${run.runId}`, name: '反馈行动建议.md', artifactType: 'report', mimeType: 'text/markdown', size: 1638, summary: '基于课程反馈生成的三项行动建议。', visibility: 'VISIBLE', sourceToolInvocationId: `tool-${run.runId}-1` }),
    event(run, 6, 'TOOL_RESULT', { toolInvocationId: `tool-${run.runId}-2`, toolName: 'skill_script_runner_tool', status: 'SUCCEEDED', association: 'PERSISTED', argumentsSummary: 'script=aggregate_feedback.py', observation: '已生成按主题归类的行动建议，并登记为可复用报告。', artifactIds: [`artifact-${run.runId}`] }),
    event(run, 7, 'LLM_RESPONSE', { llmInvocationId: `llm-${run.runId}-3`, stepNo: 3, model: 'gpt-5', scene: 'summary', text: '我已经基于可用证据形成结论，并附上报告引用。' }),
    event(run, 8, 'RUN_COMPLETED', { status: 'COMPLETED', summary: '分析完成。优先处理高频反馈主题，指定负责人和完成时限，并在下一次课程后复核结果。', artifactIds: [`artifact-${run.runId}`], artifactSelection: 'explicit' }),
  ] };
}

export const replayFixtures: Record<string, RunReplay> = {
  [historySuccess.runId]: historySuccessReplay,
  [historyFailure.runId]: historyFailureReplay,
};

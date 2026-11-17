import type { ToolProgressPayload } from './types';
import { runtimeStageStatusLabel } from './runtime-operation-copy';

export type RichStageMergePolicy = 'replace' | 'append';

export interface RichPresentationDetail {
  label: string;
  value: string;
  href?: string;
  description?: string;
}

export interface RichPresentationView {
  label: string;
  summary: string;
  details: RichPresentationDetail[];
  unavailable?: boolean;
}

export interface RichPresentationProjection {
  slotKey: string;
  mergePolicy: RichStageMergePolicy;
  view: RichPresentationView;
}

interface RichPresentationAdapter {
  slotKey: string;
  mergePolicy: RichStageMergePolicy;
  present: (payload: ToolProgressPayload) => RichPresentationView;
}

const string = (value: unknown, fallback = ''): string => typeof value === 'string' ? value : fallback;
const number = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? value : 0;
const records = (value: unknown): Record<string, unknown>[] => Array.isArray(value)
  ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
  : [];
const strings = (value: unknown): string[] => Array.isArray(value)
  ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
  : [];

const stageLabels: Record<string, string> = {
  inputs_resolved: '已解析输入材料',
  generation_started: '开始生成',
  completed: '已完成',
  failed: '未完成',
  analysis_finished: '分析结束，文件登记中',
  research_started: '开始研究',
  plan_started: '制定研究计划',
  iteration_started: '开始新一轮研究',
  search_started: '搜索公开来源',
  search_completed: '搜索完成',
  source_selection_completed: '筛选可用来源',
  fetch_started: '读取来源内容',
  fetch_completed: '来源读取完成',
  evidence_verified: '核验证据',
  coverage_reviewed: '检查证据覆盖',
  artifact_created: '登记研究产物',
  research_completed: '研究完成',
  research_failed: '研究未完成',
  research_cancelled: '研究已取消',
};

const outcomeLabels: Record<string, string> = {
  evidence_sufficient: '证据充分',
  evidence_limited: '证据有限',
  evidence_insufficient: '证据不足',
};

const stopReasonLabels: Record<string, string> = {
  coverage_sufficient: '核心问题已获得充分覆盖',
  coverage_incomplete: '仍有问题未被充分覆盖',
  coverage_unavailable: '当前无法完成证据覆盖检查',
  source_materials_delivered: '已交付公开来源材料',
  no_verified_evidence_budget_exhausted: '检索预算已用尽且没有可核验证据',
  research_budget_exhausted: '研究预算已用尽',
  missing_coverage_gap: '无法确定下一步需要补充的证据',
};

const stageLabel = (stage: string): string => stageLabels[stage] ?? stage.replaceAll('_', ' ');
const outcomeLabel = (value: unknown): string => outcomeLabels[string(value)] ?? string(value, '未提供');
const stopReasonLabel = (value: unknown): string => stopReasonLabels[string(value)] ?? string(value, '未提供');

const publicHref = (value: unknown): string | undefined => {
  if (typeof value !== 'string') return undefined;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : undefined;
  } catch {
    return undefined;
  }
};

const sources = (data: Record<string, unknown>): string => {
  const sourceItems = records(data.sources);
  return sourceItems.length ? `${sourceItems.length} 条来源` : '未返回可展示的来源';
};

const sourceDetail = (
  item: Record<string, unknown>,
  label: string,
  value: string,
  description = '',
): RichPresentationDetail => {
  const detail: RichPresentationDetail = { label, value };
  const href = publicHref(item.url);
  if (href) detail.href = href;
  if (description) detail.description = description;
  return detail;
};

const adapter = (
  slotKey: string,
  mergePolicy: RichStageMergePolicy,
  present: RichPresentationAdapter['present'],
): RichPresentationAdapter => ({ slotKey, mergePolicy, present });

const adapters = new Map<string, RichPresentationAdapter>([
  ['code_interpreter.step.v1@1', adapter('code-interpreter-steps', 'append', (payload) => ({
    label: payload.stage === 'step' ? `执行步骤 ${number(payload.presentationData.step)}` : stageLabel(payload.stage),
    summary: payload.message,
    details: [
      { label: '状态', value: runtimeStageStatusLabel(payload.status) },
      ...(string(payload.presentationData.thought) ? [{ label: '思路', value: string(payload.presentationData.thought) }] : []),
      ...(string(payload.presentationData.code) ? [{ label: '代码', value: string(payload.presentationData.code) }] : []),
      ...(string(payload.presentationData.observation) ? [{ label: '执行观察', value: string(payload.presentationData.observation) }] : []),
    ],
  }))],
  ['report.progress.v1@1', adapter('report-progress', 'append', (payload) => ({
    label: stageLabel(payload.stage),
    summary: payload.message,
    details: [
      { label: '状态', value: runtimeStageStatusLabel(payload.status) },
      ...(string(payload.presentationData.outputType) ? [{ label: '输出格式', value: string(payload.presentationData.outputType) }] : []),
      ...(typeof payload.presentationData.inputArtifactCount === 'number'
        ? [{ label: '输入材料', value: `${number(payload.presentationData.inputArtifactCount)} 份` }]
        : []),
      ...(typeof payload.presentationData.acceptedArtifactCount === 'number'
        ? [{ label: '可用材料', value: `${number(payload.presentationData.acceptedArtifactCount)} 份` }]
        : []),
      ...(string(payload.presentationData.fileName) ? [{ label: '交付文件', value: string(payload.presentationData.fileName) }] : []),
      ...(string(payload.presentationData.artifactId) ? [{ label: 'Artifact ID', value: string(payload.presentationData.artifactId) }] : []),
    ],
  }))],
  ['image.progress.v1@1', adapter('image-progress', 'append', (payload) => ({
    label: payload.stage === 'artifact_created' ? '图片已登记' : stageLabel(payload.stage),
    summary: payload.message,
    details: [
      { label: '状态', value: runtimeStageStatusLabel(payload.status) },
      ...(string(payload.presentationData.mode) ? [{ label: '生成模式', value: string(payload.presentationData.mode) === 'edits' ? '图生图/编辑' : '文生图' }] : []),
      ...(string(payload.presentationData.size) ? [{ label: '图片尺寸', value: string(payload.presentationData.size) }] : []),
      ...(typeof payload.presentationData.referenceCount === 'number' ? [{ label: '参考图', value: `${number(payload.presentationData.referenceCount)} 张` }] : []),
      ...(typeof payload.presentationData.maskCount === 'number' ? [{ label: '遮罩图', value: `${number(payload.presentationData.maskCount)} 张` }] : []),
      ...(typeof payload.presentationData.generatedCount === 'number' ? [{ label: '已生成', value: `${number(payload.presentationData.generatedCount)} 张` }] : []),
      ...(string(payload.presentationData.fileName) ? [{ label: '交付图片', value: string(payload.presentationData.fileName) }] : []),
      ...(string(payload.presentationData.artifactId) ? [{ label: 'Artifact ID', value: string(payload.presentationData.artifactId) }] : []),
      ...(payload.presentationData.usedFallback === true ? [{ label: '接口兼容', value: '已使用备用接口' }] : []),
    ],
  }))],
  ['deep_search.progress.v1@1', adapter('progress', 'replace', (payload) => ({
    label: stageLabel(payload.stage),
    summary: payload.message,
    details: [{ label: '状态', value: runtimeStageStatusLabel(payload.status) }],
  }))],
  ['deep_search.search_results.v1@1', adapter('search-results', 'replace', (payload) => ({
    label: '搜索完成',
    summary: sources(payload.presentationData),
    details: records(payload.presentationData.sources).map((item) => sourceDetail(
      item,
      string(item.title, '未命名来源'),
      string(item.domain) || string(item.url),
      string(item.snippet),
    )),
  }))],
  ['deep_search.source_selection.v1@1', adapter('source-selection', 'replace', (payload) => ({
    label: '已选择来源',
    summary: sources(payload.presentationData),
    details: records(payload.presentationData.sources).map((item) => sourceDetail(
      item,
      string(item.title, '未命名来源'),
      string(item.reason, '未记录选择理由'),
      string(item.domain),
    )),
  }))],
  ['deep_search.fetch_results.v1@1', adapter('fetch-results', 'replace', (payload) => ({
    label: '抓取完成',
    summary: sources(payload.presentationData),
    details: records(payload.presentationData.sources).map((item) => sourceDetail(
      item,
      string(item.title, '未命名来源'),
      `${runtimeStageStatusLabel(string(item.status, '状态未知'))}，${number(item.contentLength)} 字符`,
      string(item.domain),
    )),
  }))],
  ['deep_search.evidence_summary.v1@1', adapter('evidence-summary', 'append', (payload) => ({
    label: '证据已核验',
    summary: `累计 ${number(payload.presentationData.evidenceCount)} 条证据`,
    details: records(payload.presentationData.evidence).map((item) => sourceDetail(
      item,
      string(item.claim, '未命名论断'),
      runtimeStageStatusLabel(string(item.verification, '未记录验证状态')),
      string(item.title) || string(item.quotePreview),
    )),
  }))],
  ['deep_search.artifact.v1@1', adapter('artifact-registration', 'replace', (payload) => ({
    label: '研究产物已登记',
    summary: string(payload.presentationData.summary, string(payload.presentationData.name, '产物已登记')),
    details: [{ label: '产物类型', value: string(payload.presentationData.artifactType, 'artifact') }],
  }))],
  ['deep_search.completed.v1@1', adapter('completed', 'replace', (payload) => ({
    label: '研究完成',
    summary: string(payload.presentationData.summary, payload.message),
    details: [
      { label: '研究结果', value: outcomeLabel(payload.presentationData.researchOutcome) },
      { label: '来源数量', value: String(number(payload.presentationData.evidenceCount)) },
      { label: '完成原因', value: stopReasonLabel(payload.presentationData.stopReason) },
      ...strings(payload.presentationData.limitations).map((limitation, index) => ({ label: `限制 ${index + 1}`, value: limitation })),
    ],
  }))],
]);

function unavailable(payload: ToolProgressPayload): RichPresentationProjection {
  return {
    slotKey: `unavailable:${payload.presentationKind}:${payload.presentationSchemaVersion}`,
    mergePolicy: 'replace',
    view: {
      label: payload.stage,
      summary: '该过程详情暂不可展示',
      details: [{ label: '阶段状态', value: runtimeStageStatusLabel(payload.status) }],
      unavailable: true,
    },
  };
}

/** 只按后端拥有的 kind/schema 解释受控 Rich 展示协议。 */
export function projectRichPresentation(payload: ToolProgressPayload): RichPresentationProjection {
  const matched = adapters.get(`${payload.presentationKind}@${payload.presentationSchemaVersion}`);
  if (!matched) return unavailable(payload);
  return { slotKey: matched.slotKey, mergePolicy: matched.mergePolicy, view: matched.present(payload) };
}

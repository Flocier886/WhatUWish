import { describe, expect, it } from 'vitest';
import type { ToolProgressPayload } from '@/domain/runtime/types';
import { presentDeepSearchProgress } from './presentation';

const payload = (overrides: Partial<ToolProgressPayload> = {}): ToolProgressPayload => ({
  runtimeEventId: 'fact-1',
  toolInvocationId: 'tool-1',
  toolName: 'deep_search_tool',
  toolSequence: 1,
  stage: 'search_completed',
  status: 'completed',
  message: 'search completed',
  presentationKind: 'deep_search.search_results.v1',
  presentationSchemaVersion: '1',
  presentationData: {
    sources: [{ title: 'OpenAI tools', domain: 'openai.github.io', url: 'https://openai.github.io/tools' }],
  },
  ...overrides,
});

describe('presentDeepSearchProgress', () => {
  it('renders controlled source data without exposing raw payload fields', () => {
    const presentation = presentDeepSearchProgress(payload({ presentationData: {
      sources: [{ title: 'OpenAI tools', domain: 'openai.github.io', rawBody: 'must not render' }],
    } }));

    expect(presentation.label).toBe('搜索完成');
    expect(presentation.details).toEqual([{ label: 'OpenAI tools', value: 'openai.github.io' }]);
    expect(JSON.stringify(presentation)).not.toContain('must not render');
  });

  it('uses a controlled fallback for an unknown schema', () => {
    const presentation = presentDeepSearchProgress(payload({ presentationSchemaVersion: '2' }));

    expect(presentation.unavailable).toBe(true);
    expect(presentation.summary).toBe('该过程详情暂不可展示');
  });

  it.each([
    ['deep_search.search_results.v1', { sources: [] }, '搜索完成'],
    ['deep_search.source_selection.v1', { sources: [] }, '已选择来源'],
    ['deep_search.fetch_results.v1', { sources: [] }, '抓取完成'],
    ['deep_search.evidence_summary.v1', { evidenceCount: 2, evidence: [] }, '证据已核验'],
    ['deep_search.artifact.v1', { name: '研究证据包', artifactType: 'research_evidence_package' }, '研究产物已登记'],
    ['deep_search.completed.v1', { summary: '研究完成', evidenceCount: 2 }, '研究完成'],
  ])('renders controlled %s data', (presentationKind, presentationData, label) => {
    const presentation = presentDeepSearchProgress(payload({ presentationKind, presentationData }));

    expect(presentation.label).toBe(label);
    expect(presentation.unavailable).toBeUndefined();
  });
});
